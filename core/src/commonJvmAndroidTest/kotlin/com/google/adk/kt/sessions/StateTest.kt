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

package com.google.adk.kt.sessions

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class StateTest {

  @Test
  fun initialState_hasNoDelta() {
    val state = State()
    assertThat(state.hasDelta).isFalse()
  }

  @Test
  fun putAndGet_updatesStateAndDelta() {
    val state = State()
    state["key1"] = "value1"
    assertThat(state["key1"]).isEqualTo("value1")
    assertThat(state.hasDelta).isTrue()
  }

  @Test
  fun put_updatesDelta() {
    val state = State()
    state["key1"] = "value1"
    state["key2"] = "value2"

    assertThat(state.hasDelta).isTrue()
  }

  @Test
  fun remove_updatesStateAndDelta() {
    val state = State(mutableMapOf("key1" to "value1"))
    val unused = state.remove("key1")

    assertThat(state.containsKey("key1")).isFalse()
    assertThat(state.hasDelta).isTrue()
  }

  @Test
  fun clear_clearsStateAndDelta() {
    val state = State()
    state["key1"] = "value1"
    state.clear()
    assertThat(state).isEmpty()
    assertThat(state.hasDelta).isFalse()
  }

  @Test
  fun applyDelta_updatesState() {
    val state =
      State(initialState = mapOf("key1" to "value1", "key2" to "value2", "key3" to "value3"))
    val delta =
      mapOf(
        "key1" to "new-value1",
        "key2" to "value2",
        "key3" to State.REMOVED,
        "temp:key4" to "value4",
      )

    state.applyDelta(delta)

    assertThat(state["key1"]).isEqualTo("new-value1")
    assertThat(state["key2"]).isEqualTo("value2")
    assertThat(state.containsKey("key3")).isFalse()
    // temp: entries are skipped by applyDelta.
    assertThat(state.containsKey("temp:key4")).isFalse()
    assertThat(state.hasDelta).isTrue()
  }

  @Test
  fun applyTempDelta_appliesOnlyTempEntries() {
    val state = State(initialState = mapOf("keep" to "old"))

    state.applyTempDelta(mapOf("temp:key1" to "value1", "sessionKey" to "ignored"))

    assertThat(state["temp:key1"]).isEqualTo("value1")
    // Non-`temp:` entries are left to applyDelta.
    assertThat(state.containsKey("sessionKey")).isFalse()
    assertThat(state["keep"]).isEqualTo("old")
  }

  @Test
  fun applyTempDelta_removedSentinel_removesTempKey() {
    val state = State(initialState = mapOf("temp:key1" to "value1"))

    state.applyTempDelta(mapOf("temp:key1" to State.REMOVED))

    assertThat(state.containsKey("temp:key1")).isFalse()
  }

  @Test
  fun prefixConstants_areDefinedCorrectly() {
    assertThat(State.Companion.APP_PREFIX).isEqualTo("app:")
    assertThat(State.Companion.USER_PREFIX).isEqualTo("user:")
    assertThat(State.Companion.TEMP_PREFIX).isEqualTo("temp:")
  }
}
