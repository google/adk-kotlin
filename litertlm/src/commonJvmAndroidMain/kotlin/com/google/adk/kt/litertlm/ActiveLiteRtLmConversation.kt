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

package com.google.adk.kt.litertlm

import com.google.adk.kt.types.Content as AdkContent

/** Represents the active [LiteRtLmConversation] and its corresponding [AdkContent] history key. */
internal class ActiveLiteRtLmConversation {
  var conversation: LiteRtLmConversation? = null
    private set

  var history: List<AdkContent>? = null
    private set

  fun update(conversation: LiteRtLmConversation, history: List<AdkContent>) {
    this.conversation = conversation
    this.history = history
  }

  /** Checks if the active conversation history matches the given history. */
  fun matches(history: List<AdkContent>): Boolean {
    return conversation != null && this.history == history
  }

  /** Closes the active conversation and clears the conversation history. */
  fun clear() {
    conversation?.close()
    conversation = null
    history = null
  }
}
