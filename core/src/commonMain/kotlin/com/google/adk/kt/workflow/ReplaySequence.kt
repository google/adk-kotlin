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

import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Releases a node's replayed children in their recorded order, given as `name@runId` keys in
 * [order], so code that collects children as they complete sees that order. Safe to call from
 * concurrent coroutines.
 */
@OptIn(ExperimentalWorkflowApi::class)
internal class ReplaySequence(
  order: List<String>,
  private val timeout: Duration = DEFAULT_TIMEOUT,
) {
  private val indexByKey = buildMap {
    for (key in order) {
      if (key !in this) put(key, size)
    }
  }
  private val done = List(indexByKey.size) { CompletableDeferred<Unit>() }

  /**
   * Suspends until every child ahead of [key] in the order has completed. Throws
   * [WorkflowInvariantException] after [timeout], when the run no longer follows the recorded one.
   */
  suspend fun awaitTurn(key: String) {
    val index = indexByKey[key] ?: return
    withTimeoutOrNull(timeout) { for (i in 0 until index) done[i].await() }
      ?: throw WorkflowInvariantException(
        "Replay divergence detected: Timed out waiting for sequence key '$key' to be unblocked."
      )
  }

  /** First incomplete child ahead of [key], or null if [key] may go now. */
  fun firstBlocker(key: String): Deferred<Unit>? {
    val index = indexByKey[key] ?: return null
    for (i in 0 until index) {
      if (!done[i].isCompleted) return done[i]
    }
    return null
  }

  /** Whether [key] is in the order and has not yet been advanced. */
  fun isPending(key: String): Boolean = indexByKey[key]?.let { !done[it].isCompleted } ?: false

  /** Records that the child [key] completed, releasing the children it was holding back. */
  fun advance(key: String) {
    indexByKey[key]?.let { done[it].complete(Unit) }
  }

  /** Releases every child at once, when no order is left to keep. */
  fun releaseAll() {
    for (turn in done) turn.complete(Unit)
  }

  companion object {
    val DEFAULT_TIMEOUT: Duration = 15.seconds
  }
}
