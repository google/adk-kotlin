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
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.DummyTool
import com.google.adk.kt.testing.testSession
import com.google.adk.kt.types.GenerateContentConfig
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import org.junit.Test

class BasicRequestProcessorTest {

  private val outputSchema =
    Schema(type = Type.OBJECT, properties = mapOf("answer" to Schema(type = Type.STRING)))

  @Test
  fun run_withAgentFields_setsModelAndConfigOnRequest() = runBlocking {
    val model = DummyModel("gemini")
    val config = GenerateContentConfig()
    val agent = LlmAgent(name = "test", model = model, generateContentConfig = config)
    val session = testSession()
    val context = InvocationContext(session = session, runConfig = null, agent = agent)
    var request = LlmRequest()

    val processor = BasicRequestProcessor()
    request = processor.process(context, request)

    assertEquals(model, request.model)
    assertEquals(config, request.config)
  }

  @Test
  fun run_withOutputSchemaAndNoTools_setsResponseSchema() = runBlocking {
    val agent =
      LlmAgent(name = "test", model = DummyModel("gemini-2.0-flash"), outputSchema = outputSchema)
    val context = InvocationContext(session = testSession(), runConfig = null, agent = agent)

    val request = BasicRequestProcessor().process(context, LlmRequest())

    assertEquals(outputSchema, request.config.responseSchema)
    assertEquals("application/json", request.config.responseMimeType)
  }

  @Test
  fun run_withOutputSchemaAndTools_gemini2Model_doesNotSetResponseSchema() = runBlocking {
    // Gemini 2.x cannot use a response schema together with tools, so the schema is left for the
    // OutputSchemaProcessor workaround instead of being set on the request config here.
    val agent =
      LlmAgent(
        name = "test",
        model = DummyModel("gemini-2.0-flash"),
        tools = listOf(DummyTool("my_tool")),
        outputSchema = outputSchema,
      )
    val context = InvocationContext(session = testSession(), runConfig = null, agent = agent)

    val request = BasicRequestProcessor().process(context, LlmRequest())

    assertNull(request.config.responseSchema)
    assertNull(request.config.responseMimeType)
  }

  @Test
  fun run_withOutputSchemaAndTools_supportedModel_setsResponseSchema() = runBlocking {
    // Gemini 3.x supports a response schema together with tools, so the schema is applied directly.
    val agent =
      LlmAgent(
        name = "test",
        model = DummyModel("gemini-3.0-pro"),
        tools = listOf(DummyTool("my_tool")),
        outputSchema = outputSchema,
      )
    val context = InvocationContext(session = testSession(), runConfig = null, agent = agent)

    val request = BasicRequestProcessor().process(context, LlmRequest())

    assertEquals(outputSchema, request.config.responseSchema)
    assertEquals("application/json", request.config.responseMimeType)
  }

  @Test
  fun run_withOutputSchemaAndSubAgents_gemini2Model_doesNotSetResponseSchema() = runBlocking {
    // An agent with sub-agents gets a `transfer_to_agent` tool injected later by
    // AgentTransferProcessor. On Gemini 2.x that tool is incompatible with a response schema, so
    // the
    // schema must be left for the OutputSchemaProcessor workaround even though no explicit tools
    // are
    // declared.
    val agent =
      LlmAgent(
        name = "parent",
        model = DummyModel("gemini-2.0-flash"),
        outputSchema = outputSchema,
        subAgents = listOf(LlmAgent(name = "child", model = DummyModel("gemini-2.0-flash"))),
      )
    val context = InvocationContext(session = testSession(), runConfig = null, agent = agent)

    val request = BasicRequestProcessor().process(context, LlmRequest())

    assertNull(request.config.responseSchema)
    assertNull(request.config.responseMimeType)
  }

  @Test
  fun run_withOutputSchemaAndSubAgents_supportedModel_setsResponseSchema() = runBlocking {
    // Gemini 3.x supports a response schema together with tools (including transfer_to_agent), so
    // the schema is applied directly even though sub-agents are present.
    val agent =
      LlmAgent(
        name = "parent",
        model = DummyModel("gemini-3.0-pro"),
        outputSchema = outputSchema,
        subAgents = listOf(LlmAgent(name = "child", model = DummyModel("gemini-3.0-pro"))),
      )
    val context = InvocationContext(session = testSession(), runConfig = null, agent = agent)

    val request = BasicRequestProcessor().process(context, LlmRequest())

    assertEquals(outputSchema, request.config.responseSchema)
    assertEquals("application/json", request.config.responseMimeType)
  }
}
