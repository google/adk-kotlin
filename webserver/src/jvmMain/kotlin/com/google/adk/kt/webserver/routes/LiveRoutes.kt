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
import com.google.adk.kt.types.Modality
import com.google.adk.kt.types.ProactivityConfig
import com.google.adk.kt.types.SessionResumptionConfig
import io.ktor.http.Parameters

/** The modalities a live agent may answer in, spelled as ADK Python's `/run_live` accepts them. */
private val LIVE_MODALITIES = listOf(Modality.TEXT, Modality.AUDIO, Modality.VIDEO)

/** The ids a live run is for and the run configuration its query parameters imply. */
internal data class LiveRunParams(
  val appName: String,
  val userId: String,
  val sessionId: String,
  val runConfig: RunConfig,
)

internal sealed interface LiveParamsResult {
  data class Success(val params: LiveRunParams) : LiveParamsResult

  /** [reason] is sent to the client as the close reason, so it must not echo any input. */
  data class Invalid(val reason: String) : LiveParamsResult
}

/** The live run-config flags ADK Python's `/run_live` accepts, by query parameter name. */
private val BOOLEAN_PARAMS =
  listOf(
    "proactive_audio",
    "enable_affective_dialog",
    "enable_session_resumption",
    "save_live_blob",
    "explicit_vad_signal",
  )

/**
 * Parses a live handshake's query parameters, as ADK Python's `/run_live` does, into the run they
 * ask for; each flag falls back to the [LiveRunConfig] default when unset.
 */
internal fun parseLiveRunParams(parameters: Parameters): LiveParamsResult {
  // As FastAPI reads a query: the last value wins, and a key without a value reads as "".
  fun queryValue(name: String): String? = parameters.getAll(name)?.let { it.lastOrNull() ?: "" }
  // An empty `app_name` counts as missing, as in ADK Python.
  val appName =
    queryValue("app_name")?.ifEmpty { null }
      ?: return LiveParamsResult.Invalid("Missing app_name query parameter")
  val userId =
    queryValue("user_id") ?: return LiveParamsResult.Invalid("Missing user_id query parameter")
  val sessionId =
    queryValue("session_id")
      ?: return LiveParamsResult.Invalid("Missing session_id query parameter")
  val requested = parameters.getAll("modalities").orEmpty()
  val modalities = requested.map { name ->
    LIVE_MODALITIES.firstOrNull { it.name == name }
      ?: return LiveParamsResult.Invalid("Unsupported modality")
  }
  // A key without a value is no modality either.
  if (requested.isEmpty() && "modalities" in parameters) {
    return LiveParamsResult.Invalid("Unsupported modality")
  }
  val flags = BOOLEAN_PARAMS.associateWith { name ->
    when (queryValue(name)?.lowercase()) {
      null -> null
      in TRUE_SPELLINGS -> true
      in FALSE_SPELLINGS -> false
      else -> return LiveParamsResult.Invalid("Invalid boolean query parameter $name")
    }
  }
  fun flag(name: String): Boolean? = flags.getValue(name)
  // The streaming mode stays at its default, as in ADK Python's `/run_live`.
  val runConfig =
    RunConfig(
      live =
        LiveRunConfig(
          responseModalities = modalities.ifEmpty { listOf(Modality.AUDIO) },
          // A set flag becomes the config object; unset leaves the default.
          proactivity = flag("proactive_audio")?.let { ProactivityConfig(proactiveAudio = it) },
          enableAffectiveDialog = flag("enable_affective_dialog"),
          // Not checked here: a backend without transparent resumption rejects the connection.
          sessionResumption =
            flag("enable_session_resumption")?.let { SessionResumptionConfig(transparent = it) },
          saveLiveBlob = flag("save_live_blob") ?: false,
          explicitVadSignal = flag("explicit_vad_signal"),
        )
    )
  return LiveParamsResult.Success(LiveRunParams(appName, userId, sessionId, runConfig))
}

/** The spellings pydantic accepts for a bool, as ADK Python's `/run_live` parses them. */
private val TRUE_SPELLINGS = setOf("true", "1", "yes", "on", "t", "y")

private val FALSE_SPELLINGS = setOf("false", "0", "no", "off", "f", "n")
