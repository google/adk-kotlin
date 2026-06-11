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
import com.google.adk.kt.testing.DummySpan
import com.google.adk.kt.testing.DummyTracer
import com.google.adk.kt.testing.testInvocationContext
import com.google.adk.kt.tools.FunctionTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.FunctionCall
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
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
    // Empty placeholders are emitted on tool spans for ADK Dev UI trace-view compatibility.
    assertEquals("{}", span.attributes[TelemetryAttributes.GCP_VERTEX_AGENT_LLM_REQUEST])
    assertEquals("{}", span.attributes[TelemetryAttributes.GCP_VERTEX_AGENT_LLM_RESPONSE])
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
  fun executeSingleFunctionCall_usesEmptyPlaceholdersWhenCaptureDisabled() = runTest {
    TelemetryConfig.captureMessageContent = false
    val unused =
      createInvocationContext()
        .executeSingleFunctionCall(
          FunctionCall(name = "test_tool", args = mapOf("param" to "value"), id = "call_1"),
          mapOf("test_tool" to TestFunctionTool()),
        )

    assertEquals(1, fakeTracer.recordedSpans.size)
    val span = fakeTracer.recordedSpans[0]
    // Payloads are emitted as "{}" (not omitted) so the ADK Dev UI can JSON.parse them.
    assertEquals("{}", span.attributes[TelemetryAttributes.GCP_VERTEX_AGENT_TOOL_CALL_ARGS])
    assertEquals("{}", span.attributes[TelemetryAttributes.GCP_VERTEX_AGENT_TOOL_RESPONSE])
  }

  // ---------------------------------------------------------------------------
  // Python parity: these mirror the Python suite
  // tests/unittests/telemetry/test_spans.py::test_trace_tool_call* and
  // ::test_trace_merged_tool_calls.
  // ---------------------------------------------------------------------------

  @Test
  fun executeSingleFunctionCall_setsExecuteToolOperation() = runTest {
    val span = recordSingleToolSpan()

    assertEquals("execute_tool", span.attributes[TelemetryAttributes.GEN_AI_OPERATION_NAME])
  }

  @Test
  fun executeSingleFunctionCall_setsToolDescription() = runTest {
    val span = recordSingleToolSpan()

    assertEquals("A test tool", span.attributes[TelemetryAttributes.GEN_AI_TOOL_DESCRIPTION])
  }

  @Test
  fun executeSingleFunctionCall_setsToolCallId() = runTest {
    val span = recordSingleToolSpan()

    assertEquals("call_1", span.attributes[TelemetryAttributes.GEN_AI_TOOL_CALL_ID])
  }

  @Test
  fun executeSingleFunctionCall_capturesToolResponseWhenEnabled() = runTest {
    TelemetryConfig.captureMessageContent = true

    val span = recordSingleToolSpan()

    val toolResponse =
      span.attributes[TelemetryAttributes.GCP_VERTEX_AGENT_TOOL_RESPONSE] as? String
    assertNotNull(toolResponse)
    assertNotEquals("{}", toolResponse)
    assertContains(toolResponse, "success")
  }

  @Test
  fun executeSingleFunctionCall_setsErrorTypeWhenToolThrows() = runTest {
    assertFailsWith<IllegalStateException> {
      createInvocationContext()
        .executeSingleFunctionCall(
          FunctionCall(name = "throwing_tool", args = emptyMap(), id = "call_err"),
          mapOf("throwing_tool" to ThrowingFunctionTool()),
        )
    }

    assertEquals(1, fakeTracer.recordedSpans.size)
    val span = fakeTracer.recordedSpans[0]
    assertEquals("IllegalStateException", span.attributes[TelemetryAttributes.ERROR_TYPE])
  }

  @Test
  fun handleFunctionCalls_parallelCalls_recordsMergedToolSpan() = runTest {
    TelemetryConfig.captureMessageContent = true

    val merged =
      createInvocationContext()
        .handleFunctionCalls(
          listOf(
            FunctionCall(name = "tool_a", args = emptyMap(), id = "call_a"),
            FunctionCall(name = "tool_b", args = emptyMap(), id = "call_b"),
          ),
          mapOf("tool_a" to NamedFunctionTool("tool_a"), "tool_b" to NamedFunctionTool("tool_b")),
        )
    assertNotNull(merged)

    val mergedSpan = fakeTracer.recordedSpans.find { it.name == "execute_tool (merged)" }
    assertNotNull(mergedSpan)
    assertEquals("execute_tool", mergedSpan.attributes[TelemetryAttributes.GEN_AI_OPERATION_NAME])
    assertEquals("(merged tools)", mergedSpan.attributes[TelemetryAttributes.GEN_AI_TOOL_NAME])
    assertEquals(
      "(merged tools)",
      mergedSpan.attributes[TelemetryAttributes.GEN_AI_TOOL_DESCRIPTION],
    )
    assertEquals("N/A", mergedSpan.attributes[TelemetryAttributes.GCP_VERTEX_AGENT_TOOL_CALL_ARGS])
    assertEquals("{}", mergedSpan.attributes[TelemetryAttributes.GCP_VERTEX_AGENT_LLM_REQUEST])
    assertEquals("{}", mergedSpan.attributes[TelemetryAttributes.GCP_VERTEX_AGENT_LLM_RESPONSE])
    assertEquals(merged.id, mergedSpan.attributes[TelemetryAttributes.GEN_AI_TOOL_CALL_ID])
    assertEquals(merged.id, mergedSpan.attributes[TelemetryAttributes.GCP_VERTEX_AGENT_EVENT_ID])
    assertNotNull(mergedSpan.attributes[TelemetryAttributes.GCP_VERTEX_AGENT_TOOL_RESPONSE])
  }

  private suspend fun recordSingleToolSpan(): DummySpan {
    val unused =
      createInvocationContext()
        .executeSingleFunctionCall(
          FunctionCall(name = "test_tool", args = mapOf("param" to "value"), id = "call_1"),
          mapOf("test_tool" to TestFunctionTool()),
        )
    return fakeTracer.recordedSpans.single { it.name == "execute_tool [test_tool]" }
  }

  private fun createInvocationContext(): InvocationContext =
    testInvocationContext(agent = LlmAgent(name = "test_agent", model = DummyModel("mock_model")))

  private class TestFunctionTool : FunctionTool("test_tool", "A test tool") {
    override fun declaration() = null

    override suspend fun execute(context: ToolContext, args: Map<String, Any>): Any =
      mapOf("output" to "success")
  }

  private class NamedFunctionTool(name: String) : FunctionTool(name, "A test tool") {
    override fun declaration() = null

    override suspend fun execute(context: ToolContext, args: Map<String, Any>): Any =
      mapOf("output" to "success")
  }

  private class ThrowingFunctionTool : FunctionTool("throwing_tool", "A throwing tool") {
    override fun declaration() = null

    override suspend fun execute(context: ToolContext, args: Map<String, Any>): Any =
      throw IllegalStateException("boom")
  }
}
