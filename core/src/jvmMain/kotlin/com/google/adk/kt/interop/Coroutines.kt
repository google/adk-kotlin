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
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.future.future
import kotlinx.coroutines.runBlocking

/**
 * Entry points for calling the suspending and [Flow]-returning parts of the ADK from Java.
 *
 * A suspend function compiles to a method with a trailing `Continuation` parameter, so Java passes
 * the call as a lambda that forwards it. Kotlin default arguments are not visible to Java, so every
 * parameter must be supplied:
 * ```java
 * Session session = Coroutines.await(c -> sessionService.createSession(key, null, c));
 * List<Event> events = Coroutines.collect(runner.runAsync(user, id, null, message, null, null));
 * ```
 *
 * Kotlin callers should use the suspending APIs directly instead.
 */
object Coroutines {

  /**
   * Runs [block] and blocks the calling thread until it completes.
   *
   * Do not call this from a thread the coroutine itself needs, or it will deadlock.
   */
  @CheckReturnValue @JvmStatic fun <T> await(block: suspend () -> T): T = runBlocking { block() }

  /**
   * Runs [block] on [scope] and returns a future. Cancelling the future cancels the coroutine, and
   * cancelling [scope] cancels work already in flight.
   */
  @CheckReturnValue
  @JvmStatic
  fun <T> async(scope: CoroutineScope, block: suspend () -> T): CompletableFuture<T> =
    scope.future {
      block()
    }

  /** Collects every element of [flow], blocking until it completes. */
  @CheckReturnValue
  @JvmStatic
  fun <T> collect(flow: Flow<T>): List<T> = runBlocking { flow.toList() }

  /** Passes each element of [flow] to [action] as it arrives, blocking until [flow] completes. */
  @JvmStatic
  fun <T> forEach(flow: Flow<T>, action: Consumer<T>) {
    runBlocking { flow.collect { action.accept(it) } }
  }
}
