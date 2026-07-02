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

/** The reason why the prompt was blocked. */
enum class BlockedReason {
  /** The blocked reason is unspecified. */
  BLOCKED_REASON_UNSPECIFIED,

  /** The prompt was blocked for safety reasons. */
  SAFETY,

  /**
   * The prompt was blocked for other reasons. For example, it may be due to the prompt's language,
   * or because it contains other harmful content.
   */
  OTHER,

  /** The prompt was blocked because it contains a term from the terminology blocklist. */
  BLOCKLIST,

  /** The prompt was blocked because it contains prohibited content. */
  PROHIBITED_CONTENT,

  /** The prompt was blocked because it contains content that is unsafe for image generation. */
  IMAGE_SAFETY,

  /** The prompt was blocked by Model Armor. This enum value is not supported in Gemini API. */
  MODEL_ARMOR,

  /**
   * The prompt was blocked as a jailbreak attempt. This enum value is not supported in Gemini API.
   */
  JAILBREAK;

  /** Converts [BlockedReason] to [FinishReason]. */
  internal fun toFinishReason(): FinishReason =
    when (this) {
      BlockedReason.SAFETY -> FinishReason.SAFETY
      BlockedReason.BLOCKLIST -> FinishReason.BLOCKLIST
      BlockedReason.PROHIBITED_CONTENT -> FinishReason.PROHIBITED_CONTENT
      BlockedReason.IMAGE_SAFETY -> FinishReason.IMAGE_SAFETY
      else -> FinishReason.OTHER
    }
}
