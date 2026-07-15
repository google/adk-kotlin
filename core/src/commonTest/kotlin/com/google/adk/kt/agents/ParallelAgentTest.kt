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

import com.google.adk.kt.events.Event
import com.google.adk.kt.testing.DummyAgent
import com.google.adk.kt.testing.testInvocationContext
import com.google.adk.kt.testing.userMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

class ParallelAgentTest {

  @Test
  fun runAsync_multipleSubAgents_mergesEvents() = runTest {
    val event1 = Event(author = "sub1", content = userMessage("Hello from sub1"))
    val event2 = Event(author = "sub2", content = userMessage("Hello from sub2"))
    val subAgent1 = DummyAgent("sub1", onRunAsync = { emit(event1) })
    val subAgent2 = DummyAgent("sub2", onRunAsync = { emit(event2) })
    val parallelAgent = ParallelAgent(name = "parallel", subAgents = listOf(subAgent1, subAgent2))
    val context = createTestContext(parallelAgent)

    val events = parallelAgent.runAsync(context).toList()

    // 1 state event + 2 events from sub-agents
    assertEquals(3, events.size)
    assertTrue(events.any { it.author == "parallel" })
    assertTrue(events.any { it.author == "sub1" })
    assertTrue(events.any { it.author == "sub2" })
  }

  @Test
  fun runAsync_subAgentThrows_propagatesError() = runTest {
    val subAgent1 = DummyAgent("sub1", onRunAsync = { throw RuntimeException("Failed") })
    val subAgent2 = DummyAgent("sub2", onRunAsync = { emit(Event(author = "sub2")) })
    val parallelAgent = ParallelAgent(name = "parallel", subAgents = listOf(subAgent1, subAgent2))
    val context = createTestContext(parallelAgent)

    try {
      parallelAgent.runAsync(context).toList()
      fail("Expected RuntimeException")
    } catch (e: RuntimeException) {
      assertEquals("Failed", e.message)
    }
  }

  @Test
  fun runAsync_subAgentPauses_doesNotEndParallelAgent() = runTest {
    val pauseEvent = Event(author = "sub1", longRunningToolIds = setOf("tool1"))
    val subAgent = DummyAgent("sub1", onRunAsync = { _ -> emit(pauseEvent) })
    val parallelAgent = ParallelAgent(name = "parallel", subAgents = listOf(subAgent))
    val context = createTestContext(parallelAgent)

    parallelAgent.runAsync(context).toList()

    assertFalse(context.endOfAgents["parallel"] ?: false)
  }

  @Test
  fun runAsync_allSubAgentsEnd_endsParallelAgent() = runTest {
    val event = Event(author = "sub1", content = userMessage("Hello"))
    val subAgent =
      DummyAgent(
        "sub1",
        onRunAsync = { ctx ->
          emit(event)
          ctx.endOfAgents["sub1"] = true
        },
      )
    val parallelAgent = ParallelAgent(name = "parallel", subAgents = listOf(subAgent))
    val context = createTestContext(parallelAgent)

    parallelAgent.runAsync(context).toList()

    assertTrue(context.endOfAgents["parallel"] == true)
  }

  @Test
  fun runAsync_emptySubAgents_doesNotEmitEvents() = runTest {
    val parallelAgent = ParallelAgent(name = "parallel", subAgents = emptyList())
    val context = createTestContext(parallelAgent)

    val events = parallelAgent.runAsync(context).toList()

    assertTrue(events.isEmpty(), "Expected no events to be emitted")
  }

  /**
   * Each parallel sub-agent runs on its own isolated branch `<parallel>.<sub>`, and a sub-agent's
   * own descendants keep that same branch (a plain run does not deepen it). Ported from Python ADK
   * 1.x `agents/test_parallel_agent.py::test_run_async_branches`.
   */
  @Test
  fun runAsync_subAgentsRunOnIsolatedBranches() = runTest {
    val branches = mutableMapOf<String, String?>()
    val recordBranch: suspend FlowCollector<Event>.(InvocationContext) -> Unit = { ctx ->
      branches[ctx.agent.name] = ctx.branch
    }
    val agent1 = DummyAgent("agent1", onRunAsync = recordBranch)
    val agent2 = DummyAgent("agent2", onRunAsync = recordBranch)
    val agent3 = DummyAgent("agent3", onRunAsync = recordBranch)
    val sequential = SequentialAgent(name = "sequential", subAgents = listOf(agent2, agent3))
    val parallel = ParallelAgent(name = "parallel", subAgents = listOf(sequential, agent1))
    val context = testInvocationContext(agent = parallel)

    parallel.runAsync(context).toList()

    // A direct sub-agent runs on `<parallel>.<sub>`.
    assertEquals("parallel.agent1", branches["agent1"])
    // The sequential sub-agent's own children share the sequential's branch (no extra deepening).
    assertEquals("parallel.sequential", branches["agent2"])
    assertEquals("parallel.sequential", branches["agent3"])
  }

  private fun createTestContext(agent: BaseAgent): InvocationContext =
    testInvocationContext(
      agent = agent,
      resumabilityConfig = ResumabilityConfig(isResumable = true),
    )

  @Test
  fun runAsync_resuming_skipsCompletedAgents() = runTest {
    val event2 = Event(author = "sub2", content = userMessage("Hello from sub2"))

    var sub1Run = false
    var sub2Run = false

    val subAgent1 = DummyAgent("sub1", onRunAsync = { sub1Run = true })
    val subAgent2 =
      DummyAgent(
        "sub2",
        onRunAsync = {
          sub2Run = true
          emit(event2)
        },
      )

    val parallelAgent = ParallelAgent(name = "parallel", subAgents = listOf(subAgent1, subAgent2))
    val context = createTestContext(parallelAgent)

    // Mark sub1 as completed in context
    context.endOfAgents["sub1"] = true

    val events = parallelAgent.runAsync(context).toList()

    assertFalse(sub1Run, "sub1 should have been skipped")
    assertTrue(sub2Run, "sub2 should have been run")

    val subAgentEvents = events.filter { it.author == "sub1" || it.author == "sub2" }
    assertEquals(1, subAgentEvents.size)
    assertEquals("sub2", subAgentEvents[0].author)
  }
}
