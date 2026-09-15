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

package com.google.adk.kt.agents

import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.DummyTool
import com.google.adk.kt.testing.TRANSFER_TO_AGENT_RESPONSE_PART
import com.google.adk.kt.testing.modelFunctionCallResponse
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.modelTransferToAgentResponse
import com.google.adk.kt.testing.simplifyEvents
import com.google.adk.kt.testing.transferToAgentCallPart
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

class LlmAgentTurnTest {

  /**
   * Root `MissionControl` transfers to sub-agent `HeartOfGold`, which calls a tool and then
   * answers. Asserts the exact 5-event sequence (transfer call → transfer response → tool call →
   * tool response → final text) and that the tool runs exactly once. Regression guard for the
   * `tracedFlow` fix: the old `channelFlow + send` design could let the sub-agent's second turn see
   * stale conversation history and re-emit the tool call.
   */
  @Test
  fun runAsync_rootAgentDelegatesToSubAgentThatInvokesTool_emitsOrderedEventSequence() = runTest {
    var toolCallCount = 0
    val returnRandomNumberTool =
      DummyTool(
        name = "return_random_number",
        description = "Returns a random number.",
        onRun = { _, _ ->
          toolCallCount++
          mapOf("number" to 42)
        },
      )
    // Tool call on first turn; final text once a function response is in the conversation.
    val subModel =
      DummyModel("sub-model") { request ->
        flow {
          val lastContent = request.contents.lastOrNull()
          val isFunctionResponse = lastContent?.parts?.any { it.functionResponse != null } == true
          if (!isFunctionResponse) {
            emit(
              modelFunctionCallResponse(
                name = "return_random_number",
                args = emptyMap(),
                id = "call_1",
              )
            )
          } else {
            emit(LlmResponse(content = modelMessage("The answer is 42.")))
          }
        }
      }
    // Root agent model: unconditionally delegates to the HeartOfGold sub-agent.
    val rootModel =
      DummyModel("root-model") {
        flow { emit(modelTransferToAgentResponse("HeartOfGold", id = "transfer_call_1")) }
      }
    val heartOfGoldAgent =
      LlmAgent(
        name = "HeartOfGold",
        description = "Sub-agent that knows about random numbers.",
        model = subModel,
        tools = listOf(returnRandomNumberTool),
      )
    val missionControlAgent =
      LlmAgent(
        name = "MissionControl",
        description = "Root agent that transfers to HeartOfGold.",
        subAgents = listOf(heartOfGoldAgent),
        model = rootModel,
      )
    val runner = InMemoryRunner(agent = missionControlAgent)
    val userMessage = userMessage("What's the answer?")

    val flowEvents =
      runner
        .runAsync(userId = "user1", sessionId = "session1", newMessage = userMessage)
        .toList()
        .filter { it.author != Role.USER }

    assertEquals("tool should have been invoked exactly once", 1, toolCallCount)
    assertEquals(
      listOf(
        "MissionControl" to transferToAgentCallPart("HeartOfGold"),
        "MissionControl" to TRANSFER_TO_AGENT_RESPONSE_PART,
        "HeartOfGold" to Part(functionCall = FunctionCall("return_random_number")),
        "HeartOfGold" to
          Part(
            functionResponse =
              FunctionResponse("return_random_number", response = mapOf("number" to 42))
          ),
        "HeartOfGold" to "The answer is 42.",
      ),
      simplifyEvents(flowEvents),
    )
  }

  /**
   * `errorCode` and `customMetadata` exist on both [LlmResponse] and `Event`, so finalizing the
   * model-response event must carry them over.
   */
  @Test
  fun runAsync_withErrorCodeAndCustomMetadata_propagatesToFinalEvent() = runBlocking {
    val model =
      DummyModel("metadata-model") {
        flow {
          emit(
            LlmResponse(
              content = modelMessage("Answered."),
              errorCode = "SAFETY",
              customMetadata = mapOf("trace_id" to "abc123", "attempt" to 2),
            )
          )
        }
      }
    val agent = LlmAgent(name = "MetadataAgent", description = "Echoes metadata.", model = model)
    val runner = InMemoryRunner(agent = agent)

    val modelEvent =
      runner
        .runAsync(userId = "user1", sessionId = "session1", newMessage = userMessage("Hello?"))
        .toList()
        .single { it.author == agent.name }

    assertEquals("SAFETY", modelEvent.errorCode)
    assertEquals(mapOf("trace_id" to "abc123", "attempt" to 2), modelEvent.customMetadata)
  }

  @Test
  fun runAsync_streamingStep_eachEmittedEventGetsItsOwnActionsSnapshot() = runBlocking {
    // Each emitted event must carry its own EventActions, isolating already-emitted partials.
    val streamingModel =
      DummyModel(
        "streaming-model",
        listOf(
          flowOf(
            LlmResponse(content = modelMessage("a"), partial = true),
            LlmResponse(content = modelMessage("b"), partial = true),
            LlmResponse(content = modelMessage("ab")),
          )
        ),
      )
    val agent = LlmAgent(name = "test-agent", model = streamingModel)
    val runner = InMemoryRunner(agent = agent)

    val modelEvents =
      runner
        .runAsync(
          userId = "user1",
          sessionId = "session1",
          newMessage = userMessage("hi"),
          runConfig = RunConfig(streamingMode = StreamingMode.SSE),
        )
        .toList()
        .filter { it.author == "test-agent" }

    // Two partials followed by the aggregated final event, each with a distinct EventActions.
    assertEquals(3, modelEvents.size)
    assertNotSame(modelEvents[0].actions, modelEvents[1].actions)
    assertNotSame(modelEvents[1].actions, modelEvents[2].actions)
    assertNotSame(modelEvents[0].actions, modelEvents[2].actions)
  }
}
