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
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.Part
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Bounds a collect whose failure mode is a hang. */
private val GUARD_TIMEOUT = 10.seconds

/** Covers ordering, the close sentinel, and the unbounded buffer. */
class LiveRequestQueueTest {

  @Test
  fun requests_deliversInSendOrder() = runBlocking {
    val queue = LiveRequestQueue()

    queue.sendContent(userMessage("one"))
    queue.sendAudio(Blob(mimeType = "audio/pcm;rate=16000", data = byteArrayOf(1)))
    queue.sendActivityEnd()
    queue.close()

    val requests = queue.requests.toList()

    assertEquals(4, requests.size)
    val first = assertIs<ContentInput>(requests[0].input)
    assertEquals("one", first.content.parts.single().text)
    assertFalse(first.partial, "sendContent defaults to a complete turn")
    assertIs<RealtimeInput.Audio>(requests[1].input)
    assertEquals(RealtimeInput.ActivityEnd, requests[2].input)
    assertTrue(requests[3].close)
  }

  @Test
  fun use_blockCompletes_closesTheQueue() = runBlocking {
    val queue = LiveRequestQueue()

    queue.use { it.sendContent(userMessage("inside use")) }

    val requests = withTimeout(GUARD_TIMEOUT) { queue.requests.toList() }

    assertEquals(
      "inside use",
      assertIs<ContentInput>(requests.first().input).content.parts.single().text,
    )
    assertTrue(requests.last().close)
  }

  @Test
  fun close_completesTheRequestFlow() = runBlocking {
    val queue = LiveRequestQueue()
    queue.close()

    // toList() returning at all is the assertion: an uncompleted flow would hang here.
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
    queue.sendContent(userMessage("too late"))

    val requests = queue.requests.toList()

    assertEquals(1, requests.size)
    assertTrue(requests.single().close)
  }

  @Test
  fun senders_calledOutsideACoroutine_bufferEveryChunkWithoutDropping() {
    // Non-suspension is compiler-enforced here; this test pins the unbounded buffer instead.
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
    val queue = LiveRequestQueue()

    queue.send(LiveRequest(close = true, stateDelta = mapOf("key" to "value")))

    val requests = queue.requests.toList()

    assertEquals(2, requests.size)
    assertEquals(mapOf("key" to "value"), requests[0].stateDelta)
    assertFalse(requests[0].close, "the forwarded payload must not carry the close flag")
    assertTrue(requests[1].close)
  }

  @Test
  fun send_closeRequest_closesTheQueueRatherThanEnqueuingASecondSentinel() = runBlocking {
    val queue = LiveRequestQueue()

    queue.send(LiveRequest(close = true))
    queue.send(LiveRequest(ContentInput(userMessage("after the close"))))

    val requests = queue.requests.toList()

    assertEquals(1, requests.size)
    assertTrue(requests.single().close)
  }

  @Test
  fun sendContent_functionCallContent_throwsAtTheCallSiteAndEnqueuesNothing() = runBlocking {
    val queue = LiveRequestQueue()
    val functionCall = userMessage(Part(functionCall = FunctionCall(name = "doIt")))

    assertFailsWith<IllegalArgumentException> { queue.sendContent(functionCall) }
    queue.close()

    assertTrue(withTimeout(GUARD_TIMEOUT) { queue.requests.toList() }.single().close)
  }

  @Test
  fun sendContent_partial_marksTheTurnIncomplete() = runBlocking {
    val queue = LiveRequestQueue()

    queue.sendContent(userMessage("half a thought"), partial = true)
    queue.close()

    val request = queue.requests.toList().first()

    assertTrue(assertIs<ContentInput>(request.input).partial)
  }

  @Test
  fun sendRealtime_carriesEachInputKind() = runBlocking {
    val queue = LiveRequestQueue()
    val blob = Blob(mimeType = "image/jpeg", data = byteArrayOf(7))

    queue.sendVideo(blob)
    queue.sendActivityStart()
    queue.sendAudioStreamEnd()
    queue.close()

    val inputs = queue.requests.toList().mapNotNull { it.input }

    assertEquals(
      listOf(RealtimeInput.Video(blob), RealtimeInput.ActivityStart, RealtimeInput.AudioStreamEnd),
      inputs,
    )
  }

  @Test
  fun send_stateDeltaWithoutContent_isDelivered() = runBlocking {
    val queue = LiveRequestQueue()

    queue.send(LiveRequest(stateDelta = mapOf("key" to "value")))
    queue.close()

    assertEquals(mapOf("key" to "value"), queue.requests.toList().first().stateDelta)
  }

  @Test
  fun requests_secondConcurrentCollector_isRejected() = runBlocking {
    val queue = LiveRequestQueue()
    val firstReceived = CompletableDeferred<Unit>()
    val first = launch { queue.requests.collect { firstReceived.complete(Unit) } }

    queue.sendContent(userMessage("ping"))
    firstReceived.await()

    val error =
      withTimeout(GUARD_TIMEOUT) {
        assertFailsWith<IllegalStateException> { queue.requests.collect {} }
      }
    assertContains(error.message.orEmpty(), "one collector at a time")

    first.cancel()
  }

  @Test
  fun requests_collectedAgainAfterCompletion_isAllowed() = runBlocking {
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("first pass"))
    queue.close()

    val firstPass = queue.requests.toList()
    assertEquals(
      "first pass",
      assertIs<ContentInput>(firstPass.first().input).content.parts.single().text,
    )

    assertTrue(queue.requests.toList().single().close)
  }

  @Test
  fun requests_reCollectedAfterCancellation_isAllowed() = runBlocking {
    val queue = LiveRequestQueue()
    val firstReceived = CompletableDeferred<Unit>()
    val first = launch { queue.requests.collect { firstReceived.complete(Unit) } }

    queue.sendContent(userMessage("before cancelling"))
    firstReceived.await()
    first.cancelAndJoin()

    queue.sendContent(userMessage("after cancelling"))
    queue.close()

    val second = queue.requests.toList()
    assertTrue(
      second.any {
        (it.input as? ContentInput)?.content?.parts?.single()?.text == "after cancelling"
      }
    )
    assertEquals(1, second.count { it.close }, "exactly one close sentinel")
    assertTrue(second.last().close)
  }
}
