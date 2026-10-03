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

@file:OptIn(ExperimentalWorkflowApi::class)

package com.google.adk.kt.workflow

import com.google.adk.kt.agents.Context
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Routes `needs-more` on its first run and `done` after that. */
private class WorkflowDslTestReviewer(override val name: String) : Node {
  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    context.routes = listOf(Route.Tag(if (context.runId == "1") "needs-more" else "done"))
    emit("review${context.runId}")
  }
}

/** Emits every route in [routes] at once. */
private class WorkflowDslTestMultiRouter(
  override val name: String,
  private val routes: List<Route>,
) : Node {
  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    context.routes = routes
  }
}

/** Returns its arguments, so a run shows what reached the tool. */
private class WorkflowDslTestEchoTool(name: String) :
  BaseTool(name = name, description = "Echoes its arguments.") {
  override fun declaration(): FunctionDeclaration? = null

  override suspend fun run(context: ToolContext, args: Map<String, Any?>): Any = args
}

/** Returns the edges [block] declares, without the graph validation a [Workflow] runs. */
private fun edgesOf(block: EdgesBuilder.() -> Unit): List<Edge> = edges { block() }

class WorkflowDslTest {

  @Test
  fun edges_declaresTheEdgesThatWorkflowBuildsFrom() {
    // Arrange
    val a = StubNode("a")
    val b = StubNode("b")
    val block = EdgesDslBlock<EdgesBuilder> { Start.then(a).thenRoute { "x" routesTo b } }

    // Act
    val graphEdges = edges(block)

    // Assert
    assertEquals(workflow("wf", block = block).edges, graphEdges)
  }

  @Test
  fun then_infixChain_connectsEachNodeToTheNext() {
    // Arrange
    val a = StubNode("a")
    val b = StubNode("b")

    // Act
    val edges = edgesOf { Start then a then b }

    // Assert
    assertEquals(listOf(Edge(Start, a), Edge(a, b)), edges)
  }

  @Test
  fun then_severalNodes_fansOutToEach() {
    // Arrange
    val plan = StubNode("plan")
    val web = StubNode("web")
    val docs = StubNode("docs")
    val news = StubNode("news")

    // Act
    val edges = edgesOf { Start.then(plan).then(web, docs, news) }

    // Assert
    assertEquals(
      listOf(Edge(Start, plan), Edge(plan, web), Edge(plan, docs), Edge(plan, news)),
      edges,
    )
  }

  @Test
  fun joinFrom_severalNodes_connectsEachToTheJoinAndContinuesFromIt() {
    // Arrange
    val web = StubNode("web")
    val docs = StubNode("docs")
    val gather = JoinNode("gather")
    val write = StubNode("write")

    // Act
    val edges = edgesOf { gather.joinFrom(web, docs).then(write) }

    // Assert
    assertEquals(listOf(Edge(web, gather), Edge(docs, gather), Edge(gather, write)), edges)
  }

  @Test
  fun routeOn_everyRouteKind_buildsRoutedEdgeAndContinuesFromTarget() {
    // Arrange
    val a = StubNode("a")
    val b = StubNode("b")
    val c = StubNode("c")
    val d = StubNode("d")
    val e = StubNode("e")
    val after = StubNode("after")

    // Act
    val edges = edgesOf {
      a routeOn "tag" to b
      a routeOn 7 to c
      a routeOn true to d
      a.routeOn(Route.Default).to(e).then(after)
    }

    // Assert
    assertEquals(
      listOf(
        Edge(a, b, Route.Tag("tag")),
        Edge(a, c, Route.Num(7)),
        Edge(a, d, Route.Flag(true)),
        Edge(a, e, Route.Default),
        Edge(e, after),
      ),
      edges,
    )
  }

  @Test
  fun routeOn_completedTwice_fansOutOnThatRoute() {
    // Arrange
    val a = StubNode("a")
    val b = StubNode("b")
    val c = StubNode("c")

    // Act
    val edges = edgesOf {
      val onRetry = a routeOn "retry"
      onRetry to b
      onRetry to c
    }

    // Assert
    assertEquals(listOf(Edge(a, b, Route.Tag("retry")), Edge(a, c, Route.Tag("retry"))), edges)
  }

  @Test
  fun thenRoute_everyKeyKind_buildsOneRoutedEdgePerEntry() {
    // Arrange
    val a = StubNode("a")
    val b = StubNode("b")
    val c = StubNode("c")
    val d = StubNode("d")
    val e = StubNode("e")
    val f = StubNode("f")

    // Act
    val edges = edgesOf {
      a.thenRoute {
        "billing" routesTo b
        7 routesTo c
        false routesTo d
        Route.Tag("custom") routesTo e
        otherwise(f)
      }
    }

    // Assert
    assertEquals(
      listOf(
        Edge(a, b, Route.Tag("billing")),
        Edge(a, c, Route.Num(7)),
        Edge(a, d, Route.Flag(false)),
        Edge(a, e, Route.Tag("custom")),
        Edge(a, f, Route.Default),
      ),
      edges,
    )
  }

  @Test
  fun thenRoute_routeMappedToTwoNodes_fansOutOnThatRoute() {
    // Arrange
    val a = StubNode("a")
    val b = StubNode("b")
    val c = StubNode("c")

    // Act
    val edges = edgesOf {
      a.thenRoute {
        "both".routesTo(b)
        "both".routesTo(c)
      }
    }

    // Assert
    assertEquals(listOf(Edge(a, b, Route.Tag("both")), Edge(a, c, Route.Tag("both"))), edges)
  }

  @Test
  fun workflow_routingExample_buildsItsGraph() {
    // Arrange
    val classify = StubNode("classifier")
    val billing = StubNode("billing")
    val tech = StubNode("tech")
    val summarize = StubNode("summary")

    // Act
    val support =
      workflow("support-triage", maxConcurrency = 4) {
        Start.then(classify).thenRoute {
          "billing" routesTo billing
          "tech" routesTo tech
          otherwise(summarize)
        }
        billing.then(summarize)
        tech.then(summarize)
      }

    // Assert
    assertEquals(
      listOf(
        Edge(Start, classify),
        Edge(classify, billing, Route.Tag("billing")),
        Edge(classify, tech, Route.Tag("tech")),
        Edge(classify, summarize, Route.Default),
        Edge(billing, summarize),
        Edge(tech, summarize),
      ),
      support.edges,
    )
  }

  @Test
  fun workflow_fanOutJoinAndLoopExample_buildsItsGraph() {
    // Arrange
    val plan = StubNode("plan")
    val searchWeb = StubNode("search_web")
    val searchDocs = StubNode("search_docs")
    val gather = JoinNode("gather")
    val write = StubNode("write")

    // Act
    val research =
      workflow("research") {
        Start.then(plan).then(searchWeb, searchDocs)
        gather.joinFrom(searchWeb, searchDocs).then(write)
        write.routeOn("needs-more").to(plan)
      }

    // Assert
    assertEquals(
      listOf(
        Edge(Start, plan),
        Edge(plan, searchWeb),
        Edge(plan, searchDocs),
        Edge(searchWeb, gather),
        Edge(searchDocs, gather),
        Edge(gather, write),
        Edge(write, plan, Route.Tag("needs-more")),
      ),
      research.edges,
    )
  }

  @Test
  fun workflow_parameters_reachTheWorkflow() {
    // Arrange
    val config = NodeConfig(timeout = 30.seconds)
    val schema = Schema(type = Type.STRING)

    // Act
    val built =
      workflow(
        name = "wf",
        description = "desc",
        maxConcurrency = 2,
        rerunOnResume = false,
        waitForOutput = true,
        config = config,
        inputSchema = schema,
        outputSchema = schema,
        stateSchema = Schema(type = Type.OBJECT),
      ) {
        Start then StubNode("a")
      }

    // Assert
    assertEquals("wf", built.name)
    assertEquals("desc", built.description)
    assertEquals(2, built.maxConcurrency)
    assertFalse(built.rerunOnResume)
    assertTrue(built.waitForOutput)
    assertEquals(config, built.config)
    assertEquals(schema, built.inputSchema)
    assertEquals(schema, built.outputSchema)
    assertEquals(Schema(type = Type.OBJECT), built.stateSchema)
  }

  @Test
  fun workflow_noParameters_matchesWorkflowConstructorDefaults() {
    // Arrange
    val a = StubNode("a")
    val expected = Workflow(name = "wf", edges = listOf(Edge(Start, a)))

    // Act
    val built = workflow("wf") { Start then a }

    // Assert
    assertEquals(expected.edges, built.edges)
    assertEquals(expected.description, built.description)
    assertEquals(expected.maxConcurrency, built.maxConcurrency)
    assertEquals(expected.rerunOnResume, built.rerunOnResume)
    assertEquals(expected.waitForOutput, built.waitForOutput)
    assertEquals(expected.config, built.config)
    assertEquals(expected.inputSchema, built.inputSchema)
    assertEquals(expected.outputSchema, built.outputSchema)
    assertEquals(expected.stateSchema, built.stateSchema)
  }

  @Test
  fun workflow_invalidGraph_throwsGraphValidationException() {
    // Arrange
    val a = StubNode("a")

    // Act
    val error =
      assertFailsWith<GraphValidationException> { workflow("wf") { Start routeOn "x" to a } }

    // Assert
    assertContains(error.message.orEmpty(), "Edges from START must not have routes")
  }

  @Test
  fun thenRoute_emptyRoutingMap_throws() {
    // Arrange
    val a = StubNode("a")

    // Act
    val error = assertFailsWith<IllegalArgumentException> { edgesOf { a thenRoute {} } }

    // Assert
    assertContains(error.message.orEmpty(), "routing map of node 'a'")
  }

  @Test
  fun routeOn_withoutTo_throws() {
    // Arrange
    val a = StubNode("a")

    // Act
    val error =
      assertFailsWith<IllegalStateException> {
        workflow("wf") {
          Start then a
          val unused = a routeOn "loop"
        }
      }

    // Assert
    assertContains(error.message.orEmpty(), "routeOn from node 'a' has no target")
  }

  @Test
  fun run_routeOnLoop_repeatsUntilTheNodeRoutesOut() {
    // Arrange
    val draft = Emitter("draft", "D")
    val review = WorkflowDslTestReviewer("review")
    val publish = Emitter("publish", "P")
    val looping =
      workflow("wf") {
        Start then draft then review
        review routeOn "needs-more" to draft
        review routeOn "done" to publish
      }

    // Act
    val outputs =
      runWorkflow(looping).filter { it.output != null }.map { it.nodeInfo?.path to it.output }

    // Assert
    assertEquals(
      listOf(
        "wf@1/draft@1" to "D",
        "wf@1/review@1" to "review1",
        "wf@1/draft@2" to "D",
        "wf@1/review@2" to "review2",
        "wf@1/publish@1" to "P",
      ),
      outputs,
    )
  }

  @Test
  fun anyOf_inARoutingMap_buildsOneEdgeWithEveryRoute() {
    // Arrange
    val router = StubNode("router")
    val fix = StubNode("fix")

    // Act
    val edges = edgesOf { Start.then(router).thenRoute { anyOf("bug", "crash") routesTo fix } }

    // Assert
    assertEquals(
      listOf(Edge(Start, router), Edge(router, fix, listOf(Route.Tag("bug"), Route.Tag("crash")))),
      edges,
    )
  }

  @Test
  fun anyOf_withRouteOn_buildsOneEdgeWithEveryRoute() {
    // Arrange
    val router = StubNode("router")
    val fix = StubNode("fix")

    // Act
    val edges = edgesOf {
      Start.then(router)
      router.routeOn(anyOf(1, 2)).to(fix)
    }

    // Assert
    assertEquals(Edge(router, fix, listOf(Route.Num(1), Route.Num(2))), edges.last())
  }

  @Test
  fun anyOf_runsTheTargetOnceWhenSeveralOfItsRoutesMatch() {
    // Arrange
    val router = WorkflowDslTestMultiRouter("router", listOf(Route.Tag("bug"), Route.Tag("crash")))
    val graph =
      workflow("wf") {
        Start.then(router).thenRoute { anyOf("bug", "crash") routesTo StubNode("fix", "fixed") }
      }

    // Act
    val runs = runWorkflow(graph).filter { it.output != null }.map { it.nodeInfo?.path }

    // Assert
    assertEquals(listOf("wf@1/fix@1"), runs)
  }

  @Test
  fun tool_usedInSeveralStatements_becomesOneNode() {
    // Arrange
    val router = StubNode("router")
    val lookup = WorkflowDslTestEchoTool("lookup")
    val sink = StubNode("sink")

    // Act
    val graph =
      workflow("wf") {
        Start.then(router).thenRoute { "a" routesTo lookup }
        router.routeOn("b").to(lookup)
        lookup.then(sink)
      }

    // Assert
    val lookupNodes = graph.edges.flatMap { listOf(it.from, it.to) }.filter { it.name == "lookup" }
    assertEquals(1, lookupNodes.toSet().size)
    assertEquals(4, graph.edges.size)
  }

  @Test
  fun then_tool_runsTheToolOnThePredecessorsOutput() {
    // Arrange
    val echo = WorkflowDslTestEchoTool("echo")
    val graph = workflow("wf") { Start.then(StubNode("args", mapOf("city" to "Paris"))).then(echo) }

    // Act
    val outputs =
      runWorkflow(graph).filter { it.output != null }.associate { it.nodeInfo?.path to it.output }

    // Assert
    assertEquals(mapOf("city" to "Paris"), outputs["wf@1/echo@1"])
  }

  @Test
  fun routesTo_tool_mapsEveryKeyTypeAndOtherwiseToTheToolsNode() {
    // Arrange
    val router = StubNode("router")
    val tagged = WorkflowDslTestEchoTool("tagged")
    val numbered = WorkflowDslTestEchoTool("numbered")
    val flagged = WorkflowDslTestEchoTool("flagged")
    val custom = WorkflowDslTestEchoTool("custom")
    val grouped = WorkflowDslTestEchoTool("grouped")
    val fallback = WorkflowDslTestEchoTool("fallback")

    // Act
    val edges = edgesOf {
      Start.then(router).thenRoute {
        "a" routesTo tagged
        1 routesTo numbered
        true routesTo flagged
        Route.Tag("c") routesTo custom
        anyOf("d", "e", "d") routesTo grouped
        otherwise(fallback)
      }
    }

    // Assert: one edge per entry, each to a node named after its tool, with anyOf deduplicated.
    assertEquals(
      listOf(
        "tagged" to listOf(Route.Tag("a")),
        "numbered" to listOf(Route.Num(1)),
        "flagged" to listOf(Route.Flag(true)),
        "custom" to listOf(Route.Tag("c")),
        "grouped" to listOf(Route.Tag("d"), Route.Tag("e")),
        "fallback" to listOf(Route.Default),
      ),
      edges.drop(1).map { it.to.name to it.routes },
    )
  }

  @Test
  fun then_toolToTool_chainsTheirNodes() {
    // Arrange
    val fetch = WorkflowDslTestEchoTool("fetch")
    val parse = WorkflowDslTestEchoTool("parse")

    // Act
    val edges = edgesOf {
      Start.then(fetch)
      fetch.then(parse)
    }

    // Assert
    assertEquals(
      listOf("__START__" to "fetch", "fetch" to "parse"),
      edges.map { it.from.name to it.to.name },
    )
    assertSame(edges[0].to, edges[1].from)
  }

  @Test
  fun anyOf_withTheDefaultRoute_failsValidation() {
    // Arrange
    val router = StubNode("router")
    val fix = StubNode("fix")

    // Act, Assert
    assertFailsWith<GraphValidationException> {
      workflow("wf") {
        Start.then(router).thenRoute { anyOf(Route.Tag("bug"), Route.Default) routesTo fix }
      }
    }
  }
}
