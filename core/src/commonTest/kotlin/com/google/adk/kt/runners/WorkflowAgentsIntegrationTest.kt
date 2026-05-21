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

import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.LoopAgent
import com.google.adk.kt.agents.ParallelAgent
import com.google.adk.kt.agents.SequentialAgent
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.modelFunctionCallResponse
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.textAgent
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.tools.ExitLoopTool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/**
 * Workflow agents ([SequentialAgent], [ParallelAgent], [LoopAgent]) used as the [InMemoryRunner]'s
 * root with real [LlmAgent] children. Mirrors Python ADK's `agents/test_sequential_agent.py`,
 * `test_parallel_agent.py`, and `test_loop_agent.py`.
 *
 * Per-agent unit tests run the workflow agents directly against a hand-built `InvocationContext`.
 * These tests instead drive everything through the full Runner with `LlmAgent` + `DummyModel`
 * children, exercising session bootstrap, event persistence, and the agent-context plumbing the way
 * real applications do.
 */
class WorkflowAgentsIntegrationTest {

  /** A [SequentialAgent] containing two text-emitting children must run them in declared order. */
  @Test
  fun runAsync_sequentialAgentRoot_executesChildrenInOrder() = runTest {
    val sequential =
      SequentialAgent(
        name = "seq",
        subAgents = listOf(textAgent("first", "first-text"), textAgent("second", "second-text")),
      )
    val runner = InMemoryRunner(agent = sequential)

    val events =
      runner
        .runAsync(userId = "user1", sessionId = "session1", newMessage = userMessage("go"))
        .toList()

    val agentTexts =
      events
        .filter { it.author == "first" || it.author == "second" }
        .map { it.author to it.content?.parts?.singleOrNull()?.text }
    assertEquals(listOf("first" to "first-text", "second" to "second-text"), agentTexts)
  }

  /**
   * A [ParallelAgent] dispatches all children concurrently and surfaces every child's event in the
   * merged output. Order across children is non-deterministic; assert on the set.
   */
  @Test
  fun runAsync_parallelAgentRoot_emitsEventsFromAllChildren() = runTest {
    val parallel =
      ParallelAgent(
        name = "par",
        subAgents = listOf(textAgent("agent_a", "from-a"), textAgent("agent_b", "from-b")),
      )
    val runner = InMemoryRunner(agent = parallel)

    val events =
      runner
        .runAsync(userId = "user1", sessionId = "session1", newMessage = userMessage("go"))
        .toList()

    val childPairs =
      events
        .filter { it.author == "agent_a" || it.author == "agent_b" }
        .map { it.author to it.content?.parts?.singleOrNull()?.text }
        .toSet()
    assertEquals(setOf("agent_a" to "from-a", "agent_b" to "from-b"), childPairs)
  }

  /** A [LoopAgent] with `maxIterations=3` and a text-emitting child runs exactly 3 iterations. */
  @Test
  fun runAsync_loopAgentRoot_runsUntilMaxIterations() = runTest {
    val loop =
      LoopAgent(name = "loop", maxIterations = 3, subAgents = listOf(textAgent("looper", "tick")))
    val runner = InMemoryRunner(agent = loop)

    val events =
      runner
        .runAsync(userId = "user1", sessionId = "session1", newMessage = userMessage("go"))
        .toList()

    val tickCount = events.count {
      it.author == "looper" && it.content?.parts?.singleOrNull()?.text == "tick"
    }
    assertEquals(3, tickCount)
  }

  /**
   * A [LoopAgent] containing an [LlmAgent] whose model calls [ExitLoopTool] on the second turn must
   * terminate before reaching `maxIterations`. ExitLoopTool sets `escalate=true` on the tool
   * actions; LoopAgent observes this and breaks out of the loop.
   */
  @Test
  fun runAsync_loopAgentRoot_exitLoopToolEscalates_terminatesEarly() = runTest {
    var modelTurn = 0
    val loop =
      LoopAgent(
        name = "loop",
        // `maxIterations = 10` would let the loop run far beyond the escalate; this test guards
        // that escalation actually short-circuits before we hit that bound.
        maxIterations = 10,
        subAgents =
          listOf(
            LlmAgent(
              name = "looper",
              model =
                DummyModel("loop-model") {
                  modelTurn++
                  flowOf(
                    if (modelTurn == 1) LlmResponse(content = modelMessage("iter-1"))
                    else modelFunctionCallResponse("exit_loop", id = "exit_call_1")
                  )
                },
              tools = listOf(ExitLoopTool()),
            )
          ),
      )
    val runner = InMemoryRunner(agent = loop)

    val events =
      runner
        .runAsync(userId = "user1", sessionId = "session1", newMessage = userMessage("go"))
        .toList()

    assertTrue(events.any { it.functionCalls().any { c -> c.name == "exit_loop" } })
    assertTrue(events.any { it.content?.parts?.singleOrNull()?.text == "iter-1" })
    assertTrue(modelTurn <= 2, "expected loop to terminate after exit_loop, got $modelTurn turns")
  }
}
