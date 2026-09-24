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

import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/** Graph validation, rule by rule, matching adk-python's `utils/_graph_validation.py`. */
class GraphValidationTest {

  @Test
  fun aGraphWithoutStartIsRejected() {
    // Arrange
    val a = StubNode("a")
    val b = StubNode("b")

    // Act
    val error = assertFailsWith<GraphValidationException> { Graph.of(listOf(Edge(a, b))) }

    // Assert
    assertEquals(
      "Graph validation failed. START node (name: '__START__') not found in graph nodes.",
      error.message,
    )
  }

  @Test
  fun aRouteOnAStartEdgeIsRejected() {
    // Arrange
    val a = StubNode("a")

    // Act
    val error =
      assertFailsWith<GraphValidationException> { Graph.of(listOf(Edge(Start, a, listOf(yes())))) }

    // Assert: the route prints as its value, as adk-python prints it.
    assertContains(
      error.message!!,
      "Edges from START must not have routes (edge to a has route yes).",
    )
  }

  @Test
  fun aDefaultRouteOnAStartEdgeIsNamedByItsSentinel() {
    // Arrange
    val a = StubNode("a")

    // Act
    val error =
      assertFailsWith<GraphValidationException> { Graph.of(listOf(Edge(Start, a, Route.Default))) }

    // Assert
    assertContains(error.message!!, "(edge to a has route __DEFAULT__).")
  }

  @Test
  fun aGraphKeepsItsOwnCopyOfTheEdges() {
    // Arrange
    val a = StubNode("a")
    val edges = mutableListOf(Edge(Start, a))
    val graph = Graph.of(edges)

    // Act: an edge into START added afterwards would have failed validation.
    edges.add(Edge(a, Start))

    // Assert
    assertEquals(listOf(Edge(Start, a)), graph.edges)
  }

  @Test
  fun unreachableNodesAreRejectedAndListedInOrder() {
    // Arrange
    val a = StubNode("a")
    val orphan = StubNode("orphan")
    val sink = StubNode("sink")

    // Act
    val error =
      assertFailsWith<GraphValidationException> {
        Graph.of(listOf(Edge(Start, a), Edge(sink, a), Edge(orphan, sink)))
      }

    // Assert
    assertContains(
      error.message!!,
      "The following nodes are unreachable from START: [orphan, sink]",
    )
  }

  @Test
  fun aDisconnectedRoutedSubgraphIsUnreachable() {
    // Arrange: b and c only route to each other, so START reaches neither.
    val a = StubNode("a")
    val b = StubNode("b")
    val c = StubNode("c")

    // Act
    val error =
      assertFailsWith<GraphValidationException> {
        Graph.of(
          listOf(
            Edge(Start, a),
            Edge(b, c, listOf(Route.Tag("x"))),
            Edge(c, b, listOf(Route.Tag("y"))),
          )
        )
      }

    // Assert
    assertContains(error.message!!, "unreachable from START: [b, c]")
  }

  @Test
  fun anEdgeIntoStartIsRejected() {
    // Arrange
    val a = StubNode("a")

    // Act
    val error =
      assertFailsWith<GraphValidationException> { Graph.of(listOf(Edge(Start, a), Edge(a, Start))) }

    // Assert
    assertEquals("Graph validation failed. START node must not have incoming edges.", error.message)
  }

  @Test
  fun overlappingEdgesBetweenTheSameTwoNodesAreRejected() {
    // Arrange: an empty route list is unconditional, which overlaps every other edge.
    val x = Route.Tag("x")
    val y = Route.Tag("y")
    val z = Route.Tag("z")
    val unconditional = emptyList<Route>()
    val overlapping =
      listOf(
        unconditional to unconditional,
        listOf(x) to listOf(x),
        listOf(x) to unconditional,
        unconditional to listOf(x),
        listOf(x, y) to listOf(y),
        listOf(x, y) to listOf(y, z),
      )

    for ((first, second) in overlapping) {
      val a = StubNode("a")
      val b = StubNode("b")

      // Act
      val error =
        assertFailsWith<GraphValidationException>("$first vs $second") {
          Graph.of(listOf(Edge(Start, a), Edge(a, b, first), Edge(a, b, second)))
        }

      // Assert
      assertEquals("Graph validation failed. Duplicate edge found: from=a, to=b", error.message)
    }
  }

  @Test
  fun routedEdgesBetweenTheSameTwoNodesWithDisjointRoutesAreAccepted() {
    // Arrange
    val routes = listOf("w", "x", "y", "z").map { Route.Tag(it) }
    val disjoint =
      listOf(
        listOf(routes[0]) to listOf(routes[1]),
        listOf(routes[0], routes[1]) to listOf(routes[2]),
        listOf(routes[0], routes[1]) to listOf(routes[2], routes[3]),
      )

    for ((first, second) in disjoint) {
      val a = StubNode("a")
      val b = StubNode("b")

      // Act
      val graph = Graph.of(listOf(Edge(Start, a), Edge(a, b, first), Edge(a, b, second)))

      // Assert
      assertEquals(listOf(Start.name, "a", "b"), graph.nodes.map { it.name })
    }
  }

  @Test
  fun disjointEdgesToOneTargetEachTriggerItWhenBothMatch() {
    // Arrange
    val a = StubNode("a")
    val b = StubNode("b")
    val graph =
      Graph.of(listOf(Edge(Start, a), Edge(a, b, Route.Tag("x")), Edge(a, b, Route.Tag("y"))))

    // Act
    val successors = graph.nodesTriggeredBy("a", listOf(Route.Tag("x"), Route.Tag("y")))

    // Assert: one trigger per matched edge, as adk-python's get_next_pending_nodes appends.
    assertEquals(listOf("b", "b"), successors)
  }

  @Test
  fun aNamedRouteAndTheDefaultRouteMayShareATarget() {
    // Arrange: the routing-map shape {x: target, DEFAULT: target}.
    val router = StubNode("router")
    val target = StubNode("target")
    val graph =
      Graph.of(
        listOf(
          Edge(Start, router),
          Edge(router, target, Route.Tag("x")),
          Edge(router, target, Route.Default),
        )
      )

    // Act
    val onNamed = graph.nodesTriggeredBy("router", listOf(Route.Tag("x")))
    val onFallback = graph.nodesTriggeredBy("router", listOf(Route.Tag("other")))

    // Assert
    assertEquals(listOf("target"), onNamed)
    assertEquals(listOf("target"), onFallback)
  }

  @Test
  fun anEdgeListingTheSameRouteTwiceIsAccepted() {
    // Arrange: routes on one edge form a set, so a repeat is redundant rather than a conflict.
    val router = StubNode("router")
    val target = StubNode("target")

    // Act
    val graph = Graph.of(listOf(Edge(Start, router), Edge(router, target, listOf(yes(), yes()))))

    // Assert
    assertEquals(listOf("target"), graph.nodesTriggeredBy("router", listOf(yes())))
  }

  @Test
  fun severalDefaultRouteEdgesFromOneNodeAreAccepted() {
    // Arrange
    val router = StubNode("router")
    val first = StubNode("first")
    val second = StubNode("second")

    // Act
    val graph =
      Graph.of(
        listOf(
          Edge(Start, router),
          Edge(router, first, Route.Default),
          Edge(router, second, Route.Default),
        )
      )

    // Assert
    assertEquals(setOf("first", "second"), graph.terminalNodeNames)
  }

  @Test
  fun aSingleDefaultRouteBesideANamedRouteIsAccepted() {
    // Arrange
    val router = StubNode("router")
    val fallback = StubNode("fallback")
    val named = StubNode("named")

    // Act
    val graph =
      Graph.of(
        listOf(
          Edge(Start, router),
          Edge(router, fallback, Route.Default),
          Edge(router, named, Route.Tag("another_route")),
        )
      )

    // Assert
    assertEquals(setOf("fallback", "named"), graph.terminalNodeNames)
  }

  @Test
  fun aDefaultRouteSharingAnEdgeWithAnotherRouteIsRejected() {
    // Arrange
    val router = StubNode("router")
    val target = StubNode("target")

    // Act
    val error =
      assertFailsWith<GraphValidationException> {
        Graph.of(
          listOf(Edge(Start, router), Edge(router, target, listOf(Route.Tag("x"), Route.Default)))
        )
      }

    // Assert
    assertContains(
      error.message!!,
      "DEFAULT_ROUTE cannot be combined with other routes in a list (edge from=router, to=target)",
    )
  }

  @Test
  fun twoDistinctNodesSharingANameAreRejected() {
    // Arrange
    val a = StubNode("a")
    val first = StubNode("dup")
    val second = StubNode("dup")

    // Act
    val error =
      assertFailsWith<GraphValidationException> {
        Graph.of(listOf(Edge(Start, a), Edge(a, first), Edge(a, second)))
      }

    // Assert
    assertContains(error.message!!, "Duplicate node names found: [dup].")
  }

  @Test
  fun reusingOneNodeInstanceAcrossBranchesIsAccepted() {
    // Arrange: a diamond, where both branches name the same join instance.
    val a = StubNode("a")
    val b = StubNode("b")
    val join = StubNode("join")

    // Act
    val graph = Graph.of(listOf(Edge(Start, a), Edge(Start, b), Edge(a, join), Edge(b, join)))

    // Assert
    assertEquals(setOf("join"), graph.terminalNodeNames)
  }

  @Test
  fun anUnconditionalCycleIsRejected() {
    // Arrange
    val a = StubNode("a")
    val b = StubNode("b")

    // Act
    val error =
      assertFailsWith<GraphValidationException> {
        Graph.of(listOf(Edge(Start, a), Edge(a, b), Edge(b, a)))
      }

    // Assert
    assertContains(error.message!!, "Unconditional cycle detected: a -> b -> a.")
  }

  @Test
  fun aLongerUnconditionalCycleIsRejected() {
    // Arrange
    val a = StubNode("a")
    val b = StubNode("b")
    val c = StubNode("c")

    // Act
    val error =
      assertFailsWith<GraphValidationException> {
        Graph.of(listOf(Edge(Start, a), Edge(a, b), Edge(b, c), Edge(c, a)))
      }

    // Assert
    assertContains(error.message!!, "Unconditional cycle detected: a -> b -> c -> a.")
  }

  @Test
  fun anUnconditionalSelfLoopIsRejected() {
    // Arrange
    val a = StubNode("a")

    // Act
    val error =
      assertFailsWith<GraphValidationException> { Graph.of(listOf(Edge(Start, a), Edge(a, a))) }

    // Assert
    assertContains(error.message!!, "Unconditional cycle detected: a -> a.")
  }

  @Test
  fun aCycleThroughARoutedBackEdgeIsAccepted() {
    // Arrange
    val a = StubNode("a")
    val b = StubNode("b")

    // Act
    val graph = Graph.of(listOf(Edge(Start, a), Edge(a, b), Edge(b, a, Route.Tag("retry"))))

    // Assert
    assertEquals(listOf(Start.name, "a", "b"), graph.nodes.map { it.name })
  }

  @Test
  fun aRoutedSelfLoopIsAccepted() {
    // Arrange
    val a = StubNode("a")
    val b = StubNode("b")

    // Act
    val graph =
      Graph.of(
        listOf(Edge(Start, a), Edge(a, a, Route.Tag("continue")), Edge(a, b, Route.Tag("done")))
      )

    // Assert
    assertEquals(setOf("b"), graph.terminalNodeNames)
  }

  @Test
  fun aValidationFailureIsCatchableAsAnIllegalArgumentException() {
    // Arrange
    val a = StubNode("a")
    val b = StubNode("b")

    // Act
    val error = assertFailsWith<IllegalArgumentException> { Graph.of(listOf(Edge(a, b))) }

    // Assert
    assertEquals(GraphValidationException::class, error::class)
  }

  @Test
  fun constructingAWorkflowValidatesItsGraph() {
    // Arrange
    val a = StubNode("a")
    val b = StubNode("b")

    // Act
    val error =
      assertFailsWith<GraphValidationException> {
        Workflow(name = "wf", edges = listOf(Edge(Start, a), Edge(a, b), Edge(b, a)))
      }

    // Assert
    assertContains(error.message!!, "Unconditional cycle detected")
  }

  @Test
  fun aWellFormedWorkflowBuilds() {
    // Arrange
    val a = StubNode("a")

    // Act
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, a)))

    // Assert
    assertNotNull(workflow.graph)
  }
}
