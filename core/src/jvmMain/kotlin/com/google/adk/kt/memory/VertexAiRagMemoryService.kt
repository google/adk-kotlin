/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.adk.kt.memory

import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.events.Event
import com.google.adk.kt.gcp.GoogleApiClient
import com.google.adk.kt.logging.LoggerFactory
import com.google.adk.kt.memory.dto.MemoryRecordDto
import com.google.adk.kt.memory.dto.RagQueryDto
import com.google.adk.kt.memory.dto.RagResourceDto
import com.google.adk.kt.memory.dto.RetrieveContextsRequestDto
import com.google.adk.kt.memory.dto.VertexRagStoreDto
import com.google.adk.kt.serialization.adkJson
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import com.google.auth.oauth2.GoogleCredentials
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import java.util.Base64
import kotlin.math.roundToLong
import kotlin.time.Instant
import kotlinx.serialization.SerializationException

/**
 * A [MemoryService] backed by a Vertex AI RAG corpus.
 *
 * This is a Kotlin port of the Python ADK `VertexAiRagMemoryService`. [addSessionToMemory] uploads
 * a session's text events to the corpus as a single newline-delimited-JSON file whose display name
 * encodes the `app/user/session` scope; [searchMemory] retrieves relevant contexts, keeps only
 * those whose scope matches the requested app and user, and reconstructs the stored events into
 * [MemoryEntry] results.
 *
 * It follows the `VertexAiSessionService` port: a thin [VertexAiRagClient] over a shared
 * [GoogleApiClient] handles the Vertex AI RAG REST calls, and `@Serializable` wire DTOs live in the
 * `dto` sub-package.
 *
 * @property client The [VertexAiRagClient] used to talk to the Vertex AI RAG API.
 * @property corpusName The full corpus resource name
 *   (`projects/{p}/locations/{l}/ragCorpora/{id}`).
 * @property similarityTopK The number of contexts to retrieve, or `null` for the service default.
 * @property vectorDistanceThreshold Only contexts with a vector distance below this threshold are
 *   returned.
 */
@OptIn(FrameworkInternalApi::class)
class VertexAiRagMemoryService
internal constructor(
  private val client: VertexAiRagClient,
  private val corpusName: String,
  private val similarityTopK: Int?,
  private val vectorDistanceThreshold: Double,
) : MemoryService {

  /**
   * Creates a service for the given RAG corpus in [project] and [location].
   *
   * @param project The Google Cloud project. Required and read only from here, never the
   *   environment.
   * @param location The Google Cloud location. Required and read only from here, never the
   *   environment. The special value `"global"` selects the global endpoint.
   * @param ragCorpus The bare corpus id (e.g. `my-corpus`). It is expanded to
   *   `projects/{project}/locations/{location}/ragCorpora/{id}`; a full resource name is rejected.
   * @param similarityTopK The number of contexts to retrieve.
   * @param vectorDistanceThreshold Only return contexts with a vector distance below this
   *   threshold.
   * @param credentials Credentials for the Vertex AI API; defaults to application-default
   *   credentials scoped for Google Cloud Platform.
   * @param httpClient The underlying ktor [HttpClient].
   */
  constructor(
    project: String,
    location: String,
    ragCorpus: String,
    similarityTopK: Int? = null,
    vectorDistanceThreshold: Double = DEFAULT_VECTOR_DISTANCE_THRESHOLD,
    credentials: GoogleCredentials = GoogleApiClient.defaultCredentials(),
    httpClient: HttpClient = HttpClient(Java),
  ) : this(
    client = VertexAiRagClient(GoogleApiClient(httpClient, credentials), project, location),
    corpusName = normalizeCorpusName(ragCorpus, project, location),
    similarityTopK = similarityTopK,
    vectorDistanceThreshold = vectorDistanceThreshold,
  )

  override suspend fun addSessionToMemory(session: Session) {
    val sessionId =
      requireNotNull(session.key.id) { "Session.key.id is required for addSessionToMemory." }
    val content = session.events.mapNotNull { serializeEvent(it) }.joinToString("\n")
    val displayName = buildSourceDisplayName(session.key.appName, session.key.userId, sessionId)
    client.uploadRagFile(corpusName, displayName, content).getOrThrow()
  }

  override suspend fun searchMemory(
    appName: String,
    userId: String,
    query: String,
  ): SearchMemoryResponse {
    val request =
      RetrieveContextsRequestDto(
        vertexRagStore =
          VertexRagStoreDto(
            ragResources = listOf(RagResourceDto(ragCorpus = corpusName)),
            vectorDistanceThreshold = vectorDistanceThreshold,
          ),
        query = RagQueryDto(text = query, similarityTopK = similarityTopK),
      )
    val contexts = client.retrieveContexts(request).getOrThrow()?.contexts?.contexts ?: emptyList()

    // Group the reconstructed events by session id. Each retrieved context becomes one event list;
    // a session can yield several overlapping lists that are merged below.
    val sessionEvents = LinkedHashMap<String, MutableList<List<Event>>>()
    for (context in contexts) {
      val displayName = context.sourceDisplayName ?: continue
      val scope = parseSourceDisplayName(displayName) ?: continue
      // Client-side scope filter: the corpus may hold entries for other apps/users.
      if (scope.appName != appName || scope.userId != userId) continue
      sessionEvents
        .getOrPut(scope.sessionId) { mutableListOf() }
        .add(parseContextText(context.text))
    }

    val memories = mutableListOf<MemoryEntry>()
    for ((_, eventLists) in sessionEvents) {
      for (merged in mergeEventLists(eventLists)) {
        for (event in merged.sortedBy { it.timestamp }) {
          val content = event.content ?: continue
          memories.add(
            MemoryEntry(
              content = content,
              author = event.author,
              timestamp = formatTimestamp(event.timestamp),
            )
          )
        }
      }
    }
    return SearchMemoryResponse(memories = memories)
  }

  /**
   * Serializes one [event] into a single newline-delimited-JSON line, or `null` if the event has no
   * text. Newlines inside a part are flattened to spaces (each event stays on one line) and the
   * event's text parts are joined with `.`, matching the Python wire format.
   */
  private fun serializeEvent(event: Event): String? {
    val textParts =
      event.content
        ?.parts
        .orEmpty()
        .mapNotNull { it.text }
        .filter { it.isNotEmpty() }
        .map { it.replace("\n", " ") }
    if (textParts.isEmpty()) return null
    val record =
      MemoryRecordDto(
        author = event.author,
        timestamp = millisToSeconds(event.timestamp),
        text = textParts.joinToString("."),
      )
    return adkJson.encodeToString(MemoryRecordDto.serializer(), record)
  }

  /** Parses a retrieved context's [text] back into the events it was built from. */
  private fun parseContextText(text: String?): List<Event> {
    if (text.isNullOrEmpty()) return emptyList()
    val events = mutableListOf<Event>()
    for (rawLine in text.split("\n")) {
      val line = rawLine.trim()
      if (line.isEmpty()) continue
      val record =
        try {
          adkJson.decodeFromString(MemoryRecordDto.serializer(), line)
        } catch (e: SerializationException) {
          // Not a valid record line; warn (rather than silently drop data) and skip it.
          logger.warn { "Skipping an unparseable memory record line: ${e.message}" }
          continue
        }
      events.add(
        Event(
          author = record.author ?: "",
          content = Content(parts = listOf(Part(text = record.text))),
          timestamp = secondsToMillis(record.timestamp),
        )
      )
    }
    return events
  }

  companion object {
    private val logger = LoggerFactory.getLogger(VertexAiRagMemoryService::class)

    /** Default matches the Python ADK `VertexAiRagMemoryService`. */
    private const val DEFAULT_VECTOR_DISTANCE_THRESHOLD = 10.0

    /**
     * Versioned prefix for the RAG file display name. Everything after it is three
     * URL-safe-base64-without-padding parts (`app`, `user`, `session`) joined by `.`, so ids that
     * themselves contain `.` survive the round trip.
     */
    private const val SOURCE_DISPLAY_NAME_PREFIX = "adk-memory-v1."

    private val URL_ENCODER = Base64.getUrlEncoder().withoutPadding()
    private val URL_DECODER = Base64.getUrlDecoder()

    private fun millisToSeconds(millis: Long): Double = millis / 1000.0

    private fun secondsToMillis(seconds: Double): Long = (seconds * 1000.0).roundToLong()

    private fun formatTimestamp(millis: Long): String =
      Instant.fromEpochMilliseconds(millis).toString()

    private fun encodeDisplayNamePart(value: String): String =
      URL_ENCODER.encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun decodeDisplayNamePart(value: String): String {
      val padded = value + "=".repeat((4 - value.length % 4) % 4)
      return String(URL_DECODER.decode(padded), Charsets.UTF_8)
    }

    private fun buildSourceDisplayName(appName: String, userId: String, sessionId: String): String =
      SOURCE_DISPLAY_NAME_PREFIX +
        listOf(
            encodeDisplayNamePart(appName),
            encodeDisplayNamePart(userId),
            encodeDisplayNamePart(sessionId),
          )
          .joinToString(".")

    /**
     * Reverses [buildSourceDisplayName], returning `null` when [name] is not a recognized scope
     * key. A legacy dot-delimited form (three raw, unencoded parts) is accepted for back-compat.
     */
    private fun parseSourceDisplayName(name: String): MemoryScope? {
      if (name.startsWith(SOURCE_DISPLAY_NAME_PREFIX)) {
        val parts = name.substring(SOURCE_DISPLAY_NAME_PREFIX.length).split(".")
        if (parts.size != 3) return null
        return try {
          MemoryScope(
            decodeDisplayNamePart(parts[0]),
            decodeDisplayNamePart(parts[1]),
            decodeDisplayNamePart(parts[2]),
          )
        } catch (e: IllegalArgumentException) {
          null
        }
      }
      // Legacy display names were dot-delimited. Only the exact three-part form is unambiguous.
      val parts = name.split(".")
      if (parts.size != 3) return null
      return MemoryScope(parts[0], parts[1], parts[2])
    }

    /**
     * Merges event lists that share at least one timestamp, so overlapping RAG chunks from the same
     * session are combined without duplicating events (dedup is by timestamp). Ports the Python
     * `_merge_event_lists`.
     */
    private fun mergeEventLists(eventLists: List<List<Event>>): List<List<Event>> {
      val merged = mutableListOf<List<Event>>()
      val remaining = ArrayDeque(eventLists)
      while (remaining.isNotEmpty()) {
        val current = remaining.removeFirst().toMutableList()
        val currentTimestamps = current.mapTo(mutableSetOf()) { it.timestamp }
        var mergeFound = true
        while (mergeFound) {
          mergeFound = false
          val stillRemaining = mutableListOf<List<Event>>()
          for (other in remaining) {
            if (other.any { it.timestamp in currentTimestamps }) {
              val newEvents = other.filter { it.timestamp !in currentTimestamps }
              current.addAll(newEvents)
              currentTimestamps.addAll(newEvents.map { it.timestamp })
              mergeFound = true
            } else {
              stillRemaining.add(other)
            }
          }
          remaining.clear()
          remaining.addAll(stillRemaining)
        }
        merged.add(current)
      }
      return merged
    }

    /**
     * Allowed characters for a project, location, or corpus id. Keeps each value within a single
     * URL path segment (no `/`, `?`, `#`, or `..`), matching the session-service
     * `validateSessionId` allowlist.
     */
    private val RESOURCE_SEGMENT_PATTERN = Regex("^[a-zA-Z0-9_-]+$")

    private fun validateSegment(value: String, label: String) {
      require(RESOURCE_SEGMENT_PATTERN.matches(value)) {
        "Invalid $label: '$value'. It must match ${RESOURCE_SEGMENT_PATTERN.pattern}."
      }
    }

    /**
     * Builds the full corpus resource name from a bare [ragCorpus] id under [project]/[location].
     *
     * [ragCorpus] must be a bare id, not a full resource name: the project and location come only
     * from the constructor. Each segment (project, location, corpus id) must match
     * [RESOURCE_SEGMENT_PATTERN] before being interpolated into a request URL.
     */
    internal fun normalizeCorpusName(ragCorpus: String, project: String, location: String): String {
      validateSegment(project, "project")
      validateSegment(location, "location")
      require(!ragCorpus.startsWith("projects/")) {
        "ragCorpus must be a bare corpus id, not a full resource name: '$ragCorpus'."
      }
      validateSegment(ragCorpus, "ragCorpus id")
      return "projects/$project/locations/$location/ragCorpora/$ragCorpus"
    }
  }

  /** The `app/user/session` scope decoded from a RAG file display name. */
  private data class MemoryScope(val appName: String, val userId: String, val sessionId: String)
}
