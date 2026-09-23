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

/** The outcome of executing an [ExecutableCode] part. */
@Serializable
enum class Outcome {
  /** Unspecified status. This value should not be used. */
  OUTCOME_UNSPECIFIED,

  /** Code execution completed successfully. */
  OUTCOME_OK,

  /** Code execution failed. */
  OUTCOME_FAILED,

  /** Code execution ran for too long and was cancelled. */
  OUTCOME_DEADLINE_EXCEEDED,
}
