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

import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.serialization.LenientDurationStringSerializer
import kotlin.jvm.JvmStatic
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.Serializable

/** Metadata describing how to interpret a video [Part]. */
@Serializable
data class VideoMetadata(
  /** The start offset of the video segment to use. */
  @Serializable(with = LenientDurationStringSerializer::class) val startOffset: Duration? = null,
  /** The end offset of the video segment to use. */
  @Serializable(with = LenientDurationStringSerializer::class) val endOffset: Duration? = null,
  /** The frame rate (frames per second) to sample the video at. */
  val fps: Double? = null,
) {
  /** Returns [startOffset] in whole milliseconds, or `null` (its getter is mangled for Java). */
  fun startOffsetMillis(): Long? = startOffset?.inWholeMilliseconds

  /** Returns [endOffset] in whole milliseconds, or `null` (its getter is mangled for Java). */
  fun endOffsetMillis(): Long? = endOffset?.inWholeMilliseconds

  /**
   * Fluent builder for [VideoMetadata], provided primarily for Java callers. Any property left
   * unset falls back to the same default as the constructor.
   */
  @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
  class Builder {
    private var startOffset: Duration? = null
    private var endOffset: Duration? = null
    private var fps: Double? = null

    /** Sets [startOffset] in milliseconds; the [Duration] constructor param is mangled for Java. */
    fun startOffsetMillis(startOffsetMillis: Long): Builder = apply {
      this.startOffset = startOffsetMillis.milliseconds
    }

    /** Sets [endOffset] in milliseconds; the [Duration] constructor param is mangled for Java. */
    fun endOffsetMillis(endOffsetMillis: Long): Builder = apply {
      this.endOffset = endOffsetMillis.milliseconds
    }

    fun fps(fps: Double?): Builder = apply { this.fps = fps }

    fun build(): VideoMetadata =
      VideoMetadata(startOffset = startOffset, endOffset = endOffset, fps = fps)
  }

  companion object {
    @AdkJavaInteropApi @JvmStatic fun builder(): Builder = Builder()
  }
}
