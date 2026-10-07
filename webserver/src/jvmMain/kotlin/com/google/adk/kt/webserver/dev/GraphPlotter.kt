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

import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.workflow.Route
import com.google.adk.kt.workflow.Start
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Renders [root] as Graphviz DOT source for the Dev UI: a workflow graph from START to END, or an
 * agent tree with attached tools drawn as dashed nodes.
 */
internal fun plotGraph(root: GraphNode, darkMode: Boolean): String {
  val palette = if (darkMode) DARK_PALETTE else LIGHT_PALETTE
  val nodes = mutableListOf<PlotNode>()
  val edges = mutableListOf<PlotEdge>()
  val graph = root.graph
  if (graph != null) {
    graph.nodes.mapTo(nodes) { PlotNode(it.name, it.type, it.tools.orEmpty()) }
    graph.edges.mapTo(edges) { PlotEdge(it.from.name, it.to.name, it.route) }
  } else {
    addAgentTree(root, nodes, edges)
  }
  addTools(nodes, edges)

  val dot = DotWriter()
  dot.statement(
    "graph",
    "bgcolor" to palette.background,
    "pad" to "0.5",
    "nodesep" to "0.5",
    "ranksep" to "0.8",
    "fontname" to "Helvetica",
    "splines" to "spline",
  )
  dot.statement(
    "node",
    "shape" to "rect",
    "style" to "rounded,filled",
    "fillcolor" to palette.nodeFill,
    "color" to palette.nodeLine,
    "penwidth" to "1.5",
    "fontname" to "Helvetica",
    "fontcolor" to palette.nodeFont,
    "fontsize" to "12",
    "margin" to "0.25,0.15",
  )
  dot.statement(
    "edge",
    "color" to palette.edgeLine,
    "penwidth" to "1.2",
    "fontname" to "Helvetica",
    "fontcolor" to palette.edgeFont,
    "fontsize" to "10",
    "arrowhead" to "vee",
    "arrowsize" to "0.7",
  )
  for (node in nodes) {
    if (node.name != START_NODE_NAME) drawNode(dot, node, edges, palette)
  }
  for (edge in edges) {
    if (edge.from == START_NODE_NAME) {
      drawTerminus(dot, START_NODE_NAME, "START", palette.startFill, palette.startLine, palette)
    }
    if (edge.isToolEdge) {
      dot.edge(edge.from, edge.to, null, "style" to "dashed", "color" to palette.edgeLine)
    } else {
      dot.edge(edge.from, edge.to, plainLabel(edge.route?.let { "  ${routeLabel(it)}" }.orEmpty()))
    }
  }
  val terminalNodes = nodes.filter { node ->
    node.name != START_NODE_NAME &&
      node.name != END_NODE_NAME &&
      node.type != TOOL_NODE_TYPE &&
      edges.none { it.from == node.name && !it.isToolEdge }
  }
  if (graph != null && terminalNodes.isNotEmpty()) {
    drawTerminus(dot, END_NODE_NAME, "END", palette.endFill, palette.endLine, palette)
    for (node in terminalNodes) dot.edge(node.name, END_NODE_NAME, null)
  }
  return dot.build()
}

private class PlotNode(val name: String, val type: String, val tools: List<GraphTool>)

private class PlotEdge(
  val from: String,
  val to: String,
  val route: JsonElement? = null,
  val isToolEdge: Boolean = false,
)

private class Palette(
  val background: String,
  val nodeFill: String,
  val nodeLine: String,
  val nodeFont: String,
  val edgeLine: String,
  val edgeFont: String,
  val startFill: String,
  val startLine: String,
  val endFill: String,
  val endLine: String,
)

private val DARK_PALETTE =
  Palette(
    background = "#0F172A",
    nodeFill = "#1E293B",
    nodeLine = "#475569",
    nodeFont = "#F8FAFC",
    edgeLine = "#94A3B8",
    edgeFont = "#CBD5E1",
    startFill = "#059669",
    startLine = "#047857",
    endFill = "#DC2626",
    endLine = "#B91C1C",
  )

private val LIGHT_PALETTE =
  Palette(
    background = "#F8FAFC",
    nodeFill = "#FFFFFF",
    nodeLine = "#94A3B8",
    nodeFont = "#0F172A",
    edgeLine = "#64748B",
    edgeFont = "#475569",
    startFill = "#10B981",
    startLine = "#059669",
    endFill = "#EF4444",
    endLine = "#DC2626",
  )

private class Icon(val glyph: String, val color: String)

private val ICONS =
  mapOf(
    AGENT_NODE_TYPE to Icon("✦", "#42A5F5"),
    WORKFLOW_NODE_TYPE to Icon("⊷", "#9333EA"),
    "function" to Icon("ƒ", "#10B981"),
    JOIN_NODE_TYPE to Icon("⌵", "#F59E0B"),
    TOOL_NODE_TYPE to Icon("🔧", "#6B7280"),
  )

@OptIn(ExperimentalWorkflowApi::class) private val START_NODE_NAME: String = Start.name

private const val END_NODE_NAME = "__END__"

private const val NO_DEFAULT_WARNING = "⚠️ [NO DEFAULT]"

/** Collects [root] and its sub-agents, recursively, as a tree of agents. */
private fun addAgentTree(
  root: GraphNode,
  nodes: MutableList<PlotNode>,
  edges: MutableList<PlotEdge>,
) {
  nodes.add(PlotNode(root.name, AGENT_NODE_TYPE, root.tools.orEmpty()))
  fun addSubAgents(parent: GraphNode) {
    for (sub in parent.subAgents.orEmpty()) {
      nodes.add(PlotNode(sub.name, AGENT_NODE_TYPE, sub.tools.orEmpty()))
      edges.add(PlotEdge(parent.name, sub.name))
      addSubAgents(sub)
    }
  }
  addSubAgents(root)
}

/** Adds each tool as a node, once per name, joined to every node that uses it. */
private fun addTools(nodes: MutableList<PlotNode>, edges: MutableList<PlotEdge>) {
  val toolTypes = LinkedHashMap<String, String>()
  val toolEdges = mutableListOf<PlotEdge>()
  for (node in nodes) {
    if (node.name == START_NODE_NAME) continue
    for (tool in node.tools) {
      if (tool.name.isEmpty()) continue
      toolTypes.putIfAbsent(tool.name, tool.type)
      toolEdges.add(PlotEdge(node.name, tool.name, isToolEdge = true))
    }
  }
  for ((name, type) in toolTypes) {
    if (nodes.none { it.name == name }) nodes.add(PlotNode(name, type, emptyList()))
  }
  edges.addAll(toolEdges)
}

private fun drawNode(dot: DotWriter, node: PlotNode, edges: List<PlotEdge>, palette: Palette) {
  val outgoing = edges.filter { it.from == node.name }
  val icon = ICONS[node.type]
  val tooltip = "tooltip" to node.type.replaceFirstChar { it.titlecase() }
  val fill = "fillcolor" to palette.nodeFill
  when {
    outgoing.any { it.route != null } -> {
      val hasDefault = outgoing.any {
        !it.isToolEdge && (it.route == null || it.route == DEFAULT_ROUTE_JSON)
      }
      val label =
        when {
          hasDefault -> nodeLabel(node.name, icon)
          icon != null ->
            htmlLabel(
              "${iconHtml(icon)} ${escapeHtml(node.name)}<br/><br/>" +
                "<FONT POINT-SIZE=\"10\">$NO_DEFAULT_WARNING</FONT>"
            )
          else ->
            htmlLabel(
              "${escapeHtml(node.name)}<br/><br/><font point-size='10'>$NO_DEFAULT_WARNING</font>"
            )
        }
      dot.node(
        node.name,
        label,
        tooltip,
        "shape" to "diamond",
        "style" to "filled",
        fill,
        "height" to "1.2",
        "width" to "0.8",
        "margin" to "0.0,0.0",
      )
    }
    node.type == JOIN_NODE_TYPE ->
      dot.node(
        node.name,
        nodeLabel(node.name, icon),
        tooltip,
        "shape" to "oval",
        "style" to "filled",
        fill,
        "margin" to "0.05,0.05",
      )
    node.type == TOOL_NODE_TYPE ->
      dot.node(
        node.name,
        nodeLabel(node.name, icon),
        tooltip,
        "style" to "rounded,filled,dashed",
        fill,
      )
    else ->
      dot.node(node.name, nodeLabel(node.name, icon), tooltip, "style" to "rounded,filled", fill)
  }
}

private fun drawTerminus(
  dot: DotWriter,
  name: String,
  label: String,
  fill: String,
  line: String,
  palette: Palette,
) {
  dot.node(
    name,
    plainLabel(label),
    "shape" to "oval",
    "style" to "filled",
    "fillcolor" to fill,
    "color" to line,
    "fontcolor" to palette.nodeFont,
    "fontname" to "Helvetica-Bold",
    "width" to "0.9",
    "fixedsize" to "true",
  )
}

private val DEFAULT_ROUTE_JSON = JsonPrimitive(Route.DEFAULT_ROUTE_SENTINEL)

/** The node's name, led by its type's icon when the type has one. */
private fun nodeLabel(name: String, icon: Icon?): DotLabel =
  if (icon == null) plainLabel(name) else htmlLabel("${iconHtml(icon)} ${escapeHtml(name)}")

private fun iconHtml(icon: Icon) =
  "<FONT COLOR=\"${icon.color}\" POINT-SIZE=\"14\">${icon.glyph}</FONT>"

/** Formats a route value as an edge label. */
private fun routeLabel(route: JsonElement): String =
  when (route) {
    is JsonArray ->
      route.joinToString(", ", "[", "]") { item ->
        if (item is JsonPrimitive && item.isString) quoteRouteItem(item.content)
        else routeLabel(item)
      }
    is JsonPrimitive ->
      if (route.isString) route.content
      else route.booleanOrNull?.let { if (it) "True" else "False" } ?: route.content
    else -> route.toString()
  }

/** Quotes and escapes a string element inside a multi-route edge label. */
private fun quoteRouteItem(text: String): String {
  val quote = if ('\'' in text && '"' !in text) '"' else '\''
  val escaped =
    text
      .replace("\\", "\\\\")
      .replace("\n", "\\n")
      .replace("\r", "\\r")
      .replace("\t", "\\t")
      .replace("$quote", "\\$quote")
  return "$quote$escaped$quote"
}

private fun escapeHtml(text: String): String =
  text
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&#x27;")

/** A label in DOT form: a quoted string, or HTML-like markup, which DOT takes unquoted. */
@JvmInline private value class DotLabel(val dot: String)

private fun plainLabel(text: String) = DotLabel(quoteDot(text))

private fun htmlLabel(markup: String) = DotLabel("<$markup>")

private val DOT_ID = Regex("[a-zA-Z_][a-zA-Z0-9_]*|-?(\\.[0-9]+|[0-9]+(\\.[0-9]*)?)")

private val DOT_KEYWORDS = setOf("node", "edge", "graph", "digraph", "subgraph", "strict")

/** An even run of backslashes, then a double quote that may already be escaped. */
private val OPTIONALLY_ESCAPED_QUOTE = Regex("""((?:\\\\)*)\\?"""")

/**
 * Formats [value] as a DOT identifier, quoting it and escaping unescaped double quotes unless it is
 * a plain identifier or number.
 */
private fun quoteDot(value: String): String =
  if (DOT_ID.matches(value) && value.lowercase() !in DOT_KEYWORDS) value
  else "\"${value.replace(OPTIONALLY_ESCAPED_QUOTE) { it.groupValues[1] + "\\\"" }}\""

/** Builds Graphviz DOT source with `label` placed before other sorted attributes. */
private class DotWriter {
  private val out = StringBuilder("// Workflow Visualization\ndigraph {\n")

  fun statement(kind: String, vararg attributes: Pair<String, String>) {
    line("$kind ${attributeList(null, attributes)}")
  }

  fun node(name: String, label: DotLabel, vararg attributes: Pair<String, String>) {
    line("${quoteDot(name)} ${attributeList(label, attributes)}")
  }

  fun edge(from: String, to: String, label: DotLabel?, vararg attributes: Pair<String, String>) {
    val list =
      if (label == null && attributes.isEmpty()) "" else " ${attributeList(label, attributes)}"
    line("${quoteDot(from)} -> ${quoteDot(to)}$list")
  }

  fun build(): String = out.append("}\n").toString()

  private fun line(text: String) {
    out.append('\t').append(text).append('\n')
  }

  private fun attributeList(label: DotLabel?, attributes: Array<out Pair<String, String>>): String {
    val sorted =
      attributes.sortedBy { it.first }.map { (name, value) -> "$name=${quoteDot(value)}" }
    return (listOfNotNull(label?.let { "label=${it.dot}" }) + sorted).joinToString(" ", "[", "]")
  }
}
