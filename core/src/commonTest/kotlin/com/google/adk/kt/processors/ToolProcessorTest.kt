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
import com.google.adk.kt.testing.testSession
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.FunctionDeclaration
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ToolProcessorTest {

  class MyTool : BaseTool(name = "my_tool", description = "my tool description") {
    override fun declaration() =
      FunctionDeclaration(name = "my_tool_function", description = "does stuff")

    override suspend fun run(context: ToolContext, args: Map<String, Any>): Any = TODO()
  }

  @Test
  fun process_withAgentTools_addsToolsToRequest() = runTest {
    val model = DummyModel("gemini")
    val tool = MyTool()
    val agent = LlmAgent(name = "test", model = model, tools = listOf(tool))
    val session = testSession()
    val invocationContext = InvocationContext(session = session, runConfig = null, agent = agent)
    val request = LlmRequest()
    val processor = ToolProcessor()

    val processedRequest = processor.process(invocationContext, request) {}

    val tools = processedRequest.config.tools
    assertNotNull(tools)
    assertEquals(1, tools.size)
    assertEquals(listOf(tool.declaration()), tools.get(0).functionDeclarations)
  }

  @Test
  fun process_withNoAgentTools_requestIsUnchanged() = runTest {
    val model = DummyModel("gemini")
    val agent = LlmAgent(name = "test", model = model, tools = emptyList())
    val session = testSession()
    val invocationContext = InvocationContext(session = session, runConfig = null, agent = agent)
    val request = LlmRequest()
    val processor = ToolProcessor()

    val processedRequest = processor.process(invocationContext, request) {}

    assertEquals(request, processedRequest)
    assertNull(processedRequest.config.tools)
  }
}
