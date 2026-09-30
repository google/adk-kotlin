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
import com.google.adk.kt.annotations.ExperimentalLiveApi
import com.google.adk.kt.models.CONCURRENT_COLLECTION_MESSAGE
import com.google.adk.kt.models.LiveConnection
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.RealtimeInput
import com.google.adk.kt.models.singleCollectorFlow
import com.google.adk.kt.types.Content
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.future.asDeferred
import kotlinx.coroutines.future.await
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.sync.Mutex
import org.reactivestreams.Publisher

/**
 * Java-friendly base for implementing a [LiveConnection]: override the `...Async` methods, which
 * return a [CompletableFuture], and [receiveJava], which returns a [Publisher] of one model turn.
 * The base maps these to [LiveConnection]'s calls and also enforces two of its rules, one [receive]
 * collector at a time and a [closeSession] that is safe to call again; apply its content rules
 * yourself. Return a new future from each call, because the base cancels a send's future when its
 * caller is cancelled, and a new publisher from each [receiveJava] call; return each promptly, with
 * any blocking work inside it, and fail a future rather than cancel it.
 */
@AdkJavaInteropApi
@ExperimentalLiveApi
abstract class BaseFutureLiveConnection : LiveConnection {

  // receive()'s one-collector guard, as in SingleCollectorLiveConnection, which is internal.
  private val collecting = Mutex()
  // closeSessionAsync() runs once; a repeated closeSession() or close() waits for it to finish.
  private val closeStarted = AtomicBoolean()
  private val closing = CompletableDeferred<Deferred<Void?>>()

  final override suspend fun sendHistory(history: List<Content>) {
    sendHistoryAsync(history).await()
  }

  final override suspend fun sendContent(content: Content, partial: Boolean) {
    sendContentAsync(content, partial).await()
  }

  final override suspend fun sendRealtime(input: RealtimeInput) {
    sendRealtimeAsync(input).await()
  }

  // Requests one response at a time, so a collection that stops early loses at most one.
  final override fun receive(): Flow<LlmResponse> =
    singleCollectorFlow(collecting, CONCURRENT_COLLECTION_MESSAGE) {
      receiveJava().asFlow().buffer(Channel.RENDEZVOUS)
    }

  // Deferred.await() doesn't cancel what it waits for; only the first caller sees a failure.
  final override suspend fun closeSession() {
    if (!closeStarted.compareAndSet(false, true)) {
      val hook = closing.await()
      // join() checks for cancellation even when the close is done; a finished close returns.
      if (!hook.isCompleted) hook.join()
      return
    }
    val hook =
      try {
        closeSessionAsync().asDeferred()
      } catch (e: Throwable) {
        CompletableDeferred<Void?>().apply { completeExceptionally(e) }
      }
    closing.complete(hook)
    hook.await()
  }

  /**
   * Blocks until the close finishes, so never call it from a thread that the future from
   * [closeSessionAsync] needs in order to complete. It is final, so a subclass cannot swap the
   * graceful close for an abrupt one: put teardown in [closeSessionAsync].
   */
  final override fun close() {
    super.close()
  }

  /**
   * Sends [history] as [LiveConnection.sendHistory] describes: drop audio parts, and complete the
   * turn only if the last content is from the user.
   */
  protected abstract fun sendHistoryAsync(history: List<Content>): CompletableFuture<Void?>

  /**
   * Sends [content] as [LiveConnection.sendContent] describes: reject content that breaks
   * `ContentInput`'s rules with [IllegalArgumentException]; function responses answer the model's
   * calls, and other content completes the turn unless [partial] is true.
   */
  protected abstract fun sendContentAsync(
    content: Content,
    partial: Boolean,
  ): CompletableFuture<Void?>

  /** Sends one chunk of media, an activity signal, or the end of the audio stream. */
  protected abstract fun sendRealtimeAsync(input: RealtimeInput): CompletableFuture<Void?>

  /**
   * Returns a publisher of the current model turn's remaining responses that completes at the end
   * of the turn; called once per collection of [receive], never concurrently. Honor `request(n)`,
   * continue after the last response delivered rather than replaying, and hold responses that
   * arrive while no publisher is subscribed. Complete a publisher without responses only once the
   * connection has ended, because that is how the caller learns of it, and fail it instead if the
   * connection dropped.
   */
  protected abstract fun receiveJava(): Publisher<LlmResponse>

  /**
   * Closes the connection gracefully, completing any open [receiveJava] publisher; called at most
   * once, possibly while a send is in flight or a turn is being collected. The base never cancels
   * the returned future and [close] waits for it without a timeout, so complete it within a bounded
   * time even if the peer never answers.
   */
  protected abstract fun closeSessionAsync(): CompletableFuture<Void?>
}
