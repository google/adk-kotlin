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
import com.google.adk.kt.agents.SequentialAgent
import com.google.adk.kt.events.Event
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.sessions.InMemorySessionService
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.sessions.SessionService
import com.google.adk.kt.testing.DummyAgent
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.DummyTool
import com.google.adk.kt.testing.modelFunctionCallResponse
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.userMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield

/**
 * [ParallelAgent] wired as the root of a [Runner], complementing the unit-level coverage in
 * [com.google.adk.kt.agents.ParallelAgentTest] (which calls `BaseAgent.runAsync` directly) and the
 * success-path coverage in [WorkflowAgentsIntegrationTest]. Mirrors Python ADK's
 * `test_parallel_agent.py` and Go ADK's `TestParallelAgent_PropagatesContextError` /
 * `TestParallelAgent_StateSync` in `agent/workflowagents/parallelagent/agent_test.go`.
 *
 * Adds scenarios not exercised elsewhere: one sub-agent's failure propagating through the public
 * [Runner] API, a sub-agent's tool-driven `stateDelta` reaching the persisted session state after
 * the parallel agent completes, and a sub-agent's next step or an agent after the parallel agent
 * seeing the events the runner appended earlier.
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

  /**
   * Each sequential sub-agent's second step reads the `outputKey` written by its first step. The
   * runner applies that write when appending the first step's event, so the second step must not
   * start before then.
   */
  @Test
  fun runAsync_parallelAgentRoot_sequentialSubAgent_laterStepSeesEarlierOutputKey(): Unit =
    runBlocking {
      assertEquals(
        listOf("draft a", "draft b"),
        runPipelinesReadingDrafts(InMemorySessionService()),
      )
    }

  /** As above, with a session service that suspends before applying the event. */
  @Test
  fun runAsync_parallelAgentRoot_suspendingAppend_laterStepSeesEarlierOutputKey(): Unit =
    runBlocking {
      assertEquals(
        listOf("draft a", "draft b"),
        runPipelinesReadingDrafts(SuspendingSessionService()),
      )
    }

  /** As above, with the branches and the runner on the threads of a multi-threaded dispatcher. */
  @Test
  fun runAsync_parallelAgentRoot_multiThreaded_laterStepSeesEarlierOutputKey(): Unit =
    runBlocking(Dispatchers.Default) {
      repeat(20) {
        assertEquals(
          listOf("draft a", "draft b"),
          runPipelinesReadingDrafts(InMemorySessionService()),
        )
      }
    }

  /** A sequential sub-agent's second LlmAgent sees the first one's reply in its request. */
  @Test
  fun runAsync_parallelAgentRoot_sequentialSubAgent_laterLlmAgentSeesEarlierReply(): Unit =
    runBlocking {
      val reviewerRequests = mutableListOf<LlmRequest>()
      val pipeline =
        SequentialAgent(
          name = "pipeline",
          subAgents =
            listOf(
              writer("a"),
              LlmAgent(
                name = "reviewer",
                model =
                  DummyModel("reviewer-model") { request ->
                    reviewerRequests += request
                    flowOf(LlmResponse(content = modelMessage("ok")))
                  },
              ),
            ),
        )
      val runner =
        InMemoryRunner(agent = ParallelAgent(name = "parallel_root", subAgents = listOf(pipeline)))

      runner.runAsync(userId = "u", sessionId = "s", newMessage = userMessage("go")).toList()

      val texts = reviewerRequests.single().contents.flatMap { it.parts }.mapNotNull { it.text }
      assertTrue(texts.any { "draft a" in it }, "The reviewer's request lacks the writer's reply")
    }

  /**
   * A tool-using sub-agent's second model request carries the function response for the call
   * returned by the first request. Mirrors Go ADK's `TestParallelAgentWithTools`.
   */
  @Test
  fun runAsync_parallelAgentRoot_toolUsingSubAgent_nextRequestHasFunctionResponse(): Unit =
    runBlocking {
      val requests = mutableListOf<LlmRequest>()
      val responses =
        listOf(
          modelFunctionCallResponse("lookup", id = "call_1"),
          LlmResponse(content = modelMessage("done")),
        )
      val child =
        LlmAgent(
          name = "child",
          model =
            DummyModel("child-model") { request ->
              requests += request
              flowOf(responses[requests.size - 1])
            },
          tools = listOf(DummyTool(name = "lookup")),
        )
      val runner =
        InMemoryRunner(agent = ParallelAgent(name = "parallel_root", subAgents = listOf(child)))

      runner.runAsync(userId = "u", sessionId = "s", newMessage = userMessage("go")).toList()

      assertEquals(2, requests.size)
      assertTrue(
        requests[1].contents.any { content ->
          content.parts.any { it.functionResponse?.id == "call_1" }
        },
        "The second model request lacks the function response",
      )
    }

  /**
   * In `SequentialAgent(ParallelAgent(pipelines), aggregator)`, the aggregator runs after the
   * parallel agent and reads the value that each pipeline's second step derived from its first
   * step's output.
   */
  @Test
  fun runAsync_sequentialRoot_aggregatorAfterParallelAgent_seesBranchResults(): Unit = runBlocking {
    var aggregated: List<Any?> = emptyList()
    val parallel =
      ParallelAgent(
        name = "parallel",
        subAgents = listOf(editingPipeline("a"), editingPipeline("b")),
      )
    val aggregator =
      DummyAgent(
        "aggregator",
        onRunAsync = { context ->
          aggregated = listOf("edited_a", "edited_b").map { context.session.state[it] }
        },
      )
    val root = SequentialAgent(name = "root", subAgents = listOf(parallel, aggregator))

    InMemoryRunner(agent = root)
      .runAsync(userId = "u", sessionId = "s", newMessage = userMessage("go"))
      .toList()

    assertEquals(listOf("edited draft a", "edited draft b"), aggregated)
  }

  /**
   * Runs a `ParallelAgent` of two `SequentialAgent(writer, reader)` pipelines and returns what each
   * reader read from its writer's `outputKey`.
   */
  private suspend fun runPipelinesReadingDrafts(sessionService: SessionService): List<Any?> {
    var seenA: Any? = null
    var seenB: Any? = null
    val parallel =
      ParallelAgent(
        name = "parallel_root",
        subAgents = listOf(readingPipeline("a") { seenA = it }, readingPipeline("b") { seenB = it }),
      )

    InMemoryRunner(agent = parallel, sessionService = sessionService)
      .runAsync(userId = "u", sessionId = "s", newMessage = userMessage("go"))
      .toList()
    return listOf(seenA, seenB)
  }

  /** `SequentialAgent(writer, reader)`, where the reader passes its writer's output to [onRead]. */
  private fun readingPipeline(id: String, onRead: (Any?) -> Unit) =
    SequentialAgent(
      name = "pipeline_$id",
      subAgents =
        listOf(
          writer(id),
          DummyAgent(
            "reader_$id",
            onRunAsync = { context -> onRead(context.session.state["draft_$id"]) },
          ),
        ),
    )

  /**
   * `SequentialAgent(writer, editor)`, where the editor saves "edited " plus its writer's output.
   */
  private fun editingPipeline(id: String) =
    SequentialAgent(
      name = "pipeline_$id",
      subAgents =
        listOf(
          writer(id),
          DummyAgent(
            "editor_$id",
            onRunAsync = { context ->
              val edited = "edited ${context.session.state["draft_$id"]}"
              emit(
                Event(
                  author = "editor_$id",
                  actions =
                    EventActions(stateDelta = mutableMapOf<String, Any>("edited_$id" to edited)),
                )
              )
            },
          ),
        ),
    )

  /** An LlmAgent that replies "draft [id]" and saves the reply under `outputKey` "draft_[id]". */
  private fun writer(id: String) =
    LlmAgent(
      name = "writer_$id",
      model =
        DummyModel("writer-model-$id") { flowOf(LlmResponse(content = modelMessage("draft $id"))) },
      outputKey = "draft_$id",
    )

  /**
   * Appends through [InMemorySessionService] after suspending, as a service performing I/O would.
   */
  private class SuspendingSessionService(
    private val delegate: SessionService = InMemorySessionService()
  ) : SessionService by delegate {
    override suspend fun appendEvent(session: Session, event: Event): Event {
      yield()
      return delegate.appendEvent(session, event)
    }
  }
}
