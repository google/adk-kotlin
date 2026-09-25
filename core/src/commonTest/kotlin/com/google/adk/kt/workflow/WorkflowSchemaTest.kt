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

import com.google.adk.kt.agents.BaseAgent
import com.google.adk.kt.agents.Context
import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.events.Event
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.sessions.State
import com.google.adk.kt.testing.testInvocationContext
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

class WorkflowSchemaTest {

  private val countSchema =
    Schema(type = Type.OBJECT, properties = mapOf("count" to Schema(type = Type.INTEGER)))

  /** A value no failure message may quote. */
  private val secret = "SENTINEL-4711"

  /** Asserts that [block] fails with a message containing [expected] and never quoting [secret]. */
  private fun assertRejects(expected: String = "validation error: ", block: () -> Any?) {
    val message = assertFailsWith<IllegalArgumentException> { block() }.message.orEmpty()
    assertContains(message, expected)
    assertFalse(secret in message)
  }

  @Test
  fun validateInput_checksTheInputOrEachFanInTrigger() {
    val node = SchemaEcho("echo", inputSchema = countSchema)
    val join = SchemaJoin("join", inputSchema = countSchema)
    val triggers = mapOf("a" to mapOf("count" to 1), "b" to null)

    assertEquals(mapOf("count" to 1), node.validateInput(mapOf("count" to 1)))
    assertEquals("raw", SchemaEcho("plain").validateInput("raw"))
    assertEquals(triggers, join.validateInput(triggers))
    assertRejects("validation error: input of node 'echo' does not match its schema: ") {
      node.validateInput(mapOf("count" to secret))
    }
    assertRejects("validation error: output of 'a' into node 'join' does not match its schema: ") {
      join.validateInput(mapOf("a" to mapOf("count" to secret)))
    }
  }

  @Test
  fun workflow_failureNamesTheNodeInputNodeOutputOrWorkflowOutput() {
    // Arrange
    val bad = mapOf("count" to secret)
    val producer = SchemaEmitter("p", bad)
    val consumer = SchemaEcho("c", inputSchema = countSchema)
    val badInput =
      Workflow(name = "wf", edges = listOf(Edge(Start, producer), Edge(producer, consumer)))
    val badOutput =
      Workflow(name = "wf", edges = listOf(Edge(Start, SchemaEmitter("n", bad, countSchema))))
    val badScalarOutput =
      Workflow(
        name = "wf",
        edges = listOf(Edge(Start, SchemaEmitter("n", secret, Schema(type = Type.INTEGER)))),
      )
    val badWorkflowOutput =
      Workflow(
        name = "wf",
        edges = listOf(Edge(Start, SchemaEmitter("n", bad))),
        outputSchema = countSchema,
      )
    val good =
      Workflow(
        name = "wf",
        edges = listOf(Edge(Start, SchemaEmitter("n", mapOf("count" to 1), countSchema))),
        outputSchema = countSchema,
      )

    // Act
    val events = runWorkflow(good)

    // Assert
    assertEquals(mapOf("count" to 1), events.last { it.output != null }.output)
    assertRejects(
      "validation error: input of node 'c' does not match its schema: Value arg: count"
    ) {
      runWorkflow(badInput)
    }
    assertRejects(
      "validation error: output of node 'n' does not match its schema: Value arg: count"
    ) {
      runWorkflow(badOutput)
    }
    assertRejects("validation error: output of node 'n' does not match its schema.") {
      runWorkflow(badScalarOutput)
    }
    assertRejects(
      "validation error: output of workflow 'wf' does not match its schema: Value arg"
    ) {
      runWorkflow(badWorkflowOutput)
    }
  }

  @Test
  fun agentNode_checksItsInputAgainstItsInputSchema() {
    // Arrange: an agent is a node like any other, so a predecessor's output must fit its schema.
    fun feeding(value: Any): Workflow {
      val producer = SchemaEmitter("producer", value)
      val agent = SchemaAgentNode("agent", inputSchema = countSchema)
      return Workflow(name = "wf", edges = listOf(Edge(Start, producer), Edge(producer, agent)))
    }

    // Act
    val events = runWorkflow(feeding(mapOf("count" to 1)))

    // Assert
    assertEquals(mapOf("count" to 1), events.first { it.output != null }.output)
    assertRejects("validation error: input of node 'agent' does not match its schema: ") {
      runWorkflow(feeding(mapOf("count" to secret)))
    }
  }

  @Test
  fun workflow_readsTheUserContentAsJsonForItsFirstNode() {
    // Arrange
    val workflow =
      Workflow(name = "wf", edges = listOf(Edge(Start, SchemaEcho("echo", countSchema))))

    // Act
    val events = runBlocking {
      workflow
        .runAsync(testInvocationContext(userContent = userMessage("""{"count": 42}""")))
        .toList()
    }

    // Assert
    assertEquals(mapOf("count" to 42L), events.last { it.output != null }.output)
  }

  @Test
  fun stateSchema_rejectsAnUndeclaredKeyOrAValueThatDoesNotFit() {
    // Arrange
    val stateSchema =
      Schema(
        type = Type.OBJECT,
        properties = mapOf("count" to Schema(type = Type.INTEGER), "box" to countSchema),
      )
    fun writing(key: String, value: Any) =
      Workflow(
        name = "wf",
        edges = listOf(Edge(Start, SchemaStateNode("n", key, value))),
        stateSchema = stateSchema,
      )

    fun List<Event>.written(key: String) =
      first { key in it.actions.stateDelta }.actions.stateDelta[key]

    // Act
    val box = runWorkflow(writing("box", mapOf("count" to 1)))
    val removed = runWorkflow(writing("count", State.REMOVED))
    val scoped = runWorkflow(writing("app:any", "raw"))

    // Assert
    assertEquals(mapOf("count" to 1), box.written("box"))
    assertEquals(State.REMOVED, removed.written("count"))
    assertEquals("raw", scoped.written("app:any"))
    assertRejects("validation error: state key 'other' is not declared") {
      runWorkflow(writing("other", 1))
    }
    assertRejects("validation error: state key 'count' does not match its schema.") {
      runWorkflow(writing("count", secret))
    }
    assertRejects("validation error: state key 'box' does not match its schema: Value arg: count") {
      runWorkflow(writing("box", mapOf("count" to secret)))
    }
  }

  @Test
  fun stateSchema_checksADeltaAnEventCarriesOrANodeMerges() {
    // Arrange
    fun delta(value: Any, merge: Boolean) =
      Workflow(
        name = "wf",
        edges = listOf(Edge(Start, SchemaDeltaNode("n", "count", value, merge))),
        stateSchema =
          Schema(type = Type.OBJECT, properties = mapOf("count" to Schema(type = Type.INTEGER))),
      )

    // Act
    val events = runWorkflow(delta(1, merge = false))

    // Assert
    assertEquals(1, events.first { "count" in it.actions.stateDelta }.actions.stateDelta["count"])
    assertRejects("validation error: state key 'count' does not match its schema.") {
      runWorkflow(delta(secret, merge = false))
    }
    assertRejects("validation error: state key 'count' does not match its schema.") {
      runWorkflow(delta(secret, merge = true))
    }
  }
}

/** A minimal agent used as a graph node, with an optional [inputSchema]. */
private class SchemaAgentNode(name: String, override val inputSchema: Schema? = null) :
  BaseAgent(name = name) {
  override fun runAsyncImpl(context: InvocationContext): Flow<Event> = emptyFlow()
}

/** Returns a fixed value as output; may declare an [outputSchema]. */
private class SchemaEmitter(
  override val name: String,
  private val value: Any?,
  override val outputSchema: Schema? = null,
) : Node {
  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow { emit(value) }
}

/** Emits its input as output; may declare an [inputSchema]. */
private class SchemaEcho(override val name: String, override val inputSchema: Schema? = null) :
  Node {
  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow { emit(nodeInput) }
}

/** A fan-in node that emits the outputs of all its predecessors; may declare an [inputSchema]. */
private class SchemaJoin(override val name: String, override val inputSchema: Schema? = null) :
  Node {
  override val requiresAllPredecessors: Boolean = true

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow { emit(nodeInput) }
}

/**
 * Puts one state key on an event it emits or, with [merge], through [Context.mergeEventActions].
 */
private class SchemaDeltaNode(
  override val name: String,
  private val key: String,
  private val value: Any,
  private val merge: Boolean,
) : Node {
  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    val actions = EventActions(stateDelta = mutableMapOf(key to value))
    if (merge) {
      context.mergeEventActions(actions)
      emit("done")
    } else {
      emit(Event(author = name, actions = actions))
    }
  }
}

/** Writes one state key, then emits a marker. */
private class SchemaStateNode(
  override val name: String,
  private val key: String,
  private val value: Any,
) : Node {
  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    context.updateState(key, value)
    emit("done")
  }
}
