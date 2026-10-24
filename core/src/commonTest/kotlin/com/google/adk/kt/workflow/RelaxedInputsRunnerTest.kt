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
import com.google.adk.kt.agents.LlmAgent
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
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.userFunctionResponse
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import kotlin.jvm.JvmInline
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
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

@Serializable
private data class RelaxedOptionalNoteOrder(
  val item: String,
  val quantity: Int,
  val note: String? = null,
)

@Serializable private data class RelaxedDecimalOrder(val item: String, val quantity: Double)

@Serializable
private enum class RelaxedPriority {
  LOW,
  HIGH,
}

@Serializable
private sealed interface RelaxedShape {
  @Serializable @SerialName("Circle") data class Circle(val r: Double) : RelaxedShape

  @Serializable @SerialName("Square") data class Square(val a: Double) : RelaxedShape
}

@Serializable private data class RelaxedShapeBox(val shape: RelaxedShape)

@Serializable @JvmInline private value class RelaxedUserId(val raw: String)

@Serializable private data class RelaxedTree(val label: String, val children: List<RelaxedTree>)

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
    val widened = node("widened") { _, n: Int? -> n }

    // Act
    val nullable = assertFailsWith<GraphValidationException> { runApp(chain(maybe, needsInt)) }
    val superset = assertFailsWith<GraphValidationException> { runApp(chain(noted, plain)) }
    val intoContent =
      assertFailsWith<GraphValidationException> {
        runApp(chain(node<Any?, RelaxedOrder>("order") { _, _ -> RelaxedOrder("pen", 1) }, message))
      }
    val widenedOutput = lastOutput(chain(node<Any?, Int>("one") { _, _ -> 1 }, widened))

    // Assert
    assertContains(nullable.message.orEmpty(), "maybe -> needs_int")
    assertContains(superset.message.orEmpty(), "noted -> plain")
    assertContains(intoContent.message.orEmpty(), "order -> message")
    assertEquals(1L, widenedOutput)
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
    val unused = session.turn(Content.fromText(Role.USER, "go"))

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
  }

  @Test
  fun edgeCheck_wideningEdge_buildsAndDeliversTheValue() {
    // Arrange
    val order = node<Any?, RelaxedOrder>("order") { _, _ -> RelaxedOrder("pen", 2) }
    val cases =
      listOf<Pair<Workflow, Any?>>(
        // a class fits its nullable form.
        chain(order, node("maybe_order") { _, o: RelaxedOrder? -> o?.item }) to "pen",
        // a Long, even one beyond Int's range, fits a Double.
        chain(
          node<Any?, Long>("long") { _, _ -> 3_000_000_000L },
          node("fraction") { _, d: Double -> d / 4_000_000_000 },
        ) to 0.75,
        // an enum fits a String.
        chain(
          node<Any?, RelaxedPriority>("priority") { _, _ -> RelaxedPriority.HIGH },
          node("label") { _, s: String -> s },
        ) to "HIGH",
        // a class field widens from Int to Double.
        chain(order, node("quarter") { _, o: RelaxedDecimalOrder -> o.quantity / 4 }) to 0.5,
        // a Map<String, Any?> takes every field.
        chain(order, node("fields") { _, m: Map<String, Any?> -> m }) to
          mapOf("item" to "pen", "quantity" to 2L),
        // a sealed type's concrete subclass is an ordinary class.
        chain(
          node<Any?, RelaxedShape.Circle>("circle") { _, _ -> RelaxedShape.Circle(1.5) },
          node("radius") { _, c: RelaxedShape.Circle -> c.r },
        ) to 1.5,
        // a value class and the String it wraps fit each other both ways.
        chain(
          node<Any?, RelaxedUserId>("user") { _, _ -> RelaxedUserId("u1") },
          node("raw") { _, s: String -> s },
        ) to "u1",
        chain(
          node<Any?, String>("name") { _, _ -> "u2" },
          node("user_id") { _, id: RelaxedUserId -> id.raw },
        ) to "u2",
      )

    // Act
    val outputs = cases.map { (workflow, _) -> lastOutput(workflow) }

    // Assert
    assertEquals(cases.map { it.second }, outputs)
  }

  @Test
  fun edgeCheck_narrowingOrLossyEdge_failsAtBuild() {
    // Arrange
    val order = node<Any?, RelaxedOrder>("order") { _, _ -> RelaxedOrder("pen", 2) }
    val orderReply =
      Schema(
        type = Type.OBJECT,
        properties =
          mapOf("item" to Schema(type = Type.STRING), "quantity" to Schema(type = Type.INTEGER)),
        required = listOf("item", "quantity"),
      )
    val edges =
      listOf<Pair<Node, Node>>(
        // an Int is not a String.
        node<Any?, Int>("count") { _, _ -> 1 } to node("text") { _, s: String -> s },
        // list elements narrow from Double to Int.
        node<Any?, List<Double>>("doubles") { _, _ -> listOf(1.5) } to
          node("ints") { _, l: List<Int> -> l.sum() },
        // a class field narrows from String? to String.
        node<Any?, RelaxedOptionalNoteOrder>("maybe_noted") { _, _ ->
          RelaxedOptionalNoteOrder("pen", 1)
        } to node("noted") { _, o: RelaxedNotedOrder -> o.note },
        // an Event is closed and lacks the order's fields.
        order to node("read_event") { _, e: Event -> e.author },
        // a Content lacks the fields an agent's output schema declares.
        LlmAgent(name = "agent", model = DummyModel("model"), outputSchema = orderReply) to
          node("read_reply") { _, c: Content -> c.text() },
      )

    // Act
    val errors = edges.map { (from, to) ->
      assertFailsWith<GraphValidationException> { runApp(chain(from, to)) }
    }

    // Assert
    for ((error, edge) in errors.zip(edges)) {
      assertContains(error.message.orEmpty(), "${edge.first.name} -> ${edge.second.name}")
    }
  }

  @Test
  fun handWrittenInputSchema_openOrClosed_keepsAnUnlistedFieldOrFailsAtBuild() {
    // Arrange
    val stock = node<Any?, RelaxedStock>("stock") { _, _ -> RelaxedStock("pen") }
    val idOnly =
      Schema(
        type = Type.OBJECT,
        properties = mapOf("id" to Schema(type = Type.STRING)),
        required = listOf("id"),
      )
    fun check(schema: Schema) = node<Any?, Any?>("check", inputSchema = schema) { _, v -> v }

    // Act
    val open = lastOutput(chain(stock, check(idOnly)))
    val closed =
      assertFailsWith<GraphValidationException> {
        runApp(chain(stock, check(idOnly.copy(additionalProperties = false))))
      }

    // Assert: an open schema passes the extra field through; a closed one fails at build.
    assertEquals(mapOf("id" to "pen", "quantity" to 1L), open)
    assertContains(closed.message.orEmpty(), "stock -> check")
  }

  @Test
  fun typedPort_valueOutsideItsJsonForm_failsAtRun() {
    // Arrange: an Any? producer declares no output schema, so only the port's decode checks it.
    fun producing(value: Any) = node<Any?, Any?>("produce") { _, _ -> value }
    val cases =
      listOf<Pair<Node, Node>>(
        // "on" is not a JSON boolean literal.
        producing("on") to node("negate") { _, b: Boolean -> !b },
        // a plain String is data, so it is not parsed as JSON.
        producing("""{"item": "pen", "quantity": 2}""") to
          node("quantity") { _, o: RelaxedOrder -> o.quantity },
        // a ByteArray port takes base64 text, not a list of numbers.
        producing(listOf(1, 2)) to node("size") { _, b: ByteArray -> b.size },
      )

    // Act
    val errors = cases.map { (producer, port) ->
      assertFailsWith<NodeInputValidationException> { runApp(chain(producer, port)) }
    }

    // Assert
    for ((error, case) in errors.zip(cases)) {
      assertContains(error.message.orEmpty(), "input of node '${case.second.name}' does not match")
    }
  }

  @Test
  fun sealedField_handWrittenMap_readsByItsDiscriminatorOrFails() {
    // Arrange
    val received = mutableListOf<RelaxedShapeBox>()
    val readBox = node<RelaxedShapeBox, Unit>("read_box") { _, box -> received += box }
    fun producing(shape: Map<String, Any>) =
      node<Any?, Any?>("produce") { _, _ -> mapOf("shape" to shape) }

    // Act
    val unused = runApp(chain(producing(mapOf("type" to "Circle", "r" to 1.0)), readBox))
    val missingType =
      assertFailsWith<NodeInputValidationException> {
        runApp(chain(producing(mapOf("r" to 1.0)), readBox))
      }

    // Assert: a map with a discriminator selects the subclass; one without it fails.
    assertEquals(listOf(RelaxedShapeBox(RelaxedShape.Circle(1.0))), received)
    assertContains(missingType.message.orEmpty(), "input of node 'read_box' does not match")
  }

  @Test
  fun uncheckedSource_eventOutputOrRecursiveClass_isDecodedAtRun() {
    // Arrange: neither producer has an output schema, so the edge builds and the port decides.
    val event =
      node<Any?, Event>("event") { _, _ -> Event(output = mapOf("item" to "pen", "quantity" to 2)) }
    val quantity = node("quantity") { _, o: RelaxedOrder -> o.quantity }
    val tree =
      node<Any?, RelaxedTree>("tree") { _, _ ->
        RelaxedTree("root", listOf(RelaxedTree("leaf", emptyList())))
      }
    val leaves = node("leaves") { _, t: RelaxedTree -> t.children.map { it.label } }
    val treeJson = """{"label": "root", "children": [{"label": "leaf", "children": []}]}"""

    // Act
    val fromEvent = lastOutput(chain(event, quantity))
    val fromTree = lastOutput(chain(tree, leaves))
    val fromMessage = lastOutput(chain(leaves), treeJson)

    // Assert
    assertNull(event.outputSchema)
    assertNull(tree.outputSchema)
    assertNull(leaves.inputSchema)
    assertEquals(2L, fromEvent)
    assertEquals(listOf("leaf"), fromTree)
    assertEquals(listOf("leaf"), fromMessage)
  }

  @Test
  fun mapPort_stringKeysIntoIntKeys_failsAtRunOnANonNumericKey() {
    // Arrange: a map schema does not describe its keys, so the edge builds either way.
    fun names(vararg keys: String) =
      node<Any?, Map<String, String>>("names") { _, _ -> keys.associateWith { "a" } }
    val byNumber = node("by_number") { _, m: Map<Int, String> -> m.keys.sum() }

    // Act
    val numeric = lastOutput(chain(names("1", "2"), byNumber))
    val error =
      assertFailsWith<NodeInputValidationException> { runApp(chain(names("x"), byNumber)) }

    // Assert
    assertEquals(3L, numeric)
    assertContains(error.message.orEmpty(), "key of input of node 'by_number' does not match")
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
