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

@file:OptIn(ExperimentalLiveApi::class)

package com.google.adk.kt.agents

import com.google.adk.kt.annotations.ExperimentalLiveApi
import com.google.adk.kt.callbacks.AfterAgentCallback
import com.google.adk.kt.callbacks.BeforeAgentCallback
import com.google.adk.kt.callbacks.CallbackChoice
import com.google.adk.kt.events.Event
import com.google.adk.kt.telemetry.Telemetry
import com.google.adk.kt.telemetry.TelemetryAttributes
import com.google.adk.kt.testing.DummyTracer
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.testInvocationContext
import com.google.adk.kt.types.Content
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/**
 * Tests for [BaseAgent] name validation, its resumability state helpers and its live entry point.
 */
class BaseAgentTest {

  @Test
  fun loadAgentState_stateNotInContext_returnsMapperResultForNull() {
    val agent = StateTestAgent()
    val context = testInvocationContext(agent = agent)

    val state = agent.loadState(context)

    assertNull(state)
  }

  @Test
  fun loadAgentState_stateInContext_returnsMappedState() {
    val agent = StateTestAgent()
    val context =
      testInvocationContext(
        agent = agent,
        resumabilityConfig = ResumabilityConfig(isResumable = true),
      )
    context.agentStates[agent.name] = TestAgentState(testField = "resumed").toStateValue()

    val state = agent.loadState(context)

    assertEquals(TestAgentState(testField = "resumed"), state)
  }

  @Test
  fun createStateEvent_populatesInvocationIdAuthorBranchAndAgentState() {
    val agent = StateTestAgent()
    val context = testInvocationContext(agent = agent, branch = "test_branch")
    val state = TestAgentState(testField = "checkpoint")

    val event = agent.createState(context, state)

    assertEquals(context.invocationId, event.invocationId)
    assertEquals(agent.name, event.author)
    assertEquals("test_branch", event.branch)
    assertEquals(state.toStateValue(), event.actions.agentState)
  }

  @Test
  fun construct_nameWithHyphen_isAccepted() {
    val agent = StateTestAgent(name = "my-agent")

    assertEquals("my-agent", agent.name)
  }

  @Test
  fun construct_nameWithSpace_isAccepted() {
    val agent = StateTestAgent(name = "my agent")

    assertEquals("my agent", agent.name)
  }

  @Test
  fun construct_nameWithDot_isAccepted() {
    val agent = StateTestAgent(name = "my.agent")

    assertEquals("my.agent", agent.name)
  }

  @Test
  fun construct_nameStartingWithUnderscore_isAccepted() {
    val agent = StateTestAgent(name = "_agent")

    assertEquals("_agent", agent.name)
  }

  @Test
  fun construct_emptyName_throwsIllegalArgumentException() {
    assertFailsWith<IllegalArgumentException> { StateTestAgent(name = "") }
  }

  @Test
  fun construct_nameWithTrailingHyphen_throwsIllegalArgumentException() {
    assertFailsWith<IllegalArgumentException> { StateTestAgent(name = "agent-") }
  }

  @Test
  fun construct_nameWithUnsupportedCharacter_throwsIllegalArgumentException() {
    assertFailsWith<IllegalArgumentException> { StateTestAgent(name = "my@agent") }
  }

  @Test
  fun construct_reservedNameUser_throwsIllegalArgumentException() {
    assertFailsWith<IllegalArgumentException> { StateTestAgent(name = "user") }
  }

  @Test
  fun construct_reservedNameIsCaseSensitive_acceptsCapitalizedUser() {
    val agent = StateTestAgent(name = "User")

    assertEquals("User", agent.name)
  }

  @Test
  fun runLiveImpl_agentWithoutLiveSupport_throwsWhenCollectedNotWhenCalled(): Unit = runBlocking {
    val agent = StateTestAgent()
    val work = agent.liveWork(testInvocationContext(agent = agent))

    assertFailsWith<UnsupportedOperationException> { work.toList() }
  }

  @Test
  fun runLive_beforeAgentCallbackBreaks_skipsTheLiveWork() = runBlocking {
    val skipped = modelMessage("skipped")
    val agent =
      LiveTestAgent(
        beforeAgentCallbacks = listOf(BeforeAgentCallback { CallbackChoice.Break(skipped) })
      )

    val events = agent.runLive(testInvocationContext(agent = agent)).toList()

    assertEquals(listOf<Content?>(skipped), events.map { it.content })
    assertEquals(0, agent.liveRuns)
  }

  @Test
  fun runLive_afterAgentCallbackBreaks_appendsItsContentAfterTheLiveEvents() = runBlocking {
    val after = modelMessage("after")
    val agent =
      LiveTestAgent(
        afterAgentCallbacks = listOf(AfterAgentCallback { CallbackChoice.Break(after) })
      )

    val events = agent.runLive(testInvocationContext(agent = agent)).toList()

    assertEquals(listOf<Content?>(LiveTestAgent.SPOKEN, after), events.map { it.content })
  }

  @Test
  fun runLive_tracesAnInvokeAgentSpan() = runBlocking {
    val tracer = DummyTracer()
    Telemetry.setTracerForTest(tracer)
    try {
      val agent = LiveTestAgent()

      agent.runLive(testInvocationContext(agent = agent)).toList()

      val span = tracer.recordedSpans.single { it.name == "invoke_agent live_agent" }
      assertEquals("invoke_agent", span.attributes[TelemetryAttributes.GEN_AI_OPERATION_NAME])
    } finally {
      Telemetry.resetTracer()
    }
  }
}

/** A minimal [AgentState] with a single string field, mirroring Python's `_TestAgentState`. */
private data class TestAgentState(val testField: String = "") : AgentState {
  override fun toStateValue(): TypedData.MapValue =
    TypedData.MapValue(mapOf(TEST_FIELD_KEY to TypedData.StringValue(testField)))

  companion object {
    private const val TEST_FIELD_KEY = "test_field"

    fun fromValue(value: TypedData?): TestAgentState? {
      val fields = (value as? TypedData.MapValue)?.fields ?: return null
      return TestAgentState((fields[TEST_FIELD_KEY] as? TypedData.StringValue)?.value ?: "")
    }
  }
}

/** A [BaseAgent] that exposes the protected resumability helpers for testing. */
private class StateTestAgent(name: String = "test_agent") : BaseAgent(name = name) {
  override fun runAsyncImpl(context: InvocationContext): Flow<Event> = emptyFlow()

  fun loadState(context: InvocationContext): TestAgentState? =
    loadAgentState(context) { TestAgentState.fromValue(it) }

  fun createState(context: InvocationContext, state: AgentState): Event =
    createStateEvent(context, state)

  fun liveWork(context: InvocationContext): Flow<Event> = runLiveImpl(context)
}

/** A [BaseAgent] whose live work emits one event and counts its runs. */
private class LiveTestAgent(
  beforeAgentCallbacks: List<BeforeAgentCallback> = emptyList(),
  afterAgentCallbacks: List<AfterAgentCallback> = emptyList(),
) :
  BaseAgent(
    name = "live_agent",
    beforeAgentCallbacks = beforeAgentCallbacks,
    afterAgentCallbacks = afterAgentCallbacks,
  ) {
  var liveRuns = 0

  override fun runAsyncImpl(context: InvocationContext): Flow<Event> = emptyFlow()

  override fun runLiveImpl(context: InvocationContext): Flow<Event> = flow {
    liveRuns++
    emit(Event(author = name, content = SPOKEN))
  }

  companion object {
    val SPOKEN = modelMessage("spoken")
  }
}
