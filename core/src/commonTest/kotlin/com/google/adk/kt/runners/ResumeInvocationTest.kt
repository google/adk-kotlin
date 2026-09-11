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

import com.google.adk.kt.agents.BaseAgent
import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.ParallelAgent
import com.google.adk.kt.agents.ResumabilityConfig
import com.google.adk.kt.apps.App
import com.google.adk.kt.events.Event
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.sessions.Session
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
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/**
 * Edge cases of resuming invocations through the runner by `invocationId`, ported from Python ADK
 * `tests/unittests/runners/test_resume_invocation.py`.
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
      InMemoryRunner(
        App(
          appName = "InMemoryRunner",
          rootAgent = rootAgent,
          resumabilityConfig = ResumabilityConfig(isResumable = true),
        )
      )

    // Invocation 1: starts at root and transfers to sub_agent.
    val inv1 =
      runner.runAsync(USER_ID, SESSION_ID, newMessage = userMessage("test user query")).toList()
    assertEquals(
      listOf(
        "root_agent" to transferToAgentCallPart("sub_agent"),
        "root_agent" to TRANSFER_TO_AGENT_RESPONSE_PART,
        "root_agent" to END_OF_AGENT,
        "sub_agent" to "first response from sub_agent",
        "sub_agent" to END_OF_AGENT,
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
          listOf(DummyTool(name = "test_tool", isLongRunning = true, onRun = { _, _ -> Unit })),
      )
    val runner =
      InMemoryRunner(
        App(
          appName = "InMemoryRunner",
          rootAgent = rootAgent,
          resumabilityConfig = ResumabilityConfig(isResumable = true),
        )
      )

    // Invocation 1: pauses on the long-running call (Unit return, so no function-response event).
    val inv1 =
      runner.runAsync(USER_ID, SESSION_ID, newMessage = userMessage("test user query")).toList()
    assertEquals(
      listOf("root_agent" to Part(functionCall = FunctionCall(name = "test_tool"))),
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
      listOf("root_agent" to Part(functionCall = FunctionCall(name = "test_tool"))),
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

  /**
   * root transfers to sub_agent, which issues a long-running call and pauses. Resuming with the
   * function response must run the sub-agent, not the finished root. Regression: marking the root
   * end-of-agent on transfer must still let the runner route the resume to the paused sub-agent.
   */
  @Test
  fun resumeTransferredSubAgent_thatPausedOnLongRunningCall() = runTest {
    val subAgent =
      LlmAgent(
        name = "sub_agent",
        model =
          DummyModel.createSequential(
            "sub",
            listOf(
              modelFunctionCallResponse("pending_tool", id = "lro-1"),
              LlmResponse(content = modelMessage("sub_agent resumed")),
            ),
          ),
        tools =
          listOf(DummyTool(name = "pending_tool", isLongRunning = true, onRun = { _, _ -> Unit })),
      )
    val rootAgent =
      LlmAgent(
        name = "root_agent",
        model =
          DummyModel.createSequential("root", listOf(modelTransferToAgentResponse("sub_agent"))),
        subAgents = listOf(subAgent),
      )
    val runner =
      InMemoryRunner(
        App(
          appName = "InMemoryRunner",
          rootAgent = rootAgent,
          resumabilityConfig = ResumabilityConfig(isResumable = true),
        )
      )

    // Turn 1: root transfers; sub_agent issues the long-running call and pauses.
    val turn1 = runner.runAsync(USER_ID, SESSION_ID, newMessage = userMessage("start")).toList()
    val invId = turn1.first().invocationId!!
    assertFalse(simplifyResumableEvents(turn1).any { it.second == "sub_agent resumed" })

    // Turn 2: resume with the tool response -> the sub-agent runs, not the finished root.
    val resumed =
      runner
        .runAsync(
          USER_ID,
          SESSION_ID,
          invocationId = invId,
          newMessage =
            userFunctionResponse(
              name = "pending_tool",
              id = "lro-1",
              response = mapOf("status" to "done"),
            ),
        )
        .toList()
    assertEquals(
      listOf("sub_agent" to "sub_agent resumed", "sub_agent" to END_OF_AGENT),
      simplifyResumableEvents(resumed),
    )
  }

  /**
   * Tests that resuming a leaf nested under a [ParallelAgent] seeds the context with the leaf's
   * full branch. Without restoring the branch, the empty root branch hides branch-scoped paused
   * calls and causes the model to re-invoke prematurely.
   */
  @Test
  fun resume_leafPausedUnderParallelBranch_seedsBranchAndStaysPaused() = runTest {
    val invId = "inv-1"
    val leafBranch = "root_agent.leaf"
    val leaf =
      LlmAgent(
        name = "leaf",
        model =
          DummyModel.createSequential(
            "leaf",
            listOf(LlmResponse(content = modelMessage("summary after tools"))),
          ),
        tools =
          listOf(
            DummyTool(name = "tool_one", isLongRunning = true, onRun = { _, _ -> Unit }),
            DummyTool(name = "tool_two", isLongRunning = true, onRun = { _, _ -> Unit }),
          ),
      )
    val rootAgent = ParallelAgent(name = "root_agent", subAgents = listOf(leaf))
    val runner = ResumeBranchTestRunner(rootAgent)

    val session = runner.sessionService.createSession(SessionKey(APP_NAME, USER_ID, SESSION_ID))
    // The leaf's pause point: two long-running calls on its own branch. The ParallelAgent's state
    // checkpoint is intentionally omitted so the resume resolves the leaf directly.
    val unusedAppend =
      runner.sessionService.appendEvent(
        session,
        Event(
          author = "leaf",
          invocationId = invId,
          branch = leafBranch,
          content =
            Content(
              Role.MODEL,
              listOf(
                Part(
                  functionCall =
                    FunctionCall(name = "tool_one", args = emptyMap(), id = "tool_one_id")
                ),
                Part(
                  functionCall =
                    FunctionCall(name = "tool_two", args = emptyMap(), id = "tool_two_id")
                ),
              ),
            ),
          longRunningToolIds = setOf("tool_one_id", "tool_two_id"),
        ),
      )

    val resumedContext =
      runner.resumeContext(
        session,
        userFunctionResponse(
          name = "tool_one",
          id = "tool_one_id",
          response = mapOf("result" to "ok"),
        ),
        invId,
      )

    // Verifies the resumed context restores the leaf's branch rather than the root branch.
    assertEquals("leaf", resumedContext.agent.name)
    assertEquals(leafBranch, resumedContext.branch)

    // With its branch restored, the leaf sees the remaining unanswered call and stays paused.
    val sessionService = resumedContext.sessionService!!
    val resumedEvents = mutableListOf<Event>()
    resumedContext.agent.runAsync(resumedContext).collect { event ->
      val unused = sessionService.appendEvent(resumedContext.session, event)
      resumedEvents.add(event)
    }
    val simplified = simplifyResumableEvents(resumedEvents)
    assertFalse(
      simplified.any { it.second == "summary after tools" },
      "the model must not be re-invoked while a branch-scoped paused call is unanswered: $simplified",
    )
    assertFalse(
      simplified.contains("leaf" to END_OF_AGENT),
      "the leaf must stay paused (no end-of-agent): $simplified",
    )
  }

  /** Exposes the protected [setupContextForResumedInvocation] hook for the branch-seed test. */
  private class ResumeBranchTestRunner(agent: BaseAgent) :
    InMemoryRunner(
      App(
        appName = "InMemoryRunner",
        rootAgent = agent,
        resumabilityConfig = ResumabilityConfig(isResumable = true),
      )
    ) {
    suspend fun resumeContext(
      session: Session,
      newMessage: Content,
      invocationId: String,
    ): InvocationContext =
      setupContextForResumedInvocation(
        session = session,
        newMessage = newMessage,
        invocationId = invocationId,
        runConfig = null,
        stateDelta = null,
      )
  }

  private companion object {
    const val APP_NAME = "InMemoryRunner"
    const val USER_ID = "user"
    const val SESSION_ID = "session"
  }
}
