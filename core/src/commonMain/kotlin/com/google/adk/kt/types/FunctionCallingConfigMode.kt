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

/** The mode controlling how the model chooses whether to emit function calls. */
@Serializable
enum class FunctionCallingConfigMode {
  /** Unspecified mode. This value should not be used. */
  MODE_UNSPECIFIED,

  /** The model decides whether to predict a function call or a natural language response. */
  AUTO,

  /** The model is constrained to always predict a function call. */
  ANY,

  /** The model will not predict any function calls. */
  NONE,

  /** The model predicts either a function call or a natural language response, validated. */
  VALIDATED,
}
