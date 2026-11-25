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
import com.google.adk.kt.testing.testInvocationContext
import com.google.adk.kt.testing.userMessage
import com.google.common.truth.Truth.assertThat
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Covers the one-reader rule on a live run's [LiveRequestQueue]. */
class LiveRunTest {

  private fun LiveRequest?.text(): String? =
    assertIs<ContentInput>(assertNotNull(this).input).content.text()

  @Test
  fun receiveRequest_whileAnotherReaderIsActive_failsWithoutTakingARequest() = runBlocking {
    val queue = LiveRequestQueue()
    val liveRun = testInvocationContext().openLiveRun(queue)
    // Undispatched, so the first reader is already waiting on the empty queue.
    val first = async(start = CoroutineStart.UNDISPATCHED) { liveRun.receiveRequest("agent") }
    try {
      // Bounded, so a receive that waits instead of failing ends on a timeout, not a hang.
      val failure =
        withTimeout(10.seconds) {
          assertFailsWith<IllegalStateException> { liveRun.receiveRequest("agent") }
        }
      queue.sendContent(userMessage("one"))

      assertThat(failure).hasMessageThat().contains("already reading its LiveRequestQueue")
      assertThat(first.await().text()).isEqualTo("one")
    } finally {
      first.cancel()
    }
  }

  @Test
  fun receiveRequest_anotherRunReadsTheSameQueue_failsWithoutTakingARequest() = runBlocking {
    val queue = LiveRequestQueue()
    val first = testInvocationContext().openLiveRun(queue)
    val second = testInvocationContext().openLiveRun(queue)
    val reading = async(start = CoroutineStart.UNDISPATCHED) { first.receiveRequest("first") }
    try {
      val failure =
        withTimeout(10.seconds) {
          assertFailsWith<IllegalStateException> { second.receiveRequest("second") }
        }
      queue.sendContent(userMessage("one"))

      assertThat(failure).hasMessageThat().contains("Live agent 'second'")
      assertThat(reading.await().text()).isEqualTo("one")
    } finally {
      reading.cancel()
    }
  }

  @Test
  fun receiveRequest_cancelledCallerWhileAnotherReads_isCancelledRatherThanRejected() =
    runBlocking {
      val queue = LiveRequestQueue()
      val liveRun = testInvocationContext().openLiveRun(queue)
      val first = async(start = CoroutineStart.UNDISPATCHED) { liveRun.receiveRequest("agent") }
      try {
        val second = launch {
          cancel()
          assertFailsWith<CancellationException> { liveRun.receiveRequest("agent") }
        }
        second.join()
      } finally {
        first.cancel()
      }
    }

  @Test
  fun checkNoOtherReader_cancelledCallerWhileAnotherReads_isCancelledRatherThanRejected() =
    runBlocking {
      val queue = LiveRequestQueue()
      val liveRun = testInvocationContext().openLiveRun(queue)
      val first = async(start = CoroutineStart.UNDISPATCHED) { liveRun.receiveRequest("agent") }
      try {
        val second = launch {
          cancel()
          assertFailsWith<CancellationException> { liveRun.checkNoOtherReader("agent") }
        }
        second.join()
      } finally {
        first.cancel()
      }
    }

  @Test
  fun receiveRequest_afterTheReaderFinished_readsTheNextRequest() = runBlocking {
    val queue = LiveRequestQueue()
    val liveRun = testInvocationContext().openLiveRun(queue)
    queue.sendContent(userMessage("one"))
    queue.sendContent(userMessage("two"))

    // Each read runs in a coroutine of its own, as each live connection's reader does.
    val first = async { liveRun.receiveRequest("agent") }.await()
    val second = async { liveRun.receiveRequest("agent") }.await()

    assertThat(listOf(first.text(), second.text())).containsExactly("one", "two").inOrder()
  }

  @Test
  fun receiveRequest_readerCancelledButNotYetFinished_letsTheNextOneRead() = runBlocking {
    val queue = LiveRequestQueue()
    val liveRun = testInvocationContext().openLiveRun(queue)
    val stopping = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    // Waits to read, then is cancelled while its cleanup is still running.
    val first =
      launch(start = CoroutineStart.UNDISPATCHED) {
        try {
          val unused = liveRun.receiveRequest("agent")
        } finally {
          withContext(NonCancellable) {
            stopping.complete(Unit)
            release.await()
          }
        }
      }
    // Released however the test ends, so a failing read cannot leave the reader hanging.
    val next =
      try {
        first.cancel()
        stopping.await()
        queue.sendContent(userMessage("next"))
        withTimeout(10.seconds) { liveRun.receiveRequest("agent") }
      } finally {
        release.complete(Unit)
      }
    first.join()

    assertThat(next.text()).isEqualTo("next")
  }
}
