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
import com.google.adk.kt.events.Event
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.Model
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.telemetry.Telemetry
import com.google.adk.kt.telemetry.TelemetryAttributes
import com.google.adk.kt.testing.DummyLiveModel
import com.google.adk.kt.testing.DummyLiveStep.EndStream
import com.google.adk.kt.testing.DummyLiveStep.Fail
import com.google.adk.kt.testing.DummyLiveStep.Respond
import com.google.adk.kt.testing.DummyTracer
import com.google.adk.kt.testing.connectionDrop
import com.google.adk.kt.testing.goAway
import com.google.adk.kt.testing.turnCompleteStep
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.testing.wholeText
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.LiveServerSessionResumptionUpdate
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest

/**
 * Covers the `send_data` span.
 *
 * A live run makes no `call_llm` calls, so without this span the one request it does send, the
 * conversation so far handed over at connect, appears in no trace at all. Tests that reconnect use
 * `runTest` so the reconnect backoff runs on virtual time.
 */
class LlmAgentLiveTelemetryTest {

  private val tracer = DummyTracer()

  @BeforeTest
  fun setUp() {
    Telemetry.setTracerForTest(tracer)
  }

  @AfterTest
  fun tearDown() {
    Telemetry.resetTracer()
  }

  /** Runs a live turn against a session already holding [history]. */
  private suspend fun runWithHistory(
    history: Content?,
    model: Model = DummyLiveModel(listOf(EndStream)),
    queue: LiveRequestQueue = LiveRequestQueue().also { it.close() },
  ) {
    val runner = InMemoryRunner(agent = LlmAgent(name = "live_agent", model = model))
    if (history != null) {
      val session = runner.sessionService.createSession(SessionKey(runner.appName, "u", "s"))
      val unused =
        runner.sessionService.appendEvent(session, Event(author = "user", content = history))
    }
    val unusedEvents = runner.runLive("u", "s", queue).toList()
  }

  private fun sendDataSpans() = tracer.recordedSpans.filter { it.name == "send_data" }

  @Test
  fun runLive_recordsNoInvocationSpan(): Unit = runBlocking {
    // ADK Python opens an invocation span only for turn-based runs; the agent's span still records.
    runWithHistory(history = null)

    val names = tracer.recordedSpans.map { it.name }
    assertWithMessage("expected the agent's span, saw ${names.size}")
      .that(names)
      .contains("invoke_agent live_agent")
    assertWithMessage("a live run must not open an invocation span")
      .that(names)
      .doesNotContain("invocation")
  }

  @Test
  fun runLive_dropAfterAHandleUpdate_recordsOneSendDataSpanAcrossBothConnections(): Unit = runTest {
    // Only the first connection sends the history; the resume reuses the server's copy.
    val queue = LiveRequestQueue()
    val model =
      DummyLiveModel.perConnection(
        listOf(
          listOf(
            Respond(
              LlmResponse(
                liveSessionResumptionUpdate =
                  LiveServerSessionResumptionUpdate(newHandle = "h-1", resumable = true)
              )
            ),
            Fail(connectionDrop()),
          ),
          listOf(EndStream),
        ),
        onConnect = { if (it == 1) queue.close() },
      )

    runWithHistory(userMessage("earlier turn"), model, queue)

    assertThat(model.connections.size).isEqualTo(2)
    assertThat(sendDataSpans().size).isEqualTo(1)
  }

  @Test
  fun runLive_freshRestartAfterAGoAway_recordsASecondSpanWithItsOwnEventId(): Unit = runTest {
    // A handle-less go-away restarts the session, which resends the history, as in ADK Python.
    val queue = LiveRequestQueue()
    val model =
      DummyLiveModel.perConnection(
        listOf(listOf(wholeText("hi"), turnCompleteStep(), Respond(goAway())), listOf(EndStream)),
        onConnect = { if (it == 1) queue.close() },
      )

    runWithHistory(userMessage("earlier turn"), model, queue)

    val eventIds =
      sendDataSpans().map { it.attributes[TelemetryAttributes.GCP_VERTEX_AGENT_EVENT_ID] }
    assertThat(model.connections.size).isEqualTo(2)
    assertWithMessage("the restarted session sends the history again")
      .that(eventIds.size)
      .isEqualTo(2)
    assertWithMessage("each session gets its own event id")
      .that(eventIds.distinct().size)
      .isEqualTo(2)
  }
}
