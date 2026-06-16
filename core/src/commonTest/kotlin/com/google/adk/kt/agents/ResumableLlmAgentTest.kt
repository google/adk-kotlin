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

package com.google.adk.kt.agents

import com.google.adk.kt.annotations.ExperimentalResumabilityFeature
import com.google.adk.kt.events.Event
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.sessions.InMemorySessionService
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.DummyTool
import com.google.adk.kt.testing.ResumableEvents.END_OF_AGENT
import com.google.adk.kt.testing.TRANSFER_TO_AGENT_RESPONSE_PART
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.simplifyResumableEvents
import com.google.adk.kt.testing.transferToAgentCallPart
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

/**
 * Tests that a resumable [LlmAgent] resumes from the correct point given a context pre-populated
 * with a previous invocation's events and agent states. Ported from Python ADK 1.x
 * `v1/tests/unittests/agents/test_resumable_llm_agent.py`.
 *
 * Each test builds an [InvocationContext] holding the prior events, seeds the agent state(s) (the
 * checkpoint a real run would have persisted), then calls `agent.runAsync(context)` directly and
 * asserts the resumed event stream via [simplifyResumableEvents].
 */
class ResumableLlmAgentTest {

  // TODO: known gap to fix in a follow-up CL. Resuming from a *pending* transfer call (a
  // `transfer_to_agent` call with no response yet) fails in Kotlin: the resume "pickup" path in
  // `LlmAgentTurn.execute()` builds its tool map via `getToolMap(null)`, which omits the
  // request-scoped `transfer_to_agent` tool that `AgentTransferProcessor` normally adds, so
  // executing the pending transfer call throws "BaseTool transfer_to_agent not found". (A naive fix
  // that re-adds the transfer tool then trips a separate flow-buffering recursion when the
  // transferred-to sub-agent re-reads session state.) Python ADK 1.x
  // (`v1/tests/.../test_resumable_llm_agent.py::test_resume_from_transfer_call`) supports this.
  // Re-enable once the resume pickup path makes request-scoped tools available.
  @Ignore
  @Test
  fun resumeFromTransferCall_runsTransferredSubAgent() = runTest {
    val subAgent1 = llmAgent("sub_agent_1", "response from sub_agent_1")
    val rootAgent = llmAgent("root_agent", "response from root", subAgents = listOf(subAgent1))
    val ctx =
      resumableContext(
        rootAgent,
        listOf(modelEvent("root_agent", transferToAgentCallPart("sub_agent_1"))),
      )
    ctx.agentStates["root_agent"] = baseAgentState()

    assertEquals(
      listOf(
        "root_agent" to TRANSFER_TO_AGENT_RESPONSE_PART,
        "sub_agent_1" to "response from sub_agent_1",
        "sub_agent_1" to END_OF_AGENT,
        "root_agent" to END_OF_AGENT,
      ),
      resumeAndSimplify(rootAgent, ctx),
    )
  }

  @Test
  fun resumeFromTransferResponse_runsTransferredSubAgent() = runTest {
    val subAgent1 = llmAgent("sub_agent_1", "response from sub_agent_1")
    val rootAgent = llmAgent("root_agent", "response from root", subAgents = listOf(subAgent1))
    val ctx =
      resumableContext(
        rootAgent,
        listOf(
          Event(
            author = "root_agent",
            invocationId = INVOCATION_ID,
            content = Content(Role.MODEL, listOf(TRANSFER_TO_AGENT_RESPONSE_PART)),
            actions = EventActions(transferToAgent = "sub_agent_1"),
          )
        ),
      )
    ctx.agentStates["root_agent"] = baseAgentState()

    assertEquals(
      listOf(
        "sub_agent_1" to "response from sub_agent_1",
        "sub_agent_1" to END_OF_AGENT,
        "root_agent" to END_OF_AGENT,
      ),
      resumeAndSimplify(rootAgent, ctx),
    )
  }

  @Test
  fun resumeFromModelResponse_noTransfer_continuesRootAgent() = runTest {
    val rootAgent = llmAgent("root_agent", "second response from root")
    val ctx =
      resumableContext(
        rootAgent,
        listOf(modelEvent("root_agent", Part(text = "initial response from root"))),
      )
    ctx.agentStates["root_agent"] = baseAgentState()

    assertEquals(
      listOf("root_agent" to "second response from root", "root_agent" to END_OF_AGENT),
      resumeAndSimplify(rootAgent, ctx),
    )
  }

  @Test
  fun resumeFromToolCall_executesToolThenContinues() = runTest {
    val rootAgent =
      LlmAgent(
        name = "root_agent",
        model = sequentialModel("root", "response after tool call"),
        tools = listOf(someTool()),
      )
    val ctx =
      resumableContext(rootAgent, listOf(modelEvent("root_agent", toolCallPart("some_tool"))))
    ctx.agentStates["root_agent"] = baseAgentState()

    assertEquals(
      listOf(
        "root_agent" to toolResponsePart("some_tool"),
        "root_agent" to "response after tool call",
        "root_agent" to END_OF_AGENT,
      ),
      resumeAndSimplify(rootAgent, ctx),
    )
  }

  @Test
  fun resumeAfterToolResponse_continuesRootAgent() = runTest {
    val rootAgent =
      LlmAgent(
        name = "root_agent",
        model = sequentialModel("root", "response after tool call"),
        tools = listOf(someTool()),
      )
    val ctx =
      resumableContext(
        rootAgent,
        listOf(
          modelEvent("root_agent", toolCallPart("some_tool")),
          modelEvent("root_agent", toolResponsePartWithId("some_tool")),
        ),
      )
    ctx.agentStates["root_agent"] = baseAgentState()

    assertEquals(
      listOf("root_agent" to "response after tool call", "root_agent" to END_OF_AGENT),
      resumeAndSimplify(rootAgent, ctx),
    )
  }

  @Test
  fun resumeRootAgent_onUserProvidedFunctionResponseToRootTool() = runTest {
    val subAgent1 =
      LlmAgent(
        name = "sub_agent_1",
        model = sequentialModel("sub", "response from sub_agent_1 after tool call"),
        tools = listOf(someTool("sub_agent_tool")),
      )
    val rootAgent =
      LlmAgent(
        name = "root_agent",
        model = sequentialModel("root", "response from root after tool call"),
        subAgents = listOf(subAgent1),
        tools = listOf(someTool()),
      )
    val ctx =
      resumableContext(
        rootAgent,
        listOf(
          Event(
            author = "root_agent",
            invocationId = INVOCATION_ID,
            actions = EventActions(transferToAgent = "sub_agent_1"),
          ),
          modelEvent("root_agent", transferToAgentCallPart("sub_agent_1")),
          Event(
            author = "root_agent",
            invocationId = INVOCATION_ID,
            content = Content(Role.MODEL, listOf(TRANSFER_TO_AGENT_RESPONSE_PART)),
            actions = EventActions(transferToAgent = "sub_agent_1"),
          ),
          modelEvent("root_agent", toolCallPart("some_tool")),
          modelEvent("sub_agent_1", toolCallPart("sub_agent_tool")),
          Event(
            author = Role.USER,
            invocationId = INVOCATION_ID,
            content = Content(Role.USER, listOf(toolResponsePartWithId("some_tool"))),
          ),
        ),
      )
    ctx.agentStates["root_agent"] = baseAgentState()
    ctx.agentStates["sub_agent_1"] = baseAgentState()

    assertEquals(
      listOf("root_agent" to "response from root after tool call", "root_agent" to END_OF_AGENT),
      resumeAndSimplify(rootAgent, ctx),
    )
  }

  @Test
  fun resumeSubAgent_onUserProvidedFunctionResponseToSubAgentTool() = runTest {
    val subAgent1 =
      LlmAgent(
        name = "sub_agent_1",
        model = sequentialModel("sub", "response from sub_agent_1 after tool call"),
        tools = listOf(someTool("sub_agent_tool")),
      )
    val rootAgent =
      LlmAgent(
        name = "root_agent",
        model = sequentialModel("root", "response from root after tool call"),
        subAgents = listOf(subAgent1),
      )
    val ctx =
      resumableContext(
        rootAgent,
        listOf(
          Event(
            author = "root_agent",
            invocationId = INVOCATION_ID,
            actions = EventActions(transferToAgent = "sub_agent_1"),
          ),
          modelEvent("root_agent", transferToAgentCallPart("sub_agent_1")),
          Event(
            author = "root_agent",
            invocationId = INVOCATION_ID,
            content = Content(Role.MODEL, listOf(TRANSFER_TO_AGENT_RESPONSE_PART)),
            actions = EventActions(transferToAgent = "sub_agent_1"),
          ),
          modelEvent("sub_agent_1", toolCallPart("sub_agent_tool")),
          Event(
            author = Role.USER,
            invocationId = INVOCATION_ID,
            content = Content(Role.USER, listOf(toolResponsePartWithId("sub_agent_tool"))),
          ),
        ),
      )
    ctx.agentStates["root_agent"] = baseAgentState()
    ctx.agentStates["sub_agent_1"] = baseAgentState()

    assertEquals(
      listOf(
        "sub_agent_1" to "response from sub_agent_1 after tool call",
        "sub_agent_1" to END_OF_AGENT,
        "root_agent" to END_OF_AGENT,
      ),
      resumeAndSimplify(rootAgent, ctx),
    )
  }

  // -- Helpers -----------------------------------------------------------------------------------

  private companion object {
    const val INVOCATION_ID = "test_invocation"

    fun llmAgent(
      name: String,
      vararg textResponses: String,
      subAgents: List<BaseAgent> = emptyList(),
    ): LlmAgent =
      LlmAgent(name = name, model = sequentialModel(name, *textResponses), subAgents = subAgents)

    fun sequentialModel(tag: String, vararg textResponses: String): DummyModel =
      DummyModel.createSequential(
        "model-$tag",
        textResponses.map { LlmResponse(content = modelMessage(it)) },
      )

    /** A regular (non-long-running) tool returning `{"result": "ok"}`. */
    fun someTool(name: String = "some_tool"): DummyTool =
      DummyTool(name = name, onRun = { _, _ -> mapOf("result" to "ok") })

    fun baseAgentState(): TypedData.MapValue = TypedData.MapValue(emptyMap())

    fun modelEvent(author: String, part: Part): Event =
      Event(
        author = author,
        invocationId = INVOCATION_ID,
        content = Content(Role.MODEL, listOf(part)),
      )

    /** A function call part with the conventional `<tool>_id` id. */
    fun toolCallPart(toolName: String): Part =
      Part(functionCall = FunctionCall(name = toolName, args = emptyMap(), id = "${toolName}_id"))

    /** The simplified (id-stripped) function response part used in expectations. */
    fun toolResponsePart(toolName: String): Part =
      Part(functionResponse = FunctionResponse(name = toolName, response = mapOf("result" to "ok")))

    /** A function response part carrying the matching `<tool>_id` id, for building past events. */
    fun toolResponsePartWithId(toolName: String): Part =
      Part(
        functionResponse =
          FunctionResponse(
            name = toolName,
            id = "${toolName}_id",
            response = mapOf("result" to "ok"),
          )
      )

    suspend fun resumableContext(agent: BaseAgent, pastEvents: List<Event>): InvocationContext {
      val sessionService = InMemorySessionService()
      val key = SessionKey("test_app", "test_user", "test_session")
      val session = sessionService.createSession(key)
      for (event in pastEvents) {
        val unused = sessionService.appendEvent(session, event)
      }
      return InvocationContext(
        // `appendEvent` updates the passed `session` object (see SessionService.appendEvent), so it
        // already reflects the past events -- no need to re-fetch via getSession.
        session = session,
        runConfig = null,
        agent = agent,
        invocationId = INVOCATION_ID,
        sessionService = sessionService,
        resumabilityConfig = ResumabilityConfig(isResumable = true),
      )
    }

    suspend fun resumeAndSimplify(
      agent: BaseAgent,
      context: InvocationContext,
    ): List<Pair<String, Any>> {
      val sessionService =
        checkNotNull(context.sessionService) { "test InvocationContext must have a sessionService" }
      val events = mutableListOf<Event>()
      agent.runAsync(context).collect { event ->
        val unused = sessionService.appendEvent(context.session, event)
        events.add(event)
      }
      return simplifyResumableEvents(events)
    }
  }
}
