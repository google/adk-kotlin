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

import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.webserver.dev.GraphFixtures.assistantAgent
import com.google.adk.kt.webserver.dev.GraphFixtures.supportWorkflow
import com.google.adk.kt.workflow.Edge
import com.google.adk.kt.workflow.Route
import com.google.adk.kt.workflow.Start
import com.google.adk.kt.workflow.Workflow
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class GraphPlotterTest {

  @Test
  fun plotGraph_workflow_drawsItsGraphFromStartToEnd() {
    // Act
    val dot = plotGraph(graphNodeOf(supportWorkflow()), darkMode = false)

    // Assert
    assertThat(dot)
      .isEqualTo(
        expectedDot(
          LIGHT_GRAPH,
          LIGHT_NODE,
          LIGHT_EDGE,
          """classify [label=classify fillcolor="#FFFFFF" height=1.2 margin="0.0,0.0" shape=diamond style=filled tooltip="Node" width=0.8]""",
          """billing_agent [label=<<FONT COLOR="#42A5F5" POINT-SIZE="14">✦</FONT> billing_agent> fillcolor="#FFFFFF" style="rounded,filled" tooltip=Agent]""",
          """tech_flow [label=<<FONT COLOR="#9333EA" POINT-SIZE="14">⊷</FONT> tech_flow> fillcolor="#FFFFFF" style="rounded,filled" tooltip=Workflow]""",
          """fallback [label=fallback fillcolor="#FFFFFF" style="rounded,filled" tooltip="Node"]""",
          """summarize [label=summarize fillcolor="#FFFFFF" style="rounded,filled" tooltip="Node"]""",
          """lookup_invoice [label=<<FONT COLOR="#6B7280" POINT-SIZE="14">🔧</FONT> lookup_invoice> fillcolor="#FFFFFF" style="rounded,filled,dashed" tooltip=Tool]""",
          LIGHT_START,
          """__START__ -> classify [label=""]""",
          """classify -> billing_agent [label="  billing"]""",
          """classify -> tech_flow [label="  tech"]""",
          """classify -> fallback [label="  __DEFAULT__"]""",
          """billing_agent -> summarize [label=""]""",
          """tech_flow -> summarize [label=""]""",
          """fallback -> summarize [label=""]""",
          """billing_agent -> lookup_invoice [color="#64748B" style=dashed]""",
          LIGHT_END,
          """summarize -> __END__""",
        )
      )
  }

  @Test
  fun plotGraph_agentTree_drawsAgentsAndToolsWithoutStartOrEnd() {
    // Act
    val dot = plotGraph(graphNodeOf(assistantAgent()), darkMode = false)

    // Assert
    assertThat(dot)
      .isEqualTo(
        expectedDot(
          LIGHT_GRAPH,
          LIGHT_NODE,
          LIGHT_EDGE,
          """assistant [label=<<FONT COLOR="#42A5F5" POINT-SIZE="14">✦</FONT> assistant> fillcolor="#FFFFFF" style="rounded,filled" tooltip=Agent]""",
          """helper [label=<<FONT COLOR="#42A5F5" POINT-SIZE="14">✦</FONT> helper> fillcolor="#FFFFFF" style="rounded,filled" tooltip=Agent]""",
          """lookup_invoice [label=<<FONT COLOR="#6B7280" POINT-SIZE="14">🔧</FONT> lookup_invoice> fillcolor="#FFFFFF" style="rounded,filled,dashed" tooltip=Tool]""",
          """assistant -> helper [label=""]""",
          """helper -> lookup_invoice [color="#64748B" style=dashed]""",
        )
      )
  }

  @Test
  fun plotGraph_darkMode_drawsWithTheDarkPalette() {
    // Arrange
    val techFlow = graphNodeOf(supportWorkflow()).graph!!.nodes.single { it.name == "tech_flow" }

    // Act
    val dot = plotGraph(techFlow, darkMode = true)

    // Assert
    assertThat(dot)
      .isEqualTo(
        expectedDot(
          """graph [bgcolor="#0F172A" fontname=Helvetica nodesep=0.5 pad=0.5 ranksep=0.8 splines=spline]""",
          """node [color="#475569" fillcolor="#1E293B" fontcolor="#F8FAFC" fontname=Helvetica fontsize=12 margin="0.25,0.15" penwidth=1.5 shape=rect style="rounded,filled"]""",
          """edge [arrowhead=vee arrowsize=0.7 color="#94A3B8" fontcolor="#CBD5E1" fontname=Helvetica fontsize=10 penwidth=1.2]""",
          """diagnose [label=diagnose fillcolor="#1E293B" style="rounded,filled" tooltip="Node"]""",
          """fix [label=fix fillcolor="#1E293B" style="rounded,filled" tooltip="Node"]""",
          """__START__ [label=START color="#047857" fillcolor="#059669" fixedsize=true fontcolor="#F8FAFC" fontname="Helvetica-Bold" shape=oval style=filled width=0.9]""",
          """__START__ -> diagnose [label=""]""",
          """diagnose -> fix [label=""]""",
          """__END__ [label=END color="#B91C1C" fillcolor="#DC2626" fixedsize=true fontcolor="#F8FAFC" fontname="Helvetica-Bold" shape=oval style=filled width=0.9]""",
          """fix -> __END__""",
        )
      )
  }

  @Test
  fun plotGraph_routesWithoutDefaultJoinsAndEscapedNames_drawsExpectedShapesAndLabels() {
    // Arrange
    val quoted = "say \"hi\""
    val root =
      GraphNode(
        name = "edge_cases",
        type = "workflow",
        graph =
          WorkflowGraph(
            nodes =
              listOf(
                GraphNode(Start.name, "start"),
                GraphNode("pick", "node"),
                GraphNode("fn", "function", tools = listOf(GraphTool("a<b&\"c'", "tool"))),
                GraphNode("merge", "join"),
                GraphNode(quoted, "node"),
              ),
            edges =
              listOf(
                edge(Start.name, "pick"),
                edge("pick", "fn", "\"a\""),
                edge("pick", "merge", "true"),
                edge("fn", "merge", """["x", "y"]"""),
                edge("fn", quoted, "1"),
                edge("merge", quoted),
              ),
          ),
      )

    // Act
    val dot = plotGraph(root, darkMode = false)

    // Assert
    assertThat(dot)
      .isEqualTo(
        expectedDot(
          LIGHT_GRAPH,
          LIGHT_NODE,
          LIGHT_EDGE,
          """pick [label=<pick<br/><br/><font point-size='10'>⚠️ [NO DEFAULT]</font>> fillcolor="#FFFFFF" height=1.2 margin="0.0,0.0" shape=diamond style=filled tooltip="Node" width=0.8]""",
          """fn [label=<<FONT COLOR="#10B981" POINT-SIZE="14">ƒ</FONT> fn<br/><br/><FONT POINT-SIZE="10">⚠️ [NO DEFAULT]</FONT>> fillcolor="#FFFFFF" height=1.2 margin="0.0,0.0" shape=diamond style=filled tooltip=Function width=0.8]""",
          """merge [label=<<FONT COLOR="#F59E0B" POINT-SIZE="14">⌵</FONT> merge> fillcolor="#FFFFFF" margin="0.05,0.05" shape=oval style=filled tooltip=Join]""",
          """"say \"hi\"" [label="say \"hi\"" fillcolor="#FFFFFF" style="rounded,filled" tooltip="Node"]""",
          """"a<b&\"c'" [label=<<FONT COLOR="#6B7280" POINT-SIZE="14">🔧</FONT> a&lt;b&amp;&quot;c&#x27;> fillcolor="#FFFFFF" style="rounded,filled,dashed" tooltip=Tool]""",
          LIGHT_START,
          """__START__ -> pick [label=""]""",
          """pick -> fn [label="  a"]""",
          """pick -> merge [label="  True"]""",
          """fn -> merge [label="  ['x', 'y']"]""",
          """fn -> "say \"hi\"" [label="  1"]""",
          """merge -> "say \"hi\"" [label=""]""",
          """fn -> "a<b&\"c'" [color="#64748B" style=dashed]""",
          LIGHT_END,
          """"say \"hi\"" -> __END__""",
        )
      )
  }

  @Test
  fun plotGraph_backslashesQuotesAndListRoutes_quotesIdentifiersAndListLabels() {
    // Arrange
    val backslash = "a\\b"
    val escapedQuotes = "say \\\"hi\\\""
    val root =
      GraphNode(
        name = "quirks",
        type = "workflow",
        graph =
          WorkflowGraph(
            nodes =
              listOf(
                GraphNode(Start.name, "start"),
                GraphNode("pick", "node"),
                GraphNode(backslash, "node"),
                GraphNode(escapedQuotes, "node"),
              ),
            edges =
              listOf(
                edge(Start.name, "pick"),
                edge("pick", backslash, """["won't", "x\"y", "p\\q"]"""),
                edge("pick", escapedQuotes, "\"__DEFAULT__\""),
              ),
          ),
      )

    // Act
    val dot = plotGraph(root, darkMode = false)

    // Assert
    assertThat(dot)
      .isEqualTo(
        expectedDot(
          LIGHT_GRAPH,
          LIGHT_NODE,
          LIGHT_EDGE,
          """pick [label=pick fillcolor="#FFFFFF" height=1.2 margin="0.0,0.0" shape=diamond style=filled tooltip="Node" width=0.8]""",
          """"a\b" [label="a\b" fillcolor="#FFFFFF" style="rounded,filled" tooltip="Node"]""",
          """"say \"hi\"" [label="say \"hi\"" fillcolor="#FFFFFF" style="rounded,filled" tooltip="Node"]""",
          LIGHT_START,
          """__START__ -> pick [label=""]""",
          """pick -> "a\b" [label="  [\"won't\", 'x\"y', 'p\\q']"]""",
          """pick -> "say \"hi\"" [label="  __DEFAULT__"]""",
          LIGHT_END,
          """"a\b" -> __END__""",
          """"say \"hi\"" -> __END__""",
        )
      )
  }

  @Test
  fun plotGraph_falseRoute_labelsItAndMakesItsSourceConditional() {
    // Arrange
    val pick = GraphFixtures.step("pick")
    val workflow =
      Workflow(
        name = "flow",
        edges = listOf(Edge(Start, pick), Edge(pick, GraphFixtures.step("no"), Route.Flag(false))),
      )

    // Act
    val dot = plotGraph(graphNodeOf(workflow), darkMode = false)

    // Assert
    assertThat(dot).contains("""pick -> no [label="  False"]""")
    assertThat(dot)
      .contains("""pick [label=<pick<br/><br/><font point-size='10'>⚠️ [NO DEFAULT]</font>>""")
  }

  @Test
  fun plotGraph_trueAndFalseRoutes_warnNoDefault() {
    // Arrange
    val pick = GraphFixtures.step("pick")
    val count = GraphFixtures.step("count")
    val workflow =
      Workflow(
        name = "flow",
        edges =
          listOf(
            Edge(Start, pick),
            Edge(pick, GraphFixtures.step("yes"), Route.Flag(true)),
            Edge(pick, GraphFixtures.step("no"), Route.Flag(false)),
            Edge(Start, count),
            Edge(count, GraphFixtures.step("zero"), Route.Num(0)),
            Edge(count, GraphFixtures.step("one"), Route.Num(1)),
          ),
      )

    // Act
    val dot = plotGraph(graphNodeOf(workflow), darkMode = false)

    // Assert
    assertThat(dot)
      .contains("""pick [label=<pick<br/><br/><font point-size='10'>⚠️ [NO DEFAULT]</font>>""")
    assertThat(dot)
      .contains("""count [label=<count<br/><br/><font point-size='10'>⚠️ [NO DEFAULT]</font>>""")
  }

  @Test
  fun plotGraph_plainNameThatLooksLikeHtml_quotesItInsteadOfParsingMarkup() {
    // Arrange
    val root =
      GraphNode(
        "root",
        "workflow",
        graph = WorkflowGraph(listOf(GraphNode("<b>", "node")), emptyList()),
      )

    // Act
    val dot = plotGraph(root, darkMode = false)

    // Assert
    assertThat(dot).contains("\t\"<b>\" [label=\"<b>\" fillcolor=")
  }

  @Test
  fun plotGraph_routedAgentWithDefaultRoute_drawsAnIconDiamondWithoutWarning() {
    // Arrange
    val router = GraphFixtures.llmAgent("router")
    val workflow =
      Workflow(
        name = "flow",
        edges =
          listOf(
            Edge(Start, router),
            Edge(router, GraphFixtures.step("special"), Route.Tag("x")),
            Edge(router, GraphFixtures.step("usual"), Route.Default),
          ),
      )

    // Act
    val dot = plotGraph(graphNodeOf(workflow), darkMode = false)

    // Assert
    assertThat(dot)
      .contains(
        """router [label=<<FONT COLOR="#42A5F5" POINT-SIZE="14">✦</FONT> router> fillcolor="#FFFFFF" height=1.2 margin="0.0,0.0" shape=diamond style=filled tooltip=Agent width=0.8]"""
      )
    assertThat(dot).doesNotContain("NO DEFAULT")
  }

  @Test
  fun plotGraph_agentsWithOnlyToolEdges_endAtEndAndShareOneToolNode() {
    // Arrange
    val sharedTool = GraphFixtures.tool("t")
    val step = GraphFixtures.step("step")
    val workflow =
      Workflow(
        name = "flow",
        edges =
          listOf(
            Edge(Start, step),
            Edge(step, GraphFixtures.llmAgent("first_agent", tools = listOf(sharedTool))),
            Edge(step, GraphFixtures.llmAgent("second_agent", tools = listOf(sharedTool))),
          ),
      )

    // Act
    val dot = plotGraph(graphNodeOf(workflow), darkMode = false)

    // Assert
    assertThat(dot).contains("\tfirst_agent -> __END__\n")
    assertThat(dot).contains("\tsecond_agent -> __END__\n")
    assertThat(dot).doesNotContain("step -> __END__")
    assertThat(dot.lines().count { it.startsWith("\tt [") }).isEqualTo(1)
  }

  private fun edge(from: String, to: String, route: String? = null) =
    GraphEdge(
      GraphNodeRef(from, "node"),
      GraphNodeRef(to, "node"),
      route?.let(Json::parseToJsonElement),
    )

  private fun expectedDot(vararg statements: String) =
    "// Workflow Visualization\ndigraph {\n" + statements.joinToString("") { "\t$it\n" } + "}\n"

  private companion object {
    const val LIGHT_GRAPH =
      """graph [bgcolor="#F8FAFC" fontname=Helvetica nodesep=0.5 pad=0.5 ranksep=0.8 splines=spline]"""
    const val LIGHT_NODE =
      """node [color="#94A3B8" fillcolor="#FFFFFF" fontcolor="#0F172A" fontname=Helvetica fontsize=12 margin="0.25,0.15" penwidth=1.5 shape=rect style="rounded,filled"]"""
    const val LIGHT_EDGE =
      """edge [arrowhead=vee arrowsize=0.7 color="#64748B" fontcolor="#475569" fontname=Helvetica fontsize=10 penwidth=1.2]"""
    const val LIGHT_START =
      """__START__ [label=START color="#059669" fillcolor="#10B981" fixedsize=true fontcolor="#0F172A" fontname="Helvetica-Bold" shape=oval style=filled width=0.9]"""
    const val LIGHT_END =
      """__END__ [label=END color="#DC2626" fillcolor="#EF4444" fixedsize=true fontcolor="#0F172A" fontname="Helvetica-Bold" shape=oval style=filled width=0.9]"""
  }
}
