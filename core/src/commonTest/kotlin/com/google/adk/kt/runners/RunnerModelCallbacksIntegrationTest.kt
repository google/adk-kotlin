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

import com.google.adk.kt.agents.CallbackContext
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.LoopAgent
import com.google.adk.kt.agents.RunConfig
import com.google.adk.kt.agents.StreamingMode
import com.google.adk.kt.apps.App
import com.google.adk.kt.callbacks.AfterModelCallback
import com.google.adk.kt.callbacks.BeforeModelCallback
import com.google.adk.kt.callbacks.CallbackChoice
import com.google.adk.kt.callbacks.OnModelErrorCallback
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.plugins.Plugin
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.types.Part
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest

/**
 * End-to-end tests for the model-callback extension points on [LlmAgent] through a real
 * [InMemoryRunner]. Mirrors Python ADK's `agents/test_llm_agent_callbacks.py` and
 * `flows/llm_flows/test_model_callbacks.py`.
 */
class RunnerModelCallbacksIntegrationTest {

  /**
   * `Break` from `beforeModelCallback` must skip the model call entirely. The underlying model is
   * never invoked; the callback's `LlmResponse` is the only model output.
   */
  @Test
  fun runAsync_beforeModelCallbackReturnsBreak_shortCircuitsModelCall() = runTest {
    var modelCalls = 0
    val agent =
      LlmAgent(
        name = "test-agent",
        model =
          DummyModel("mock-model") {
            modelCalls++
            flowOf(LlmResponse(content = modelMessage("from-real-model")))
          },
        beforeModelCallbacks =
          listOf(
            BeforeModelCallback { _, _ ->
              CallbackChoice.Break(LlmResponse(content = modelMessage("from-callback")))
            }
          ),
      )
    val runner = InMemoryRunner(agent = agent)

    val events =
      runner
        .runAsync(userId = "user1", sessionId = "session1", newMessage = userMessage("hi"))
        .toList()

    assertEquals(0, modelCalls)
    val modelEvent =
      events.firstOrNull { it.author == "test-agent" } ?: fail("expected an agent-authored event")
    assertEquals("from-callback", modelEvent.content?.parts?.singleOrNull()?.text)
  }

  /**
   * `Continue` with a mutated [LlmRequest] must hand the modified request to the actual model.
   * Captures the request the model receives and asserts on the mutation.
   */
  @Test
  fun runAsync_beforeModelCallbackReturnsContinueWithMutatedRequest_modelSeesMutation() = runTest {
    var capturedRequest: LlmRequest? = null
    val capturingModel =
      DummyModel("capturing-model") { request ->
        flow {
          capturedRequest = request
          emit(LlmResponse(content = modelMessage("ok")))
        }
      }
    val injected = userMessage("INJECTED")
    val agent =
      LlmAgent(
        name = "test-agent",
        model = capturingModel,
        beforeModelCallbacks =
          listOf(
            BeforeModelCallback { _, request ->
              CallbackChoice.Continue(request.appendContent(injected))
            }
          ),
      )
    val runner = InMemoryRunner(agent = agent)

    runner
      .runAsync(userId = "user1", sessionId = "session1", newMessage = userMessage("original"))
      .toList()

    val request = capturedRequest ?: fail("model should have been called once")
    assertTrue(request.contents.any { it.parts.any { p -> p.text == "INJECTED" } })
  }

  /** `afterModelCallback` must replace the model's response before it is published as an event. */
  @Test
  fun runAsync_afterModelCallback_replacesModelResponse() = runTest {
    val agent =
      LlmAgent(
        name = "test-agent",
        model =
          DummyModel("mock-model") {
            flowOf(LlmResponse(content = modelMessage("from-real-model")))
          },
        afterModelCallbacks =
          listOf(
            AfterModelCallback { _, _ ->
              LlmResponse(content = modelMessage("from-after-callback"))
            }
          ),
      )
    val runner = InMemoryRunner(agent = agent)

    val events =
      runner
        .runAsync(userId = "user1", sessionId = "session1", newMessage = userMessage("hi"))
        .toList()

    val modelEvent =
      events.firstOrNull { it.author == "test-agent" } ?: fail("expected an agent-authored event")
    assertEquals("from-after-callback", modelEvent.content?.parts?.singleOrNull()?.text)
  }

  /**
   * If the model throws, `Break` from `onModelErrorCallback` must convert the error into a normal
   * model event.
   */
  @Test
  fun runAsync_onModelErrorCallbackReturnsBreak_modelErrorBecomesNormalResponse() = runTest {
    val agent =
      LlmAgent(
        name = "test-agent",
        model = DummyModel("failing-model") { flow { throw RuntimeException("model boom") } },
        onModelErrorCallbacks =
          listOf(
            OnModelErrorCallback { _, _, _ ->
              CallbackChoice.Break(LlmResponse(content = modelMessage("recovered")))
            }
          ),
      )
    val runner = InMemoryRunner(agent = agent)

    val events =
      runner
        .runAsync(userId = "user1", sessionId = "session1", newMessage = userMessage("hi"))
        .toList()

    val modelEvent =
      events.firstOrNull { it.author == "test-agent" } ?: fail("expected a recovered model event")
    assertEquals("recovered", modelEvent.content?.parts?.singleOrNull()?.text)
  }

  /** Without an `onModelErrorCallback`, a model exception must propagate up through the runner. */
  @Test
  fun runAsync_modelThrowsWithoutErrorCallback_propagatesException() = runTest {
    val agent =
      LlmAgent(
        name = "test-agent",
        model = DummyModel("failing-model") { flow { throw RuntimeException("model boom") } },
      )
    val runner = InMemoryRunner(agent = agent)

    var threw = false
    try {
      runner
        .runAsync(userId = "user1", sessionId = "session1", newMessage = userMessage("hi"))
        .toList()
    } catch (e: RuntimeException) {
      threw = true
      assertTrue(
        (e.message ?: "").contains("model boom") ||
          generateSequence<Throwable>(e.cause) { it.cause }
            .any { (it.message ?: "").contains("model boom") }
      )
    }
    assertTrue(threw)
  }

  /**
   * A state write from `beforeModelCallback` must reach the event the step emits, and from there
   * the session, as in Python and Java ADK. Otherwise it lands on a context nothing reads and is
   * lost silently.
   */
  @Test
  fun runAsync_beforeModelCallbackWritesState_deltaReachesEmittedEventAndSession() = runTest {
    val agent =
      LlmAgent(
        name = "test-agent",
        model = DummyModel("mock-model") { flowOf(LlmResponse(content = modelMessage("ok"))) },
        beforeModelCallbacks =
          listOf(
            BeforeModelCallback { context, request ->
              context.updateState("before_key", "before_value")
              CallbackChoice.Continue(request)
            }
          ),
      )
    val runner = InMemoryRunner(agent = agent)

    val events =
      runner
        .runAsync(userId = "user1", sessionId = "session1", newMessage = userMessage("hi"))
        .toList()

    assertEquals(
      "before_value",
      events.firstNotNullOfOrNull { it.actions.stateDelta["before_key"] },
      "a beforeModel state write must be carried by an emitted event",
    )
    val session = runner.sessionService.getSession(SessionKey(runner.appName, "user1", "session1"))
    assertEquals(
      "before_value",
      session?.state?.get("before_key"),
      "and must therefore be persisted to the session",
    )
  }

  /** As above for `afterModelCallback`, which shares the step's callback context. */
  @Test
  fun runAsync_afterModelCallbackWritesState_deltaReachesEmittedEventAndSession() = runTest {
    val agent =
      LlmAgent(
        name = "test-agent",
        model = DummyModel("mock-model") { flowOf(LlmResponse(content = modelMessage("ok"))) },
        afterModelCallbacks =
          listOf(
            AfterModelCallback { context, response ->
              context.updateState("after_key", "after_value")
              response
            }
          ),
      )
    val runner = InMemoryRunner(agent = agent)

    val events =
      runner
        .runAsync(userId = "user1", sessionId = "session1", newMessage = userMessage("hi"))
        .toList()

    assertEquals(
      "after_value",
      events.firstNotNullOfOrNull { it.actions.stateDelta["after_key"] },
      "an afterModel state write must be carried by an emitted event",
    )
    val session = runner.sessionService.getSession(SessionKey(runner.appName, "user1", "session1"))
    assertEquals("after_value", session?.state?.get("after_key"), "and must be persisted")
  }

  /**
   * The short-circuit path emits the callback's own response, so it must carry the actions written
   * before the `Break` rather than a fresh, empty set.
   */
  @Test
  fun runAsync_beforeModelCallbackWritesStateThenBreaks_deltaStillReachesEmittedEvent() = runTest {
    val agent =
      LlmAgent(
        name = "test-agent",
        model =
          DummyModel("mock-model") { flowOf(LlmResponse(content = modelMessage("unreachable"))) },
        beforeModelCallbacks =
          listOf(
            BeforeModelCallback { context, _ ->
              context.updateState("break_key", "break_value")
              CallbackChoice.Break(LlmResponse(content = modelMessage("from-callback")))
            }
          ),
      )
    val runner = InMemoryRunner(agent = agent)

    val events =
      runner
        .runAsync(userId = "user1", sessionId = "session1", newMessage = userMessage("hi"))
        .toList()

    assertEquals(
      "break_value",
      events.firstNotNullOfOrNull { it.actions.stateDelta["break_key"] },
      "a state write made before Break must survive the short-circuit",
    )
  }

  /** The recovered event from `onModelErrorCallback` must likewise carry the callback's writes. */
  @Test
  fun runAsync_onModelErrorCallbackWritesState_deltaReachesRecoveredEvent() = runTest {
    val agent =
      LlmAgent(
        name = "test-agent",
        model = DummyModel("failing-model") { flow { throw RuntimeException("model boom") } },
        onModelErrorCallbacks =
          listOf(
            OnModelErrorCallback { context, _, _ ->
              context.updateState("error_key", "error_value")
              CallbackChoice.Break(LlmResponse(content = modelMessage("recovered")))
            }
          ),
      )
    val runner = InMemoryRunner(agent = agent)

    val events =
      runner
        .runAsync(userId = "user1", sessionId = "session1", newMessage = userMessage("hi"))
        .toList()

    assertEquals(
      "error_value",
      events.firstNotNullOfOrNull { it.actions.stateDelta["error_key"] },
      "an onModelError state write must be carried by the recovered event",
    )
  }

  /**
   * The streaming loop runs the after-model callback once per chunk, so the actions transfer runs
   * repeatedly while the event's id and timestamp rotate underneath it. Every write must survive to
   * the final event and the session.
   */
  @Test
  fun runAsync_afterModelCallbackWritesPerChunk_allDeltasSurviveTheStream() = runTest {
    var chunk = 0
    val agent =
      LlmAgent(
        name = "test-agent",
        model =
          DummyModel("mock-model") {
            flowOf(
              LlmResponse(content = modelMessage("part one "), partial = true),
              LlmResponse(content = modelMessage("part two")),
            )
          },
        afterModelCallbacks =
          listOf(
            AfterModelCallback { context, response ->
              context.updateState("chunk_${chunk++}", "seen")
              response
            }
          ),
      )
    val runner = InMemoryRunner(agent = agent)

    val events =
      runner
        .runAsync(
          userId = "user1",
          sessionId = "session1",
          newMessage = userMessage("hi"),
          runConfig = RunConfig(streamingMode = StreamingMode.SSE),
        )
        .toList()

    assertTrue(chunk > 1, "the model should have streamed more than one chunk, saw $chunk")
    val written = (0 until chunk).map { "chunk_$it" }
    val session = runner.sessionService.getSession(SessionKey(runner.appName, "user1", "session1"))
    assertEquals(
      written,
      written.filter { session?.state?.get(it) == "seen" },
      "every per-chunk write should reach the session",
    )
    val finalEvent = events.last { !it.partial && it.author == "test-agent" }
    assertEquals(
      written,
      written.filter { finalEvent.actions.stateDelta[it] == "seen" },
      "and the final event should carry them all",
    )
  }

  /**
   * The transfer moves the whole actions object, so a model callback can also set the control-flow
   * signals, not just the deltas. `escalate` is the load-bearing one: it breaks an enclosing
   * [LoopAgent] out of its loop.
   */
  @Test
  fun runAsync_beforeModelCallbackSetsEscalate_breaksTheEnclosingLoop() = runTest {
    var modelCalls = 0
    val child =
      LlmAgent(
        name = "child",
        model =
          DummyModel("mock-model") {
            modelCalls++
            flowOf(LlmResponse(content = modelMessage("ok")))
          },
        beforeModelCallbacks =
          listOf(
            BeforeModelCallback { context, request ->
              context.eventActions.escalate = true
              CallbackChoice.Continue(request)
            }
          ),
      )
    val runner =
      InMemoryRunner(
        app =
          App(
            appName = "escalate_app",
            rootAgent = LoopAgent("loop", maxIterations = 5, subAgents = listOf(child)),
          )
      )

    val events =
      runner
        .runAsync(userId = "user1", sessionId = "session1", newMessage = userMessage("hi"))
        .toList()

    assertTrue(events.any { it.actions.escalate }, "escalate should reach an emitted event")
    assertEquals(1, modelCalls, "escalate should stop the loop after the first iteration")
  }

  /**
   * Artifact deltas take the other route out of the callback context: `saveArtifact` mutates the
   * actions in place rather than replacing them. Covered through a plugin, the entry point where a
   * lost delta is hardest to notice.
   */
  @Test
  fun runAsync_pluginBeforeModelSavesArtifact_artifactDeltaReachesEmittedEvent() = runTest {
    var savedVersion = -1
    val plugin =
      object : Plugin {
        override val name = "artifact-saving-plugin"

        override suspend fun beforeModel(
          context: CallbackContext,
          request: LlmRequest,
        ): CallbackChoice<LlmRequest, LlmResponse> {
          savedVersion = context.saveArtifact("note.txt", Part(text = "hi"))
          return CallbackChoice.Continue(request)
        }
      }
    val agent =
      LlmAgent(
        name = "test-agent",
        model = DummyModel("mock-model") { flowOf(LlmResponse(content = modelMessage("ok"))) },
      )
    val runner =
      InMemoryRunner(
        app = App(appName = "artifact_app", rootAgent = agent, plugins = listOf(plugin))
      )

    val events =
      runner
        .runAsync(userId = "user1", sessionId = "session1", newMessage = userMessage("hi"))
        .toList()

    assertEquals(0, savedVersion, "the first save of a filename is version 0")
    assertEquals(
      0,
      events.firstNotNullOfOrNull { it.actions.artifactDelta["note.txt"] },
      "a beforeModel artifact save must be carried by an emitted event",
    )
  }

  /**
   * A cancellation raised by the model must not be routed into the `onModelError` recovery
   * pipeline. `CancellationException` is an `Exception` in Kotlin, so a recovering callback could
   * otherwise swallow it; the model-call path must rethrow it first. Python ADK 1.x is safe
   * structurally (CancelledError is a BaseException); see base_llm_flow._run_and_handle_error.
   */
  @Test
  fun runAsync_modelThrowsCancellation_recoveryCallbackDoesNotSwallowIt(): Unit = runBlocking {
    val agent =
      LlmAgent(
        name = "test-agent",
        model =
          DummyModel("cancelling-model") { flow { throw CancellationException("cancelled") } },
        onModelErrorCallbacks =
          listOf(
            OnModelErrorCallback { _, _, _ ->
              CallbackChoice.Break(LlmResponse(content = modelMessage("recovered")))
            }
          ),
      )
    val runner = InMemoryRunner(agent = agent)

    assertFailsWith<CancellationException> {
      runner
        .runAsync(userId = "user1", sessionId = "session1", newMessage = userMessage("hi"))
        .toList()
    }
  }
}
