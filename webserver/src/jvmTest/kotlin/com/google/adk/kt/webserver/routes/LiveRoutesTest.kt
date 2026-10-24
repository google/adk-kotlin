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
import com.google.adk.kt.agents.RunConfig
import com.google.adk.kt.agents.StreamingMode
import com.google.adk.kt.annotations.ExperimentalLiveApi
import com.google.adk.kt.models.RealtimeInput
import com.google.adk.kt.types.Modality
import com.google.adk.kt.types.ProactivityConfig
import com.google.adk.kt.types.SessionResumptionConfig
import com.google.adk.kt.webserver.liveClient
import com.google.common.truth.Truth.assertThat
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.http.parametersOf
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

private const val AUDIO_FRAME = """{"blob":{"mime_type":"audio/pcm","data":"AQID"}}"""

private const val JPEG_FRAME = """{"blob":{"mime_type":"image/jpeg","data":"AQID"}}"""

private val COLLECT_TIMEOUT = 10.seconds

/** How long a pump that should still be running is watched for an early return. */
private val STILL_READING = 500.milliseconds

/**
 * Serves [pumpLiveRequests] at `/pump` and returns a job that completes once the pump returns.
 *
 * The client returns as soon as it has closed, before the server has read what it sent, so a case
 * joins the job before asserting on the sink.
 */
private fun ApplicationTestBuilder.installPumpRoute(onRequest: (LiveRequest) -> Unit): Job {
  val pumped = Job()
  application {
    install(WebSockets)
    routing {
      webSocket("/pump") {
        try {
          pumpLiveRequests(onRequest)
        } finally {
          pumped.complete()
        }
      }
    }
  }
  return pumped
}

/** The close the server sends on [url], bounded because an accepted socket stays open. */
private suspend fun ApplicationTestBuilder.serverCloseOf(
  url: String,
  vararg frames: Frame,
): CloseReason? {
  var reason: CloseReason? = null
  liveClient().use { client ->
    client.webSocket(url) {
      for (frame in frames) send(frame)
      reason = withTimeout(COLLECT_TIMEOUT) { closeReason.await() }
    }
  }
  return reason
}

/** The close [pumpLiveRequests] sends after reading [frames]. */
private suspend fun ApplicationTestBuilder.serverCloseAfter(vararg frames: Frame): CloseReason? =
  serverCloseOf("/pump", *frames)

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

  @Test
  fun pumpLiveRequests_malformedFrame_closesWithNotConsistent() = testApplication {
    installPumpRoute {}

    val code = serverCloseAfter(Frame.Text("not json"))?.code

    assertThat(code).isEqualTo(CloseReason.Codes.NOT_CONSISTENT.code)
  }

  @Test
  fun pumpLiveRequests_invalidUtf8_closesWithNotConsistent() = testApplication {
    installPumpRoute {}
    val bytes = """{"content":{"role":"user","parts":[{"text":"#"}]}}""".encodeToByteArray()
    // 0xFF never occurs in UTF-8; decoded leniently, the frame would pass as valid content.
    bytes[bytes.indexOf('#'.code.toByte())] = 0xFF.toByte()

    val code = serverCloseAfter(Frame.Text(true, bytes))?.code

    assertThat(code).isEqualTo(CloseReason.Codes.NOT_CONSISTENT.code)
  }

  @Test
  fun pumpLiveRequests_contentBreakingContentInputRule_closesWithNotConsistent() = testApplication {
    installPumpRoute {}
    val forgedCall = """{"content":{"role":"user","parts":[{"function_call":{"name":"forged"}}]}}"""

    val code = serverCloseAfter(Frame.Text(forgedCall))?.code

    assertThat(code).isEqualTo(CloseReason.Codes.NOT_CONSISTENT.code)
  }

  @Test
  fun pumpLiveRequests_stateDeltaWithNullValue_isPassedOn() = testApplication {
    val received = mutableListOf<LiveRequest>()
    val pumped = installPumpRoute(received::add)

    liveClient().use { client ->
      client.webSocket("/pump") {
        send(Frame.Text("""{"state_delta":{"k":null}}"""))
        close()
      }
    }
    withTimeout(COLLECT_TIMEOUT) { pumped.join() }

    // The decoder turns the null into a removal; the pump only passes it on.
    assertThat(received.single().stateDelta.keys).containsExactly("k")
  }

  @Test
  fun pumpLiveRequests_numberArrayBlobData_closesWithNotConsistent() = testApplication {
    installPumpRoute {}

    val reason =
      serverCloseAfter(Frame.Text("""{"blob":{"mime_type":"audio/pcm","data":[1,2,3]}}"""))

    assertThat(reason?.code).isEqualTo(CloseReason.Codes.NOT_CONSISTENT.code)
    assertThat(reason?.message).isEqualTo("Malformed live request")
  }

  @Test
  fun pumpLiveRequests_binaryFrame_closesWithCannotAccept() = testApplication {
    installPumpRoute {}

    val reason = serverCloseAfter(Frame.Binary(true, byteArrayOf(1, 2, 3)))

    assertThat(reason?.code).isEqualTo(CloseReason.Codes.CANNOT_ACCEPT.code)
    assertThat(reason?.message).isEqualTo("Binary frames are not supported")
  }

  @Test
  fun pumpLiveRequests_afterClose_keepsReadingUntilTheClientLeaves() = testApplication {
    val closeSeen = CompletableDeferred<Unit>()
    val pumped = installPumpRoute { if (it.close) closeSeen.complete(Unit) }

    liveClient().use { client ->
      client.webSocket("/pump") {
        send(Frame.Text("""{"close":true}"""))
        withTimeout(COLLECT_TIMEOUT) { closeSeen.await() }

        // Returning here would cancel the run, so the pump must still be reading.
        assertThat(withTimeoutOrNull(STILL_READING) { pumped.join() }).isNull()
      }
    }
    withTimeout(COLLECT_TIMEOUT) { pumped.join() }
  }

  @Test
  fun pumpLiveRequests_framesAfterClose_areIgnored() = testApplication {
    val received = mutableListOf<LiveRequest>()
    val pumped = installPumpRoute(received::add)

    liveClient().use { client ->
      client.webSocket("/pump") {
        send(Frame.Text("""{"close":true}"""))
        send(Frame.Text(AUDIO_FRAME))
        close()
      }
    }
    withTimeout(COLLECT_TIMEOUT) { pumped.join() }

    assertThat(received).hasSize(1)
    assertThat(received.single().close).isTrue()
  }

  @Test
  fun pumpLiveRequests_binaryFrameAfterClose_closesWithCannotAccept() = testApplication {
    installPumpRoute {}

    val code =
      serverCloseAfter(Frame.Text("""{"close":true}"""), Frame.Binary(true, byteArrayOf(1)))?.code

    assertThat(code).isEqualTo(CloseReason.Codes.CANNOT_ACCEPT.code)
  }

  @Test
  fun pumpLiveRequests_audioFrame_reachesTheSink() = testApplication {
    val received = mutableListOf<LiveRequest>()
    val pumped = installPumpRoute(received::add)

    liveClient().use { client ->
      client.webSocket("/pump") {
        send(Frame.Text(AUDIO_FRAME))
        close()
      }
    }
    withTimeout(COLLECT_TIMEOUT) { pumped.join() }

    val audio = received.first().input as RealtimeInput.Audio
    assertThat(audio.blob.data).isEqualTo(byteArrayOf(1, 2, 3))
    assertThat(audio.blob.mimeType).isEqualTo("audio/pcm")
  }

  @Test
  fun pumpLiveRequests_blobWithContent_sendsBothRealtimeFirst() = testApplication {
    val received = mutableListOf<LiveRequest>()
    val pumped = installPumpRoute(received::add)
    val blob = """"blob":{"mime_type":"audio/pcm","data":"AQID"}"""
    val content = """"content":{"role":"user","parts":[{"text":"hi"}]}"""

    liveClient().use { client ->
      client.webSocket("/pump") {
        send(Frame.Text("{$blob,$content}"))
        close()
      }
    }
    withTimeout(COLLECT_TIMEOUT) { pumped.join() }

    assertThat(received).hasSize(2)
    assertThat(received[0].input).isInstanceOf(RealtimeInput.Audio::class.java)
    assertThat(received[1].input).isNotNull()
    assertThat(received[1].input).isNotInstanceOf(RealtimeInput::class.java)
  }

  @Test
  fun pumpLiveRequests_clientDisconnects_returnsWithoutSending() = testApplication {
    val received = mutableListOf<LiveRequest>()
    val pumped = installPumpRoute(received::add)

    liveClient().use { client -> client.webSocket("/pump") { close() } }
    withTimeout(COLLECT_TIMEOUT) { pumped.join() }

    // A disconnect passes nothing on, not even a close request.
    assertThat(received).isEmpty()
  }

  @Test
  fun pumpLiveRequests_pingTimeout_returnsAsForADisconnect() = testApplication {
    val outcome = CompletableDeferred<Result<Unit>>()
    application {
      install(WebSockets)
      routing {
        webSocket("/pump") {
          // How Ktor ends `incoming` once the client stops answering pings.
          val timedOut = Channel<Frame>().apply { close(IOException("Ping timeout")) }
          val session =
            object : DefaultWebSocketServerSession by this {
              override val incoming: ReceiveChannel<Frame> = timedOut
            }
          outcome.complete(runCatching { session.pumpLiveRequests {} })
        }
      }
    }

    liveClient().use { client ->
      client.webSocket("/pump") { withTimeout(COLLECT_TIMEOUT) { outcome.await() } }
    }

    assertThat(outcome.await().exceptionOrNull()).isNull()
  }

  @Test
  fun pumpLiveRequests_severalFrames_keepsOrder() = testApplication {
    val received = mutableListOf<LiveRequest>()
    val pumped = installPumpRoute(received::add)

    liveClient().use { client ->
      client.webSocket("/pump") {
        send(Frame.Text(AUDIO_FRAME))
        send(Frame.Text(JPEG_FRAME))
        send(Frame.Text("""{"audio_stream_end":true}"""))
        close()
      }
    }
    withTimeout(COLLECT_TIMEOUT) { pumped.join() }

    assertThat(received).hasSize(3)
    assertThat(received[0].input).isInstanceOf(RealtimeInput.Audio::class.java)
    assertThat(received[1].input).isInstanceOf(RealtimeInput.Video::class.java)
    assertThat(received[2].input).isEqualTo(RealtimeInput.AudioStreamEnd)
  }
}
