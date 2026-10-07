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
@file:OptIn(ExperimentalLiveApi::class)

package com.google.adk.kt.testing.live

import com.google.adk.kt.annotations.ExperimentalLiveApi
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.RealtimeInput
import com.google.adk.kt.testing.userFunctionResponse
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.common.truth.Truth.assertThat
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** Tests for [FakeLiveConnection]. */
class FakeLiveConnectionTest {

  private fun toolResponse(name: String = "GetWeather"): Content =
    userFunctionResponse(name = name, id = null)

  /** A fully-formed answer: named, correlated to a call, and carrying a result. */
  private fun answer(
    name: String = "GetWeather",
    callId: String = "call-1",
    result: Map<String, Any?> = mapOf("tempF" to 72),
  ): SentLiveMessage.ClientContent =
    SentLiveMessage.ClientContent(userFunctionResponse(name = name, id = callId, response = result))

  private fun textOf(response: LlmResponse): String? = response.content?.parts?.firstOrNull()?.text

  private fun micAudio(): RealtimeInput.Audio =
    RealtimeInput.Audio(Blob(mimeType = "audio/pcm;rate=16000", data = ByteArray(640)))

  @Test
  fun receive_scriptOfResponses_emitsThemInOrder(): Unit = runBlocking {
    val connection =
      FakeLiveConnection(
        LiveScript.builder().text("one").text("two").audio(frames = 2).endStream().build()
      )

    val responses = connection.receive().toList()

    assertThat(responses).hasSize(4)
    assertThat(responses.take(2).map(::textOf)).containsExactly("one", "two").inOrder()
    assertThat(responses[2].content?.parts?.first()?.inlineData?.data).hasLength(960)
  }

  @Test
  fun sendContent_partialContent_recordsThePartialFlag(): Unit = runBlocking {
    val connection = FakeLiveConnection(LiveScript.builder().endStream().build())

    connection.sendContent(userMessage("half"), partial = true)

    assertThat(connection.sent)
      .containsExactly(SentLiveMessage.ClientContent(userMessage("half"), partial = true))
  }

  @Test
  fun receive_concurrentCollection_throwsIllegalStateException(): Unit = runBlocking {
    val connection =
      FakeLiveConnection(LiveScript.builder().text("one").awaitToolResponse().build())
    val firstCollectorRunning = CompletableDeferred<Unit>()
    val collector = launch { connection.receive().collect { firstCollectorRunning.complete(Unit) } }
    firstCollectorRunning.await()

    val error = assertFailsWith<IllegalStateException> { connection.receive().collect {} }

    assertThat(error).hasMessageThat().contains("one collector at a time")
    collector.cancel()
  }

  @Test
  fun receive_afterPreviousCollectionFinished_resumesAtNextStep(): Unit = runBlocking {
    val connection =
      FakeLiveConnection(
        LiveScript.builder().text("first turn").endTurn().text("second").endStream().build()
      )

    val firstTurn = connection.receive().toList()
    val secondTurn = connection.receive().toList()

    assertThat(firstTurn.mapNotNull(::textOf)).containsExactly("first turn")
    assertThat(secondTurn.map(::textOf)).containsExactly("second")
  }

  @Test
  fun receive_afterTurnEnds_reCollectionDeliversTrailingFrames(): Unit = runBlocking {
    // The service sends the resumption handle after the turn boundary; a new collection reaches it.
    val trailing = LlmResponse(modelVersion = "trailing-after-turn")
    val connection =
      FakeLiveConnection(
        LiveScript.builder().text("spoken").endTurn().respond(trailing).endStream().build()
      )

    val turn = connection.receive().toList()
    val afterTurn = connection.receive().toList()

    assertThat(turn.mapNotNull(::textOf)).containsExactly("spoken")
    assertThat(afterTurn.map { it.modelVersion }).containsExactly("trailing-after-turn")
    assertThat(connection.isClosed).isFalse()
  }

  @Test
  fun receive_turnCompleteResponse_endsTheCollectionBeforeTheNextResponse(): Unit = runBlocking {
    // Turn complete ends the collection, as it does on the real connection.
    val connection =
      FakeLiveConnection(
        LiveScript.builder().text("a").turnComplete(null).text("b").endStream().build()
      )

    val firstTurn = connection.receive().toList()
    val secondTurn = connection.receive().toList()

    assertThat(firstTurn.mapNotNull(::textOf)).containsExactly("a")
    assertThat(secondTurn.mapNotNull(::textOf)).containsExactly("b")
  }

  @Test
  fun receive_afterAGateTimedOut_reCollectionStillWaitsOnTheGate(): Unit = runBlocking {
    // The timed-out gate was never satisfied, so collecting again must wait on it, not skip it.
    val connection =
      FakeLiveConnection(
        LiveScript.builder().awaitToolResponse().text("after").endStream().build(),
        gateTimeout = 50.milliseconds,
      )
    assertFailsWith<AssertionError> { connection.receive().collect {} }

    val error = assertFailsWith<AssertionError> { connection.receive().collect {} }

    assertThat(error).hasMessageThat().contains("a tool response")
  }

  @Test
  fun receive_scriptFails_propagatesTheCause(): Unit = runBlocking {
    val connection =
      FakeLiveConnection(
        LiveScript.builder().failWith(IllegalStateException("transport lost")).endStream().build()
      )

    val error = assertFailsWith<IllegalStateException> { connection.receive().collect {} }

    assertThat(error).hasMessageThat().isEqualTo("transport lost")
  }

  @Test
  fun receive_scriptFailed_rethrowsTheSameCauseAndReleasesTheGuard(): Unit = runBlocking {
    val connection =
      FakeLiveConnection(
        LiveScript.builder().failWith(IllegalStateException("transport lost")).endStream().build()
      )
    assertFailsWith<IllegalStateException> { connection.receive().collect {} }

    // The same cause, not a concurrent-collection error, also shows the guard was released.
    val afterFailure = assertFailsWith<IllegalStateException> { connection.receive().collect {} }

    assertThat(afterFailure).hasMessageThat().isEqualTo("transport lost")
  }

  @Test
  fun awaitClientMessage_messageSentBeforeGateReached_isSatisfied(): Unit = runBlocking {
    val connection =
      FakeLiveConnection(
        LiveScript.builder().awaitToolResponse().text("after tool").endStream().build()
      )
    connection.sendContent(toolResponse())

    val responses = connection.receive().toList()

    assertThat(responses.map(::textOf)).containsExactly("after tool")
  }

  @Test
  fun awaitClientMessage_messageSentAfterGateReached_isSatisfied(): Unit = runBlocking {
    val connection =
      FakeLiveConnection(
        LiveScript.builder()
          .text("asking")
          .awaitToolResponse()
          .text("after tool")
          .endStream()
          .build()
      )
    val gateReached = CompletableDeferred<Unit>()
    val received = mutableListOf<LlmResponse>()
    val collector = launch {
      connection.receive().collect {
        received.add(it)
        gateReached.complete(Unit)
      }
    }
    gateReached.await()

    connection.sendContent(toolResponse())
    collector.join()

    assertThat(received.map(::textOf)).containsExactly("asking", "after tool").inOrder()
  }

  @Test
  fun awaitClientMessage_neverSatisfied_failsNamingTheGateAndWhatWasSent(): Unit = runBlocking {
    val connection =
      FakeLiveConnection(
        LiveScript.builder().awaitToolResponse().build(),
        gateTimeout = 50.milliseconds,
      )
    connection.sendRealtime(micAudio())

    val error = assertFailsWith<AssertionError> { connection.receive().collect {} }

    assertThat(error).hasMessageThat().contains("a tool response")
    assertThat(error)
      .hasMessageThat()
      .contains("Realtime(Audio, mimeType=audio/pcm;rate=16000, bytes=640)")
  }

  @Test
  fun closeSession_whileGateWaiting_completesTheCollection(): Unit = runBlocking {
    val connection =
      FakeLiveConnection(
        LiveScript.builder().text("asking").awaitToolResponse().text("never").build()
      )
    val gateReached = CompletableDeferred<Unit>()
    val received = mutableListOf<LlmResponse>()
    val collector = launch {
      connection.receive().collect {
        received.add(it)
        gateReached.complete(Unit)
      }
    }
    gateReached.await()

    connection.closeSession()
    collector.join()

    assertThat(received.map(::textOf)).containsExactly("asking")
    assertThat(connection.isClosed).isTrue()
  }

  @Test
  fun sent_severalKindsOfSend_recordsThemInOneOrderedList(): Unit = runBlocking {
    val connection = FakeLiveConnection(LiveScript.builder().build())

    connection.sendHistory(listOf(userMessage("hello")))
    connection.sendRealtime(micAudio())
    connection.sendContent(toolResponse())
    connection.closeSession()

    assertThat(connection.sent.map { it::class.simpleName })
      .containsExactly("History", "Realtime", "ClientContent", "Closed")
      .inOrder()
  }

  @Test
  fun closeSession_calledTwice_recordsOneClose(): Unit = runBlocking {
    val connection = FakeLiveConnection(LiveScript.builder().build())

    connection.closeSession()
    connection.closeSession()

    assertThat(connection.sent.count { it == SentLiveMessage.Closed }).isEqualTo(1)
  }

  @Test
  fun receive_afterTheStreamEnded_completesAtOnce(): Unit = runBlocking {
    val connection =
      FakeLiveConnection(
        LiveScript.builder().text("last").endStream().build(),
        gateTimeout = 100.milliseconds,
      )
    connection.receive().toList()

    // An ended stream stays ended; parking here would read as an open socket with nothing to say.
    assertThat(connection.receive().toList()).isEmpty()
  }

  @Test
  fun sendContent_functionCallPart_throwsAndRecordsNothing(): Unit = runBlocking {
    val connection = FakeLiveConnection(LiveScript.builder().build())

    assertFailsWith<IllegalArgumentException> {
      connection.sendContent(userMessage(Part(functionCall = FunctionCall(name = "GetWeather"))))
    }

    assertThat(connection.sent).isEmpty()
  }

  @Test
  fun isToolResponse_contentOfOnlyFunctionResponses_isTrue() {
    assertThat(SentLiveMessage.ClientContent(toolResponse()).isToolResponse).isTrue()
  }

  @Test
  fun isToolResponse_contentOfText_isFalse() {
    val text = SentLiveMessage.ClientContent(userMessage("hello"))

    assertThat(text.isToolResponse).isFalse()
  }

  // Each false case below breaks one property, so a broken check fails only its own test.

  @Test
  fun isToolResponseFor_namedCorrelatedAndAnswered_isTrue() {
    assertThat(answer().isToolResponseFor("GetWeather", "call-1")).isTrue()
  }

  @Test
  fun isToolResponseFor_answeringADifferentCall_isFalse() {
    // The model matches an answer to its call by id, so a wrong id fails silently.
    assertThat(answer(callId = "call-2").isToolResponseFor("GetWeather", "call-1")).isFalse()
  }

  @Test
  fun isToolResponseFor_answeringAsADifferentTool_isFalse() {
    assertThat(answer(name = "GetTime").isToolResponseFor("GetWeather", "call-1")).isFalse()
  }

  @Test
  fun isToolResponseFor_carryingNoResult_isFalse() {
    // Shape-wise a perfectly good tool response; it just does not answer anything.
    assertThat(answer(result = emptyMap()).isToolResponseFor("GetWeather", "call-1")).isFalse()
  }

  @Test
  fun isToolResponseFor_oneOfSeveralParallelAnswers_isTrue() {
    // Two answers in one content; only one answers call-1, and a single match must still count.
    val parallel =
      Content(
        role = Role.USER,
        parts =
          answer(name = "GetWeather", callId = "call-1").content.parts +
            answer(name = "GetTime", callId = "call-2").content.parts,
      )

    assertThat(SentLiveMessage.ClientContent(parallel).isToolResponseFor("GetWeather", "call-1"))
      .isTrue()
  }

  @Test
  fun describeShape_closed_namesOnlyItsShape() {
    assertThat(describeShape(SentLiveMessage.Closed)).isEqualTo("Closed")
  }

  @Test
  fun describeShape_eachRealtimeKind_namesOnlyItsShape() {
    assertThat(describeShape(SentLiveMessage.Realtime(micAudio())))
      .isEqualTo("Realtime(Audio, mimeType=audio/pcm;rate=16000, bytes=640)")
    assertThat(
        describeShape(
          SentLiveMessage.Realtime(
            RealtimeInput.Video(Blob(mimeType = "video/mp4", data = ByteArray(10)))
          )
        )
      )
      .isEqualTo("Realtime(Video, mimeType=video/mp4, bytes=10)")
    assertThat(describeShape(SentLiveMessage.Realtime(RealtimeInput.ActivityStart)))
      .isEqualTo("Realtime(ActivityStart)")
    assertThat(describeShape(SentLiveMessage.Realtime(RealtimeInput.ActivityEnd)))
      .isEqualTo("Realtime(ActivityEnd)")
    assertThat(describeShape(SentLiveMessage.Realtime(RealtimeInput.AudioStreamEnd)))
      .isEqualTo("Realtime(AudioStreamEnd)")
  }

  @Test
  fun receive_scriptExhaustedWhileOpen_parksInsteadOfCompleting(): Unit = runBlocking {
    val connection =
      FakeLiveConnection(LiveScript.builder().text("only").build(), gateTimeout = 50.milliseconds)

    val error = assertFailsWith<AssertionError> { connection.receive().toList() }

    assertThat(error).hasMessageThat().contains("script is exhausted")
  }

  @Test
  fun awaitClientMessage_oneMessageTwoGates_satisfiesOnlyTheFirst(): Unit = runBlocking {
    val connection =
      FakeLiveConnection(
        LiveScript.builder()
          .awaitToolResponse()
          .text("a")
          .awaitToolResponse()
          .text("b")
          .endStream()
          .build(),
        gateTimeout = 50.milliseconds,
      )
    connection.sendContent(toolResponse())
    val received = mutableListOf<LlmResponse>()

    assertFailsWith<AssertionError> { connection.receive().collect { received.add(it) } }

    assertThat(received.map(::textOf)).containsExactly("a")
  }

  @Test
  fun awaitClientMessage_messagePassedOverByAnEarlierGate_cannotSatisfyALaterGate(): Unit =
    runBlocking {
      val connection =
        FakeLiveConnection(
          LiveScript.builder()
            .awaitClientMessage("realtime input") { it is SentLiveMessage.Realtime }
            .text("a")
            .awaitToolResponse()
            .text("b")
            .endStream()
            .build(),
          gateTimeout = 50.milliseconds,
        )
      connection.sendContent(toolResponse())
      connection.sendRealtime(micAudio())
      val received = mutableListOf<LlmResponse>()

      val error =
        assertFailsWith<AssertionError> { connection.receive().collect { received.add(it) } }

      // The first gate passed over the tool response, so the later gate cannot see it.
      assertThat(received.map(::textOf)).containsExactly("a")
      assertThat(error).hasMessageThat().contains("a tool response")
    }

  @Test
  fun isToolResponse_contentWithNoParts_isFalse() {
    assertThat(
        SentLiveMessage.ClientContent(Content(role = Role.USER, parts = emptyList())).isToolResponse
      )
      .isFalse()
  }
}
