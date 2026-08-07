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
import com.google.adk.kt.memory.dto.IngestEventsRequestDto
import com.google.adk.kt.memory.dto.IngestionDirectContentsSourceDto
import com.google.adk.kt.memory.dto.IngestionEventDto
import com.google.adk.kt.memory.dto.MemoryDto
import com.google.adk.kt.memory.dto.MemoryMetadataValueDto
import com.google.adk.kt.memory.dto.RetrieveMemoriesRequestDto
import com.google.adk.kt.memory.dto.SimilaritySearchParamsDto
import com.google.adk.kt.serialization.adkJson
import com.google.adk.kt.serialization.anyToJsonElement
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.auth.oauth2.GoogleCredentials
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import java.time.Instant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement

/**
 * A [MemoryService] backed by Vertex AI Agent Engine Memory Bank.
 *
 * This is a Kotlin port of the Python ADK `VertexAiMemoryBankService`. Memories are scoped by
 * `{app_name, user_id}`; [addSessionToMemory] and [addEventsToMemory] send event content for
 * server-side memory generation, [addMemory] writes explicit memories (directly, or via generation
 * when consolidation is requested), and [searchMemory] retrieves memories by semantic similarity.
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
   * @param credentials Credentials for the Vertex AI API; defaults to application-default
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
    addEventsRouted(session.key.appName, session.key.userId, session.events, customMetadata = null)
  }

  /**
   * Adds [events] for memory generation. Uses `memories:ingestEvents` by default; if
   * [customMetadata] carries a key only `memories:generate` accepts (e.g. `ttl`, `metadata`,
   * `disable_consolidation`), the generate path is used. See [GENERATE_ONLY_KEYS] for the keys.
   */
  override suspend fun addEventsToMemory(
    appName: String,
    userId: String,
    events: List<Event>,
    sessionId: String?,
    customMetadata: Map<String, Any?>?,
  ) {
    // sessionId is unused (Memory Bank scopes by app/user), matching the Python implementation.
    addEventsRouted(appName, userId, events, customMetadata)
  }

  /**
   * Writes [memories] as explicit facts, or consolidates them server-side via generate when
   * `enable_consolidation` is set in [customMetadata] (the generate config keys in [customMetadata]
   * then apply). Only each entry's text is persisted; per-entry `author`, `timestamp`, and
   * `customMetadata` are not written (Python 2.0 maps them to revision labels and merged metadata).
   */
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
      val config = buildGenerateConfig(customMetadata)
      val facts = memories.map { memoryEntryToFact(it) }
      for (batch in facts.chunked(MAX_DIRECT_MEMORIES_PER_GENERATE_CALL)) {
        client
          .generateMemories(
            generateRequest(
              scope = scope,
              config = config,
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
        if (fact.isEmpty()) {
          logger.warn { "Skipping a retrieved memory with no fact." }
          return@mapNotNull null
        }
        MemoryEntry(
          author = MEMORY_AUTHOR,
          content = Content(role = Role.USER, parts = listOf(Part(text = fact))),
          timestamp = retrieved.memory?.updateTime,
        )
      }
    return SearchMemoryResponse(memories = memories)
  }

  /** Routes event ingestion to `memories:generate` or the default `memories:ingestEvents`. */
  private suspend fun addEventsRouted(
    appName: String,
    userId: String,
    events: List<Event>,
    customMetadata: Map<String, Any?>?,
  ) {
    if (shouldUseGenerate(customMetadata)) {
      generateFromEvents(appName, userId, events, customMetadata)
    } else {
      ingestFromEvents(appName, userId, events, customMetadata)
    }
  }

  /** Default path: buffer events into a stream for asynchronous, server-side memory generation. */
  private suspend fun ingestFromEvents(
    appName: String,
    userId: String,
    events: List<Event>,
    customMetadata: Map<String, Any?>?,
  ) {
    val directEvents = events.mapNotNull { event ->
      val content = event.content
      if (shouldFilterOutEvent(content)) {
        null
      } else {
        IngestionEventDto(
          content = encodeContentToWire(content!!),
          eventId = event.id,
          eventTime = Instant.ofEpochMilli(event.timestamp).toString(),
        )
      }
    }
    val streamId = requireString(customMetadata, STREAM_ID_KEY)
    val forceFlush = requireBoolean(customMetadata, FORCE_FLUSH_KEY)
    val triggerConfig = requireTriggerConfig(customMetadata)
    // A no-event request is still valid when it carries stream controls (e.g. a trigger update).
    if (directEvents.isEmpty() && streamId == null && forceFlush == null && triggerConfig == null) {
      logger.info { "No events with content to add to memory." }
      return
    }
    client
      .ingestEvents(
        IngestEventsRequestDto(
          scope = scope(appName, userId),
          directContentsSource =
            directEvents.takeIf { it.isNotEmpty() }?.let { IngestionDirectContentsSourceDto(it) },
          streamId = streamId,
          forceFlush = forceFlush,
          generationTriggerConfig = triggerConfig,
        )
      )
      .getOrThrow()
  }

  /** Generate path: extract memories now, honoring the generate config in [customMetadata]. */
  private suspend fun generateFromEvents(
    appName: String,
    userId: String,
    events: List<Event>,
    customMetadata: Map<String, Any?>?,
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
        generateRequest(
          scope = scope(appName, userId),
          config = buildGenerateConfig(customMetadata),
          directContentsSource = DirectContentsSourceDto(events = directEvents),
        )
      )
      .getOrThrow()
  }

  companion object {
    private val logger = LoggerFactory.getLogger(VertexAiMemoryBankService::class)

    private const val MEMORY_AUTHOR = "user"
    private const val ENABLE_CONSOLIDATION_KEY = "enable_consolidation"

    // customMetadata keys understood by memories:ingestEvents.
    private const val STREAM_ID_KEY = "stream_id"
    private const val FORCE_FLUSH_KEY = "force_flush"
    private const val GENERATION_TRIGGER_CONFIG_KEY = "generation_trigger_config"

    // customMetadata keys understood by memories:generate.
    private const val DISABLE_CONSOLIDATION_KEY = "disable_consolidation"
    private const val DISABLE_MEMORY_REVISIONS_KEY = "disable_memory_revisions"
    private const val TTL_KEY = "ttl"
    private const val REVISION_TTL_KEY = "revision_ttl"
    private const val REVISION_EXPIRE_TIME_KEY = "revision_expire_time"
    private const val REVISION_LABELS_KEY = "revision_labels"
    private const val METADATA_KEY = "metadata"
    private const val METADATA_MERGE_STRATEGY_KEY = "metadata_merge_strategy"

    /**
     * Keys that only `memories:generate` accepts. Their presence in `customMetadata` routes event
     * ingestion to generate instead of the default ingest path, mirroring Python's
     * `_should_use_generate_memories`. Unrecognized keys are ignored (they do not force generate).
     */
    private val GENERATE_ONLY_KEYS =
      setOf(
        DISABLE_CONSOLIDATION_KEY,
        DISABLE_MEMORY_REVISIONS_KEY,
        TTL_KEY,
        REVISION_TTL_KEY,
        REVISION_EXPIRE_TIME_KEY,
        REVISION_LABELS_KEY,
        METADATA_KEY,
        METADATA_MERGE_STRATEGY_KEY,
      )

    // Vertex allows at most 5 direct memories per generate request.
    private const val MAX_DIRECT_MEMORIES_PER_GENERATE_CALL = 5

    private fun scope(appName: String, userId: String): Map<String, String> =
      mapOf("app_name" to appName, "user_id" to userId)

    private fun shouldUseGenerate(customMetadata: Map<String, Any?>?): Boolean =
      customMetadata?.keys?.any { it in GENERATE_ONLY_KEYS } ?: false

    /** The optional `memories:generate` config parsed from `customMetadata`. */
    private class GenerateConfig(
      val disableConsolidation: Boolean? = null,
      val disableMemoryRevisions: Boolean? = null,
      val revisionTtl: String? = null,
      val revisionExpireTime: String? = null,
      val revisionLabels: Map<String, String>? = null,
      val metadata: Map<String, MemoryMetadataValueDto>? = null,
      val metadataMergeStrategy: String? = null,
    )

    private fun generateRequest(
      scope: Map<String, String>,
      config: GenerateConfig,
      directContentsSource: DirectContentsSourceDto? = null,
      directMemoriesSource: DirectMemoriesSourceDto? = null,
    ): GenerateMemoriesRequestDto =
      GenerateMemoriesRequestDto(
        scope = scope,
        directContentsSource = directContentsSource,
        directMemoriesSource = directMemoriesSource,
        disableConsolidation = config.disableConsolidation,
        revisionLabels = config.revisionLabels,
        revisionTtl = config.revisionTtl,
        revisionExpireTime = config.revisionExpireTime,
        disableMemoryRevisions = config.disableMemoryRevisions,
        metadata = config.metadata,
        metadataMergeStrategy = config.metadataMergeStrategy,
      )

    /**
     * Parses the `memories:generate` config keys from [customMetadata]. `ttl` is an alias for
     * `revision_ttl` (an explicit `revision_ttl` wins). Values are strictly type-checked; a
     * present-but-wrong-typed value fails fast. Unrecognized keys are ignored.
     */
    private fun buildGenerateConfig(customMetadata: Map<String, Any?>?): GenerateConfig {
      if (customMetadata.isNullOrEmpty()) return GenerateConfig()
      val revisionTtl =
        requireString(customMetadata, REVISION_TTL_KEY) ?: requireString(customMetadata, TTL_KEY)
      return GenerateConfig(
        disableConsolidation = requireBoolean(customMetadata, DISABLE_CONSOLIDATION_KEY),
        disableMemoryRevisions = requireBoolean(customMetadata, DISABLE_MEMORY_REVISIONS_KEY),
        revisionTtl = revisionTtl,
        revisionExpireTime = requireString(customMetadata, REVISION_EXPIRE_TIME_KEY),
        revisionLabels = requireStringMap(customMetadata, REVISION_LABELS_KEY),
        metadata = buildMetadata(customMetadata[METADATA_KEY]),
        metadataMergeStrategy = requireString(customMetadata, METADATA_MERGE_STRATEGY_KEY),
      )
    }

    /** Converts a caller `metadata` map to wire values; null entries are dropped. */
    private fun buildMetadata(value: Any?): Map<String, MemoryMetadataValueDto>? {
      if (value == null) return null
      require(value is Map<*, *>) { "customMetadata[\"$METADATA_KEY\"] must be a Map." }
      val out = LinkedHashMap<String, MemoryMetadataValueDto>()
      for ((key, entry) in value) {
        require(key is String) { "customMetadata[\"$METADATA_KEY\"] keys must be String." }
        toMetadataValue(entry)?.let { out[key] = it }
      }
      return out.ifEmpty { null }
    }

    private fun toMetadataValue(value: Any?): MemoryMetadataValueDto? =
      when (value) {
        null -> null
        is Boolean -> MemoryMetadataValueDto(boolValue = value)
        is Number -> MemoryMetadataValueDto(doubleValue = value.toDouble())
        is String -> MemoryMetadataValueDto(stringValue = value)
        else -> MemoryMetadataValueDto(stringValue = value.toString())
      }

    private fun requireString(customMetadata: Map<String, Any?>?, key: String): String? {
      val value = customMetadata?.get(key) ?: return null
      require(value is String) { "customMetadata[\"$key\"] must be a String." }
      return value
    }

    private fun requireBoolean(customMetadata: Map<String, Any?>?, key: String): Boolean? {
      val value = customMetadata?.get(key) ?: return null
      require(value is Boolean) { "customMetadata[\"$key\"] must be a Boolean." }
      return value
    }

    private fun requireStringMap(
      customMetadata: Map<String, Any?>?,
      key: String,
    ): Map<String, String>? {
      val value = customMetadata?.get(key) ?: return null
      require(value is Map<*, *>) { "customMetadata[\"$key\"] must be a Map." }
      val out = LinkedHashMap<String, String>()
      for ((k, v) in value) {
        require(k is String && v is String) {
          "customMetadata[\"$key\"] must be a Map<String, String>."
        }
        out[k] = v
      }
      return out
    }

    /** Passes `generation_trigger_config` through as opaque JSON (a nested config object). */
    private fun requireTriggerConfig(customMetadata: Map<String, Any?>?): JsonElement? {
      val value = customMetadata?.get(GENERATION_TRIGGER_CONFIG_KEY) ?: return null
      require(value is Map<*, *> || value is JsonElement) {
        "customMetadata[\"$GENERATION_TRIGGER_CONFIG_KEY\"] must be a Map."
      }
      return anyToJsonElement(value)
    }

    /**
     * Serializes [content] to the genai wire JSON for Memory Bank, keeping only the fields the
     * aiplatform Content proto accepts. Model-internal fields (`thought`, `thoughtSignature`,
     * `partMetadata`) are dropped: the deployed Memory Bank endpoint rejects them, e.g. a model
     * turn's `thoughtSignature` otherwise fails `generate` with HTTP 400 "Unknown name
     * thoughtSignature". `videoMetadata` is kept: it is a long-standing `Content.Part` field.
     */
    private fun encodeContentToWire(content: Content): JsonElement =
      adkJson.encodeToJsonElement(content.copy(parts = content.parts.map(::sanitizePartForWire)))

    /** Reduces a [Part] to the fields the aiplatform Content proto accepts. */
    private fun sanitizePartForWire(part: Part): Part =
      Part(
        text = part.text,
        inlineData = part.inlineData,
        fileData = part.fileData,
        functionCall = part.functionCall,
        functionResponse = part.functionResponse,
        videoMetadata = part.videoMetadata,
      )

    /**
     * Mirrors the Python `_should_filter_out_event`: drop events with no usable content parts.
     *
     * Server-side tool calls are deliberately not counted as usable, unlike in Python:
     * [sanitizePartForWire] strips them, so counting them would send a part with nothing in it.
     */
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
