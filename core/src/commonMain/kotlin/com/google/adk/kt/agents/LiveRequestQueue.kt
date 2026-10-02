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

@file:OptIn(ExperimentalLiveApi::class, ExperimentalAtomicApi::class)

package com.google.adk.kt.agents

import com.google.adk.kt.annotations.ExperimentalLiveApi
import com.google.adk.kt.models.ContentInput
import com.google.adk.kt.models.RealtimeInput
import com.google.adk.kt.types.Content
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.jvm.JvmOverloads
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The input side of a live conversation: everything the user sends reaches the agent through one of
 * these, from any thread and without suspending, so audio can be pushed from a microphone callback
 * or a UI handler without a coroutine.
 *
 * The cost is an unbounded queue: a producer faster than the agent grows it without limit, an entry
 * per audio chunk spoken.
 *
 * ADK Python makes the same trade, with an `asyncio.Queue` that sets no maximum.
 */
class LiveRequestQueue @ExperimentalLiveApi constructor() : AutoCloseable {
  // Only the constructor and senders need the opt-in, so a stable type can hold a queue without it.

  /**
   * Holds a request the channel already handed to a [receiveRequest] call that was then cancelled,
   * so the next call returns it instead of losing it. That happens, for example, when a reconnect
   * or a transfer to another agent cancels the reader and starts a new one on the same queue. One
   * slot is enough because [receiving] lets only one call run at a time.
   */
  private val redelivery = AtomicReference<LiveRequest?>(null)
  private val channel =
    Channel<LiveRequest>(Channel.UNLIMITED, onUndeliveredElement = { redelivery.store(it) })

  /**
   * Lets one [receiveRequest] call run at a time, so a cancelled call's request is already in
   * [redelivery] before the next call looks there.
   */
  private val receiving = Mutex()

  /**
   * Returns the next request in send order, or null after [close] once every request sent before it
   * has been returned. A caller can cancel a call and start the next one without waiting for it to
   * finish: calls run one at a time, and a request the channel had already handed to the cancelled
   * call is returned by the next one, so none is lost or reordered. A returned request never has
   * [LiveRequest.close] set, and one whose [LiveRequest.input] is null carries only a
   * [LiveRequest.stateDelta], which may be empty.
   */
  internal suspend fun receiveRequest(): LiveRequest? = receiving.withLock {
    redelivery.exchange(null) ?: channel.receiveCatching().getOrNull()
  }

  /**
   * Sends [request]; a [LiveRequest.close] request delivers its [LiveRequest.stateDelta], then
   * closes the queue.
   *
   * Copies [LiveRequest.stateDelta], so later changes to the caller's map don't reach the queued
   * request.
   *
   * Use this one to attach a [LiveRequest.stateDelta]; the other senders send input without one.
   */
  @ExperimentalLiveApi
  fun send(request: LiveRequest) {
    val queued = request.copy(stateDelta = request.stateDelta.toMap())
    // Forward the state change before closing so it is delivered like any other request.
    if (queued.close) {
      if (queued.stateDelta.isNotEmpty()) {
        val unused = channel.trySend(queued.copy(close = false))
      }
      close()
      return
    }
    // trySend only fails after close, where dropping is the documented behaviour.
    val unused = channel.trySend(queued)
  }

  /**
   * Sends content in turn-by-turn mode.
   *
   * Throws [IllegalArgumentException] if [content] breaks a [ContentInput] rule.
   *
   * @param partial Whether this update leaves the model's current turn open.
   */
  @JvmOverloads
  @ExperimentalLiveApi
  fun sendContent(content: Content, partial: Boolean = false) {
    send(LiveRequest(ContentInput(content, partial)))
  }

  /**
   * Sends a [RealtimeInput.Audio] chunk, a [RealtimeInput.Video] frame, or an activity signal in
   * realtime mode.
   */
  @ExperimentalLiveApi
  fun sendRealtime(input: RealtimeInput) {
    send(LiveRequest(input))
  }

  /** Marks the start of user activity, when automatic activity detection is disabled. */
  @ExperimentalLiveApi
  fun sendActivityStart() {
    sendRealtime(RealtimeInput.ActivityStart)
  }

  /** Marks the end of user activity, when automatic activity detection is disabled. */
  @ExperimentalLiveApi
  fun sendActivityEnd() {
    sendRealtime(RealtimeInput.ActivityEnd)
  }

  /** Marks the end of the audio stream, forcing the model to flush what it has. */
  @ExperimentalLiveApi
  fun sendAudioStreamEnd() {
    sendRealtime(RealtimeInput.AudioStreamEnd)
  }

  /**
   * Closes the queue; calling it again is harmless.
   *
   * Close is ordered: requests sent before it are still delivered, and anything sent after it is
   * silently dropped. The queue cannot be reopened.
   */
  override fun close() {
    // Returns false on a second close.
    val unused = channel.close()
  }
}
