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

@file:OptIn(FrameworkInternalApi::class)

package com.google.adk.kt.memory.appsearch

import android.content.Context
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.events.Event
import com.google.adk.kt.ids.Uuid
import com.google.adk.kt.memory.MemoryEntry
import com.google.adk.kt.memory.MemoryService
import com.google.adk.kt.memory.SearchMemoryResponse
import com.google.adk.kt.serialization.AnySerializer
import com.google.adk.kt.serialization.adkJson
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.types.Content
import kotlin.time.Instant
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/** Scope separator combining `appName` and `userId` into one AppSearch namespace. */
private const val NAMESPACE_SEPARATOR = "\u001F"

/** Serialized form of an empty custom-metadata map. */
private const val EMPTY_METADATA_JSON = "{}"

/** Serializer for the free-form `customMetadata` map (`Map<String, Any>`). */
private val METADATA_SERIALIZER = MapSerializer(String.serializer(), AnySerializer)

/**
 * A persistent [MemoryService] backed by AndroidX AppSearch in the consumer app's private storage.
 *
 * Ingested events and explicit memories survive process death and reboot, never leave the device,
 * and are retrieved by AppSearch's native full-text relevance ranking. Use this in place of
 * [com.google.adk.kt.memory.InMemoryMemoryService] when constructing a
 * [com.google.adk.kt.runners.Runner] in an Android consumer app:
 * ```kotlin
 * val memoryService = AppSearchMemoryService.fromContext(applicationContext)
 * ```
 *
 * Memory is scoped by `(appName, userId)`: a search never returns another user's memory. All three
 * write paths are supported and each event / explicit memory becomes its own retrievable document.
 *
 * This class holds all behavior and delegates storage to a [MemoryIndex] so it can be unit-tested
 * on the host JVM; [fromContext] wires it to the on-device [LocalStorageMemoryIndex].
 */
class AppSearchMemoryService internal constructor(private val index: MemoryIndex) : MemoryService {

  override suspend fun addSessionToMemory(session: Session) =
    addEventsToMemory(
      session.key.appName,
      session.key.userId,
      session.events,
      sessionId = session.key.id,
      customMetadata = null,
    )

  override suspend fun addEventsToMemory(
    appName: String,
    userId: String,
    events: List<Event>,
    sessionId: String?,
    customMetadata: Map<String, Any?>?,
  ) {
    // `sessionId` is not stored: memories are searched across all sessions, so `searchMemory` never
    // needs it (this matches the other ADK memory services). `customMetadata` is stored on each
    // event and returned later as `MemoryEntry.customMetadata`.
    val namespace = namespaceOf(appName, userId)
    putAll(events.mapNotNull { eventToRecord(namespace, it, customMetadata) })
  }

  override suspend fun addMemory(
    appName: String,
    userId: String,
    memories: List<MemoryEntry>,
    customMetadata: Map<String, Any?>?,
  ) {
    val namespace = namespaceOf(appName, userId)
    putAll(memories.mapNotNull { memoryToRecord(namespace, it, customMetadata) })
  }

  override suspend fun searchMemory(
    appName: String,
    userId: String,
    query: String,
  ): SearchMemoryResponse {
    if (query.isBlank()) return SearchMemoryResponse(memories = emptyList())
    val records = index.search(namespaceOf(appName, userId), query)
    return SearchMemoryResponse(memories = records.map { recordToEntry(it) })
  }

  /** Releases the underlying index resources. Primarily useful for tests. */
  fun close() = index.close()

  private suspend fun putAll(records: List<MemoryRecord>) {
    if (records.isNotEmpty()) index.put(records)
  }

  /** Builds a record for an ingested event, or null if it carries no searchable text. */
  private fun eventToRecord(
    namespace: String,
    event: Event,
    customMetadata: Map<String, Any?>?,
  ): MemoryRecord? {
    val content = event.content ?: return null
    val text = textOf(content)
    if (text.isEmpty()) return null
    return MemoryRecord(
      namespace = namespace,
      id = event.id,
      text = text,
      author = event.author,
      timestamp = formatTimestamp(event.timestamp),
      entryId = null,
      contentJson = adkJson.encodeToString(Content.serializer(), content),
      customMetadataJson = encodeMetadata(mergeMetadata(customMetadata, emptyMap())),
    )
  }

  /** Builds a record for an explicit memory, or null if it carries no searchable text. */
  private fun memoryToRecord(
    namespace: String,
    memory: MemoryEntry,
    customMetadata: Map<String, Any?>?,
  ): MemoryRecord? {
    val text = textOf(memory.content)
    if (text.isEmpty()) return null
    return MemoryRecord(
      namespace = namespace,
      id = memory.id ?: Uuid.random(),
      text = text,
      author = memory.author,
      timestamp = memory.timestamp,
      entryId = memory.id,
      contentJson = adkJson.encodeToString(Content.serializer(), memory.content),
      customMetadataJson = encodeMetadata(mergeMetadata(customMetadata, memory.customMetadata)),
    )
  }

  /**
   * Merges write-level [batch] metadata with per-entry [perEntry] metadata, with per-entry keys
   * taking precedence. Null batch values are dropped (the store holds only non-null values).
   */
  private fun mergeMetadata(
    batch: Map<String, Any?>?,
    perEntry: Map<String, Any>,
  ): Map<String, Any> = buildMap {
    batch?.forEach { (key, value) -> if (value != null) put(key, value) }
    putAll(perEntry)
  }

  private fun encodeMetadata(metadata: Map<String, Any>): String =
    if (metadata.isEmpty()) EMPTY_METADATA_JSON
    else adkJson.encodeToString(METADATA_SERIALIZER, metadata)

  private fun recordToEntry(record: MemoryRecord): MemoryEntry =
    MemoryEntry(
      content = adkJson.decodeFromString(Content.serializer(), record.contentJson),
      id = record.entryId,
      author = record.author,
      timestamp = record.timestamp,
      customMetadata = adkJson.decodeFromString(METADATA_SERIALIZER, record.customMetadataJson),
    )

  private fun namespaceOf(appName: String, userId: String): String {
    require(appName.isNotEmpty() && userId.isNotEmpty()) { "appName and userId must not be empty" }
    // Guards against namespace collisions (e.g. "a$SEP" + "b" vs "a" + "${SEP}b").
    require(NAMESPACE_SEPARATOR !in appName && NAMESPACE_SEPARATOR !in userId) {
      "appName and userId must not contain the namespace separator"
    }
    return "$appName$NAMESPACE_SEPARATOR$userId"
  }

  private fun textOf(content: Content): String =
    content.parts.mapNotNull { it.text }.filter { it.isNotEmpty() }.joinToString(" ")

  private fun formatTimestamp(timestamp: Long): String =
    Instant.fromEpochMilliseconds(timestamp).toString()

  companion object {
    /** Default AppSearch database name under the app's private storage. */
    const val DEFAULT_DATABASE_NAME: String = "adk_memory"

    /**
     * Builds an [AppSearchMemoryService] backed by an AppSearch LocalStorage database named
     * [databaseName] in the app's private storage. Uses [Context.getApplicationContext] to avoid
     * leaking an Activity. The AppSearch session is opened lazily on first use.
     */
    fun fromContext(
      context: Context,
      databaseName: String = DEFAULT_DATABASE_NAME,
    ): AppSearchMemoryService =
      AppSearchMemoryService(LocalStorageMemoryIndex(context.applicationContext, databaseName))
  }
}
