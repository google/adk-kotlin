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

import com.google.adk.kt.types.GroundingMetadata

/**
 * Folds one frame's grounding into what the turn has accumulated so far, as ADK Python's
 * `GeminiLlmConnection._merge_grounding_metadata` does.
 *
 * A support's `groundingChunkIndices` index its own frame's chunk list, so each is raised by the
 * number of chunks already held. Without that shift a citation would silently point at an earlier
 * frame's source.
 */
internal fun mergeGroundingMetadata(
  existing: GroundingMetadata?,
  new: GroundingMetadata?,
): GroundingMetadata? {
  if (existing == null) return new
  if (new == null) return existing
  val chunkOffset = existing.groundingChunks?.size ?: 0
  return GroundingMetadata(
    imageSearchQueries =
      bothAbsentOrMerged(existing.imageSearchQueries, new.imageSearchQueries) {
        unionQueries(existing.imageSearchQueries.orEmpty(), new.imageSearchQueries.orEmpty())
      },
    groundingChunks =
      bothAbsentOrMerged(existing.groundingChunks, new.groundingChunks) {
        existing.groundingChunks.orEmpty() + new.groundingChunks.orEmpty()
      },
    groundingSupports =
      bothAbsentOrMerged(existing.groundingSupports, new.groundingSupports) {
        existing.groundingSupports.orEmpty() +
          new.groundingSupports.orEmpty().map { support ->
            support.copy(
              groundingChunkIndices = support.groundingChunkIndices?.map { it + chunkOffset }
            )
          }
      },
    webSearchQueries =
      bothAbsentOrMerged(existing.webSearchQueries, new.webSearchQueries) {
        unionQueries(existing.webSearchQueries.orEmpty(), new.webSearchQueries.orEmpty())
      },
    retrievalQueries =
      bothAbsentOrMerged(existing.retrievalQueries, new.retrievalQueries) {
        unionQueries(existing.retrievalQueries.orEmpty(), new.retrievalQueries.orEmpty())
      },
    searchEntryPoint = new.searchEntryPoint ?: existing.searchEntryPoint,
    retrievalMetadata = new.retrievalMetadata ?: existing.retrievalMetadata,
  )
}

/** Returns null only when both sides are absent; otherwise the fields are merged by [merge]. */
private inline fun <L : Any> bothAbsentOrMerged(existing: L?, new: L?, merge: () -> L): L? =
  if (existing == null && new == null) null else merge()

private fun unionQueries(existing: List<String>, new: List<String>): List<String> =
  existing + new.distinct().filterNot { it in existing }
