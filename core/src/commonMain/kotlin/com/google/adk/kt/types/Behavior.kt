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

/** Whether a live model waits for a function's response before it continues the conversation. */
@Serializable
enum class Behavior {
  /** Unspecified; the model keeps its current behavior for the function. */
  UNSPECIFIED,

  /** The model waits for the function's response before it continues. */
  BLOCKING,

  /** The model keeps the conversation going and handles each response when it arrives. */
  NON_BLOCKING,
}
