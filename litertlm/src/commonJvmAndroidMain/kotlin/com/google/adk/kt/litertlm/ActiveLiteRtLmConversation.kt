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

/**
 * Represents the active [LiteRtLmConversation] and the [LiteRtLmConversationDto] it was built from.
 */
internal class ActiveLiteRtLmConversation {
  var conversation: LiteRtLmConversation? = null
    private set

  var dto: LiteRtLmConversationDto? = null
    private set

  fun update(conversation: LiteRtLmConversation, dto: LiteRtLmConversationDto) {
    this.conversation = conversation
    this.dto = dto
  }

  /** Checks if the active conversation was built from the given [dto]. */
  fun matches(dto: LiteRtLmConversationDto): Boolean {
    return conversation != null && this.dto == dto
  }

  /**
   * Forgets the active conversation and returns it for the caller to close. Closing blocks until
   * generation ends, so it must not happen under this object's lock.
   */
  fun detach(): LiteRtLmConversation? {
    val detached = conversation
    conversation = null
    dto = null
    return detached
  }
}
