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
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class ToolTelemetryTest {

  private val fakeTracer = DummyTracer()

  @BeforeTest
  fun setUp() {
    TelemetryConfig.captureMessageContent = false
  }

  @AfterTest
  fun tearDown() {
    TelemetryConfig.captureMessageContent = false
  }

  @Test
  fun executeSingleFunctionCall_recordsSpanWithAttributes() =
    runTest(TracerElement(fakeTracer)) {
      val unused =
        createInvocationContext()
          .executeSingleFunctionCall(
            FunctionCall(name = "test_tool", args = mapOf("param" to "value"), id = "call_1"),
            mapOf("test_tool" to TestFunctionTool()),
          )

      assertEquals(1, fakeTracer.recordedSpans.size)
      val span = fakeTracer.recordedSpans[0]
      assertEquals("execute_tool test_tool", span.name)
      assertEquals("test_tool", span.attributes[TelemetryAttributes.GEN_AI_TOOL_NAME])
      assertEquals("TestFunctionTool", span.attributes[TelemetryAttributes.GEN_AI_TOOL_TYPE])
      // Empty placeholders are emitted on tool spans for ADK Dev UI trace-view compatibility.
      assertEquals("{}", span.attributes[TelemetryAttributes.GCP_VERTEX_AGENT_LLM_REQUEST])
      assertEquals("{}", span.attributes[TelemetryAttributes.GCP_VERTEX_AGENT_LLM_RESPONSE])
    }

  @Test
  fun executeSingleFunctionCall_capturesArgsWhenConfigured() =
    runTest(TracerElement(fakeTracer)) {
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
  fun executeSingleFunctionCall_usesEmptyPlaceholdersWhenCaptureDisabled() =
    runTest(TracerElement(fakeTracer)) {
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
  fun executeSingleFunctionCall_setsExecuteToolOperation() =
    runTest(TracerElement(fakeTracer)) {
      val span = recordSingleToolSpan()

      assertEquals("execute_tool", span.attributes[TelemetryAttributes.GEN_AI_OPERATION_NAME])
    }

  @Test
  fun executeSingleFunctionCall_setsToolDescription() =
    runTest(TracerElement(fakeTracer)) {
      val span = recordSingleToolSpan()

      assertEquals("A test tool", span.attributes[TelemetryAttributes.GEN_AI_TOOL_DESCRIPTION])
    }

  @Test
  fun executeSingleFunctionCall_setsToolCallId() =
    runTest(TracerElement(fakeTracer)) {
      val span = recordSingleToolSpan()

      assertEquals("call_1", span.attributes[TelemetryAttributes.GEN_AI_TOOL_CALL_ID])
    }

  @Test
  fun executeSingleFunctionCall_capturesToolResponseWhenEnabled() =
    runTest(TracerElement(fakeTracer)) {
      TelemetryConfig.captureMessageContent = true

      val span = recordSingleToolSpan()

      val toolResponse =
        span.attributes[TelemetryAttributes.GCP_VERTEX_AGENT_TOOL_RESPONSE] as? String
      assertNotNull(toolResponse)
      assertNotEquals("{}", toolResponse)
      assertContains(toolResponse, "success")
    }

  @Test
  fun executeSingleFunctionCall_setsErrorTypeWhenToolThrows() =
    runTest(TracerElement(fakeTracer)) {
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
  fun handleFunctionCalls_parallelCalls_recordsMergedToolSpan() =
    runTest(TracerElement(fakeTracer)) {
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
      assertEquals(
        "N/A",
        mergedSpan.attributes[TelemetryAttributes.GCP_VERTEX_AGENT_TOOL_CALL_ARGS],
      )
      assertEquals("{}", mergedSpan.attributes[TelemetryAttributes.GCP_VERTEX_AGENT_LLM_REQUEST])
      assertEquals("{}", mergedSpan.attributes[TelemetryAttributes.GCP_VERTEX_AGENT_LLM_RESPONSE])
      assertEquals(merged.id, mergedSpan.attributes[TelemetryAttributes.GEN_AI_TOOL_CALL_ID])
      assertEquals(merged.id, mergedSpan.attributes[TelemetryAttributes.GCP_VERTEX_AGENT_EVENT_ID])
      assertNotNull(mergedSpan.attributes[TelemetryAttributes.GCP_VERTEX_AGENT_TOOL_RESPONSE])
    }

  @Test
  fun executeSingleFunctionCall_scalarResult_capturesScalarToolResponse() =
    runTest(TracerElement(fakeTracer)) {
      TelemetryConfig.captureMessageContent = true

      val unused =
        createInvocationContext()
          .executeSingleFunctionCall(
            FunctionCall(name = "scalar_tool", args = emptyMap(), id = "call_scalar"),
            mapOf("scalar_tool" to ScalarFunctionTool()),
          )

      val span = fakeTracer.recordedSpans.single { it.name == "execute_tool scalar_tool" }
      val toolResponse =
        span.attributes[TelemetryAttributes.GCP_VERTEX_AGENT_TOOL_RESPONSE] as? String
      assertNotNull(toolResponse)
      assertNotEquals("{}", toolResponse)
      assertContains(toolResponse, "scalar-result-value")
    }

  @Test
  fun handleFunctionCalls_parallelCallsCaptureDisabled_mergedSpanHasEmptyToolResponse() =
    runTest(TracerElement(fakeTracer)) {
      TelemetryConfig.captureMessageContent = false

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

      val mergedSpan = fakeTracer.recordedSpans.single { it.name == "execute_tool (merged)" }
      assertEquals("{}", mergedSpan.attributes[TelemetryAttributes.GCP_VERTEX_AGENT_TOOL_RESPONSE])
    }

  @Test
  fun executeSingleFunctionCall_toolThrowsIllegalArgument_setsErrorType() =
    runTest(TracerElement(fakeTracer)) {
      assertFailsWith<IllegalArgumentException> {
        createInvocationContext()
          .executeSingleFunctionCall(
            FunctionCall(name = "arg_throwing_tool", args = emptyMap(), id = "call_arg_err"),
            mapOf("arg_throwing_tool" to ArgThrowingFunctionTool()),
          )
      }

      val span = fakeTracer.recordedSpans.single { it.name == "execute_tool arg_throwing_tool" }
      assertEquals("IllegalArgumentException", span.attributes[TelemetryAttributes.ERROR_TYPE])
    }

  @Test
  fun executeSingleFunctionCall_withDestinationId_setsMcpDestinationAttribute() =
    runTest(TracerElement(fakeTracer)) {
      val unused =
        createInvocationContext()
          .executeSingleFunctionCall(
            FunctionCall(name = "mcp_tool", args = emptyMap(), id = "call_1"),
            mapOf("mcp_tool" to DestinationIdFunctionTool("my-destination")),
          )

      val span = fakeTracer.recordedSpans.single { it.name == "execute_tool mcp_tool" }
      assertEquals(
        "my-destination",
        span.attributes[TelemetryAttributes.GCP_MCP_SERVER_DESTINATION_ID],
      )
    }

  @Test
  fun executeSingleFunctionCall_withoutDestinationId_omitsMcpDestinationAttribute() =
    runTest(TracerElement(fakeTracer)) {
      val unused =
        createInvocationContext()
          .executeSingleFunctionCall(
            FunctionCall(name = "other_meta_tool", args = emptyMap(), id = "call_1"),
            mapOf("other_meta_tool" to OtherMetadataFunctionTool()),
          )

      val span = fakeTracer.recordedSpans.single { it.name == "execute_tool other_meta_tool" }
      assertNull(span.attributes[TelemetryAttributes.GCP_MCP_SERVER_DESTINATION_ID])
    }

  @Test
  fun executeSingleFunctionCall_emptyCustomMetadata_omitsMcpDestinationAttribute() =
    runTest(TracerElement(fakeTracer)) {
      val span = recordSingleToolSpan()

      assertNull(span.attributes[TelemetryAttributes.GCP_MCP_SERVER_DESTINATION_ID])
    }

  private suspend fun recordSingleToolSpan(): DummySpan {
    val unused =
      createInvocationContext()
        .executeSingleFunctionCall(
          FunctionCall(name = "test_tool", args = mapOf("param" to "value"), id = "call_1"),
          mapOf("test_tool" to TestFunctionTool()),
        )
    return fakeTracer.recordedSpans.single { it.name == "execute_tool test_tool" }
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

  private class ScalarFunctionTool : FunctionTool("scalar_tool", "A scalar tool") {
    override fun declaration() = null

    override suspend fun execute(context: ToolContext, args: Map<String, Any>): Any =
      "scalar-result-value"
  }

  private class ArgThrowingFunctionTool : FunctionTool("arg_throwing_tool", "A throwing tool") {
    override fun declaration() = null

    override suspend fun execute(context: ToolContext, args: Map<String, Any>): Any =
      throw IllegalArgumentException("bad arg")
  }

  private class DestinationIdFunctionTool(destinationId: String) :
    FunctionTool(
      "mcp_tool",
      "An MCP tool",
      customMetadata = mapOf(TelemetryAttributes.GCP_MCP_SERVER_DESTINATION_ID to destinationId),
    ) {
    override fun declaration() = null

    override suspend fun execute(context: ToolContext, args: Map<String, Any>): Any =
      mapOf("output" to "success")
  }

  private class OtherMetadataFunctionTool :
    FunctionTool(
      "other_meta_tool",
      "A tool with unrelated metadata",
      customMetadata = mapOf("k" to "v"),
    ) {
    override fun declaration() = null

    override suspend fun execute(context: ToolContext, args: Map<String, Any>): Any =
      mapOf("output" to "success")
  }
}
