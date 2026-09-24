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

class GraphTest {

  @Test
  fun terminalNodesAreThoseWithNoOutgoingEdge() {
    // Arrange
    val a = StubNode("a")
    val b = StubNode("b")
    val c = StubNode("c")

    // Act
    val graph = Graph.of(listOf(Edge(Start, a), Edge(a, b), Edge(a, c)))

    // Assert
    assertEquals(setOf("b", "c"), graph.terminalNodeNames)
  }

  @Test
  fun onlyTheEdgeTaggedWithTheEmittedRouteIsFollowed() {
    // Arrange
    val router = StubNode("router")
    val yesNode = StubNode("yes_node")
    val noNode = StubNode("no_node")
    val graph =
      Graph.of(
        listOf(
          Edge(Start, router),
          Edge(router, yesNode, listOf(yes())),
          Edge(router, noNode, listOf(no())),
        )
      )

    // Act
    val successors = graph.nodesTriggeredBy("router", listOf(yes()))

    // Assert
    assertEquals(listOf("yes_node"), successors)
  }

  @Test
  fun anUnmatchedRouteFallsThroughToTheDefaultEdge() {
    // Arrange
    val router = StubNode("router")
    val yesNode = StubNode("yes_node")
    val fallback = StubNode("fallback")
    val graph =
      Graph.of(
        listOf(
          Edge(Start, router),
          Edge(router, yesNode, listOf(yes())),
          Edge(router, fallback, listOf(Route.Default)),
        )
      )

    // Act
    val successors = graph.nodesTriggeredBy("router", listOf(Route.Tag("other")))

    // Assert
    assertEquals(listOf("fallback"), successors)
  }

  /** Pins adk-python `test_graph.py::test_get_next_pending_nodes_with_default_route_fan_out`. */
  @Test
  fun anUnmatchedRouteTriggersEveryDefaultEdge() {
    // Arrange
    val router = StubNode("router")
    val named = StubNode("named")
    val first = StubNode("first")
    val second = StubNode("second")
    val graph =
      Graph.of(
        listOf(
          Edge(Start, router),
          Edge(router, named, Route.Tag("route1")),
          Edge(router, first, Route.Default),
          Edge(router, second, Route.Default),
        )
      )

    // Act
    val onFallback = graph.nodesTriggeredBy("router", listOf(Route.Tag("unknown_route")))
    val onNamed = graph.nodesTriggeredBy("router", listOf(Route.Tag("route1")))

    // Assert: a matched route still suppresses every default target.
    assertEquals(listOf("first", "second"), onFallback)
    assertEquals(listOf("named"), onNamed)
  }

  @Test
  fun anUnconditionalEdgeFiresWhateverTheRoute() {
    // Arrange
    val a = StubNode("a")
    val b = StubNode("b")
    val graph = Graph.of(listOf(Edge(Start, a), Edge(a, b)))

    // Act
    val successors = graph.nodesTriggeredBy("a", emittedRoutes = null)

    // Assert
    assertEquals(listOf("b"), successors)
  }

  @Test
  fun aCycleThroughARoutedEdgeIsAllowed() {
    // Arrange
    val a = StubNode("a")
    val b = StubNode("b")

    // Act
    val graph = Graph.of(listOf(Edge(Start, a), Edge(a, b), Edge(b, a, listOf(yes()))))

    // Assert
    assertEquals(listOf("a"), graph.nodesTriggeredBy("b", listOf(yes())))
  }

  @Test
  fun aNodeNameContainingAPathDelimiterIsRejected() {
    // Act: '/' separates path segments and '@' separates a name from its run id, so both corrupt
    // it.
    val slash = assertFailsWith<IllegalArgumentException> { validateNodeName("a/b") }
    val at = assertFailsWith<IllegalArgumentException> { validateNodeName("a@1") }

    // Assert
    assertContains(slash.message!!, "'/'")
    assertContains(at.message!!, "'@'")
  }

  @Test
  fun aNodeNameContainingABranchDelimiterIsRejected() {
    // '.' separates branch-path segments, so it would corrupt branch derivation.
    val dot = assertFailsWith<IllegalArgumentException> { validateNodeName("a.b") }

    assertContains(dot.message!!, "'.'")
  }

  @Test
  fun anEmptyNodeNameIsRejected() {
    val empty = assertFailsWith<IllegalArgumentException> { validateNodeName("") }

    assertContains(empty.message!!, "must not be empty")
  }

  @Test
  fun aNodeWhoseRoutingEdgesNoneMatchTriggersNothing() {
    // Arrange: a router with one routed edge and no default.
    val router = StubNode("router")
    val yesNode = StubNode("yes_node")
    val graph = Graph.of(listOf(Edge(Start, router), Edge(router, yesNode, listOf(yes()))))

    // Act: the emitted route matches no edge, so the branch ends.
    val successors = graph.nodesTriggeredBy("router", listOf(no()))

    // Assert
    assertEquals(emptyList<String>(), successors)
  }
}
