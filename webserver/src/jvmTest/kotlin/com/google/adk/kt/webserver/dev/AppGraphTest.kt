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

@file:OptIn(ExperimentalWorkflowApi::class, FrameworkInternalApi::class)

package com.google.adk.kt.webserver.dev

import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.serialization.adkJson
import com.google.adk.kt.webserver.dev.GraphFixtures.assistantAgent
import com.google.adk.kt.webserver.dev.GraphFixtures.llmAgent
import com.google.adk.kt.webserver.dev.GraphFixtures.step
import com.google.adk.kt.webserver.dev.GraphFixtures.supportWorkflow
import com.google.adk.kt.webserver.dev.GraphFixtures.tool
import com.google.adk.kt.workflow.Edge
import com.google.adk.kt.workflow.JoinNode
import com.google.adk.kt.workflow.Route
import com.google.adk.kt.workflow.Start
import com.google.adk.kt.workflow.Workflow
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class AppGraphTest {

  @Test
  fun appGraphOf_workflowRoot_describesStartRoutesAndNestedWorkflows() {
    // Act
    val json = encode(appGraphOf("support_app", supportWorkflow()))

    // Assert
    assertThat(json)
      .isEqualTo(
        Json.parseToJsonElement(
          """
          {"name": "support_app", "root_agent": {"name": "support", "type": "workflow", "graph": {
            "nodes": [
              {"name": "__START__", "type": "start"},
              {"name": "classify", "type": "node"},
              {"name": "billing_agent", "type": "agent", "sub_agents": [],
               "tools": [{"name": "lookup_invoice", "type": "tool"}]},
              {"name": "tech_flow", "type": "workflow", "graph": {
                "nodes": [
                  {"name": "__START__", "type": "start"},
                  {"name": "diagnose", "type": "node"},
                  {"name": "fix", "type": "node"}],
                "edges": [
                  {"from_node": {"name": "__START__", "type": "start"},
                   "to_node": {"name": "diagnose", "type": "node"}},
                  {"from_node": {"name": "diagnose", "type": "node"},
                   "to_node": {"name": "fix", "type": "node"}}]}},
              {"name": "fallback", "type": "node"},
              {"name": "summarize", "type": "node"}],
            "edges": [
              {"from_node": {"name": "__START__", "type": "start"},
               "to_node": {"name": "classify", "type": "node"}},
              {"from_node": {"name": "classify", "type": "node"},
               "to_node": {"name": "billing_agent", "type": "agent"}, "route": "billing"},
              {"from_node": {"name": "classify", "type": "node"},
               "to_node": {"name": "tech_flow", "type": "workflow"}, "route": "tech"},
              {"from_node": {"name": "classify", "type": "node"},
               "to_node": {"name": "fallback", "type": "node"}, "route": "__DEFAULT__"},
              {"from_node": {"name": "billing_agent", "type": "agent"},
               "to_node": {"name": "summarize", "type": "node"}},
              {"from_node": {"name": "tech_flow", "type": "workflow"},
               "to_node": {"name": "summarize", "type": "node"}},
              {"from_node": {"name": "fallback", "type": "node"},
               "to_node": {"name": "summarize", "type": "node"}}]}}}
          """
        )
      )
  }

  @Test
  fun appGraphOf_agentRoot_describesSubAgentsAndTools() {
    // Act
    val json = encode(appGraphOf("assistant", assistantAgent()))

    // Assert
    assertThat(json)
      .isEqualTo(
        Json.parseToJsonElement(
          """
          {"name": "assistant", "root_agent": {"name": "assistant", "type": "agent", "tools": [],
            "sub_agents": [{"name": "helper", "type": "agent", "sub_agents": [],
              "tools": [{"name": "lookup_invoice", "type": "tool"}]}]}}
          """
        )
      )
  }

  @Test
  fun graphNodeOf_toolNamedLikeASubAgent_skipsItAndNamesToolsetsByClass() {
    // Arrange
    val agent =
      llmAgent(
        "root",
        tools = listOf(tool("helper"), tool("search")),
        toolsets = listOf(InvoiceToolset()),
        subAgents = listOf(llmAgent("helper")),
      )

    // Act
    val tools = graphNodeOf(agent).tools

    // Assert
    assertThat(tools)
      .containsExactly(GraphTool("search", "tool"), GraphTool("InvoiceToolset", "tool"))
      .inOrder()
  }

  @Test
  fun graphNodeOf_routedEdges_describesRoutesAsScalarsOrAnArray() {
    // Arrange
    val a = step("a")
    val workflow =
      Workflow(
        name = "flow",
        edges =
          listOf(
            Edge(Start, a),
            Edge(a, step("b"), Route.Num(1)),
            Edge(a, step("c"), Route.Flag(false)),
            Edge(a, step("d"), listOf(Route.Tag("x"), Route.Tag("y"))),
          ),
      )

    // Act
    val routes = graphNodeOf(workflow).graph!!.edges.map { it.route }

    // Assert
    assertThat(routes)
      .containsExactly(
        null,
        Json.parseToJsonElement("1"),
        Json.parseToJsonElement("false"),
        Json.parseToJsonElement("""["x", "y"]"""),
      )
      .inOrder()
  }

  @Test
  fun graphNodeOf_joinNode_describesJoinTypeInNodesAndEdges() {
    // Arrange
    val a = step("a")
    val join = JoinNode("merge")
    val workflow = Workflow(name = "flow", edges = listOf(Edge(Start, a), Edge(a, join)))

    // Act
    val graph = graphNodeOf(workflow).graph!!

    // Assert
    assertThat(graph.nodes).contains(GraphNode("merge", "join"))
    assertThat(graph.edges.last().to).isEqualTo(GraphNodeRef("merge", "join"))
  }

  @Test
  fun graphNodeOf_nodeReachedByTwoEdges_listsItOnce() {
    // Arrange
    val a = step("a")
    val join = step("join")
    val workflow =
      Workflow(name = "flow", edges = listOf(Edge(Start, a), Edge(a, join), Edge(Start, join)))

    // Act
    val names = graphNodeOf(workflow).graph!!.nodes.map { it.name }

    // Assert
    assertThat(names).containsExactly("__START__", "a", "join").inOrder()
  }

  @Test
  fun graphNodeOf_workflowWithoutEdges_hasNoGraph() {
    // Act
    val node = graphNodeOf(Workflow(name = "empty"))

    // Assert
    assertThat(node).isEqualTo(GraphNode("empty", "workflow"))
  }

  @Test
  fun find_pathThroughGraphsAndSubAgents_returnsTheNode() {
    // Arrange
    val support = graphNodeOf(supportWorkflow())
    val assistant = graphNodeOf(assistantAgent())

    // Act + Assert
    assertThat(support.find("")).isSameInstanceAs(support)
    assertThat(support.find("tech_flow")?.type).isEqualTo("workflow")
    assertThat(support.find("support/tech_flow/diagnose")?.name).isEqualTo("diagnose")
    assertThat(support.find("/tech_flow/fix/")?.name).isEqualTo("fix")
    assertThat(assistant.find("helper")?.tools).containsExactly(GraphTool("lookup_invoice", "tool"))
  }

  @Test
  fun find_nameMatchingNoChild_returnsNull() {
    // Arrange
    val support = graphNodeOf(supportWorkflow())

    // Act + Assert
    assertThat(support.find("missing")).isNull()
    assertThat(support.find("tech_flow/missing")).isNull()
    assertThat(support.find("classify/anything")).isNull()
  }

  @Test
  fun workflowsByPath_nestedWorkflows_keysEachByItsPath() {
    // Arrange
    val innermost = Workflow(name = "inner", edges = listOf(Edge(Start, step("leaf"))))
    val middle = Workflow(name = "middle", edges = listOf(Edge(Start, innermost)))
    val outer =
      Workflow(name = "outer", edges = listOf(Edge(Start, middle), Edge(middle, step("z"))))

    // Act
    val fromRoot = graphNodeOf(outer).workflowsByPath("")
    val fromMiddle = graphNodeOf(outer).find("middle")!!.workflowsByPath("middle")

    // Assert
    assertThat(fromRoot.keys).containsExactly("", "middle", "middle/inner").inOrder()
    assertThat(fromRoot["middle/inner"]?.name).isEqualTo("inner")
    assertThat(fromMiddle.keys).containsExactly("middle", "middle/inner").inOrder()
  }

  @Test
  fun workflowsByPath_agentTree_isEmpty() {
    // Act + Assert
    assertThat(graphNodeOf(assistantAgent()).workflowsByPath("")).isEmpty()
  }

  private fun encode(appGraph: AppGraph): JsonElement =
    adkJson.encodeToJsonElement(AppGraph.serializer(), appGraph)
}
