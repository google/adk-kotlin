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

package com.google.adk.kt.testing

import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.Model
import kotlin.jvm.JvmStatic
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf

/**
 * A configurable dummy implementation of [Model] for testing workflows.
 *
 * This fixture simplifies agent execution tests by mimicking language model interactions without
 * triggering real network or LLM calls.
 *
 * @param name The model's identifier.
 * @param generateContentFlow A lambda allowing the test to control the stream of [LlmResponse]
 *   objects emitted by the [generateContent] call. Because it defaults to [emptyFlow], developers
 *   often inject a mock flow with predefined responses:
 * ```kotlin
 * DummyModel("mock_model") { flowOf(LlmResponse(...)) }
 * ```
 */
class DummyModel(
  override val name: String,
  val generateContentFlow: (LlmRequest) -> Flow<LlmResponse> = { emptyFlow() },
) : Model {

  /**
   * Creates a [DummyModel] that returns a sequence of [Flow]s on subsequent calls.
   *
   * @param name The model's identifier.
   * @param flows The list of flows to return in order.
   */
  constructor(
    name: String,
    flows: List<Flow<LlmResponse>>,
  ) : this(
    name,
    object : (LlmRequest) -> Flow<LlmResponse> {
      private var callCount = 0

      override fun invoke(request: LlmRequest): Flow<LlmResponse> =
        flows.getOrElse(callCount++) { emptyFlow() }
    },
  )

  override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> =
    generateContentFlow(request)

  companion object {
    /**
     * Creates a [DummyModel] that returns a sequence of [LlmResponse]s, each wrapped in its own
     * [Flow], on subsequent calls.
     */
    @JvmStatic
    fun createSequential(name: String, responses: List<LlmResponse>): DummyModel =
      DummyModel(name, responses.map { flowOf(it) })
  }
}
