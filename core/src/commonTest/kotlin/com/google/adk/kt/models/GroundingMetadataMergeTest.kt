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

package com.google.adk.kt.models

import com.google.adk.kt.types.GroundingChunk
import com.google.adk.kt.types.GroundingChunkWeb
import com.google.adk.kt.types.GroundingMetadata
import com.google.adk.kt.types.GroundingSupport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Unit tests for [mergeGroundingMetadata]. */
class GroundingMetadataMergeTest {
  @Test
  fun mergeGroundingMetadata_queryRepeatedInOneFrame_isKeptOnce() {
    val merged =
      mergeGroundingMetadata(
        GroundingMetadata(webSearchQueries = listOf("a")),
        GroundingMetadata(webSearchQueries = listOf("b", "b")),
      )

    assertEquals(listOf("a", "b"), merged?.webSearchQueries)
  }

  @Test
  fun mergeGroundingMetadata_shiftsLaterSupportIndicesByTheChunksAlreadyHeld() {
    val existing =
      GroundingMetadata(
        groundingChunks = listOf(GroundingChunk(web = GroundingChunkWeb(title = "atlas"))),
        groundingSupports = listOf(GroundingSupport(groundingChunkIndices = listOf(0))),
      )
    val incoming =
      GroundingMetadata(
        groundingChunks = listOf(GroundingChunk(web = GroundingChunkWeb(title = "almanac"))),
        groundingSupports = listOf(GroundingSupport(groundingChunkIndices = listOf(0))),
      )

    val merged = assertNotNull(mergeGroundingMetadata(existing, incoming))

    assertEquals(
      listOf(listOf(0), listOf(1)),
      merged.groundingSupports?.map { it.groundingChunkIndices },
    )
  }
}
