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
import com.google.adk.kt.testing.testInvocationContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Tests for the resumability state helpers on [BaseAgent] ([BaseAgent.loadAgentState] and
 * [BaseAgent.createStateEvent]). Mirrors Python ADK `tests/unittests/agents/test_base_agent.py`
 * (`test_load_agent_state_*`, `test_create_agent_state_event`).
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
}
