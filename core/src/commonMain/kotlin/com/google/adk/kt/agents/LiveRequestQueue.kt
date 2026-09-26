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

package com.google.adk.kt.agents

import com.google.adk.kt.models.ContentInput
import com.google.adk.kt.models.RealtimeInput
import com.google.adk.kt.singleCollectorFlow
import com.google.adk.kt.types.Content
import kotlin.jvm.JvmOverloads
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex

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
class LiveRequestQueue : AutoCloseable {
  private val channel = Channel<LiveRequest>(Channel.UNLIMITED)
  private val collecting = Mutex()

  /**
   * The queued requests in send order, ending with a final `close = true` request once [close] is
   * called.
   *
   * Only one collector is allowed at a time, because requests come from a channel rather than a
   * broadcast, and collecting while another collection is active throws [IllegalStateException].
   * Collecting again after one ends is allowed, but a cancelled collection may already have taken
   * requests, so each is delivered at most once.
   */
  internal val requests: Flow<LiveRequest> =
    singleCollectorFlow(collecting, CONCURRENT_COLLECTION_MESSAGE) {
      flow {
        emitAll(channel.receiveAsFlow())
        // Appended after the channel drains, so a send racing the close cannot land behind it.
        emit(LiveRequest(close = true))
      }
    }

  /**
   * Sends [request]; a [LiveRequest.close] request delivers its [LiveRequest.stateDelta], then
   * closes the queue.
   *
   * Prefer [sendContent], [sendRealtime] or [close].
   */
  fun send(request: LiveRequest) {
    // Forward the state change before closing so consumers stopping on `close` do not miss it.
    if (request.close) {
      if (request.stateDelta.isNotEmpty()) {
        val unused = channel.trySend(request.copy(close = false))
      }
      close()
      return
    }
    // trySend only fails after close, where dropping is the documented behaviour.
    val unused = channel.trySend(request)
  }

  /**
   * Sends content in turn-by-turn mode.
   *
   * Throws [IllegalArgumentException] if [content] breaks a [ContentInput] rule.
   *
   * @param partial Whether this update leaves the model's current turn open.
   */
  @JvmOverloads
  fun sendContent(content: Content, partial: Boolean = false) {
    send(LiveRequest(ContentInput(content, partial)))
  }

  /**
   * Sends a [RealtimeInput.Audio] chunk, a [RealtimeInput.Video] frame, or an activity signal in
   * realtime mode.
   */
  fun sendRealtime(input: RealtimeInput) {
    send(LiveRequest(input))
  }

  /** Marks the start of user activity, when automatic activity detection is disabled. */
  fun sendActivityStart() {
    sendRealtime(RealtimeInput.ActivityStart)
  }

  /** Marks the end of user activity, when automatic activity detection is disabled. */
  fun sendActivityEnd() {
    sendRealtime(RealtimeInput.ActivityEnd)
  }

  /** Marks the end of the audio stream, forcing the model to flush what it has. */
  fun sendAudioStreamEnd() {
    sendRealtime(RealtimeInput.AudioStreamEnd)
  }

  /**
   * Closes the queue; calling it again is harmless.
   *
   * Close is ordered: requests already sent are still delivered, followed by one final request with
   * [LiveRequest.close] set. Anything sent after this is silently dropped, and the queue cannot be
   * reopened.
   */
  override fun close() {
    // Returns false on a second close; the sentinel is appended by [requests], not here.
    val unused = channel.close()
  }
}

private const val CONCURRENT_COLLECTION_MESSAGE =
  "LiveRequestQueue.requests allows one collector at a time, because it is a channel of " +
    "requests, not a broadcast: a second concurrent collector would take an arbitrary subset " +
    "of them from the first. Collecting again after one completes is allowed."
