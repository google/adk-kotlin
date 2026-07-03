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

package com.google.adk.kt.models

import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.telemetry.TelemetryAttributes
import com.google.adk.kt.telemetry.TracerElement
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.DummyTracer
import com.google.adk.kt.testing.testInvocationContext
import com.google.adk.kt.types.FunctionCall
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

/**
 * Verifies that a [com.google.adk.kt.tools.FunctionTool] generated from the `@Tool` annotation
 * records the annotated function's name in the `execute_tool` trace span.
 *
 * `ToolTelemetryTest` covers hand-written `FunctionTool` subclasses; this complements it by going
 * through the KSP `FunctionToolGenerator`, which derives the tool name from the function name
 * (`requiresName` here, from `FunctionToolWireFormatTestTools.kt`).
 */
class ToolNameTelemetryTest {

  private val fakeTracer = DummyTracer()

  @Test
  fun executeSingleFunctionCall_toolFromToolAnnotation_recordsFunctionNameInSpan() =
    runTest(TracerElement(fakeTracer)) {
      val tool = RequiresNameTool()
      assertEquals("requiresName", tool.name)

      val unused =
        testInvocationContext(
            agent = LlmAgent(name = "test_agent", model = DummyModel("mock_model"))
          )
          .executeSingleFunctionCall(
            FunctionCall(name = tool.name, args = mapOf("name" to "world"), id = "call_1"),
            mapOf(tool.name to tool),
          )

      val span = fakeTracer.recordedSpans.single()
      assertEquals("execute_tool requiresName", span.name)
      assertEquals("requiresName", span.attributes[TelemetryAttributes.GEN_AI_TOOL_NAME])
    }
}
