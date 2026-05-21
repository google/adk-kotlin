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

package com.google.adk.kt.tools

import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.Model
import com.google.adk.kt.testing.DummyAgent
import com.google.adk.kt.testing.testSession
import com.google.adk.kt.types.VertexAISearchDataStoreSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest

class VertexAiSearchToolTest {
  private fun getTestToolContext(): ToolContext {
    val invocationContext =
      InvocationContext(
        session = testSession(),
        runConfig = null,
        agent = DummyAgent("test-agent"),
        artifactService = null,
      )
    return ToolContext(invocationContext = invocationContext)
  }

  // Helper to create mock model
  private class MockModel(override val name: String) : Model {
    override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> =
      emptyFlow()
  }

  @Test
  fun constructor_dataStoreIdAndSearchEngineIdNull_throwsException() {
    assertFailsWith<IllegalArgumentException> { VertexAiSearchTool() }
  }

  @Test
  fun constructor_dataStoreIdAndSearchEngineIdSet_throwsException() {
    assertFailsWith<IllegalArgumentException> {
      VertexAiSearchTool(dataStoreId = "ds1", searchEngineId = "se1")
    }
  }

  @Test
  fun constructor_dataStoreSpecsWithoutSearchEngineId_throwsException() {
    assertFailsWith<IllegalArgumentException> {
      VertexAiSearchTool(
        dataStoreId = "ds1",
        dataStoreSpecs = listOf(VertexAISearchDataStoreSpec(dataStore = "ds1")),
      )
    }
  }

  @Test
  fun declaration_returnsNull() {
    val tool = VertexAiSearchTool(dataStoreId = "ds1")
    assertNull(tool.declaration())
  }

  @Test
  fun run_throwsUnsupportedOperationException() = runTest {
    val tool = VertexAiSearchTool(dataStoreId = "ds1")
    assertFailsWith<UnsupportedOperationException> { tool.run(getTestToolContext(), emptyMap()) }
  }

  @Test
  fun processLlmRequest_geminiWithDataStoreId_addsRetrievalTool() = runTest {
    val tool = VertexAiSearchTool(dataStoreId = "ds1", filter = "filter1", maxResults = 5)
    val context = getTestToolContext()
    val request = LlmRequest(model = MockModel("gemini-2.0-flash"))

    val updatedRequest = tool.processLlmRequest(context, request)

    val tools = updatedRequest.config.tools
    assertNotNull(tools)
    assertEquals(1, tools.size)
    val retrieval = tools[0].retrieval
    assertNotNull(retrieval)
    val vertexAiSearch = retrieval.vertexAiSearch
    assertNotNull(vertexAiSearch)
    assertEquals("ds1", vertexAiSearch.datastore)
    assertNull(vertexAiSearch.engine)
    assertEquals("filter1", vertexAiSearch.filter)
    assertEquals(5, vertexAiSearch.maxResults)
    assertNull(vertexAiSearch.dataStoreSpecs)
  }

  @Test
  fun processLlmRequest_geminiWithSearchEngineId_addsRetrievalTool() = runTest {
    val specs = listOf(VertexAISearchDataStoreSpec(dataStore = "ds1"))
    val tool = VertexAiSearchTool(searchEngineId = "se1", dataStoreSpecs = specs, maxResults = 10)
    val context = getTestToolContext()
    val request = LlmRequest(model = MockModel("gemini-pro"))

    val updatedRequest = tool.processLlmRequest(context, request)

    val tools = updatedRequest.config.tools
    assertNotNull(tools)
    assertEquals(1, tools.size)
    val retrieval = tools[0].retrieval
    assertNotNull(retrieval)
    val vertexAiSearch = retrieval.vertexAiSearch
    assertNotNull(vertexAiSearch)
    assertEquals("se1", vertexAiSearch.engine)
    assertNull(vertexAiSearch.datastore)
    assertEquals(10, vertexAiSearch.maxResults)
    assertNull(vertexAiSearch.filter)
    assertEquals(specs, vertexAiSearch.dataStoreSpecs)
  }

  @Test
  fun processLlmRequest_unsupportedModel_throwsException() = runTest {
    val tool = VertexAiSearchTool(dataStoreId = "ds1")
    val context = getTestToolContext()
    val request = LlmRequest(model = MockModel("gpt-4"))

    assertFailsWith<IllegalArgumentException> { tool.processLlmRequest(context, request) }
  }

  @Test
  fun processLlmRequest_noModel_throwsException() = runTest {
    val tool = VertexAiSearchTool(dataStoreId = "ds1")
    val context = getTestToolContext()
    val request = LlmRequest()

    assertFailsWith<IllegalArgumentException> { tool.processLlmRequest(context, request) }
  }
}
