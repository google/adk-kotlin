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
package com.google.adk.kt.processors

import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.events.Event
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.serialization.Json
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.DummyTool
import com.google.adk.kt.testing.testInvocationContext
import com.google.adk.kt.testing.testSession
import com.google.adk.kt.tools.SetModelResponseTool
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test

class OutputSchemaProcessorTest {

  private val outputSchema =
    Schema(type = Type.OBJECT, properties = mapOf("answer" to Schema(type = Type.STRING)))

  private fun contextFor(agent: LlmAgent): InvocationContext =
    InvocationContext(session = testSession(), runConfig = null, agent = agent)

  @Test
  fun process_noOutputSchema_returnsRequestUnchanged() = runBlocking {
    val agent =
      LlmAgent(
        name = "test",
        model = DummyModel("gemini-2.0-flash"),
        tools = listOf(DummyTool("my_tool")),
      )
    val request = LlmRequest()

    val result = OutputSchemaProcessor().process(contextFor(agent), request)

    assertEquals(request, result)
  }

  @Test
  fun process_outputSchemaButNoTools_returnsRequestUnchanged() = runBlocking {
    val agent =
      LlmAgent(name = "test", model = DummyModel("gemini-2.0-flash"), outputSchema = outputSchema)
    val request = LlmRequest()

    val result = OutputSchemaProcessor().process(contextFor(agent), request)

    assertEquals(request, result)
  }

  @Test
  fun process_outputSchemaWithToolsOnSupportedModel_returnsRequestUnchanged() = runBlocking {
    // Gemini 3.x supports response schema + tools, so the schema is applied by
    // BasicRequestProcessor
    // and this processor is a no-op.
    val agent =
      LlmAgent(
        name = "test",
        model = DummyModel("gemini-3.0-pro"),
        tools = listOf(DummyTool("my_tool")),
        outputSchema = outputSchema,
      )
    val request = LlmRequest()

    val result = OutputSchemaProcessor().process(contextFor(agent), request)

    assertEquals(request, result)
  }

  @Test
  fun process_outputSchemaWithToolsOnGemini2_addsSetModelResponseToolAndInstruction() =
    runBlocking {
      val agent =
        LlmAgent(
          name = "test",
          model = DummyModel("gemini-2.0-flash"),
          tools = listOf(DummyTool("my_tool")),
          outputSchema = outputSchema,
        )

      val result = OutputSchemaProcessor().process(contextFor(agent), LlmRequest())

      // The set_model_response tool is registered both in the tools dictionary and as a function
      // declaration in the config.
      assertTrue(result.toolsDict.any { it.name == SetModelResponseTool.NAME })
      val declarations = result.config.tools?.flatMap { it.functionDeclarations ?: emptyList() }
      assertTrue(declarations?.any { it.name == SetModelResponseTool.NAME } == true)
      // The model is instructed to call set_model_response for its final answer.
      val systemInstruction =
        result.config.systemInstruction?.parts?.joinToString("") { it.text.orEmpty() }
      assertTrue(systemInstruction?.contains("set_model_response") == true)
    }

  @Test
  fun process_outputSchemaWithSubAgentsOnGemini2_addsSetModelResponseTool() = runBlocking {
    // No explicit tools, but sub-agents cause a `transfer_to_agent` tool to be injected, which on
    // Gemini 2.x is incompatible with a response schema. The workaround must therefore activate.
    val agent =
      LlmAgent(
        name = "parent",
        model = DummyModel("gemini-2.0-flash"),
        outputSchema = outputSchema,
        subAgents = listOf(LlmAgent(name = "child", model = DummyModel("gemini-2.0-flash"))),
      )

    val result = OutputSchemaProcessor().process(contextFor(agent), LlmRequest())

    assertTrue(result.toolsDict.any { it.name == SetModelResponseTool.NAME })
  }

  @Test
  fun process_outputSchemaWithSubAgentsOnSupportedModel_returnsRequestUnchanged() = runBlocking {
    // Gemini 3.x supports response schema + tools, so the schema is applied by
    // BasicRequestProcessor
    // and this processor is a no-op even with sub-agents present.
    val agent =
      LlmAgent(
        name = "parent",
        model = DummyModel("gemini-3.0-pro"),
        outputSchema = outputSchema,
        subAgents = listOf(LlmAgent(name = "child", model = DummyModel("gemini-3.0-pro"))),
      )
    val request = LlmRequest()

    val result = OutputSchemaProcessor().process(contextFor(agent), request)

    assertEquals(request, result)
  }

  @Test
  fun getStructuredModelResponse_withSetModelResponse_returnsJson() {
    val response = mapOf<String, Any?>("answer" to "42")
    val event =
      Event(
        author = "test-agent",
        content =
          Content(
            role = Role.USER,
            parts =
              listOf(
                Part(
                  functionResponse =
                    FunctionResponse(name = SetModelResponseTool.NAME, response = response)
                )
              ),
          ),
      )

    val json = getStructuredModelResponse(event)

    assertEquals(response, json?.let { Json.fromJsonToMap(it) })
  }

  @Test
  fun getStructuredModelResponse_withOtherFunctionResponse_returnsNull() {
    val event =
      Event(
        author = "test-agent",
        content =
          Content(
            role = Role.USER,
            parts =
              listOf(
                Part(
                  functionResponse =
                    FunctionResponse(name = "my_tool", response = mapOf("result" to "ok"))
                )
              ),
          ),
      )

    assertNull(getStructuredModelResponse(event))
  }

  @Test
  fun createFinalModelResponseEvent_buildsModelTextEvent() {
    val agent = LlmAgent(name = "structured-agent", model = DummyModel("gemini-2.0-flash"))
    val context = testInvocationContext(agent = agent)

    val event = createFinalModelResponseEvent(context, """{"answer":"42"}""")

    assertEquals("structured-agent", event.author)
    assertEquals(Role.MODEL, event.content?.role)
    assertEquals("""{"answer":"42"}""", event.content?.parts?.firstOrNull()?.text)
  }
}
