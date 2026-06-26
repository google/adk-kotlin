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

/** Detailed log probabilities for the chosen and top candidate tokens. */
@Serializable
data class LogprobsResult(
  /** The chosen token at each decoding step. */
  val chosenCandidates: List<LogprobsResultCandidate>? = null,
  /** The top token candidates at each decoding step. */
  val topCandidates: List<LogprobsResultTopCandidates>? = null,
  /** The sum of log probabilities across the chosen tokens. */
  val logProbabilitySum: Double? = null,
)
