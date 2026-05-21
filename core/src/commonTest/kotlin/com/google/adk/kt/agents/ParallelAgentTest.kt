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
import com.google.adk.kt.testing.DummyAgent
import com.google.adk.kt.testing.testSession
import com.google.adk.kt.testing.userMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
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
    val subAgent = ContextAwareDummyAgent("sub1", onRunAsyncBlock = { ctx -> emit(pauseEvent) })
    val parallelAgent = ParallelAgent(name = "parallel", subAgents = listOf(subAgent))
    val context = createTestContext(parallelAgent)

    parallelAgent.runAsync(context).toList()

    assertFalse(context.endOfAgents["parallel"] ?: false)
  }

  @Test
  fun runAsync_allSubAgentsEnd_endsParallelAgent() = runTest {
    val event = Event(author = "sub1", content = userMessage("Hello"))
    val subAgent =
      ContextAwareDummyAgent(
        "sub1",
        onRunAsyncBlock = { ctx ->
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

  private class ContextAwareDummyAgent(
    name: String,
    val onRunAsyncBlock:
      suspend kotlinx.coroutines.flow.FlowCollector<Event>.(InvocationContext) -> Unit,
  ) : BaseAgent(name = name) {
    override fun runAsyncImpl(context: InvocationContext): kotlinx.coroutines.flow.Flow<Event> =
      kotlinx.coroutines.flow.flow { onRunAsyncBlock(context) }
  }

  private fun createTestContext(agent: BaseAgent): InvocationContext {
    return InvocationContext(
      session = testSession(),
      runConfig = null,
      agent = agent,
      resumabilityConfig = ResumabilityConfig(isResumable = true),
    )
  }

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
