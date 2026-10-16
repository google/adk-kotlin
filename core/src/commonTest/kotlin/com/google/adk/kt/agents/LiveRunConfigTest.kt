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
import com.google.adk.kt.annotations.ExperimentalLiveApi
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

// Opts in to the Java-interop builders under test and to the experimental live settings.
@OptIn(AdkJavaInteropApi::class, ExperimentalLiveApi::class)
class LiveRunConfigTest {

  @Test
  fun toBuilder_matchesCopy() {
    // Every value differs from its default and from the others, so a misrouted setter fails too.
    val config =
      LiveRunConfig(
        responseModalities = listOf(Modality.AUDIO),
        speechConfig = SpeechConfig(languageCode = "en-US"),
        outputAudioTranscription = null,
        inputAudioTranscription = AudioTranscriptionConfig(languageCodes = listOf("fr-FR")),
        realtimeInputConfig =
          RealtimeInputConfig(activityHandling = ActivityHandling.NO_INTERRUPTION),
        explicitVadSignal = true,
        translationConfig = TranslationConfig(targetLanguageCode = "es"),
        enableAffectiveDialog = false,
        proactivity = ProactivityConfig(proactiveAudio = true),
        sessionResumption = SessionResumptionConfig(handle = "resumption-handle"),
        contextWindowCompression = ContextWindowCompressionConfig(triggerTokens = 1024L),
        saveLiveBlob = true,
      )

    assertEquals(config.copy(), config.toBuilder().build())
    assertEquals(config.copy(saveLiveBlob = false), config.toBuilder().saveLiveBlob(false).build())
  }

  @Test
  fun defaults_toString_enumeratesEveryProperty() {
    // A tripwire for new LiveRunConfig properties. The defaults match ADK Python, which
    // transcribes both directions; the nested transcription text is derived.
    val transcription = AudioTranscriptionConfig().toString()
    assertEquals(
      "LiveRunConfig(responseModalities=[], speechConfig=null," +
        " outputAudioTranscription=$transcription, inputAudioTranscription=$transcription," +
        " realtimeInputConfig=null, explicitVadSignal=null, translationConfig=null," +
        " enableAffectiveDialog=null, proactivity=null," +
        " sessionResumption=null, contextWindowCompression=null, saveLiveBlob=false)",
      LiveRunConfig().toString(),
    )
  }

  @Test
  fun builder_noPropertySet_matchesConstructorDefaults() {
    // Data-class equality compares every property, so one assertion catches a drift.
    assertEquals(LiveRunConfig(), LiveRunConfig.builder().build())
  }

  @Test
  fun builder_audioTranscriptionSetToNull_disablesIt() {
    // The transcription defaults are non-null, so null must survive the builder to reach Java.
    val config =
      LiveRunConfig.builder().outputAudioTranscription(null).inputAudioTranscription(null).build()

    assertEquals(null, config.outputAudioTranscription)
    assertEquals(null, config.inputAudioTranscription)
  }
}
