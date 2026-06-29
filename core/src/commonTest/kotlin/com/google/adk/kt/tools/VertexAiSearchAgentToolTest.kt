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

import com.google.adk.kt.testing.DummyModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class VertexAiSearchAgentToolTest {

  @Test
  fun createVertexAiSearchAgent_buildsAgentWithVertexAiSearchTool() {
    val tool = VertexAiSearchTool(dataStoreId = "ds1", bypassMultiToolsLimit = true)

    val agent = createVertexAiSearchAgent(DummyModel("test"), tool)

    assertEquals(VERTEX_AI_SEARCH_AGENT_NAME, agent.name)
    assertEquals(1, agent.tools.size)
    assertIs<VertexAiSearchTool>(agent.tools[0])
  }

  @Test
  fun vertexAiSearchAgentTool_declaration_usesAgentName() {
    val tool = VertexAiSearchTool(dataStoreId = "ds1", bypassMultiToolsLimit = true)

    val agentTool = VertexAiSearchAgentTool(createVertexAiSearchAgent(DummyModel("test"), tool))

    assertEquals(VERTEX_AI_SEARCH_AGENT_NAME, agentTool.declaration().name)
    assertTrue(agentTool.propagateGroundingMetadata)
  }
}
