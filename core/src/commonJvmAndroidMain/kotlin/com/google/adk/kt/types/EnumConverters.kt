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

/**
 * Converts a [com.google.genai.types.BlockedReason] from the GenAI SDK to an ADK [BlockedReason].
 */
internal fun com.google.genai.types.BlockedReason.toKt(): BlockedReason =
  when (this.knownEnum()) {
    com.google.genai.types.BlockedReason.Known.BLOCKED_REASON_UNSPECIFIED ->
      BlockedReason.BLOCKED_REASON_UNSPECIFIED
    com.google.genai.types.BlockedReason.Known.SAFETY -> BlockedReason.SAFETY
    com.google.genai.types.BlockedReason.Known.OTHER -> BlockedReason.OTHER
    com.google.genai.types.BlockedReason.Known.BLOCKLIST -> BlockedReason.BLOCKLIST
    com.google.genai.types.BlockedReason.Known.PROHIBITED_CONTENT ->
      BlockedReason.PROHIBITED_CONTENT
    com.google.genai.types.BlockedReason.Known.IMAGE_SAFETY -> BlockedReason.IMAGE_SAFETY
    com.google.genai.types.BlockedReason.Known.MODEL_ARMOR -> BlockedReason.MODEL_ARMOR
    com.google.genai.types.BlockedReason.Known.JAILBREAK -> BlockedReason.JAILBREAK
  }

/**
 * Converts an ADK [BlockedReason] to a [com.google.genai.types.BlockedReason] for the GenAI SDK.
 */
internal fun BlockedReason.toJava(): com.google.genai.types.BlockedReason =
  com.google.genai.types.BlockedReason(this.name)

/** Converts a [com.google.genai.types.FinishReason] from the GenAI SDK to an ADK [FinishReason]. */
internal fun com.google.genai.types.FinishReason.toKt(): FinishReason =
  runCatching { FinishReason.valueOf(this.toString()) }.getOrDefault(FinishReason.OTHER)

/** Converts an ADK [FinishReason] to a [com.google.genai.types.FinishReason] for the GenAI SDK. */
internal fun FinishReason.toJava(): com.google.genai.types.FinishReason =
  com.google.genai.types.FinishReason(this.name)

/** Converts an ADK [BlockedReason] to its equivalent [FinishReason]. */
internal fun com.google.genai.types.BlockedReason.toFinishReason(): FinishReason =
  when (this.knownEnum()) {
    com.google.genai.types.BlockedReason.Known.SAFETY -> FinishReason.SAFETY
    else -> FinishReason.OTHER
  }

/**
 * Converts a [com.google.genai.types.ThinkingLevel] from the GenAI SDK to an ADK [ThinkingLevel].
 */
internal fun com.google.genai.types.ThinkingLevel.toKt(): ThinkingLevel =
  runCatching { ThinkingLevel.valueOf(this.toString()) }
    .getOrDefault(ThinkingLevel.THINKING_LEVEL_UNSPECIFIED)

/**
 * Converts an ADK [ThinkingLevel] to a [com.google.genai.types.ThinkingLevel] for the GenAI SDK.
 */
internal fun ThinkingLevel.toJava(): com.google.genai.types.ThinkingLevel =
  com.google.genai.types.ThinkingLevel(this.name)

/**
 * Converts a [com.google.genai.types.MediaModality] from the GenAI SDK to an ADK [MediaModality].
 */
internal fun com.google.genai.types.MediaModality.toKt(): MediaModality =
  runCatching { MediaModality.valueOf(this.toString()) }
    .getOrDefault(MediaModality.MODALITY_UNSPECIFIED)

/**
 * Converts an ADK [MediaModality] to a [com.google.genai.types.MediaModality] for the GenAI SDK.
 */
internal fun MediaModality.toJava(): com.google.genai.types.MediaModality =
  com.google.genai.types.MediaModality(this.name)

/** Converts a [com.google.genai.types.HarmCategory] from the GenAI SDK to an ADK [HarmCategory]. */
internal fun com.google.genai.types.HarmCategory.toKt(): HarmCategory =
  runCatching { HarmCategory.valueOf(this.toString()) }
    .getOrDefault(HarmCategory.HARM_CATEGORY_UNSPECIFIED)

/** Converts an ADK [HarmCategory] to a [com.google.genai.types.HarmCategory] for the GenAI SDK. */
internal fun HarmCategory.toJava(): com.google.genai.types.HarmCategory =
  com.google.genai.types.HarmCategory(this.name)

/**
 * Converts a [com.google.genai.types.HarmBlockThreshold] from the GenAI SDK to an ADK
 * [HarmBlockThreshold].
 */
internal fun com.google.genai.types.HarmBlockThreshold.toKt(): HarmBlockThreshold =
  runCatching { HarmBlockThreshold.valueOf(this.toString()) }
    .getOrDefault(HarmBlockThreshold.HARM_BLOCK_THRESHOLD_UNSPECIFIED)

/**
 * Converts an ADK [HarmBlockThreshold] to a [com.google.genai.types.HarmBlockThreshold] for the
 * GenAI SDK.
 */
internal fun HarmBlockThreshold.toJava(): com.google.genai.types.HarmBlockThreshold =
  com.google.genai.types.HarmBlockThreshold(this.name)

/**
 * Converts a [com.google.genai.types.MediaResolution] from the GenAI SDK to an ADK
 * [MediaResolution].
 */
internal fun com.google.genai.types.MediaResolution.toKt(): MediaResolution =
  runCatching { MediaResolution.valueOf(this.toString()) }
    .getOrDefault(MediaResolution.MEDIA_RESOLUTION_UNSPECIFIED)

/**
 * Converts an ADK [MediaResolution] to a [com.google.genai.types.MediaResolution] for the GenAI
 * SDK.
 */
internal fun MediaResolution.toJava(): com.google.genai.types.MediaResolution =
  com.google.genai.types.MediaResolution(this.name)

/** Converts a [com.google.genai.types.ServiceTier] from the GenAI SDK to an ADK [ServiceTier]. */
internal fun com.google.genai.types.ServiceTier.toKt(): ServiceTier =
  runCatching { ServiceTier.valueOf(this.toString()) }.getOrDefault(ServiceTier.UNSPECIFIED)

/** Converts an ADK [ServiceTier] to a [com.google.genai.types.ServiceTier] for the GenAI SDK. */
internal fun ServiceTier.toJava(): com.google.genai.types.ServiceTier =
  com.google.genai.types.ServiceTier(this.name)
