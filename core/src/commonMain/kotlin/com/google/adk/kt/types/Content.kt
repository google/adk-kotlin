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

package com.google.adk.kt.types

import kotlin.jvm.JvmOverloads
import kotlinx.serialization.Serializable

/** Represents the content of a response, including its role and parts. */
@Serializable
data class Content(
  /** The role of the content. */
  val role: String? = null,
  /** The parts of the content. */
  val parts: List<Part> = emptyList(),
) {
  /**
   * Returns the text of every text [Part] joined with [separator] (none by default), skipping parts
   * that carry no text and, unless [includeThoughts] is true, parts marked as thoughts.
   */
  @JvmOverloads
  fun text(separator: CharSequence = "", includeThoughts: Boolean = false): String =
    parts
      .filter { includeThoughts || it.thought != true }
      .mapNotNull { it.text }
      .joinToString(separator)

  companion object {
    /** Creates a [Content] with the given [role] containing a single text [Part] of [text]. */
    @JvmStatic
    fun fromText(role: String, text: String): Content =
      Content(role = role, parts = listOf(Part(text = text)))
  }
}
