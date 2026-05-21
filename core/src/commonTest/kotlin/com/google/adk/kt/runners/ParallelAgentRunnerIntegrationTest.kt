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
import com.google.adk.kt.agents.ParallelAgent
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.DummyTool
import com.google.adk.kt.testing.modelFunctionCallResponse
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.userMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/**
 * [ParallelAgent] wired as the root of a [Runner], complementing the unit-level coverage in
 * [com.google.adk.kt.agents.ParallelAgentTest] (which calls `BaseAgent.runAsync` directly) and the
 * success-path coverage in [WorkflowAgentsIntegrationTest]. Mirrors Python ADK's
 * `test_parallel_agent.py` and Go ADK's `TestParallelAgent_PropagatesContextError` /
 * `TestParallelAgent_StateSync` in `agent/workflowagents/parallelagent/agent_test.go`.
 *
 * Adds two scenarios not exercised elsewhere: failure of one sub-agent propagating through the
 * public [Runner] API, and a sub-agent's tool-driven `stateDelta` reaching the persisted session
 * state after the parallel agent completes.
 */
class ParallelAgentRunnerIntegrationTest {

  /**
   * One of two parallel sub-agents has a model that throws. The runner must propagate the exception
   * out of [Runner.runAsync].`toList()`. Mirrors Go ADK's
   * `TestParallelAgent_PropagatesContextError`.
   */
  @Test
  fun runAsync_parallelAgentRoot_oneSubAgentThrows_runnerSurfacesException() = runTest {
    val parallel =
      ParallelAgent(
        name = "parallel_root",
        description = "Runs healthy and failing in parallel.",
        subAgents =
          listOf(
            LlmAgent(
              name = "healthy",
              description = "Healthy.",
              model =
                DummyModel("healthy-model") {
                  flow { emit(LlmResponse(content = modelMessage("ok"))) }
                },
            ),
            LlmAgent(
              name = "failing",
              description = "Failing.",
              model = DummyModel("failing-model") { flow { throw RuntimeException("kaboom") } },
            ),
          ),
      )
    val runner = InMemoryRunner(agent = parallel)

    val ex =
      assertFailsWith<RuntimeException> {
        runner.runAsync(userId = "u", sessionId = "s", newMessage = userMessage("go")).toList()
      }
    assertEquals("kaboom", ex.message)
  }

  /**
   * Each parallel sub-agent has a tool whose `onRun` writes a unique key into the session's
   * `stateDelta`. After the parallel agent completes, all keys must be visible in `session.state`
   * because every child's `FunctionResponse` event (carrying its `stateDelta`) is appended to the
   * session, and the in-memory session service applies each delta to the stored state. Mirrors Go
   * ADK's `TestParallelAgent_StateSync`.
   */
  @Test
  fun runAsync_parallelAgentRoot_subAgentToolStateDelta_persistedToSessionState() = runTest {
    val children =
      (1..3).map { idx ->
        LlmAgent(
          name = "child_$idx",
          description = "Child $idx",
          model =
            DummyModel.createSequential(
              "model-$idx",
              listOf(
                modelFunctionCallResponse("writer_$idx", id = "call_$idx"),
                LlmResponse(content = modelMessage("child-$idx")),
              ),
            ),
          tools =
            listOf(
              DummyTool(
                name = "writer_$idx",
                onRun = { ctx, _ ->
                  ctx.actions.stateDelta["k$idx"] = "v$idx"
                  mapOf("done" to true)
                },
              )
            ),
        )
      }
    val parallel =
      ParallelAgent(
        name = "parallel_root",
        description = "Three writer children.",
        subAgents = children,
      )
    val runner = InMemoryRunner(agent = parallel)

    runner.runAsync(userId = "u", sessionId = "s", newMessage = userMessage("write all")).toList()

    val finalSession = runner.sessionService.getSession(SessionKey(runner.appName, "u", "s"))
    val state = finalSession?.state
    assertTrue(state != null && state["k1"] == "v1", "k1 missing or wrong; state=$state")
    assertTrue(state["k2"] == "v2", "k2 missing or wrong; state=$state")
    assertTrue(state["k3"] == "v3", "k3 missing or wrong; state=$state")
  }
}
