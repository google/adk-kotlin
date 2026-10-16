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
import com.google.adk.kt.runners.EarlyStopOnCancellation
import com.google.errorprone.annotations.CheckReturnValue
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow as asFlowFromIterable
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.future.future
import kotlinx.coroutines.job
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.asPublisher
import kotlinx.coroutines.runBlocking
import org.reactivestreams.Publisher

/**
 * Entry points for calling the suspending and [Flow]-returning parts of the ADK from Java, and for
 * bridging a [Flow] to Reactive Streams.
 *
 * A suspend function compiles to a method with a trailing `Continuation` parameter, so Java passes
 * the call as a lambda that forwards it. Kotlin default arguments are not visible to Java, so every
 * parameter must be supplied:
 * ```java
 * Session session = AsyncJavaHelpers.await(c -> sessionService.createSession(key, null, c));
 * List<Event> events =
 *     AsyncJavaHelpers.collect(runner.runAsync(user, id, null, message, null, null));
 * ```
 *
 * A [Flow] also converts to a Reactive Streams [Publisher], the common currency of RxJava, Project
 * Reactor, and `java.util.concurrent.Flow`; the final hop is one line in caller code:
 * ```java
 * Flowable<Event> rx   = Flowable.fromPublisher(AsyncJavaHelpers.asPublisher(flow)); // RxJava 3
 * Flux<Event>     flux = Flux.from(AsyncJavaHelpers.asPublisher(flow));              // Reactor
 * Flow.Publisher<Event> jdk =
 *     FlowAdapters.toFlowPublisher(AsyncJavaHelpers.asPublisher(flow));              // JDK
 * ```
 *
 * Kotlin callers should use the suspending APIs directly instead.
 */
object AsyncJavaHelpers {

  /**
   * Runs [block] and blocks the calling thread until it completes.
   *
   * Do not call this from a thread the coroutine itself needs, or it will deadlock.
   */
  @AdkJavaInteropApi
  @CheckReturnValue
  @JvmStatic
  fun <T> await(block: suspend () -> T): T = runBlocking { block() }

  /**
   * Runs [block] on [scope] and returns a future. Cancelling the future cancels the coroutine, and
   * cancelling [scope] cancels work already in flight.
   */
  @AdkJavaInteropApi
  @CheckReturnValue
  @JvmStatic
  fun <T> async(scope: CoroutineScope, block: suspend () -> T): CompletableFuture<T> =
    scope.future {
      block()
    }

  /** Collects every element of [flow], blocking until it completes. */
  @AdkJavaInteropApi
  @CheckReturnValue
  @JvmStatic
  fun <T> collect(flow: Flow<T>): List<T> = runBlocking { flow.toList() }

  /** Passes each element of [flow] to [action] as it arrives, blocking until [flow] completes. */
  @AdkJavaInteropApi
  @JvmStatic
  fun <T> forEach(flow: Flow<T>, action: Consumer<T>) {
    runBlocking { flow.collect { action.accept(it) } }
  }

  /** Passes each element of [publisher] to [action], blocking until [publisher] completes. */
  @AdkJavaInteropApi
  @JvmStatic
  fun <T : Any> forEach(publisher: Publisher<T>, action: Consumer<T>) {
    forEach(publisher.asFlow(), action)
  }

  /**
   * Exposes [flow] as a [Publisher] of non-null elements, since Reactive Streams forbids null; for
   * a single value, use [async] and `Mono.fromFuture(...)` instead.
   *
   * Canceling the subscription to a runner's events is an early stop, so afterRun plugin callbacks
   * still run, as with [PublisherRunner.runAsync].
   */
  @AdkJavaInteropApi
  @CheckReturnValue
  @JvmStatic
  fun <T : Any> asPublisher(flow: Flow<T>): Publisher<T> = flow.asEarlyStopPublisher()

  /** Adapts [publisher] to a [Flow], for feeding an Rx or Reactor source into the ADK. */
  @AdkJavaInteropApi
  @CheckReturnValue
  @JvmStatic
  fun <T : Any> asFlow(publisher: Publisher<T>): Flow<T> = publisher.asFlow()

  /**
   * Exposes a fixed [items] sequence as a [Publisher] -- the Java-friendly way for a synchronous
   * model or agent (extending a `BasePublisher*` base) to return already-computed values. Elements
   * must be non-null; Reactive Streams forbids null.
   */
  @AdkJavaInteropApi
  @CheckReturnValue
  @JvmStatic
  fun <T : Any> publisherOf(items: Iterable<T>): Publisher<T> =
    items.asFlowFromIterable().asPublisher()
}

/** Exposes [this] as a [Publisher] whose canceled subscription a runner treats as an early stop. */
internal fun <T : Any> Flow<T>.asEarlyStopPublisher(): Publisher<T> {
  val upstream = this
  // flowOn without a dispatcher keeps the run in the subscription's coroutine and job.
  return flow { emitAll(upstream.flowOn(EarlyStopOnCancellation(currentCoroutineContext().job))) }
    .asPublisher()
}
