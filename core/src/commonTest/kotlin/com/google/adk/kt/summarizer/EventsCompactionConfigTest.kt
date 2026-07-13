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
    assertFalse(config.hasTokenThresholdConfig())
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

  @Test
  fun construct_validTokenThreshold_succeeds() {
    val config =
      EventsCompactionConfig(
        tokenThreshold = 50000,
        eventRetentionSize = 5,
        summarizer = NoopSummarizer,
      )

    assertTrue(config.hasTokenThresholdConfig())
    assertFalse(config.hasSlidingWindowConfig())
    assertEquals(50000, config.tokenThreshold)
    assertEquals(5, config.eventRetentionSize)
  }

  @Test
  fun construct_eventRetentionSizeZero_succeeds() {
    val config = EventsCompactionConfig(tokenThreshold = 50000, eventRetentionSize = 0)

    assertTrue(config.hasTokenThresholdConfig())
  }

  @Test
  fun construct_bothStrategiesConfigured_succeeds() {
    val config =
      EventsCompactionConfig(
        compactionInterval = 3,
        overlapSize = 1,
        tokenThreshold = 50000,
        eventRetentionSize = 5,
      )

    assertTrue(config.hasSlidingWindowConfig())
    assertTrue(config.hasTokenThresholdConfig())
  }

  @Test
  fun construct_tokenThresholdZero_throwsIllegalArgumentException() {
    assertFailsWith<IllegalArgumentException> {
      EventsCompactionConfig(tokenThreshold = 0, eventRetentionSize = 5)
    }
  }

  @Test
  fun construct_tokenThresholdNegative_throwsIllegalArgumentException() {
    assertFailsWith<IllegalArgumentException> {
      EventsCompactionConfig(tokenThreshold = -1, eventRetentionSize = 5)
    }
  }

  @Test
  fun construct_eventRetentionSizeNegative_throwsIllegalArgumentException() {
    assertFailsWith<IllegalArgumentException> {
      EventsCompactionConfig(tokenThreshold = 50000, eventRetentionSize = -1)
    }
  }

  @Test
  fun construct_tokenThresholdWithoutEventRetentionSize_throwsIllegalArgumentException() {
    assertFailsWith<IllegalArgumentException> {
      EventsCompactionConfig(tokenThreshold = 50000, eventRetentionSize = null)
    }
  }

  @Test
  fun construct_eventRetentionSizeWithoutTokenThreshold_throwsIllegalArgumentException() {
    assertFailsWith<IllegalArgumentException> {
      EventsCompactionConfig(tokenThreshold = null, eventRetentionSize = 5)
    }
  }

  private object NoopSummarizer : EventSummarizer {
    override suspend fun summarizeEvents(events: List<Event>): Event? = null
  }
}
