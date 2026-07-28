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

import com.google.adk.kt.types.Content
import kotlin.jvm.JvmStatic

/**
 * Represents one memory entry in the Vertex AI Memory Bank.
 *
 * @property content The main content of the memory.
 * @property id The unique identifier of the memory entry, or null if not yet persisted.
 * @property author The author of the memory, or null if not set.
 * @property timestamp The timestamp when the original content of this memory happened, or null if
 *   not set. Preferred format is ISO 8601 format.
 * @property customMetadata Optional key-value metadata associated with this memory.
 */
data class MemoryEntry(
  val content: Content,
  val id: String? = null,
  val author: String? = null,
  val timestamp: String? = null,
  val customMetadata: Map<String, Any> = emptyMap(),
) {
  /**
   * Fluent builder for [MemoryEntry], provided primarily for Java callers. Any property left unset
   * falls back to the same default as the constructor.
   */
  @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
  class Builder {
    private var content: Content? = null
    private var id: String? = null
    private var author: String? = null
    private var timestamp: String? = null
    private var customMetadata: Map<String, Any> = emptyMap()

    fun content(content: Content): Builder = apply { this.content = content }

    fun id(id: String?): Builder = apply { this.id = id }

    fun author(author: String?): Builder = apply { this.author = author }

    fun timestamp(timestamp: String?): Builder = apply { this.timestamp = timestamp }

    fun customMetadata(customMetadata: Map<String, Any>): Builder = apply {
      this.customMetadata = customMetadata
    }

    fun build(): MemoryEntry =
      MemoryEntry(
        content = checkNotNull(content) { "MemoryEntry.Builder requires content to be set." },
        id = id,
        author = author,
        timestamp = timestamp,
        customMetadata = customMetadata,
      )
  }

  companion object {
    @JvmStatic fun builder(): Builder = Builder()
  }
}
