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
import kotlin.test.assertNull

class TriggerQueueTest {

  @Test
  fun aNewQueueHasNoQueuedNodes() {
    val queue = TriggerQueue()

    assertEquals(emptyList(), queue.queuedNodeNames)
  }

  @Test
  fun triggersForOneNodeArePolledInArrivalOrder() {
    // Arrange
    val queue = TriggerQueue()
    queue.enqueue("a", Trigger("first"))
    queue.enqueue("a", Trigger("second"))

    // Act + Assert
    assertEquals("first", queue.poll("a")?.input)
    assertEquals("second", queue.poll("a")?.input)
    assertNull(queue.poll("a"))
  }

  @Test
  fun eachNodeKeepsAnIndependentQueue() {
    // Arrange
    val queue = TriggerQueue()
    queue.enqueue("a", Trigger("a1"))
    queue.enqueue("b", Trigger("b1"))

    // Assert: draining one node leaves the other untouched.
    assertEquals("a1", queue.poll("a")?.input)
    assertNull(queue.poll("a"))
    assertEquals("b1", queue.poll("b")?.input)
  }

  @Test
  fun aNodeDropsOutOfTheQueueOnceItsLastTriggerIsPolled() {
    // Arrange
    val queue = TriggerQueue()
    queue.enqueue("a", Trigger("only"))
    queue.enqueue("b", Trigger("keep"))

    // Act
    val unused = queue.poll("a")

    // Assert: 'a' is gone and 'b' remains.
    assertEquals(listOf("b"), queue.queuedNodeNames)
  }

  @Test
  fun pollingAnUnknownNodeReturnsNull() {
    val queue = TriggerQueue()

    assertNull(queue.poll("missing"))
  }

  @Test
  fun queuedNodeNamesListsEveryNodeWithPendingWork() {
    // Arrange
    val queue = TriggerQueue()
    queue.enqueue("a", Trigger(null))
    queue.enqueue("b", Trigger(null))

    // Assert
    assertEquals(setOf("a", "b"), queue.queuedNodeNames.toSet())
  }
}
