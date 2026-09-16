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

package com.google.adk.kt.serialization

import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.events.Event
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.workflow.NodeInfo
import com.google.adk.kt.workflow.Route
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.serialization.SerializationException
import org.junit.Test

/**
 * Covers the wire form of [Route] and the all-empty [NodeInfo] normalization on the [Event] graph.
 *
 * Route decode failures must surface as a [SerializationException] rather than a bare
 * [IllegalArgumentException], which the `catch (SerializationException)` sites around decoding
 * would miss.
 */
@OptIn(FrameworkInternalApi::class)
class RouteNodeInfoSerializationTest {

  private fun roundTrip(route: Route): Route =
    adkJson.decodeFromString(Route.serializer(), adkJson.encodeToString(Route.serializer(), route))

  @Test
  fun route_eachVariant_roundTripsLosslessly() {
    assertEquals(Route.Tag("hello"), roundTrip(Route.Tag("hello")))
    assertEquals(Route.Num(5), roundTrip(Route.Num(5)))
    assertEquals(Route.Flag(true), roundTrip(Route.Flag(true)))
    assertEquals(Route.Flag(false), roundTrip(Route.Flag(false)))
    assertEquals(Route.Default, roundTrip(Route.Default))
  }

  @Test
  fun route_encodesAsBareScalar() {
    assertEquals("\"hello\"", adkJson.encodeToString(Route.serializer(), Route.Tag("hello")))
    assertEquals("5", adkJson.encodeToString(Route.serializer(), Route.Num(5)))
    assertEquals("true", adkJson.encodeToString(Route.serializer(), Route.Flag(true)))
    assertEquals("\"__DEFAULT__\"", adkJson.encodeToString(Route.serializer(), Route.Default))
  }

  @Test
  fun route_decodesEachScalarToItsVariant() {
    assertEquals(Route.Tag("hello"), adkJson.decodeFromString(Route.serializer(), "\"hello\""))
    assertEquals(Route.Num(5), adkJson.decodeFromString(Route.serializer(), "5"))
    assertEquals(Route.Flag(true), adkJson.decodeFromString(Route.serializer(), "true"))
    assertEquals(Route.Default, adkJson.decodeFromString(Route.serializer(), "\"__DEFAULT__\""))
  }

  @Test
  fun route_nonPrimitiveValue_failsAsASerializationError() {
    assertFailsWith<SerializationException> { adkJson.decodeFromString(Route.serializer(), "{}") }
  }

  @Test
  fun route_unsupportedScalar_failsAsASerializationError() {
    // A fractional number is neither a string, a boolean, nor a long.
    assertFailsWith<SerializationException> { adkJson.decodeFromString(Route.serializer(), "1.5") }
  }

  @Test
  fun eventActionsRoute_roundTripsAsAnArray() {
    val event =
      Event(author = "agent", actions = EventActions(route = listOf(Route.Tag("x"), Route.Default)))

    val decoded =
      adkJson.decodeFromString(
        Event.serializer(),
        adkJson.encodeToString(Event.serializer(), event),
      )

    assertEquals(listOf(Route.Tag("x"), Route.Default), decoded.actions.route)
  }

  @Test
  fun eventActionsRoute_bareScalarTag_decodesToSingleElementList() {
    val decoded = adkJson.decodeFromString(EventActions.serializer(), """{"route":"approved"}""")

    assertEquals(listOf(Route.Tag("approved")), decoded.route)
  }

  @Test
  fun eventActionsRoute_bareScalarNum_decodesToSingleElementList() {
    val decoded = adkJson.decodeFromString(EventActions.serializer(), """{"route":7}""")

    assertEquals(listOf(Route.Num(7)), decoded.route)
  }

  @Test
  fun eventActionsRoute_bareScalarFlag_decodesToSingleElementList() {
    val decoded = adkJson.decodeFromString(EventActions.serializer(), """{"route":true}""")

    assertEquals(listOf(Route.Flag(true)), decoded.route)
  }

  @Test
  fun eventActionsRoute_array_decodesToMultiElementList() {
    val decoded =
      adkJson.decodeFromString(EventActions.serializer(), """{"route":["x",5,"__DEFAULT__"]}""")

    assertEquals(listOf(Route.Tag("x"), Route.Num(5), Route.Default), decoded.route)
  }

  @Test
  fun eventActionsRoute_singleRoute_encodesAsArray() {
    val encoded =
      adkJson.encodeToString(
        EventActions.serializer(),
        EventActions(route = listOf(Route.Tag("approved"))),
      )

    assertEquals("""{"route":["approved"]}""", encoded)
  }

  @Test
  fun eventActionsRoute_multiElementList_roundTripsLosslessly() {
    val actions = EventActions(route = listOf(Route.Tag("x"), Route.Num(5), Route.Default))

    val decoded =
      adkJson.decodeFromString(
        EventActions.serializer(),
        adkJson.encodeToString(EventActions.serializer(), actions),
      )

    assertEquals(actions.route, decoded.route)
  }

  @Test
  fun nodeInfo_allEmpty_decodesToNull() {
    val decoded =
      adkJson.decodeFromString(Event.serializer(), """{"author":"a","nodeInfo":{"path":""}}""")

    assertNull(decoded.nodeInfo)
  }

  @Test
  fun nodeInfo_workflowNode_isPreservedOnDecode() {
    // messageAsOutput carries information, so a top-level node with an empty path is not dropped.
    val decoded =
      adkJson.decodeFromString(
        Event.serializer(),
        """{"author":"a","nodeInfo":{"path":"","messageAsOutput":true}}""",
      )

    assertEquals(NodeInfo(path = "", messageAsOutput = true), decoded.nodeInfo)
  }

  @Test
  fun nodeInfo_explicitJsonNull_decodesToNull() {
    val decoded = adkJson.decodeFromString(Event.serializer(), """{"author":"a","nodeInfo":null}""")

    assertNull(decoded.nodeInfo)
  }

  @Test
  fun event_workflowFields_roundTripLosslessly() {
    val event =
      Event(
        author = "agent",
        output = "handoff",
        nodeInfo = NodeInfo(path = "wf@1/a@1", outputFor = listOf("wf@1"), messageAsOutput = true),
      )

    val decoded =
      adkJson.decodeFromString(
        Event.serializer(),
        adkJson.encodeToString(Event.serializer(), event),
      )

    assertEquals("handoff", decoded.output)
    assertEquals(event.nodeInfo, decoded.nodeInfo)
  }

  @Test
  fun eventActionsRoute_mergeWith_overridesOrPreservesRoute() {
    val base = EventActions(route = listOf(Route.Tag("initial")))
    val withOverride = EventActions(route = listOf(Route.Tag("updated")))
    val withoutOverride = EventActions(route = null)

    assertEquals(listOf(Route.Tag("updated")), base.mergeWith(withOverride).route)
    assertEquals(listOf(Route.Tag("initial")), base.mergeWith(withoutOverride).route)
  }
}
