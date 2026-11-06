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
import com.google.common.truth.Truth.assertThat
import com.google.errorprone.annotations.MustBeClosed
import io.opentelemetry.context.Context
import io.opentelemetry.context.Scope
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription

@OptIn(AdkJavaInteropApi::class)
@RunWith(JUnit4::class)
class AsyncJavaHelpersTest {

  private val scope = CoroutineScope(Dispatchers.Default)

  /** Emits the trace value and coroutine name it runs with, from another dispatcher. */
  private val tracedFlow = flow { emit(CallerTrace.traceAndName()) }.flowOn(Dispatchers.IO)

  @After
  fun cancelScope() {
    scope.cancel()
  }

  @Test
  fun await_switchingDispatchers_keepsTheCallersTraceContext() {
    val seen =
      CallerTrace.CONTEXT.makeCurrent().use {
        AsyncJavaHelpers.await { withContext(Dispatchers.IO) { CallerTrace.traceAndName() } }
      }

    assertThat(seen).isEqualTo("caller/null")
  }

  @Test
  fun await_withCoroutineContext_runsInIt() {
    assertThat(AsyncJavaHelpers.await(CoroutineName("call")) { CallerTrace.traceAndName() })
      .isEqualTo("null/call")
  }

  @Test
  fun await_canceledJobInTheCoroutineContext_isIgnored() {
    val canceled = Job().apply { cancel() }

    assertThat(AsyncJavaHelpers.await(canceled) { "ran" }).isEqualTo("ran")
  }

  @Test
  fun async_callerHasATraceContext_runsInIt() {
    val seen =
      CallerTrace.CONTEXT.makeCurrent().use {
        AsyncJavaHelpers.async(scope) { CallerTrace.traceAndName() }.get(5, SECONDS)
      }

    assertThat(seen).isEqualTo("caller/null")
  }

  @Test
  fun async_scopeCarriesATraceContext_runsInItInsteadOfTheCallers() {
    val traced = CoroutineScope(Dispatchers.Default + OtelContextElement(CallerTrace.CONTEXT))

    try {
      assertThat(AsyncJavaHelpers.async(traced) { CallerTrace.traceAndName() }.get(5, SECONDS))
        .isEqualTo("caller/null")
    } finally {
      traced.cancel()
    }
  }

  @Test
  fun collect_flowOnAnotherDispatcher_runsInTheCallersTraceContext() {
    val seen = CallerTrace.CONTEXT.makeCurrent().use { AsyncJavaHelpers.collect(tracedFlow) }

    assertThat(seen).containsExactly("caller/null")
  }

  @Test
  fun collect_withCoroutineContext_runsInIt() {
    assertThat(AsyncJavaHelpers.collect(tracedFlow, CoroutineName("call")))
      .containsExactly("null/call")
  }

  @Test
  fun forEach_callerHasATraceContext_runsInIt() {
    val seen = mutableListOf<String>()

    CallerTrace.CONTEXT.makeCurrent().use { AsyncJavaHelpers.forEach(tracedFlow) { seen += it } }

    assertThat(seen).containsExactly("caller/null")
  }

  @Test
  fun forEach_withCoroutineContext_runsInItAndTheCallersTraceContext() {
    val seen = mutableListOf<String>()

    CallerTrace.CONTEXT.makeCurrent().use {
      AsyncJavaHelpers.forEach(tracedFlow, CoroutineName("call")) { seen += it }
    }

    assertThat(seen).containsExactly("caller/call")
  }

  @Test
  fun asPublisher_callerHasATraceContext_runsInIt() {
    val publisher =
      CallerTrace.CONTEXT.makeCurrent().use { AsyncJavaHelpers.asPublisher(tracedFlow) }

    // Collected once the caller's context is closed, so only the publisher can carry it.
    val seen = AsyncJavaHelpers.collect(AsyncJavaHelpers.asFlow(publisher))

    assertThat(seen).containsExactly("caller/null")
  }

  @Test
  fun asPublisher_noCoroutineContext_signalsOffTheEmittingThread() {
    val input = Channel<String>(Channel.UNLIMITED)
    val subscriber = ThreadRecordingSubscriber()
    AsyncJavaHelpers.asPublisher(input.consumeAsFlow()).subscribe(subscriber)

    thread(name = "emitter") {
        val unused = input.trySend("a")
        input.close()
      }
      .join()

    assertThat(subscriber.awaitThreads().single()).isNoneOf("emitter", Thread.currentThread().name)
  }

  @Test
  fun asPublisher_dispatcherGiven_signalsOnIt() {
    val subscriber = ThreadRecordingSubscriber()

    Executors.newSingleThreadExecutor { Thread(it, "adk-publisher") }
      .asCoroutineDispatcher()
      .use { dispatcher ->
        AsyncJavaHelpers.asPublisher(flowOf("a", "b"), dispatcher).subscribe(subscriber)
        assertThat(subscriber.awaitThreads()).containsExactly("adk-publisher", "adk-publisher")
      }
  }

  @Test
  fun asPublisher_fromJava_keepsTheOneArgumentOverload() {
    val method = AsyncJavaHelpers::class.java.getMethod("asPublisher", Flow::class.java)

    assertThat(method.returnType).isEqualTo(Publisher::class.java)
    // A null receiver works only for a static method, which is how Java calls it.
    assertThat(method.invoke(null, flowOf("a"))).isInstanceOf(Publisher::class.java)
  }
}

/** Requests every element and records the thread each one arrives on. */
private class ThreadRecordingSubscriber : Subscriber<String> {
  private val threads = CopyOnWriteArrayList<String>()
  private val error = AtomicReference<Throwable>()
  private val done = CountDownLatch(1)

  /** Waits for the publisher to complete, then returns the thread names, in order. */
  fun awaitThreads(): List<String> {
    assertThat(done.await(5, SECONDS)).isTrue()
    assertThat(error.get()).isNull()
    return threads
  }

  override fun onSubscribe(subscription: Subscription) {
    subscription.request(Long.MAX_VALUE)
  }

  override fun onNext(item: String) {
    // Without the ` @coroutine#N` suffix that coroutine debug mode adds.
    threads += Thread.currentThread().name.substringBefore(" @coroutine#")
  }

  override fun onError(t: Throwable) {
    error.set(t)
    done.countDown()
  }

  override fun onComplete() {
    done.countDown()
  }
}

/** Makes [otelContext] current while a coroutine runs, as opentelemetry-extension-kotlin does. */
private class OtelContextElement(private val otelContext: Context) : ThreadContextElement<Scope> {
  companion object Key : CoroutineContext.Key<OtelContextElement>

  override val key: CoroutineContext.Key<OtelContextElement>
    get() = Key

  @MustBeClosed
  override fun updateThreadContext(context: CoroutineContext): Scope = otelContext.makeCurrent()

  override fun restoreThreadContext(context: CoroutineContext, oldState: Scope) {
    oldState.close()
  }
}
