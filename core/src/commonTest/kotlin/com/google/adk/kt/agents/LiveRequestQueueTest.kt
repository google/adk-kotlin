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
import com.google.adk.kt.types.Part
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/** Covers ordering, the close sentinel, and the non-suspending contract of the senders. */
class LiveRequestQueueTest {

  private fun userContent(text: String) = Content(role = "user", parts = listOf(Part(text = text)))

  @Test
  fun requests_areDeliveredInSendOrder() = runBlocking {
    val queue = LiveRequestQueue()

    queue.sendContent(userContent("one"))
    queue.sendAudio(Blob(mimeType = "audio/pcm;rate=16000", data = byteArrayOf(1)))
    queue.sendActivityEnd()
    queue.close()

    val requests = queue.requests.toList()

    assertEquals(4, requests.size)
    assertEquals("one", requests[0].content?.parts?.single()?.text)
    assertTrue(requests[1].realtimeInput is RealtimeInput.Audio)
    assertEquals(RealtimeInput.ActivityEnd, requests[2].realtimeInput)
    assertTrue(requests[3].close)
  }

  @Test
  fun close_deliversRequestsSentBeforeIt() = runBlocking {
    // Close is ordered: a caller that sends then closes must not lose what it just sent.
    val queue = LiveRequestQueue()

    queue.sendContent(userContent("do not drop me"))
    queue.close()

    val requests = queue.requests.toList()

    assertEquals("do not drop me", requests.first().content?.parts?.single()?.text)
    assertTrue(requests.last().close)
  }

  @Test
  fun close_completesTheRequestFlow() = runBlocking {
    val queue = LiveRequestQueue()
    queue.close()

    // toList returning at all is the assertion: an uncompleted flow would hang here.
    assertEquals(1, queue.requests.toList().size)
  }

  @Test
  fun close_calledTwice_isHarmless() = runBlocking {
    val queue = LiveRequestQueue()

    queue.close()
    queue.close()

    assertEquals(1, queue.requests.toList().count { it.close })
  }

  @Test
  fun send_afterClose_isDropped() = runBlocking {
    val queue = LiveRequestQueue()

    queue.close()
    queue.sendContent(userContent("too late"))

    val requests = queue.requests.toList()

    assertEquals(1, requests.size)
    assertTrue(requests.single().close)
  }

  @Test
  fun senders_calledOutsideACoroutine_bufferEveryChunkWithoutDropping() {
    // Non-suspension is compiler-enforced here; what this pins is the unbounded buffer.
    val queue = LiveRequestQueue()

    repeat(1_000) {
      queue.sendAudio(Blob(mimeType = "audio/pcm;rate=16000", data = byteArrayOf(1)))
    }
    queue.close()

    val requests = runBlocking { queue.requests.toList() }

    assertEquals(1_001, requests.size, "1000 chunks and the close sentinel, none dropped")
  }

  @Test
  fun send_closeRequestCarryingAPayload_deliversThePayloadBeforeClosing() = runBlocking {
    // A payload must survive a close request; the split is what makes that work.
    val queue = LiveRequestQueue()

    queue.send(LiveRequest(close = true, stateDelta = mapOf("key" to "value")))

    val requests = queue.requests.toList()

    assertEquals(2, requests.size)
    assertEquals(mapOf("key" to "value"), requests[0].stateDelta)
    assertTrue(requests[1].close)
  }

  @Test
  fun send_closeRequestCarryingContent_deliversTheContentBeforeClosing() = runBlocking {
    // The whole payload survives, not just `stateDelta`.
    val queue = LiveRequestQueue()

    queue.send(LiveRequest(close = true, content = userContent("last words")))

    val requests = queue.requests.toList()

    assertEquals(2, requests.size)
    assertEquals("last words", requests[0].content?.parts?.single()?.text)
    assertTrue(requests[1].close, "the payload must arrive before the close sentinel")
  }

  @Test
  fun send_closeRequestCarryingRealtimeInput_deliversTheInputBeforeClosing() = runBlocking {
    // The same rule for the realtime payload.
    val queue = LiveRequestQueue()
    val blob = Blob(mimeType = "audio/pcm;rate=16000", data = byteArrayOf(7))

    queue.send(LiveRequest(close = true, realtimeInput = RealtimeInput.Audio(blob)))

    val requests = queue.requests.toList()

    assertEquals(2, requests.size)
    assertEquals(RealtimeInput.Audio(blob), requests[0].realtimeInput)
    assertTrue(requests[1].close, "the payload must arrive before the close sentinel")
  }

  @Test
  fun send_closeRequest_closesTheQueueRatherThanEnqueuingASecondSentinel() = runBlocking {
    // `LiveRequest.close` is public, so `send` must treat one as a close rather than pass it on.
    val queue = LiveRequestQueue()

    queue.send(LiveRequest(close = true))
    queue.send(LiveRequest(content = userContent("after the close")))

    val requests = queue.requests.toList()

    assertEquals(1, requests.size)
    assertTrue(requests.single().close)
  }

  @Test
  fun sendContent_partial_marksTheTurnIncomplete() = runBlocking {
    val queue = LiveRequestQueue()

    queue.sendContent(userContent("half a thought"), partial = true)

    val request = queue.requests.first()

    assertTrue(request.partial)
  }

  @Test
  fun sendRealtime_carriesEachInputKind() = runBlocking {
    val queue = LiveRequestQueue()
    val blob = Blob(mimeType = "image/jpeg", data = byteArrayOf(7))

    queue.sendVideo(blob)
    queue.sendActivityStart()
    queue.sendAudioStreamEnd()
    queue.close()

    val inputs = queue.requests.toList().mapNotNull { it.realtimeInput }

    assertEquals(
      listOf(RealtimeInput.Video(blob), RealtimeInput.ActivityStart, RealtimeInput.AudioStreamEnd),
      inputs,
    )
  }

  @Test
  fun send_stateDeltaWithoutContent_isDelivered() = runBlocking {
    // State changes have to reach the session even on a request carrying nothing else.
    val queue = LiveRequestQueue()

    queue.send(LiveRequest(stateDelta = mapOf("key" to "value")))
    queue.close()

    assertEquals(mapOf("key" to "value"), queue.requests.toList().first().stateDelta)
  }
}
