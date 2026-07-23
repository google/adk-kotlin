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
import com.google.adk.kt.memory.dto.DirectContentsSourceDto
import com.google.adk.kt.memory.dto.DirectContentsSourceEventDto
import com.google.adk.kt.memory.dto.DirectMemoriesSourceDto
import com.google.adk.kt.memory.dto.DirectMemoryDto
import com.google.adk.kt.memory.dto.GenerateMemoriesRequestDto
import com.google.adk.kt.memory.dto.MemoryDto
import com.google.adk.kt.memory.dto.RetrieveMemoriesRequestDto
import com.google.adk.kt.memory.dto.SimilaritySearchParamsDto
import com.google.adk.kt.serialization.adkJson
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.auth.oauth2.GoogleCredentials
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement

/**
 * A [MemoryService] backed by Vertex AI Agent Engine Memory Bank.
 *
 * This is a Kotlin port of the Python ADK `VertexAiMemoryBankService`. Memories are scoped by
 * `{app_name, user_id}`; [addSessionToMemory] and [addEventsToMemory] generate memories from event
 * content, [addMemory] writes explicit memories (directly, or via generation when consolidation is
 * requested), and [searchMemory] retrieves memories by semantic similarity.
 *
 * It follows the existing Vertex service ports: a thin [VertexAiMemoryBankClient] over a shared
 * [GoogleApiClient] handles the REST calls, and `@Serializable` wire DTOs live in the `dto`
 * sub-package.
 */
@OptIn(FrameworkInternalApi::class)
class VertexAiMemoryBankService internal constructor(private val client: VertexAiMemoryBankClient) :
  MemoryService {

  /**
   * Creates a service for Agent Engine [agentEngineId] under [project] and [location].
   *
   * @param project The Google Cloud project id used to address the API; must match
   *   [RESOURCE_SEGMENT_PATTERN].
   * @param location The Google Cloud location; `"global"` selects the global endpoint. Must match
   *   [RESOURCE_SEGMENT_PATTERN].
   * @param agentEngineId The numeric Agent Engine (reasoning engine) id to address, e.g.
   *   `"1234567890"`.
   * @param credentials Credenti8ls for the Vertex AI API; defaults to application-default
   *   credentials scoped for Google Cloud Platform.
   * @param httpClient The underlying ktor [HttpClient].
   */
  constructor(
    project: String,
    location: String,
    agentEngineId: String,
    credentials: GoogleCredentials = GoogleApiClient.defaultCredentials(),
    httpClient: HttpClient = HttpClient(Java),
  ) : this(
    VertexAiMemoryBankClient(
      GoogleApiClient(httpClient, credentials),
      validateSegment(project, "project"),
      validateSegment(location, "location"),
      validateAgentEngineId(agentEngineId),
    )
  )

  override suspend fun addSessionToMemory(session: Session) {
    generateFromEvents(
      session.key.appName,
      session.key.userId,
      session.events,
      disableConsolidation = null,
    )
  }

  override suspend fun addEventsToMemory(
    appName: String,
    userId: String,
    events: List<Event>,
    sessionId: String?,
    customMetadata: Map<String, Any?>?,
  ) {
    // sessionId is unused (Memory Bank scopes by app/user), matching the Python implementation.
    generateFromEvents(
      appName,
      userId,
      events,
      disableConsolidation = customMetadata?.get(DISABLE_CONSOLIDATION_KEY) as? Boolean,
    )
  }

  override suspend fun addMemory(
    appName: String,
    userId: String,
    memories: List<MemoryEntry>,
    customMetadata: Map<String, Any?>?,
  ) {
    require(memories.isNotEmpty()) { "memories must contain at least one entry." }
    val scope = scope(appName, userId)
    if (isConsolidationEnabled(customMetadata)) {
      // Consolidate the provided memories server-side via generate (max 5 direct memories/request).
      val facts = memories.map { memoryEntryToFact(it) }
      for (batch in facts.chunked(MAX_DIRECT_MEMORIES_PER_GENERATE_CALL)) {
        client
          .generateMemories(
            GenerateMemoriesRequestDto(
              scope = scope,
              directMemoriesSource = DirectMemoriesSourceDto(batch.map { DirectMemoryDto(it) }),
            )
          )
          .getOrThrow()
      }
    } else {
      for (memory in memories) {
        client.createMemory(MemoryDto(fact = memoryEntryToFact(memory), scope = scope)).getOrThrow()
      }
    }
  }

  override suspend fun searchMemory(
    appName: String,
    userId: String,
    query: String,
  ): SearchMemoryResponse {
    val response =
      client
        .retrieveMemories(
          RetrieveMemoriesRequestDto(
            scope = scope(appName, userId),
            similaritySearchParams = SimilaritySearchParamsDto(searchQuery = query),
          )
        )
        .getOrThrow()
    val memories =
      response?.retrievedMemories.orEmpty().mapNotNull { retrieved ->
        val fact = retrieved.memory?.fact.orEmpty()
        if (fact.isEmpty()) return@mapNotNull null
        MemoryEntry(
          author = MEMORY_AUTHOR,
          content = Content(role = Role.USER, parts = listOf(Part(text = fact))),
          timestamp = retrieved.memory?.updateTime,
        )
      }
    return SearchMemoryResponse(memories = memories)
  }

  private suspend fun generateFromEvents(
    appName: String,
    userId: String,
    events: List<Event>,
    disableConsolidation: Boolean?,
  ) {
    val directEvents = events.mapNotNull { event ->
      val content = event.content
      if (shouldFilterOutEvent(content)) {
        null
      } else {
        DirectContentsSourceEventDto(content = encodeContentToWire(content!!))
      }
    }
    if (directEvents.isEmpty()) {
      logger.info { "No events with content to add to memory." }
      return
    }
    client
      .generateMemories(
        GenerateMemoriesRequestDto(
          scope = scope(appName, userId),
          directContentsSource = DirectContentsSourceDto(events = directEvents),
          disableConsolidation = disableConsolidation,
        )
      )
      .getOrThrow()
  }

  companion object {
    private val logger = LoggerFactory.getLogger(VertexAiMemoryBankService::class)

    private const val MEMORY_AUTHOR = "user"
    private const val DISABLE_CONSOLIDATION_KEY = "disable_consolidation"
    private const val ENABLE_CONSOLIDATION_KEY = "enable_consolidation"

    // Vertex allows at most 5 direct memories per generate request.
    private const val MAX_DIRECT_MEMORIES_PER_GENERATE_CALL = 5

    private fun scope(appName: String, userId: String): Map<String, String> =
      mapOf("app_name" to appName, "user_id" to userId)

    /**
     * Serializes [content] to the genai wire JSON for Memory Bank, keeping only the content-bearing
     * part fields. Model-internal fields (`thought`, `thoughtSignature`, `videoMetadata`,
     * `partMetadata`) are dropped: the Memory Bank Content proto rejects them, e.g. a model turn's
     * `thoughtSignature` otherwise fails `generate` with HTTP 400 "Unknown name thoughtSignature".
     */
    private fun encodeContentToWire(content: Content): JsonElement =
      adkJson.encodeToJsonElement(content.copy(parts = content.parts.map(::sanitizePartForWire)))

    /** Reduces a [Part] to the content-bearing fields the Memory Bank Content proto accepts. */
    private fun sanitizePartForWire(part: Part): Part =
      Part(
        text = part.text,
        inlineData = part.inlineData,
        fileData = part.fileData,
        functionCall = part.functionCall,
        functionResponse = part.functionResponse,
      )

    /** Mirrors the Python `_should_filter_out_event`: drop events with no usable content parts. */
    private fun shouldFilterOutEvent(content: Content?): Boolean {
      val parts = content?.parts
      if (parts.isNullOrEmpty()) return true
      return parts.none { part ->
        !part.text.isNullOrEmpty() ||
          part.inlineData != null ||
          part.fileData != null ||
          part.functionCall != null ||
          part.functionResponse != null
      }
    }

    /**
     * Builds a memory fact from a [MemoryEntry]'s text, mirroring Python `_memory_entry_to_fact`.
     */
    private fun memoryEntryToFact(memory: MemoryEntry): String {
      val parts = memory.content.parts
      require(parts.any { !it.text.isNullOrEmpty() }) { "each memory must include text." }
      val textParts = mutableListOf<String>()
      for (part in parts) {
        require(part.inlineData == null && part.fileData == null) {
          "each memory must include text only; inline_data and file_data are not supported."
        }
        part.text?.trim()?.takeIf { it.isNotEmpty() }?.let { textParts.add(it) }
      }
      require(textParts.isNotEmpty()) { "each memory must include non-whitespace text." }
      return textParts.joinToString("\n")
    }

    private fun isConsolidationEnabled(customMetadata: Map<String, Any?>?): Boolean {
      val value = customMetadata?.get(ENABLE_CONSOLIDATION_KEY) ?: return false
      require(value is Boolean) {
        "customMetadata[\"$ENABLE_CONSOLIDATION_KEY\"] must be a Boolean."
      }
      return value
    }

    /**
     * Requires the numeric Agent Engine id, mirroring `VertexAiSessionService`. A resource name or
     * any other non-digit input is rejected so project and location stay separate arguments.
     */
    internal fun validateAgentEngineId(agentEngineId: String): String {
      require(agentEngineId.isNotBlank()) { "agentEngineId must not be blank." }
      require(agentEngineId.all { it.isDigit() }) {
        "agentEngineId must be the numeric Agent Engine id (e.g. \"1234567890\"), not a resource" +
          " name; pass project and location as separate arguments. Got: $agentEngineId"
      }
      return agentEngineId
    }

    /**
     * Allowed characters for a project or location. Keeps each value within a single URL path
     * segment (no `/`, `?`, `#`, or `..`), matching the `VertexAiRagMemoryService` and
     * session-service `validateSessionId` allowlist.
     */
    internal val RESOURCE_SEGMENT_PATTERN = Regex("^[a-zA-Z0-9_-]+$")

    /** Requires [value] to match [RESOURCE_SEGMENT_PATTERN] before it goes into a URL path. */
    internal fun validateSegment(value: String, label: String): String {
      require(RESOURCE_SEGMENT_PATTERN.matches(value)) {
        "Invalid $label: '$value'. It must match ${RESOURCE_SEGMENT_PATTERN.pattern}."
      }
      return value
    }
  }
}
