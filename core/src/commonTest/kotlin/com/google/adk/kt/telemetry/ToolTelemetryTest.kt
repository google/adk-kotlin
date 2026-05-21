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
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.DummyTracer
import com.google.adk.kt.testing.testSession
import com.google.adk.kt.tools.FunctionTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.FunctionCall
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class ToolTelemetryTest {

  private val fakeTracer = DummyTracer()

  @BeforeTest
  fun setUp() {
    Telemetry.setTracerForTest(fakeTracer)
    TelemetryConfig.captureMessageContent = false
  }

  @AfterTest
  fun tearDown() {
    Telemetry.resetTracer()
    TelemetryConfig.captureMessageContent = false
  }

  @Test
  fun executeSingleFunctionCall_recordsSpanWithAttributes() = runTest {
    val unused =
      createInvocationContext()
        .executeSingleFunctionCall(
          FunctionCall(name = "test_tool", args = mapOf("param" to "value"), id = "call_1"),
          mapOf("test_tool" to TestFunctionTool()),
        )

    assertEquals(1, fakeTracer.recordedSpans.size)
    val span = fakeTracer.recordedSpans[0]
    assertEquals("execute_tool [test_tool]", span.name)
    assertEquals("test_tool", span.attributes[TelemetryAttributes.GEN_AI_TOOL_NAME])
    assertEquals("function", span.attributes[TelemetryAttributes.GEN_AI_TOOL_TYPE])
  }

  @Test
  fun executeSingleFunctionCall_capturesArgsWhenConfigured() = runTest {
    TelemetryConfig.captureMessageContent = true
    val unused =
      createInvocationContext()
        .executeSingleFunctionCall(
          FunctionCall(name = "test_tool", args = mapOf("param" to "value"), id = "call_1"),
          mapOf("test_tool" to TestFunctionTool()),
        )

    assertEquals(1, fakeTracer.recordedSpans.size)
    val span = fakeTracer.recordedSpans[0]
    assertEquals(
      "{\"param\":\"value\"}",
      span.attributes[TelemetryAttributes.GCP_VERTEX_AGENT_TOOL_CALL_ARGS],
    )
  }

  @Test
  fun executeSingleFunctionCall_doesNotCaptureArgsWhenConfiguredFalse() = runTest {
    TelemetryConfig.captureMessageContent = false
    val unused =
      createInvocationContext()
        .executeSingleFunctionCall(
          FunctionCall(name = "test_tool", args = mapOf("param" to "value"), id = "call_1"),
          mapOf("test_tool" to TestFunctionTool()),
        )

    assertEquals(1, fakeTracer.recordedSpans.size)
    val span = fakeTracer.recordedSpans[0]
    assertNull(span.attributes[TelemetryAttributes.GCP_VERTEX_AGENT_TOOL_CALL_ARGS])
  }

  private fun createInvocationContext(): InvocationContext =
    InvocationContext(
      session = testSession(),
      runConfig = null,
      agent = LlmAgent(name = "test_agent", model = DummyModel("mock_model")),
    )

  private class TestFunctionTool : FunctionTool("test_tool", "A test tool") {
    override fun declaration() = null

    override suspend fun execute(context: ToolContext, args: Map<String, Any>): Any =
      mapOf("output" to "success")
  }
}
