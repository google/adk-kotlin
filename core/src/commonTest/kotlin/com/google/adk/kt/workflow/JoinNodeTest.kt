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
import com.google.adk.kt.apps.App
import com.google.adk.kt.events.Event
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.testing.testInvocationContext
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/**
 * Selects [route] and emits [output] when it is non-null. With no output, its successors are
 * triggered with a null input.
 */
private class JoinNodeRouter(
  override val name: String,
  private val route: Route,
  private val output: Any? = null,
) : Node {
  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    context.routes = listOf(route)
    if (output != null) emit(output)
  }
}

/** Outputs the input it received, so a test can see what a join handed downstream. */
private class JoinNodeCapture(override val name: String) : Node {
  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    emit(mapOf("received" to nodeInput))
  }
}

/** An object with a required string `key` and a required integer `value`. */
private val keyValueSchema =
  Schema(
    type = Type.OBJECT,
    properties = mapOf("key" to Schema(type = Type.STRING), "value" to Schema(type = Type.INTEGER)),
    required = listOf("key", "value"),
  )

/** Wires `START -> each predecessor -> join -> capture` and returns the workflow. */
private fun fanInto(join: JoinNode, vararg predecessors: Node): Workflow =
  Workflow(
    name = "wf",
    edges =
      predecessors.map { Edge(Start, it) } +
        predecessors.map { Edge(it, join) } +
        Edge(join, JoinNodeCapture("capture")),
  )

/** Returns the events the node at [path] emitted. */
private fun List<Event>.at(path: String): List<Event> = filter { it.nodeInfo?.path == path }

/**
 * Returns the events every run of the node at [path] emitted (`<path>@1`, `<path>@2`, ...), so a
 * node that fires twice fails `single()`.
 */
private fun List<Event>.runsOf(path: String): List<Event> = filter {
  it.nodeInfo?.path?.substringBeforeLast('@') == path
}

/**
 * Covers the tests in Python ADK's `workflow/test_join_node.py`, plus
 * `test_routing_map_fan_out_runs_both_targets` from `workflow/test_workflow_routes.py`.
 */
class JoinNodeTest {

  @Test
  fun run_twoPredecessors_outputsMapKeyedByName() {
    // Arrange
    val workflow = fanInto(JoinNode("j"), Emitter("a", "A"), Emitter("b", "B"))

    // Act
    val events = runWorkflow(workflow)

    // Assert
    val joined = mapOf("a" to "A", "b" to "B")
    val joinEvent = events.runsOf("wf@1/j").single()
    assertEquals(joined, joinEvent.output)
    // The predecessors ran on sibling sub-branches, so the join re-merges onto the root branch.
    assertNull(joinEvent.branch)
    val captureEvent = events.runsOf("wf@1/capture").single()
    assertEquals(mapOf("received" to joined), captureEvent.output)
    assertNull(captureEvent.branch)
  }

  @Test
  fun run_threePredecessors_outputsAllThree() {
    // Arrange
    val workflow = fanInto(JoinNode("j"), Emitter("one", 1), Emitter("two", 2), Emitter("three", 3))

    // Act
    val events = runWorkflow(workflow)

    // Assert
    assertEquals(
      mapOf("one" to 1, "two" to 2, "three" to 3),
      events.runsOf("wf@1/j").single().output,
    )
  }

  @Test
  fun run_allPredecessorsWithoutOutput_outputsNullForEach() {
    // Arrange
    val toJoin = Route.Tag("to_join")
    val a = JoinNodeRouter("a", toJoin)
    val b = JoinNodeRouter("b", toJoin)
    val join = JoinNode("j")
    val workflow =
      Workflow(
        name = "wf",
        edges =
          listOf(
            Edge(Start, a),
            Edge(Start, b),
            Edge(a, join, listOf(toJoin)),
            Edge(b, join, listOf(toJoin)),
          ),
      )

    // Act
    val events = runWorkflow(workflow)

    // Assert
    assertEquals(mapOf("a" to null, "b" to null), events.runsOf("wf@1/j").single().output)
  }

  @Test
  fun run_routeFansOutToTwoPredecessors_joinsBothOutputs() {
    // Arrange: one route from `a` leads to both `b` and `c`, which feed the join.
    val x = Route.Tag("x")
    val a = JoinNodeRouter("a", x, output = "A")
    val b = Emitter("b", "B")
    val c = Emitter("c", "C")
    val join = JoinNode("j")
    val workflow =
      Workflow(
        name = "wf",
        edges =
          listOf(
            Edge(Start, a),
            Edge(a, b, listOf(x)),
            Edge(a, c, listOf(x)),
            Edge(b, join),
            Edge(c, join),
          ),
      )

    // Act
    val events = runWorkflow(workflow)

    // Assert
    assertEquals(listOf<Any?>("A"), events.at("wf@1/a@1").mapNotNull { it.output })
    assertEquals(mapOf("b" to "B", "c" to "C"), events.runsOf("wf@1/j").single().output)
  }

  @Test
  fun run_startIsAPredecessor_keepsWorkflowInputAlongsideOtherOutputs() {
    // Arrange
    val a = Emitter("a", "A")
    val join = JoinNode("j")
    val workflow =
      Workflow(name = "wf", edges = listOf(Edge(Start, join), Edge(Start, a), Edge(a, join)))
    val seed = Content.fromText(Role.USER, "seed")

    // Act
    val events = runBlocking {
      workflow.runAsync(testInvocationContext(userContent = seed)).toList()
    }

    // Assert
    assertEquals(
      mapOf(START_NODE_NAME to seed, "a" to "A"),
      events.runsOf("wf@1/j").single().output,
    )
  }

  @Test
  fun run_startIsAPredecessorInNestedWorkflow_staysOnThatWorkflowsBranch() {
    // Arrange: a second START edge in the outer workflow puts the inner one on a sub-branch.
    val a = Emitter("a", "A")
    val b = Emitter("b", "B")
    val join = JoinNode("j")
    val inner =
      Workflow(
        name = "inner",
        edges =
          listOf(Edge(Start, a), Edge(Start, b), Edge(Start, join), Edge(a, join), Edge(b, join)),
      )
    val outer =
      Workflow(name = "outer", edges = listOf(Edge(Start, inner), Edge(Start, StubNode("sibling"))))

    // Act
    val events = runWorkflow(outer)

    // Assert: the join stays on the inner workflow's branch rather than falling to the root.
    assertEquals("inner@1.a@1", events.at("outer@1/inner@1/a@1").single().branch)
    assertEquals("inner@1", events.runsOf("outer@1/inner@1/j").single().branch)
  }

  @Test
  fun run_secondInvocation_firesAgain() {
    // Arrange
    val workflow = fanInto(JoinNode("j"), Emitter("a", "A"), Emitter("b", "B"))
    val runner = InMemoryRunner(App(appName = "join_app", rootNode = workflow))
    val go = Content.fromText(Role.USER, "go")

    // Act
    val first = runBlocking {
      runner.runAsync(userId = "u", sessionId = "s", newMessage = go).toList()
    }
    val second = runBlocking {
      runner.runAsync(userId = "u", sessionId = "s", newMessage = go).toList()
    }

    // Assert
    val joined = mapOf("a" to "A", "b" to "B")
    assertEquals(joined, first.runsOf("wf@1/j").single().output)
    assertEquals(joined, second.runsOf("wf@1/j").single().output)
  }

  @Test
  fun inputSchema_validOutputs_checksEachOutputOnItsOwn() {
    // Arrange: had the schema applied to the joined map, its required keys would be missing.
    val a = Emitter("a", mapOf("key" to "a", "value" to 1L))
    val b = Emitter("b", Content.fromText(Role.MODEL, """{"key": "b", "value": 2}"""))
    val workflow = fanInto(JoinNode("j", inputSchema = keyValueSchema), a, b)

    // Act
    val events = runWorkflow(workflow)

    // Assert: the Content's JSON text is read into the object it describes.
    assertEquals(
      mapOf("a" to mapOf("key" to "a", "value" to 1L), "b" to mapOf("key" to "b", "value" to 2L)),
      events.runsOf("wf@1/j").single().output,
    )
  }

  @Test
  fun inputSchema_outputWithUndeclaredKey_failsNamingThePredecessor() {
    // Arrange
    val a = Emitter("a", mapOf("key" to "a", "value" to 1L))
    val b = Emitter("b", mapOf("key" to "b", "value" to 2L, "extra" to true))
    val workflow = fanInto(JoinNode("j", inputSchema = keyValueSchema), a, b)

    // Act
    val error = assertFailsWith<IllegalArgumentException> { runWorkflow(workflow) }

    // Assert: the failure names the predecessor whose output broke the schema.
    val message = error.message.orEmpty()
    assertContains(message, "validation error: output of 'b' into node 'j'")
    assertContains(message, "it has a key the schema does not declare")
  }

  @Test
  fun inputSchema_numericStringForInteger_failsWithoutConverting() {
    // Arrange
    val a = Emitter("a", mapOf("key" to "a", "value" to 1L))
    val b = Emitter("b", mapOf("key" to "b", "value" to "2"))
    val workflow = fanInto(JoinNode("j", inputSchema = keyValueSchema), a, b)

    // Act
    val error = assertFailsWith<IllegalArgumentException> { runWorkflow(workflow) }

    // Assert
    assertContains(error.message.orEmpty(), "validation error")
  }

  @Test
  fun inputSchema_outputMissingRequiredKey_fails() {
    // Arrange: b's output lacks the required `value`.
    val a = Emitter("a", mapOf("key" to "a", "value" to 1L))
    val b = Emitter("b", mapOf("key" to "b"))
    val workflow = fanInto(JoinNode("j", inputSchema = keyValueSchema), a, b)

    // Act
    val error = assertFailsWith<IllegalArgumentException> { runWorkflow(workflow) }

    // Assert
    assertContains(error.message.orEmpty(), "validation error")
  }

  @Test
  fun inputSchema_predecessorWithoutOutput_joinsAsNullUnchecked() {
    // Arrange
    val toJoin = Route.Tag("to_join")
    val a = JoinNodeRouter("a", toJoin)
    val b = Emitter("b", mapOf("key" to "b", "value" to 2L))
    val join = JoinNode("j", inputSchema = keyValueSchema)
    val workflow =
      Workflow(
        name = "wf",
        edges =
          listOf(
            Edge(Start, a),
            Edge(Start, b),
            Edge(a, join, listOf(toJoin)),
            Edge(b, join),
            Edge(join, JoinNodeCapture("capture")),
          ),
      )

    // Act
    val events = runWorkflow(workflow)

    // Assert
    assertEquals(
      mapOf("a" to null, "b" to mapOf("key" to "b", "value" to 2L)),
      events.runsOf("wf@1/j").single().output,
    )
  }

  @Test
  fun constructor_nameWithDot_isRejected() {
    // Act
    val error = assertFailsWith<IllegalArgumentException> { JoinNode("fan.in") }

    // Assert
    assertContains(error.message.orEmpty(), "must not contain")
  }
}
