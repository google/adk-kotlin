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

package com.google.adk.kt.types

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

/** A contiguous segment of the response content that a [GroundingSupport] refers to. */
@Serializable
data class Segment(
  /** The start index (in bytes) of the segment within the part. */
  @JsonNames("start_index") val startIndex: Int? = null,
  /** The end index (in bytes, exclusive) of the segment within the part. */
  @JsonNames("end_index") val endIndex: Int? = null,
  /** The index of the part the segment belongs to. */
  @JsonNames("part_index") val partIndex: Int? = null,
  /** The text of the segment. */
  val text: String? = null,
)
