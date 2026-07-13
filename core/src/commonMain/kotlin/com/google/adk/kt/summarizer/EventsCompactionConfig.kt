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
 * @property tokenThreshold The most recent prompt token count that triggers intra-invocation
 *   token-threshold (tail-retention) compaction before an LLM call. Must be strictly positive when
 *   set, and must be set together with [eventRetentionSize].
 * @property eventRetentionSize The number of most recent events kept raw (un-compacted) when
 *   token-threshold compaction fires; the older events are summarized into a single event. Must be
 *   non-negative when set, and must be set together with [tokenThreshold].
 */
data class EventsCompactionConfig(
  val compactionInterval: Int? = null,
  val overlapSize: Int? = null,
  val summarizer: EventSummarizer? = null,
  val tokenThreshold: Int? = null,
  val eventRetentionSize: Int? = null,
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
    if (tokenThreshold != null) {
      require(tokenThreshold > 0) { "tokenThreshold must be > 0, but was $tokenThreshold." }
    }
    if (eventRetentionSize != null) {
      require(eventRetentionSize >= 0) {
        "eventRetentionSize must be >= 0, but was $eventRetentionSize."
      }
    }
    require((tokenThreshold == null) == (eventRetentionSize == null)) {
      "tokenThreshold and eventRetentionSize must be set together or both null " +
        "(got tokenThreshold=$tokenThreshold, eventRetentionSize=$eventRetentionSize)."
    }
  }

  /** Returns true when sliding-window compaction is fully configured. */
  fun hasSlidingWindowConfig(): Boolean = compactionInterval != null && overlapSize != null

  /** Returns true when token-threshold compaction is fully configured. */
  fun hasTokenThresholdConfig(): Boolean = tokenThreshold != null && eventRetentionSize != null
}
