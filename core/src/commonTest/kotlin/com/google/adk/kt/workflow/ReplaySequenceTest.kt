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

@file:OptIn(ExperimentalWorkflowApi::class)

package com.google.adk.kt.workflow

import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

class ReplaySequenceTest {

  @Test
  fun firstKeyGoesAtOnce_laterKeyWaitsUntilTheKeyAheadOfItCompletes(): Unit = runBlocking {
    // Arrange
    val sequence = ReplaySequence(listOf("a@1", "b@1"))
    withTimeout(1.seconds) { sequence.awaitTurn("a@1") }
    val b = async(start = CoroutineStart.UNDISPATCHED) { sequence.awaitTurn("b@1") }
    assertFalse(b.isCompleted)

    // Act
    sequence.advance("a@1")

    // Assert
    withTimeout(1.seconds) { b.await() }
  }

  @Test
  fun keyCompletingOutOfOrder_releasesNothingUntilTheHeadCompletes(): Unit = runBlocking {
    // Arrange
    val sequence = ReplaySequence(listOf("a@1", "b@1", "c@1"))

    // Act
    sequence.advance("b@1")
    val afterB = listOf("b@1", "c@1").map { sequence.goesNow(it) }
    sequence.advance("a@1")

    // Assert: `b` and `c` wait for `a`, then `a` releases both.
    assertEquals(listOf(false, false), afterB)
    assertEquals(listOf(true, true), listOf("b@1", "c@1").map { sequence.goesNow(it) })
  }

  @Test
  fun keyNotInTheOrder_doesNotWait(): Unit = runBlocking {
    // Arrange
    val sequence = ReplaySequence(listOf("a@1", "b@1"))

    // Act + Assert
    withTimeout(1.seconds) { sequence.awaitTurn("other@1") }
  }

  @Test
  fun releaseAll_releasesEveryKeyWithoutAnyCompleting(): Unit = runBlocking {
    // Arrange
    val sequence = ReplaySequence(listOf("a@1", "b@1", "c@1"))

    // Act
    sequence.releaseAll()

    // Assert
    assertEquals(listOf(true, true, true), listOf("a@1", "b@1", "c@1").map { sequence.goesNow(it) })
  }

  @Test
  fun keyThatIsNeverReleased_failsAsADivergenceAfterTheTimeout(): Unit = runBlocking {
    // Arrange
    val sequence = ReplaySequence(listOf("a@1", "b@1"), timeout = 10.milliseconds)

    // Act
    val error = assertFailsWith<WorkflowInvariantException> { sequence.awaitTurn("b@1") }

    // Assert
    assertEquals(
      "Replay divergence detected: Timed out waiting for sequence key 'b@1' to be unblocked.",
      error.message,
    )
  }

  @Test
  fun firstBlocker_tracksNextIncompleteKeyAheadAndClearsOnReleaseAll() {
    // Arrange
    val sequence = ReplaySequence(listOf("a@1", "b@1", "c@1"))

    // Act + Assert: head and unknown keys have no blocker; a later key blocks on the head.
    assertNull(sequence.firstBlocker("a@1"))
    assertNull(sequence.firstBlocker("other@1"))
    assertTrue(sequence.isPending("a@1"))
    assertFalse(sequence.isPending("other@1"))
    val headBlocker = assertNotNull(sequence.firstBlocker("c@1"))
    assertFalse(headBlocker.isCompleted)

    // Advancing the head completes its blocker and shifts `c@1` to `b@1`.
    sequence.advance("a@1")
    assertFalse(sequence.isPending("a@1"))
    assertTrue(headBlocker.isCompleted)
    val nextBlocker = assertNotNull(sequence.firstBlocker("c@1"))
    assertFalse(nextBlocker.isCompleted)

    // `releaseAll` completes every remaining blocker so no key is blocked.
    sequence.releaseAll()
    assertFalse(sequence.isPending("c@1"))
    assertTrue(nextBlocker.isCompleted)
    assertNull(sequence.firstBlocker("c@1"))
  }
}

/** Whether [key] may go now, as every child ahead of it completed or it has no place. */
private suspend fun ReplaySequence.goesNow(key: String): Boolean =
  withTimeoutOrNull(100.milliseconds) { awaitTurn(key) } != null
