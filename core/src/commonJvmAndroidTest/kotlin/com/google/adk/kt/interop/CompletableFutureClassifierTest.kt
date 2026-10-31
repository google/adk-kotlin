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

@file:OptIn(AdkJavaInteropApi::class, ExperimentalClassifierApi::class)

package com.google.adk.kt.interop

import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.annotations.ExperimentalClassifierApi
import com.google.adk.kt.classifiers.Binary
import com.google.adk.kt.classifiers.BinaryAnswer
import com.google.adk.kt.classifiers.Classifier
import com.google.adk.kt.classifiers.ClassifierResponse
import com.google.adk.kt.classifiers.Question
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class CompletableFutureClassifierTest {

  private val questionMap = mapOf<String, Question>("risky" to Binary(instructions = "Risky?"))
  private val cannedResponse =
    ClassifierResponse(
      answers = mapOf("risky" to BinaryAnswer(probability = 0.25)),
      modelVersion = "jev-1.13.0",
    )

  @Test
  fun classify_forwardsTheArgumentsAndTheResponse() {
    val delegate = RecordingClassifier()

    CompletableFutureClassifier.of(delegate).use { wrapper ->
      assertThat(wrapper.classify("ticket", questionMap).get(5, SECONDS)).isEqualTo(cannedResponse)
    }

    assertThat(delegate.calls).containsExactly("ticket" to questionMap)
  }

  @Test
  fun asKotlinClassifier_returnsTheWrappedClassifier() {
    val delegate = RecordingClassifier()

    CompletableFutureClassifier.of(delegate).use { wrapper ->
      assertThat(wrapper.asKotlinClassifier()).isSameInstanceAs(delegate)
    }
  }

  @Test
  fun classify_delegateThrows_failsTheFutureWithTheCause() {
    val delegate = RecordingClassifier { throw IllegalStateException("upstream down") }

    CompletableFutureClassifier.of(delegate).use { wrapper ->
      val thrown =
        assertFailsWith<ExecutionException> { wrapper.classify("x", questionMap).get(5, SECONDS) }
      assertThat(thrown).hasCauseThat().isInstanceOf(IllegalStateException::class.java)
      assertThat(thrown).hasCauseThat().hasMessageThat().isEqualTo("upstream down")
    }
  }

  @Test
  fun classify_callerHasATraceContext_runsInIt() {
    val seen = ConcurrentLinkedQueue<String>()
    val delegate = RecordingClassifier { seen += CallerTrace.traceAndName() }

    CompletableFutureClassifier.of(delegate).use { wrapper ->
      CallerTrace.CONTEXT.makeCurrent().use { wrapper.classify("x", questionMap).get(5, SECONDS) }
    }

    assertThat(seen).containsExactly("caller/null")
  }

  @Test
  fun classify_withCoroutineContext_runsInItAndTheCallersTraceContext() {
    val seen = ConcurrentLinkedQueue<String>()
    val delegate = RecordingClassifier { seen += CallerTrace.traceAndName() }

    CompletableFutureClassifier.of(delegate).use { wrapper ->
      CallerTrace.CONTEXT.makeCurrent().use {
        wrapper.classify("x", questionMap, CoroutineName("classify-call")).get(5, SECONDS)
      }
    }

    assertThat(seen).containsExactly("caller/classify-call")
  }

  @Test
  fun classify_canceledJobInTheCoroutineContext_isIgnored() {
    val delegate = RecordingClassifier()
    val canceledJob = Job().apply { cancel() }

    CompletableFutureClassifier.of(delegate).use { wrapper ->
      assertThat(wrapper.classify("x", questionMap, canceledJob).get(5, SECONDS))
        .isEqualTo(cannedResponse)
    }
  }

  @Test
  fun classify_futureCanceled_cancelsTheCall(): Unit = runBlocking {
    val hanging = HangingClassifier()

    CompletableFutureClassifier.of(hanging).use { wrapper ->
      val future = wrapper.classify("x", questionMap)
      hanging.started.await()

      future.cancel(true)

      withTimeout(5.seconds) { hanging.canceled.await() }
    }
  }

  @Test
  fun close_cancelsCallsInFlightAndLaterCalls(): Unit = runBlocking {
    val hanging = HangingClassifier()
    val wrapper = CompletableFutureClassifier.of(hanging)
    val inFlight = wrapper.classify("x", questionMap)
    hanging.started.await()

    wrapper.close()
    val afterClose = wrapper.classify("x", questionMap)

    withTimeout(5.seconds) { hanging.canceled.await() }
    assertFailsWith<CancellationException> { inFlight.await() }
    assertFailsWith<CancellationException> { afterClose.await() }
    assertThat(hanging.calls.get()).isEqualTo(1)
  }

  @Test
  fun close_autoCloseableClassifier_closesTheClassifier() {
    var closed = false
    val delegate =
      object : Classifier by RecordingClassifier(), AutoCloseable {
        override fun close() {
          closed = true
        }
      }

    CompletableFutureClassifier.of(delegate).close()

    assertThat(closed).isTrue()
  }

  /** Records every call, runs [onCall], then answers with [cannedResponse]. */
  private inner class RecordingClassifier(private val onCall: suspend () -> Unit = {}) :
    Classifier {
    override val name: String = "recording"
    val calls = ConcurrentLinkedQueue<Pair<Any, Map<String, Question>>>()

    override suspend fun classify(
      input: Any,
      questions: Map<String, Question>,
    ): ClassifierResponse {
      calls += input to questions
      onCall()
      return cannedResponse
    }
  }

  /** Suspends until canceled, signaling [started] and [canceled]. */
  private class HangingClassifier : Classifier {
    override val name: String = "hanging"
    val calls = AtomicInteger()
    val started = CompletableDeferred<Unit>()
    val canceled = CompletableDeferred<Unit>()

    override suspend fun classify(
      input: Any,
      questions: Map<String, Question>,
    ): ClassifierResponse {
      calls.incrementAndGet()
      started.complete(Unit)
      try {
        awaitCancellation()
      } finally {
        canceled.complete(Unit)
      }
    }
  }
}
