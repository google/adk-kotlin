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
import com.google.adk.kt.types.AvatarConfig
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
  return (result as LiveRunParamsResult.Success).params.runConfig
}

@RunWith(JUnit4::class)
class LiveParamsTest {

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

    with((result as LiveRunParamsResult.Success).params) {
      assertThat(appName).isEqualTo("a")
      assertThat(userId).isEqualTo("u")
      assertThat(sessionId).isEqualTo("s")
      assertThat(runConfig.live.responseModalities).containsExactly(Modality.AUDIO)
    }
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

    val config = (result as LiveRunParamsResult.Success).params.runConfig
    assertThat(config.live.responseModalities)
      .containsExactly(Modality.TEXT, Modality.AUDIO, Modality.VIDEO)
      .inOrder()
  }

  @Test
  fun parseLiveRunParams_videoAmongModalities_getsTheDefaultAvatar() {
    val config = runConfigFrom("modalities" to listOf("AUDIO", "VIDEO"))

    assertThat(config.live.avatarConfig).isEqualTo(AvatarConfig(avatarName = "Kai"))
  }

  @Test
  fun parseLiveRunParams_audioOnly_getsNoAvatar() {
    val config = runConfigFrom("modalities" to listOf("AUDIO"))

    assertThat(config.live.avatarConfig).isNull()
  }

  @Test
  fun parseLiveRunParams_missingUserId_isInvalid() {
    val result =
      parseLiveRunParams(parametersOf("app_name" to listOf("a"), "session_id" to listOf("s")))

    assertThat(result).isEqualTo(LiveRunParamsResult.Invalid("Missing user_id query parameter"))
  }

  @Test
  fun parseLiveRunParams_missingAppName_isInvalid() {
    val result =
      parseLiveRunParams(parametersOf("user_id" to listOf("u"), "session_id" to listOf("s")))

    assertThat(result).isEqualTo(LiveRunParamsResult.Invalid("Missing app_name query parameter"))
  }

  @Test
  fun parseLiveRunParams_emptyAppName_isInvalid() {
    val result =
      parseLiveRunParams(
        parametersOf(
          "app_name" to listOf(""),
          "user_id" to listOf("u"),
          "session_id" to listOf("s"),
        )
      )

    assertThat(result).isEqualTo(LiveRunParamsResult.Invalid("Missing app_name query parameter"))
  }

  @Test
  fun parseLiveRunParams_missingSessionId_isInvalid() {
    val result =
      parseLiveRunParams(parametersOf("app_name" to listOf("a"), "user_id" to listOf("u")))

    assertThat(result).isEqualTo(LiveRunParamsResult.Invalid("Missing session_id query parameter"))
  }

  @Test
  fun parseLiveRunParams_repeatedParameter_lastValueWins() {
    val result =
      parseLiveRunParams(
        parametersOf(
          "app_name" to listOf("a"),
          "user_id" to listOf("u1", "u2"),
          "session_id" to listOf("s"),
          "save_live_blob" to listOf("maybe", "true"),
        )
      )

    with((result as LiveRunParamsResult.Success).params) {
      assertThat(userId).isEqualTo("u2")
      assertThat(runConfig.live.saveLiveBlob).isTrue()
    }
  }

  @Test
  fun parseLiveRunParams_flagWithoutValue_isInvalid() {
    // Some Ktor engines store `?save_live_blob` with no value; the parser reads it as "".
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
      .isEqualTo(LiveRunParamsResult.Invalid("Invalid boolean query parameter save_live_blob"))
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

    assertThat(result).isEqualTo(LiveRunParamsResult.Invalid("Unsupported modality"))
  }

  @Test
  fun parseLiveRunParams_emptyModalitiesValue_isInvalid() {
    // `?modalities=` arrives as one empty value, not as no value.
    val result =
      parseLiveRunParams(
        parametersOf(
          "app_name" to listOf("a"),
          "user_id" to listOf("u"),
          "session_id" to listOf("s"),
          "modalities" to listOf(""),
        )
      )

    assertThat(result).isEqualTo(LiveRunParamsResult.Invalid("Unsupported modality"))
  }

  @Test
  fun parseLiveRunParams_emptyUserIdAndSessionId_areAccepted() {
    val result =
      parseLiveRunParams(
        parametersOf("app_name" to listOf("a"), "user_id" to listOf(""), "session_id" to listOf(""))
      )

    with((result as LiveRunParamsResult.Success).params) {
      assertThat(userId).isEmpty()
      assertThat(sessionId).isEmpty()
    }
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

    assertThat(result).isEqualTo(LiveRunParamsResult.Invalid("Unsupported modality"))
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

    assertThat(result).isEqualTo(LiveRunParamsResult.Invalid("Unsupported modality"))
  }

  // Each optional flag must reach its RunConfig field, not merely parse.

  @Test
  fun parseLiveRunParams_noOptionalParams_leavesLiveConfigFieldsAtTheirDefaults() {
    val config = runConfigFrom()

    assertThat(config.streamingMode).isEqualTo(StreamingMode.NONE)
    with(config.live) {
      assertThat(proactivity).isNull()
      assertThat(enableAffectiveDialog).isNull()
      assertThat(sessionResumption).isNull()
      assertThat(realtimeInputConfig).isNull()
      assertThat(saveLiveBlob).isFalse()
      assertThat(explicitVadSignal).isNull()
    }
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
  fun parseLiveRunParams_enableAffectiveDialogFalse_setsAffectiveDialogOff() {
    val config = runConfigFrom("enable_affective_dialog" to listOf("false"))

    assertThat(config.live.enableAffectiveDialog).isFalse()
  }

  @Test
  fun parseLiveRunParams_enableSessionResumptionTrue_leavesTransparentUnset() {
    val config = runConfigFrom("enable_session_resumption" to listOf("true"))

    assertThat(config.live.sessionResumption).isEqualTo(SessionResumptionConfig())
  }

  @Test
  fun parseLiveRunParams_enableSessionResumptionFalse_setsTransparentFalse() {
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
  fun parseLiveRunParams_booleanSpellings_parseCaseInsensitively() {
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
    with(config.live) {
      assertThat(proactivity).isEqualTo(ProactivityConfig(proactiveAudio = true))
      assertThat(enableAffectiveDialog).isTrue()
      assertThat(sessionResumption).isEqualTo(SessionResumptionConfig())
      assertThat(explicitVadSignal).isTrue()
    }
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
      .isEqualTo(LiveRunParamsResult.Invalid("Invalid boolean query parameter save_live_blob"))
  }
}
