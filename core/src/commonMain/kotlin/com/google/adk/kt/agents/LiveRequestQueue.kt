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

import com.google.adk.kt.models.RealtimeInput
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.Content
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * The input side of a live conversation: everything the user sends reaches the agent through one of
 * these, from any thread and without suspending, so audio can be pushed from a microphone callback
 * or a UI handler with no coroutine in sight.
 *
 * The cost is an unbounded queue: a producer faster than the agent grows it without limit, an
 * entry per audio chunk spoken.
 *
 * ADK Python makes the same trade, with an `asyncio.Queue` that sets no maximum, so bound it only
 * with a measured consumer to justify the policy.
 */
class LiveRequestQueue {
  private val channel = Channel<LiveRequest>(Channel.UNLIMITED)

  /**
   * The queued requests, in the order they were sent, ending with the final request emitted after
   * [close], after which the flow completes.
   *
   * **One collector at a time.** It is a channel, not a broadcast, so two concurrent collectors
   * split the requests and each receives its own close sentinel. Unenforced, unlike
   * [LiveConnection.receive], because collecting again after one finishes is legitimate.
   */
  val requests: Flow<LiveRequest> =
    channel.receiveAsFlow().onCompletion { cause ->
      // Appended after the channel drains, so a send racing the close cannot land behind it.
      if (cause == null) emit(LiveRequest(close = true))
    }

  /** Sends [request] as-is. Prefer the specific senders below. */
  fun send(request: LiveRequest) {
    // Kotlin alone splits this: Java and Python each send one item carrying payload and close.
    if (request.close) {
      if (request.content != null || request.realtimeInput != null || request.stateDelta != null) {
        val unused = channel.trySend(request.copy(close = false))
      }
      close()
      return
    }
    // Fails only once closed -- see [close]; the drop is documented, so the result is discarded.
    val unused = channel.trySend(request)
  }

  /**
   * Sends content in turn-by-turn mode.
   *
   * @param partial Whether this update leaves the model's current turn open.
   */
  fun sendContent(content: Content, partial: Boolean = false) {
    send(LiveRequest(content = content, partial = partial))
  }

  /** Sends media or an activity signal in realtime mode. */
  fun sendRealtime(input: RealtimeInput) {
    send(LiveRequest(realtimeInput = input))
  }

  /** Sends a chunk of audio, conventionally 16-bit mono PCM at 16kHz. */
  fun sendAudio(blob: Blob) {
    sendRealtime(RealtimeInput.Audio(blob))
  }

  /** Sends a frame of video, conventionally a JPEG image. */
  fun sendVideo(blob: Blob) {
    sendRealtime(RealtimeInput.Video(blob))
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
   * Closes the queue, permanently; calling it again is harmless.
   *
   * Close is ordered: requests already sent are still delivered, then one final request with
   * [LiveRequest.close] set, after which [requests] completes.
   *
   * **Anything sent after this is dropped, silently**, and a closed queue cannot be reopened, so a
   * live run writes a tool answer to the connection directly rather than routing it through here.
   */
  fun close() {
    // Returns false on a second close; the sentinel is appended by [requests], not here.
    val unused = channel.close()
  }
}
