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

/**
 * Configuration for event compaction.
 *
 * @property compactionInterval The number of *new* user-initiated invocations that, once fully
 *   represented in the session's events, will trigger a sliding-window compaction. Must be strictly
 *   positive when set.
 * @property overlapSize The number of preceding invocations to include from the end of the last
 *   compacted range when forming the next sliding-window compaction window. Creates an overlap
 *   between consecutive summaries for context continuity. Must be non-negative when set.
 * @property summarizer The [EventSummarizer] used to produce the compaction summary. When `null`,
 *   the runner defaults it to an [LlmEventSummarizer] backed by the root agent's model, requiring
 *   the root to be an `LlmAgent` (otherwise it throws at construction).
 */
data class EventsCompactionConfig(
  val compactionInterval: Int? = null,
  val overlapSize: Int? = null,
  val summarizer: EventSummarizer? = null,
) {
  init {
    if (compactionInterval != null) {
      require(compactionInterval > 0) {
        "compactionInterval must be > 0, but was $compactionInterval."
      }
    }
    if (overlapSize != null) {
      require(overlapSize >= 0) { "overlapSize must be >= 0, but was $overlapSize." }
    }
    require((compactionInterval == null) == (overlapSize == null)) {
      "compactionInterval and overlapSize must be set together or both null " +
        "(got compactionInterval=$compactionInterval, overlapSize=$overlapSize)."
    }
  }

  /** Returns true when sliding-window compaction is fully configured. */
  fun hasSlidingWindowConfig(): Boolean = compactionInterval != null && overlapSize != null
}
