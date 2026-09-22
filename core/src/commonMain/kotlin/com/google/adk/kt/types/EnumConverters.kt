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

import com.google.genai.kotlin.types.BlockedReason as GenAiBlockedReason
import com.google.genai.kotlin.types.FinishReason as GenAiFinishReason
import com.google.genai.kotlin.types.FunctionCallingConfigMode as GenAiFunctionCallingConfigMode
import com.google.genai.kotlin.types.HarmBlockMethod as GenAiHarmBlockMethod
import com.google.genai.kotlin.types.HarmBlockThreshold as GenAiHarmBlockThreshold
import com.google.genai.kotlin.types.HarmCategory as GenAiHarmCategory
import com.google.genai.kotlin.types.Language as GenAiLanguage
import com.google.genai.kotlin.types.MediaModality as GenAiMediaModality
import com.google.genai.kotlin.types.MediaResolution as GenAiMediaResolution
import com.google.genai.kotlin.types.ModelRoutingPreference as GenAiModelRoutingPreference
import com.google.genai.kotlin.types.Outcome as GenAiOutcome
import com.google.genai.kotlin.types.PartMediaResolutionLevel as GenAiPartMediaResolutionLevel
import com.google.genai.kotlin.types.ServiceTier as GenAiServiceTier
import com.google.genai.kotlin.types.ThinkingLevel as GenAiThinkingLevel
import com.google.genai.kotlin.types.Type as GenAiType

/**
 * Converts a GenAI SDK [GenAiBlockedReason] to an ADK [BlockedReason]. The SDK models enums as
 * `value class` wrappers, so unknown values map to [BlockedReason.OTHER].
 */
internal fun GenAiBlockedReason.toKt(): BlockedReason =
  runCatching { BlockedReason.valueOf(this.value) }.getOrDefault(BlockedReason.OTHER)

/** Converts an ADK [BlockedReason] to a [GenAiBlockedReason] for the GenAI SDK. */
internal fun BlockedReason.toGenaiSdk(): GenAiBlockedReason = GenAiBlockedReason(this.name)

/** Converts a [GenAiFinishReason] from the GenAI SDK to an ADK [FinishReason]. */
internal fun GenAiFinishReason.toKt(): FinishReason =
  runCatching { FinishReason.valueOf(this.value) }.getOrDefault(FinishReason.OTHER)

/** Converts an ADK [FinishReason] to a [GenAiFinishReason] for the GenAI SDK. */
internal fun FinishReason.toGenaiSdk(): GenAiFinishReason = GenAiFinishReason(this.name)

/** Converts a [GenAiThinkingLevel] from the GenAI SDK to an ADK [ThinkingLevel]. */
internal fun GenAiThinkingLevel.toKt(): ThinkingLevel =
  runCatching { ThinkingLevel.valueOf(this.value) }
    .getOrDefault(ThinkingLevel.THINKING_LEVEL_UNSPECIFIED)

/** Converts an ADK [ThinkingLevel] to a [GenAiThinkingLevel] for the GenAI SDK. */
internal fun ThinkingLevel.toGenaiSdk(): GenAiThinkingLevel = GenAiThinkingLevel(this.name)

/** Converts a [GenAiType] from the GenAI SDK to an ADK [Type]. */
internal fun GenAiType.toKt(): Type? = runCatching { Type.valueOf(this.value) }.getOrNull()

/** Converts an ADK [Type] to a [GenAiType] for the GenAI SDK. */
internal fun Type.toGenaiSdk(): GenAiType = GenAiType(this.name)

/**
 * Converts a [GenAiMediaModality] from the GenAI SDK to an ADK [MediaModality]. The SDK models
 * enums as `value class` wrappers, so unknown values map to [MediaModality.MODALITY_UNSPECIFIED].
 */
internal fun GenAiMediaModality.toKt(): MediaModality =
  runCatching { MediaModality.valueOf(this.value) }.getOrDefault(MediaModality.MODALITY_UNSPECIFIED)

/** Converts an ADK [MediaModality] to a [GenAiMediaModality] for the GenAI SDK. */
internal fun MediaModality.toGenaiSdk(): GenAiMediaModality = GenAiMediaModality(this.name)

/**
 * Converts a [GenAiHarmCategory] from the GenAI SDK to an ADK [HarmCategory]. The SDK models enums
 * as `value class` wrappers, so unknown values map to [HarmCategory.HARM_CATEGORY_UNSPECIFIED].
 */
internal fun GenAiHarmCategory.toKt(): HarmCategory =
  runCatching { HarmCategory.valueOf(this.value) }
    .getOrDefault(HarmCategory.HARM_CATEGORY_UNSPECIFIED)

/** Converts an ADK [HarmCategory] to a [GenAiHarmCategory] for the SDK. */
internal fun HarmCategory.toGenaiSdk(): GenAiHarmCategory = GenAiHarmCategory(this.name)

/**
 * Converts a [GenAiHarmBlockThreshold] from the GenAI SDK to an ADK [HarmBlockThreshold]. The SDK
 * models enums as `value class` wrappers, so unknown values map to
 * [HarmBlockThreshold.HARM_BLOCK_THRESHOLD_UNSPECIFIED].
 */
internal fun GenAiHarmBlockThreshold.toKt(): HarmBlockThreshold =
  runCatching { HarmBlockThreshold.valueOf(this.value) }
    .getOrDefault(HarmBlockThreshold.HARM_BLOCK_THRESHOLD_UNSPECIFIED)

/** Converts an ADK [HarmBlockThreshold] to a [GenAiHarmBlockThreshold] for the GenAI SDK. */
internal fun HarmBlockThreshold.toGenaiSdk(): GenAiHarmBlockThreshold =
  GenAiHarmBlockThreshold(this.name)

/**
 * Converts a [GenAiMediaResolution] from the GenAI SDK to an ADK [MediaResolution]. The SDK models
 * enums as `value class` wrappers, so unknown values map to
 * [MediaResolution.MEDIA_RESOLUTION_UNSPECIFIED].
 */
internal fun GenAiMediaResolution.toKt(): MediaResolution =
  runCatching { MediaResolution.valueOf(this.value) }
    .getOrDefault(MediaResolution.MEDIA_RESOLUTION_UNSPECIFIED)

/** Converts an ADK [MediaResolution] to a [GenAiMediaResolution] for the GenAI SDK. */
internal fun MediaResolution.toGenaiSdk(): GenAiMediaResolution = GenAiMediaResolution(this.name)

/**
 * Converts a [GenAiServiceTier] from the GenAI SDK to an ADK [ServiceTier]. The SDK models enums as
 * `value class` wrappers, so unknown values map to [ServiceTier.UNSPECIFIED].
 */
internal fun GenAiServiceTier.toKt(): ServiceTier =
  runCatching { ServiceTier.valueOf(this.value) }.getOrDefault(ServiceTier.UNSPECIFIED)

/** Converts an ADK [ServiceTier] to a [GenAiServiceTier] for the SDK. */
internal fun ServiceTier.toGenaiSdk(): GenAiServiceTier = GenAiServiceTier(this.name)

/**
 * Converts a [GenAiModelRoutingPreference] from the GenAI SDK to an ADK [ModelRoutingPreference].
 * The SDK models enums as `value class` wrappers, so unknown values map to
 * [ModelRoutingPreference.UNKNOWN].
 */
internal fun GenAiModelRoutingPreference.toKt(): ModelRoutingPreference =
  runCatching { ModelRoutingPreference.valueOf(this.value) }
    .getOrDefault(ModelRoutingPreference.UNKNOWN)

/**
 * Converts an ADK [ModelRoutingPreference] to a [GenAiModelRoutingPreference] for the GenAI SDK.
 */
internal fun ModelRoutingPreference.toGenaiSdk(): GenAiModelRoutingPreference =
  GenAiModelRoutingPreference(this.name)

/**
 * Converts a [GenAiFunctionCallingConfigMode] from the GenAI SDK to an ADK
 * [FunctionCallingConfigMode]. The SDK models enums as `value class` wrappers, so unknown values
 * map to [FunctionCallingConfigMode.MODE_UNSPECIFIED].
 */
internal fun GenAiFunctionCallingConfigMode.toKt(): FunctionCallingConfigMode =
  runCatching { FunctionCallingConfigMode.valueOf(this.value) }
    .getOrDefault(FunctionCallingConfigMode.MODE_UNSPECIFIED)

/**
 * Converts an ADK [FunctionCallingConfigMode] to a [GenAiFunctionCallingConfigMode] for the GenAI
 * SDK.
 */
internal fun FunctionCallingConfigMode.toGenaiSdk(): GenAiFunctionCallingConfigMode =
  GenAiFunctionCallingConfigMode(this.name)

/**
 * Converts a [GenAiHarmBlockMethod] from the GenAI SDK to an ADK [HarmBlockMethod]. The SDK models
 * enums as `value class` wrappers, so unknown values map to
 * [HarmBlockMethod.HARM_BLOCK_METHOD_UNSPECIFIED].
 */
internal fun GenAiHarmBlockMethod.toKt(): HarmBlockMethod =
  runCatching { HarmBlockMethod.valueOf(this.value) }
    .getOrDefault(HarmBlockMethod.HARM_BLOCK_METHOD_UNSPECIFIED)

/** Converts an ADK [HarmBlockMethod] to a [GenAiHarmBlockMethod] for the GenAI SDK. */
internal fun HarmBlockMethod.toGenaiSdk(): GenAiHarmBlockMethod = GenAiHarmBlockMethod(this.name)

/**
 * Converts a [GenAiLanguage] from the GenAI SDK to an ADK [Language]. The SDK models enums as
 * `value class` wrappers, so unknown values map to [Language.LANGUAGE_UNSPECIFIED].
 */
internal fun GenAiLanguage.toKt(): Language =
  runCatching { Language.valueOf(this.value) }.getOrDefault(Language.LANGUAGE_UNSPECIFIED)

/** Converts an ADK [Language] to a [GenAiLanguage] for the GenAI SDK. */
internal fun Language.toGenaiSdk(): GenAiLanguage = GenAiLanguage(this.name)

/**
 * Converts a [GenAiOutcome] from the GenAI SDK to an ADK [Outcome]. The SDK models enums as `value
 * class` wrappers, so unknown values map to [Outcome.OUTCOME_UNSPECIFIED].
 */
internal fun GenAiOutcome.toKt(): Outcome =
  runCatching { Outcome.valueOf(this.value) }.getOrDefault(Outcome.OUTCOME_UNSPECIFIED)

/** Converts an ADK [Outcome] to a [GenAiOutcome] for the GenAI SDK. */
internal fun Outcome.toGenaiSdk(): GenAiOutcome = GenAiOutcome(this.name)

/**
 * Converts a [GenAiPartMediaResolutionLevel] from the GenAI SDK to an ADK
 * [PartMediaResolutionLevel]. The SDK models enums as `value class` wrappers, so unknown values map
 * to [PartMediaResolutionLevel.MEDIA_RESOLUTION_UNSPECIFIED].
 */
internal fun GenAiPartMediaResolutionLevel.toKt(): PartMediaResolutionLevel =
  runCatching { PartMediaResolutionLevel.valueOf(this.value) }
    .getOrDefault(PartMediaResolutionLevel.MEDIA_RESOLUTION_UNSPECIFIED)

/** Converts an ADK [PartMediaResolutionLevel] to a [GenAiPartMediaResolutionLevel] for the SDK. */
internal fun PartMediaResolutionLevel.toGenaiSdk(): GenAiPartMediaResolutionLevel =
  GenAiPartMediaResolutionLevel(this.name)
