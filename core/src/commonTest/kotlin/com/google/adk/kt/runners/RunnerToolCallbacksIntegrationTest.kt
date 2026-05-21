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
import com.google.adk.kt.callbacks.AfterToolCallback
import com.google.adk.kt.callbacks.BeforeToolCallback
import com.google.adk.kt.callbacks.CallbackChoice
import com.google.adk.kt.callbacks.OnToolErrorCallback
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.DummyTool
import com.google.adk.kt.testing.modelFunctionCallResponse
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.userMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/**
 * Tool-callback extension points on [LlmAgent] through a real [InMemoryRunner]. Mirrors Python
 * ADK's `flows/llm_flows/test_tool_callbacks.py`.
 *
 * Each scenario uses a two-turn conversation: the model first emits a `FunctionCall`, then a final
 * text answer once it sees the function response. Assertions inspect the merged `FunctionResponse`
 * event the runner persists in the session (the same data the next LLM turn will see).
 */
class RunnerToolCallbacksIntegrationTest {

  /**
   * `Break` from `beforeToolCallback` skips the actual tool and uses the callback's value as the
   * tool response.
   */
  @Test
  fun runAsync_beforeToolCallbackReturnsBreak_shortCircuitsToolAndUsesCallbackResult() = runTest {
    var toolWasInvoked = false
    val agent =
      LlmAgent(
        name = "test-agent",
        model = twoTurnFunctionCallModel("real_tool"),
        tools =
          listOf(
            DummyTool(
              name = "real_tool",
              onRun = { _, _ ->
                toolWasInvoked = true
                mapOf("result" to "from-real-tool")
              },
            )
          ),
        beforeToolCallbacks =
          listOf(
            BeforeToolCallback { _, _, _ ->
              CallbackChoice.Break(mapOf("result" to "from-callback"))
            }
          ),
      )
    val runner = InMemoryRunner(agent = agent)

    val events =
      runner
        .runAsync(
          userId = "user1",
          sessionId = "session1",
          newMessage = userMessage("call the tool"),
        )
        .toList()

    assertFalse(toolWasInvoked)
    val response =
      events.firstOrNull { it.functionResponses().isNotEmpty() }?.functionResponses()?.single()
        ?: fail("expected a function-response event, got: $events")
    assertEquals("real_tool", response.name)
    assertEquals("from-callback", response.response["result"])
  }

  /**
   * `Continue` with mutated args must hand the (modified) args back to the regular execution path
   * so the tool observes the mutation.
   */
  @Test
  fun runAsync_beforeToolCallbackReturnsContinueWithMutatedArgs_toolReceivesMutatedArgs() =
    runTest {
      var observedArg: Any? = null
      val agent =
        LlmAgent(
          name = "test-agent",
          model = twoTurnFunctionCallModel("echo_tool", args = mapOf("query" to "original")),
          tools =
            listOf(
              DummyTool(
                name = "echo_tool",
                onRun = { _, args ->
                  observedArg = args["query"]
                  mapOf("result" to "ok")
                },
              )
            ),
          beforeToolCallbacks =
            listOf(
              BeforeToolCallback { _, _, args ->
                CallbackChoice.Continue(args + ("query" to "mutated"))
              }
            ),
        )
      val runner = InMemoryRunner(agent = agent)

      runner
        .runAsync(userId = "user1", sessionId = "session1", newMessage = userMessage("hi"))
        .toList()

      assertEquals("mutated", observedArg)
    }

  /**
   * `afterToolCallback` transforms the tool's actual return value before it is wrapped into the
   * `FunctionResponse` event.
   */
  @Test
  fun runAsync_afterToolCallback_replacesToolResponseInFunctionResponseEvent() = runTest {
    val agent =
      LlmAgent(
        name = "test-agent",
        model = twoTurnFunctionCallModel("real_tool"),
        tools =
          listOf(DummyTool(name = "real_tool", onRun = { _, _ -> mapOf("result" to "from-tool") })),
        afterToolCallbacks =
          listOf(AfterToolCallback { _, _, _, _ -> mapOf("result" to "from-after-callback") }),
      )
    val runner = InMemoryRunner(agent = agent)

    val events =
      runner
        .runAsync(userId = "user1", sessionId = "session1", newMessage = userMessage("hi"))
        .toList()

    val response =
      events.firstOrNull { it.functionResponses().isNotEmpty() }?.functionResponses()?.single()
        ?: fail("expected a function-response event, got: $events")
    assertEquals("real_tool", response.name)
    assertEquals("from-after-callback", response.response["result"])
  }

  /**
   * If the tool throws and `onToolErrorCallback` returns `Break(fallback)`, the runner converts the
   * failure into a normal `FunctionResponse` and the model still produces its final text.
   */
  @Test
  fun runAsync_onToolErrorCallback_convertsToolExceptionIntoNormalResponse() = runTest {
    val agent =
      LlmAgent(
        name = "test-agent",
        model = twoTurnFunctionCallModel("exploding_tool"),
        tools =
          listOf(
            DummyTool(
              name = "exploding_tool",
              onRun = { _, _ -> throw RuntimeException("tool failure") },
            )
          ),
        onToolErrorCallbacks =
          listOf(
            OnToolErrorCallback { _, _, _, _ ->
              CallbackChoice.Break(mapOf("result" to "recovered"))
            }
          ),
      )
    val runner = InMemoryRunner(agent = agent)

    val events =
      runner
        .runAsync(userId = "user1", sessionId = "session1", newMessage = userMessage("hi"))
        .toList()

    val response =
      events.firstOrNull { it.functionResponses().isNotEmpty() }?.functionResponses()?.single()
        ?: fail("expected a function-response event, got: $events")
    assertEquals("recovered", response.response["result"])
    val finalEvent = events.last()
    assertEquals("test-agent", finalEvent.author)
    assertEquals("Done.", finalEvent.content?.parts?.singleOrNull()?.text)
  }

  /** Without an `onToolErrorCallback`, a tool exception bubbles up through the runner. */
  @Test
  fun runAsync_toolThrowsAndNoErrorCallback_propagatesException() = runTest {
    val agent =
      LlmAgent(
        name = "test-agent",
        model = twoTurnFunctionCallModel("exploding_tool"),
        tools =
          listOf(
            DummyTool(name = "exploding_tool", onRun = { _, _ -> throw RuntimeException("boom") })
          ),
      )
    val runner = InMemoryRunner(agent = agent)

    try {
      runner
        .runAsync(userId = "user1", sessionId = "session1", newMessage = userMessage("hi"))
        .toList()
      fail("expected RuntimeException to propagate")
    } catch (e: RuntimeException) {
      assertTrue(
        (e.message ?: "").contains("boom") ||
          generateSequence<Throwable>(e.cause) { it.cause }
            .any { (it.message ?: "").contains("boom") }
      )
    }
  }

  /**
   * A `DummyModel` that emits a `FunctionCall` on turn 1 and a fixed final text on turn 2. Useful
   * for any tool-callback scenario where the test only cares about a single tool round.
   */
  private fun twoTurnFunctionCallModel(
    toolName: String,
    args: Map<String, Any> = emptyMap(),
  ): DummyModel =
    DummyModel.createSequential(
      "mock-model",
      listOf(
        modelFunctionCallResponse(toolName, args, id = "call_1"),
        LlmResponse(content = modelMessage("Done.")),
      ),
    )
}
