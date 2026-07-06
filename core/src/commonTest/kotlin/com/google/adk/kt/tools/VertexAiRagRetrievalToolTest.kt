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

import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.testToolContext
import com.google.adk.kt.types.VertexRagStoreRagResource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class VertexAiRagRetrievalToolTest {

  @Test
  fun declaration_returnsNull() {
    assertNull(VertexAiRagRetrieval(name = "rag", description = "d").declaration())
  }

  @Test
  fun run_throwsUnsupportedOperationException() = runTest {
    val tool = VertexAiRagRetrieval(name = "rag", description = "d")
    assertFailsWith<UnsupportedOperationException> {
      tool.run(testToolContext(), mapOf("query" to "q"))
    }
  }

  @Test
  fun processLlmRequest_addsVertexRagStoreRetrievalTool() = runTest {
    val resources = listOf(VertexRagStoreRagResource(ragCorpus = "corpus1"))
    val tool =
      VertexAiRagRetrieval(
        name = "rag",
        description = "d",
        ragResources = resources,
        similarityTopK = 3,
        vectorDistanceThreshold = 0.5,
      )
    val request = LlmRequest(model = DummyModel("gemini-2.0-flash"))

    val updated = tool.processLlmRequest(testToolContext(), request)

    val tools = updated.config.tools
    assertNotNull(tools)
    assertEquals(1, tools.size)
    val store = tools[0].retrieval?.vertexRagStore
    assertNotNull(store)
    assertEquals(resources, store.ragResources)
    assertEquals(3, store.similarityTopK)
    assertEquals(0.5, store.vectorDistanceThreshold)
  }

  @Test
  fun processLlmRequest_preservesExistingTools() = runTest {
    val tool = VertexAiRagRetrieval(name = "rag", description = "d", ragCorpora = listOf("corpus1"))
    val request =
      LlmRequest(model = DummyModel("gemini-2.0-flash")).let {
        it.copy(config = it.config.copy(tools = listOf(com.google.adk.kt.types.Tool())))
      }

    val updated = tool.processLlmRequest(testToolContext(), request)

    assertEquals(2, updated.config.tools?.size)
    assertNotNull(updated.config.tools?.get(1)?.retrieval?.vertexRagStore)
  }
}
