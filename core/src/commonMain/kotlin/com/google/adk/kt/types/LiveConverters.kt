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

/**
 * Converters for the live (bidirectional streaming) types, mapping between the Kotlin ADK
 * [com.google.adk.kt.types] and the [com.google.genai.kotlin.types] from the Kotlin GenAI SDK.
 *
 * These extend the family in [GenaiConverters.kt]; the naming and `internal` visibility match it.
 */
package com.google.adk.kt.types

import com.google.genai.kotlin.types.ActivityHandling as GenAiActivityHandling
import com.google.genai.kotlin.types.AudioTranscriptionConfig as GenAiAudioTranscriptionConfig
import com.google.genai.kotlin.types.AutomaticActivityDetection as GenAiAutomaticActivityDetection
import com.google.genai.kotlin.types.ContextWindowCompressionConfig as GenAiContextWindowCompressionConfig
import com.google.genai.kotlin.types.EndSensitivity as GenAiEndSensitivity
import com.google.genai.kotlin.types.LiveConnectConfig as GenAiLiveConnectConfig
import com.google.genai.kotlin.types.LiveServerGoAway as GenAiLiveServerGoAway
import com.google.genai.kotlin.types.LiveServerSessionResumptionUpdate as GenAiLiveServerSessionResumptionUpdate
import com.google.genai.kotlin.types.Modality as GenAiModality
import com.google.genai.kotlin.types.MultiSpeakerVoiceConfig as GenAiMultiSpeakerVoiceConfig
import com.google.genai.kotlin.types.PrebuiltVoiceConfig as GenAiPrebuiltVoiceConfig
import com.google.genai.kotlin.types.ProactivityConfig as GenAiProactivityConfig
import com.google.genai.kotlin.types.RealtimeInputConfig as GenAiRealtimeInputConfig
import com.google.genai.kotlin.types.ReplicatedVoiceConfig as GenAiReplicatedVoiceConfig
import com.google.genai.kotlin.types.SessionResumptionConfig as GenAiSessionResumptionConfig
import com.google.genai.kotlin.types.SlidingWindow as GenAiSlidingWindow
import com.google.genai.kotlin.types.SpeakerVoiceConfig as GenAiSpeakerVoiceConfig
import com.google.genai.kotlin.types.SpeechConfig as GenAiSpeechConfig
import com.google.genai.kotlin.types.StartSensitivity as GenAiStartSensitivity
import com.google.genai.kotlin.types.Transcription as GenAiTranscription
import com.google.genai.kotlin.types.TurnCompleteReason as GenAiTurnCompleteReason
import com.google.genai.kotlin.types.TurnCoverage as GenAiTurnCoverage
import com.google.genai.kotlin.types.UsageMetadata as GenAiLiveUsageMetadata
import com.google.genai.kotlin.types.VoiceActivity as GenAiVoiceActivity
import com.google.genai.kotlin.types.VoiceActivityType as GenAiVoiceActivityType
import com.google.genai.kotlin.types.VoiceConfig as GenAiVoiceConfig
import com.google.genai.kotlin.types.VoiceConsentSignature as GenAiVoiceConsentSignature
import com.google.genai.kotlin.types.WordInfo as GenAiWordInfo

// --- Enums ---
/**
 * Converts a [GenAiModality] from the GenAI SDK to an ADK [Modality]. The SDK models enums as
 * `value class` wrappers, so unknown values map to [Modality.MODALITY_UNSPECIFIED].
 *
 * That fallback is deliberate, and the same for every enum converter below: the SDK's wrapper is an
 * open enum and ADK's is closed, so a value added to the wire protocol later must not fail the
 * conversion. It is lossy and undetectable -- the original string is discarded, nothing logs, and
 * no caller inspects the result for `UNSPECIFIED`.
 */
internal fun GenAiModality.toKt(): Modality =
  runCatching { Modality.valueOf(this.value) }.getOrDefault(Modality.MODALITY_UNSPECIFIED)

/** Converts an ADK [Modality] to a [GenAiModality] for the GenAI SDK. */
internal fun Modality.toGenaiSdk(): GenAiModality = GenAiModality(this.name)

/**
 * Converts a [GenAiActivityHandling] from the GenAI SDK to an ADK [ActivityHandling]. Unknown
 * values map to [ActivityHandling.ACTIVITY_HANDLING_UNSPECIFIED].
 */
internal fun GenAiActivityHandling.toKt(): ActivityHandling =
  runCatching { ActivityHandling.valueOf(this.value) }
    .getOrDefault(ActivityHandling.ACTIVITY_HANDLING_UNSPECIFIED)

/** Converts an ADK [ActivityHandling] to a [GenAiActivityHandling] for the GenAI SDK. */
internal fun ActivityHandling.toGenaiSdk(): GenAiActivityHandling = GenAiActivityHandling(this.name)

/**
 * Converts a [GenAiTurnCoverage] from the GenAI SDK to an ADK [TurnCoverage]. Unknown values map to
 * [TurnCoverage.TURN_COVERAGE_UNSPECIFIED].
 */
internal fun GenAiTurnCoverage.toKt(): TurnCoverage =
  runCatching { TurnCoverage.valueOf(this.value) }
    .getOrDefault(TurnCoverage.TURN_COVERAGE_UNSPECIFIED)

/** Converts an ADK [TurnCoverage] to a [GenAiTurnCoverage] for the GenAI SDK. */
internal fun TurnCoverage.toGenaiSdk(): GenAiTurnCoverage = GenAiTurnCoverage(this.name)

/**
 * Converts a [GenAiStartSensitivity] from the GenAI SDK to an ADK [StartSensitivity]. Unknown
 * values map to [StartSensitivity.START_SENSITIVITY_UNSPECIFIED].
 */
internal fun GenAiStartSensitivity.toKt(): StartSensitivity =
  runCatching { StartSensitivity.valueOf(this.value) }
    .getOrDefault(StartSensitivity.START_SENSITIVITY_UNSPECIFIED)

/** Converts an ADK [StartSensitivity] to a [GenAiStartSensitivity] for the GenAI SDK. */
internal fun StartSensitivity.toGenaiSdk(): GenAiStartSensitivity = GenAiStartSensitivity(this.name)

/**
 * Converts a [GenAiEndSensitivity] from the GenAI SDK to an ADK [EndSensitivity]. Unknown values
 * map to [EndSensitivity.END_SENSITIVITY_UNSPECIFIED].
 */
internal fun GenAiEndSensitivity.toKt(): EndSensitivity =
  runCatching { EndSensitivity.valueOf(this.value) }
    .getOrDefault(EndSensitivity.END_SENSITIVITY_UNSPECIFIED)

/** Converts an ADK [EndSensitivity] to a [GenAiEndSensitivity] for the GenAI SDK. */
internal fun EndSensitivity.toGenaiSdk(): GenAiEndSensitivity = GenAiEndSensitivity(this.name)

// --- VoiceConsentSignature ---
/** Converts a [GenAiVoiceConsentSignature] from the GenAI SDK to an ADK [VoiceConsentSignature]. */
internal fun GenAiVoiceConsentSignature.fromGenaiSdk(): VoiceConsentSignature =
  VoiceConsentSignature(signature = signature)

/** Converts an ADK [VoiceConsentSignature] to a [GenAiVoiceConsentSignature] for the GenAI SDK. */
internal fun VoiceConsentSignature.toGenaiSdk(): GenAiVoiceConsentSignature =
  GenAiVoiceConsentSignature(signature = signature)

// --- PrebuiltVoiceConfig ---
/** Converts a [GenAiPrebuiltVoiceConfig] from the GenAI SDK to an ADK [PrebuiltVoiceConfig]. */
internal fun GenAiPrebuiltVoiceConfig.fromGenaiSdk(): PrebuiltVoiceConfig =
  PrebuiltVoiceConfig(voiceName = voiceName)

/** Converts an ADK [PrebuiltVoiceConfig] to a [GenAiPrebuiltVoiceConfig] for the GenAI SDK. */
internal fun PrebuiltVoiceConfig.toGenaiSdk(): GenAiPrebuiltVoiceConfig =
  GenAiPrebuiltVoiceConfig(voiceName = voiceName)

// --- ReplicatedVoiceConfig ---
/** Converts a [GenAiReplicatedVoiceConfig] from the GenAI SDK to an ADK [ReplicatedVoiceConfig]. */
internal fun GenAiReplicatedVoiceConfig.fromGenaiSdk(): ReplicatedVoiceConfig =
  ReplicatedVoiceConfig(
    mimeType = mimeType,
    voiceSampleAudio = voiceSampleAudio,
    consentAudio = consentAudio,
    voiceConsentSignature = voiceConsentSignature?.fromGenaiSdk(),
  )

/** Converts an ADK [ReplicatedVoiceConfig] to a [GenAiReplicatedVoiceConfig] for the GenAI SDK. */
internal fun ReplicatedVoiceConfig.toGenaiSdk(): GenAiReplicatedVoiceConfig =
  GenAiReplicatedVoiceConfig(
    mimeType = mimeType,
    voiceSampleAudio = voiceSampleAudio,
    consentAudio = consentAudio,
    voiceConsentSignature = voiceConsentSignature?.toGenaiSdk(),
  )

// --- VoiceConfig ---
/** Converts a [GenAiVoiceConfig] from the GenAI SDK to an ADK [VoiceConfig]. */
internal fun GenAiVoiceConfig.fromGenaiSdk(): VoiceConfig =
  VoiceConfig(
    replicatedVoiceConfig = replicatedVoiceConfig?.fromGenaiSdk(),
    prebuiltVoiceConfig = prebuiltVoiceConfig?.fromGenaiSdk(),
  )

/** Converts an ADK [VoiceConfig] to a [GenAiVoiceConfig] for the GenAI SDK. */
internal fun VoiceConfig.toGenaiSdk(): GenAiVoiceConfig =
  GenAiVoiceConfig(
    replicatedVoiceConfig = replicatedVoiceConfig?.toGenaiSdk(),
    prebuiltVoiceConfig = prebuiltVoiceConfig?.toGenaiSdk(),
  )

// --- SpeakerVoiceConfig ---
/** Converts a [GenAiSpeakerVoiceConfig] from the GenAI SDK to an ADK [SpeakerVoiceConfig]. */
internal fun GenAiSpeakerVoiceConfig.fromGenaiSdk(): SpeakerVoiceConfig =
  SpeakerVoiceConfig(speaker = speaker, voiceConfig = voiceConfig?.fromGenaiSdk())

/** Converts an ADK [SpeakerVoiceConfig] to a [GenAiSpeakerVoiceConfig] for the GenAI SDK. */
internal fun SpeakerVoiceConfig.toGenaiSdk(): GenAiSpeakerVoiceConfig =
  GenAiSpeakerVoiceConfig(speaker = speaker, voiceConfig = voiceConfig?.toGenaiSdk())

// --- MultiSpeakerVoiceConfig ---
/**
 * Converts a [GenAiMultiSpeakerVoiceConfig] from the GenAI SDK to an ADK [MultiSpeakerVoiceConfig].
 */
internal fun GenAiMultiSpeakerVoiceConfig.fromGenaiSdk(): MultiSpeakerVoiceConfig =
  MultiSpeakerVoiceConfig(speakerVoiceConfigs = speakerVoiceConfigs?.map { it.fromGenaiSdk() })

/**
 * Converts an ADK [MultiSpeakerVoiceConfig] to a [GenAiMultiSpeakerVoiceConfig] for the GenAI SDK.
 */
internal fun MultiSpeakerVoiceConfig.toGenaiSdk(): GenAiMultiSpeakerVoiceConfig =
  GenAiMultiSpeakerVoiceConfig(speakerVoiceConfigs = speakerVoiceConfigs?.map { it.toGenaiSdk() })

// --- SpeechConfig ---
/** Converts a [GenAiSpeechConfig] from the GenAI SDK to an ADK [SpeechConfig]. */
internal fun GenAiSpeechConfig.fromGenaiSdk(): SpeechConfig =
  SpeechConfig(
    voiceConfig = voiceConfig?.fromGenaiSdk(),
    languageCode = languageCode,
    multiSpeakerVoiceConfig = multiSpeakerVoiceConfig?.fromGenaiSdk(),
  )

/** Converts an ADK [SpeechConfig] to a [GenAiSpeechConfig] for the GenAI SDK. */
internal fun SpeechConfig.toGenaiSdk(): GenAiSpeechConfig =
  GenAiSpeechConfig(
    voiceConfig = voiceConfig?.toGenaiSdk(),
    languageCode = languageCode,
    multiSpeakerVoiceConfig = multiSpeakerVoiceConfig?.toGenaiSdk(),
  )

// --- AutomaticActivityDetection ---
/**
 * Converts a [GenAiAutomaticActivityDetection] from the GenAI SDK to an ADK
 * [AutomaticActivityDetection].
 */
internal fun GenAiAutomaticActivityDetection.fromGenaiSdk(): AutomaticActivityDetection =
  AutomaticActivityDetection(
    disabled = disabled,
    startOfSpeechSensitivity = startOfSpeechSensitivity?.toKt(),
    endOfSpeechSensitivity = endOfSpeechSensitivity?.toKt(),
    prefixPaddingMs = prefixPaddingMs,
    silenceDurationMs = silenceDurationMs,
  )

/**
 * Converts an ADK [AutomaticActivityDetection] to a [GenAiAutomaticActivityDetection] for the GenAI
 * SDK.
 */
internal fun AutomaticActivityDetection.toGenaiSdk(): GenAiAutomaticActivityDetection =
  GenAiAutomaticActivityDetection(
    disabled = disabled,
    startOfSpeechSensitivity = startOfSpeechSensitivity?.toGenaiSdk(),
    endOfSpeechSensitivity = endOfSpeechSensitivity?.toGenaiSdk(),
    prefixPaddingMs = prefixPaddingMs,
    silenceDurationMs = silenceDurationMs,
  )

// --- RealtimeInputConfig ---
/** Converts a [GenAiRealtimeInputConfig] from the GenAI SDK to an ADK [RealtimeInputConfig]. */
internal fun GenAiRealtimeInputConfig.fromGenaiSdk(): RealtimeInputConfig =
  RealtimeInputConfig(
    automaticActivityDetection = automaticActivityDetection?.fromGenaiSdk(),
    activityHandling = activityHandling?.toKt(),
    turnCoverage = turnCoverage?.toKt(),
  )

/** Converts an ADK [RealtimeInputConfig] to a [GenAiRealtimeInputConfig] for the GenAI SDK. */
internal fun RealtimeInputConfig.toGenaiSdk(): GenAiRealtimeInputConfig =
  GenAiRealtimeInputConfig(
    automaticActivityDetection = automaticActivityDetection?.toGenaiSdk(),
    activityHandling = activityHandling?.toGenaiSdk(),
    turnCoverage = turnCoverage?.toGenaiSdk(),
  )

// --- AudioTranscriptionConfig ---
/**
 * Converts a [GenAiAudioTranscriptionConfig] from the GenAI SDK to an ADK
 * [AudioTranscriptionConfig]. Four SDK fields are not mirrored: the deprecated `languageAuto`,
 * `languageHints` and `adaptationPhrases`, and `mode`, which is current -- so a caller cannot ask
 * for `SMART` transcription and always gets the default `VERBATIM`.
 */
internal fun GenAiAudioTranscriptionConfig.fromGenaiSdk(): AudioTranscriptionConfig =
  AudioTranscriptionConfig(
    languageCodes = languageCodes,
    customVocabulary = customVocabulary,
    diarization = diarization,
    wordTimestamp = wordTimestamp,
  )

/**
 * Converts an ADK [AudioTranscriptionConfig] to a [GenAiAudioTranscriptionConfig] for the GenAI
 * SDK.
 */
internal fun AudioTranscriptionConfig.toGenaiSdk(): GenAiAudioTranscriptionConfig =
  GenAiAudioTranscriptionConfig(
    languageCodes = languageCodes,
    customVocabulary = customVocabulary,
    diarization = diarization,
    wordTimestamp = wordTimestamp,
  )

// --- SessionResumptionConfig ---
/**
 * Converts a [GenAiSessionResumptionConfig] from the GenAI SDK to an ADK [SessionResumptionConfig].
 */
internal fun GenAiSessionResumptionConfig.fromGenaiSdk(): SessionResumptionConfig =
  SessionResumptionConfig(handle = handle, transparent = transparent)

/**
 * Converts an ADK [SessionResumptionConfig] to a [GenAiSessionResumptionConfig] for the GenAI SDK.
 */
internal fun SessionResumptionConfig.toGenaiSdk(): GenAiSessionResumptionConfig =
  GenAiSessionResumptionConfig(handle = handle, transparent = transparent)

// --- SlidingWindow ---
/** Converts a [GenAiSlidingWindow] from the GenAI SDK to an ADK [SlidingWindow]. */
internal fun GenAiSlidingWindow.fromGenaiSdk(): SlidingWindow =
  SlidingWindow(targetTokens = targetTokens)

/** Converts an ADK [SlidingWindow] to a [GenAiSlidingWindow] for the GenAI SDK. */
internal fun SlidingWindow.toGenaiSdk(): GenAiSlidingWindow =
  GenAiSlidingWindow(targetTokens = targetTokens)

// --- ContextWindowCompressionConfig ---
/**
 * Converts a [GenAiContextWindowCompressionConfig] from the GenAI SDK to an ADK
 * [ContextWindowCompressionConfig].
 */
internal fun GenAiContextWindowCompressionConfig.fromGenaiSdk(): ContextWindowCompressionConfig =
  ContextWindowCompressionConfig(
    triggerTokens = triggerTokens,
    slidingWindow = slidingWindow?.fromGenaiSdk(),
  )

/**
 * Converts an ADK [ContextWindowCompressionConfig] to a [GenAiContextWindowCompressionConfig] for
 * the GenAI SDK.
 */
internal fun ContextWindowCompressionConfig.toGenaiSdk(): GenAiContextWindowCompressionConfig =
  GenAiContextWindowCompressionConfig(
    triggerTokens = triggerTokens,
    slidingWindow = slidingWindow?.toGenaiSdk(),
  )

// --- ProactivityConfig ---
/** Converts a [GenAiProactivityConfig] from the GenAI SDK to an ADK [ProactivityConfig]. */
internal fun GenAiProactivityConfig.fromGenaiSdk(): ProactivityConfig =
  ProactivityConfig(proactiveAudio = proactiveAudio)

/** Converts an ADK [ProactivityConfig] to a [GenAiProactivityConfig] for the GenAI SDK. */
internal fun ProactivityConfig.toGenaiSdk(): GenAiProactivityConfig =
  GenAiProactivityConfig(proactiveAudio = proactiveAudio)

// --- LiveConnectConfig ---
/**
 * Converts a [GenAiLiveConnectConfig] from the GenAI SDK to an ADK [LiveConnectConfig]. The SDK
 * fields ADK does not mirror -- `httpOptions`, `avatarConfig`, `explicitVadSignal` and
 * `translationConfig` -- are dropped.
 */
internal fun GenAiLiveConnectConfig.fromGenaiSdk(): LiveConnectConfig =
  LiveConnectConfig(
    responseModalities = responseModalities?.map { it.toKt() },
    temperature = temperature?.toFloat(),
    topP = topP?.toFloat(),
    topK = topK?.toInt(),
    maxOutputTokens = maxOutputTokens,
    mediaResolution = mediaResolution?.toKt(),
    seed = seed,
    speechConfig = speechConfig?.fromGenaiSdk(),
    thinkingConfig = thinkingConfig?.fromGenaiSdk(),
    enableAffectiveDialog = enableAffectiveDialog,
    systemInstruction = systemInstruction?.fromGenaiSdk(),
    tools = tools?.map { it.fromGenaiSdk() },
    sessionResumption = sessionResumption?.fromGenaiSdk(),
    inputAudioTranscription = inputAudioTranscription?.fromGenaiSdk(),
    outputAudioTranscription = outputAudioTranscription?.fromGenaiSdk(),
    realtimeInputConfig = realtimeInputConfig?.fromGenaiSdk(),
    contextWindowCompression = contextWindowCompression?.fromGenaiSdk(),
    proactivity = proactivity?.fromGenaiSdk(),
    safetySettings = safetySettings?.map { it.fromGenaiSdk() },
  )

/** Converts an ADK [LiveConnectConfig] to a [GenAiLiveConnectConfig] for the GenAI SDK. */
internal fun LiveConnectConfig.toGenaiSdk(): GenAiLiveConnectConfig =
  GenAiLiveConnectConfig(
    responseModalities = responseModalities?.map { it.toGenaiSdk() },
    temperature = temperature?.toDouble(),
    topP = topP?.toDouble(),
    topK = topK?.toDouble(),
    maxOutputTokens = maxOutputTokens,
    mediaResolution = mediaResolution?.toGenaiSdk(),
    seed = seed,
    speechConfig = speechConfig?.toGenaiSdk(),
    thinkingConfig = thinkingConfig?.toGenaiSdk(),
    enableAffectiveDialog = enableAffectiveDialog,
    systemInstruction = systemInstruction?.toGenaiSdk(),
    tools = tools?.map { it.toGenaiSdk() },
    sessionResumption = sessionResumption?.toGenaiSdk(),
    inputAudioTranscription = inputAudioTranscription?.toGenaiSdk(),
    outputAudioTranscription = outputAudioTranscription?.toGenaiSdk(),
    realtimeInputConfig = realtimeInputConfig?.toGenaiSdk(),
    contextWindowCompression = contextWindowCompression?.toGenaiSdk(),
    proactivity = proactivity?.toGenaiSdk(),
    safetySettings = safetySettings?.map { it.toGenaiSdk() },
  )

// --- Server message types ---
/**
 * Converts a [GenAiVoiceActivityType] from the GenAI SDK to an ADK [VoiceActivityType]. Unknown
 * values map to [VoiceActivityType.TYPE_UNSPECIFIED].
 */
internal fun GenAiVoiceActivityType.toKt(): VoiceActivityType =
  runCatching { VoiceActivityType.valueOf(this.value) }
    .getOrDefault(VoiceActivityType.TYPE_UNSPECIFIED)

/** Converts an ADK [VoiceActivityType] to a [GenAiVoiceActivityType] for the GenAI SDK. */
internal fun VoiceActivityType.toGenaiSdk(): GenAiVoiceActivityType =
  GenAiVoiceActivityType(this.name)

/**
 * Converts a [GenAiTurnCompleteReason] from the GenAI SDK to an ADK [TurnCompleteReason]. Unknown
 * values map to [TurnCompleteReason.TURN_COMPLETE_REASON_UNSPECIFIED], so a reason added to the
 * wire protocol later does not fail the conversion.
 */
internal fun GenAiTurnCompleteReason.toKt(): TurnCompleteReason =
  runCatching { TurnCompleteReason.valueOf(this.value) }
    .getOrDefault(TurnCompleteReason.TURN_COMPLETE_REASON_UNSPECIFIED)

/** Converts an ADK [TurnCompleteReason] to a [GenAiTurnCompleteReason] for the GenAI SDK. */
internal fun TurnCompleteReason.toGenaiSdk(): GenAiTurnCompleteReason =
  GenAiTurnCompleteReason(this.name)

/** Converts a [GenAiWordInfo] from the GenAI SDK to an ADK [WordInfo]. */
internal fun GenAiWordInfo.fromGenaiSdk(): WordInfo =
  WordInfo(word = word, startOffset = startOffset, endOffset = endOffset)

/** Converts an ADK [WordInfo] to a [GenAiWordInfo] for the GenAI SDK. */
internal fun WordInfo.toGenaiSdk(): GenAiWordInfo =
  GenAiWordInfo(word = word, startOffset = startOffset, endOffset = endOffset)

/** Converts a [GenAiTranscription] from the GenAI SDK to an ADK [Transcription]. */
internal fun GenAiTranscription.fromGenaiSdk(): Transcription =
  Transcription(
    text = text,
    finished = finished,
    languageCode = languageCode,
    speakerLabel = speakerLabel,
    words = words?.map { it.fromGenaiSdk() },
  )

/** Converts an ADK [Transcription] to a [GenAiTranscription] for the GenAI SDK. */
internal fun Transcription.toGenaiSdk(): GenAiTranscription =
  GenAiTranscription(
    text = text,
    finished = finished,
    languageCode = languageCode,
    speakerLabel = speakerLabel,
    words = words?.map { it.toGenaiSdk() },
  )

/** Converts a [GenAiVoiceActivity] from the GenAI SDK to an ADK [VoiceActivity]. */
internal fun GenAiVoiceActivity.fromGenaiSdk(): VoiceActivity =
  VoiceActivity(voiceActivityType = voiceActivityType?.toKt(), audioOffset = audioOffset)

/** Converts an ADK [VoiceActivity] to a [GenAiVoiceActivity] for the GenAI SDK. */
internal fun VoiceActivity.toGenaiSdk(): GenAiVoiceActivity =
  GenAiVoiceActivity(voiceActivityType = voiceActivityType?.toGenaiSdk(), audioOffset = audioOffset)

/** Converts a [GenAiLiveServerGoAway] from the GenAI SDK to an ADK [LiveServerGoAway]. */
internal fun GenAiLiveServerGoAway.fromGenaiSdk(): LiveServerGoAway =
  LiveServerGoAway(timeLeft = timeLeft)

/** Converts an ADK [LiveServerGoAway] to a [GenAiLiveServerGoAway] for the GenAI SDK. */
internal fun LiveServerGoAway.toGenaiSdk(): GenAiLiveServerGoAway =
  GenAiLiveServerGoAway(timeLeft = timeLeft)

/**
 * Converts a [GenAiLiveServerSessionResumptionUpdate] from the GenAI SDK to an ADK
 * [LiveServerSessionResumptionUpdate].
 */
internal fun GenAiLiveServerSessionResumptionUpdate.fromGenaiSdk():
  LiveServerSessionResumptionUpdate =
  LiveServerSessionResumptionUpdate(
    newHandle = newHandle,
    resumable = resumable,
    lastConsumedClientMessageIndex = lastConsumedClientMessageIndex,
  )

/**
 * Converts an ADK [LiveServerSessionResumptionUpdate] to a [GenAiLiveServerSessionResumptionUpdate]
 * for the GenAI SDK.
 */
internal fun LiveServerSessionResumptionUpdate.toGenaiSdk():
  GenAiLiveServerSessionResumptionUpdate =
  GenAiLiveServerSessionResumptionUpdate(
    newHandle = newHandle,
    resumable = resumable,
    lastConsumedClientMessageIndex = lastConsumedClientMessageIndex,
  )

/**
 * Converts the live API's own usage metadata to the ADK [UsageMetadata] shared with the unary path.
 *
 * The two wire shapes disagree on names: the live type calls the model's own output
 * `responseTokenCount` / `responseTokensDetails` where the unary type calls it
 * `candidatesTokenCount` / `candidatesTokensDetails`. Copying by matching name would leave every
 * live token count unset, so the crossing here is deliberate. Three SDK fields are dropped:
 * `serviceTier` and `cacheTokensDetails` have no counterpart on the ADK type, and `trafficType` has
 * one that neither this converter nor the unary path populates.
 */
internal fun GenAiLiveUsageMetadata.fromGenaiSdk(): UsageMetadata =
  UsageMetadata(
    promptTokenCount = promptTokenCount,
    candidatesTokenCount = responseTokenCount,
    totalTokenCount = totalTokenCount,
    thoughtsTokenCount = thoughtsTokenCount,
    toolUsePromptTokenCount = toolUsePromptTokenCount,
    cachedContentTokenCount = cachedContentTokenCount,
    promptTokensDetails = promptTokensDetails?.map { it.fromGenaiSdk() },
    candidatesTokensDetails = responseTokensDetails?.map { it.fromGenaiSdk() },
    toolUsePromptTokensDetails = toolUsePromptTokensDetails?.map { it.fromGenaiSdk() },
  )
