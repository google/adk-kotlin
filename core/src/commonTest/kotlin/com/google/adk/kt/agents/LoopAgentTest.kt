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
import com.google.adk.kt.ids.Uuid
import com.google.adk.kt.testing.testSession
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

class LoopAgentTest {

  private fun createTestContext() =
    InvocationContext(
      session = testSession(),
      runConfig = null,
      agent = DummyAgent("dummy"),
      resumabilityConfig = ResumabilityConfig(isResumable = true),
    )

  private fun createEvent(author: String, text: String, escalate: Boolean = false): Event {
    return Event(
      id = Uuid.random(),
      invocationId = "test-invocation",
      author = author,
      content = Content(parts = listOf(Part(text = text))),
      actions = EventActions(escalate = escalate),
    )
  }

  private open class DummyAgent(
    name: String,
    val onRunAsync: suspend () -> Event = {
      Event(id = Uuid.random(), invocationId = "test", author = name)
    },
  ) : BaseAgent(name = name) {
    override fun runAsyncImpl(context: InvocationContext): Flow<Event> = flow { emit(onRunAsync()) }
  }

  @Test
  fun testLoopMaxIterations() = runTest {
    var count = 0
    val agent1 =
      DummyAgent(
        "agent1",
        onRunAsync = {
          count++
          createEvent("agent1", "msg1")
        },
      )

    val loopAgent = LoopAgent(name = "loop", subAgents = listOf(agent1), maxIterations = 3)
    val events = loopAgent.runAsync(createTestContext()).toList()

    val nonStateEvents = events.filter { it.actions.agentState == null && !it.actions.endOfAgent }
    assertEquals(3, nonStateEvents.size)
    assertEquals(3, count)
  }

  @Test
  fun testLoopEscalate() = runTest {
    var count = 0
    val agent1 =
      DummyAgent(
        "agent1",
        onRunAsync = {
          count++
          createEvent("agent1", "msg1", escalate = count == 2)
        },
      )

    val loopAgent = LoopAgent(name = "loop", subAgents = listOf(agent1), maxIterations = 5)
    val events = loopAgent.runAsync(createTestContext()).toList()

    val nonStateEvents = events.filter { it.actions.agentState == null && !it.actions.endOfAgent }
    assertEquals(2, nonStateEvents.size)
    assertEquals(2, count)
  }

  @Test
  fun testLoopMultipleSubAgents() = runTest {
    val eventList = mutableListOf<String>()
    val agent1 =
      DummyAgent(
        "agent1",
        onRunAsync = {
          eventList.add("agent1")
          createEvent("agent1", "msg1")
        },
      )
    val agent2 =
      DummyAgent(
        "agent2",
        onRunAsync = {
          eventList.add("agent2")
          createEvent("agent2", "msg2")
        },
      )

    val loopAgent = LoopAgent(name = "loop", subAgents = listOf(agent1, agent2), maxIterations = 2)
    loopAgent.runAsync(createTestContext()).toList()

    assertEquals(listOf("agent1", "agent2", "agent1", "agent2"), eventList)
  }

  @Test
  fun testRunAsyncWithPauseInvocation() = runTest {
    val eventList = mutableListOf<String>()
    val agent1 =
      DummyAgent(
        "agent1",
        onRunAsync = {
          eventList.add("agent1")
          createEvent("agent1", "msg1")
        },
      )
    // Pause event must include a function call whose id matches an entry in longRunningToolIds;
    // shouldPauseInvocation matches them per call (mirroring Python's request_confirmation/long-
    // running tool plumbing).
    val pauseEvent =
      Event(
        author = "agent2",
        content =
          Content(
            role = Role.MODEL,
            parts =
              listOf(
                Part(
                  functionCall = FunctionCall(name = "long_tool", args = emptyMap(), id = "tool1")
                )
              ),
          ),
        longRunningToolIds = setOf("tool1"),
      )
    val agent2 =
      DummyAgent(
        "agent2",
        onRunAsync = {
          eventList.add("agent2")
          pauseEvent
        },
      )

    val loopAgent = LoopAgent(name = "loop", subAgents = listOf(agent1, agent2), maxIterations = 2)
    loopAgent.runAsync(createTestContext()).toList()

    // It should stop after agent2 pauses, so only agent1 and agent2 were run once.
    assertEquals(listOf("agent1", "agent2"), eventList)
  }

  @Test
  fun runAsync_resumingFromMiddle_restoresIterationAndAgent() = runTest {
    val eventList = mutableListOf<String>()
    val agent1 =
      DummyAgent(
        "agent1",
        onRunAsync = {
          eventList.add("agent1")
          createEvent("agent1", "msg1")
        },
      )
    val agent2 =
      DummyAgent(
        "agent2",
        onRunAsync = {
          eventList.add("agent2")
          createEvent("agent2", "msg2")
        },
      )

    val loopAgent = LoopAgent(name = "loop", subAgents = listOf(agent1, agent2), maxIterations = 3)

    val context =
      InvocationContext(
        session = testSession(),
        runConfig = null,
        agent = DummyAgent("dummy"),
        resumabilityConfig = ResumabilityConfig(isResumable = true),
      )

    context.agentStates["loop"] = LoopAgentState("agent2", 1).toStateValue()

    loopAgent.runAsync(context).toList()

    assertEquals(listOf("agent2", "agent1", "agent2"), eventList)
  }

  @Test
  fun testLoopNotResumable_doesNotEmitEndOfAgent() = runTest {
    var count = 0
    val agent1 =
      DummyAgent(
        "agent1",
        onRunAsync = {
          count++
          createEvent("agent1", "msg1")
        },
      )

    val loopAgent = LoopAgent(name = "loop", subAgents = listOf(agent1), maxIterations = 1)

    val context =
      InvocationContext(
        session = testSession(),
        runConfig = null,
        agent = DummyAgent("dummy"),
        resumabilityConfig = ResumabilityConfig(isResumable = false),
      )

    val events = loopAgent.runAsync(context).toList()

    assertEquals(0, events.count { it.actions.endOfAgent })
  }
}
