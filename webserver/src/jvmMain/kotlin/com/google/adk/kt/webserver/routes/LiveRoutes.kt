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

import com.google.adk.kt.agents.LiveRunConfig
import com.google.adk.kt.agents.RunConfig
import com.google.adk.kt.annotations.ExperimentalLiveApi
import com.google.adk.kt.types.AvatarConfig
import com.google.adk.kt.types.Modality
import com.google.adk.kt.types.ProactivityConfig
import com.google.adk.kt.types.SessionResumptionConfig
import io.ktor.http.Parameters

/**
 * The prebuilt avatar every VIDEO session gets, because the Live API rejects video output without
 * an avatar.
 */
private const val DEFAULT_AVATAR_NAME = "Kai"

/** The modalities a live agent may answer in, matched by their exact enum names. */
private val LIVE_MODALITIES = listOf(Modality.TEXT, Modality.AUDIO, Modality.VIDEO)

/** The ids a live run is for and the run configuration its query parameters imply. */
internal data class LiveRunParams(
  val appName: String,
  val userId: String,
  val sessionId: String,
  val runConfig: RunConfig,
)

internal sealed interface LiveRunParamsResult {
  data class Success(val params: LiveRunParams) : LiveRunParamsResult

  /** [reason] is sent to the client as the close reason, so it must not echo any input. */
  data class Invalid(val reason: String) : LiveRunParamsResult
}

// Query parameter names, as `/run_live` clients send them.
private const val APP_NAME_PARAM = "app_name"
private const val USER_ID_PARAM = "user_id"
private const val SESSION_ID_PARAM = "session_id"
private const val MODALITIES_PARAM = "modalities"
private const val PROACTIVE_AUDIO_PARAM = "proactive_audio"
private const val ENABLE_AFFECTIVE_DIALOG_PARAM = "enable_affective_dialog"
private const val ENABLE_SESSION_RESUMPTION_PARAM = "enable_session_resumption"
private const val SAVE_LIVE_BLOB_PARAM = "save_live_blob"
private const val EXPLICIT_VAD_SIGNAL_PARAM = "explicit_vad_signal"

/** The live run-config flags `/run_live` accepts, by query parameter name. */
private val BOOLEAN_PARAMS =
  listOf(
    PROACTIVE_AUDIO_PARAM,
    ENABLE_AFFECTIVE_DIALOG_PARAM,
    ENABLE_SESSION_RESUMPTION_PARAM,
    SAVE_LIVE_BLOB_PARAM,
    EXPLICIT_VAD_SIGNAL_PARAM,
  )

/** The last value of [name], "" for a key given without a value, or null when absent. */
private fun Parameters.lastValue(name: String): String? =
  getAll(name)?.let { it.lastOrNull() ?: "" }

/**
 * Parses a live handshake's query parameters into the run they ask for; each flag falls back to the
 * [LiveRunConfig] default when unset.
 */
internal fun parseLiveRunParams(parameters: Parameters): LiveRunParamsResult {
  // An empty `app_name` counts as missing.
  val appName =
    parameters.lastValue(APP_NAME_PARAM).orEmpty().ifEmpty {
      return LiveRunParamsResult.Invalid("Missing $APP_NAME_PARAM query parameter")
    }
  val userId =
    parameters.lastValue(USER_ID_PARAM)
      ?: return LiveRunParamsResult.Invalid("Missing $USER_ID_PARAM query parameter")
  val sessionId =
    parameters.lastValue(SESSION_ID_PARAM)
      ?: return LiveRunParamsResult.Invalid("Missing $SESSION_ID_PARAM query parameter")

  val requested = parameters.getAll(MODALITIES_PARAM).orEmpty()
  val modalities = requested.map { name ->
    LIVE_MODALITIES.firstOrNull { it.name == name }
      ?: return LiveRunParamsResult.Invalid("Unsupported modality")
  }
  // A key without a value is no modality either.
  if (requested.isEmpty() && MODALITIES_PARAM in parameters) {
    return LiveRunParamsResult.Invalid("Unsupported modality")
  }

  val flags = BOOLEAN_PARAMS.associateWith { name ->
    parameters.lastValue(name)?.let {
      queryBooleanOrNull(it)
        ?: return LiveRunParamsResult.Invalid("Invalid boolean query parameter $name")
    }
  }

  // The streaming mode stays at its default; only SSE changes how a run behaves.
  val runConfig =
    RunConfig(
      live =
        LiveRunConfig(
          responseModalities = modalities.ifEmpty { listOf(Modality.AUDIO) },
          // A set flag becomes the config object; unset leaves the default.
          proactivity =
            flags.getValue(PROACTIVE_AUDIO_PARAM)?.let { ProactivityConfig(proactiveAudio = it) },
          enableAffectiveDialog = flags.getValue(ENABLE_AFFECTIVE_DIALOG_PARAM),
          // `true` leaves `transparent` for core to add on a Vertex AI resume; `false` pins it off.
          sessionResumption =
            when (flags.getValue(ENABLE_SESSION_RESUMPTION_PARAM)) {
              true -> SessionResumptionConfig()
              false -> SessionResumptionConfig(transparent = false)
              null -> null
            },
          saveLiveBlob = flags.getValue(SAVE_LIVE_BLOB_PARAM) ?: false,
          explicitVadSignal = flags.getValue(EXPLICIT_VAD_SIGNAL_PARAM),
          avatarConfig =
            if (Modality.VIDEO in modalities) AvatarConfig(avatarName = DEFAULT_AVATAR_NAME)
            else null,
        )
    )
  return LiveRunParamsResult.Success(LiveRunParams(appName, userId, sessionId, runConfig))
}
