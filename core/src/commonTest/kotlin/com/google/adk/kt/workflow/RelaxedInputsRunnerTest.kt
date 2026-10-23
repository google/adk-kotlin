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

import com.google.adk.kt.agents.Context
import com.google.adk.kt.agents.ResumabilityConfig
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.apps.App
import com.google.adk.kt.events.Event
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.serialization.adkJson
import com.google.adk.kt.serialization.jsonElementToAny
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.testing.userFunctionResponse
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable

@Serializable private data class RelaxedOrder(val item: String, val quantity: Int)

@Serializable
private data class RelaxedNotedOrder(val item: String, val quantity: Int, val note: String)

@Serializable private data class RelaxedQuery(val project: String, val maxResults: Int = 5)

@Serializable private data class RelaxedStock(val id: String, val quantity: Int = 1)

@Serializable private data class RelaxedStockNeed(val id: String, val quantity: Int)

@Serializable private data class RelaxedTag(val id: String)

@Serializable private data class RelaxedLine(val stock: RelaxedStock)

@Serializable private data class RelaxedNeedLine(val stock: RelaxedStockNeed)

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

/** How values are read where they enter a port or a schema, end to end through a runner. */
class RelaxedInputsRunnerTest {

  @Test
  fun userMessage_intoStringOrClassPort_readsAsTextOrJson() {
    // Arrange
    val greet = node("greet") { _, name: String -> "Hi $name" }
    val total = node("total") { _, order: RelaxedOrder -> "${order.item}:${order.quantity}" }

    // Act
    val greeting = lastOutput(chain(greet), "Ada")
    val order = lastOutput(chain(total), """{"item": "pen", "quantity": "2"}""")

    // Assert
    assertEquals("Hi Ada", greeting)
    assertEquals("pen:2", order)
  }

  @Test
  fun userMessage_intoByteArrayPort_readsAsBase64Text() {
    // Arrange
    val size = node("size") { _, b: ByteArray -> b.size }

    // Act: base64 text that also reads as a JSON number.
    val decodedSize = lastOutput(chain(size), "1234")

    // Assert
    assertEquals(3L, decodedSize)
  }

  @Test
  fun typedPort_wholeNumberOrCapitalizedTrue_takesTheIntRejectsTheBoolean() {
    // Arrange
    val produceWhole = node<Any?, Any?>("produce") { _, _ -> 42.0 }
    val double = node("double") { _, n: Int -> n * 2 }
    val produceWord = node<Any?, Any?>("produce") { _, _ -> "TRUE" }
    val negate = node("negate") { _, b: Boolean -> !b }

    // Act
    val doubled = lastOutput(chain(produceWhole, double))
    val error = assertFailsWith<NodeInputValidationException> { runApp(chain(produceWord, negate)) }

    // Assert
    assertEquals(84L, doubled)
    assertContains(error.message.orEmpty(), "input of node 'negate' does not match")
  }

  @Test
  fun explicitInputSchema_integersExtraKeysAndEnum_convertsKeepsAndEnforces() {
    // Arrange
    val countSchema =
      Schema(type = Type.OBJECT, properties = mapOf("count" to Schema(type = Type.INTEGER)))
    val colorSchema = Schema(type = Type.STRING, enum = listOf("red"))
    fun passThrough(schema: Schema) = node<Any?, Any?>("check", inputSchema = schema) { _, v -> v }
    fun producing(value: Any) = node<Any?, Any?>("produce") { _, _ -> value }

    // Act
    val quoted = lastOutput(chain(producing(mapOf("count" to "3")), passThrough(countSchema)))
    val whole = lastOutput(chain(producing(mapOf("count" to 30.0)), passThrough(countSchema)))
    val extra =
      lastOutput(chain(producing(mapOf("count" to 1, "unit" to "kg")), passThrough(countSchema)))
    val error =
      assertFailsWith<NodeInputValidationException> {
        runApp(chain(producing("blue"), passThrough(colorSchema)))
      }

    // Assert
    assertEquals(mapOf("count" to 3L), quoted)
    assertEquals(mapOf("count" to 30L), whole)
    assertEquals(mapOf("count" to 1L, "unit" to "kg"), extra)
    assertContains(error.message.orEmpty(), "enum")
  }

  @Test
  fun explicitInputSchema_keyOnlyTheTypeDefaults_fails() {
    // Arrange
    val requiresMax =
      Schema(
        type = Type.OBJECT,
        properties =
          mapOf(
            "project" to Schema(type = Type.STRING),
            "maxResults" to Schema(type = Type.INTEGER),
          ),
        required = listOf("project", "maxResults"),
      )
    val search = node("search", inputSchema = requiresMax) { _, q: RelaxedQuery -> q.maxResults }

    // Act
    val error =
      assertFailsWith<NodeInputValidationException> {
        runApp(chain(search), """{"project": "ADK"}""")
      }

    // Assert
    assertContains(error.message.orEmpty(), "lacks required 'maxResults'")
  }

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
  fun edgeCheck_valueTheConsumerCannotTake_failsAtBuild() {
    // Arrange
    val maybe = node<Any?, Int?>("maybe") { _, _ -> 1 }
    val needsInt = node("needs_int") { _, n: Int -> n }
    val noted = node<Any?, RelaxedNotedOrder>("noted") { _, _ -> RelaxedNotedOrder("pen", 1, "n") }
    val plain = node("plain") { _, order: RelaxedOrder -> order.item }
    val message = node("message") { _, content: Content -> content.text() }

    // Act
    val nullable = assertFailsWith<GraphValidationException> { runApp(chain(maybe, needsInt)) }
    val superset = assertFailsWith<GraphValidationException> { runApp(chain(noted, plain)) }
    val intoContent =
      assertFailsWith<GraphValidationException> {
        runApp(chain(node<Any?, RelaxedOrder>("order") { _, _ -> RelaxedOrder("pen", 1) }, message))
      }

    // Assert
    assertContains(nullable.message.orEmpty(), "maybe -> needs_int")
    assertContains(superset.message.orEmpty(), "noted -> plain")
    assertContains(intoContent.message.orEmpty(), "order -> message")
  }

  @Test
  fun edgeCheck_intIntoNullableInt_buildsAndRuns() {
    // Arrange
    val widened = node("widened") { _, n: Int? -> n }

    // Act
    val output = lastOutput(chain(node<Any?, Int>("one") { _, _ -> 1 }, widened))

    // Assert
    assertEquals(1L, output)
  }

  @Test
  fun edgeCheck_fieldWithADefault_requiredOnOutputOptionalOnInput() {
    // Arrange
    val stock = node<Any?, RelaxedStock>("stock") { _, _ -> RelaxedStock("pen") }
    val tag = node<Any?, RelaxedTag>("tag") { _, _ -> RelaxedTag("pen") }
    val need = node("need") { _, n: RelaxedStockNeed -> "${n.id}:${n.quantity}" }
    val restock = node("restock") { _, s: RelaxedStock -> "${s.id}:${s.quantity}" }
    val lines =
      node<Any?, List<RelaxedLine>>("lines") { _, _ -> listOf(RelaxedLine(RelaxedStock("pen"))) }
    val needLines = node("need_lines") { _, l: List<RelaxedNeedLine> -> l.single().stock.quantity }

    // Act
    val writtenDefault = lastOutput(chain(stock, need))
    val nestedDefault = lastOutput(chain(lines, needLines))
    val filledDefault = lastOutput(chain(tag, restock))
    val missing = assertFailsWith<GraphValidationException> { runApp(chain(tag, need)) }

    // Assert
    assertEquals("pen:1", writtenDefault)
    assertEquals(1L, nestedDefault)
    assertEquals("pen:1", filledDefault)
    assertContains(missing.message.orEmpty(), "tag -> need")
  }

  @Test
  fun edgeCheck_byteArrayOutput_fitsAStringAndNotAList() {
    // Arrange
    val bytes = node<Any?, ByteArray>("bytes") { _, _ -> byteArrayOf(1, 2) }
    val text = node("text") { _, encoded: String -> encoded }
    val numbers = node("numbers") { _, n: List<Int> -> n.size }

    // Act
    val encoded = lastOutput(chain(bytes, text))
    val error = assertFailsWith<GraphValidationException> { runApp(chain(bytes, numbers)) }

    // Assert
    assertEquals("AQI=", encoded)
    assertContains(error.message.orEmpty(), "bytes -> numbers")
  }

  @Test
  fun contentPort_objectFromAnUncheckedProducer_fails() {
    // Arrange: a custom node declares no output schema, so the decode guards the port.
    val order = Emitter("order", mapOf("item" to "pen", "quantity" to 1))
    val message = node("message") { _, content: Content -> content.text() }

    // Act
    val error = assertFailsWith<NodeInputValidationException> { runApp(chain(order, message)) }

    // Assert
    assertContains(error.message.orEmpty(), "input of node 'message' does not match")
  }

  @Test
  fun adkTypePort_objectOfAnotherShape_failsAndASavedOneReadsBack() {
    // Arrange: a custom node declares no output schema, so the decode guards the port.
    val part = Part(text = "hi")
    val savedPart = jsonElementToAny(adkJson.encodeToJsonElement(Part.serializer(), part))
    val readPart = node("read_part") { _, p: Part -> p.text }

    // Act
    val readBack = lastOutput(chain(Emitter("saved", savedPart), readPart))
    val error =
      assertFailsWith<NodeInputValidationException> {
        runApp(chain(Emitter("order", mapOf("id" to "a1", "total" to 3)), readPart))
      }

    // Assert
    assertEquals("hi", readBack)
    assertContains(error.message.orEmpty(), "input of node 'read_part' does not match")
  }

  @Test
  fun closedSchema_undeclaredKey_fails() {
    // Arrange
    val closed =
      Schema(
        type = Type.OBJECT,
        properties = mapOf("count" to Schema(type = Type.INTEGER)),
        additionalProperties = false,
      )
    val produce = node<Any?, Any?>("produce") { _, _ -> mapOf("count" to 1, "unit" to "kg") }
    val check = node<Any?, Any?>("check", inputSchema = closed) { _, v -> v }

    // Act
    val error = assertFailsWith<NodeInputValidationException> { runApp(chain(produce, check)) }

    // Assert
    assertContains(error.message.orEmpty(), "has a key the schema does not declare")
  }

  @Test
  fun userMessage_literalThatIsNotStrictJson_failsATypedPort() {
    // Arrange
    val negate = node("negate") { _, b: Boolean -> !b }
    val double = node("double") { _, n: Int -> n * 2 }

    // Act
    val accepted = lastOutput(chain(negate), "true")
    val failures =
      listOf(
          "TRUE" to negate,
          "NaN" to double,
          "Infinity" to double,
          "3_000" to double,
          "007" to double,
          "+3" to double,
          "3d" to double,
          "0x1p3" to double,
        )
        .map { (message, port) ->
          assertFailsWith<NodeInputValidationException> { runApp(chain(port), message) }
        }

    // Assert
    assertEquals(false, accepted)
    for (error in failures) assertContains(error.message.orEmpty(), "input of node '")
  }

  @Test
  fun outputSchemas_valueThatFits_outputTheCheckedValue() {
    // Arrange
    val countSchema =
      Schema(type = Type.OBJECT, properties = mapOf("count" to Schema(type = Type.INTEGER)))
    val colorSchema = Schema(type = Type.STRING, enum = listOf("red"))
    val functionNode =
      node<Any?, Any?>("produce", outputSchema = countSchema) { _, _ ->
        mapOf("count" to "3", "unit" to "kg")
      }
    val customNode = Emitter("emit", mapOf("count" to 4.0))
    // A next node shows the checked output a successor receives.
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
    val fromFunction = lastOutput(chain(functionNode))
    val fromCustom = lastOutput(chain(CheckedEmitter(customNode, countSchema)))
    val fromWorkflow = lastOutput(workflowOutput(mapOf("count" to "5"), countSchema))
    val inEnum = lastOutput(workflowOutput("red", colorSchema))
    val outOfEnum =
      assertFailsWith<IllegalArgumentException> { runApp(workflowOutput("blue", colorSchema)) }

    // Assert
    assertEquals(mapOf("count" to 3L, "unit" to "kg"), fromFunction)
    assertEquals(mapOf("count" to 4L), fromCustom)
    assertEquals(mapOf("count" to 5L), fromWorkflow)
    assertEquals("red", inEnum)
    assertContains(outOfEnum.message.orEmpty(), "output of workflow 'inner' does not match")
  }

  /** A workflow's checked output reaches a node that delegated to it the same, live and resumed. */
  @Test
  fun delegatedWorkflowOutput_afterAResume_isTheCheckedValueAsLive() {
    // Arrange: d delegates its output to a workflow whose schema reads "5" as 5; a sibling of d
    // finishes at once (live) or asks for input (resumed).
    val countSchema =
      Schema(type = Type.OBJECT, properties = mapOf("count" to Schema(type = Type.INTEGER)))
    fun graph(sibling: Node): Workflow {
      val inner =
        Workflow(
          name = "inner",
          edges = listOf(Edge(Start, Emitter("n", mapOf("count" to "5")))),
          outputSchema = countSchema,
        )
      val d = Delegator("d", inner)
      val join = JoinNode("j")
      return Workflow(
        name = "wf",
        edges = listOf(Edge(Start, d), Edge(Start, sibling), Edge(d, join), Edge(sibling, join)),
      )
    }
    val session = ResumableSession(graph(Asker("ask")))
    val first = session.turn(Content.fromText(Role.USER, "go"))

    // Act
    val live = runApp(graph(Emitter("ask", mapOf("ok" to true)))).first
    val resumed =
      session.turn(
        userFunctionResponse(RequestInput.FUNCTION_CALL_NAME, "ask_1", mapOf("ok" to true))
      )

    // Assert
    fun fromD(events: List<Event>) =
      (events.single { it.nodeInfo?.path == "wf@1/j@1" && it.output != null }.output as Map<*, *>)[
        "d"]
    assertEquals(mapOf("count" to 5L), fromD(live))
    assertEquals(mapOf("count" to 5L), fromD(resumed))
    // The workflow sends the checked value before it marks its own end.
    val innerOutput = first.indexOfFirst {
      it.output != null && it.nodeInfo?.path.orEmpty().endsWith("/inner@c")
    }
    val innerEnd = first.indexOfFirst { it.author == "inner" && it.actions.endOfAgent == true }
    assertTrue(innerOutput in 0 until innerEnd)
  }

  /** A root workflow's schema changes its output without an output event of the workflow's own. */
  @Test
  fun workflowOutputSchema_rootWorkflow_sendsNoOutputEventOfItsOwn() {
    // Arrange
    val countSchema =
      Schema(type = Type.OBJECT, properties = mapOf("count" to Schema(type = Type.INTEGER)))
    val workflow =
      Workflow(
        name = "wf",
        edges = listOf(Edge(Start, Emitter("n", mapOf("count" to "5")))),
        outputSchema = countSchema,
      )

    // Act
    val events = runApp(workflow).first

    // Assert
    assertEquals(listOf("wf@1/n@1"), events.filter { it.output != null }.map { it.nodeInfo?.path })
  }
}

/** Delegates its output to [child] with `useAsOutput`. */
private class Delegator(override val name: String, private val child: Node) : Node {
  override val rerunOnResume: Boolean = true

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    val unused = context.runNode(child, runId = "c", useAsOutput = true)
  }
}

/** Asks for input under the interrupt id `ask_1`. */
private class Asker(override val name: String) : Node {
  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    emit(RequestInput(interruptId = "ask_1", message = "?"))
  }
}

/** A runner over a resumable app rooted on [root], driven a turn at a time. */
private class ResumableSession(root: Node) {
  private val runner =
    InMemoryRunner(
      App(
        appName = "relaxed",
        rootNode = root,
        resumabilityConfig = ResumabilityConfig(isResumable = true),
      )
    )

  fun turn(message: Content): List<Event> = runBlocking {
    runner.runAsync(userId = "u", sessionId = "s", newMessage = message).toList()
  }
}

/** Declares [outputSchema] on a node that emits what [emitter] emits. */
private class CheckedEmitter(private val emitter: Emitter, override val outputSchema: Schema) :
  Node by emitter
