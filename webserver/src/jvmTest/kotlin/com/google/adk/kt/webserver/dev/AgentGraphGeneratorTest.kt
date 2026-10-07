/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.adk.kt.webserver.dev

import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.Model
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.webserver.loaders.SingleAgentLoader
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class AgentGraphGeneratorTest {

  private val root =
    LlmAgent(
      name = "root",
      model = FakeModel,
      subAgents = listOf(LlmAgent(name = "sub", model = FakeModel)),
      tools = listOf(FakeTool("tool")),
    )
  private val generator = AgentGraphGenerator(SingleAgentLoader(root))

  @Test
  fun generateGraph_toolCall_highlightsBothNodesAndTheForwardEdge() {
    val dot = generator.generateGraph(root, highlightPairs = listOf("root" to "tool"))

    val expected =
      """
      strict digraph "agent_schema" {
        graph [fontcolor="#FAFAFA"]
        "root" [label="🤖 root", shape="ellipse", fontcolor="#B0B0B0", style="filled", color="#0F5223"]
        "root" -> "sub" [color="#B0B0B0", arrowhead="none"]
        "sub" [label="🤖 sub", shape="ellipse", fontcolor="#B0B0B0", style="rounded", color="#B0B0B0"]
        "tool" [label="🔧 tool", shape="box", fontcolor="#B0B0B0", style="filled", color="#0F5223"]
        "root" -> "tool" [color="#69CB87"]
      }
      """
        .trimIndent()
    assertThat(dot).isEqualTo(expected)
  }

  @Test
  fun generateGraph_toolResponse_pointsTheEdgeBack() {
    val dot = generator.generateGraph(root, highlightPairs = listOf("tool" to "root"))

    assertThat(dot).contains(""""root" -> "tool" [color="#69CB87", dir="back"]""")
  }

  @Test
  fun generateGraph_quoteOrBackslashInName_isEscaped() {
    val agent = LlmAgent(name = "root", model = FakeModel, tools = listOf(FakeTool("a\\b \"c\"")))

    val dot = generator.generateGraph(agent, highlightPairs = emptyList())

    assertThat(dot).contains(""""root" -> "a\\b \"c\"" [""")
  }

  @Test
  fun generateGraph_unknownAgent_returnsEmpty() {
    assertThat(generator.generateGraph("missing")).isEmpty()
  }

  private object FakeModel : Model {
    override val name = "fake-model"

    override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> =
      error("the graph must not call the model")
  }

  private class FakeTool(name: String) : BaseTool(name = name, description = "") {
    override fun declaration(): FunctionDeclaration? = null

    override suspend fun run(context: ToolContext, args: Map<String, Any?>): Any = Unit
  }
}
