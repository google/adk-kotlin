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

import com.google.adk.kt.serialization.LenientEpochMillisSerializer
import com.google.adk.kt.types.Content
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

/**
 * A record that a continuous range of session [Event]s has been replaced by a single piece of
 * [compactedContent] (typically a model-generated summary).
 *
 * An [EventCompaction] is attached to a new [Event] via [EventActions.compaction]; the original
 * events are left untouched. When the next LLM prompt is built, the contents processor uses the
 * range to skip the covered events and inserts [compactedContent] in their place.
 *
 * @property startTimestamp Epoch milliseconds of the earliest covered event (inclusive). Also reads
 *   ADK Python's fractional epoch seconds.
 * @property endTimestamp Epoch milliseconds of the latest covered event (inclusive). Must be
 *   greater than or equal to [startTimestamp]. Also reads ADK Python's fractional epoch seconds.
 * @property compactedContent The content that replaces the covered events in the prompt.
 */
@Serializable
data class EventCompaction(
  @Serializable(with = LenientEpochMillisSerializer::class)
  @JsonNames("start_timestamp")
  val startTimestamp: Long,
  @Serializable(with = LenientEpochMillisSerializer::class)
  @JsonNames("end_timestamp")
  val endTimestamp: Long,
  @JsonNames("compacted_content") val compactedContent: Content,
) {
  init {
    require(endTimestamp >= startTimestamp) {
      "endTimestamp ($endTimestamp) must be greater than or equal to startTimestamp " +
        "($startTimestamp)."
    }
  }
}
