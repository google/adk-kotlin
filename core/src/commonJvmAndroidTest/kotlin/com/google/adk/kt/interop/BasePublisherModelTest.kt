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
import com.google.adk.kt.models.LiveConnection
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.RealtimeInput
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.types.Content
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.util.concurrent.CompletableFuture
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asPublisher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.reactivestreams.Publisher

/** Bounds a wait whose failure mode is a hang. */
private val GUARD_TIMEOUT = 10.seconds

@OptIn(AdkJavaInteropApi::class)
@RunWith(JUnit4::class)
class BasePublisherModelTest {

  /** Holds no transport; counts closes, so a test can see a dropped connection was closed. */
  private class StubLiveConnection(
    private val closeError: Throwable? = null,
    private val closeGate: CompletableDeferred<Unit>? = null,
  ) : LiveConnection {
    var closeSessionCalls = 0

    override suspend fun sendHistory(history: List<Content>) {}

    override suspend fun sendContent(content: Content, partial: Boolean) {}

    override suspend fun sendRealtime(input: RealtimeInput) {}

    override fun receive(): Flow<LlmResponse> = emptyFlow()

    override suspend fun closeSession() {
      // Suspends first, so a close run from a cancelled caller's context throws before counting.
      yield()
      closeSessionCalls++
      if (closeError != null) throw closeError
      closeGate?.await()
    }
  }

  /** Completes with [connection] when cancelled, like a handshake finishing at the deadline. */
  private class CompletesWhenCancelledFuture(private val connection: LiveConnection) :
    CompletableFuture<LiveConnection>() {
    override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
      complete(connection)
      return false
    }
  }

  /** A model that implements only the required hook, as a Java model without live support does. */
  private open class OfflineModel(name: String = "offline-model") : BasePublisherModel(name) {
    override fun generateContentJava(request: LlmRequest, stream: Boolean): Publisher<LlmResponse> =
      emptyFlow<LlmResponse>().asPublisher()
  }

  /** Answers [connectAsync] with [connectResult] and records each request it receives. */
  private class LiveModel(private val connectResult: CompletableFuture<out LiveConnection>) :
    OfflineModel("live-model") {
    val requests = mutableListOf<LlmRequest>()

    override fun connectAsync(request: LlmRequest): CompletableFuture<out LiveConnection> {
      requests.add(request)
      return connectResult
    }
  }

  @Test
  fun generateContent_publisherResponses_flowThrough(): Unit = runBlocking {
    val responses =
      listOf(LlmResponse(content = modelMessage("one")), LlmResponse(content = modelMessage("two")))
    val streams = mutableListOf<Boolean>()
    val model =
      object : OfflineModel("streaming-model") {
        override fun generateContentJava(
          request: LlmRequest,
          stream: Boolean,
        ): Publisher<LlmResponse> {
          streams.add(stream)
          return flowOf(*responses.toTypedArray()).asPublisher()
        }
      }

    val received = model.generateContent(LlmRequest(), stream = true).toList()

    assertThat(received).containsExactlyElementsIn(responses).inOrder()
    assertThat(streams).containsExactly(true)
  }

  @Test
  fun connect_hookNotOverridden_throwsNamingTheModel(): Unit = runBlocking {
    val error =
      assertFailsWith<UnsupportedOperationException> { OfflineModel().connect(LlmRequest()) }

    assertThat(error).hasMessageThat().contains("offline-model")
  }

  @Test
  fun connect_hookOverridden_returnsItsConnection(): Unit = runBlocking {
    val connection = StubLiveConnection()
    val model = LiveModel(CompletableFuture.completedFuture(connection))
    val request = LlmRequest(contents = listOf(userMessage("hi")))

    val opened = model.connect(request)

    assertThat(opened).isSameInstanceAs(connection)
    assertThat(model.requests).containsExactly(request)
  }

  @Test
  fun connect_hookReturnsFutureOfSubtype_returnsItsConnection(): Unit = runBlocking {
    val connection = StubLiveConnection()
    val model =
      object : OfflineModel("typed-model") {
        override fun connectAsync(request: LlmRequest): CompletableFuture<StubLiveConnection> =
          CompletableFuture.completedFuture(connection)
      }

    assertThat(model.connect(LlmRequest())).isSameInstanceAs(connection)
  }

  @Test
  fun connect_hookThrowsSynchronously_propagatesException(): Unit = runBlocking {
    val model =
      object : OfflineModel("failing-model") {
        override fun connectAsync(request: LlmRequest): CompletableFuture<out LiveConnection> =
          throw IllegalStateException("no credentials")
      }

    val error = assertFailsWith<IllegalStateException> { model.connect(LlmRequest()) }

    assertThat(error).hasMessageThat().isEqualTo("no credentials")
  }

  @Test
  fun connect_hookThrowsCancellationException_throwsIllegalStateException(): Unit = runBlocking {
    val model =
      object : OfflineModel("cancelling-model") {
        override fun connectAsync(request: LlmRequest): CompletableFuture<out LiveConnection> =
          throw CancellationException("handshake aborted")
      }

    val error = assertFailsWith<IllegalStateException> { model.connect(LlmRequest()) }

    // CancellationException is an IllegalStateException, so rule it out explicitly.
    assertThat(error).isNotInstanceOf(CancellationException::class.java)
    assertThat(error).hasMessageThat().contains("connectAsync")
  }

  @Test
  fun connect_hookFutureFails_throwsCause(): Unit = runBlocking {
    val failed =
      CompletableFuture<LiveConnection>().apply {
        completeExceptionally(IOException("handshake failed"))
      }

    val error = assertFailsWith<IOException> { LiveModel(failed).connect(LlmRequest()) }

    assertThat(error).hasMessageThat().isEqualTo("handshake failed")
  }

  @Test
  fun connect_hookFutureCompletesWithNull_throwsIllegalStateException(): Unit = runBlocking {
    val completedWithNull = CompletableFuture<LiveConnection>().apply { complete(null) }

    val error =
      assertFailsWith<IllegalStateException> { LiveModel(completedWithNull).connect(LlmRequest()) }

    assertThat(error).hasMessageThat().contains("live-model")
  }

  @Test
  fun connect_hookFutureCancelled_throwsIllegalStateException(): Unit = runBlocking {
    val cancelled = CompletableFuture<LiveConnection>().apply { cancel(false) }

    val error =
      assertFailsWith<IllegalStateException> { LiveModel(cancelled).connect(LlmRequest()) }

    // CancellationException is an IllegalStateException, so rule it out explicitly.
    assertThat(error).isNotInstanceOf(CancellationException::class.java)
    assertThat(error).hasMessageThat().contains("connectAsync")
  }

  @Test
  fun connect_callerCancelledBeforeFutureCompletes_closesConnectionWhenItArrives(): Unit =
    runBlocking {
      val pending = CompletableFuture<LiveConnection>()
      val connection = StubLiveConnection()
      val connect =
        async(start = CoroutineStart.UNDISPATCHED) { LiveModel(pending).connect(LlmRequest()) }

      connect.cancel()
      yield()

      // The base still waits for the handshake, so it can close the connection that arrives.
      assertThat(connect.isCompleted).isFalse()
      assertThat(pending.complete(connection)).isTrue()
      withTimeout(GUARD_TIMEOUT) { connect.join() }
      assertThat(pending.isCancelled).isFalse()
      assertThat(connection.closeSessionCalls).isEqualTo(1)
      assertFailsWith<CancellationException> { connect.await() }
    }

  @Test
  fun connect_futureCompletesAsCallerIsCancelled_closesConnection(): Unit = runBlocking {
    val pending = CompletableFuture<LiveConnection>()
    val connection = StubLiveConnection()
    val connect =
      async(start = CoroutineStart.UNDISPATCHED) { LiveModel(pending).connect(LlmRequest()) }

    // The resume is queued on this thread, so the cancel lands before the caller takes the result.
    pending.complete(connection)
    connect.cancel()
    withTimeout(GUARD_TIMEOUT) { connect.join() }

    assertFailsWith<CancellationException> { connect.await() }
    assertThat(connection.closeSessionCalls).isEqualTo(1)
  }

  @Test
  fun connect_lateConnectionCloseFails_stillThrowsCancellation(): Unit = runBlocking {
    val pending = CompletableFuture<LiveConnection>()
    val connection = StubLiveConnection(closeError = IOException("socket gone"))

    val connect =
      async(start = CoroutineStart.UNDISPATCHED) { LiveModel(pending).connect(LlmRequest()) }

    pending.complete(connection)
    connect.cancel()
    withTimeout(GUARD_TIMEOUT) { connect.join() }

    assertFailsWith<CancellationException> { connect.await() }
    assertThat(connection.closeSessionCalls).isEqualTo(1)
  }

  // runTest, not runBlocking: these wait out the 10-second late-connection bound in virtual time.

  @Test
  fun connect_callerCancelledAndFutureNeverCompletes_cancelsFutureAtDeadline() = runTest {
    val pending = CompletableFuture<LiveConnection>()
    val connect =
      async(start = CoroutineStart.UNDISPATCHED) { LiveModel(pending).connect(LlmRequest()) }

    try {
      connect.cancel()
      advanceTimeBy(9.seconds)
      runCurrent()
      assertThat(pending.isCancelled).isFalse()
      advanceTimeBy(1.seconds)
      runCurrent()

      assertThat(pending.isCancelled).isTrue()
      assertFailsWith<CancellationException> { connect.await() }
    } finally {
      // Releases what a regression would leave waiting, so the test fails rather than hangs.
      pending.cancel(false)
    }
  }

  @Test
  fun connect_futureCompletesAsDeadlineCancelsIt_closesConnection() = runTest {
    val connection = StubLiveConnection()
    val racing = CompletesWhenCancelledFuture(connection)
    val connect =
      async(start = CoroutineStart.UNDISPATCHED) { LiveModel(racing).connect(LlmRequest()) }

    try {
      connect.cancel()
      advanceTimeBy(9.seconds)
      runCurrent()
      assertThat(connection.closeSessionCalls).isEqualTo(0)
      advanceTimeBy(1.seconds)
      runCurrent()

      assertThat(connection.closeSessionCalls).isEqualTo(1)
      assertFailsWith<CancellationException> { connect.await() }
    } finally {
      // Releases what a regression would leave waiting, so the test fails rather than hangs.
      racing.complete(connection)
    }
  }

  @Test
  fun connect_lateConnectionCloseHangs_givesUpAtDeadline() = runTest {
    val pending = CompletableFuture<LiveConnection>()
    val closeGate = CompletableDeferred<Unit>()
    val connection = StubLiveConnection(closeGate = closeGate)
    val connect =
      async(start = CoroutineStart.UNDISPATCHED) { LiveModel(pending).connect(LlmRequest()) }

    try {
      connect.cancel()
      pending.complete(connection)
      advanceTimeBy(9.seconds)
      runCurrent()
      assertThat(connection.closeSessionCalls).isEqualTo(1)
      assertThat(connect.isCompleted).isFalse()
      advanceTimeBy(1.seconds)
      runCurrent()

      assertThat(connect.isCompleted).isTrue()
      assertFailsWith<CancellationException> { connect.await() }
    } finally {
      // Releases what a regression would leave waiting, so the test fails rather than hangs.
      closeGate.complete(Unit)
    }
  }
}
