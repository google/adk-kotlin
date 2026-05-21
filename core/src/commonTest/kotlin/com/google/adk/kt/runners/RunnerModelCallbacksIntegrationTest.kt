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
package com.google.adk.kt.runners

import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.callbacks.AfterModelCallback
import com.google.adk.kt.callbacks.BeforeModelCallback
import com.google.adk.kt.callbacks.CallbackChoice
import com.google.adk.kt.callbacks.OnModelErrorCallback
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.Model
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.userMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/**
 * End-to-end tests for the model-callback extension points on [LlmAgent] through a real
 * [InMemoryRunner]. Mirrors Python ADK's `agents/test_llm_agent_callbacks.py` and
 * `flows/llm_flows/test_model_callbacks.py`.
 */
class RunnerModelCallbacksIntegrationTest {

  /**
   * `Break` from `beforeModelCallback` must skip the model call entirely. The underlying model is
   * never invoked; the callback's `LlmResponse` is the only model output.
   */
  @Test
  fun runAsync_beforeModelCallbackReturnsBreak_shortCircuitsModelCall() = runTest {
    var modelCalls = 0
    val agent =
      LlmAgent(
        name = "test-agent",
        model =
          DummyModel("mock-model") {
            modelCalls++
            flowOf(LlmResponse(content = modelMessage("from-real-model")))
          },
        beforeModelCallbacks =
          listOf(
            BeforeModelCallback { _, _ ->
              CallbackChoice.Break(LlmResponse(content = modelMessage("from-callback")))
            }
          ),
      )
    val runner = InMemoryRunner(agent = agent)

    val events =
      runner
        .runAsync(userId = "user1", sessionId = "session1", newMessage = userMessage("hi"))
        .toList()

    assertEquals(0, modelCalls)
    val modelEvent =
      events.firstOrNull { it.author == "test-agent" } ?: fail("expected an agent-authored event")
    assertEquals("from-callback", modelEvent.content?.parts?.singleOrNull()?.text)
  }

  /**
   * `Continue` with a mutated [LlmRequest] must hand the modified request to the actual model.
   * Captures the request the model receives and asserts on the mutation.
   */
  @Test
  fun runAsync_beforeModelCallbackReturnsContinueWithMutatedRequest_modelSeesMutation() = runTest {
    var capturedRequest: LlmRequest? = null
    val capturingModel =
      object : Model {
        override val name = "capturing-model"

        override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> =
          flow {
            capturedRequest = request
            emit(LlmResponse(content = modelMessage("ok")))
          }
      }
    val injected = userMessage("INJECTED")
    val agent =
      LlmAgent(
        name = "test-agent",
        model = capturingModel,
        beforeModelCallbacks =
          listOf(
            BeforeModelCallback { _, request ->
              CallbackChoice.Continue(request.appendContent(injected))
            }
          ),
      )
    val runner = InMemoryRunner(agent = agent)

    runner
      .runAsync(userId = "user1", sessionId = "session1", newMessage = userMessage("original"))
      .toList()

    val request = capturedRequest ?: fail("model should have been called once")
    assertTrue(request.contents.any { it.parts.any { p -> p.text == "INJECTED" } })
  }

  /** `afterModelCallback` must replace the model's response before it is published as an event. */
  @Test
  fun runAsync_afterModelCallback_replacesModelResponse() = runTest {
    val agent =
      LlmAgent(
        name = "test-agent",
        model =
          DummyModel("mock-model") {
            flowOf(LlmResponse(content = modelMessage("from-real-model")))
          },
        afterModelCallbacks =
          listOf(
            AfterModelCallback { _, _ ->
              LlmResponse(content = modelMessage("from-after-callback"))
            }
          ),
      )
    val runner = InMemoryRunner(agent = agent)

    val events =
      runner
        .runAsync(userId = "user1", sessionId = "session1", newMessage = userMessage("hi"))
        .toList()

    val modelEvent =
      events.firstOrNull { it.author == "test-agent" } ?: fail("expected an agent-authored event")
    assertEquals("from-after-callback", modelEvent.content?.parts?.singleOrNull()?.text)
  }

  /**
   * If the model throws, `Break` from `onModelErrorCallback` must convert the error into a normal
   * model event.
   */
  @Test
  fun runAsync_onModelErrorCallbackReturnsBreak_modelErrorBecomesNormalResponse() = runTest {
    val agent =
      LlmAgent(
        name = "test-agent",
        model =
          object : Model {
            override val name = "failing-model"

            override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> =
              flow {
                throw RuntimeException("model boom")
              }
          },
        onModelErrorCallbacks =
          listOf(
            OnModelErrorCallback { _, _, _ ->
              CallbackChoice.Break(LlmResponse(content = modelMessage("recovered")))
            }
          ),
      )
    val runner = InMemoryRunner(agent = agent)

    val events =
      runner
        .runAsync(userId = "user1", sessionId = "session1", newMessage = userMessage("hi"))
        .toList()

    val modelEvent =
      events.firstOrNull { it.author == "test-agent" } ?: fail("expected a recovered model event")
    assertEquals("recovered", modelEvent.content?.parts?.singleOrNull()?.text)
  }

  /** Without an `onModelErrorCallback`, a model exception must propagate up through the runner. */
  @Test
  fun runAsync_modelThrowsWithoutErrorCallback_propagatesException() = runTest {
    val agent =
      LlmAgent(
        name = "test-agent",
        model =
          object : Model {
            override val name = "failing-model"

            override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> =
              flow {
                throw RuntimeException("model boom")
              }
          },
      )
    val runner = InMemoryRunner(agent = agent)

    var threw = false
    try {
      runner
        .runAsync(userId = "user1", sessionId = "session1", newMessage = userMessage("hi"))
        .toList()
    } catch (e: RuntimeException) {
      threw = true
      assertTrue(
        (e.message ?: "").contains("model boom") ||
          generateSequence<Throwable>(e.cause) { it.cause }
            .any { (it.message ?: "").contains("model boom") }
      )
    }
    assertTrue(threw)
  }
}
