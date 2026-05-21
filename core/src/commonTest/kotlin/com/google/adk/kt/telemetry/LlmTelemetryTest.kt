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

package com.google.adk.kt.telemetry

import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.sessions.InMemorySessionService
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.DummyTracer
import com.google.adk.kt.testing.modelMessage
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

class LlmTelemetryTest {

  private val dummyTracer = DummyTracer()

  @BeforeTest
  fun setUp() {
    Telemetry.setTracerForTest(dummyTracer)
    TelemetryConfig.captureMessageContent = false
  }

  @AfterTest
  fun tearDown() {
    Telemetry.resetTracer()
    TelemetryConfig.captureMessageContent = false
  }

  @Test
  fun runAsync_recordsCallLlmSpan() = runTest {
    val testModel = DummyModel.createSequential("test-model", listOf(LlmResponse(content = modelMessage("Hello"))))
    val agent = LlmAgent(name = "test-agent", model = testModel)
    val sessionService = InMemorySessionService()
    val session = sessionService.createSession(SessionKey("app", "user", "test-session"))
    val context = InvocationContext(agent = agent, session = session, runConfig = null)

    agent.runAsync(context).toList()

    assertEquals(2, dummyTracer.recordedSpans.size)
    val span = dummyTracer.recordedSpans.find { it.name == "call_llm" }
    assertNotNull(span)
    assertEquals("test-model", span.attributes[TelemetryAttributes.GEN_AI_REQUEST_MODEL])
  }

  @Test
  fun runAsync_recordsChunkReceivedEvents() = runTest {
    val testModel =
      DummyModel("test-model") { flowOf(LlmResponse(content = modelMessage("Hel")), LlmResponse(content = modelMessage("lo"))) }
    val agent = LlmAgent(name = "test-agent", model = testModel)
    val sessionService = InMemorySessionService()
    val session = sessionService.createSession(SessionKey("app", "user", "test-session"))
    val context = InvocationContext(agent = agent, session = session, runConfig = null)

    agent.runAsync(context).toList()

    assertEquals(2, dummyTracer.recordedSpans.size)
    val span = dummyTracer.recordedSpans.find { it.name == "call_llm" }
    assertNotNull(span)

    val events = span.events
    assertEquals(2, events.size)
    assertEquals("chunk_received", events[0])
    assertEquals("chunk_received", events[1])
  }
}
