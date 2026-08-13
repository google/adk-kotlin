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

import com.google.errorprone.annotations.CheckReturnValue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.asPublisher
import org.reactivestreams.Publisher

/**
 * Converts between a [Flow] and a Reactive Streams [Publisher], the common currency of RxJava,
 * Project Reactor, and `java.util.concurrent.Flow`.
 *
 * ADK depends only on `org.reactivestreams`; the final hop is one line in caller code:
 * ```java
 * Flowable<Event> rx   = Flowable.fromPublisher(Reactive.asPublisher(flow)); // RxJava 3
 * Flux<Event>     flux = Flux.from(Reactive.asPublisher(flow));              // Reactor
 * Flow.Publisher<Event> jdk =
 *     FlowAdapters.toFlowPublisher(Reactive.asPublisher(flow));              // JDK
 * ```
 *
 * For a single value, use [Coroutines.async] and `Mono.fromFuture(...)` instead.
 */
object Reactive {

  /** Exposes [flow] as a [Publisher]. Elements must be non-null; Reactive Streams forbids null. */
  @CheckReturnValue
  @JvmStatic
  fun <T : Any> asPublisher(flow: Flow<T>): Publisher<T> = flow.asPublisher()

  /** Adapts [publisher] to a [Flow], for feeding an Rx or Reactor source into the ADK. */
  @CheckReturnValue
  @JvmStatic
  fun <T : Any> asFlow(publisher: Publisher<T>): Flow<T> = publisher.asFlow()
}
