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
import com.google.adk.kt.callbacks.AfterToolCallback
import com.google.adk.kt.callbacks.BeforeModelCallback
import com.google.adk.kt.callbacks.BeforeToolCallback
import com.google.adk.kt.callbacks.CallbackChoice
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.DummyTool
import com.google.adk.kt.testing.modelFunctionCallResponse
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/**
 * Verifies the chained callback lists on [LlmAgent] (`beforeToolCallbacks`, `afterToolCallbacks`,
 * `beforeModelCallbacks`, `afterModelCallbacks`) execute in declared order and that each pipeline's
 * short-circuit semantics are honoured. Mirrors Python ADK's `test_before_tool_callbacks_chain` /
 * `test_after_tool_callbacks_chain` in `flows/llm_flows/test_async_tool_callbacks.py` and
 * `test_before_model_callbacks_chain` / `test_after_model_callbacks_chain` in
 * `agents/test_model_callback_chain.py`.
 *
 * Kotlin exposes callbacks as plural `List<Callback>` properties, unlike Python's single-callback
 * fields, so plugin and agent authors rely on declared order being honoured, `Continue(value)`
 * chaining its value to the next callback, and the first `Break(value)` short-circuiting the rest
 * of the chain. Single-callback scenarios are covered by [RunnerToolCallbacksIntegrationTest] and
 * [RunnerModelCallbacksIntegrationTest]; this file focuses on multi-callback chain semantics.
 */
class CallbackChainOrderingIntegrationTest {

  /**
   * Two `BeforeToolCallback`s in declared order, both returning `Continue(mutated)`. The second
   * callback observes the first callback's mutated args, and the tool observes the second
   * callback's mutated args.
   */
  @Test
  fun runAsync_beforeToolCallbackChain_eachCallbackSeesPriorMutationAndToolSeesFinal() = runTest {
    val invocationOrder = mutableListOf<String>()
    var argsObservedByTool: Map<String, Any>? = null
    val agent =
      llmAgentWithFunctionThenText(
        toolName = "t",
        tool =
          DummyTool(
            name = "t",
            onRun = { _, args ->
              argsObservedByTool = args
              mapOf("ok" to true)
            },
          ),
        beforeToolCallbacks =
          listOf(
            BeforeToolCallback { _, _, args ->
              invocationOrder += "first"
              CallbackChoice.Continue(args + ("from_first" to "yes"))
            },
            BeforeToolCallback { _, _, args ->
              invocationOrder += "second"
              assertEquals("yes", args["from_first"])
              CallbackChoice.Continue(args + ("from_second" to "yes"))
            },
          ),
      )
    val runner = InMemoryRunner(agent = agent)

    runner.runAsync(userId = "u", sessionId = "s", newMessage = userMessage("go")).toList()

    assertEquals(listOf("first", "second"), invocationOrder)
    assertEquals("yes", argsObservedByTool?.get("from_first"))
    assertEquals("yes", argsObservedByTool?.get("from_second"))
  }

  /**
   * Three `BeforeToolCallback`s where the middle one returns `Break(value)`. The first runs, the
   * middle short-circuits, the third must NOT run, and the published response carries the middle
   * callback's payload.
   */
  @Test
  fun runAsync_beforeToolCallbackChain_middleBreakShortCircuitsRemainingCallbacks() = runTest {
    val invocationOrder = mutableListOf<String>()
    var toolWasInvoked = false
    val agent =
      llmAgentWithFunctionThenText(
        toolName = "t",
        tool =
          DummyTool(
            name = "t",
            onRun = { _, _ ->
              toolWasInvoked = true
              mapOf("from" to "real-tool")
            },
          ),
        beforeToolCallbacks =
          listOf(
            BeforeToolCallback { _, _, args ->
              invocationOrder += "first"
              CallbackChoice.Continue(args)
            },
            BeforeToolCallback { _, _, _ ->
              invocationOrder += "middle"
              CallbackChoice.Break(mapOf("from" to "middle-callback"))
            },
            BeforeToolCallback { _, _, args ->
              invocationOrder += "third"
              CallbackChoice.Continue(args)
            },
          ),
      )
    val runner = InMemoryRunner(agent = agent)

    val events =
      runner.runAsync(userId = "u", sessionId = "s", newMessage = userMessage("go")).toList()

    assertEquals(listOf("first", "middle"), invocationOrder)
    assertEquals(false, toolWasInvoked)
    val responseMap =
      events
        .firstOrNull { it.functionResponses().isNotEmpty() }
        ?.functionResponses()
        ?.firstOrNull()
        ?.response
    assertEquals("middle-callback", responseMap?.get("from"))
  }

  /**
   * Two `AfterToolCallback`s in declared order. Pure transformation chain (no break semantics): the
   * second observes the first's mutation, and the published response carries both.
   */
  @Test
  fun runAsync_afterToolCallbackChain_eachCallbackSeesPriorMutationAndResponseCarriesFinal() =
    runTest {
      val invocationOrder = mutableListOf<String>()
      val agent =
        llmAgentWithFunctionThenText(
          toolName = "t",
          tool = DummyTool(name = "t", onRun = { _, _ -> mapOf("from_tool" to "yes") }),
          afterToolCallbacks =
            listOf(
              AfterToolCallback { _, _, _, result ->
                invocationOrder += "first"
                result + ("from_first" to "yes")
              },
              AfterToolCallback { _, _, _, result ->
                invocationOrder += "second"
                assertEquals("yes", result["from_first"])
                result + ("from_second" to "yes")
              },
            ),
        )
      val runner = InMemoryRunner(agent = agent)

      val events =
        runner.runAsync(userId = "u", sessionId = "s", newMessage = userMessage("go")).toList()

      assertEquals(listOf("first", "second"), invocationOrder)
      val responseMap =
        events
          .firstOrNull { it.functionResponses().isNotEmpty() }
          ?.functionResponses()
          ?.firstOrNull()
          ?.response
      assertEquals("yes", responseMap?.get("from_tool"))
      assertEquals("yes", responseMap?.get("from_first"))
      assertEquals("yes", responseMap?.get("from_second"))
    }

  /**
   * Two `BeforeModelCallback`s in declared order, both returning `Continue(mutatedRequest)`. The
   * second callback observes the first's mutated request, and the model observes the second's
   * mutation.
   */
  @Test
  fun runAsync_beforeModelCallbackChain_eachCallbackSeesPriorMutationAndModelSeesFinal() = runTest {
    val invocationOrder = mutableListOf<String>()
    var systemTextSeenByModel = ""
    val agent =
      LlmAgent(
        name = "agent",
        model =
          DummyModel("model") { request ->
            systemTextSeenByModel =
              request.config.systemInstruction
                ?.parts
                ?.mapNotNull { it.text }
                ?.joinToString(" ")
                .orEmpty()
            flowOf(LlmResponse(content = modelMessage("ok")))
          },
        beforeModelCallbacks =
          listOf(
            BeforeModelCallback { _, request ->
              invocationOrder += "first"
              CallbackChoice.Continue(
                request.appendInstructions(Content(parts = listOf(Part(text = "from-first"))))
              )
            },
            BeforeModelCallback { _, request ->
              invocationOrder += "second"
              val systemText =
                request.config.systemInstruction
                  ?.parts
                  ?.mapNotNull { it.text }
                  ?.joinToString(" ")
                  .orEmpty()
              assertTrue(
                systemText.contains("from-first"),
                "second before-model callback must see request mutated by the first; got " +
                  "'$systemText'",
              )
              CallbackChoice.Continue(
                request.appendInstructions(Content(parts = listOf(Part(text = "from-second"))))
              )
            },
          ),
      )
    val runner = InMemoryRunner(agent = agent)

    runner.runAsync(userId = "u", sessionId = "s", newMessage = userMessage("hello")).toList()

    assertEquals(listOf("first", "second"), invocationOrder)
    assertTrue(
      systemTextSeenByModel.contains("from-first") && systemTextSeenByModel.contains("from-second"),
      "model must see system instructions from both callbacks; got '$systemTextSeenByModel'",
    )
  }

  /**
   * Two `AfterModelCallback`s in declared order. The second observes the first's mutated response,
   * and the published event carries the second's mutation.
   */
  @Test
  fun runAsync_afterModelCallbackChain_eachCallbackSeesPriorMutationAndEventCarriesFinal() =
    runTest {
      val invocationOrder = mutableListOf<String>()
      val agent =
        LlmAgent(
          name = "agent",
          model = DummyModel("model") { flowOf(LlmResponse(content = modelMessage("base"))) },
          afterModelCallbacks =
            listOf(
              AfterModelCallback { _, response ->
                invocationOrder += "first"
                val originalText = response.content?.parts?.singleOrNull()?.text.orEmpty()
                response.copy(content = modelMessage("$originalText|first"))
              },
              AfterModelCallback { _, response ->
                invocationOrder += "second"
                val firstText = response.content?.parts?.singleOrNull()?.text.orEmpty()
                assertTrue(
                  firstText.endsWith("|first"),
                  "second after-model callback must see response mutated by the first; got " +
                    "'$firstText'",
                )
                response.copy(content = modelMessage("$firstText|second"))
              },
            ),
        )
      val runner = InMemoryRunner(agent = agent)

      val events =
        runner.runAsync(userId = "u", sessionId = "s", newMessage = userMessage("hello")).toList()

      assertEquals(listOf("first", "second"), invocationOrder)
      val finalText =
        events.firstOrNull { it.author == "agent" }?.content?.parts?.singleOrNull()?.text
      assertEquals("base|first|second", finalText)
    }

  /** A 2-turn agent: turn 1 emits a single FunctionCall, turn 2 emits final text. */
  private fun llmAgentWithFunctionThenText(
    toolName: String,
    tool: DummyTool,
    beforeToolCallbacks: List<BeforeToolCallback> = emptyList(),
    afterToolCallbacks: List<AfterToolCallback> = emptyList(),
  ): LlmAgent =
    LlmAgent(
      name = "agent",
      model =
        DummyModel.createSequential(
          "model",
          listOf(
            modelFunctionCallResponse(toolName, id = "call_1"),
            LlmResponse(content = modelMessage("done")),
          ),
        ),
      tools = listOf(tool),
      beforeToolCallbacks = beforeToolCallbacks,
      afterToolCallbacks = afterToolCallbacks,
    )
}
