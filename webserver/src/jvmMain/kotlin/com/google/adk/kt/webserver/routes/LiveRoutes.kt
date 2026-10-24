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

import com.google.adk.kt.agents.LiveRequest
import com.google.adk.kt.agents.LiveRunConfig
import com.google.adk.kt.agents.RunConfig
import com.google.adk.kt.annotations.ExperimentalLiveApi
import com.google.adk.kt.types.Modality
import com.google.adk.kt.types.ProactivityConfig
import com.google.adk.kt.types.SessionResumptionConfig
import com.google.adk.kt.webserver.models.decodeLiveRequests
import io.ktor.http.Parameters
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.FrameTooBigException
import io.ktor.websocket.ProtocolViolationException
import io.ktor.websocket.close
import java.io.IOException
import java.lang.invoke.MethodHandles
import java.nio.charset.CharacterCodingException
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass())

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

/** The largest message a live client may send, uvicorn's default `ws_max_size` under ADK Python. */
internal const val MAX_LIVE_FRAME_BYTES = 16L * 1024 * 1024

/** Installs `WebSockets` with the limits `/run_live` relies on. */
internal fun Application.installLiveWebSockets() {
  install(WebSockets) {
    // uvicorn's 20 s ping and timeout, which ADK Python runs with, so a dead socket closes.
    pingPeriodMillis = 20_000
    timeoutMillis = 20_000
    maxFrameSize = MAX_LIVE_FRAME_BYTES
  }
}

/**
 * Decodes each text frame from the client and passes its requests to [onRequest] in order, up to
 * and including a close request. Later text frames are ignored until the client disconnects, so the
 * agent run can finish sending its events. A binary frame at any point, or a malformed text frame
 * before the close, closes the websocket.
 */
internal suspend fun DefaultWebSocketServerSession.pumpLiveRequests(
  onRequest: (LiveRequest) -> Unit
) {
  var closeRequested = false
  try {
    for (frame in incoming) {
      if (frame is Frame.Binary) {
        // As ADK Java does: a client streaming raw audio would otherwise never learn why.
        return close(
          CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Binary frames are not supported")
        )
      }
      if (closeRequested || frame !is Frame.Text) continue
      val requests =
        try {
          decodeLiveRequests(frame.data.decodeToString(throwOnInvalidSequence = true))
        } catch (e: Exception) {
          // Bytes that are not UTF-8, or a frame that does not decode, are the client's mistake.
          if (e !is IllegalArgumentException && e !is CharacterCodingException) throw e
          // The frame is the user's content, so only its shape is logged.
          logger.warn(
            "Rejecting a live request of {} bytes: {}",
            frame.data.size,
            e::class.simpleName,
          )
          return close(CloseReason(CloseReason.Codes.NOT_CONSISTENT, "Malformed live request"))
        }
      for (request in requests) {
        onRequest(request)
        if (request.close) closeRequested = true
      }
    }
  } catch (e: FrameTooBigException) {
    // Ktor has already closed the socket with 1009, so this is the client's end, not a run failure.
    logger.warn("A live client sent a message over {} bytes", maxFrameSize)
  } catch (e: ProtocolViolationException) {
    // Ktor has already closed the socket with 1002.
    logger.warn("A live client broke the websocket protocol")
  } catch (e: IOException) {
    // Ktor's ping timeout among them: the client is gone, as when it disconnects.
    logger.info("A live client's connection failed: {}", e::class.simpleName)
  }
}
