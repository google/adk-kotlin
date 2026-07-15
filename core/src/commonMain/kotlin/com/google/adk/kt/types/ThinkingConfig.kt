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

/**
 * The thinking features configuration.
 *
 * @property includeThoughts Indicates whether to include thoughts in the response. If true,
 *   thoughts are returned only if the model supports thought and thoughts are available.
 * @property thinkingBudget Indicates the thinking budget in tokens. 0 is DISABLED. -1 is AUTOMATIC.
 *   The default values and allowed ranges are model dependent.
 * @property thinkingLevel Controls the maximum depth of the model's internal reasoning process
 *   before it produces a response. If not specified, the default is HIGH. Recommended for Gemini 3
 *   or later models. Use with earlier models results in an error.
 */
@Serializable
data class ThinkingConfig(
  val includeThoughts: Boolean? = null,
  val thinkingBudget: Int? = null,
  val thinkingLevel: ThinkingLevel? = null,
)
