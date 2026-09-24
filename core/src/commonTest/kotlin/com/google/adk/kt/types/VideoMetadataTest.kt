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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.microseconds

class VideoMetadataTest {

  @OptIn(AdkJavaInteropApi::class)
  @Test
  fun toBuilder_matchesCopy() {
    val metadata =
      VideoMetadata(
        // Sub-millisecond, so a copy made through the millisecond setters would not match.
        startOffset = 1500.microseconds,
        endOffset = 2500.microseconds,
        fps = 2.0,
      )

    assertEquals(metadata.copy(), metadata.toBuilder().build())
    assertEquals(metadata.copy(fps = 1.0), metadata.toBuilder().fps(1.0).build())
  }

  @OptIn(AdkJavaInteropApi::class)
  @Test
  fun toBuilder_nullOffsetMillis_matchesCopy() {
    val metadata =
      VideoMetadata(startOffset = 1500.microseconds, endOffset = 2500.microseconds, fps = 2.0)

    assertEquals(
      metadata.copy(startOffset = null, endOffset = null),
      metadata.toBuilder().startOffsetMillis(null).endOffsetMillis(null).build(),
    )
  }
}
