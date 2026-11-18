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

package com.google.adk.kt.processors

import com.google.adk.kt.agents.CallbackContext
import com.google.adk.kt.events.Event
import com.google.adk.kt.models.LlmResponse

/**
 * Defines a step in the pipeline that executes after an LLM request completes.
 *
 * Implementations of this interface process the raw [LlmResponse] received from the model. They may
 * extract information, handle agent transfer signals, or format the response. Like request
 * processors, they can emit [Event]s directly to the caller.
 */
internal interface LlmResponseProcessor {
  /**
   * Executes the processor logic.
   *
   * @param context The [CallbackContext] providing current execution state.
   * @param response The [LlmResponse] returned by the model.
   * @param emitEvent A callback to emit [Event]s to the caller during this processing step.
   */
  suspend fun process(
    context: CallbackContext,
    response: LlmResponse,
    emitEvent: suspend (Event) -> Unit = {},
  ): LlmResponse = response
}
