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
import com.google.adk.kt.callbacks.CallbackChoice
import com.google.adk.kt.events.Event
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.plugins.Plugin
import com.google.adk.kt.plugins.PluginManager
import com.google.adk.kt.testing.DummyAgent
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.DummyTool
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.testInvocationContext
import com.google.adk.kt.testing.testSession
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class InvocationContextTest {

  @Test
  fun invocationContext_creation_setsDefaultValues() {
    val context =
      InvocationContext(
        session = testSession(),
        runConfig = null,
        agent = DummyAgent("test-agent"),
        userContent = Content(role = Role.USER),
        invocationId = "invocation-id",
      )

    assertEquals("test_session_id", context.session.key.id)
    assertEquals("test-agent", context.agent.name)
    assertEquals("invocation-id", context.invocationId)
    assertNotNull(context.pluginManager)
  }

  @Test
  fun invocationContext_creation_allowsPassingPluginManager() {
    val pluginManager = PluginManager()
    val context =
      InvocationContext(
        session = testSession(),
        runConfig = null,
        agent = DummyAgent("test-agent"),
        userContent = Content(role = Role.USER),
        invocationId = "invocation-id",
        pluginManager = pluginManager,
      )

    assertEquals(pluginManager, context.pluginManager)
  }

  @Test
  fun incrementLlmCallsCount_whenLimitNotExceeded_doesNotThrow() {
    val context = testInvocationContext(runConfig = RunConfig(maxLlmCalls = 2))

    // Exactly maxLlmCalls calls are allowed without throwing.
    context.incrementLlmCallsCount()
    context.incrementLlmCallsCount()
  }

  @Test
  fun incrementLlmCallsCount_whenLimitExceeded_throws() {
    val context = testInvocationContext(runConfig = RunConfig(maxLlmCalls = 1))

    context.incrementLlmCallsCount()

    assertThrows(LlmCallsLimitExceededException::class.java) { context.incrementLlmCallsCount() }
  }

  @Test
  fun incrementLlmCallsCount_whenLimitIsNonPositive_doesNotEnforce() {
    val context = testInvocationContext(runConfig = RunConfig(maxLlmCalls = 0))

    // A non-positive limit disables enforcement: many calls never throw.
    repeat(1000) { context.incrementLlmCallsCount() }
  }

  @Test
  fun incrementLlmCallsCount_whenNoRunConfig_doesNotEnforce() {
    val context = testInvocationContext(runConfig = null)

    repeat(1000) { context.incrementLlmCallsCount() }
  }

  @Test
  fun incrementLlmCallsCount_counterIsSharedAcrossDerivedContexts() {
    // The cap applies to the whole invocation: contexts derived via copy (sub-agents, transfers)
    // share the same counter. One call via the parent and one via the child reach the limit of 2,
    // so a third call throws.
    val context = testInvocationContext(runConfig = RunConfig(maxLlmCalls = 2))
    val childContext = context.forAgent(DummyAgent("child-agent"))

    context.incrementLlmCallsCount()
    childContext.incrementLlmCallsCount()

    assertThrows(LlmCallsLimitExceededException::class.java) { context.incrementLlmCallsCount() }
  }

  @Test
  fun branch_withChildAgent_returnsNewContextWithUpdatedBranchAndAgent() {
    val context =
      testInvocationContext(
        agent = DummyAgent("parent-agent"),
        userContent = Content(role = Role.USER),
        invocationId = "invocation-id",
      )

    val childAgent = DummyAgent("child-agent")
    val branchedContext = context.branch(childAgent)

    assertEquals(childAgent, branchedContext.agent)
    assertEquals("child-agent", branchedContext.branch)
    assertEquals("invocation-id", branchedContext.invocationId)

    val subChildAgent = DummyAgent("sub-child")
    val subBranchedContext = branchedContext.branch(subChildAgent)

    assertEquals(subChildAgent, subBranchedContext.agent)
    assertEquals("child-agent.sub-child", subBranchedContext.branch)
  }

  @Test
  fun findMatchingFunctionCall_withMatchingFunctionCall_returnsMatchingEvent() = runTest {
    val session = testSession()
    val context = testInvocationContext(session = session, invocationId = "inv-1")

    val functionCallEvent =
      Event(
        invocationId = "inv-1",
        author = "test-agent",
        branch = "branch1",
        content =
          Content(
            role = Role.MODEL,
            parts =
              listOf(
                Part(functionCall = FunctionCall(name = "test_func", args = emptyMap(), id = "123"))
              ),
          ),
      )
    session.events.add(functionCallEvent)
    session.events.add(
      Event(invocationId = "inv-1", author = "user", content = userMessage("processing..."))
    )

    val match =
      context.findMatchingFunctionCall(
        Event(
          invocationId = "inv-1",
          author = "user",
          content =
            Content(
              role = Role.USER,
              parts =
                listOf(
                  Part(
                    functionResponse =
                      FunctionResponse(name = "test_func", response = emptyMap(), id = "123")
                  )
                ),
            ),
        )
      )

    assertNotNull(match)
    assertEquals(functionCallEvent.id, match!!.id)
  }

  @Test
  fun findMatchingFunctionCall_withoutMatchingFunctionCall_returnsNull() = runTest {
    val session = testSession()
    val context = testInvocationContext(session = session, invocationId = "inv-1")

    session.events.add(
      Event(
        invocationId = "inv-1",
        author = "test-agent",
        branch = "branch1",
        content =
          Content(
            role = Role.MODEL,
            parts =
              listOf(
                Part(functionCall = FunctionCall(name = "test_func", args = emptyMap(), id = "456"))
              ),
          ),
      )
    )

    val match =
      context.findMatchingFunctionCall(
        Event(
          invocationId = "inv-1",
          author = "user",
          content =
            Content(
              role = Role.USER,
              parts =
                listOf(
                  Part(
                    functionResponse =
                      FunctionResponse(name = "test_func", response = emptyMap(), id = "123")
                  )
                ),
            ),
        )
      )

    assertNull(match)
  }

  @Test
  fun findMatchingFunctionCall_withoutFunctionResponses_returnsNull() = runTest {
    val context = testInvocationContext(invocationId = "inv-1")

    val match =
      context.findMatchingFunctionCall(
        Event(invocationId = "inv-1", author = "user", content = userMessage("Hello"))
      )

    assertNull(match)
  }

  @Test
  fun executeSingleFunctionCall_beforeToolShortCircuits_returnsShortCircuitResponseAndSkipsTool() =
    runTest {
      var toolExecuted = false
      val tool =
        DummyTool(name = "test_tool") { _, _ ->
          toolExecuted = true
          mapOf("result" to "actual_result")
        }

      val shortCircuitResponse = mapOf("short" to "circuit")

      class ShortCircuitPlugin : Plugin {
        override val name: String = "ShortCircuitPlugin"

        override suspend fun beforeTool(
          context: ToolContext,
          tool: BaseTool,
          args: Map<String, Any>,
        ): CallbackChoice<Map<String, Any>, Map<String, Any>> {
          return CallbackChoice.Break(shortCircuitResponse)
        }
      }

      val pluginManager = PluginManager(listOf(ShortCircuitPlugin()))

      val context =
        InvocationContext(
          session = testSession(),
          runConfig = null,
          agent = LlmAgent(name = "test_llm_agent", model = DummyModel("mock_model")),
          invocationId = "inv",
          pluginManager = pluginManager,
        )

      val result =
        context.executeSingleFunctionCall(
          FunctionCall(name = "test_tool", args = emptyMap(), id = "call_id"),
          mapOf("test_tool" to tool),
        )

      assertFalse(toolExecuted)
      assertNotNull(result)
      val functionResponse = result!!.content?.parts?.get(0)?.functionResponse
      assertNotNull(functionResponse)
      assertEquals(shortCircuitResponse, functionResponse!!.response)
    }

  @Test
  fun setAgentState_withNewState_updatesMap() {
    val context = testInvocationContext()

    val state = TypedData.StringValue("some-state")
    context.setAgentState("agent-A", state)

    assertEquals(state, context.agentStates["agent-A"])
    assertEquals(false, context.endOfAgents["agent-A"])
  }

  @Test
  fun setAgentState_withEndOfAgent_removesStateAndSetsEndOfAgent() {
    val context = testInvocationContext()
    context.agentStates["agent-A"] = TypedData.StringValue("some-state")

    context.setAgentState("agent-A", endOfAgent = true)

    assertNull(context.agentStates["agent-A"])
    assertEquals(true, context.endOfAgents["agent-A"])
  }

  @Test
  fun setAgentState_withNullStateAndNotEnd_removesBoth() {
    val context = testInvocationContext()
    context.agentStates["agent-A"] = TypedData.StringValue("some-state")
    context.endOfAgents["agent-A"] = false

    context.setAgentState("agent-A", agentState = null, endOfAgent = false)

    assertNull(context.agentStates["agent-A"])
    assertNull(context.endOfAgents["agent-A"])
  }

  @Test
  fun isResumable_withConfigTrue_returnsTrue() {
    val context = testInvocationContext(resumabilityConfig = ResumabilityConfig(isResumable = true))

    assertEquals(true, context.isResumable)
  }

  @Test
  fun isResumable_withConfigFalse_returnsFalse() {
    val context =
      testInvocationContext(resumabilityConfig = ResumabilityConfig(isResumable = false))

    assertFalse(context.isResumable)
  }

  @Test
  fun isResumable_withNullConfig_returnsFalse() {
    val context = testInvocationContext(runConfig = null)

    assertFalse(context.isResumable)
  }

  @Test
  fun resetSubAgentStates_clearsStateOfSubAgents() {
    val subAgentB = DummyAgent("agent-B")
    val subAgentC = DummyAgent("agent-C")
    val parentAgent = DummyAgent("agent-A", subAgents = listOf(subAgentB, subAgentC))

    val context = testInvocationContext(agent = parentAgent)

    context.agentStates["agent-B"] = TypedData.StringValue("state-B")
    context.agentStates["agent-C"] = TypedData.StringValue("state-C")

    context.resetSubAgentStates("agent-A")

    assertNull(context.agentStates["agent-B"])
    assertNull(context.agentStates["agent-C"])
  }

  @Test
  fun populateInvocationAgentStates_withEndOfAgent_removesAgentState() = runTest {
    val session = testSession()
    val context =
      testInvocationContext(
        session = session,
        resumabilityConfig = ResumabilityConfig(isResumable = true),
        invocationId = "inv-1",
      )

    context.agentStates["agent-A"] = TypedData.StringValue("some-state")

    val event =
      Event(invocationId = "inv-1", author = "agent-A", actions = EventActions(endOfAgent = true))
    session.events.add(event)

    context.populateInvocationAgentStates()

    assertNull(context.agentStates["agent-A"])
    assertEquals(true, context.endOfAgents["agent-A"])
  }

  @Test
  fun populateInvocationAgentStates_withNewContentFromNonWorkflowAgent_initializesState() =
    runTest {
      val session = testSession()
      val context =
        testInvocationContext(
          session = session,
          resumabilityConfig = ResumabilityConfig(isResumable = true),
          invocationId = "inv-1",
        )

      val event = Event(invocationId = "inv-1", author = "agent-A", content = modelMessage("Hello"))
      session.events.add(event)

      context.populateInvocationAgentStates()

      assertNotNull(context.agentStates["agent-A"])
      assertEquals(false, context.endOfAgents["agent-A"])
    }

  @Test
  fun populateInvocationAgentStates_notResumable_doesNothing() = runTest {
    val session = testSession()
    val context =
      testInvocationContext(
        session = session,
        resumabilityConfig = ResumabilityConfig(isResumable = false),
        invocationId = "inv-1",
      )

    session.events.add(
      Event(invocationId = "inv-1", author = "agent-A", actions = EventActions(endOfAgent = true))
    )

    context.populateInvocationAgentStates()

    assertTrue(context.agentStates.isEmpty())
    assertTrue(context.endOfAgents.isEmpty())
  }

  @Test
  fun populateInvocationAgentStates_withAgentStateFromEvent_setsAgentStateAndClearsEnd() = runTest {
    val session = testSession()
    val context =
      testInvocationContext(
        session = session,
        resumabilityConfig = ResumabilityConfig(isResumable = true),
        invocationId = "inv-1",
      )

    val savedState = TypedData.MapValue(mapOf("k" to TypedData.StringValue("v")))
    session.events.add(
      Event(
        invocationId = "inv-1",
        author = "agent-A",
        actions = EventActions(agentState = savedState),
      )
    )

    context.populateInvocationAgentStates()

    assertEquals(savedState, context.agentStates["agent-A"])
    assertEquals(false, context.endOfAgents["agent-A"])
  }

  @Test
  fun populateInvocationAgentStates_withAgentStateAndEndOfAgent_endOfAgentWins() = runTest {
    val session = testSession()
    val context =
      testInvocationContext(
        session = session,
        resumabilityConfig = ResumabilityConfig(isResumable = true),
        invocationId = "inv-1",
      )

    // When both agent_state and end_of_agent are set on the same event, end_of_agent takes
    // priority and the agent_state is discarded. Mirrors Python ADK
    // `test_populate_invocation_agent_states_with_agent_state_and_end_of_agent`.
    session.events.add(
      Event(
        invocationId = "inv-1",
        author = "agent-A",
        actions = EventActions(endOfAgent = true, agentState = TypedData.MapValue(emptyMap())),
      )
    )

    context.populateInvocationAgentStates()

    assertNull(context.agentStates["agent-A"])
    assertEquals(true, context.endOfAgents["agent-A"])
  }

  @Test
  fun populateInvocationAgentStates_userMessageEvent_ignoredForDefaultState() = runTest {
    val session = testSession()
    val context =
      testInvocationContext(
        session = session,
        resumabilityConfig = ResumabilityConfig(isResumable = true),
        invocationId = "inv-1",
      )

    // A `user`-authored event must never seed a default agent state, even though it has content.
    session.events.add(Event(invocationId = "inv-1", author = "user", content = userMessage("hi")))

    context.populateInvocationAgentStates()

    assertTrue(context.agentStates.isEmpty())
    assertTrue(context.endOfAgents.isEmpty())
  }

  @Test
  fun populateInvocationAgentStates_eventWithNoContentAndNoState_ignored() = runTest {
    val session = testSession()
    val context =
      testInvocationContext(
        session = session,
        resumabilityConfig = ResumabilityConfig(isResumable = true),
        invocationId = "inv-1",
      )

    // No content and no state actions: the agent has not produced anything to checkpoint, so the
    // default-state seeding branch is skipped.
    session.events.add(Event(invocationId = "inv-1", author = "agent-A"))

    context.populateInvocationAgentStates()

    assertTrue(context.agentStates.isEmpty())
    assertTrue(context.endOfAgents.isEmpty())
  }

  @Test
  fun resetSubAgentStates_recursive_clearsNestedSubAgentStates() {
    val subSubAgent = DummyAgent("sub-sub")
    val subAgentB = DummyAgent("agent-B", subAgents = listOf(subSubAgent))
    val subAgentC = DummyAgent("agent-C")
    val parentAgent = DummyAgent("agent-A", subAgents = listOf(subAgentB, subAgentC))

    val context = testInvocationContext(agent = parentAgent)
    context.agentStates["agent-B"] = TypedData.StringValue("state-B")
    context.endOfAgents["agent-C"] = true
    context.agentStates["sub-sub"] = TypedData.StringValue("state-sub-sub")

    context.resetSubAgentStates("agent-A")

    assertNull(context.agentStates["agent-B"])
    assertNull(context.endOfAgents["agent-C"])
    assertNull(context.agentStates["sub-sub"])
  }

  @Test
  fun shouldPauseInvocation_resumableAndLongRunningToolIds_returnsTrue() {
    val context = pausableInvocationContext(resumable = true)

    assertTrue(context.shouldPauseInvocation(longRunningModelEvent()))
  }

  @Test
  fun shouldPauseInvocation_notResumable_returnsFalse() {
    // Pausing requires resumability in 1.x: even with a long-running function call, a non-resumable
    // app does not pause. Mirrors Python ADK 1.x
    // `test_should_not_pause_invocation_with_non_resumable_app`.
    val context = pausableInvocationContext(resumable = false)

    assertFalse(context.shouldPauseInvocation(longRunningModelEvent()))
  }

  @Test
  fun shouldPauseInvocation_resumableButNoLongRunningToolIds_returnsFalse() {
    val context = pausableInvocationContext(resumable = true)
    val event =
      Event(
        invocationId = "inv-1",
        author = "agent-A",
        content =
          Content(
            role = Role.MODEL,
            parts =
              listOf(
                Part(
                  functionCall = FunctionCall(name = "regular_tool", args = emptyMap(), id = "c")
                )
              ),
          ),
      )

    assertFalse(context.shouldPauseInvocation(event))
  }

  @Test
  fun shouldPauseInvocation_resumableButNoFunctionCalls_returnsFalse() {
    val context = pausableInvocationContext(resumable = true)
    val event = Event(invocationId = "inv-1", author = Role.USER, content = userMessage("hello"))

    assertFalse(context.shouldPauseInvocation(event))
  }

  @Test
  fun shouldPauseInvocation_resumableButNoFunctionCallIdMatchesLongRunningSet_returnsFalse() {
    val context = pausableInvocationContext(resumable = true)
    // The event advertises a long-running tool id that does not match any of the function calls
    // it carries (the event has a call with id "other_id"), so the runner should not pause.
    // Mirrors Python's per-FunctionCall id check in `should_pause_invocation`.
    val event =
      Event(
        invocationId = "inv-1",
        author = "agent-A",
        content =
          Content(
            role = Role.MODEL,
            parts =
              listOf(
                Part(
                  functionCall =
                    FunctionCall(name = "regular_tool", args = emptyMap(), id = "other_id")
                )
              ),
          ),
        longRunningToolIds = setOf("tool_call_id_1"),
      )

    assertFalse(context.shouldPauseInvocation(event))
  }

  private fun pausableInvocationContext(resumable: Boolean): InvocationContext =
    testInvocationContext(
      invocationId = "inv-1",
      resumabilityConfig = ResumabilityConfig(isResumable = resumable),
    )

  private fun longRunningModelEvent(): Event =
    Event(
      invocationId = "inv-1",
      author = "agent-A",
      content =
        Content(
          role = Role.MODEL,
          parts =
            listOf(
              Part(
                functionCall =
                  FunctionCall(name = "long_running_tool", args = emptyMap(), id = "tool_call_id_1")
              )
            ),
        ),
      longRunningToolIds = setOf("tool_call_id_1"),
    )

  @Test
  fun executeSingleFunctionCall_beforeToolModifiesArgs_passesModifiedArgsToTool() = runTest {
    var capturedArgs: Map<String, Any>? = null
    val tool =
      DummyTool(name = "test_tool") { _, args ->
        capturedArgs = args
        mapOf("result" to "success")
      }

    val plugin =
      object : Plugin {
        override val name = "modifier"

        override suspend fun beforeTool(
          context: ToolContext,
          tool: BaseTool,
          args: Map<String, Any>,
        ): CallbackChoice<Map<String, Any>, Map<String, Any>> {
          return CallbackChoice.Continue(mapOf("injected" to "value"))
        }
      }

    val context =
      InvocationContext(
        session = testSession(),
        runConfig = null,
        agent = LlmAgent(name = "a", model = DummyModel("m")),
        pluginManager = PluginManager(listOf(plugin)),
      )

    val unused =
      context.executeSingleFunctionCall(
        FunctionCall(name = "test_tool", args = mapOf("original" to "value"), id = "call_id"),
        mapOf("test_tool" to tool),
      )

    assertEquals("value", capturedArgs?.get("injected"))
    assertNull(capturedArgs?.get("original"))
  }

  @Test
  fun executeSingleFunctionCall_longRunningToolReturnsDict_buildsResponseEventFromPayload() =
    runTest {
      // A long-running tool returning a plain dict propagates that dict as the function-response
      // payload (no wrapping, no rewriting). Matches the contract documented on
      // `BaseTool.isLongRunning`.
      val placeholder = mapOf("ticket" to "abc-123", "status" to "pending")
      val tool = DummyTool(name = "test_tool", isLongRunning = true) { _, _ -> placeholder }

      val context =
        testInvocationContext(
          agent = LlmAgent(name = "test_llm_agent", model = DummyModel("mock_model")),
          invocationId = "inv",
        )

      val result =
        context.executeSingleFunctionCall(
          FunctionCall(name = "test_tool", args = emptyMap(), id = "call_id"),
          mapOf("test_tool" to tool),
        )

      assertNotNull(result)
      val functionResponse = result!!.content?.parts?.get(0)?.functionResponse
      assertNotNull(functionResponse)
      assertEquals(placeholder, functionResponse!!.response)
    }

  @Test
  fun executeSingleFunctionCall_longRunningToolReturnsUnit_suppressesResponseEvent() = runTest {
    // A `Unit` return ("no response yet") suppresses the FR event: the framework returns `null` so
    // the long-running FC (the turn's final response) ends the turn instead of re-emitting an empty
    // placeholder.
    val tool = DummyTool(name = "test_tool", isLongRunning = true) { _, _ -> Unit }

    val context =
      testInvocationContext(
        agent = LlmAgent(name = "test_llm_agent", model = DummyModel("mock_model")),
        invocationId = "inv",
      )

    val result =
      context.executeSingleFunctionCall(
        FunctionCall(name = "test_tool", args = emptyMap(), id = "call_id"),
        mapOf("test_tool" to tool),
      )

    assertNull(result)
  }

  @Test
  fun executeSingleFunctionCall_longRunningToolReturnsNull_emitsEmptyResponseEvent() = runTest {
    // A Java-implemented tool can break the non-null `run(): Any` contract and return `null`.
    // Unlike
    // `Unit` (which defers/suppresses), `null` is coerced to `{}` and emitted, matching Java.
    val tool = DummyTool(name = "test_tool", isLongRunning = true) { _, _ -> forceNull() }

    val context =
      testInvocationContext(
        agent = LlmAgent(name = "test_llm_agent", model = DummyModel("mock_model")),
        invocationId = "inv",
      )

    val result =
      context.executeSingleFunctionCall(
        FunctionCall(name = "test_tool", args = emptyMap(), id = "call_id"),
        mapOf("test_tool" to tool),
      )

    assertNotNull(result)
    val functionResponse = result!!.content?.parts?.get(0)?.functionResponse
    assertNotNull(functionResponse)
    assertEquals(emptyMap<String, Any>(), functionResponse!!.response)
  }

  /**
   * Produces a `null` typed as a non-null [T] (via erasure), simulating a Java platform-type leak.
   */
  @Suppress("UNCHECKED_CAST") private fun <T> forceNull(): T = null as T

  @Test
  fun executeSingleFunctionCall_longRunningToolReturnsEmptyMap_buildsEmptyResponseEvent() =
    runTest {
      // A long-running tool returning an empty Map emits an FR event with that payload, matching
      // Java's `LongRunningFunctionTool` semantics.
      val tool =
        DummyTool(name = "test_tool", isLongRunning = true) { _, _ -> emptyMap<String, Any>() }

      val context =
        testInvocationContext(
          agent = LlmAgent(name = "test_llm_agent", model = DummyModel("mock_model")),
          invocationId = "inv",
        )

      val result =
        context.executeSingleFunctionCall(
          FunctionCall(name = "test_tool", args = emptyMap(), id = "call_id"),
          mapOf("test_tool" to tool),
        )

      assertNotNull(result)
      val functionResponse = result!!.content?.parts?.get(0)?.functionResponse
      assertNotNull(functionResponse)
      assertEquals(emptyMap<String, Any>(), functionResponse!!.response)
    }

  @Test
  fun executeSingleFunctionCall_longRunningToolReturnsNonDict_wrapsInResultMap() = runTest {
    // A long-running tool that returns a non-dict value (e.g. a `String`) is wrapped in
    // `{"result": ...}` per the Gen-AI specs, just like a regular tool.
    val tool = DummyTool(name = "test_tool", isLongRunning = true) { _, _ -> "pending" }

    val context =
      testInvocationContext(
        agent = LlmAgent(name = "test_llm_agent", model = DummyModel("mock_model")),
        invocationId = "inv",
      )

    val result =
      context.executeSingleFunctionCall(
        FunctionCall(name = "test_tool", args = emptyMap(), id = "call_id"),
        mapOf("test_tool" to tool),
      )

    assertNotNull(result)
    val functionResponse = result!!.content?.parts?.get(0)?.functionResponse
    assertNotNull(functionResponse)
    assertEquals(mapOf(BaseTool.RESULT_KEY to "pending"), functionResponse!!.response)
  }

  @Test
  fun executeSingleFunctionCall_regularToolReturnsUnit_buildsEmptyResponseEvent() = runTest {
    // The `Unit`-suppression is gated on `tool.isLongRunning`. A regular tool returning `Unit`
    // (e.g. a hand-rolled `BaseTool` whose `run` ends with a statement, or a KSP-generated
    // `@Tool fun(): Unit`) yields a function-response event with an empty payload so the agent
    // loop continues normally. The framework coerces the `Unit` singleton to `emptyMap()` to
    // avoid leaking the Kotlin sentinel as `{result: kotlin.Unit}` on the wire.
    val tool = DummyTool(name = "test_tool", isLongRunning = false) { _, _ -> Unit }

    val context =
      testInvocationContext(
        agent = LlmAgent(name = "test_llm_agent", model = DummyModel("mock_model")),
        invocationId = "inv",
      )

    val result =
      context.executeSingleFunctionCall(
        FunctionCall(name = "test_tool", args = emptyMap(), id = "call_id"),
        mapOf("test_tool" to tool),
      )

    assertNotNull(result)
    val functionResponse = result!!.content?.parts?.get(0)?.functionResponse
    assertNotNull(functionResponse)
    assertEquals(emptyMap<String, Any>(), functionResponse!!.response)
  }

  @Test
  fun executeSingleFunctionCall_toolThrowsException_propagatesToCaller() = runTest {
    // Per the new contract (matching Python ADK), exceptions thrown by the tool function are not
    // caught by KSP-generated code. The framework's outer try/catch in
    // `executeSingleFunctionCall` routes them through `runErrorBaseToolCallbacks` (or rethrows
    // when no callback recovers).
    val tool =
      DummyTool(name = "test_tool") { _, _ -> throw IllegalStateException("database unreachable") }

    val context =
      testInvocationContext(
        agent = LlmAgent(name = "test_llm_agent", model = DummyModel("mock_model")),
        invocationId = "inv",
      )

    val thrown =
      kotlin
        .runCatching {
          context.executeSingleFunctionCall(
            FunctionCall(name = "test_tool", args = emptyMap(), id = "call_id"),
            mapOf("test_tool" to tool),
          )
        }
        .exceptionOrNull()

    assertNotNull(thrown)
    assertTrue(thrown is IllegalStateException)
    assertEquals("database unreachable", thrown!!.message)
  }
}
