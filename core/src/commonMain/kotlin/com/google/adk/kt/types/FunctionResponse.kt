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

import kotlinx.serialization.Contextual
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable

/**
 * Represents a function response.
 *
 * @property name The name of the function.
 * @property response The response from the function.
 * @property id The unique identifier for this function response.
 */
@Serializable
data class FunctionResponse(
  val name: String,
  // Always emit response (even empty {}) to match the genai/Python golden shape.
  @EncodeDefault(EncodeDefault.Mode.ALWAYS)
  val response: Map<String, @Contextual Any?> = emptyMap(),
  val id: String? = null,
)
