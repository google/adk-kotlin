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

import com.google.adk.kt.testing.modelMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class EventCompactionTest {

  @Test
  fun construct_validRange_exposesProperties() {
    val content = modelMessage("summary")

    val compaction =
      EventCompaction(startTimestamp = 100L, endTimestamp = 200L, compactedContent = content)

    assertEquals(100L, compaction.startTimestamp)
    assertEquals(200L, compaction.endTimestamp)
    assertSame(content, compaction.compactedContent)
  }

  @Test
  fun construct_startEqualsEnd_succeeds() {
    val compaction =
      EventCompaction(
        startTimestamp = 50L,
        endTimestamp = 50L,
        compactedContent = modelMessage("single-event summary"),
      )

    assertEquals(50L, compaction.startTimestamp)
    assertEquals(50L, compaction.endTimestamp)
  }

  @Test
  fun construct_endBeforeStart_throwsIllegalArgumentException() {
    assertFailsWith<IllegalArgumentException> {
      EventCompaction(
        startTimestamp = 200L,
        endTimestamp = 100L,
        compactedContent = modelMessage("summary"),
      )
    }
  }
}
