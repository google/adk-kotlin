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
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.DummyTool
import com.google.adk.kt.testing.ResumableEvents.END_OF_AGENT
import com.google.adk.kt.testing.TRANSFER_TO_AGENT_RESPONSE_PART
import com.google.adk.kt.testing.modelFunctionCallResponse
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.modelTransferToAgentResponse
import com.google.adk.kt.testing.simplifyResumableEvents
import com.google.adk.kt.testing.transferToAgentCallPart
import com.google.adk.kt.testing.userFunctionResponse
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.Part
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/**
 * Edge cases of resuming invocations through the runner by `invocationId`, ported from Python ADK
 * 1.x `v1/tests/unittests/runners/test_resume_invocation.py`.
 */
class ResumeInvocationTest {

  @Test
  fun resumeInvocation_thatStartedFromSubAgent() = runTest {
    val subAgent =
      LlmAgent(
        name = "sub_agent",
        model =
          DummyModel.createSequential(
            "sub",
            listOf(
              LlmResponse(content = modelMessage("first response from sub_agent")),
              LlmResponse(content = modelMessage("second response from sub_agent")),
              LlmResponse(content = modelMessage("third response from sub_agent")),
            ),
          ),
      )
    val rootAgent =
      LlmAgent(
        name = "root_agent",
        model =
          DummyModel.createSequential("root", listOf(modelTransferToAgentResponse("sub_agent"))),
        subAgents = listOf(subAgent),
      )
    val runner =
      InMemoryRunner(agent = rootAgent, resumabilityConfig = ResumabilityConfig(isResumable = true))

    // Invocation 1: starts at root and transfers to sub_agent.
    val inv1 =
      runner.runAsync(USER_ID, SESSION_ID, newMessage = userMessage("test user query")).toList()
    assertEquals(
      listOf(
        "root_agent" to transferToAgentCallPart("sub_agent"),
        "root_agent" to TRANSFER_TO_AGENT_RESPONSE_PART,
        "sub_agent" to "first response from sub_agent",
        "sub_agent" to END_OF_AGENT,
        "root_agent" to END_OF_AGENT,
      ),
      simplifyResumableEvents(inv1),
    )

    // Invocation 2: starts directly at sub_agent.
    val inv2 =
      runner.runAsync(USER_ID, SESSION_ID, newMessage = userMessage("test user query 2")).toList()
    assertEquals(
      listOf("sub_agent" to "second response from sub_agent", "sub_agent" to END_OF_AGENT),
      simplifyResumableEvents(inv2),
    )

    // Re-running an already-final invocation (no new message) is a no-op.
    val inv2Id = inv2.first().invocationId
    val noop = runner.runAsync(USER_ID, SESSION_ID, invocationId = inv2Id).toList()
    assertTrue(noop.isEmpty())

    // Simulate pausing on invocation 2: copy all but the last event to a fresh session.
    val key = SessionKey(APP_NAME, USER_ID, SESSION_ID)
    val session = runner.sessionService.getSession(key)!!
    val newKey = SessionKey(APP_NAME, USER_ID, "session-2")
    val newSession = runner.sessionService.createSession(newKey)
    for (event in session.events.dropLast(1)) {
      val unused = runner.sessionService.appendEvent(newSession, event)
    }

    // Resume invocation 2 on the new session.
    val resumed = runner.runAsync(USER_ID, "session-2", invocationId = inv2Id).toList()
    assertEquals(
      listOf("sub_agent" to "third response from sub_agent", "sub_agent" to END_OF_AGENT),
      simplifyResumableEvents(resumed),
    )
  }

  @Test
  fun resumeAnyInvocation_notJustTheLatest() = runTest {
    val rootAgent =
      LlmAgent(
        name = "root_agent",
        model =
          DummyModel.createSequential(
            "root",
            listOf(
              modelFunctionCallResponse("test_tool", id = "call-1"),
              LlmResponse(content = modelMessage("llm response in invocation 2")),
              modelFunctionCallResponse("test_tool", id = "call-3"),
              LlmResponse(content = modelMessage("llm response after resuming invocation 1")),
            ),
          ),
        tools =
          listOf(
            DummyTool(
              name = "test_tool",
              isLongRunning = true,
              onRun = { _, _ -> mapOf("result" to "test tool result") },
            )
          ),
      )
    val runner =
      InMemoryRunner(agent = rootAgent, resumabilityConfig = ResumabilityConfig(isResumable = true))

    // Invocation 1: pauses on the long-running call.
    val inv1 =
      runner.runAsync(USER_ID, SESSION_ID, newMessage = userMessage("test user query")).toList()
    assertEquals(
      listOf(
        "root_agent" to Part(functionCall = FunctionCall(name = "test_tool")),
        "root_agent" to
          Part(
            functionResponse =
              FunctionResponse(name = "test_tool", response = mapOf("result" to "test tool result"))
          ),
      ),
      simplifyResumableEvents(inv1),
    )

    // Invocation 2: finishes normally.
    val inv2 =
      runner.runAsync(USER_ID, SESSION_ID, newMessage = userMessage("test user query 2")).toList()
    assertEquals(
      listOf("root_agent" to "llm response in invocation 2", "root_agent" to END_OF_AGENT),
      simplifyResumableEvents(inv2),
    )

    // Invocation 3: pauses on the long-running call again.
    val inv3 =
      runner.runAsync(USER_ID, SESSION_ID, newMessage = userMessage("test user query 3")).toList()
    assertEquals(
      listOf(
        "root_agent" to Part(functionCall = FunctionCall(name = "test_tool")),
        "root_agent" to
          Part(
            functionResponse =
              FunctionResponse(name = "test_tool", response = mapOf("result" to "test tool result"))
          ),
      ),
      simplifyResumableEvents(inv3),
    )

    // Resume invocation 1 (not the latest) by supplying its long-running function response.
    val inv1Id = inv1.first().invocationId
    val callId = inv1.first().content!!.parts.first().functionCall!!.id!!
    val resumed =
      runner
        .runAsync(
          USER_ID,
          SESSION_ID,
          invocationId = inv1Id,
          newMessage =
            userFunctionResponse(
              name = "test_tool",
              id = callId,
              response = mapOf("result" to "test tool update"),
            ),
        )
        .toList()
    assertEquals(
      listOf(
        "root_agent" to "llm response after resuming invocation 1",
        "root_agent" to END_OF_AGENT,
      ),
      simplifyResumableEvents(resumed),
    )
  }

  private companion object {
    const val APP_NAME = "InMemoryRunner"
    const val USER_ID = "user"
    const val SESSION_ID = "session"
  }
}
