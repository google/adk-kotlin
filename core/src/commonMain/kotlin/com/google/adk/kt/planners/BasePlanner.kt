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

package com.google.adk.kt.planners

import com.google.adk.kt.agents.CallbackContext
import com.google.adk.kt.agents.ReadonlyContext
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.types.Part

/**
 * A planner for an agent.
 *
 * A planner shapes how an agent thinks about a query: it contributes an instruction to the request
 * on the way out, and gets to rewrite the parts of the response on the way back.
 */
interface BasePlanner {
  /**
   * Builds the system instruction to be appended to the LLM request for planning.
   *
   * @param readonlyContext The readonly context of the invocation.
   * @param llmRequest The LLM request. Readonly.
   * @return The planning system instruction, or `null` if no instruction is needed.
   */
  fun buildPlanningInstruction(readonlyContext: ReadonlyContext, llmRequest: LlmRequest): String?

  /**
   * Processes the LLM response for planning.
   *
   * @param callbackContext The callback context of the invocation.
   * @param responseParts The LLM response parts. Readonly.
   * @return The processed response parts, or `null` if no processing is needed.
   */
  fun processPlanningResponse(
    callbackContext: CallbackContext,
    responseParts: List<Part>,
  ): List<Part>?
}
