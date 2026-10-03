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

/** Where a node activation's output stands. One value, so every transition is a single CAS. */
internal sealed interface OutputState {
  /** No output and no delegation yet. */
  data object None : OutputState

  /** A `useAsOutput` child owns this activation's output and has recorded none yet. */
  data object Delegated : OutputState

  /** This activation set its own output; it is not marked emitted yet. */
  data class Produced(val value: Any) : OutputState

  /**
   * This activation's own output was marked emitted; a workflow's validated output is never sent.
   */
  data class Emitted(val value: Any) : OutputState

  /** A `useAsOutput` descendant recorded [value] as this activation's output. */
  data class DelegateEmitted(val value: Any) : OutputState
}
