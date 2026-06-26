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

/** Represents a possible response from the model. */
data class Candidate(
  /** The content of the candidate. */
  val content: Content,
  /** The reason why the model stopped generating content. */
  val finishReason: FinishReason? = null,
  /** The message associated with the finish reason. */
  val finishMessage: String? = null,
  /** The citation metadata associated with the candidate. */
  val citationMetadata: CitationMetadata? = null,
  /** The grounding metadata associated with the candidate. */
  val groundingMetadata: GroundingMetadata? = null,
  /** The average log probability of the candidate's tokens. */
  val avgLogprobs: Double? = null,
  /** Detailed log probabilities for the chosen and top candidate tokens. */
  val logprobsResult: LogprobsResult? = null,
)
