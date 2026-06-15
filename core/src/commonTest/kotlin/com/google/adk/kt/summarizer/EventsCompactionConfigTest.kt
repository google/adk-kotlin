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
package com.google.adk.kt.summarizer

import com.google.adk.kt.events.Event
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EventsCompactionConfigTest {

  @Test
  fun construct_allNull_succeeds() {
    val config = EventsCompactionConfig()

    assertFalse(config.hasSlidingWindowConfig())
  }

  @Test
  fun construct_validSlidingWindow_succeeds() {
    val config =
      EventsCompactionConfig(compactionInterval = 2, overlapSize = 1, summarizer = NoopSummarizer)

    assertTrue(config.hasSlidingWindowConfig())
    assertEquals(2, config.compactionInterval)
    assertEquals(1, config.overlapSize)
  }

  @Test
  fun construct_overlapSizeZero_succeeds() {
    val config = EventsCompactionConfig(compactionInterval = 2, overlapSize = 0)

    assertTrue(config.hasSlidingWindowConfig())
  }

  @Test
  fun construct_compactionIntervalZero_throwsIllegalArgumentException() {
    assertFailsWith<IllegalArgumentException> {
      EventsCompactionConfig(compactionInterval = 0, overlapSize = 1)
    }
  }

  @Test
  fun construct_compactionIntervalNegative_throwsIllegalArgumentException() {
    assertFailsWith<IllegalArgumentException> {
      EventsCompactionConfig(compactionInterval = -1, overlapSize = 1)
    }
  }

  @Test
  fun construct_overlapSizeNegative_throwsIllegalArgumentException() {
    assertFailsWith<IllegalArgumentException> {
      EventsCompactionConfig(compactionInterval = 2, overlapSize = -1)
    }
  }

  @Test
  fun construct_compactionIntervalWithoutOverlapSize_throwsIllegalArgumentException() {
    assertFailsWith<IllegalArgumentException> {
      EventsCompactionConfig(compactionInterval = 2, overlapSize = null)
    }
  }

  @Test
  fun construct_overlapSizeWithoutCompactionInterval_throwsIllegalArgumentException() {
    assertFailsWith<IllegalArgumentException> {
      EventsCompactionConfig(compactionInterval = null, overlapSize = 1)
    }
  }

  private object NoopSummarizer : EventSummarizer {
    override suspend fun summarizeEvents(events: List<Event>): Event? = null
  }
}
