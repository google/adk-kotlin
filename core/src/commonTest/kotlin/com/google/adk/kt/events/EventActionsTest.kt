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

package com.google.adk.kt.events

import com.google.adk.kt.agents.TypedData
import com.google.adk.kt.sessions.State
import com.google.adk.kt.types.Content
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame

class EventActionsTest {

  @Test
  fun removeStateByKey_setsStateToRemoved() {
    val eventActions = EventActions(stateDelta = mutableMapOf("key1" to "value1"))
    eventActions.removeStateByKey("key1")
    assertEquals(State.REMOVED, eventActions.stateDelta["key1"])
  }

  @Test
  fun removeTempKeys_deletesTempEntries_keepingTheRest() {
    val eventActions = EventActions(stateDelta = mutableMapOf("temp:scratch" to "v", "keep" to "w"))

    eventActions.removeTempKeys()

    assertFalse(eventActions.stateDelta.containsKey("temp:scratch"))
    assertEquals("w", eventActions.stateDelta["keep"])
  }

  @Test
  fun removeTempKeys_noTempEntries_isNoOp() {
    val eventActions = EventActions(stateDelta = mutableMapOf("keep" to "w"))

    eventActions.removeTempKeys()

    assertEquals(mapOf<String, Any>("keep" to "w"), eventActions.stateDelta)
  }

  @Test
  fun mergeWith_defaultWithDefault_returnsDefault() {
    val ea1 = EventActions()
    val ea2 = EventActions()
    val merged = ea1.mergeWith(ea2)
    assertEquals(EventActions(), merged)
  }

  @Test
  fun mergeWith_mergesCorrectly() {
    val toolConfirmation1 = ToolConfirmation(confirmed = false)
    val toolConfirmation2 = ToolConfirmation(confirmed = true)
    val compaction1 =
      EventCompaction(
        startTimestamp = 1L,
        endTimestamp = 10L,
        compactedContent = Content.fromText("model", "first summary"),
      )
    val compaction2 =
      EventCompaction(
        startTimestamp = 11L,
        endTimestamp = 20L,
        compactedContent = Content.fromText("model", "second summary"),
      )

    val ea1 =
      EventActions(
        skipSummarization = false,
        stateDelta = mutableMapOf("key1" to "value1", "key2" to "value2"),
        artifactDelta = mutableMapOf("file1" to 1),
        transferToAgent = "agent1",
        escalate = false,
        endOfAgent = false,
        requestedToolConfirmations = mutableMapOf("tc1" to toolConfirmation1),
        rewindBeforeInvocationId = "id1",
        compaction = compaction1,
      )
    val ea2 =
      EventActions(
        skipSummarization = true,
        stateDelta = mutableMapOf("key2" to "newValue2", "key3" to "value3"),
        artifactDelta = mutableMapOf("file2" to 2),
        transferToAgent = "agent2",
        escalate = true,
        endOfAgent = true,
        requestedToolConfirmations = mutableMapOf("tc2" to toolConfirmation2),
        rewindBeforeInvocationId = "id2",
        compaction = compaction2,
      )

    val merged = ea1.mergeWith(ea2)
    val expected =
      EventActions(
        skipSummarization = true,
        stateDelta = mutableMapOf("key1" to "value1", "key2" to "newValue2", "key3" to "value3"),
        artifactDelta = mutableMapOf("file1" to 1, "file2" to 2),
        transferToAgent = "agent2",
        escalate = true,
        endOfAgent = true,
        requestedToolConfirmations =
          mutableMapOf("tc1" to toolConfirmation1, "tc2" to toolConfirmation2),
        rewindBeforeInvocationId = "id2",
        compaction = compaction2,
      )
    assertEquals(expected, merged)
  }

  @Test
  fun mergeWith_usesThisIfOtherIsNullForNullable() {
    val ea1 = EventActions(transferToAgent = "agent1", rewindBeforeInvocationId = "id1")
    val ea2 = EventActions()

    val merged = ea1.mergeWith(ea2)
    val expected = EventActions(transferToAgent = "agent1", rewindBeforeInvocationId = "id1")
    assertEquals(expected, merged)
  }

  @Test
  fun mergeWith_withNewAgentState_updatesAgentState() {
    val ea1 = EventActions()
    val ea2 = EventActions(agentState = TypedData.IntValue(42))

    val merged = ea1.mergeWith(ea2)

    assertEquals(TypedData.IntValue(42), merged.agentState)
  }

  @Test
  fun mergeWith_otherHasCompactionThisDoesNot_usesOther() {
    val compaction =
      EventCompaction(
        startTimestamp = 100L,
        endTimestamp = 200L,
        compactedContent = Content.fromText("model", "summary"),
      )
    val ea1 = EventActions()
    val ea2 = EventActions(compaction = compaction)

    val merged = ea1.mergeWith(ea2)

    assertSame(compaction, merged.compaction)
  }

  @Test
  fun mergeWith_thisHasCompactionOtherDoesNot_keepsThis() {
    val compaction =
      EventCompaction(
        startTimestamp = 100L,
        endTimestamp = 200L,
        compactedContent = Content.fromText("model", "summary"),
      )
    val ea1 = EventActions(compaction = compaction)
    val ea2 = EventActions()

    val merged = ea1.mergeWith(ea2)

    assertSame(compaction, merged.compaction)
  }

  @Test
  fun defaultConstructor_compactionIsNull() {
    assertNull(EventActions().compaction)
  }
}
