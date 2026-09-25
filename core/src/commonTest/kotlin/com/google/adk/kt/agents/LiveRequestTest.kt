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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Covers [LiveRequest]'s construction rule: a close request carries no input. */
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
}
