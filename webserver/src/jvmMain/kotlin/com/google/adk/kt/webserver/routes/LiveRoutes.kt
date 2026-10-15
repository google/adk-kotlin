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
import com.google.adk.kt.agents.LiveRequestQueue
import com.google.adk.kt.agents.LiveRunConfig
import com.google.adk.kt.agents.RunConfig
import com.google.adk.kt.annotations.ExperimentalLiveApi
import com.google.adk.kt.artifacts.ArtifactService
import com.google.adk.kt.plugins.Plugin
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.sessions.SessionService
import com.google.adk.kt.types.Modality
import com.google.adk.kt.types.ProactivityConfig
import com.google.adk.kt.types.SessionResumptionConfig
import com.google.adk.kt.webserver.loaders.ServedApps
import com.google.adk.kt.webserver.models.decodeLiveRequests
import com.google.adk.kt.webserver.models.encodeLiveEvent
import io.ktor.http.Parameters
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.FrameTooBigException
import io.ktor.websocket.ProtocolViolationException
import io.ktor.websocket.close
import java.io.IOException
import java.lang.invoke.MethodHandles
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass())

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
        )
    )
  return LiveRunParamsResult.Success(LiveRunParams(appName, userId, sessionId, runConfig))
}

/**
 * The largest message, in bytes, a live client may send. 16 MiB fits the audio chunks and camera
 * images clients stream while bounding the memory one message takes; Ktor closes the socket with
 * 1009 for anything bigger.
 */
internal const val MAX_LIVE_FRAME_BYTES = 16L * 1024 * 1024

/**
 * Installs `WebSockets` with the limits `/run_live` relies on. On a stalled client, the engine's
 * write timeout (Netty's default is 10 s) then closes the socket.
 */
internal fun Application.installLiveWebSockets() {
  install(WebSockets) {
    // A 20 s ping and timeout, so the socket of a client that stopped answering closes.
    pingPeriod = 20.seconds
    timeout = 20.seconds
    maxFrameSize = MAX_LIVE_FRAME_BYTES
    // A few frames of slack: send waits once 16 are queued, so a slow client can't grow memory.
    channels { outgoing = bounded(16) }
  }
}

/** Registers the bidirectional streaming endpoint the live clients connect to. */
internal fun Route.liveRoutes(
  servedApps: ServedApps,
  sessionService: SessionService,
  artifactService: ArtifactService,
  plugins: List<Plugin> = emptyList(),
) {
  route("/run_live") {
    webSocket {
      val params =
        when (val result = parseLiveRunParams(call.request.queryParameters)) {
          is LiveRunParamsResult.Success -> result.params
          is LiveRunParamsResult.Invalid ->
            return@webSocket close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, result.reason))
        }

      val runner =
        try {
          servedApps.runnerOrNull(params.appName, sessionService, artifactService, plugins)
        } catch (e: Exception) {
          return@webSocket closeUnloadedAgent(e)
        } catch (e: LinkageError) {
          // A throwing initializer behind the loader fails class initialization, which is an Error.
          return@webSocket closeUnloadedAgent(e)
        }
          ?: return@webSocket close(
            CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Agent not found")
          )

      val sessionKey = SessionKey(params.appName, params.userId, params.sessionId)
      val session =
        try {
          sessionService.getSession(sessionKey)
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          // A fixed reason: the store's message can carry connection details.
          logger.warn("Loading the session for a live run failed: {}", e.typeName)
          return@webSocket close(
            CloseReason(CloseReason.Codes.INTERNAL_ERROR, "Session could not be loaded")
          )
        }
      if (session == null) {
        // The runner would create a missing session, but a live client must name one that exists.
        return@webSocket close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "Session not found"))
      }

      val requests = LiveRequestQueue()
      try {
        coroutineScope {
          // Safe: only this job runs the agent and sends events, and `requests` is thread-safe.
          @Suppress("UnsafeCoroutineCrossing")
          val events = launch {
            runner
              .runLive(params.userId, params.sessionId, requests, params.runConfig)
              .map(::encodeLiveEvent)
              // Upstream only, so a client that went away is not reported as a failed run.
              .catch { failure -> closeFailedRun(failure) }
              .collect { text -> send(Frame.Text(text)) }
          }
          // Safe: only this job reads incoming frames, and `requests` is thread-safe.
          @Suppress("UnsafeCoroutineCrossing")
          val input = launch { pumpLiveRequests(requests::send) }
          // Stop the other job when the run ends or a disconnect or rejected frame ends the pump.
          events.invokeOnCompletion { input.cancel() }
          input.invokeOnCompletion { events.cancel() }
        }
      } catch (e: IOException) {
        // The client went away, e.g. stopped answering pings: not a failure, so only at info.
        logger.info("A live client disconnected: {}", e.typeName)
      } finally {
        requests.close()
      }
    }
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
        // Closing tells a client streaming raw audio why nothing it sends is used.
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
          return close(CloseReason(CloseReason.Codes.NOT_CONSISTENT, "Invalid live request"))
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

/** [message] cut to the 123 UTF-8 bytes a close reason may carry, never splitting a character. */
internal fun closeReasonFor(message: String): String {
  val reason = ByteBuffer.allocate(MAX_CLOSE_REASON_BYTES)
  // Stops before the first character that does not fit whole; a lone surrogate becomes `?`.
  Charsets.UTF_8.newEncoder()
    .onMalformedInput(CodingErrorAction.REPLACE)
    .encode(CharBuffer.wrap(message), reason, true)
  return String(reason.array(), 0, reason.position(), Charsets.UTF_8)
}

/** RFC 6455 leaves 123 bytes for the reason in a 125-byte close frame. */
private const val MAX_CLOSE_REASON_BYTES = 123

/**
 * Closes a live run whose agent failed to load, with a fixed reason: the loader's message can carry
 * configuration the peer should not see.
 */
private suspend fun DefaultWebSocketServerSession.closeUnloadedAgent(cause: Throwable) {
  logger.warn("Loading the agent for a live run failed: {}", cause.typeName)
  close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, "Agent could not be loaded"))
}

/**
 * Closes a failed live run with the failure's message, so the client learns why, e.g. that the
 * agent cannot run live.
 */
private suspend fun DefaultWebSocketServerSession.closeFailedRun(failure: Throwable) {
  logger.warn("Live run failed: {}", failure.typeName)
  close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, closeReasonFor(failure.message.orEmpty())))
}

/** The failure's type, for a log line: the failure's message can carry user or model content. */
private val Throwable.typeName: String
  get() = this::class.simpleName ?: this::class.java.name
