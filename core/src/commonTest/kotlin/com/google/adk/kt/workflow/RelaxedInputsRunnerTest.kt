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

@file:OptIn(ExperimentalWorkflowApi::class, FrameworkInternalApi::class)

package com.google.adk.kt.workflow

import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.apps.App
import com.google.adk.kt.events.Event
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/** Runs [workflow] through [InMemoryRunner] on the user [message]: its events and final session. */
private fun runApp(workflow: Workflow, message: String = "go"): Pair<List<Event>, Session> =
  runBlocking {
    val runner = InMemoryRunner(App(appName = "relaxed", rootNode = workflow))
    val newMessage = Content.fromText(Role.USER, message)
    val events = runner.runAsync(userId = "u", sessionId = "s", newMessage = newMessage).toList()
    events to checkNotNull(runner.sessionService.getSession(SessionKey("relaxed", "u", "s")))
  }

/** The last output [workflow] produces through a runner. */
private fun lastOutput(workflow: Workflow, message: String = "go"): Any? =
  runApp(workflow, message).first.last { it.output != null }.output

private fun chain(vararg nodes: Node): Workflow =
  Workflow(name = "wf", edges = (listOf(Start) + nodes).zipWithNext { a, b -> Edge(a, b) })

/** How values are read where they enter a schema, end to end through a runner. */
class RelaxedInputsRunnerTest {

  @Test
  fun stateSchema_quotedInteger_storesTheCheckedValue() {
    // Arrange
    val emitted = mutableMapOf<String, Any>("total" to "4")
    val write =
      node<Any?, Unit>("write") { ctx, _ ->
        ctx.updateState("count", "3")
        emit(Event(author = "write", actions = EventActions(stateDelta = emitted)))
      }
    val workflow =
      Workflow(
        name = "wf",
        edges = listOf(Edge(Start, write)),
        stateSchema =
          Schema(
            type = Type.OBJECT,
            properties =
              mapOf("count" to Schema(type = Type.INTEGER), "total" to Schema(type = Type.INTEGER)),
          ),
      )

    // Act
    val session = runApp(workflow).second

    // Assert
    assertEquals(3L, session.state["count"])
    assertEquals(4L, session.state["total"])
    assertEquals(mapOf<String, Any>("total" to "4"), emitted)
  }

  @Test
  fun outputSchemas_valueThatFits_outputTheCheckedValue() {
    // Arrange
    val countSchema =
      Schema(type = Type.OBJECT, properties = mapOf("count" to Schema(type = Type.INTEGER)))
    val colorSchema = Schema(type = Type.STRING, enum = listOf("red"))
    val customNode = Emitter("emit", mapOf("count" to 4.0))
    // A root workflow emits no output event of its own, so a next node shows its checked output.
    fun workflowOutput(value: Any, schema: Schema): Workflow {
      val inner =
        Workflow(
          name = "inner",
          edges = listOf(Edge(Start, node<Any?, Any?>("n") { _, _ -> value })),
          outputSchema = schema,
        )
      return chain(inner, node<Any?, Any?>("next") { _, v -> v })
    }

    // Act
    val fromCustom = lastOutput(chain(CheckedEmitter(customNode, countSchema)))
    val fromWorkflow = lastOutput(workflowOutput(mapOf("count" to "5"), countSchema))
    val inEnum = lastOutput(workflowOutput("red", colorSchema))
    val outOfEnum =
      assertFailsWith<IllegalArgumentException> { runApp(workflowOutput("blue", colorSchema)) }

    // Assert
    assertEquals(mapOf("count" to 4L), fromCustom)
    assertEquals(mapOf("count" to 5L), fromWorkflow)
    assertEquals("red", inEnum)
    assertContains(outOfEnum.message.orEmpty(), "output of workflow 'inner' does not match")
  }
}

/** Declares [schema] as the output schema of a node that emits what [emitter] emits. */
private class CheckedEmitter(private val emitter: Emitter, override val outputSchema: Schema) :
  Node by emitter
