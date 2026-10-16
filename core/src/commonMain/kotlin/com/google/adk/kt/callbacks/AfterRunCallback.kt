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

package com.google.adk.kt.callbacks

import com.google.adk.kt.agents.InvocationContext

/**
 * Callback executed after an ADK runner run has completed or its caller stopped collecting early.
 */
interface AfterRunCallback : Callback {
  /**
   * Invoked after the runner run has finished, or after the caller stopped collecting early.
   *
   * @param invocationContext The context for the entire invocation.
   */
  suspend fun call(invocationContext: InvocationContext)

  companion object {
    // Workaround for problems when automatic SAM conversion code generated with gradle kotlin
    // plugin introduces an invalid method name in class files for functional interfaces with
    // abstract suspend methods.
    // This manual override permits the class to be used as if it were a functional interface with
    // SAM conversion (eg. AfterRunCallback { invocationContext -> ... }).

    operator fun invoke(
      block: suspend (invocationContext: InvocationContext) -> Unit
    ): AfterRunCallback =
      object : AfterRunCallback {
        override suspend fun call(invocationContext: InvocationContext) = block(invocationContext)
      }
  }
}
