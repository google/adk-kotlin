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
package com.google.adk.kt.models

import com.google.adk.kt.types.Content
import kotlin.jvm.JvmOverloads

/**
 * A user turn, or the function responses that answer the model's calls.
 *
 * The constructor throws [IllegalArgumentException] unless [content] has at least one part, carries
 * no function calls, and holds either only function responses or none of them.
 *
 * @property content The turn to send.
 * @property partial Whether more of the same turn follows, so the model waits instead of answering;
 *   not allowed with function responses.
 */
data class ContentInput
@JvmOverloads
constructor(val content: Content, val partial: Boolean = false) : LiveInput {
  init {
    require(content.parts.isNotEmpty()) { "content must have at least one part." }
    require(content.parts.none { it.functionCall != null }) {
      "User message cannot contain function calls."
    }
    val functionResponses = content.parts.count { it.functionResponse != null }
    require(functionResponses == 0 || functionResponses == content.parts.size) {
      "Function responses must be sent on their own, not mixed with other parts."
    }
    require(!partial || functionResponses == 0) { "partial does not apply to function responses." }
  }
}
