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

package com.google.adk.kt.interop

import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.models.CONCURRENT_COLLECTION_MESSAGE
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.RealtimeInput
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.userFunctionResponse
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FileData
import com.google.adk.kt.types.Part
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.asPublisher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription

/** Bounds a wait whose failure mode is a hang. */
private val GUARD_TIMEOUT = 10.seconds

@OptIn(AdkJavaInteropApi::class)
@RunWith(JUnit4::class)
class BaseFutureLiveConnectionTest {

  private data class SentContent(val content: Content, val partial: Boolean)

  /** Records what reaches each hook, and answers with the futures and turns it was given. */
  private class RecordingConnection(
    private val sendResult: CompletableFuture<Void?> = CompletableFuture.completedFuture(null),
    private val onClose: () -> CompletableFuture<Void?> = {
      CompletableFuture.completedFuture(null)
    },
    turns: List<Flow<LlmResponse>> = emptyList(),
    private val onReceive: (() -> Publisher<LlmResponse>)? = null,
    private val sendThrows: Throwable? = null,
  ) : BaseFutureLiveConnection() {
    private val remainingTurns = ArrayDeque(turns)
    val histories = mutableListOf<List<Content>>()
    val contents = mutableListOf<SentContent>()
    val realtimeInputs = mutableListOf<RealtimeInput>()
    var receiveCalls = 0
    var closeCalls = 0

    override fun sendHistoryAsync(history: List<Content>): CompletableFuture<Void?> {
      if (sendThrows != null) throw sendThrows
      histories.add(history)
      return sendResult
    }

    override fun sendContentAsync(content: Content, partial: Boolean): CompletableFuture<Void?> {
      if (sendThrows != null) throw sendThrows
      contents.add(SentContent(content, partial))
      return sendResult
    }

    override fun sendRealtimeAsync(input: RealtimeInput): CompletableFuture<Void?> {
      if (sendThrows != null) throw sendThrows
      realtimeInputs.add(input)
      return sendResult
    }

    override fun receiveJava(): Publisher<LlmResponse> {
      receiveCalls++
      return onReceive?.invoke() ?: remainingTurns.removeFirst().asPublisher()
    }

    override fun closeSessionAsync(): CompletableFuture<Void?> {
      closeCalls++
      return onClose()
    }
  }

  /**
   * Publishes from a deque shared across publishers, delivering only what is requested, so a new
   * publisher continues where the last one stopped.
   */
  private class DequePublisher(private val responses: ArrayDeque<LlmResponse>) :
    Publisher<LlmResponse> {
    var cancelled = false

    override fun subscribe(subscriber: Subscriber<in LlmResponse>) {
      subscriber.onSubscribe(
        object : Subscription {
          private var completed = false

          override fun request(n: Long) {
            repeat(minOf(n, responses.size.toLong()).toInt()) {
              if (!cancelled) subscriber.onNext(responses.removeFirst())
            }
            if (responses.isEmpty() && !cancelled && !completed) {
              completed = true
              subscriber.onComplete()
            }
          }

          override fun cancel() {
            cancelled = true
          }
        }
      )
    }
  }

  /** Publishes nothing until [error], if any, which it signals as soon as it is subscribed. */
  private class SilentPublisher(private val error: Throwable? = null) : Publisher<LlmResponse> {
    override fun subscribe(subscriber: Subscriber<in LlmResponse>) {
      subscriber.onSubscribe(
        object : Subscription {
          override fun request(n: Long) {}

          override fun cancel() {}
        }
      )
      if (error != null) subscriber.onError(error)
    }
  }

  private fun response(text: String) = LlmResponse(content = modelMessage(text))

  @Test
  fun sendHistory_futureCompletes_forwardsHistory(): Unit = runBlocking {
    val connection = RecordingConnection()
    val history = listOf(userMessage("hello"), modelMessage("hi"))

    connection.sendHistory(history)

    assertThat(connection.histories).containsExactly(history)
  }

  @Test
  fun sendHistory_audioParts_droppedBeforeHook(): Unit = runBlocking {
    val connection = RecordingConnection()
    val spokenAudio = Part(inlineData = Blob(mimeType = "audio/pcm", data = byteArrayOf(1, 2)))
    val recordedAudio = Part(fileData = FileData(mimeType = "audio/wav", fileUri = "file:/a.wav"))
    val image = Part(inlineData = Blob(mimeType = "image/png", data = byteArrayOf(3)))

    connection.sendHistory(
      listOf(
        userMessage(Part(text = "hello"), spokenAudio),
        modelMessage("hi"),
        userMessage(recordedAudio, image),
      )
    )

    assertThat(connection.histories)
      .containsExactly(listOf(userMessage("hello"), modelMessage("hi"), userMessage(image)))
  }

  @Test
  fun sendHistory_onlyAudio_doesNotCallHook(): Unit = runBlocking {
    val connection = RecordingConnection()
    val audio = Part(inlineData = Blob(mimeType = "audio/pcm", data = byteArrayOf(1)))

    connection.sendHistory(listOf(userMessage(audio)))

    assertThat(connection.histories).isEmpty()
  }

  @Test
  fun sendHistory_futureFailsExceptionally_throwsCause(): Unit = runBlocking {
    val failed = CompletableFuture<Void?>().apply { completeExceptionally(IOException("rejected")) }
    val connection = RecordingConnection(sendResult = failed)

    val error =
      assertFailsWith<IOException> { connection.sendHistory(listOf(userMessage("hello"))) }

    assertThat(error).hasMessageThat().isEqualTo("rejected")
  }

  @Test
  fun sendHistory_callerCancelled_cancelsFuture(): Unit = runBlocking {
    val pending = CompletableFuture<Void?>()
    val connection = RecordingConnection(sendResult = pending)
    val send =
      launch(start = CoroutineStart.UNDISPATCHED) {
        connection.sendHistory(listOf(userMessage("hello")))
      }

    send.cancelAndJoin()

    assertThat(pending.isCancelled).isTrue()
    assertThat(send.isCancelled).isTrue()
  }

  @Test
  fun sendHistory_hookFutureCancelled_throwsIllegalStateException(): Unit = runBlocking {
    val cancelled = CompletableFuture<Void?>().apply { cancel(false) }
    val connection = RecordingConnection(sendResult = cancelled)

    val error =
      assertFailsWith<IllegalStateException> { connection.sendHistory(listOf(userMessage("hi"))) }

    // CancellationException is an IllegalStateException, so rule it out explicitly.
    assertThat(error).isNotInstanceOf(CancellationException::class.java)
    assertThat(error).hasMessageThat().contains("sendHistoryAsync")
  }

  @Test
  fun sendContent_partialTrue_forwardsPartial(): Unit = runBlocking {
    val connection = RecordingConnection()

    connection.sendContent(userMessage("first half"), partial = true)

    assertThat(connection.contents).containsExactly(SentContent(userMessage("first half"), true))
  }

  @Test
  fun sendContent_defaultPartial_forwardsPartialFalse(): Unit = runBlocking {
    val connection = RecordingConnection()

    connection.sendContent(userMessage("hi"))

    assertThat(connection.contents).containsExactly(SentContent(userMessage("hi"), false))
  }

  @Test
  fun sendContent_invalidContent_throwsWithoutCallingHook(): Unit = runBlocking {
    val connection = RecordingConnection()

    assertFailsWith<IllegalArgumentException> {
      connection.sendContent(Content(role = "user", parts = emptyList()))
    }

    assertThat(connection.contents).isEmpty()
  }

  @Test
  fun sendContent_partialFunctionResponse_throwsWithoutCallingHook(): Unit = runBlocking {
    val connection = RecordingConnection()

    assertFailsWith<IllegalArgumentException> {
      connection.sendContent(userFunctionResponse("get_weather", id = "call-1"), partial = true)
    }

    assertThat(connection.contents).isEmpty()
  }

  @Test
  fun sendContent_futureFailsExceptionally_throwsCause(): Unit = runBlocking {
    val failed =
      CompletableFuture<Void?>().apply {
        completeExceptionally(IllegalArgumentException("rejected"))
      }
    val connection = RecordingConnection(sendResult = failed)

    val error =
      assertFailsWith<IllegalArgumentException> { connection.sendContent(userMessage("hi")) }

    assertThat(error).hasMessageThat().isEqualTo("rejected")
  }

  @Test
  fun sendContent_callerCancelled_cancelsFuture(): Unit = runBlocking {
    val pending = CompletableFuture<Void?>()
    val connection = RecordingConnection(sendResult = pending)
    val send =
      launch(start = CoroutineStart.UNDISPATCHED) { connection.sendContent(userMessage("hi")) }

    send.cancelAndJoin()

    assertThat(pending.isCancelled).isTrue()
    assertThat(send.isCancelled).isTrue()
  }

  @Test
  fun sendContent_hookFutureCancelled_throwsIllegalStateException(): Unit = runBlocking {
    val cancelled = CompletableFuture<Void?>().apply { cancel(false) }
    val connection = RecordingConnection(sendResult = cancelled)

    val error = assertFailsWith<IllegalStateException> { connection.sendContent(userMessage("hi")) }

    // CancellationException is an IllegalStateException, so rule it out explicitly.
    assertThat(error).isNotInstanceOf(CancellationException::class.java)
    assertThat(error).hasMessageThat().contains("sendContentAsync")
    assertThat(error).hasCauseThat().isInstanceOf(CancellationException::class.java)
  }

  @Test
  fun sendContent_hookFutureCancelledWhileWaiting_throwsIllegalStateException(): Unit =
    runBlocking {
      val pending = CompletableFuture<Void?>()
      val connection = RecordingConnection(sendResult = pending)
      val send =
        async(start = CoroutineStart.UNDISPATCHED) {
          assertFailsWith<IllegalStateException> { connection.sendContent(userMessage("hi")) }
        }

      pending.cancel(false)

      val error = withTimeout(GUARD_TIMEOUT) { send.await() }
      // CancellationException is an IllegalStateException, so rule it out explicitly.
      assertThat(error).isNotInstanceOf(CancellationException::class.java)
      assertThat(error).hasMessageThat().contains("sendContentAsync")
    }

  @Test
  fun sendContent_hookThrowsCancellationException_throwsIllegalStateException(): Unit =
    runBlocking {
      val connection = RecordingConnection(sendThrows = CancellationException("stream reset"))

      val error =
        assertFailsWith<IllegalStateException> { connection.sendContent(userMessage("hi")) }

      // CancellationException is an IllegalStateException, so rule it out explicitly.
      assertThat(error).isNotInstanceOf(CancellationException::class.java)
      assertThat(error).hasMessageThat().contains("sendContentAsync")
    }

  @Test
  fun sendRealtime_activityStart_forwardsInput(): Unit = runBlocking {
    val connection = RecordingConnection()

    connection.sendRealtime(RealtimeInput.ActivityStart)

    assertThat(connection.realtimeInputs).containsExactly(RealtimeInput.ActivityStart)
  }

  @Test
  fun sendRealtime_futureFailsExceptionally_throwsCause(): Unit = runBlocking {
    val failed = CompletableFuture<Void?>().apply { completeExceptionally(IOException("rejected")) }
    val connection = RecordingConnection(sendResult = failed)

    val error = assertFailsWith<IOException> { connection.sendRealtime(RealtimeInput.ActivityEnd) }

    assertThat(error).hasMessageThat().isEqualTo("rejected")
  }

  @Test
  fun sendRealtime_hookFutureCancelled_throwsIllegalStateException(): Unit = runBlocking {
    val cancelled = CompletableFuture<Void?>().apply { cancel(false) }
    val connection = RecordingConnection(sendResult = cancelled)

    val error =
      assertFailsWith<IllegalStateException> { connection.sendRealtime(RealtimeInput.ActivityEnd) }

    // CancellationException is an IllegalStateException, so rule it out explicitly.
    assertThat(error).isNotInstanceOf(CancellationException::class.java)
    assertThat(error).hasMessageThat().contains("sendRealtimeAsync")
  }

  @Test
  fun sendRealtime_callerCancelled_cancelsFuture(): Unit = runBlocking {
    val pending = CompletableFuture<Void?>()
    val connection = RecordingConnection(sendResult = pending)
    val send =
      launch(start = CoroutineStart.UNDISPATCHED) {
        connection.sendRealtime(RealtimeInput.AudioStreamEnd)
      }

    send.cancelAndJoin()

    assertThat(pending.isCancelled).isTrue()
    assertThat(send.isCancelled).isTrue()
  }

  @Test
  fun receive_collectedPerTurn_subscribesFreshPublisherEachTime(): Unit = runBlocking {
    val connection =
      RecordingConnection(turns = listOf(flowOf(response("one")), flowOf(response("two"))))

    val firstTurn = connection.receive().toList()
    val secondTurn = connection.receive().toList()

    assertThat(firstTurn).containsExactly(response("one"))
    assertThat(secondTurn).containsExactly(response("two"))
    assertThat(connection.receiveCalls).isEqualTo(2)
  }

  @Test
  fun receive_collectionEndsEarly_nextCollectionResumes(): Unit = runBlocking {
    val responses = ArrayDeque(listOf(response("one"), response("two"), response("three")))
    val connection = RecordingConnection(onReceive = { DequePublisher(responses) })

    val firstCollection = connection.receive().take(1).toList()
    val secondCollection = connection.receive().toList()

    assertThat(firstCollection).containsExactly(response("one"))
    assertThat(secondCollection).containsExactly(response("two"), response("three")).inOrder()
  }

  @Test
  fun receive_secondConcurrentCollector_throwsIllegalStateException(): Unit = runBlocking {
    val firstStarted = CompletableDeferred<Unit>()
    val releaseFirst = CompletableDeferred<Unit>()
    val connection =
      RecordingConnection(
        turns =
          listOf(
            flow {
              firstStarted.complete(Unit)
              releaseFirst.await()
              emit(response("one"))
            }
          )
      )
    val firstCollector = launch { connection.receive().toList() }
    firstStarted.await()

    val error =
      withTimeout(GUARD_TIMEOUT) {
        assertFailsWith<IllegalStateException> { connection.receive().toList() }
      }

    releaseFirst.complete(Unit)
    withTimeout(GUARD_TIMEOUT) { firstCollector.join() }
    assertThat(error).hasMessageThat().isEqualTo(CONCURRENT_COLLECTION_MESSAGE)
    assertThat(connection.receiveCalls).isEqualTo(1)
  }

  @Test
  fun receive_collectorCancelledMidTurn_cancelsSubscriptionAndReleasesGuard(): Unit = runBlocking {
    val responses = ArrayDeque(listOf(response("one"), response("two")))
    val publishers = mutableListOf<DequePublisher>()
    val connection =
      RecordingConnection(onReceive = { DequePublisher(responses).also { publishers.add(it) } })
    val firstReceived = CompletableDeferred<LlmResponse>()
    val collector = launch {
      connection.receive().collect {
        firstReceived.complete(it)
        awaitCancellation()
      }
    }
    assertThat(withTimeout(GUARD_TIMEOUT) { firstReceived.await() }).isEqualTo(response("one"))

    collector.cancelAndJoin()

    assertThat(publishers.single().cancelled).isTrue()
    assertThat(withTimeout(GUARD_TIMEOUT) { connection.receive().toList() })
      .containsExactly(response("two"))
  }

  @Test
  fun receive_publisherErrors_rethrowsError(): Unit = runBlocking {
    val connection =
      RecordingConnection(
        turns = listOf(flow { throw IOException("transport lost") }, flowOf(response("next")))
      )

    val error = assertFailsWith<IOException> { connection.receive().toList() }

    assertThat(error).hasMessageThat().isEqualTo("transport lost")
    // The failed collection released the guard, so the next turn can be collected.
    assertThat(connection.receive().toList()).containsExactly(response("next"))
  }

  @Test
  fun receive_publisherFailsWithCancellationException_throwsIllegalStateException(): Unit =
    runBlocking {
      var calls = 0
      val connection =
        RecordingConnection(
          onReceive = {
            if (++calls == 1) SilentPublisher(CancellationException("stream reset"))
            else flowOf(response("next")).asPublisher()
          }
        )

      val error = assertFailsWith<IllegalStateException> { connection.receive().toList() }

      // CancellationException is an IllegalStateException, so rule it out explicitly.
      assertThat(error).isNotInstanceOf(CancellationException::class.java)
      assertThat(error).hasMessageThat().contains("receiveJava")
      assertThat(connection.receive().toList()).containsExactly(response("next"))
    }

  @Test
  fun receive_receiveJavaThrowsCancellationException_throwsIllegalStateException(): Unit =
    runBlocking {
      var calls = 0
      val connection =
        RecordingConnection(
          onReceive = {
            if (++calls == 1) throw CancellationException("stream reset")
            flowOf(response("next")).asPublisher()
          }
        )

      val error = assertFailsWith<IllegalStateException> { connection.receive().toList() }

      // CancellationException is an IllegalStateException, so rule it out explicitly.
      assertThat(error).isNotInstanceOf(CancellationException::class.java)
      assertThat(error).hasMessageThat().contains("receiveJava")
      assertThat(connection.receive().toList()).containsExactly(response("next"))
    }

  @Test
  fun receive_collectorCancelledWhilePublisherPending_throwsCancellationException(): Unit =
    runBlocking {
      val connection = RecordingConnection(onReceive = { SilentPublisher() })
      val collector = async(start = CoroutineStart.UNDISPATCHED) { connection.receive().toList() }

      collector.cancel()
      withTimeout(GUARD_TIMEOUT) { collector.join() }

      // The collector's own cancellation stays a cancellation.
      assertFailsWith<CancellationException> { collector.await() }
    }

  @Test
  fun receive_publisherFailsWithCancellationAfterCollectorCancelled_throwsCancellationException():
    Unit = runBlocking {
    lateinit var subscriber: Subscriber<in LlmResponse>
    val publisher =
      Publisher<LlmResponse> { s ->
        subscriber = s
        s.onSubscribe(
          object : Subscription {
            private var sent = false

            override fun request(n: Long) {
              if (sent) return
              sent = true
              s.onNext(response("one"))
            }

            override fun cancel() {}
          }
        )
      }
    val connection = RecordingConnection(onReceive = { publisher })
    val collector =
      async(start = CoroutineStart.UNDISPATCHED) {
        connection.receive().collect {
          // The collector cancels itself, and then the publisher fails with a cancellation.
          currentCoroutineContext().cancel()
          subscriber.onError(CancellationException("stream reset"))
        }
      }

    withTimeout(GUARD_TIMEOUT) { collector.join() }

    assertFailsWith<CancellationException> { collector.await() }
  }

  @Test
  fun receive_receiveJavaThrows_rethrowsAndReleasesGuard(): Unit = runBlocking {
    var calls = 0
    val connection =
      RecordingConnection(
        onReceive = {
          if (++calls == 1) throw IOException("not connected")
          flowOf(response("next")).asPublisher()
        }
      )

    val error = assertFailsWith<IOException> { connection.receive().toList() }

    assertThat(error).hasMessageThat().isEqualTo("not connected")
    assertThat(connection.receive().toList()).containsExactly(response("next"))
  }

  @Test
  fun closeSession_calledTwice_invokesHookOnce(): Unit = runBlocking {
    val connection = RecordingConnection()

    connection.closeSession()
    connection.closeSession()

    assertThat(connection.closeCalls).isEqualTo(1)
  }

  @Test
  fun closeSession_secondCallWhileClosing_waitsForClose(): Unit = runBlocking {
    val pendingClose = CompletableFuture<Void?>()
    val connection = RecordingConnection(onClose = { pendingClose })
    val firstClose = launch(start = CoroutineStart.UNDISPATCHED) { connection.closeSession() }
    assertThat(firstClose.isActive).isTrue()

    val secondClose = launch(start = CoroutineStart.UNDISPATCHED) { connection.closeSession() }

    assertThat(secondClose.isActive).isTrue()
    pendingClose.complete(null)
    withTimeout(GUARD_TIMEOUT) { joinAll(firstClose, secondClose) }
    assertThat(connection.closeCalls).isEqualTo(1)
  }

  @Test
  fun closeSession_secondCallWhileClosingAndHookFails_firstThrowsSecondReturns(): Unit =
    runBlocking {
      val pendingClose = CompletableFuture<Void?>()
      val connection = RecordingConnection(onClose = { pendingClose })

      val firstClose =
        async(start = CoroutineStart.UNDISPATCHED) {
          assertFailsWith<IOException> { connection.closeSession() }
        }
      val secondClose = async(start = CoroutineStart.UNDISPATCHED) { connection.closeSession() }
      assertThat(firstClose.isActive).isTrue()
      assertThat(secondClose.isActive).isTrue()

      pendingClose.completeExceptionally(IOException("close failed"))

      assertThat(withTimeout(GUARD_TIMEOUT) { firstClose.await() })
        .hasMessageThat()
        .isEqualTo("close failed")
      withTimeout(GUARD_TIMEOUT) { secondClose.await() }
      assertThat(connection.closeCalls).isEqualTo(1)
    }

  @Test
  fun closeSession_callerCancelledWhileClosing_doesNotCancelHookFuture(): Unit = runBlocking {
    val pendingClose = CompletableFuture<Void?>()
    val connection = RecordingConnection(onClose = { pendingClose })
    val firstClose = launch(start = CoroutineStart.UNDISPATCHED) { connection.closeSession() }
    assertThat(firstClose.isActive).isTrue()

    firstClose.cancelAndJoin()

    assertThat(pendingClose.isCancelled).isFalse()
    // A teardown that closes again, as a live run's does, still waits for the close in flight.
    val teardown = launch(start = CoroutineStart.UNDISPATCHED) { connection.closeSession() }
    assertThat(teardown.isActive).isTrue()
    pendingClose.complete(null)
    withTimeout(GUARD_TIMEOUT) { teardown.join() }
    assertThat(connection.closeCalls).isEqualTo(1)
  }

  @Test
  fun closeSession_waitingCallerCancelled_leavesCloseRunning(): Unit = runBlocking {
    val pendingClose = CompletableFuture<Void?>()
    val connection = RecordingConnection(onClose = { pendingClose })
    val firstClose = launch(start = CoroutineStart.UNDISPATCHED) { connection.closeSession() }
    val waitingClose = launch(start = CoroutineStart.UNDISPATCHED) { connection.closeSession() }
    assertThat(waitingClose.isActive).isTrue()

    waitingClose.cancelAndJoin()

    assertThat(firstClose.isActive).isTrue()
    pendingClose.complete(null)
    withTimeout(GUARD_TIMEOUT) { firstClose.join() }
    assertThat(firstClose.isCancelled).isFalse()
    assertThat(pendingClose.isCancelled).isFalse()
    assertThat(connection.closeCalls).isEqualTo(1)
  }

  @Test
  fun closeSession_whileSendInFlight_runsCloseWithoutWaitingForSend(): Unit = runBlocking {
    val pendingSend = CompletableFuture<Void?>()
    val connection = RecordingConnection(sendResult = pendingSend)
    val send =
      launch(start = CoroutineStart.UNDISPATCHED) { connection.sendContent(userMessage("hi")) }

    withTimeout(GUARD_TIMEOUT) { connection.closeSession() }

    assertThat(connection.closeCalls).isEqualTo(1)
    assertThat(send.isActive).isTrue()
    pendingSend.complete(null)
    withTimeout(GUARD_TIMEOUT) { send.join() }
  }

  @Test
  fun closeSession_hookFails_firstCallThrowsLaterCallsReturn(): Unit = runBlocking {
    val failed =
      CompletableFuture<Void?>().apply { completeExceptionally(IOException("close failed")) }
    val connection = RecordingConnection(onClose = { failed })

    val error = assertFailsWith<IOException> { connection.closeSession() }
    connection.closeSession()

    assertThat(error).hasMessageThat().isEqualTo("close failed")
    assertThat(connection.closeCalls).isEqualTo(1)
  }

  @Test
  fun closeSession_hookThrows_firstCallThrowsLaterCallsReturn(): Unit = runBlocking {
    val connection = RecordingConnection(onClose = { throw IllegalStateException("not open") })

    val error =
      withTimeout(GUARD_TIMEOUT) {
        assertFailsWith<IllegalStateException> { connection.closeSession() }
      }
    withTimeout(GUARD_TIMEOUT) { connection.closeSession() }

    assertThat(error).hasMessageThat().isEqualTo("not open")
    assertThat(connection.closeCalls).isEqualTo(1)
  }

  @Test
  fun closeSession_hookThrowsCancellationException_firstCallThrowsLaterCallsReturn(): Unit =
    runBlocking {
      val connection =
        RecordingConnection(onClose = { throw CancellationException("stream reset") })

      val error =
        withTimeout(GUARD_TIMEOUT) {
          assertFailsWith<IllegalStateException> { connection.closeSession() }
        }
      withTimeout(GUARD_TIMEOUT) { connection.closeSession() }

      // CancellationException is an IllegalStateException, so rule it out explicitly.
      assertThat(error).isNotInstanceOf(CancellationException::class.java)
      assertThat(error).hasMessageThat().contains("closeSessionAsync")
      assertThat(connection.closeCalls).isEqualTo(1)
    }

  @Test
  fun closeSession_hookFutureCancelled_firstCallThrowsLaterCallsReturn(): Unit = runBlocking {
    val cancelled = CompletableFuture<Void?>().apply { cancel(false) }
    val connection = RecordingConnection(onClose = { cancelled })

    val error = assertFailsWith<IllegalStateException> { connection.closeSession() }
    connection.closeSession()

    // CancellationException is an IllegalStateException, so rule it out explicitly.
    assertThat(error).isNotInstanceOf(CancellationException::class.java)
    assertThat(error).hasMessageThat().contains("closeSessionAsync")
    assertThat(connection.closeCalls).isEqualTo(1)
  }

  @Test
  fun close_outsideCoroutine_runsCloseSessionHook() {
    val connection = RecordingConnection()

    connection.close()

    assertThat(connection.closeCalls).isEqualTo(1)
  }

  @Test
  fun close_declaration_isFinal() {
    // Final, so a Java subclass cannot replace the graceful close with an abrupt one.
    assertThat(BaseFutureLiveConnection::class.java.getMethod("close").toString())
      .contains(" final ")
  }

  @Test
  fun close_whileCloseSessionInFlight_waitsAndRunsHookOnce(): Unit = runBlocking {
    val pendingClose = CompletableFuture<Void?>()
    val connection = RecordingConnection(onClose = { pendingClose })
    val closeSession = launch(start = CoroutineStart.UNDISPATCHED) { connection.closeSession() }

    // close() blocks its thread until the close finishes, so it runs on another one.
    val blockingClose = CompletableFuture.runAsync { connection.close() }

    assertThat(blockingClose.isDone).isFalse()
    pendingClose.complete(null)
    blockingClose.get(5, TimeUnit.SECONDS)
    withTimeout(GUARD_TIMEOUT) { closeSession.join() }
    assertThat(connection.closeCalls).isEqualTo(1)
  }
}
