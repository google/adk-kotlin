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

import com.google.adk.kt.annotations.AdkJavaInteropApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LiveConnectConfigTest {
  @OptIn(AdkJavaInteropApi::class)
  @Test
  fun toBuilder_everyPropertySet_matchesCopy() {
    // A distinct non-default value for every one of the 21 properties, so a swap is caught.
    val config =
      LiveConnectConfig(
        responseModalities = listOf(Modality.AUDIO),
        temperature = 0.1f,
        topP = 0.2f,
        topK = 3,
        maxOutputTokens = 4,
        mediaResolution = MediaResolution.MEDIA_RESOLUTION_LOW,
        seed = 6,
        speechConfig = SpeechConfig(languageCode = "en-US"),
        thinkingConfig = ThinkingConfig(thinkingBudget = 1),
        enableAffectiveDialog = true,
        systemInstruction = Content(role = Role.SYSTEM, parts = listOf(Part(text = "si"))),
        tools =
          listOf(
            Tool(functionDeclarations = listOf(FunctionDeclaration(name = "f", description = "d")))
          ),
        sessionResumption = SessionResumptionConfig(handle = "h"),
        inputAudioTranscription = AudioTranscriptionConfig(languageCodes = listOf("fr")),
        outputAudioTranscription = AudioTranscriptionConfig(languageCodes = listOf("de")),
        realtimeInputConfig =
          RealtimeInputConfig(activityHandling = ActivityHandling.NO_INTERRUPTION),
        contextWindowCompression = ContextWindowCompressionConfig(triggerTokens = 1024L),
        proactivity = ProactivityConfig(proactiveAudio = true),
        safetySettings =
          listOf(
            SafetySetting(
              category = HarmCategory.HARM_CATEGORY_HARASSMENT,
              threshold = HarmBlockThreshold.BLOCK_NONE,
            )
          ),
        explicitVadSignal = true,
        translationConfig = TranslationConfig(targetLanguageCode = "es", echoTargetLanguage = true),
      )

    assertEquals(config.copy(), config.toBuilder().build())
    assertEquals(config.copy(seed = 5), config.toBuilder().seed(5).build())
  }

  @OptIn(AdkJavaInteropApi::class)
  @Test
  fun builder_noPropertySet_matchesConstructorDefaults() {
    assertEquals(LiveConnectConfig(), LiveConnectConfig.builder().build())
  }

  @OptIn(AdkJavaInteropApi::class)
  @Test
  fun builder_emptyResponseModalities_leavesItUnset() {
    val config = LiveConnectConfig.builder().responseModalities(emptyList()).build()

    assertNull(config.responseModalities)
    assertEquals(LiveConnectConfig(), config)
  }

  @OptIn(AdkJavaInteropApi::class)
  @Test
  fun builder_emptyTools_leavesItUnset() {
    val config = LiveConnectConfig.builder().tools(emptyList()).build()

    assertNull(config.tools)
    assertEquals(LiveConnectConfig(), config)
  }

  @OptIn(AdkJavaInteropApi::class)
  @Test
  fun builder_emptySafetySettings_leavesItUnset() {
    val config = LiveConnectConfig.builder().safetySettings(emptyList()).build()

    assertNull(config.safetySettings)
    assertEquals(LiveConnectConfig(), config)
  }
}
