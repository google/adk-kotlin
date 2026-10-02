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

@file:OptIn(ExperimentalLiveApi::class)

package com.google.adk.kt.agents

import com.google.adk.kt.annotations.ExperimentalLiveApi
import com.google.adk.kt.models.ContentInput
import com.google.adk.kt.models.RealtimeInput
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.Part
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Bounds a receive whose failure mode is a hang. */
private val GUARD_TIMEOUT = 10.seconds

/** Covers ordering, the end of the requests on close, and the unbounded buffer. */
class LiveRequestQueueTest {

  @Test
  fun receiveRequest_deliversInSendOrder() = runBlocking {
    val queue = LiveRequestQueue()

    queue.sendContent(userMessage("one"))
    queue.sendRealtime(
      RealtimeInput.Audio(Blob(mimeType = "audio/pcm;rate=16000", data = byteArrayOf(1)))
    )
    queue.sendActivityEnd()
    queue.close()

    val requests = queue.receiveAll()

    assertEquals(3, requests.size)
    val first = assertIs<ContentInput>(requests[0].input)
    assertEquals("one", first.content.parts.single().text)
    assertFalse(first.partial, "sendContent defaults to a complete turn")
    assertIs<RealtimeInput.Audio>(requests[1].input)
    assertEquals(RealtimeInput.ActivityEnd, requests[2].input)
    assertTrue(requests.none { it.close })
  }

  @Test
  fun use_blockCompletes_closesTheQueue() = runBlocking {
    val queue = LiveRequestQueue()

    queue.use { it.sendContent(userMessage("inside use")) }

    val requests = withTimeout(GUARD_TIMEOUT) { queue.receiveAll() }

    assertEquals(
      "inside use",
      assertIs<ContentInput>(requests.single().input).content.parts.single().text,
    )
  }

  @Test
  fun close_makesReceiveRequestReturnNull() = runBlocking {
    val queue = LiveRequestQueue()
    queue.close()

    // receiveAll() returning at all is the assertion: an unclosed channel would hang here.
    assertEquals(0, queue.receiveAll().size)
  }

  @Test
  fun close_calledTwice_isHarmless() = runBlocking {
    val queue = LiveRequestQueue()

    queue.close()
    queue.close()

    assertEquals(0, queue.receiveAll().size)
  }

  @Test
  fun send_afterClose_isDropped() = runBlocking {
    val queue = LiveRequestQueue()

    queue.close()
    queue.sendContent(userMessage("too late"))

    assertTrue(queue.receiveAll().isEmpty())
  }

  @Test
  fun senders_calledOutsideACoroutine_bufferEveryChunkWithoutDropping() {
    // Non-suspension is compiler-enforced here; this test pins the unbounded buffer instead.
    val queue = LiveRequestQueue()

    repeat(1_000) {
      queue.sendRealtime(
        RealtimeInput.Audio(Blob(mimeType = "audio/pcm;rate=16000", data = byteArrayOf(1)))
      )
    }
    queue.close()

    val requests = runBlocking { queue.receiveAll() }

    assertEquals(1_000, requests.size, "1000 chunks, none dropped")
  }

  @Test
  fun send_closeRequestCarryingAPayload_deliversThePayloadBeforeClosing() = runBlocking {
    val queue = LiveRequestQueue()

    queue.send(LiveRequest(close = true, stateDelta = mapOf("key" to "value")))

    val requests = queue.receiveAll()

    assertEquals(1, requests.size)
    assertEquals(mapOf("key" to "value"), requests[0].stateDelta)
    assertFalse(requests[0].close, "the forwarded payload must not carry the close flag")
  }

  @Test
  fun send_stateDeltaMutatedAfterSending_deliversTheMapAsSent() = runBlocking {
    val queue = LiveRequestQueue()
    val stateDelta = mutableMapOf<String, Any>("k" to "v1")

    queue.send(LiveRequest(stateDelta = stateDelta))
    stateDelta["k"] = "v2"
    queue.close()

    assertEquals(mapOf("k" to "v1"), queue.receiveAll().first().stateDelta)
  }

  @Test
  fun send_closeRequestWithStateDeltaMutatedAfterSending_forwardsTheMapAsSent() = runBlocking {
    val queue = LiveRequestQueue()
    val stateDelta = mutableMapOf<String, Any>("k" to "v1")

    queue.send(LiveRequest(close = true, stateDelta = stateDelta))
    stateDelta["k"] = "v2"

    val requests = queue.receiveAll()

    assertEquals(1, requests.size)
    assertEquals(mapOf("k" to "v1"), requests[0].stateDelta)
    assertFalse(requests[0].close, "the forwarded payload must not carry the close flag")
  }

  @Test
  fun send_closeRequest_closesTheQueueAndDropsLaterSends() = runBlocking {
    val queue = LiveRequestQueue()

    queue.send(LiveRequest(close = true))
    queue.send(LiveRequest(ContentInput(userMessage("after the close"))))

    assertTrue(queue.receiveAll().isEmpty())
  }

  @Test
  fun sendContent_functionCallContent_throwsAtTheCallSiteAndEnqueuesNothing() = runBlocking {
    val queue = LiveRequestQueue()
    val functionCall = userMessage(Part(functionCall = FunctionCall(name = "doIt")))

    assertFailsWith<IllegalArgumentException> { queue.sendContent(functionCall) }
    queue.close()

    assertTrue(withTimeout(GUARD_TIMEOUT) { queue.receiveAll() }.isEmpty())
  }

  @Test
  fun sendContent_partial_marksTheTurnIncomplete() = runBlocking {
    val queue = LiveRequestQueue()

    queue.sendContent(userMessage("half a thought"), partial = true)
    queue.close()

    val request = queue.receiveAll().first()

    assertTrue(assertIs<ContentInput>(request.input).partial)
  }

  @Test
  fun sendRealtime_carriesEachInputKind() = runBlocking {
    val queue = LiveRequestQueue()
    val blob = Blob(mimeType = "image/jpeg", data = byteArrayOf(7))

    queue.sendRealtime(RealtimeInput.Video(blob))
    queue.sendActivityStart()
    queue.sendAudioStreamEnd()
    queue.close()

    val inputs = queue.receiveAll().mapNotNull { it.input }

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

    assertEquals(mapOf("key" to "value"), queue.receiveAll().first().stateDelta)
  }

  @Test
  fun receiveRequest_calledAgainAfterTheEnd_returnsNull() = runBlocking {
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("first pass"))
    queue.close()

    val firstPass = queue.receiveAll()
    assertEquals(
      "first pass",
      assertIs<ContentInput>(firstPass.single().input).content.parts.single().text,
    )

    assertTrue(queue.receiveAll().isEmpty())
  }

  @Test
  fun receiveRequest_calledAgainAfterCancellation_returnsLaterRequests() = runBlocking {
    val queue = LiveRequestQueue()
    val firstReceived = CompletableDeferred<Unit>()
    val first = launch { while (queue.receiveRequest() != null) firstReceived.complete(Unit) }

    queue.sendContent(userMessage("before cancelling"))
    firstReceived.await()
    first.cancelAndJoin()

    queue.sendContent(userMessage("after cancelling"))
    queue.close()

    val second = queue.receiveAll()
    assertTrue(
      second.any {
        (it.input as? ContentInput)?.content?.parts?.single()?.text == "after cancelling"
      }
    )
    assertTrue(second.none { it.close }, "no close sentinel")
  }

  @Test
  fun receiveRequest_receiverCancelledAfterARequestWasHandedToIt_returnsItOnTheNextCall() =
    runBlocking {
      val queue = LiveRequestQueue()
      // Undispatched, so the receiver is already suspended on the empty queue when we send.
      val receiver =
        launch(start = CoroutineStart.UNDISPATCHED) {
          val unused = queue.receiveRequest()
        }

      queue.sendContent(userMessage("handed over"))
      // The receiver got the request but hasn't run yet; cancelling now calls onUndeliveredElement.
      receiver.cancelAndJoin()
      queue.close()

      assertTrue(receiver.isCancelled)
      val request = assertNotNull(queue.receiveRequest(), "the handed-over request was lost")
      assertEquals("handed over", assertIs<ContentInput>(request.input).content.parts.single().text)
      assertNull(queue.receiveRequest(), "delivered once, then the end")
    }

  // Below, the next receiver starts before the cancelled one has run and handed its request back.

  @Test
  fun receiveRequest_nextReceiverStartsBeforeTheCancelledOneRuns_keepsSendOrder() = runBlocking {
    val queue = LiveRequestQueue()
    val first =
      launch(start = CoroutineStart.UNDISPATCHED) {
        val unused = queue.receiveRequest()
      }
    queue.sendContent(userMessage("handed over"))
    first.cancel()
    queue.sendContent(userMessage("sent later"))

    val next = async(start = CoroutineStart.UNDISPATCHED) { queue.receiveRequest() }
    first.join()
    val received = listOf(next.await(), queue.receiveRequest())

    assertEquals(listOf("handed over", "sent later"), received.map { it?.text() })
  }

  @Test
  fun receiveRequest_nextReceiverStartsBeforeTheCancelledOneRunsAfterClose_returnsTheRequest() =
    runBlocking {
      val queue = LiveRequestQueue()
      val first =
        launch(start = CoroutineStart.UNDISPATCHED) {
          val unused = queue.receiveRequest()
        }
      queue.sendContent(userMessage("handed over"))
      first.cancel()
      queue.close()

      val next = async(start = CoroutineStart.UNDISPATCHED) { queue.receiveRequest() }

      val request = assertNotNull(next.await(), "the handed-over request was lost")
      assertEquals("handed over", request.text())
      assertNull(queue.receiveRequest(), "delivered once, then the end")
    }

  @Test
  fun receiveRequest_twoReceiversCancelledBeforeEitherRuns_returnsBothRequests() = runBlocking {
    val queue = LiveRequestQueue()
    val first =
      launch(start = CoroutineStart.UNDISPATCHED) {
        val unused = queue.receiveRequest()
      }
    queue.sendContent(userMessage("one"))
    first.cancel()
    val second =
      launch(start = CoroutineStart.UNDISPATCHED) {
        val unused = queue.receiveRequest()
      }
    queue.sendContent(userMessage("two"))
    second.cancel()
    joinAll(first, second)
    queue.close()

    assertEquals(listOf("one", "two"), queue.receiveAll().map { it.text() })
  }

  @Test
  fun send_emptyStateDeltaMutatedAfterSending_deliversItEmpty() = runBlocking {
    val queue = LiveRequestQueue()
    val stateDelta = mutableMapOf<String, Any>()

    queue.send(LiveRequest(stateDelta = stateDelta))
    stateDelta["k"] = "v"
    queue.close()

    assertEquals(emptyMap(), queue.receiveAll().single().stateDelta)
  }
}

private fun LiveRequest.text(): String? = (input as? ContentInput)?.content?.parts?.single()?.text

/** Receives until the queue ends, as the live flow's sender does. */
private suspend fun LiveRequestQueue.receiveAll(): List<LiveRequest> {
  val requests = mutableListOf<LiveRequest>()
  while (true) requests += receiveRequest() ?: break
  return requests
}
