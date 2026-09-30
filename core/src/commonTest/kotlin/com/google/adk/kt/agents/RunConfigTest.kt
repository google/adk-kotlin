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

package com.google.adk.kt.agents

import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.types.ActivityHandling
import com.google.adk.kt.types.AudioTranscriptionConfig
import com.google.adk.kt.types.ContextWindowCompressionConfig
import com.google.adk.kt.types.Modality
import com.google.adk.kt.types.ProactivityConfig
import com.google.adk.kt.types.RealtimeInputConfig
import com.google.adk.kt.types.SessionResumptionConfig
import com.google.adk.kt.types.SpeechConfig
import com.google.adk.kt.types.TranslationConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// Opts in to the Java-interop surface because these tests exist to verify it.
@OptIn(AdkJavaInteropApi::class)
class RunConfigTest {

  @Test
  fun testRunConfigDefaults() {
    val config = RunConfig()
    assertEquals(StreamingMode.NONE, config.streamingMode)
    assertEquals(500, config.maxLlmCalls)
    assertEquals(null, config.customMetadata)
    assertEquals(null, config.responseModalities)
    assertEquals(null, config.speechConfig)
    assertEquals(null, config.realtimeInputConfig)
    assertEquals(null, config.explicitVadSignal)
    assertEquals(null, config.translationConfig)
    assertEquals(null, config.enableAffectiveDialog)
    assertEquals(null, config.proactivity)
    assertEquals(null, config.sessionResumption)
    assertEquals(null, config.contextWindowCompression)
  }

  @Test
  fun audioTranscription_byDefault_isEnabledInBothDirections() {
    // Matches ADK Python: the default transcribes both directions unless the caller passes null.
    val config = RunConfig()
    assertEquals(AudioTranscriptionConfig(), config.outputAudioTranscription)
    assertEquals(AudioTranscriptionConfig(), config.inputAudioTranscription)
  }

  @Test
  fun maxLlmCalls_customValue_isRetained() {
    assertEquals(42, RunConfig(maxLlmCalls = 42).maxLlmCalls)
  }

  @Test
  fun maxLlmCalls_intMaxValue_throwsIllegalArgumentException() {
    assertFailsWith<IllegalArgumentException> { RunConfig(maxLlmCalls = Int.MAX_VALUE) }
  }

  @Test
  fun maxLlmCalls_nonPositiveValue_isAllowedAndDisablesEnforcement() {
    // Non-positive values are valid (a warning is logged) and disable the cap.
    assertEquals(0, RunConfig(maxLlmCalls = 0).maxLlmCalls)
    assertEquals(-1, RunConfig(maxLlmCalls = -1).maxLlmCalls)
  }

  @Test
  fun toBuilder_matchesCopy() {
    val config =
      RunConfig(
        streamingMode = StreamingMode.SSE,
        maxLlmCalls = 42,
        customMetadata = mapOf("key" to "value"),
        responseModalities = listOf(Modality.AUDIO),
        speechConfig = SpeechConfig(languageCode = "en-US"),
        outputAudioTranscription = null,
        inputAudioTranscription = AudioTranscriptionConfig(languageCodes = listOf("fr-FR")),
        realtimeInputConfig =
          RealtimeInputConfig(activityHandling = ActivityHandling.NO_INTERRUPTION),
        explicitVadSignal = true,
        translationConfig = TranslationConfig(targetLanguageCode = "es"),
        enableAffectiveDialog = true,
        proactivity = ProactivityConfig(proactiveAudio = true),
        sessionResumption = SessionResumptionConfig(handle = "resumption-handle"),
        contextWindowCompression = ContextWindowCompressionConfig(triggerTokens = 1024L),
      )

    assertEquals(config.copy(), config.toBuilder().build())
    assertEquals(config.copy(maxLlmCalls = 7), config.toBuilder().maxLlmCalls(7).build())
  }

  @Test
  fun defaults_toString_enumeratesEveryProperty() {
    // A tripwire: toString names every property, so adding one to RunConfig fails here.
    val transcription =
      "AudioTranscriptionConfig(languageCodes=null, customVocabulary=null, diarization=null," +
        " wordTimestamp=null)"
    assertEquals(
      "RunConfig(streamingMode=NONE, maxLlmCalls=500, customMetadata=null," +
        " responseModalities=null, speechConfig=null," +
        " outputAudioTranscription=$transcription, inputAudioTranscription=$transcription," +
        " realtimeInputConfig=null, explicitVadSignal=null, translationConfig=null," +
        " enableAffectiveDialog=null, proactivity=null," +
        " sessionResumption=null, contextWindowCompression=null)",
      RunConfig().toString(),
    )
  }

  @Test
  fun builder_noPropertySet_matchesConstructorDefaults() {
    // Data-class equality compares every property, so one assertion catches a drift.
    assertEquals(RunConfig(), RunConfig.builder().build())
  }

  @Test
  fun builder_everyPropertySet_matchesConstructor() {
    // Enumerated by hand because reflection is banned repo-wide; non-defaults catch drops.
    val customMetadata = mapOf<String, Any>("key" to "value")
    val speechConfig = SpeechConfig(languageCode = "en-US")
    val outputAudioTranscription = AudioTranscriptionConfig(languageCodes = listOf("en-US"))
    val inputAudioTranscription = AudioTranscriptionConfig(languageCodes = listOf("fr-FR"))
    val realtimeInputConfig =
      RealtimeInputConfig(activityHandling = ActivityHandling.NO_INTERRUPTION)
    val proactivity = ProactivityConfig(proactiveAudio = true)
    val sessionResumption = SessionResumptionConfig(handle = "resumption-handle")
    val contextWindowCompression = ContextWindowCompressionConfig(triggerTokens = 1024L)
    val translationConfig = TranslationConfig(targetLanguageCode = "es", echoTargetLanguage = true)

    val built =
      RunConfig.builder()
        .streamingMode(StreamingMode.BIDI)
        .maxLlmCalls(7)
        .customMetadata(customMetadata)
        .responseModalities(listOf(Modality.AUDIO, Modality.TEXT))
        .speechConfig(speechConfig)
        .outputAudioTranscription(outputAudioTranscription)
        .inputAudioTranscription(inputAudioTranscription)
        .realtimeInputConfig(realtimeInputConfig)
        .explicitVadSignal(true)
        .translationConfig(translationConfig)
        .enableAffectiveDialog(true)
        .proactivity(proactivity)
        .sessionResumption(sessionResumption)
        .contextWindowCompression(contextWindowCompression)
        .build()

    assertEquals(
      RunConfig(
        streamingMode = StreamingMode.BIDI,
        maxLlmCalls = 7,
        customMetadata = customMetadata,
        responseModalities = listOf(Modality.AUDIO, Modality.TEXT),
        speechConfig = speechConfig,
        outputAudioTranscription = outputAudioTranscription,
        inputAudioTranscription = inputAudioTranscription,
        realtimeInputConfig = realtimeInputConfig,
        explicitVadSignal = true,
        translationConfig = translationConfig,
        enableAffectiveDialog = true,
        proactivity = proactivity,
        sessionResumption = sessionResumption,
        contextWindowCompression = contextWindowCompression,
      ),
      built,
    )
  }

  @Test
  fun builder_audioTranscriptionSetToNull_disablesIt() {
    // The transcription defaults are non-null, so null must survive the builder to reach Java.
    val config =
      RunConfig.builder().outputAudioTranscription(null).inputAudioTranscription(null).build()

    assertEquals(null, config.outputAudioTranscription)
    assertEquals(null, config.inputAudioTranscription)
  }
}
