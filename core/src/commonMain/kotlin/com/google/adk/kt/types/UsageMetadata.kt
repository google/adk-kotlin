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

/** Usage metadata for a generate content request. */
@Serializable
data class UsageMetadata(
  /** The number of tokens in the prompt. */
  val promptTokenCount: Int? = null,
  /** The number of tokens in the candidates. */
  val candidatesTokenCount: Int? = null,
  /** The total number of tokens. */
  val totalTokenCount: Int? = null,
  /** The number of tokens that were part of the model's "thoughts" output, for thinking models. */
  val thoughtsTokenCount: Int? = null,
  /** The number of tokens in tool-execution results provided back to the model as input. */
  val toolUsePromptTokenCount: Int? = null,
  /** The number of tokens served from the cached content (cache read). */
  val cachedContentTokenCount: Int? = null,
  /** A per-modality breakdown of the prompt token count. */
  val promptTokensDetails: List<ModalityTokenCount>? = null,
  /** A per-modality breakdown of the candidates token count. */
  val candidatesTokensDetails: List<ModalityTokenCount>? = null,
  /** A per-modality breakdown of the tool-use prompt token count. */
  val toolUsePromptTokensDetails: List<ModalityTokenCount>? = null,
  /** The traffic type for the request (e.g. "ON_DEMAND", "PROVISIONED_THROUGHPUT"). */
  val trafficType: String? = null,
)
