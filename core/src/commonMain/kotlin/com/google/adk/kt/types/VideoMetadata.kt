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

import kotlin.time.Duration
import kotlinx.serialization.Serializable

/** Metadata describing how to interpret a video [Part]. */
@Serializable
data class VideoMetadata(
  /** The start offset of the video segment to use. */
  val startOffset: Duration? = null,
  /** The end offset of the video segment to use. */
  val endOffset: Duration? = null,
  /** The frame rate (frames per second) to sample the video at. */
  val fps: Double? = null,
)
