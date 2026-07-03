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
@file:OptIn(ExperimentalResumabilityFeature::class)

package com.google.adk.kt.runners

import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.ResumabilityConfig
import com.google.adk.kt.annotations.ExperimentalResumabilityFeature
import com.google.adk.kt.apps.App
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.modelFunctionCallResponse
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.userFunctionResponse
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.tools.GetUserChoiceTool
import com.google.adk.kt.tools.RequestInputTool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/**
 * End-to-end tests for [RequestInputTool] and [GetUserChoiceTool] through the Runner: each returns
 * `Unit`, so its function-response is suppressed and the long-running function-call event ends the
 * turn (id in `longRunningToolIds`); the invocation then resumes on an injected `FunctionResponse`
 * without re-running the tool.
 */
class HitlBuiltinToolsIntegrationTest {

  @Test
  fun requestInput_pausesInvocationAndResumesWithInjectedResponse() = runTest {
    val callId = "request_input_call"
    var modelInvocations = 0
    val agent =
      LlmAgent(
        name = AGENT_NAME,
        model =
          DummyModel("model") {
            modelInvocations++
            flowOf(
              if (modelInvocations == 1) {
                modelFunctionCallResponse(
                  "adk_request_input",
                  args = mapOf("message" to "What is your name?"),
                  id = callId,
                )
              } else {
                LlmResponse(content = modelMessage("Hello, Alice!"))
              }
            )
          },
        tools = listOf(RequestInputTool()),
      )
    val runner =
      InMemoryRunner(
        App(APP_NAME, agent, resumabilityConfig = ResumabilityConfig(isResumable = true))
      )

    // Turn 1: the model requests input; the invocation must pause.
    val pauseEvents =
      runner
        .runAsync(userId = USER_ID, sessionId = SESSION_ID, newMessage = userMessage("hi"))
        .toList()

    val fcEvent = pauseEvents.first { it.functionCalls().any { call -> call.id == callId } }
    assertEquals(setOf(callId), fcEvent.longRunningToolIds)
    // The tool returns Unit, so the function-response event is suppressed; only the long-running
    // function-call event is emitted, and it ends the turn without re-invoking the model.
    assertTrue(
      pauseEvents.none { it.functionResponses().any { resp -> resp.id == callId } },
      "a Unit-returning long-running tool must not emit a function-response event",
    )
    assertEquals(1, modelInvocations, "the invocation must pause without re-invoking the model")
    assertTrue(pauseEvents.none { it.actions.endOfAgent }, "endOfAgent suppressed while paused")

    // Turn 2: the caller injects the answer; the invocation resumes and completes. The long-running
    // tool is not re-executed (which would have deferred again and paused instead of finishing).
    val resumeEvents =
      runner
        .runAsync(
          userId = USER_ID,
          sessionId = SESSION_ID,
          newMessage =
            userFunctionResponse(
              name = "adk_request_input",
              id = callId,
              response = mapOf("value" to "Alice"),
            ),
        )
        .toList()

    val finalText =
      resumeEvents
        .lastOrNull {
          it.author == AGENT_NAME && it.content?.parts?.any { p -> p.text != null } == true
        }
        ?.content
        ?.parts
        ?.singleOrNull()
        ?.text
    assertEquals("Hello, Alice!", finalText)
    assertEquals(2, modelInvocations, "resume must re-invoke the model exactly once")
  }

  @Test
  fun getUserChoice_suppressesResponseAndEndsTurn() = runTest {
    val callId = "get_user_choice_call"
    var modelInvocations = 0
    val agent =
      LlmAgent(
        name = AGENT_NAME,
        model =
          DummyModel("model") {
            modelInvocations++
            flowOf(
              modelFunctionCallResponse(
                "get_user_choice",
                args = mapOf("options" to listOf("red", "green", "blue")),
                id = callId,
              )
            )
          },
        tools = listOf(GetUserChoiceTool()),
      )
    val runner = InMemoryRunner(agent = agent)

    val events =
      runner
        .runAsync(
          userId = USER_ID,
          sessionId = SESSION_ID,
          newMessage = userMessage("pick a color"),
        )
        .toList()

    val fcEvent = events.first { it.functionCalls().any { call -> call.id == callId } }
    assertEquals(setOf(callId), fcEvent.longRunningToolIds)
    // get_user_choice returns Unit, so the function-response event is suppressed; the long-running
    // function-call event ends the turn without re-invoking the model. (The tool's
    // skipSummarization
    // is covered by GetUserChoiceToolTest.)
    assertTrue(
      events.none { it.functionResponses().any { resp -> resp.id == callId } },
      "a Unit-returning long-running tool must not emit a function-response event",
    )
    assertEquals(1, modelInvocations)
  }

  private companion object {
    const val APP_NAME = "app"
    const val USER_ID = "u"
    const val SESSION_ID = "s"
    const val AGENT_NAME = "agent"
  }
}
