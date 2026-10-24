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
import kotlin.test.assertNotNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable

/** A node with explicit schemas that passes its input through. */
private class SchemaNode(
  override val name: String,
  override val inputSchema: Schema? = null,
  override val outputSchema: Schema? = null,
) : Node {
  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow { emit(nodeInput) }
}

@Serializable private data class FitBasicOrder(val id: String, val total: Double)

@Serializable private data class FitFullOrder(val id: String, val total: Double, val note: String)

@Serializable
private data class FitOrderWithNote(val id: String, val total: Double, val note: String? = null)

@Serializable private data class FitSettings(val theme: String = "light")

/** A node that outputs [value] as an [O]. */
private inline fun <reified O> produce(value: O): FunctionNode<Any?, O> =
  node("produce") { _, _ -> value }

/** A node that takes an [I] and outputs nothing. */
private inline fun <reified I> consume(): FunctionNode<I, Unit> = node("consume") { _, _ -> }

@Serializable private data class RankedTopic(val title: String, val score: Int)

@Serializable private data class RankedEntry(val topic: RankedTopic, val rank: Int)

/** An object schema with one required string property named [property]. */
private fun objectWith(property: String) =
  Schema(
    type = Type.OBJECT,
    properties = mapOf(property to Schema(type = Type.STRING)),
    required = listOf(property),
  )

/** Graph validation, rule by rule. */
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

  @Test
  fun validateGraph_equalSchemasOnAnEdge_isAccepted() {
    // Arrange
    val a = SchemaNode("a", outputSchema = objectWith("x"))
    val b = SchemaNode("b", inputSchema = objectWith("x"))

    // Act
    val graph = Graph.of(listOf(Edge(Start, a), Edge(a, b)))

    // Assert
    assertEquals(setOf("b"), graph.terminalNodeNames)
  }

  @Test
  fun validateGraph_schemaWithKeysBesideAnyOf_isCheckedAsAWhole() {
    // Arrange
    val withAnyOf = objectWith("id").copy(anyOf = listOf(objectWith("a")))
    val shared = SchemaNode("shared", outputSchema = withAnyOf)
    val sameInput = SchemaNode("same", inputSchema = withAnyOf)
    val integerOutput = SchemaNode("integer", outputSchema = Schema(type = Type.INTEGER))
    val stringInput =
      SchemaNode(
        "string",
        inputSchema = Schema(type = Type.STRING, anyOf = listOf(Schema(type = Type.INTEGER))),
      )

    // Act
    val graph = Graph.of(listOf(Edge(Start, shared), Edge(shared, sameInput)))
    val error =
      assertFailsWith<GraphValidationException> {
        Graph.of(listOf(Edge(Start, integerOutput), Edge(integerOutput, stringInput)))
      }

    // Assert
    assertEquals(setOf("same"), graph.terminalNodeNames)
    assertContains(error.message.orEmpty(), "Schema mismatch on edge integer -> string.")
  }

  /** The failure names the edge and both schemas. */
  @Test
  fun validateGraph_differentSchemasOnAnEdge_isRejected() {
    // Arrange
    val a = SchemaNode("a", outputSchema = objectWith("x"))
    val b = SchemaNode("b", inputSchema = objectWith("y"))

    // Act
    val error =
      assertFailsWith<GraphValidationException> { Graph.of(listOf(Edge(Start, a), Edge(a, b))) }

    // Assert
    assertEquals(
      "Graph validation failed. Schema mismatch on edge a -> b. Output schema" +
        """ {"type":"OBJECT","properties":{"x":{"type":"STRING"}},"required":["x"]}""" +
        " does not fit input schema" +
        """ {"type":"OBJECT","properties":{"y":{"type":"STRING"}},"required":["y"]}.""",
      error.message,
    )
  }

  /** A missing schema on either end passes. */
  @Test
  fun validateGraph_schemaMissingOnOneEnd_isAccepted() {
    // Arrange
    val a = SchemaNode("a", outputSchema = objectWith("x"))
    val b = SchemaNode("b")
    val c = SchemaNode("c", inputSchema = objectWith("z"))

    // Act
    val graph = Graph.of(listOf(Edge(Start, a), Edge(a, b), Edge(b, c)))

    // Assert
    assertEquals(setOf("c"), graph.terminalNodeNames)
  }

  /** Accepts a hand-written schema that lists the same `required` names in another order. */
  @Test
  fun validateGraph_requiredInAnotherOrder_isAccepted() {
    // Arrange
    val consume = node("consume") { _, topic: RankedTopic -> topic.title }
    val inferred = assertNotNull(consume.inputSchema)
    assertEquals(listOf("title", "score"), inferred.required)
    val produce =
      SchemaNode("produce", outputSchema = inferred.copy(required = listOf("score", "title")))

    // Act
    val graph = Graph.of(listOf(Edge(Start, produce), Edge(produce, consume)))

    // Assert
    assertEquals(setOf("consume"), graph.terminalNodeNames)
  }

  /** Ignores the order of `required` in nested schemas too, here a list's items. */
  @Test
  fun validateGraph_nestedRequiredInAnotherOrder_isAccepted() {
    // Arrange
    val consume = node("consume") { _, topics: List<RankedTopic> -> topics.size }
    val inferred = assertNotNull(consume.inputSchema)
    val items = assertNotNull(inferred.items)
    assertEquals(listOf("title", "score"), items.required)
    val produce =
      SchemaNode(
        "produce",
        outputSchema = inferred.copy(items = items.copy(required = items.required?.reversed())),
      )

    // Act
    val graph = Graph.of(listOf(Edge(Start, produce), Edge(produce, consume)))

    // Assert
    assertEquals(setOf("consume"), graph.terminalNodeNames)
  }

  /** Ignores the order of `required` in a nested object property. */
  @Test
  fun validateGraph_propertyRequiredInAnotherOrder_isAccepted() {
    // Arrange
    val consume = node("consume") { _, entry: RankedEntry -> entry.rank }
    val inferred = assertNotNull(consume.inputSchema)
    val topic = assertNotNull(inferred.properties?.get("topic"))
    assertEquals(listOf("title", "score"), topic.required)
    val reordered = topic.copy(required = listOf("score", "title"))
    val produce =
      SchemaNode(
        "produce",
        outputSchema = inferred.copy(properties = inferred.properties + ("topic" to reordered)),
      )

    // Act
    val graph = Graph.of(listOf(Edge(Start, produce), Edge(produce, consume)))

    // Assert
    assertEquals(setOf("consume"), graph.terminalNodeNames)
  }

  /** Ignores the order of `required` in an `anyOf` alternative, for hand-written schemas. */
  @Test
  fun validateGraph_anyOfRequiredInAnotherOrder_isAccepted() {
    // Arrange
    fun anyOfObject(vararg required: String) =
      Schema(
        anyOf =
          listOf(
            Schema(
              type = Type.OBJECT,
              properties =
                mapOf("a" to Schema(type = Type.STRING), "b" to Schema(type = Type.STRING)),
              required = required.toList(),
            )
          )
      )
    val a = SchemaNode("a", outputSchema = anyOfObject("a", "b"))
    val b = SchemaNode("b", inputSchema = anyOfObject("b", "a"))

    // Act
    val graph = Graph.of(listOf(Edge(Start, a), Edge(a, b)))

    // Assert
    assertEquals(setOf("b"), graph.terminalNodeNames)
  }

  /** Still rejects a schema whose `required` names differ, not only their order. */
  @Test
  fun validateGraph_differentRequiredNames_isRejected() {
    // Arrange
    val consume = node("consume") { _, topic: RankedTopic -> topic.title }
    val inferred = assertNotNull(consume.inputSchema)
    val produce = SchemaNode("produce", outputSchema = inferred.copy(required = listOf("title")))

    // Act
    val error =
      assertFailsWith<GraphValidationException> {
        Graph.of(listOf(Edge(Start, produce), Edge(produce, consume)))
      }

    // Assert
    assertContains(error.message.orEmpty(), "Schema mismatch on edge produce -> consume.")
  }

  /** A hand-written producer schema, open as written, fits a class whose fields it declares. */
  @Test
  fun validateGraph_openHandWrittenSchemaIntoInferredClass_isAccepted() {
    // Arrange
    val handWritten =
      Schema(
        type = Type.OBJECT,
        properties =
          mapOf("title" to Schema(type = Type.STRING), "score" to Schema(type = Type.INTEGER)),
        required = listOf("title", "score"),
      )
    val agentLike = SchemaNode("agent", outputSchema = handWritten)
    val consume = node("consume") { _, topic: RankedTopic -> topic.title }

    // Act
    val graph = Graph.of(listOf(Edge(Start, agentLike), Edge(agentLike, consume)))

    // Assert
    assertEquals(setOf("consume"), graph.terminalNodeNames)
  }

  /** Accepts an edge when every value the producer's type admits fits the consumer's type. */
  @Test
  fun validateGraph_typedEdge_isAcceptedWhenEveryProducedValueFits() {
    // Arrange
    fun accepts(from: Node, to: Node): Boolean =
      try {
        val unused = Graph.of(listOf(Edge(Start, from), Edge(from, to)))
        true
      } catch (e: GraphValidationException) {
        assertContains(e.message.orEmpty(), "Schema mismatch")
        false
      }

    // Act
    val results =
      mapOf(
        "Int -> Int?" to accepts(produce<Int>(1), consume<Int?>()),
        "Int -> Double" to accepts(produce<Int>(1), consume<Double>()),
        "List<Int> -> List<Double>" to
          accepts(produce<List<Int>>(listOf(1)), consume<List<Double>>()),
        "subset -> optional note" to
          accepts(produce(FitBasicOrder("o1", 1.0)), consume<FitOrderWithNote>()),
        "Int? -> Int" to accepts(produce<Int?>(1), consume<Int>()),
        "String -> Int" to accepts(produce("1"), consume<Int>()),
        "List<Int?> -> List<Int>" to accepts(produce<List<Int?>>(listOf(1)), consume<List<Int>>()),
        "superset -> class" to
          accepts(produce(FitFullOrder("o1", 1.0, "n")), consume<FitBasicOrder>()),
        "disjoint -> all-optional" to
          accepts(produce(FitBasicOrder("o1", 1.0)), consume<FitSettings>()),
      )

    // Assert
    assertEquals(
      mapOf(
        "Int -> Int?" to true,
        "Int -> Double" to true,
        "List<Int> -> List<Double>" to true,
        "subset -> optional note" to true,
        "Int? -> Int" to false,
        "String -> Int" to false,
        "List<Int?> -> List<Int>" to false,
        "superset -> class" to false,
        "disjoint -> all-optional" to false,
      ),
      results,
    )
  }

  /** Accepts that edge once the input is typed `Any?` or takes the producer's schema. */
  @Test
  fun validateGraph_intOutputIntoAnyOrMatchingSchemaInput_isAccepted() {
    // Arrange
    val produceInt = node<Any?, Int>("produce_int") { _, _ -> 1 }
    val consumeAny = node("consume_any") { _, n: Any? -> n.toString() }
    val consumeMatching =
      node("consume_matching", inputSchema = produceInt.outputSchema) { _, n: Int? -> n }

    // Act
    val graph =
      Graph.of(
        listOf(
          Edge(Start, produceInt),
          Edge(produceInt, consumeAny),
          Edge(produceInt, consumeMatching),
        )
      )

    // Assert
    assertEquals(setOf("consume_any", "consume_matching"), graph.terminalNodeNames)
  }
}
