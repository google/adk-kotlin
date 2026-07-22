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

package com.google.adk.kt.memory.appsearch

/**
 * One stored memory item, as it crosses the [MemoryIndex] seam.
 *
 * This is the storage-facing projection of a [com.google.adk.kt.memory.MemoryEntry]: the [text] is
 * the searchable full text, while [contentJson] and [customMetadataJson] carry the serialized forms
 * needed to faithfully reconstruct the entry on read. [AppSearchMemoryService] maps between this
 * and the public `MemoryEntry`.
 *
 * @property namespace Scope key derived from `(appName, userId)`; results never cross namespaces.
 * @property id Stable document id. For ingested events this is the event id (so re-ingesting the
 *   same event overwrites it); for explicit memories it is the entry id or a generated UUID.
 * @property text The searchable full text (non-empty part texts joined by spaces).
 * @property author The memory author, or null.
 * @property timestamp ISO-8601 timestamp string, or null.
 * @property entryId The `MemoryEntry.id` to surface on read. Null for event-derived memories
 *   (matching `InMemoryMemoryService`), the memory's own id for explicit memories.
 * @property contentJson The `Content` serialized via the shared `adkJson`.
 * @property customMetadataJson The custom-metadata map serialized via the shared `adkJson` (`{}` if
 *   empty).
 */
internal data class MemoryRecord(
  val namespace: String,
  val id: String,
  val text: String,
  val author: String?,
  val timestamp: String?,
  val entryId: String?,
  val contentJson: String,
  val customMetadataJson: String,
)

/**
 * Minimal, storage-agnostic seam over the on-device search index.
 *
 * [AppSearchMemoryService] holds all business logic and depends only on this interface, so it can
 * be unit-tested on the host JVM against a fake. The production implementation,
 * [LocalStorageMemoryIndex], is a thin adapter over AndroidX AppSearch LocalStorage.
 */
internal interface MemoryIndex {

  /** Upserts [records] by `(namespace, id)`. */
  suspend fun put(records: List<MemoryRecord>)

  /**
   * Returns the records in [namespace] matching [query], ranked by the index's relevance score.
   * Only records in [namespace] are ever returned.
   */
  suspend fun search(namespace: String, query: String): List<MemoryRecord>

  /** Releases any underlying resources. */
  fun close()
}
