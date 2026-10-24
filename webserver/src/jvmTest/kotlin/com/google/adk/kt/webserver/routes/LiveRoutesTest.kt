/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:OptIn(ExperimentalLiveApi::class)

package com.google.adk.kt.webserver.routes

import com.google.adk.kt.agents.RunConfig
import com.google.adk.kt.agents.StreamingMode
import com.google.adk.kt.annotations.ExperimentalLiveApi
import com.google.adk.kt.types.Modality
import com.google.adk.kt.types.ProactivityConfig
import com.google.adk.kt.types.SessionResumptionConfig
import com.google.common.truth.Truth.assertThat
import io.ktor.http.parametersOf
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Parses the three required ids plus [extra] and maps the result to a [RunConfig], so a case can
 * assert that a value taken off the wire reaches the config rather than only that parsing
 * succeeded.
 */
private fun runConfigFrom(vararg extra: Pair<String, List<String>>): RunConfig {
  val base =
    listOf("app_name" to listOf("a"), "user_id" to listOf("u"), "session_id" to listOf("s"))
  val result = parseLiveRunParams(parametersOf(*(base + extra).toTypedArray()))
  return (result as LiveParamsResult.Success).params.runConfig
}

@RunWith(JUnit4::class)
class LiveRoutesTest {

  @Test
  fun parseLiveRunParams_allIdsPresent_defaultsToAudio() {
    val result =
      parseLiveRunParams(
        parametersOf(
          "app_name" to listOf("a"),
          "user_id" to listOf("u"),
          "session_id" to listOf("s"),
        )
      )

    val params = (result as LiveParamsResult.Success).params
    assertThat(params.appName).isEqualTo("a")
    assertThat(params.userId).isEqualTo("u")
    assertThat(params.sessionId).isEqualTo("s")
    assertThat(params.runConfig.live.responseModalities).containsExactly(Modality.AUDIO)
  }

  @Test
  fun parseLiveRunParams_repeatedModalities_keepsAllInOrder() {
    val result =
      parseLiveRunParams(
        parametersOf(
          "app_name" to listOf("a"),
          "user_id" to listOf("u"),
          "session_id" to listOf("s"),
          "modalities" to listOf("TEXT", "AUDIO", "VIDEO"),
        )
      )

    val config = (result as LiveParamsResult.Success).params.runConfig
    assertThat(config.live.responseModalities)
      .containsExactly(Modality.TEXT, Modality.AUDIO, Modality.VIDEO)
      .inOrder()
  }

  @Test
  fun parseLiveRunParams_missingUserId_isInvalid() {
    val result =
      parseLiveRunParams(parametersOf("app_name" to listOf("a"), "session_id" to listOf("s")))

    assertThat(result).isEqualTo(LiveParamsResult.Invalid("Missing user_id query parameter"))
  }

  @Test
  fun parseLiveRunParams_missingAppName_isInvalid() {
    val result =
      parseLiveRunParams(parametersOf("user_id" to listOf("u"), "session_id" to listOf("s")))

    assertThat(result).isEqualTo(LiveParamsResult.Invalid("Missing app_name query parameter"))
  }

  @Test
  fun parseLiveRunParams_emptyAppName_isInvalidAsInAdkPython() {
    val result =
      parseLiveRunParams(
        parametersOf(
          "app_name" to listOf(""),
          "user_id" to listOf("u"),
          "session_id" to listOf("s"),
        )
      )

    assertThat(result).isEqualTo(LiveParamsResult.Invalid("Missing app_name query parameter"))
  }

  @Test
  fun parseLiveRunParams_missingSessionId_isInvalid() {
    val result =
      parseLiveRunParams(parametersOf("app_name" to listOf("a"), "user_id" to listOf("u")))

    assertThat(result).isEqualTo(LiveParamsResult.Invalid("Missing session_id query parameter"))
  }

  @Test
  fun parseLiveRunParams_repeatedParameter_lastValueWinsAsInFastApi() {
    val result =
      parseLiveRunParams(
        parametersOf(
          "app_name" to listOf("a"),
          "user_id" to listOf("u1", "u2"),
          "session_id" to listOf("s"),
          "save_live_blob" to listOf("maybe", "true"),
        )
      )

    val params = (result as LiveParamsResult.Success).params
    assertThat(params.userId).isEqualTo("u2")
    assertThat(params.runConfig.live.saveLiveBlob).isTrue()
  }

  @Test
  fun parseLiveRunParams_flagWithoutValue_isInvalid() {
    // Some Ktor engines store `?save_live_blob` with no value; FastAPI reads it as "".
    val result =
      parseLiveRunParams(
        parametersOf(
          "app_name" to listOf("a"),
          "user_id" to listOf("u"),
          "session_id" to listOf("s"),
          "save_live_blob" to emptyList(),
        )
      )

    assertThat(result)
      .isEqualTo(LiveParamsResult.Invalid("Invalid boolean query parameter save_live_blob"))
  }

  @Test
  fun parseLiveRunParams_modalitiesWithoutValue_isInvalid() {
    val result =
      parseLiveRunParams(
        parametersOf(
          "app_name" to listOf("a"),
          "user_id" to listOf("u"),
          "session_id" to listOf("s"),
          "modalities" to emptyList(),
        )
      )

    assertThat(result).isEqualTo(LiveParamsResult.Invalid("Unsupported modality"))
  }

  @Test
  fun parseLiveRunParams_lowercaseModality_isInvalid() {
    val result =
      parseLiveRunParams(
        parametersOf(
          "app_name" to listOf("a"),
          "user_id" to listOf("u"),
          "session_id" to listOf("s"),
          "modalities" to listOf("audio"),
        )
      )

    assertThat(result).isEqualTo(LiveParamsResult.Invalid("Unsupported modality"))
  }

  @Test
  fun parseLiveRunParams_unknownParameter_isIgnored() {
    val config = runConfigFrom("unknown" to listOf("x"))

    assertThat(config).isEqualTo(runConfigFrom())
  }

  @Test
  fun parseLiveRunParams_unsupportedModality_isInvalid() {
    val result =
      parseLiveRunParams(
        parametersOf(
          "app_name" to listOf("a"),
          "user_id" to listOf("u"),
          "session_id" to listOf("s"),
          "modalities" to listOf("IMAGE"),
        )
      )

    assertThat(result).isEqualTo(LiveParamsResult.Invalid("Unsupported modality"))
  }

  // Each optional flag must reach its RunConfig field, not merely parse.

  @Test
  fun parseLiveRunParams_noOptionalParams_leavesLiveConfigFieldsAtTheirDefaults() {
    val config = runConfigFrom()

    assertThat(config.streamingMode).isEqualTo(StreamingMode.NONE)
    assertThat(config.live.proactivity).isNull()
    assertThat(config.live.enableAffectiveDialog).isNull()
    assertThat(config.live.sessionResumption).isNull()
    assertThat(config.live.realtimeInputConfig).isNull()
    assertThat(config.live.saveLiveBlob).isFalse()
    assertThat(config.live.explicitVadSignal).isNull()
  }

  @Test
  fun parseLiveRunParams_proactiveAudioTrue_setsProactivity() {
    val config = runConfigFrom("proactive_audio" to listOf("true"))

    assertThat(config.live.proactivity).isEqualTo(ProactivityConfig(proactiveAudio = true))
  }

  @Test
  fun parseLiveRunParams_proactiveAudioFalse_setsProactivityOff() {
    val config = runConfigFrom("proactive_audio" to listOf("false"))

    assertThat(config.live.proactivity).isEqualTo(ProactivityConfig(proactiveAudio = false))
  }

  @Test
  fun parseLiveRunParams_enableAffectiveDialogTrue_setsAffectiveDialog() {
    val config = runConfigFrom("enable_affective_dialog" to listOf("true"))

    assertThat(config.live.enableAffectiveDialog).isTrue()
  }

  @Test
  fun parseLiveRunParams_enableSessionResumptionTrue_asksForTransparentResumption() {
    val config = runConfigFrom("enable_session_resumption" to listOf("true"))

    assertThat(config.live.sessionResumption).isEqualTo(SessionResumptionConfig(transparent = true))
  }

  @Test
  fun parseLiveRunParams_enableSessionResumptionFalse_asksForNonTransparentResumption() {
    // As in ADK Python, false still asks for resumption, only not transparent.
    val config = runConfigFrom("enable_session_resumption" to listOf("false"))

    assertThat(config.live.sessionResumption)
      .isEqualTo(SessionResumptionConfig(transparent = false))
  }

  @Test
  fun parseLiveRunParams_saveLiveBlobTrue_setsSaveLiveBlob() {
    val config = runConfigFrom("save_live_blob" to listOf("true"))

    assertThat(config.live.saveLiveBlob).isTrue()
  }

  @Test
  fun parseLiveRunParams_explicitVadSignalTrue_setsExplicitVadSignal() {
    val config = runConfigFrom("explicit_vad_signal" to listOf("true"))

    assertThat(config.live.explicitVadSignal).isTrue()
  }

  @Test
  fun parseLiveRunParams_explicitVadSignalFalse_setsExplicitVadSignalFalse() {
    val config = runConfigFrom("explicit_vad_signal" to listOf("false"))

    assertThat(config.live.explicitVadSignal).isFalse()
  }

  @Test
  fun parseLiveRunParams_pydanticBooleanSpellings_parseAsInAdkPython() {
    val spellings =
      mapOf(
        "true" to true,
        "True" to true,
        "1" to true,
        "yes" to true,
        "on" to true,
        "t" to true,
        "Y" to true,
        "false" to false,
        "0" to false,
        "no" to false,
        "OFF" to false,
        "f" to false,
        "n" to false,
      )
    val parsed = spellings.mapValues { (spelling, _) ->
      runConfigFrom("save_live_blob" to listOf(spelling)).live.saveLiveBlob
    }
    assertThat(parsed).isEqualTo(spellings)
    val config =
      runConfigFrom(
        "proactive_audio" to listOf("1"),
        "enable_affective_dialog" to listOf("yes"),
        "enable_session_resumption" to listOf("on"),
        "explicit_vad_signal" to listOf("y"),
      )
    assertThat(config.live.proactivity).isEqualTo(ProactivityConfig(proactiveAudio = true))
    assertThat(config.live.enableAffectiveDialog).isTrue()
    assertThat(config.live.sessionResumption).isEqualTo(SessionResumptionConfig(transparent = true))
    assertThat(config.live.explicitVadSignal).isTrue()
  }

  @Test
  fun parseLiveRunParams_nonBooleanFlag_isInvalid() {
    val result =
      parseLiveRunParams(
        parametersOf(
          "app_name" to listOf("a"),
          "user_id" to listOf("u"),
          "session_id" to listOf("s"),
          "save_live_blob" to listOf("maybe"),
        )
      )

    assertThat(result)
      .isEqualTo(LiveParamsResult.Invalid("Invalid boolean query parameter save_live_blob"))
  }
}
