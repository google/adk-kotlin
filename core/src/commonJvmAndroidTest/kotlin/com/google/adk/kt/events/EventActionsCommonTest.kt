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

import com.google.adk.kt.sessions.State
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class EventActionsCommonTest {

  @Test
  fun defaultConstructor_initializesWithDefaultValues() {
    val eventActions = EventActions()

    assertThat(eventActions.skipSummarization).isFalse()
    assertThat(eventActions.stateDelta).isEmpty()
    assertThat(eventActions.artifactDelta).isEmpty()
    assertThat(eventActions.transferToAgent).isNull()
    assertThat(eventActions.escalate).isFalse()
    assertThat(eventActions.requestedToolConfirmations).isEmpty()
    assertThat(eventActions.compaction).isNull()
  }

  @Test
  fun removeStateByKey_marksKeyAsRemoved() {
    val eventActions = EventActions()
    eventActions.stateDelta["key1"] = "value1"
    eventActions.removeStateByKey("key1")

    assertThat(eventActions.stateDelta).containsExactly("key1", State.REMOVED)
  }
}
