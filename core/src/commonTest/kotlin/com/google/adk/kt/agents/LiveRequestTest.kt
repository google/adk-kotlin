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

import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.models.ContentInput
import com.google.adk.kt.models.RealtimeInput
import com.google.adk.kt.testing.userMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Covers [LiveRequest]'s rule that a close request carries no input, and its builder. */
class LiveRequestTest {

  @Test
  fun constructor_closeWithContentInput_throwsIllegalArgumentException() {
    assertFailsWith<IllegalArgumentException> {
      LiveRequest(input = ContentInput(userMessage("last words")), close = true)
    }
  }

  @Test
  fun constructor_closeWithRealtimeInput_throwsIllegalArgumentException() {
    assertFailsWith<IllegalArgumentException> {
      LiveRequest(input = RealtimeInput.ActivityStart, close = true)
    }
  }

  @Test
  fun constructor_closeWithStateDelta_isAccepted() {
    val request = LiveRequest(close = true, stateDelta = mapOf("k" to "v"))

    assertEquals(mapOf("k" to "v"), request.stateDelta)
    assertNull(request.input)
  }

  @Test
  fun constructor_stateDeltaWithRealtimeInput_isAccepted() {
    val request = LiveRequest(input = RealtimeInput.AudioStreamEnd, stateDelta = mapOf("k" to "v"))

    assertEquals(RealtimeInput.AudioStreamEnd, request.input)
  }

  @Test
  fun constructor_nothingSet_isAccepted() {
    val request = LiveRequest()

    assertNull(request.input)
    assertEquals(false, request.close)
  }

  @OptIn(AdkJavaInteropApi::class)
  @Test
  fun build_nothingSet_matchesConstructorDefaults() {
    // The builder repeats the constructor's defaults, so a change to one must reach the other.
    assertEquals(LiveRequest(), LiveRequest.builder().build())
  }

  @OptIn(AdkJavaInteropApi::class)
  @Test
  fun build_onePropertySet_leavesTheRestAtTheirDefaults() {
    val input = ContentInput(userMessage("hello"), partial = true)
    val stateDelta = mapOf("key" to "value")

    assertEquals(LiveRequest(input = input), LiveRequest.builder().input(input).build())
    assertEquals(LiveRequest(close = true), LiveRequest.builder().close(true).build())
    assertEquals(
      LiveRequest(stateDelta = stateDelta),
      LiveRequest.builder().stateDelta(stateDelta).build(),
    )
  }

  @OptIn(AdkJavaInteropApi::class)
  @Test
  fun input_nullAfterAValue_clearsIt() {
    val builder = LiveRequest.builder().input(ContentInput(userMessage("hello")))

    assertEquals(LiveRequest(), builder.input(null).build())
  }

  @OptIn(AdkJavaInteropApi::class)
  @Test
  fun build_closeWithInput_throwsIllegalArgumentException() {
    assertFailsWith<IllegalArgumentException> {
      LiveRequest.builder().input(ContentInput(userMessage("last words"))).close(true).build()
    }
  }

  @OptIn(AdkJavaInteropApi::class)
  @Test
  fun toBuilder_matchesCopy() {
    // A close request cannot carry input, so two instances cover every property.
    val withInput =
      LiveRequest(
        input = ContentInput(userMessage("hello"), partial = true),
        stateDelta = mapOf("key" to "value"),
      )
    val withClose = LiveRequest(close = true, stateDelta = mapOf("key" to "value"))

    assertEquals(withInput.copy(), withInput.toBuilder().build())
    assertEquals(withClose.copy(), withClose.toBuilder().build())
  }

  @OptIn(AdkJavaInteropApi::class)
  @Test
  fun stateDelta_mapMutatedAfterBuild_requestKeepsTheOriginal() {
    val stateDelta = mutableMapOf<String, Any>("key" to "value")
    val request = LiveRequest.builder().stateDelta(stateDelta).build()

    stateDelta["key"] = "changed"

    assertEquals(mapOf("key" to "value"), request.stateDelta)
  }
}
