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
package com.google.adk.kt.models

import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.types.Content
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Bounds a collect whose failure mode is a hang. */
private val GUARD_TIMEOUT = 10.seconds

/**
 * Covers the single-collector contract of [LiveConnection.receive]: concurrent collection is
 * rejected, sequential collection is not.
 */
class SingleCollectorLiveConnectionTest {

  private class FlowLiveConnection(private val source: Flow<LlmResponse>) :
    SingleCollectorLiveConnection() {
    override fun responses(): Flow<LlmResponse> = source

    override suspend fun sendHistory(history: List<Content>) {}

    override suspend fun sendContent(content: Content, partial: Boolean) {}

    override suspend fun sendRealtime(input: RealtimeInput) {}

    override suspend fun closeSession() {}
  }

  private fun response(text: String) = LlmResponse(content = modelMessage(text))

  @Test
  fun receive_singleCollector_emitsEveryResponse() = runBlocking {
    val connection = FlowLiveConnection(flowOf(response("one"), response("two")))

    val received = connection.receive().toList()

    assertEquals(listOf(response("one"), response("two")), received)
  }

  @Test
  fun receive_secondConcurrentCollection_throwsIllegalStateException() = runBlocking {
    val firstStarted = CompletableDeferred<Unit>()
    val releaseFirst = CompletableDeferred<Unit>()
    val connection =
      FlowLiveConnection(
        flow {
          firstStarted.complete(Unit)
          releaseFirst.await()
          emit(response("one"))
        }
      )

    val firstCollector = launch { connection.receive().toList() }
    firstStarted.await()

    val error =
      withTimeout(GUARD_TIMEOUT) {
        assertFailsWith<IllegalStateException> { connection.receive().toList() }
      }

    releaseFirst.complete(Unit)
    firstCollector.join()
    assertContains(
      error.message.orEmpty(),
      "one collector at a time",
      message = "the failure should name the rule the caller broke",
    )
  }

  @Test
  fun receive_collectedAgainAfterFirstCompletes_succeeds() = runBlocking {
    // Each collection reads one turn, so sequential re-collection has to stay legal.
    val connection = FlowLiveConnection(flowOf(response("one")))

    assertEquals(listOf(response("one")), connection.receive().toList())
    assertEquals(listOf(response("one")), connection.receive().toList())
  }

  @Test
  fun receive_firstCollectionFails_releasesTheGuard() = runBlocking {
    // A failed collection must not leave the connection unreadable for every later reader.
    val connection = FlowLiveConnection(flow { throw IllegalArgumentException("transport lost") })

    assertFailsWith<IllegalArgumentException> { connection.receive().toList() }
    val second = assertFailsWith<IllegalArgumentException> { connection.receive().toList() }

    // Reaching the source again, not tripping the check, shows the `finally` released the guard.
    assertEquals("transport lost", second.message)
  }

  @Test
  fun receive_collectorCancelled_releasesTheGuard() = runBlocking {
    // Cancellation is the path a live run takes; a guard left held wedges the connection shut.
    val started = CompletableDeferred<Unit>()
    val connection =
      FlowLiveConnection(
        flow {
          emit(response("one"))
          started.complete(Unit)
          awaitCancellation()
        }
      )

    val job = launch { connection.receive().collect {} }
    started.await()
    job.cancelAndJoin()

    // `first()`: this source never completes. `cancelAndJoin`: the `finally` runs after `cancel`.
    assertEquals(response("one"), connection.receive().first())
  }
}
