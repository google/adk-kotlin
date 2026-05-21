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

import com.google.adk.kt.telemetry.Telemetry
import com.google.adk.kt.telemetry.TelemetryAttributes
import com.google.adk.kt.testing.DummyAgent
import com.google.adk.kt.testing.DummyTracer
import com.google.adk.kt.testing.userMessage
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Tests for telemetry emission in [InMemoryRunner] and [BaseAgent]. */
class RunnerTelemetryTest {

  private val dummyTracer = DummyTracer()

  @Before
  fun setUp() {
    Telemetry.setTracerForTest(dummyTracer)
  }

  @After
  fun tearDown() {
    Telemetry.resetTracer()
  }

  @Test
  fun runAsync_emitsTelemetrySpans() = runBlocking {
    // Arrange
    val agent = DummyAgent(name = "telemetry-agent")
    val runner = InMemoryRunner(agent = agent)
    val message = userMessage("Hello")

    // Act
    runner.runAsync(userId = "user1", sessionId = "session1", newMessage = message).toList()

    // Assert
    val spans = dummyTracer.recordedSpans
    assertTrue("Should have recorded spans", spans.isNotEmpty())

    // Verify 'invocation' span
    val invocationSpan = spans.find { it.name == "invocation" }
    assertTrue("Should have found 'invocation' span", invocationSpan != null)

    // Verify 'invoke_agent' span
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
}
