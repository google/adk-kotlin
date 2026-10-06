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
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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

class WorkflowDslTest {

  @Test
  fun edges_declaresTheEdgesThatWorkflowBuildsFrom() {
    // Arrange
    val a = StubNode("a")
    val b = StubNode("b")
    val block = EdgesDslBlock<EdgesBuilder> { Start.then(a).route { on("x") then b } }

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
    val edges = edges { Start then a then b }

    // Assert
    assertEquals(listOf(Edge(Start, a), Edge(a, b)), edges)
  }

  @Test
  fun chain_severalNodes_connectsEachNodeToTheNext() {
    // Arrange
    val a = StubNode("a")
    val b = StubNode("b")
    val c = StubNode("c")

    // Act
    val edges = edges { chain(Start, a, b, c) }

    // Assert
    assertEquals(listOf(Edge(Start, a), Edge(a, b), Edge(b, c)), edges)
  }

  @Test
  fun chain_twoNodes_addsOneEdge() {
    // Arrange
    val a = StubNode("a")
    val b = StubNode("b")

    // Act
    val edges = edges { chain(a, b) }

    // Assert
    assertEquals(listOf(Edge(a, b)), edges)
  }

  @Test
  fun chain_followedByRoute_continuesFromTheLastNode() {
    // Arrange
    val a = StubNode("a")
    val b = StubNode("b")
    val c = StubNode("c")

    // Act
    val edges = edges { chain(Start, a, b).route { on("x") then c } }

    // Assert
    assertEquals(listOf(Edge(Start, a), Edge(a, b), Edge(b, c, Route.Tag("x"))), edges)
  }

  @Test
  fun chain_onANode_connectsItToEachNodeInOrder() {
    // Arrange
    val a = StubNode("a")
    val b = StubNode("b")
    val c = StubNode("c")
    val d = StubNode("d")

    // Act
    val edges = edges { a.chain(b, c, d) }

    // Assert
    assertEquals(listOf(Edge(a, b), Edge(b, c), Edge(c, d)), edges)
  }

  @Test
  fun chain_onANodeAfterAJoin_continuesTheChainLikeThen() {
    // Arrange
    val web = StubNode("web")
    val docs = StubNode("docs")
    val gather = JoinNode("gather")
    val write = StubNode("write")
    val review = StubNode("review")
    val publish = StubNode("publish")
    val x = StubNode("x")
    val y = StubNode("y")

    // Act
    val chained = edges {
      Start.then(nodes(web, docs)).joinTo(gather).chain(write, review, publish).then(nodes(x, y))
    }

    // Assert
    val expected = edges {
      Start.then(nodes(web, docs))
        .joinTo(gather)
        .then(write)
        .then(review)
        .then(publish)
        .then(nodes(x, y))
    }
    assertEquals(expected, chained)
  }

  @Test
  fun then_severalNodes_fansOutToEach() {
    // Arrange
    val plan = StubNode("plan")
    val web = StubNode("web")
    val docs = StubNode("docs")
    val news = StubNode("news")

    // Act
    val edges = edges { Start.then(plan).then(listOf(web, docs, news)) }

    // Assert
    assertEquals(
      listOf(Edge(Start, plan), Edge(plan, web), Edge(plan, docs), Edge(plan, news)),
      edges,
    )
  }

  @Test
  fun then_emptyCollection_addsNoEdges() {
    // Arrange
    val plan = StubNode("plan")

    // Act
    val edges = edges { Start then plan then emptyList() }

    // Assert
    assertEquals(listOf(Edge(Start, plan)), edges)
  }

  @Test
  fun then_collection_returnsTheTargetsForJoinTo() {
    // Arrange
    val web = StubNode("web")
    val docs = StubNode("docs")
    val gather = JoinNode("gather")
    val write = StubNode("write")

    // Act
    val edges = edges { Start.then(listOf(web, docs)).joinTo(gather).then(write) }

    // Assert
    assertEquals(
      listOf(
        Edge(Start, web),
        Edge(Start, docs),
        Edge(web, gather),
        Edge(docs, gather),
        Edge(gather, write),
      ),
      edges,
    )
  }

  @Test
  fun nodes_builtInNodesOfDifferentTypes_fansOutToEach() {
    // Arrange
    val plan = StubNode("plan")
    val nested = workflow("nested") { Start.then(StubNode("inner")) }
    val gather = JoinNode("gather")

    // Act
    val edges = edges { plan.then(nodes(nested, gather)) }

    // Assert
    assertEquals(listOf(Edge(plan, nested), Edge(plan, gather)), edges)
  }

  @Test
  fun joinTo_fanOutGroup_connectsEachToTheJoinAndContinuesFromIt() {
    // Arrange
    val web = StubNode("web")
    val docs = StubNode("docs")
    val gather = JoinNode("gather")
    val write = StubNode("write")

    // Act
    val edges = edges { Start.then(nodes(web, docs)).joinTo(gather).then(write) }

    // Assert
    assertEquals(
      listOf(
        Edge(Start, web),
        Edge(Start, docs),
        Edge(web, gather),
        Edge(docs, gather),
        Edge(gather, write),
      ),
      edges,
    )
  }

  @Test
  fun joinTo_infixChain_buildsTheSameEdgesAsDotCalls() {
    // Arrange
    val web = StubNode("web")
    val docs = StubNode("docs")
    val gather = JoinNode("gather")
    val write = StubNode("write")

    // Act
    val infix = edges { Start then nodes(web, docs) joinTo gather then write }

    // Assert
    assertEquals(edges { Start.then(nodes(web, docs)).joinTo(gather).then(write) }, infix)
  }

  @Test
  fun nodes_joinedWithoutFanOut_addsOnlyEdgesIntoTheJoin() {
    // Arrange
    val web = StubNode("web")
    val docs = StubNode("docs")
    val gather = JoinNode("gather")
    val write = StubNode("write")

    // Act
    val edges = edges { nodes(web, docs).joinTo(gather).then(write) }

    // Assert
    assertEquals(listOf(Edge(web, gather), Edge(docs, gather), Edge(gather, write)), edges)
  }

  @Test
  fun joinTo_collection_connectsEachToTheJoinAndContinuesFromIt() {
    // Arrange
    val web = StubNode("web")
    val docs = StubNode("docs")
    val gather = JoinNode("gather")
    val write = StubNode("write")

    // Act
    val edges = edges { listOf(web, docs) joinTo gather then write }

    // Assert
    assertEquals(listOf(Edge(web, gather), Edge(docs, gather), Edge(gather, write)), edges)
  }

  @Test
  fun joinTo_emptyCollection_addsNoEdges() {
    // Arrange
    val gather = JoinNode("gather")

    // Act
    val edges = edges { emptyList<Node>().joinTo(gather) }

    // Assert
    assertEquals(emptyList(), edges)
  }

  @Test
  fun joinFrom_severalNodes_connectsEachToTheJoinAndContinuesFromIt() {
    // Arrange
    val web = StubNode("web")
    val docs = StubNode("docs")
    val gather = JoinNode("gather")
    val write = StubNode("write")

    // Act
    val edges = edges { gather.joinFrom(web, docs).then(write) }

    // Assert
    assertEquals(listOf(Edge(web, gather), Edge(docs, gather), Edge(gather, write)), edges)
  }

  @Test
  fun on_everyRouteKind_buildsRoutedEdgeAndContinuesFromTarget() {
    // Arrange
    val a = StubNode("a")
    val b = StubNode("b")
    val c = StubNode("c")
    val d = StubNode("d")
    val e = StubNode("e")
    val after = StubNode("after")

    // Act
    val edges = edges {
      a on "tag" then b
      a on 7 then c
      a on true then d
      a.on(Route.Default).then(e).then(after)
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
  fun on_completedTwice_fansOutOnThatRoute() {
    // Arrange
    val a = StubNode("a")
    val b = StubNode("b")
    val c = StubNode("c")

    // Act
    val edges = edges {
      val onRetry = a on "retry"
      onRetry then b
      onRetry then c
    }

    // Assert
    assertEquals(listOf(Edge(a, b, Route.Tag("retry")), Edge(a, c, Route.Tag("retry"))), edges)
  }

  @Test
  fun route_everyKeyKind_buildsOneRoutedEdgePerEntry() {
    // Arrange
    val a = StubNode("a")
    val b = StubNode("b")
    val c = StubNode("c")
    val d = StubNode("d")
    val e = StubNode("e")
    val f = StubNode("f")

    // Act
    val edges = edges {
      a.route {
        on("billing") then b
        on(7) then c
        on(false) then d
        on(Route.Tag("custom")) then e
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
  fun route_routeMappedToTwoNodes_fansOutOnThatRoute() {
    // Arrange
    val a = StubNode("a")
    val b = StubNode("b")
    val c = StubNode("c")

    // Act
    val edges = edges {
      a.route {
        on("both").then(b)
        on("both").then(c)
      }
    }

    // Assert
    assertEquals(listOf(Edge(a, b, Route.Tag("both")), Edge(a, c, Route.Tag("both"))), edges)
  }

  @Test
  fun routeMapEntryThen_severalNodes_fansOutForEveryKeyKind() {
    // Arrange
    val a = StubNode("a")
    val b = StubNode("b")
    val c = StubNode("c")
    val d = StubNode("d")

    // Act
    val edges = edges {
      a.route {
        on("tag").then(b, c, d)
        on(7).then(b, c)
        on(false).then(b, c)
        on(Route.Tag("custom")).then(b, c)
        on(anyOf("x", "y")).then(b, c)
        otherwise(b, c)
      }
    }

    // Assert
    val anyRoute = listOf(Route.Tag("x"), Route.Tag("y"))
    assertEquals(
      listOf(
        Edge(a, b, Route.Tag("tag")),
        Edge(a, c, Route.Tag("tag")),
        Edge(a, d, Route.Tag("tag")),
        Edge(a, b, Route.Num(7)),
        Edge(a, c, Route.Num(7)),
        Edge(a, b, Route.Flag(false)),
        Edge(a, c, Route.Flag(false)),
        Edge(a, b, Route.Tag("custom")),
        Edge(a, c, Route.Tag("custom")),
        Edge(a, b, anyRoute),
        Edge(a, c, anyRoute),
        Edge(a, b, Route.Default),
        Edge(a, c, Route.Default),
      ),
      edges,
    )
  }

  @Test
  fun route_keyShorthand_buildsTheSameEdgesAsOn() {
    // Arrange
    val a = StubNode("a")
    val b = StubNode("b")
    val c = StubNode("c")
    val d = StubNode("d")

    // Act
    val shorthand = edges {
      a.route {
        "tag" then b
        7 then c
        false then d
        Route.Tag("custom") then b
        anyOf("x", "y") then c
        "tags".then(b, c, d)
        8.then(b, c)
        true.then(b, c)
        Route.Num(9).then(b, c)
        anyOf(1, 2).then(b, c)
      }
    }

    // Assert
    val expected = edges {
      a.route {
        on("tag") then b
        on(7) then c
        on(false) then d
        on(Route.Tag("custom")) then b
        on(anyOf("x", "y")) then c
        on("tags").then(b, c, d)
        on(8).then(b, c)
        on(true).then(b, c)
        on(Route.Num(9)).then(b, c)
        on(anyOf(1, 2)).then(b, c)
      }
    }
    assertEquals(expected, shorthand)
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
        Start.then(classify).route {
          on("billing") then billing
          on("tech") then tech
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
        Start.then(plan).then(nodes(searchWeb, searchDocs)).joinTo(gather).then(write)
        write.on("needs-more").then(plan)
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
    val error = assertFailsWith<GraphValidationException> { workflow("wf") { Start on "x" then a } }

    // Assert
    assertContains(error.message.orEmpty(), "Edges from START must not have routes")
  }

  @Test
  fun route_emptyRoutingMap_throws() {
    // Arrange
    val a = StubNode("a")

    // Act
    val error = assertFailsWith<IllegalArgumentException> { edges { a route {} } }

    // Assert
    assertContains(error.message.orEmpty(), "routing map of node 'a'")
  }

  @Test
  fun route_entryWithoutThen_throws() {
    // Arrange
    val a = StubNode("a")
    val b = StubNode("b")

    // Act
    val error =
      assertFailsWith<IllegalStateException> {
        edges {
          a.route {
            val unused = on("loop")
            otherwise(b)
          }
        }
      }

    // Assert
    assertContains(error.message.orEmpty(), "on(...) in the routing map of node 'a' has no target")
  }

  @Test
  fun route_entryCompletedAfterTheMapIsBuilt_throws() {
    // Arrange
    val a = StubNode("a")
    val b = StubNode("b")
    val c = StubNode("c")
    lateinit var entry: RouteMapEntry

    // Act
    val error =
      assertFailsWith<IllegalStateException> {
        edges {
          a.route {
            entry = on("x")
            entry then b
          }
          entry then c
        }
      }

    // Assert
    assertContains(error.message.orEmpty(), "routing map of node 'a' was already built")
  }

  @Test
  fun on_withoutThen_throws() {
    // Arrange
    val a = StubNode("a")

    // Act
    val error =
      assertFailsWith<IllegalStateException> {
        workflow("wf") {
          Start then a
          val unused = a on "loop"
        }
      }

    // Assert
    assertContains(error.message.orEmpty(), "on(...) from node 'a' has no target")
  }

  @Test
  fun run_onLoop_repeatsUntilTheNodeRoutesOut() {
    // Arrange
    val draft = Emitter("draft", "D")
    val review = WorkflowDslTestReviewer("review")
    val publish = Emitter("publish", "P")
    val looping =
      workflow("wf") {
        Start then draft then review
        review on "needs-more" then draft
        review on "done" then publish
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
    val edges = edges { Start.then(router).route { on(anyOf("bug", "crash")) then fix } }

    // Assert
    assertEquals(
      listOf(Edge(Start, router), Edge(router, fix, listOf(Route.Tag("bug"), Route.Tag("crash")))),
      edges,
    )
  }

  @Test
  fun anyOf_withOn_buildsOneEdgeWithEveryRoute() {
    // Arrange
    val router = StubNode("router")
    val fix = StubNode("fix")

    // Act
    val edges = edges {
      Start.then(router)
      router.on(anyOf(1, 2)).then(fix)
    }

    // Assert
    assertEquals(Edge(router, fix, listOf(Route.Num(1), Route.Num(2))), edges.last())
  }

  @Test
  fun anyOf_everyOverload_dropsRepeatedRoutes() {
    // Arrange
    val router = StubNode("router")
    val a = StubNode("a")
    val b = StubNode("b")
    val c = StubNode("c")
    val d = StubNode("d")
    val e = StubNode("e")

    // Act
    val edges = edges {
      router.route {
        on(anyOf("x", "y", "x")) then a
        on(anyOf(1, 2, 1)) then b
        on(anyOf(Route.Tag("p"), Route.Flag(true), Route.Tag("p"))) then c
      }
      router.on(anyOf("q", "r", "q")).then(d)
      router.on(anyOf(Route.Num(7), Route.Tag("s"), Route.Num(7))).then(e)
    }

    // Assert
    assertEquals(
      listOf(
        Edge(router, a, listOf(Route.Tag("x"), Route.Tag("y"))),
        Edge(router, b, listOf(Route.Num(1), Route.Num(2))),
        Edge(router, c, listOf(Route.Tag("p"), Route.Flag(true))),
        Edge(router, d, listOf(Route.Tag("q"), Route.Tag("r"))),
        Edge(router, e, listOf(Route.Num(7), Route.Tag("s"))),
      ),
      edges,
    )
  }

  @Test
  fun anyOf_severalRoutesMatch_runsTheTargetOnce() {
    // Arrange
    val router = WorkflowDslTestMultiRouter("router", listOf(Route.Tag("bug"), Route.Tag("crash")))
    val graph =
      workflow("wf") {
        Start.then(router).route { on(anyOf("bug", "crash")) then StubNode("fix", "fixed") }
      }

    // Act
    val runs = runWorkflow(graph).filter { it.output != null }.map { it.nodeInfo?.path }

    // Assert
    assertEquals(listOf("wf@1/fix@1"), runs)
  }

  @Test
  fun anyOf_withTheDefaultRoute_failsValidation() {
    // Arrange
    val router = StubNode("router")
    val fix = StubNode("fix")

    // Act, Assert
    assertFailsWith<GraphValidationException> {
      workflow("wf") {
        Start.then(router).route { on(anyOf(Route.Tag("bug"), Route.Default)) then fix }
      }
    }
  }
}
