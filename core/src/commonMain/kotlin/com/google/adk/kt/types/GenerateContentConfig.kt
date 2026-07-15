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

/** Configuration for generating content. */
@Serializable
data class GenerateContentConfig(
  val tools: List<Tool>? = null,
  val labels: Map<String, String>? = null,
  val systemInstruction: Content? = null,
  val temperature: Float? = null,
  val topP: Float? = null,
  val topK: Int? = null,
  val candidateCount: Int? = null,
  val maxOutputTokens: Int? = null,
  val stopSequences: List<String>? = null,
  val responseMimeType: String? = null,
  val responseSchema: Schema? = null,
  val thinkingConfig: ThinkingConfig? = null,
  val toolConfig: ToolConfig? = null,
  val safetySettings: List<SafetySetting>? = null,
  val mediaResolution: MediaResolution? = null,
  val serviceTier: ServiceTier? = null,
  val presencePenalty: Float? = null,
  val frequencyPenalty: Float? = null,
  val responseLogprobs: Boolean? = null,
  val routingConfig: GenerationConfigRoutingConfig? = null,
)
