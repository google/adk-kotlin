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

@file:OptIn(ExperimentalWorkflowApi::class, AdkJavaInteropApi::class)

package com.google.adk.kt.workflow

import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EdgeChainTest {

  @Test
  fun from_chainOfCalls_addsTheSameEdgesAsTheKotlinDsl() {
    // Arrange
    val plan = StubNode("plan")
    val web = StubNode("web")
    val docs = StubNode("docs")
    val gather = JoinNode("gather")
    val write = StubNode("write")
    val publish = StubNode("publish")

    // Act
    val chained = edges {
      from(Start).then(plan).then(listOf(web, docs)).joinTo(gather).then(write).route {
        on("done") then publish
      }
    }

    // Assert
    val expected = edges {
      Start.then(plan).then(listOf(web, docs)).joinTo(gather).then(write).route {
        on("done") then publish
      }
    }
    assertEquals(expected, chained)
  }

  @Test
  fun fromStart_chainOfCalls_startsAtStart() {
    // Arrange
    val a = StubNode("a")
    val b = StubNode("b")

    // Act
    val chained = edges { fromStart().then(a).then(b) }

    // Assert
    assertEquals(edges { Start.then(a).then(b) }, chained)
  }

  @Test
  fun chain_severalNodes_addsTheSameEdgesAsTheKotlinDsl() {
    // Arrange
    val a = StubNode("a")
    val b = StubNode("b")
    val c = StubNode("c")
    val d = StubNode("d")

    // Act
    val chained = edges { fromStart().chain(a, b, c).route { on("x") then d } }

    // Assert
    assertEquals(edges { chain(Start, a, b, c).route { on("x") then d } }, chained)
  }

  @Test
  fun then_nodeGroup_fansOutLikeTheKotlinDsl() {
    // Arrange
    val web = StubNode("web")
    val docs = StubNode("docs")
    val gather = JoinNode("gather")

    // Act
    val chained = edges { from(Start).then(nodes(web, docs)).joinTo(gather) }

    // Assert
    assertEquals(edges { Start.then(nodes(web, docs)).joinTo(gather) }, chained)
  }

  @Test
  fun from_group_joinsWithoutAFanOut() {
    // Arrange
    val web = StubNode("web")
    val docs = StubNode("docs")
    val gather = JoinNode("gather")
    val write = StubNode("write")

    // Act
    val edges = edges { from(nodes(web, docs)).joinTo(gather).then(write) }

    // Assert
    assertEquals(listOf(Edge(web, gather), Edge(docs, gather), Edge(gather, write)), edges)
  }

  @Test
  fun from_collection_joinsLikeTheKotlinDsl() {
    // Arrange
    val web = StubNode("web")
    val docs = StubNode("docs")
    val gather = JoinNode("gather")
    val write = StubNode("write")

    // Act
    val chained = edges { from(listOf(web, docs)).joinTo(gather).then(write) }

    // Assert
    assertEquals(edges { listOf(web, docs).joinTo(gather).then(write) }, chained)
  }

  @Test
  fun on_everyRouteKind_addsTheSameEdgesAsTheKotlinDsl() {
    // Arrange
    val gate = StubNode("gate")
    val plan = StubNode("plan")
    val publish = StubNode("publish")
    val two = StubNode("two")
    val yes = StubNode("yes")
    val other = StubNode("other")

    // Act
    val chained = edges {
      from(gate).on("retry").then(plan).then(gate)
      from(gate).on(anyOf("done", "ok")).then(publish)
      from(gate).on(2).then(two)
      from(gate).on(true).then(yes)
      from(gate).on(Route.Default).then(other)
    }

    // Assert
    val expected = edges {
      gate.on("retry").then(plan).then(gate)
      gate.on(anyOf("done", "ok")).then(publish)
      gate.on(2).then(two)
      gate.on(true).then(yes)
      gate.on(Route.Default).then(other)
    }
    assertEquals(expected, chained)
  }

  @Test
  fun on_withoutThen_failsInEdges() {
    // Arrange
    val gate = StubNode("gate")

    // Act
    val error =
      assertFailsWith<IllegalStateException> {
        edges {
          val unused = from(gate).on("done")
        }
      }

    // Assert
    assertContains(error.message.orEmpty(), "on(...) from node 'gate' has no target")
  }
}
