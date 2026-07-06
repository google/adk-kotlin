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

package com.google.adk.kt.agents

import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.DummyTool
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.tools.GoogleSearchTool
import com.google.adk.kt.tools.VertexAiRagRetrieval
import com.google.adk.kt.tools.VertexAiSearchTool
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

class LlmAgentMultiToolsLimitTest {

  @Test
  fun multipleTools_googleSearchWithBypass_swappedToFunctionTool() = runTest {
    var captured: LlmRequest? = null
    val model =
      DummyModel("m") { request ->
        captured = request
        flowOf(LlmResponse(content = modelMessage("done")))
      }
    val agent =
      LlmAgent(
        name = "main",
        model = model,
        tools = listOf(GoogleSearchTool(bypassMultiToolsLimit = true), DummyTool("calc")),
      )
    val runner = InMemoryRunner(agent = agent)

    val unused =
      runner.runAsync(userId = "u", sessionId = "s", newMessage = userMessage("hi")).toList()

    val tools = captured?.config?.tools
    assertNotNull(tools)
    // The built-in google_search is replaced and re-exposed as a function tool.
    assertNull(tools.firstOrNull { it.googleSearch != null })
    val declaredNames = tools.flatMap { it.functionDeclarations ?: emptyList() }.map { it.name }
    assertTrue(declaredNames.contains("google_search_agent"))
  }

  @Test
  fun singleTool_googleSearchWithBypass_keptAsBuiltIn() = runTest {
    var captured: LlmRequest? = null
    val model =
      DummyModel("m") { request ->
        captured = request
        flowOf(LlmResponse(content = modelMessage("done")))
      }
    val agent =
      LlmAgent(
        name = "main",
        model = model,
        tools = listOf(GoogleSearchTool(bypassMultiToolsLimit = true)),
      )
    val runner = InMemoryRunner(agent = agent)

    val unused =
      runner.runAsync(userId = "u", sessionId = "s", newMessage = userMessage("hi")).toList()

    val tools = captured?.config?.tools
    assertNotNull(tools)
    // Only one tool: the built-in google_search is kept as-is.
    assertNotNull(tools.firstOrNull { it.googleSearch != null })
  }

  @Test
  fun multipleTools_googleSearchWithoutBypass_keptAsBuiltIn() = runTest {
    var captured: LlmRequest? = null
    val model =
      DummyModel("m") { request ->
        captured = request
        flowOf(LlmResponse(content = modelMessage("done")))
      }
    val agent =
      LlmAgent(
        name = "main",
        model = model,
        tools = listOf(GoogleSearchTool(bypassMultiToolsLimit = false), DummyTool("calc")),
      )
    val runner = InMemoryRunner(agent = agent)

    val unused =
      runner.runAsync(userId = "u", sessionId = "s", newMessage = userMessage("hi")).toList()

    val tools = captured?.config?.tools
    assertNotNull(tools)
    // Did not opt in: the built-in google_search is left as-is (not swapped).
    assertNotNull(tools.firstOrNull { it.googleSearch != null })
  }

  @Test
  fun multipleTools_vertexAiSearchWithBypass_swappedToFunctionTool() = runTest {
    var captured: LlmRequest? = null
    val model =
      DummyModel("m") { request ->
        captured = request
        flowOf(LlmResponse(content = modelMessage("done")))
      }
    val agent =
      LlmAgent(
        name = "main",
        model = model,
        tools =
          listOf(
            VertexAiSearchTool(dataStoreId = "ds1", bypassMultiToolsLimit = true),
            DummyTool("calc"),
          ),
      )
    val runner = InMemoryRunner(agent = agent)

    val unused =
      runner.runAsync(userId = "u", sessionId = "s", newMessage = userMessage("hi")).toList()

    val tools = captured?.config?.tools
    assertNotNull(tools)
    // The built-in vertex_ai_search retrieval is replaced and re-exposed as a function tool.
    assertNull(tools.firstOrNull { it.retrieval != null })
    val declaredNames = tools.flatMap { it.functionDeclarations ?: emptyList() }.map { it.name }
    assertTrue(declaredNames.contains("vertex_ai_search_agent"))
  }

  @Test
  fun multipleTools_ragRetrievalWithBypass_swappedToFunctionTool() = runTest {
    var captured: LlmRequest? = null
    val model =
      DummyModel("m") { request ->
        captured = request
        flowOf(LlmResponse(content = modelMessage("done")))
      }
    val agent =
      LlmAgent(
        name = "main",
        model = model,
        tools =
          listOf(
            VertexAiRagRetrieval(
              name = "rag",
              description = "d",
              ragCorpora = listOf("corpus1"),
              bypassMultiToolsLimit = true,
            ),
            DummyTool("calc"),
          ),
      )
    val runner = InMemoryRunner(agent = agent)

    val unused =
      runner.runAsync(userId = "u", sessionId = "s", newMessage = userMessage("hi")).toList()

    val tools = captured?.config?.tools
    assertNotNull(tools)
    // The built-in RAG retrieval is replaced and re-exposed as a function tool.
    assertNull(tools.firstOrNull { it.retrieval != null })
    val declaredNames = tools.flatMap { it.functionDeclarations ?: emptyList() }.map { it.name }
    assertTrue(declaredNames.contains("vertex_ai_rag_retrieval_agent"))
  }

  @Test
  fun multipleTools_ragRetrievalWithoutBypass_keptAsBuiltIn() = runTest {
    var captured: LlmRequest? = null
    val model =
      DummyModel("m") { request ->
        captured = request
        flowOf(LlmResponse(content = modelMessage("done")))
      }
    val agent =
      LlmAgent(
        name = "main",
        model = model,
        tools =
          listOf(
            VertexAiRagRetrieval(name = "rag", description = "d", ragCorpora = listOf("corpus1")),
            DummyTool("calc"),
          ),
      )
    val runner = InMemoryRunner(agent = agent)

    val unused =
      runner.runAsync(userId = "u", sessionId = "s", newMessage = userMessage("hi")).toList()

    val tools = captured?.config?.tools
    assertNotNull(tools)
    // Did not opt in: the built-in RAG retrieval is left as-is.
    assertNotNull(tools.firstOrNull { it.retrieval?.vertexRagStore != null })
  }
}
