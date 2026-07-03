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

import com.google.adk.kt.agents.BaseAgent
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.apps.App
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.telemetry.TelemetryAttributes
import com.google.adk.kt.testing.DummyAgent
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.DummyTracer
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.userMessage
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for telemetry emission in [InMemoryRunner] and [BaseAgent].
 *
 * The tracer is provided per-application via [App.tracer] (no global state); recording it in
 * [tracer] proves the app-scoped tracer is what the runner actually uses.
 */
class RunnerTelemetryTest {

  private val tracer = DummyTracer()

  private fun runnerFor(agent: BaseAgent): InMemoryRunner =
    InMemoryRunner(App(appName = "telemetryapp", rootAgent = agent, tracer = tracer))

  @Test
  fun runAsync_emitsTelemetrySpans() = runBlocking {
    val runner = runnerFor(DummyAgent(name = "telemetry-agent"))

    runner
      .runAsync(userId = "user1", sessionId = "session1", newMessage = userMessage("Hello"))
      .toList()

    val spans = tracer.recordedSpans
    assertTrue("Should have recorded spans", spans.isNotEmpty())
    assertTrue("Should have found 'invocation' span", spans.any { it.name == "invocation" })

    val invokeAgentSpan = spans.find { it.name == "invoke_agent telemetry-agent" }
    assertTrue("Should have found 'invoke_agent' span", invokeAgentSpan != null)
    assertEquals(
      "gcp.vertex.agent",
      invokeAgentSpan!!.attributes[TelemetryAttributes.GEN_AI_SYSTEM],
    )
    assertEquals(
      "telemetry-agent",
      invokeAgentSpan.attributes[TelemetryAttributes.GEN_AI_AGENT_NAME],
    )
  }

  @Test
  fun runAsync_withTracerOnBareAgentRunner_emitsSpans() = runBlocking {
    // Runners built from a bare agent (no [App]) -- e.g. hosts that don't construct an App -- can
    // still get telemetry by passing a tracer to the field-based constructor.
    val runner = InMemoryRunner(agent = DummyAgent(name = "telemetry-agent"), tracer = tracer)

    runner
      .runAsync(userId = "user1", sessionId = "session1", newMessage = userMessage("Hello"))
      .toList()

    assertTrue("Should have recorded spans", tracer.recordedSpans.isNotEmpty())
    assertTrue(
      "Should have found 'invocation' span",
      tracer.recordedSpans.any { it.name == "invocation" },
    )
  }

  // ---------------------------------------------------------------------------
  // Python parity: mirrors the Python suite
  // tests/unittests/telemetry/test_spans.py::test_trace_agent_invocation.
  // ---------------------------------------------------------------------------

  @Test
  fun runAsync_invokeAgent_setsParityAttributes() = runBlocking {
    val model =
      DummyModel.createSequential("mock-model", listOf(LlmResponse(content = modelMessage("hi"))))
    val agent = LlmAgent(name = "telemetry-agent", model = model, description = "A telemetry agent")
    val runner = runnerFor(agent)

    runner
      .runAsync(userId = "user1", sessionId = "session1", newMessage = userMessage("Hello"))
      .toList()

    val span = tracer.recordedSpans.find { it.name == "invoke_agent telemetry-agent" }
    assertTrue("Should have found 'invoke_agent' span", span != null)
    assertEquals("invoke_agent", span!!.attributes[TelemetryAttributes.GEN_AI_OPERATION_NAME])
    assertEquals("A telemetry agent", span.attributes[TelemetryAttributes.GEN_AI_AGENT_DESCRIPTION])
    assertEquals("session1", span.attributes[TelemetryAttributes.GEN_AI_CONVERSATION_ID])
  }
}
