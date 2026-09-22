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

package com.google.adk.kt.workflow

/**
 * A workflow execution branch: a dot-separated path of `name@runId` segments that keeps one
 * parallel branch's events out of a sibling's conversation history.
 */
internal object BranchPath {

  /** Appends a `name@runId` segment to [base] using [separator]. */
  fun appendSegment(base: String?, name: String, runId: String, separator: Char): String {
    val segment = "$name@$runId"
    return if (base.isNullOrEmpty()) segment else "$base$separator$segment"
  }

  /** Derives a sub-branch of [base] for a node's activation, e.g. `parent` -> `parent.child@1`. */
  fun subBranch(base: String?, name: String, runId: String): String =
    appendSegment(base, name, runId, separator = '.')

  /** The longest shared segment prefix of [branches], where a join re-merges its predecessors. */
  fun commonPrefix(branches: List<String?>): String {
    val segmented = branches.map { segmentsOf(it) }
    if (segmented.isEmpty()) return ""
    val shortest = segmented.minOf { it.size }
    val common = mutableListOf<String>()
    for (i in 0 until shortest) {
      val segment = segmented.first()[i]
      if (segmented.any { it[i] != segment }) break
      common.add(segment)
    }
    return common.joinToString(".")
  }

  private fun segmentsOf(branch: String?): List<String> =
    if (branch.isNullOrEmpty()) emptyList() else branch.split(".")
}
