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

@file:OptIn(ExperimentalWorkflowApi::class)

package com.google.adk.kt.webserver.dev

import com.google.adk.kt.agents.BaseAgent
import com.google.adk.kt.agents.Context
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.ReadonlyContext
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.Model
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.tools.Toolset
import com.google.adk.kt.workflow.Edge
import com.google.adk.kt.workflow.Node
import com.google.adk.kt.workflow.Route
import com.google.adk.kt.workflow.Start
import com.google.adk.kt.workflow.Workflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** Graphs shared by the graph tests. */
internal object GraphFixtures {
  /**
   * A support desk: `classify` routes to a billing agent with a tool, a nested `tech_flow`
   * workflow, or a default fallback, and every branch ends in `summarize`.
   */
  fun supportWorkflow(): Workflow {
    val classify = step("classify")
    val billing = llmAgent("billing_agent", tools = listOf(tool("lookup_invoice")))
    val diagnose = step("diagnose")
    val techFlow =
      Workflow(
        name = "tech_flow",
        edges = listOf(Edge(Start, diagnose), Edge(diagnose, step("fix"))),
      )
    val fallback = step("fallback")
    val summarize = step("summarize")
    return Workflow(
      name = "support",
      edges =
        listOf(
          Edge(Start, classify),
          Edge(classify, billing, Route.Tag("billing")),
          Edge(classify, techFlow, Route.Tag("tech")),
          Edge(classify, fallback, Route.Default),
          Edge(billing, summarize),
          Edge(techFlow, summarize),
          Edge(fallback, summarize),
        ),
    )
  }

  /** An agent tree: `assistant` delegates to `helper`, which has a tool. */
  fun assistantAgent(): BaseAgent =
    llmAgent(
      "assistant",
      subAgents = listOf(llmAgent("helper", tools = listOf(tool("lookup_invoice")))),
    )

  fun step(name: String): Node = GraphStepNode(name)

  fun llmAgent(
    name: String,
    tools: List<BaseTool> = emptyList(),
    toolsets: List<Toolset> = emptyList(),
    subAgents: List<BaseAgent> = emptyList(),
  ): LlmAgent =
    LlmAgent(
      name = name,
      model = GraphFixtureModel,
      tools = tools,
      toolsets = toolsets,
      subAgents = subAgents,
    )

  fun tool(name: String): BaseTool = GraphFixtureTool(name)
}

/** A toolset the graph draws under its class name. */
internal class InvoiceToolset : Toolset {
  override suspend fun getTools(readonlyContext: ReadonlyContext?): List<BaseTool> = emptyList()
}

private class GraphStepNode(override val name: String) : Node {
  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = emptyFlow()
}

private object GraphFixtureModel : Model {
  override val name = "graph-fixture-model"

  override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> =
    emptyFlow()
}

private class GraphFixtureTool(name: String) : BaseTool(name = name, description = "test tool") {
  override fun declaration() = null

  override suspend fun run(context: ToolContext, args: Map<String, Any?>): Any =
    emptyMap<String, Any>()
}
