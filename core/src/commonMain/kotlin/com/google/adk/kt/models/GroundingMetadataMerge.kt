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
    imageSearchQueries = unionQueries(existing.imageSearchQueries, new.imageSearchQueries),
    groundingChunks = concatOrNull(existing.groundingChunks, new.groundingChunks),
    groundingSupports =
      concatOrNull(
        existing.groundingSupports,
        new.groundingSupports?.map { support ->
          support.copy(
            groundingChunkIndices = support.groundingChunkIndices?.map { it + chunkOffset }
          )
        },
      ),
    webSearchQueries = unionQueries(existing.webSearchQueries, new.webSearchQueries),
    retrievalQueries = unionQueries(existing.retrievalQueries, new.retrievalQueries),
    searchEntryPoint = new.searchEntryPoint ?: existing.searchEntryPoint,
    retrievalMetadata = new.retrievalMetadata ?: existing.retrievalMetadata,
  )
}

private fun <T> concatOrNull(existing: List<T>?, new: List<T>?): List<T>? =
  if (existing == null && new == null) null else existing.orEmpty() + new.orEmpty()

private fun unionQueries(existing: List<String>?, new: List<String>?): List<String>? =
  if (existing == null && new == null) null
  else existing.orEmpty() + new.orEmpty().distinct().filterNot { it in existing.orEmpty() }
