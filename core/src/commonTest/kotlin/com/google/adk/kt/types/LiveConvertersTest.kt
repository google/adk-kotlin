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

import com.google.genai.kotlin.types.ActivityHandling as SdkActivityHandling
import com.google.genai.kotlin.types.AudioTranscriptionConfig as SdkAudioTranscriptionConfig
import com.google.genai.kotlin.types.EndSensitivity as SdkEndSensitivity
import com.google.genai.kotlin.types.LanguageHints as SdkLanguageHints
import com.google.genai.kotlin.types.LiveConnectConfig as SdkLiveConnectConfig
import com.google.genai.kotlin.types.LiveServerGoAway as SdkLiveServerGoAway
import com.google.genai.kotlin.types.LiveServerSessionResumptionUpdate as SdkLiveServerSessionResumptionUpdate
import com.google.genai.kotlin.types.MediaModality as SdkMediaModality
import com.google.genai.kotlin.types.Modality as SdkModality
import com.google.genai.kotlin.types.ModalityTokenCount as SdkModalityTokenCount
import com.google.genai.kotlin.types.ServiceTier as SdkServiceTier
import com.google.genai.kotlin.types.StartSensitivity as SdkStartSensitivity
import com.google.genai.kotlin.types.TrafficType as SdkTrafficType
import com.google.genai.kotlin.types.Transcription as SdkTranscription
import com.google.genai.kotlin.types.TurnCompleteReason as SdkTurnCompleteReason
import com.google.genai.kotlin.types.TurnCoverage as SdkTurnCoverage
import com.google.genai.kotlin.types.UsageMetadata as SdkLiveUsageMetadata
import com.google.genai.kotlin.types.VoiceActivity as SdkVoiceActivity
import com.google.genai.kotlin.types.VoiceActivityType as SdkVoiceActivityType
import com.google.genai.kotlin.types.WordInfo as SdkWordInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Round-trips the live config types through the GenAI SDK. The fully-populated [LiveConnectConfig]
 * case is the one that catches a field dropped by either direction of the converter.
 */
class LiveConvertersTest {

  @Test
  fun modality_roundTripsThroughSdk() {
    for (value in Modality.entries) {
      assertEquals(value, value.toGenaiSdk().toKt())
    }
  }

  @Test
  fun activityHandling_roundTripsThroughSdk() {
    for (value in ActivityHandling.entries) {
      assertEquals(value, value.toGenaiSdk().toKt())
    }
  }

  @Test
  fun turnCoverage_roundTripsThroughSdk() {
    for (value in TurnCoverage.entries) {
      assertEquals(value, value.toGenaiSdk().toKt())
    }
  }

  @Test
  fun startSensitivity_roundTripsThroughSdk() {
    for (value in StartSensitivity.entries) {
      assertEquals(value, value.toGenaiSdk().toKt())
    }
  }

  @Test
  fun endSensitivity_roundTripsThroughSdk() {
    for (value in EndSensitivity.entries) {
      assertEquals(value, value.toGenaiSdk().toKt())
    }
  }

  @Test
  fun voiceActivityType_roundTripsThroughSdk() {
    for (value in VoiceActivityType.entries) {
      assertEquals(value, value.toGenaiSdk().toKt())
    }
  }

  @Test
  fun turnCompleteReason_roundTripsThroughSdk() {
    for (value in TurnCompleteReason.entries) {
      assertEquals(value, value.toGenaiSdk().toKt())
    }
  }

  @Test
  fun enums_unknownSdkValue_fallBackToUnspecified() {
    assertEquals(Modality.MODALITY_UNSPECIFIED, SdkModality("NOT_A_MODALITY").toKt())
    assertEquals(
      ActivityHandling.ACTIVITY_HANDLING_UNSPECIFIED,
      SdkActivityHandling("NOT_A_HANDLING").toKt(),
    )
    assertEquals(TurnCoverage.TURN_COVERAGE_UNSPECIFIED, SdkTurnCoverage("NOT_A_COVERAGE").toKt())
    assertEquals(
      StartSensitivity.START_SENSITIVITY_UNSPECIFIED,
      SdkStartSensitivity("NOT_A_SENSITIVITY").toKt(),
    )
    assertEquals(
      EndSensitivity.END_SENSITIVITY_UNSPECIFIED,
      SdkEndSensitivity("NOT_A_SENSITIVITY").toKt(),
    )
    assertEquals(
      VoiceActivityType.TYPE_UNSPECIFIED,
      SdkVoiceActivityType("NOT_A_VOICE_ACTIVITY_TYPE").toKt(),
    )
    assertEquals(
      TurnCompleteReason.TURN_COMPLETE_REASON_UNSPECIFIED,
      SdkTurnCompleteReason("NOT_A_TURN_COMPLETE_REASON").toKt(),
    )
  }

  @Test
  fun speechConfig_prebuiltVoice_roundTripsThroughSdk() {
    val config =
      SpeechConfig(
        voiceConfig = VoiceConfig(prebuiltVoiceConfig = PrebuiltVoiceConfig(voiceName = "Kore")),
        languageCode = "en-US",
      )

    val sdk = config.toGenaiSdk()

    assertEquals("Kore", sdk.voiceConfig?.prebuiltVoiceConfig?.voiceName)
    assertEquals("en-US", sdk.languageCode)
    assertEquals(config, sdk.fromGenaiSdk())
  }

  @Test
  fun speechConfig_multiSpeaker_roundTripsThroughSdk() {
    val config =
      SpeechConfig(
        multiSpeakerVoiceConfig =
          MultiSpeakerVoiceConfig(
            speakerVoiceConfigs =
              listOf(
                SpeakerVoiceConfig(
                  speaker = "Ada",
                  voiceConfig =
                    VoiceConfig(prebuiltVoiceConfig = PrebuiltVoiceConfig(voiceName = "Kore")),
                ),
                SpeakerVoiceConfig(
                  speaker = "Grace",
                  voiceConfig =
                    VoiceConfig(prebuiltVoiceConfig = PrebuiltVoiceConfig(voiceName = "Puck")),
                ),
              )
          )
      )

    assertEquals(config, config.toGenaiSdk().fromGenaiSdk())
  }

  @Test
  fun replicatedVoiceConfig_roundTripsThroughSdk() {
    val config =
      ReplicatedVoiceConfig(
        mimeType = "audio/wav",
        voiceSampleAudio = byteArrayOf(1, 2, 3),
        consentAudio = byteArrayOf(4, 5, 6),
        voiceConsentSignature = VoiceConsentSignature(signature = "sig-1"),
      )

    val sdk = config.toGenaiSdk()

    assertEquals("audio/wav", sdk.mimeType)
    // ReplicatedVoiceConfig compares its ByteArray fields by content, so equality is meaningful.
    assertEquals(config, sdk.fromGenaiSdk())
  }

  @Test
  fun realtimeInputConfig_roundTripsThroughSdk() {
    val config =
      RealtimeInputConfig(
        automaticActivityDetection =
          AutomaticActivityDetection(
            disabled = false,
            startOfSpeechSensitivity = StartSensitivity.START_SENSITIVITY_HIGH,
            endOfSpeechSensitivity = EndSensitivity.END_SENSITIVITY_LOW,
            prefixPaddingMs = 20,
            silenceDurationMs = 800,
          ),
        activityHandling = ActivityHandling.NO_INTERRUPTION,
        turnCoverage = TurnCoverage.TURN_INCLUDES_ALL_INPUT,
      )

    assertEquals(config, config.toGenaiSdk().fromGenaiSdk())
  }

  @Test
  fun audioTranscriptionConfig_roundTripsThroughSdk() {
    val config =
      AudioTranscriptionConfig(
        languageCodes = listOf("en-US", "pl-PL"),
        customVocabulary = listOf("Gemini"),
        diarization = true,
        wordTimestamp = true,
      )

    assertEquals(config, config.toGenaiSdk().fromGenaiSdk())
  }

  @Test
  fun audioTranscriptionConfig_fromGenaiSdk_dropsDeprecatedFields() {
    val sdk =
      SdkAudioTranscriptionConfig(
        languageCodes = listOf("en-US"),
        languageHints = SdkLanguageHints(languageCodes = listOf("fr-FR")),
        adaptationPhrases = listOf("legacy"),
      )

    val adk = sdk.fromGenaiSdk()

    assertEquals(listOf("en-US"), adk.languageCodes)
    // The deprecated SDK fields have no ADK mirror; converting back leaves them unset.
    assertNull(adk.toGenaiSdk().languageHints)
    assertNull(adk.toGenaiSdk().adaptationPhrases)
  }

  @Test
  fun sessionResumptionConfig_roundTripsThroughSdk() {
    val config = SessionResumptionConfig(handle = "handle-1", transparent = true)

    assertEquals(config, config.toGenaiSdk().fromGenaiSdk())
  }

  @Test
  fun contextWindowCompressionConfig_roundTripsThroughSdk() {
    val config =
      ContextWindowCompressionConfig(
        triggerTokens = 16_000L,
        slidingWindow = SlidingWindow(targetTokens = 8_000L),
      )

    assertEquals(config, config.toGenaiSdk().fromGenaiSdk())
  }

  @Test
  fun liveConnectConfig_everyFieldSet_roundTripsThroughSdk() {
    val config = fullyPopulatedLiveConnectConfig()

    assertEquals(config, config.toGenaiSdk().fromGenaiSdk())
  }

  @Test
  fun liveConnectConfig_toGenaiSdk_mapsEachFieldOntoTheWireShape() {
    val sdk = fullyPopulatedLiveConnectConfig().toGenaiSdk()

    assertEquals(listOf(SdkModality("AUDIO"), SdkModality("TEXT")), sdk.responseModalities)
    // ADK stores these as Float, as GenerateContentConfig does, so widening to the SDK's Double is
    // inexact; compare against the widened Float rather than the decimal literal.
    assertEquals(0.5f.toDouble(), sdk.temperature)
    assertEquals(0.9f.toDouble(), sdk.topP)
    assertEquals(40.0, sdk.topK)
    assertEquals(1024, sdk.maxOutputTokens)
    assertEquals(7, sdk.seed)
    assertEquals(true, sdk.enableAffectiveDialog)
    assertEquals("Kore", sdk.speechConfig?.voiceConfig?.prebuiltVoiceConfig?.voiceName)
    assertEquals("be brief", sdk.systemInstruction?.parts?.single()?.text)
    assertEquals(1, sdk.tools?.size)
    assertEquals("handle-1", sdk.sessionResumption?.handle)
    assertEquals(listOf("en-US"), sdk.inputAudioTranscription?.languageCodes)
    assertEquals(listOf("pl-PL"), sdk.outputAudioTranscription?.languageCodes)
    assertEquals(true, sdk.realtimeInputConfig?.automaticActivityDetection?.disabled)
    assertEquals(16_000L, sdk.contextWindowCompression?.triggerTokens)
    assertEquals(true, sdk.proactivity?.proactiveAudio)
    assertEquals(1, sdk.safetySettings?.size)
  }

  @Test
  fun liveConnectConfig_floatSamplingFields_surviveTheRoundTripExactly() {
    // Float -> Double -> Float is exact, so a value the SDK cannot represent exactly as a Float
    // still comes back unchanged on the ADK side. Only the value on the wire is widened.
    val config = LiveConnectConfig(temperature = 0.1f, topP = 0.9f, topK = 40)

    val backAgain = config.toGenaiSdk().fromGenaiSdk()

    assertEquals(0.1f, backAgain.temperature)
    assertEquals(0.9f, backAgain.topP)
    assertEquals(40, backAgain.topK)
  }

  @Test
  fun liveConnectConfig_toGenaiSdk_leavesUnmirroredSdkFieldsUnset() {
    val sdk = fullyPopulatedLiveConnectConfig().toGenaiSdk()

    // These are unset for two different reasons. `httpOptions` is deliberately not mirrored:
    // it configures the transport rather than the conversation, and a caller who needs it
    // passes their own client instead. The others are simply not mirrored yet. Either way the
    // converter must not invent a value, and these assertions are what enforce that -- adding
    // a mirror for any of them makes its line fail, which is the prompt to move that field out
    // of this list and test it for real.
    assertNull(sdk.httpOptions)
    assertNull(sdk.avatarConfig)
    assertNull(sdk.explicitVadSignal)
    assertNull(sdk.translationConfig)
  }

  @Test
  fun liveConnectConfig_fromGenaiSdk_ignoresUnmirroredSdkFields() {
    val sdk = SdkLiveConnectConfig(explicitVadSignal = true, temperature = 0.25)

    val adk = sdk.fromGenaiSdk()

    assertEquals(0.25f, adk.temperature)
    // Converting back does not resurrect the unmirrored field.
    assertNull(adk.toGenaiSdk().explicitVadSignal)
  }

  @Test
  fun liveConnectConfig_empty_roundTripsToAllNulls() {
    val config = LiveConnectConfig()

    assertEquals(config, config.toGenaiSdk().fromGenaiSdk())
  }

  @Test
  fun wordInfo_roundTripsThroughSdk() {
    val word = WordInfo(word = "hello", startOffset = "0.100s", endOffset = "0.450s")

    assertEquals(word, word.toGenaiSdk().fromGenaiSdk())
  }

  @Test
  fun transcription_roundTripsThroughSdk() {
    val transcription =
      Transcription(
        text = "hello there",
        finished = true,
        languageCode = "en-US",
        speakerLabel = "speaker-1",
        words =
          listOf(
            WordInfo(word = "hello", startOffset = "0.100s", endOffset = "0.450s"),
            WordInfo(word = "there", startOffset = "0.450s", endOffset = "0.900s"),
          ),
      )

    assertEquals(transcription, transcription.toGenaiSdk().fromGenaiSdk())
  }

  @Test
  fun transcription_nestedWords_surviveTheRoundTrip() {
    val sdk =
      SdkTranscription(
        text = "hi",
        words = listOf(SdkWordInfo(word = "hi", startOffset = "0s", endOffset = "0.200s")),
      )

    val adk = sdk.fromGenaiSdk()

    assertEquals(listOf(WordInfo(word = "hi", startOffset = "0s", endOffset = "0.200s")), adk.words)
  }

  @Test
  fun voiceActivity_roundTripsThroughSdk() {
    val activity =
      VoiceActivity(
        voiceActivityType = VoiceActivityType.ACTIVITY_START,
        audioOffset = 1_500.milliseconds,
      )

    assertEquals(activity, activity.toGenaiSdk().fromGenaiSdk())
  }

  @Test
  fun voiceActivity_unknownSdkType_fallsBackToUnspecified() {
    val sdk =
      SdkVoiceActivity(
        voiceActivityType = SdkVoiceActivityType("NOT_A_VOICE_ACTIVITY_TYPE"),
        audioOffset = 2.seconds,
      )

    val adk = sdk.fromGenaiSdk()

    assertEquals(VoiceActivityType.TYPE_UNSPECIFIED, adk.voiceActivityType)
    assertEquals(2.seconds, adk.audioOffset)
  }

  @Test
  fun liveServerGoAway_roundTripsThroughSdk() {
    val goAway = LiveServerGoAway(timeLeft = 30.seconds)

    assertEquals(goAway, goAway.toGenaiSdk().fromGenaiSdk())
    assertEquals(30.seconds, SdkLiveServerGoAway(timeLeft = 30.seconds).fromGenaiSdk().timeLeft)
  }

  @Test
  fun liveServerSessionResumptionUpdate_roundTripsThroughSdk() {
    val update =
      LiveServerSessionResumptionUpdate(
        newHandle = "handle-2",
        resumable = true,
        lastConsumedClientMessageIndex = 42L,
      )

    assertEquals(update, update.toGenaiSdk().fromGenaiSdk())
    assertEquals(
      42L,
      SdkLiveServerSessionResumptionUpdate(lastConsumedClientMessageIndex = 42L)
        .fromGenaiSdk()
        .lastConsumedClientMessageIndex,
    )
  }

  @Test
  fun liveUsageMetadata_fromGenaiSdk_crossesResponseCountsOntoCandidates() {
    val sdk =
      SdkLiveUsageMetadata(
        promptTokenCount = 11,
        responseTokenCount = 22,
        totalTokenCount = 33,
        thoughtsTokenCount = 44,
        toolUsePromptTokenCount = 55,
        cachedContentTokenCount = 66,
        promptTokensDetails =
          listOf(SdkModalityTokenCount(modality = SdkMediaModality.TEXT, tokenCount = 11)),
        responseTokensDetails =
          listOf(SdkModalityTokenCount(modality = SdkMediaModality.AUDIO, tokenCount = 22)),
        toolUsePromptTokensDetails =
          listOf(SdkModalityTokenCount(modality = SdkMediaModality.TEXT, tokenCount = 55)),
      )

    val adk = sdk.fromGenaiSdk()

    // The crossing this converter exists for: the live `response*` fields land on `candidates*`.
    assertEquals(22, adk.candidatesTokenCount)
    assertEquals(
      listOf(ModalityTokenCount(modality = MediaModality.AUDIO, tokenCount = 22)),
      adk.candidatesTokensDetails,
    )
    assertEquals(11, adk.promptTokenCount)
    assertEquals(
      listOf(ModalityTokenCount(modality = MediaModality.TEXT, tokenCount = 11)),
      adk.promptTokensDetails,
    )
    assertEquals(33, adk.totalTokenCount)
    assertEquals(44, adk.thoughtsTokenCount)
    assertEquals(55, adk.toolUsePromptTokenCount)
    assertEquals(66, adk.cachedContentTokenCount)
    assertEquals(
      listOf(ModalityTokenCount(modality = MediaModality.TEXT, tokenCount = 55)),
      adk.toolUsePromptTokensDetails,
    )
  }

  @Test
  fun liveUsageMetadata_fromGenaiSdk_dropsFieldsWithNoAdkCounterpart() {
    val sdk =
      SdkLiveUsageMetadata(
        responseTokenCount = 1,
        serviceTier = SdkServiceTier("SERVICE_TIER_STANDARD"),
        trafficType = SdkTrafficType("ON_DEMAND"),
        cacheTokensDetails =
          listOf(SdkModalityTokenCount(modality = SdkMediaModality.TEXT, tokenCount = 9)),
      )

    val adk = sdk.fromGenaiSdk()

    assertEquals(1, adk.candidatesTokenCount)
    // `trafficType` has an ADK counterpart but neither converter populates it; the other two
    // have none at all. All three are dropped, which the converter's KDoc states.
    assertNull(adk.trafficType)
  }

  private fun fullyPopulatedLiveConnectConfig(): LiveConnectConfig =
    LiveConnectConfig(
      responseModalities = listOf(Modality.AUDIO, Modality.TEXT),
      temperature = 0.5f,
      topP = 0.9f,
      topK = 40,
      maxOutputTokens = 1024,
      mediaResolution = MediaResolution.MEDIA_RESOLUTION_MEDIUM,
      seed = 7,
      speechConfig =
        SpeechConfig(
          voiceConfig = VoiceConfig(prebuiltVoiceConfig = PrebuiltVoiceConfig(voiceName = "Kore")),
          languageCode = "en-US",
        ),
      thinkingConfig = ThinkingConfig(includeThoughts = true, thinkingBudget = 128),
      enableAffectiveDialog = true,
      systemInstruction = Content(role = "user", parts = listOf(Part(text = "be brief"))),
      tools = listOf(Tool(googleSearch = GoogleSearch())),
      sessionResumption = SessionResumptionConfig(handle = "handle-1", transparent = true),
      inputAudioTranscription = AudioTranscriptionConfig(languageCodes = listOf("en-US")),
      outputAudioTranscription = AudioTranscriptionConfig(languageCodes = listOf("pl-PL")),
      realtimeInputConfig =
        RealtimeInputConfig(
          automaticActivityDetection =
            AutomaticActivityDetection(
              disabled = true,
              startOfSpeechSensitivity = StartSensitivity.START_SENSITIVITY_LOW,
              endOfSpeechSensitivity = EndSensitivity.END_SENSITIVITY_HIGH,
              prefixPaddingMs = 20,
              silenceDurationMs = 800,
            ),
          activityHandling = ActivityHandling.START_OF_ACTIVITY_INTERRUPTS,
          turnCoverage = TurnCoverage.TURN_INCLUDES_ONLY_ACTIVITY,
        ),
      contextWindowCompression =
        ContextWindowCompressionConfig(
          triggerTokens = 16_000L,
          slidingWindow = SlidingWindow(targetTokens = 8_000L),
        ),
      proactivity = ProactivityConfig(proactiveAudio = true),
      safetySettings =
        listOf(
          SafetySetting(
            category = HarmCategory.HARM_CATEGORY_HATE_SPEECH,
            threshold = HarmBlockThreshold.BLOCK_ONLY_HIGH,
          )
        ),
    )
}
