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

/** Configuration for how the model uses the provided function declarations. */
data class FunctionCallingConfig(
  /** Function names the model is allowed to call. */
  val allowedFunctionNames: List<String>? = null,
  /**
   * When true, a single function call's arguments are streamed across chunks (partialArgs) rather
   * than returned all at once. Vertex-only; not supported by the Gemini Developer API.
   */
  val streamFunctionCallArguments: Boolean? = null,
)
