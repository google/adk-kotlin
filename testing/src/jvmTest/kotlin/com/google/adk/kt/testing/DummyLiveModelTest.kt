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

package com.google.adk.kt.testing

import com.google.adk.kt.annotations.ExperimentalLiveApi
import com.google.adk.kt.models.ContentInput
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.RealtimeInput
import com.google.adk.kt.types.Blob
import com.google.common.truth.Truth.assertThat
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test

class DummyLiveModelTest {

  @Test
  fun receive_turnCompleteResponse_endsTheReceiveAndTheNextOneContinues(): Unit = runBlocking {
    val connection = DummyLiveConnection(listOf(text("a"), TURN_COMPLETE, text("b"), TURN_COMPLETE))

    assertThat(connection.receive().toList().map { it.firstText() })
      .containsExactly("a", null)
      .inOrder()
    assertThat(connection.receive().toList().map { it.firstText() })
      .containsExactly("b", null)
      .inOrder()
  }

  @Test
  fun receive_whileAnotherReceiveIsActive_isRejected(): Unit = runBlocking {
    val connection = DummyLiveConnection(listOf(text("a")))
    val started = CompletableDeferred<Unit>()
    val first = async { connection.receive().collect { started.complete(Unit) } }
    started.await()

    assertFailsWith<IllegalStateException> { connection.receive().toList() }
    connection.closeSession()
    first.await()
  }

  @Test
  fun awaitSent_matchingSend_letsTheStepsContinue(): Unit = runBlocking {
    val connection =
      DummyLiveConnection(
        listOf(DummyLiveStep.AwaitSent("a tool response") { it is ContentInput }, TURN_COMPLETE)
      )
    // Start `receive()` immediately so it is already waiting before `sendContent` runs.
    val responses = async(start = CoroutineStart.UNDISPATCHED) { connection.receive().toList() }

    connection.sendContent(userFunctionResponse(name = "GetWeather", id = "call-1"))

    assertThat(responses.await().single().turnComplete).isTrue()
  }

  @Test
  fun awaitSent_connectionClosedWhileWaiting_receiveFinishesEmpty(): Unit = runBlocking {
    val connection =
      DummyLiveConnection(
        listOf(DummyLiveStep.AwaitSent("a tool response") { true }, TURN_COMPLETE)
      )
    val responses = async(start = CoroutineStart.UNDISPATCHED) { connection.receive().toList() }

    connection.closeSession()

    assertThat(responses.await()).isEmpty()
  }

  @Test
  fun awaitSent_nothingSent_failsTheTestNamingWhatItWaitedFor(): Unit = runBlocking {
    val connection =
      DummyLiveConnection(
        listOf(DummyLiveStep.AwaitSent("a tool response") { true }),
        50.milliseconds,
      )

    val error = assertFailsWith<AssertionError> { connection.receive().toList() }

    assertThat(error).hasMessageThat().contains("a tool response")
  }

  @Test
  fun awaitSent_messageAnEarlierWaitSkipped_doesNotSatisfyALaterOne(): Unit = runBlocking {
    val connection =
      DummyLiveConnection(
        listOf(
          DummyLiveStep.AwaitSent("audio") { it is RealtimeInput },
          DummyLiveStep.AwaitSent("content") { it is ContentInput },
        ),
        50.milliseconds,
      )
    connection.sendContent(userMessage("hi"))
    connection.sendRealtime(RealtimeInput.Audio(Blob(mimeType = "audio/pcm", data = ByteArray(2))))

    val error = assertFailsWith<AssertionError> { connection.receive().toList() }

    assertThat(error).hasMessageThat().contains("content")
  }

  @Test
  fun awaitSent_receiveCanceledWhileWaiting_nextReceiveWaitsAgain(): Unit = runBlocking {
    val connection =
      DummyLiveConnection(listOf(DummyLiveStep.AwaitSent("anything") { true }, TURN_COMPLETE))
    launch(start = CoroutineStart.UNDISPATCHED) { connection.receive().toList() }.cancelAndJoin()
    val responses = async(start = CoroutineStart.UNDISPATCHED) { connection.receive().toList() }

    // The second receive() must still be waiting on the same step, not past it.
    assertThat(responses.isActive).isTrue()
    connection.sendRealtime(RealtimeInput.ActivityStart)

    assertThat(responses.await().single().turnComplete).isTrue()
  }

  @Test
  fun assertAllWaitsMet_waitStillPendingAtClose_failsNamingIt(): Unit = runBlocking {
    val connection =
      DummyLiveConnection(
        listOf(DummyLiveStep.AwaitSent("a tool response") { true }, TURN_COMPLETE)
      )
    val responses = async(start = CoroutineStart.UNDISPATCHED) { connection.receive().toList() }
    connection.closeSession()
    responses.await()

    val error = assertFailsWith<AssertionError> { connection.assertAllWaitsMet() }

    assertThat(error).hasMessageThat().contains("a tool response")
  }

  @Test
  fun assertAllWaitsMet_everyWaitSatisfied_passes(): Unit = runBlocking {
    val connection =
      DummyLiveConnection(listOf(DummyLiveStep.AwaitSent("anything") { true }, TURN_COMPLETE))
    val responses = async(start = CoroutineStart.UNDISPATCHED) { connection.receive().toList() }
    connection.sendRealtime(RealtimeInput.Audio(Blob(mimeType = "audio/pcm", data = ByteArray(2))))
    responses.await()

    connection.assertAllWaitsMet()
  }

  @Test
  fun fail_laterReceives_rethrowTheCause(): Unit = runBlocking {
    val cause = IllegalStateException("socket closed")
    val connection = DummyLiveConnection(listOf(DummyLiveStep.Fail(cause), text("never")))

    assertFailsWith<IllegalStateException> { connection.receive().toList() }
    assertFailsWith<IllegalStateException> { connection.receive().toList() }
  }

  @Test
  fun fail_thenClosed_laterReceivesStillThrow(): Unit = runBlocking {
    val connection =
      DummyLiveConnection(listOf(DummyLiveStep.Fail(IllegalStateException("socket closed"))))
    assertFailsWith<IllegalStateException> { connection.receive().toList() }
    connection.closeSession()

    assertFailsWith<IllegalStateException> { connection.receive().toList() }
  }

  @Test
  fun endStream_laterReceives_finishEmpty(): Unit = runBlocking {
    val connection = DummyLiveConnection(listOf(DummyLiveStep.EndStream, text("never")))

    assertThat(connection.receive().toList()).isEmpty()
    assertThat(connection.receive().toList()).isEmpty()
  }

  @Test
  fun receive_afterClose_finishesEmptyWithStepsLeft(): Unit = runBlocking {
    val connection = DummyLiveConnection(listOf(text("a"), TURN_COMPLETE))
    connection.closeSession()

    assertThat(connection.receive().toList()).isEmpty()
  }

  @Test
  fun receive_stepsRunOut_waitsUntilClosed(): Unit = runBlocking {
    val connection = DummyLiveConnection(listOf(text("a")))
    val received = CompletableDeferred<String?>()
    val done = async { connection.receive().collect { received.complete(it.firstText()) } }

    assertThat(received.await()).isEqualTo("a")
    assertThat(done.isActive).isTrue()
    connection.closeSession()
    done.await()
  }

  @Test
  fun receive_stepsRunOutAndNeverClosed_failsTheTest(): Unit = runBlocking {
    val connection = DummyLiveConnection(listOf(text("a")), 50.milliseconds)

    val error = assertFailsWith<AssertionError> { connection.receive().toList() }

    assertThat(error).hasMessageThat().contains("EndStream")
  }

  @Test
  fun send_allThreeKinds_keepsEachInOrder(): Unit = runBlocking {
    val connection = DummyLiveConnection(emptyList())
    val history = listOf(userMessage("earlier"))
    val audio = RealtimeInput.Audio(Blob(mimeType = "audio/pcm", data = ByteArray(2)))

    connection.sendHistory(history)
    connection.sendContent(userMessage("hi"), partial = true)
    connection.sendRealtime(audio)

    assertThat(connection.sent)
      .containsExactly(history, ContentInput(userMessage("hi"), partial = true), audio)
      .inOrder()
  }

  @Test
  fun sendContent_functionResponseWithoutId_throwsAndKeepsNothing(): Unit = runBlocking {
    val connection = DummyLiveConnection(emptyList())

    assertFailsWith<IllegalArgumentException> {
      connection.sendContent(userFunctionResponse(name = "GetWeather", id = null))
    }

    assertThat(connection.sent).isEmpty()
  }

  @Test
  fun connect_calledTwice_keepsEachRequestAndConnectionInOrder(): Unit = runBlocking {
    val model = DummyLiveModel(listOf(text("a")))
    val requests = listOf(LlmRequest(), LlmRequest())

    val connections = requests.map { model.connect(it) }

    assertThat(model.connectRequests).containsExactlyElementsIn(requests).inOrder()
    assertThat(model.connections).containsExactlyElementsIn(connections).inOrder()
  }

  private companion object {
    val TURN_COMPLETE = DummyLiveStep.Respond(LlmResponse(turnComplete = true))

    fun text(text: String) = DummyLiveStep.Respond(LlmResponse(content = modelMessage(text)))

    fun LlmResponse.firstText(): String? = content?.parts?.firstOrNull()?.text
  }
}
