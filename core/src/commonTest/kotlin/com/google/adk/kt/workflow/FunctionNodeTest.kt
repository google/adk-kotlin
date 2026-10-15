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
// typeOf<T>() is a compiler intrinsic and does not require kotlin-reflect.
@file:Suppress("KotlinReflectNeeded")

package com.google.adk.kt.workflow

import com.google.adk.kt.SchemaUtils
import com.google.adk.kt.agents.Context
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.apps.App
import com.google.adk.kt.events.Event
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.serialization.adkJson
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.testing.testInvocationContext
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.jvm.JvmInline
import kotlin.reflect.typeOf
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private val startMessage = Content.fromText(Role.USER, "start")

/** Creates a runner for an app rooted at [workflow], with an empty session. */
private fun runnerFor(workflow: Workflow): InMemoryRunner = runBlocking {
  InMemoryRunner(App(appName = workflow.name, rootNode = workflow)).also {
    val unused = it.sessionService.createSession(SessionKey(it.appName, "u", "s"))
  }
}

/** Runs one invocation on [message] and returns the events it streams. */
private fun InMemoryRunner.runEvents(message: Content = startMessage): List<Event> = runBlocking {
  runAsync(userId = "u", sessionId = "s", newMessage = message).toList()
}

/** Returns the session as the session service stores it. */
private fun InMemoryRunner.session(): Session = runBlocking {
  checkNotNull(sessionService.getSession(SessionKey(appName, "u", "s")))
}

/** Runs [workflow] through [InMemoryRunner] on [message] and returns the events it streams. */
private fun runWorkflowThroughRunner(
  workflow: Workflow,
  message: Content = startMessage,
): List<Event> = runnerFor(workflow).runEvents(message)

private data class UserProfileDomain(val userId: String, val tier: String)

@Serializable private data class UserProfile(val id: String, val age: Int)

@Serializable private data class AccountProfile(val userId: String, val tier: String)

@Serializable private data class TicketQuery(val project: String, val maxResults: Int = 5)

@Serializable
private data class OrderRequest(val item: String, val quantity: Int, val unitPrice: Double)

@Serializable @JvmInline private value class PortUserId(val id: String)

@Serializable private data class PortOrder(val id: String, val note: String?, val quantity: Int = 1)

@Serializable private data class PortProfile(val userId: String, val tier: String = "free")

@Serializable private data class PortReading(val type: String, val value: Double)

@Serializable
private enum class PortStatus {
  @SerialName("shipped") SHIPPED
}

@Serializable private data class PortBox<T>(val item: T)

/** Outputs an order through `Context.setOutput`. */
private class PortOrderWriter(override val name: String) : Node {
  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    context.setOutput(PortOrder(id = "o1", note = null))
  }
}

/** Outputs a class without a serializer through `Context.setOutput`. */
private class PortPlainWriter(override val name: String) : Node {
  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    context.setOutput(UserProfileDomain("u1", "gold"))
  }
}

@Serializable
private sealed interface PortShape {
  @Serializable data class Circle(val r: Double) : PortShape
}

class FunctionNodeTest {

  private val secret = "SENTINEL-SECRET-9912"

  private fun nodeContext(node: Node): Context =
    Context(
      invocationContext =
        testInvocationContext(session = Session(key = SessionKey("app", "u", "s"))),
      node = node,
      eventSink = EventSink { _, _ -> },
    )

  /** Runs [node] alone on [input], as the engine does, and returns the outputs it emits. */
  private fun runOutputs(node: BaseNode, input: Any?): List<Any?> = runBlocking {
    node.run(nodeContext(node), input).toList().map { it.output }
  }

  // -- node(...) core behavior --

  @Test
  fun node_typedInputToTypedOutput_infersTypesAndSchemasAndRuns() {
    // Arrange
    val produceText = node<Any?, String>("produce_text") { _, _ -> "hello brave world" }
    val wordCount = node("word_count") { _, text: String -> text.trim().split(Regex("\\s+")).size }
    val workflow =
      Workflow(name = "wf", edges = listOf(Edge(Start, produceText), Edge(produceText, wordCount)))

    // Act
    val events = runWorkflowThroughRunner(workflow)

    // Assert
    assertEquals(typeOf<String>(), wordCount.inputType)
    assertEquals(typeOf<Int>(), wordCount.outputType)
    assertEquals(Schema(type = Type.STRING), wordCount.inputSchema)
    assertEquals(Schema(type = Type.INTEGER), wordCount.outputSchema)
    assertEquals(3L, events.last { it.output != null }.output)
  }

  @Test
  fun node_emittingRouteEvent_selectsDynamicRouteWithoutEmittingOutput() {
    // Arrange
    val produceScore = node<Any?, Int>("score") { _, _ -> 85 }
    val routeByScore =
      node("route_by_score") { _, score: Int ->
        val route = Route.Tag(if (score >= 80) "high" else "low")
        emit(Event(actions = EventActions(route = listOf(route))))
      }
    val highNode = node<Any?, String>("high_node") { _, _ -> "passed" }
    val lowNode = node<Any?, String>("low_node") { _, _ -> "failed" }
    val workflow =
      Workflow(
        name = "wf",
        edges =
          listOf(
            Edge(Start, produceScore),
            Edge(produceScore, routeByScore),
            Edge(routeByScore, highNode, Route.Tag("high")),
            Edge(routeByScore, lowNode, Route.Tag("low")),
          ),
      )

    // Act
    val events = runWorkflowThroughRunner(workflow)

    // Assert
    val routerEvent = events.single { it.nodeInfo?.path == "wf@1/route_by_score@1" }
    assertNull(routerEvent.output)
    assertEquals(listOf(Route.Tag("high")), routerEvent.actions.route)
    assertEquals("passed", events.last { it.output != null }.output)
  }

  @Test
  fun node_emitsProgressEventsAndReturnsOutput_encodesTheOutputAndStampsAuthor() {
    // Arrange
    val streamChunks =
      node("stream_chunks") { context, text: String ->
        val items = text.split(" ")
        for (item in items) {
          emit(Event(content = Content.fromText(Role.MODEL, "Processing $item")))
        }
        context.updateState("processed_count", items.size)
        items.size
      }
    val produceText = node<Any?, String>("produce_text") { _, _ -> "a b" }
    val workflow =
      Workflow(
        name = "wf",
        edges = listOf(Edge(Start, produceText), Edge(produceText, streamChunks)),
      )

    // Act
    val events = runWorkflowThroughRunner(workflow)

    // Assert
    assertEquals(typeOf<Int>(), streamChunks.outputType)
    assertEquals(Schema(type = Type.INTEGER), streamChunks.outputSchema)
    val progressEvents = events.filter { it.content != null }
    assertEquals(listOf("Processing a", "Processing b"), progressEvents.map { it.content?.text() })
    assertEquals(listOf("wf", "wf"), progressEvents.map { it.author })
    val outputEvent = events.last { it.output != null }
    assertEquals(2L, outputEvent.output)
    assertEquals(2, outputEvent.actions.stateDelta["processed_count"])
  }

  // -- Runtime input validation --

  @Test
  fun node_wrongOrNullInput_throwsValueFreeNodeInputValidationException() {
    // Arrange
    val orderConsumer = node("order_consumer") { _, order: PortOrder -> order.id }
    val listConsumer = node("list_consumer") { _, xs: List<Int> -> xs.sum() }
    val countConsumer = node("count_consumer") { _, n: Int -> n }
    val nullableConsumer = node("nullable_consumer") { _, s: String? -> s ?: "fallback" }

    // Act
    val orderError =
      assertFailsWith<NodeInputValidationException> { runOutputs(orderConsumer, secret) }
    val listError =
      assertFailsWith<NodeInputValidationException> { runOutputs(listConsumer, listOf(1, secret)) }
    val wordError =
      assertFailsWith<NodeInputValidationException> { runOutputs(countConsumer, "forty-two") }
    val nullError =
      assertFailsWith<NodeInputValidationException> { runOutputs(countConsumer, null) }
    val nullableOutputs = runOutputs(nullableConsumer, null)

    // Assert
    assertFalse(secret in orderError.message.orEmpty())
    assertFalse(secret in listError.message.orEmpty())
    assertContains(listError.message.orEmpty(), "input of node 'list_consumer'[1]")
    assertContains(wordError.message.orEmpty(), "input of node 'count_consumer' does not match")
    assertContains(
      nullError.message.orEmpty(),
      "input of node 'count_consumer' does not accept null",
    )
    assertEquals(listOf("fallback"), nullableOutputs)
  }

  @Test
  fun workflow_invalidInputAtRuntime_persistsErrorEventAndThrows() {
    // Arrange
    val dynamicProducer = node<Any?, Any?>("producer") { _, _ -> secret }
    val consumeInt = node("consume_int") { _, x: Int -> x * 2 }
    val workflow =
      Workflow(
        name = "wf",
        edges = listOf(Edge(Start, dynamicProducer), Edge(dynamicProducer, consumeInt)),
      )
    val runner = runnerFor(workflow)

    // Act
    val error = assertFailsWith<NodeInputValidationException> { runner.runEvents() }

    // Assert
    assertFalse(secret in error.message.orEmpty())
    val errorEvent = runner.session().events.single { it.errorCode != null }
    assertEquals("NodeInputValidationError", errorEvent.errorCode)
    assertEquals("wf@1/consume_int@1", errorEvent.nodeInfo?.path)
    assertFalse(secret in errorEvent.errorMessage.orEmpty())
  }

  // -- Return / emission handling --

  @Test
  fun node_unitAndNullReturn_emitsNoOutputEventUnlessStateUpdated() {
    // Arrange
    val silentUnit = node<Any?, Unit>("silent_unit") { _, _ -> }
    val silentNull = node<Any?, String?>("silent_null") { _, _ -> null }
    val stateUnit = node<Any?, Unit>("state_unit") { ctx, _ -> ctx.updateState("touched", 1) }

    // Act
    val unitEvents = runWorkflowThroughRunner(Workflow("wf1", listOf(Edge(Start, silentUnit))))
    val nullEvents = runWorkflowThroughRunner(Workflow("wf2", listOf(Edge(Start, silentNull))))
    val stateEvents = runWorkflowThroughRunner(Workflow("wf3", listOf(Edge(Start, stateUnit))))

    // Assert
    assertEquals(emptyList(), unitEvents)
    assertEquals(emptyList(), nullEvents)
    val single = stateEvents.single()
    assertNull(single.output)
    assertEquals(1, single.actions.stateDelta["touched"])
  }

  @Test
  fun node_emitsContent_sendsAMessageBeforeTheOutput() {
    // Arrange
    val count =
      node<Any?, Int>("count") { _, _ ->
        emit(Content.fromText(Role.MODEL, "counting"))
        3
      }

    // Act
    val events = runWorkflowThroughRunner(Workflow("wf", listOf(Edge(Start, count))))

    // Assert
    assertEquals(listOf("counting", null), events.map { it.content?.text() })
    assertEquals(listOf(null, 3L), events.map { it.output })
  }

  /** Makes a returned [Content] the node's output, which a successor typed `Content` receives. */
  @Test
  fun node_contentReturn_isTheNodeOutput() {
    // Arrange
    val answer = Content.fromText(Role.MODEL, "cached answer")
    val produceContent = node<Any?, Content>("produce_content") { _, _ -> answer }
    val afterReply = node("after_reply") { _, reply: Content -> reply.text() }
    val workflow =
      Workflow(
        name = "wf",
        edges = listOf(Edge(Start, produceContent), Edge(produceContent, afterReply)),
      )
    val runner = runnerFor(workflow)

    // Act
    val events = runner.runEvents()
    val outputEvent =
      runner.session().events.single { it.nodeInfo?.path == "wf@1/produce_content@1" }

    // Assert
    assertEquals(answer, outputEvent.output)
    assertNull(outputEvent.content)
    assertEquals("cached answer", events.last { it.output != null }.output)
  }

  /** Outputs the list of the items' returned [Content]s. */
  @Test
  fun parallelNode_contentItems_outputsTheList() {
    // Arrange
    val names = node<Any?, List<String>>("names") { _, _ -> listOf("a", "b") }
    val greet =
      parallelNode("greet") { _, name: String -> Content.fromText(Role.MODEL, "hi $name") }
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, names), Edge(names, greet)))

    // Act
    val events = runWorkflowThroughRunner(workflow)

    // Assert
    assertEquals(
      listOf(Content.fromText(Role.MODEL, "hi a"), Content.fromText(Role.MODEL, "hi b")),
      events.single { it.nodeInfo?.path == "wf@1/greet@1" && it.output != null }.output,
    )
  }

  @Test
  fun node_explicitSchemas_areEnforcedWhileInferredOnesOnlyDescribe() {
    // Arrange
    val countSchema =
      Schema(
        type = Type.OBJECT,
        properties = mapOf("count" to Schema(type = Type.INTEGER)),
        required = listOf("count"),
      )
    val explicitInput =
      node("explicit_input", inputSchema = countSchema) { _, args: Map<String, Any?> -> args.size }
    val inferredInput = node("inferred_input") { _, profile: UserProfile -> profile.id }
    val explicitProfile =
      node("explicit_profile", inputSchema = SchemaUtils.inferSchema(typeOf<UserProfile>())) {
        _,
        profile: UserProfile ->
        profile.id
      }
    val matchingOutput =
      node<Any?, TicketQuery>(
        "matching_output",
        outputSchema =
          Schema(
            type = Type.OBJECT,
            properties =
              mapOf(
                "project" to Schema(type = Type.STRING),
                "maxResults" to Schema(type = Type.INTEGER),
              ),
            required = listOf("project"),
          ),
      ) { _, _ ->
        TicketQuery("ADK")
      }
    val wrongOutput =
      node<Any?, TicketQuery>("wrong_output", outputSchema = Schema(type = Type.STRING)) { _, _ ->
        TicketQuery("ADK")
      }

    // Act
    val inputError =
      assertFailsWith<NodeInputValidationException> {
        runOutputs(explicitInput, mapOf("count" to secret))
      }
    val inferredOutputs = runOutputs(inferredInput, UserProfile("u1", 3))
    val explicitProfileOutputs = runOutputs(explicitProfile, UserProfile("u2", 4))
    val matchingOutputs = runOutputs(matchingOutput, null)
    val outputError = assertFailsWith<IllegalArgumentException> { runOutputs(wrongOutput, null) }

    // Assert
    assertContains(inputError.message.orEmpty(), "input of node 'explicit_input'")
    assertFalse(secret in inputError.message.orEmpty())
    assertEquals(Type.OBJECT, inferredInput.inputSchema?.type)
    assertEquals(listOf("u1"), inferredOutputs)
    assertEquals(listOf("u2"), explicitProfileOutputs)
    assertEquals(listOf(mapOf("project" to "ADK", "maxResults" to 5L)), matchingOutputs)
    assertContains(outputError.message.orEmpty(), "output of node 'wrong_output'")
  }

  @Test
  fun node_explicitInputSchema_checksContentAsTextAndAnyValueOnlyAsJson() {
    // Arrange
    val stringSchema = Schema(type = Type.STRING)
    val profileSchema = SchemaUtils.inferSchema(typeOf<UserProfile>())
    val greetAny =
      node<Any?, String>("greet_any", inputSchema = stringSchema) { _, input ->
        (input as Content).text()
      }
    val greetContent =
      node<Content, String>("greet_content", inputSchema = stringSchema) { _, input ->
        input.text()
      }
    val describeAny =
      node<Any?, String>("describe_any", inputSchema = profileSchema) { _, input ->
        (input as Map<*, *>)["id"].toString()
      }

    // Act
    val anyOutputs = runOutputs(greetAny, Content.fromText(Role.USER, "Ada"))
    val contentOutputs = runOutputs(greetContent, Content.fromText(Role.USER, "Grace"))
    val mapOutputs = runOutputs(describeAny, mapOf("id" to "u1", "age" to 3))
    val classError =
      assertFailsWith<NodeInputValidationException> {
        runOutputs(describeAny, UserProfileDomain("u2", secret))
      }

    // Assert
    assertEquals(listOf("Ada"), anyOutputs)
    assertEquals(listOf("Grace"), contentOutputs)
    assertEquals(listOf("u1"), mapOutputs)
    assertContains(classError.message.orEmpty(), "input of node 'describe_any' has no JSON form")
    assertFalse(secret in classError.message.orEmpty())
  }

  @Test
  fun node_explicitInputSchema_checksTheConvertedInput() {
    // Arrange
    val intArray = Schema(type = Type.ARRAY, items = Schema(type = Type.INTEGER))
    val stringArray = Schema(type = Type.ARRAY, items = Schema(type = Type.STRING))
    val sum = node("sum", inputSchema = intArray) { _, xs: List<Int> -> xs.sum() }
    val join = node("join", inputSchema = stringArray) { _, xs: List<String> -> xs.joinToString() }

    // Act
    val wrongElement =
      assertFailsWith<NodeInputValidationException> { runOutputs(sum, listOf(1, secret)) }
    val joined = runOutputs(join, listOf("a", "b"))

    // Assert
    assertContains(wrongElement.message.orEmpty(), "input of node 'sum'[1]")
    assertFalse(secret in wrongElement.message.orEmpty())
    assertEquals(listOf("a, b"), joined)
  }

  @Test
  fun node_resumeShapedInputs_decodeToTypedValues() {
    // Arrange
    val describe = node("describe") { _, profile: UserProfile -> "${profile.id}:${profile.age}" }
    val sum = node("sum") { _, xs: List<Int> -> xs.sum() }
    val double = node("double") { _, x: Int -> x * 2 }

    // Act
    val profile = runOutputs(describe, mapOf("id" to "u1", "age" to 3L))
    val total = runOutputs(sum, listOf(1L, 2L))
    val doubled = runOutputs(double, 21L)
    val quoted = runOutputs(double, "21")

    // Assert
    assertEquals(listOf("u1:3"), profile)
    assertEquals(listOf(3L), total)
    assertEquals(listOf(42L), doubled)
    // A quoted number decodes into a numeric port, as pydantic reads it.
    assertEquals(listOf(42L), quoted)
  }

  // -- End-to-end workflows --

  @Test
  fun workflow_parallelFanOutAndJoinNode_aggregatesTheJsonFormsOfTheOutputs() {
    // Arrange
    val seed = node<Any?, String>("seed") { _, _ -> "admin_alice" }
    val fetchProfile =
      node("fetch_profile") { _, userId: String -> AccountProfile(userId = userId, tier = "gold") }
    val fetchQuota =
      node("fetch_quota") { _, userId: String -> if (userId.startsWith("admin_")) 10_000 else 100 }
    val joinFetches = JoinNode(name = "join_fetches")
    val buildReport =
      node("build_report") { _, joined: Map<String, Any?> ->
        val profile = joined["fetch_profile"] as Map<*, *>
        val quota = joined["fetch_quota"] as Long
        "User ${profile["userId"]} (tier=${profile["tier"]}) has quota=$quota"
      }
    val accountWorkflow =
      Workflow(
        name = "account_workflow",
        edges =
          listOf(
            Edge(Start, seed),
            Edge(seed, fetchProfile),
            Edge(seed, fetchQuota),
            Edge(fetchProfile, joinFetches),
            Edge(fetchQuota, joinFetches),
            Edge(joinFetches, buildReport),
          ),
      )

    // Act
    val events = runWorkflowThroughRunner(accountWorkflow)

    // Assert
    assertEquals(
      "User admin_alice (tier=gold) has quota=10000",
      events.last { it.output != null }.output,
    )
  }

  // -- Parallel worker FunctionNode builders --

  @Test
  fun parallelNode_listAndSingleItemInputs_mapsItemsInOrderAndPreservesItemMetadata() {
    // Arrange
    val produceItems = node<Any?, List<Int>>("produce_items") { _, _ -> listOf(1, 2, 3) }
    val doubleItems =
      parallelNode("double_items") { _, item: Int ->
        // Later items finish first, so output order must come from the input.
        delay(((3 - item) * 15).milliseconds)
        item * 10
      }
    val listWorkflow =
      Workflow(
        name = "list_wf",
        edges = listOf(Edge(Start, produceItems), Edge(produceItems, doubleItems)),
      )
    val produceSingle = node<Any?, Int>("produce_single") { _, _ -> 7 }
    val singleItemWorkflow =
      Workflow(
        name = "single_wf",
        edges = listOf(Edge(Start, produceSingle), Edge(produceSingle, doubleItems)),
      )

    // Act
    val listEvents = runWorkflowThroughRunner(listWorkflow)
    val singleEvents = runWorkflowThroughRunner(singleItemWorkflow)

    // Assert
    assertEquals(typeOf<List<Int>>(), doubleItems.inputType)
    assertEquals(typeOf<List<Int>>(), doubleItems.outputType)
    assertNull(doubleItems.inputSchema)
    assertNull(doubleItems.outputSchema)
    assertTrue(doubleItems.rerunOnResume)
    assertNull(doubleItems.maxParallelWorkers)
    assertEquals(typeOf<Int>(), doubleItems.itemNode?.inputType)
    assertEquals(typeOf<Int>(), doubleItems.itemNode?.outputType)
    assertEquals(Schema(type = Type.INTEGER), doubleItems.itemNode?.inputSchema)
    assertEquals(Schema(type = Type.INTEGER), doubleItems.itemNode?.outputSchema)
    assertEquals(listOf(10L, 20L, 30L), listEvents.last { it.output != null }.output)
    assertEquals(listOf(70L), singleEvents.last { it.output != null }.output)
  }

  @Test
  fun parallelNode_messageAfterStart_fansOutOverItsJsonListOrRunsItAsOneItem() {
    // Arrange
    val upper = parallelNode("upper") { _, item: String -> item.uppercase() }
    val echo = parallelNode("echo") { _, item: String -> item }
    val describe = parallelNode("describe") { _, item: Any? -> item.toString() }
    val partCounts = parallelNode("part_counts") { _, item: Any? -> (item as Content).parts.size }
    val sizes = parallelNode("sizes") { _, message: Content -> message.text().length }
    val upperWorkflow = Workflow(name = "upper_wf", edges = listOf(Edge(Start, upper)))
    val echoWorkflow = Workflow(name = "echo_wf", edges = listOf(Edge(Start, echo)))
    val describeWorkflow = Workflow(name = "describe_wf", edges = listOf(Edge(Start, describe)))
    val partsWorkflow = Workflow(name = "parts_wf", edges = listOf(Edge(Start, partCounts)))
    val sizesWorkflow = Workflow(name = "sizes_wf", edges = listOf(Edge(Start, sizes)))
    val listMessage = Content.fromText(Role.USER, """["a", "b"]""")
    val objectMessage = Content.fromText(Role.USER, """{"a":1}""")
    val mixedMessage =
      Content(
        role = Role.USER,
        parts = listOf(Part(text = """["a", "b"]"""), Part(functionCall = FunctionCall(name = "f"))),
      )

    // Act
    val listEvents = runWorkflowThroughRunner(upperWorkflow, listMessage)
    val textEvents = runWorkflowThroughRunner(upperWorkflow, Content.fromText(Role.USER, "plain"))
    val objectEvents = runWorkflowThroughRunner(echoWorkflow, objectMessage)
    val anyEvents = runWorkflowThroughRunner(describeWorkflow, listMessage)
    val mixedEvents = runWorkflowThroughRunner(partsWorkflow, mixedMessage)
    val wholeEvents = runWorkflowThroughRunner(sizesWorkflow, listMessage)

    // Assert
    assertEquals(listOf("A", "B"), listEvents.last { it.output != null }.output)
    assertEquals(listOf("PLAIN"), textEvents.last { it.output != null }.output)
    assertEquals(listOf("""{"a":1}"""), objectEvents.last { it.output != null }.output)
    assertEquals(listOf("a", "b"), anyEvents.last { it.output != null }.output)
    assertEquals(listOf(2L), mixedEvents.last { it.output != null }.output)
    assertEquals(listOf(10L), wholeEvents.last { it.output != null }.output)
  }

  @Test
  fun parallelNode_jsonMessageAfterStart_decodesEachItemIntoItsType() {
    // Arrange
    val quantities = parallelNode("quantities") { _, line: OrderRequest -> line.quantity }
    val workflow = Workflow(name = "quantities_wf", edges = listOf(Edge(Start, quantities)))
    val message =
      Content.fromText(
        Role.USER,
        """[{"item":"Book","quantity":2,"unitPrice":10.0},""" +
          """{"item":"Pen","quantity":"3","unitPrice":1.5}]""",
      )

    // Act
    val events = runWorkflowThroughRunner(workflow, message)

    // Assert
    assertEquals(listOf(2L, 3L), events.last { it.output != null }.output)
  }

  @Test
  fun parallelNode_emptyListInput_outputsAnEmptyList() {
    // Arrange
    val produceNone = node<Any?, List<Int>>("produce_none") { _, _ -> emptyList() }
    val doubleItems = parallelNode("double_items") { _, item: Int -> item * 2 }
    val workflow = Workflow("wf", listOf(Edge(Start, produceNone), Edge(produceNone, doubleItems)))

    // Act
    val events = runWorkflowThroughRunner(workflow)

    // Assert
    assertEquals(emptyList<Int>(), events.last { it.output != null }.output)
  }

  @OptIn(ExperimentalAtomicApi::class)
  @Test
  fun parallelNode_emittingProgressAndWrappedFunctionNode_respectMaxParallelWorkers() {
    // Arrange
    val active = AtomicInt(0)
    val peak = AtomicInt(0)
    val produceItems = node<Any?, List<String>>("produce") { _, _ -> listOf("a", "b", "c", "d") }
    val streamItems =
      parallelNode("stream_items", maxParallelWorkers = 2) { _, item: String ->
        val now = active.addAndFetch(1)
        do {
          val seen = peak.load()
        } while (now > seen && !peak.compareAndSet(seen, now))
        delay(20.milliseconds)
        emit(Event(content = Content.fromText(Role.MODEL, "step:$item")))
        val unused = active.addAndFetch(-1)
        item.uppercase()
      }
    val singleFn = node("append_bang") { _, s: String -> "$s!" }
    val wrappedParallel = parallelNode(singleFn, maxParallelWorkers = 2)
    val workflow =
      Workflow(
        name = "wf",
        edges =
          listOf(
            Edge(Start, produceItems),
            Edge(produceItems, streamItems),
            Edge(streamItems, wrappedParallel),
          ),
      )

    // Act
    val events = runWorkflowThroughRunner(workflow)

    // Assert
    assertEquals(2, peak.load())
    assertEquals(
      setOf("step:a", "step:b", "step:c", "step:d"),
      events.mapNotNull { it.content?.text() }.toSet(),
    )
    assertEquals(listOf("A!", "B!", "C!", "D!"), events.last { it.output != null }.output)
  }

  @OptIn(ExperimentalAtomicApi::class)
  @Test
  fun parallelNode_maxParallelWorkersOneOnAMultiThreadedDispatcher_startsItemsInIndexOrder() {
    // Arrange
    val items = (1..30).toList()
    val starts = mutableListOf<Int>()
    val active = AtomicInt(0)
    val peak = AtomicInt(0)
    val produceItems = node<Any?, List<Int>>("produce") { _, _ -> items }
    val worker =
      parallelNode("worker", maxParallelWorkers = 1) { _, item: Int ->
        starts += item
        val now = active.addAndFetch(1)
        if (now > peak.load()) peak.store(now)
        val unused = active.addAndFetch(-1)
        item
      }
    val runner =
      runnerFor(Workflow("wf", listOf(Edge(Start, produceItems), Edge(produceItems, worker))))

    // Act
    val unused =
      runBlocking(Dispatchers.Default) {
        runner.runAsync(userId = "u", sessionId = "s", newMessage = startMessage).toList()
      }

    // Assert
    assertEquals(items, starts)
    assertEquals(1, peak.load())
  }

  @OptIn(ExperimentalAtomicApi::class)
  @Test
  fun parallelNode_itemRetry_retriesOnlyTheFailedItem() {
    // Arrange
    val itemRuns = AtomicInt(0)
    val produceItems = node<Any?, List<Int>>("produce") { _, _ -> listOf(1, 2) }
    val worker =
      parallelNode(
        "worker",
        config =
          NodeConfig(retryConfig = RetryConfig(maxAttempts = 2, initialDelay = Duration.ZERO)),
      ) { ctx, item: Int ->
        val unused = itemRuns.addAndFetch(1)
        if (item == 2 && ctx.attemptCount == 1) throw IllegalStateException("transient")
        item * 10
      }
    val workflow = Workflow("wf", listOf(Edge(Start, produceItems), Edge(produceItems, worker)))

    // Act
    val events = runWorkflowThroughRunner(workflow)

    // Assert
    assertEquals(listOf(10L, 20L), events.last { it.output != null }.output)
    assertEquals(3, itemRuns.load())
  }

  @OptIn(ExperimentalAtomicApi::class)
  @Test
  fun parallelNode_itemInterrupt_interruptsTheNodeAndLetsRunningItemsFinish() {
    // Arrange
    val finishedSiblings = AtomicInt(0)
    val worker =
      parallelNode("worker") { _, item: Int ->
        if (item == 2) {
          emit(Event(longRunningToolIds = setOf("ask")))
        } else {
          delay(500.milliseconds)
          val unused = finishedSiblings.addAndFetch(1)
        }
        item * 10
      }

    // Act
    val ctx = runBlocking {
      NodeRunner(node = worker, parent = nodeContext(worker)).run(listOf(1, 2, 3))
    }

    // Assert
    assertEquals(setOf("ask"), ctx.interruptIds)
    assertNull(ctx.output)
    assertEquals(2, finishedSiblings.load())
  }

  @Test
  fun parallelNode_itemFailureWithOneWorker_startsNoQueuedItem() {
    // Arrange
    val startsPerRun = mutableListOf<List<Int>>()

    // Act
    repeat(50) {
      val starts = mutableListOf<Int>()
      val produceItems = node<Any?, List<Int>>("produce") { _, _ -> listOf(1, 2, 3) }
      val worker =
        parallelNode("worker", maxParallelWorkers = 1) { _, item: Int ->
          starts += item
          if (item == 1) throw IllegalStateException("boom")
          item
        }
      val runner =
        runnerFor(Workflow("wf", listOf(Edge(Start, produceItems), Edge(produceItems, worker))))
      val unused =
        assertFailsWith<IllegalStateException> {
          runBlocking(Dispatchers.Default) {
            runner.runAsync(userId = "u", sessionId = "s", newMessage = startMessage).toList()
          }
        }
      startsPerRun += starts.toList()
    }

    // Assert
    assertEquals(List(50) { listOf(1) }, startsPerRun)
  }

  @OptIn(ExperimentalAtomicApi::class)
  @Test
  fun parallelNode_itemFailure_cancelsTheItemsStillRunning() {
    // Arrange
    val finishedSiblings = AtomicInt(0)
    val produceItems = node<Any?, List<Int>>("produce") { _, _ -> listOf(1, 2, 3) }
    val worker =
      parallelNode("worker") { _, item: Int ->
        if (item == 2) throw IllegalStateException("boom")
        delay(500.milliseconds)
        val unused = finishedSiblings.addAndFetch(1)
        item
      }
    val workflow = Workflow("wf", listOf(Edge(Start, produceItems), Edge(produceItems, worker)))
    val runner = runnerFor(workflow)

    // Act
    val failure = assertFailsWith<IllegalStateException> { runner.runEvents() }
    val errorPaths =
      runner.session().events.filter { it.errorCode != null }.map { it.nodeInfo?.path }

    // Assert
    assertEquals("boom", failure.message)
    assertEquals(0, finishedSiblings.load())
    assertEquals(listOf("wf@1/worker@1/worker@2"), errorPaths)
  }

  @Test
  fun workflow_parallelFanOutToReducer_persistsItemOutputsAndTotal() {
    // Arrange
    val produceLines =
      node<Any?, List<OrderRequest>>("produce_lines") { _, _ ->
        listOf(OrderRequest("Book", 2, 10.0), OrderRequest("Pen", 3, 1.5))
      }
    val priceLines =
      parallelNode("price_lines") { _, line: OrderRequest -> line.quantity * line.unitPrice }
    val sumTotal =
      node("sum_total") { ctx, totals: List<Double> ->
        ctx.updateState("order_total", totals.sum())
        totals.sum()
      }
    val workflow =
      Workflow(
        name = "order_wf",
        edges =
          listOf(
            Edge(Start, produceLines),
            Edge(produceLines, priceLines),
            Edge(priceLines, sumTotal),
          ),
      )
    val runner = runnerFor(workflow)

    // Act
    val events = runner.runEvents()
    val session = runner.session()

    // Assert
    assertEquals(24.5, events.last { it.output != null }.output)
    assertEquals(24.5, session.state["order_total"])
    val fanOutEvent = session.events.single { it.nodeInfo?.path == "order_wf@1/price_lines@1" }
    assertEquals(listOf(20.0, 4.5), fanOutEvent.output)
  }

  @Test
  fun workflow_parallelNodeOfEvents_outputsTheOutputsOnTheItemEvents() {
    // Arrange
    val seed = node<Any?, List<Int>>("seed") { _, _ -> listOf(1, 2) }
    val wrap = parallelNode<Int, Event>("wrap") { _, x -> Event(output = x * 10) }
    val workflow = Workflow(name = "events_wf", edges = listOf(Edge(Start, seed), Edge(seed, wrap)))

    // Act
    val events = runWorkflowThroughRunner(workflow)

    // Assert
    assertEquals(
      listOf(10, 20),
      events.single { it.nodeInfo?.path == "events_wf@1/wrap@1" && it.output != null }.output,
    )
  }

  @Test
  fun parallelNode_nonPositiveMaxParallelWorkers_throwsWorkflowConfigurationException() {
    // Arrange
    val singleFn = node("single") { _, x: Int -> x }

    // Act
    val zeroError =
      assertFailsWith<WorkflowConfigurationException> {
        parallelNode("bad_zero", maxParallelWorkers = 0) { _, x: Int -> x }
      }
    val negativeError =
      assertFailsWith<WorkflowConfigurationException> {
        parallelNode(singleFn, maxParallelWorkers = -1)
      }

    // Assert
    assertEquals("maxParallelWorkers must be at least 1.", zeroError.message)
    assertEquals("maxParallelWorkers must be at least 1.", negativeError.message)
  }

  // -- Round trips through typed ports --

  /**
   * Returns what a node typed [T] receives from a predecessor that returns [value]: live, and from
   * the predecessor's event after a JSON round trip, as from a reloaded session.
   */
  private inline fun <reified T> receivedThroughNodes(value: T): Pair<Any?, Any?> {
    val received = mutableListOf<Any?>()
    val produce = node<Any?, T>("produce") { _, _ -> value }
    val consume = node<T, Unit>("consume") { _, input -> received += input }
    val runner = runnerFor(Workflow("wf", listOf(Edge(Start, produce), Edge(produce, consume))))
    val unused = runner.runEvents()
    val produced = runner.session().events.singleOrNull { it.nodeInfo?.path == "wf@1/produce@1" }
    val unusedOutputs = runOutputs(consume, produced?.let { reloaded(it).output })
    return received[0] to received[1]
  }

  private fun reloaded(event: Event): Event =
    adkJson.decodeFromString(Event.serializer(), adkJson.encodeToString(Event.serializer(), event))

  @Test
  fun typedPorts_supportedTypes_arriveEqualLiveAndAfterAReload() {
    // Arrange
    val order = PortOrder(id = "o1", note = null)

    // Act
    val cases: List<Pair<String, Pair<Any?, Pair<Any?, Any?>>>> =
      listOf(
        "Int, a Long after a reload" to (1 to receivedThroughNodes(1)),
        "String" to ("a" to receivedThroughNodes("a")),
        "enum with @SerialName" to (PortStatus.SHIPPED to receivedThroughNodes(PortStatus.SHIPPED)),
        "value class" to (PortUserId("u1") to receivedThroughNodes(PortUserId("u1"))),
        "data class with a required null" to (order to receivedThroughNodes(order)),
        "List of data classes" to (listOf(order) to receivedThroughNodes(listOf(order))),
        "Map<Int, data class>, keyed by strings" to
          (mapOf(1 to order) to receivedThroughNodes(mapOf(1 to order))),
        "Box<Int>" to (PortBox(1) to receivedThroughNodes(PortBox(1))),
        "sealed port" to
          (PortShape.Circle(1.0) to receivedThroughNodes<PortShape>(PortShape.Circle(1.0))),
      )
    val bytes = receivedThroughNodes(byteArrayOf(1, 2, 3))
    val bytesOutput =
      runOutputs(node<Any?, ByteArray>("bytes") { _, _ -> byteArrayOf(1, 2, 3) }, null)

    // Assert
    for ((case, result) in cases) {
      val (expected, received) = result
      assertEquals(expected, received.first, "$case, live")
      assertEquals(expected, received.second, "$case, after a reload")
    }
    assertEquals(listOf<Byte>(1, 2, 3), (bytes.first as ByteArray).toList())
    assertEquals(listOf<Byte>(1, 2, 3), (bytes.second as ByteArray).toList())
    assertEquals(listOf<Any?>("AQID"), bytesOutput)
  }

  @Test
  fun node_portTypeWithoutASerializer_failsWhenBuiltUnlessItNeedsNone() {
    // Act
    val inputError =
      assertFailsWith<WorkflowConfigurationException> {
        node<UserProfileDomain, String>("plain_in") { _, profile -> profile.userId }
      }
    val parallelInputError =
      assertFailsWith<WorkflowConfigurationException> {
        parallelNode<UserProfileDomain, String>("plain_in_items") { _, profile -> profile.userId }
      }
    val starInputError =
      assertFailsWith<WorkflowConfigurationException> {
        node<Map<String, *>, Int>("star_in") { _, fields -> fields.size }
      }
    val nodeError =
      assertFailsWith<WorkflowConfigurationException> {
        node<Any?, UserProfileDomain>("plain") { _, _ -> UserProfileDomain("u1", "gold") }
      }
    val parallelError =
      assertFailsWith<WorkflowConfigurationException> {
        parallelNode<Int, UserProfileDomain>("plain_items") { _, _ ->
          UserProfileDomain("u1", "gold")
        }
      }
    val interfaceError =
      assertFailsWith<WorkflowConfigurationException> {
        node<Any?, CharSequence>("text") { _, _ -> "hi" }
      }
    val nullableInterfaceError =
      assertFailsWith<WorkflowConfigurationException> {
        node<Any?, CharSequence?>("maybe_text") { _, _ -> null }
      }
    val numberError =
      assertFailsWith<WorkflowConfigurationException> { node<Any?, Number>("number") { _, _ -> 1 } }
    val requestError =
      assertFailsWith<WorkflowConfigurationException> {
        node<Any?, RequestInput>("ask") { _, _ -> RequestInput(message = "?") }
      }
    val built =
      listOf(
        node<PortOrder, PortOrder>("order") { _, order -> order },
        node<Content, Content>("content") { _, message -> message },
        node<Any?, Any?>("any") { _, _ -> null },
        node<Unit, Unit>("unit") { _, _ -> },
        node<Event, Event>("event") { _, event -> event },
      )

    // Assert
    assertContains(inputError.message.orEmpty(), "node 'plain_in' has input type")
    assertContains(inputError.message.orEmpty(), "or use Any for a JSON-native value")
    assertContains(parallelInputError.message.orEmpty(), "node 'plain_in_items' has input type")
    assertContains(starInputError.message.orEmpty(), "node 'star_in' has input type")
    assertContains(nodeError.message.orEmpty(), "node 'plain' has output type")
    assertContains(
      nodeError.message.orEmpty(),
      "which has no usable serializer. Mark it @Serializable",
    )
    assertContains(parallelError.message.orEmpty(), "node 'plain_items' has output type")
    assertContains(interfaceError.message.orEmpty(), "node 'text' has output type")
    assertContains(nullableInterfaceError.message.orEmpty(), "node 'maybe_text' has output type")
    assertContains(numberError.message.orEmpty(), "node 'number' has output type")
    assertContains(requestError.message.orEmpty(), "node 'ask' has output type")
    assertEquals(listOf("order", "content", "any", "unit", "event"), built.map { it.name })
  }

  @Test
  fun node_nestedPortTypeThatCannotTravelAsJson_failsWhenBuiltOnlyIfItCannotTravelAsJson() {
    // Act
    val interfaceListError =
      assertFailsWith<WorkflowConfigurationException> {
        node<Any?, List<CharSequence>>("texts") { _, _ -> listOf("hi") }
      }
    val byteListError =
      assertFailsWith<WorkflowConfigurationException> {
        node<Any?, List<ByteArray>>("chunks") { _, _ -> listOf(byteArrayOf(1)) }
      }
    val byteMapError =
      assertFailsWith<WorkflowConfigurationException> {
        node<Map<String, ByteArray>, Int>("blobs") { _, blobs -> blobs.size }
      }
    val built =
      listOf(
        node<Any?, ByteArray>("bytes") { _, _ -> byteArrayOf(1) },
        node<List<PortOrder>, Map<String, List<Long>>>("totals") { _, _ -> emptyMap() },
        node<List<Content>, List<Any?>>("messages") { _, _ -> emptyList() },
      )

    // Assert
    assertContains(interfaceListError.message.orEmpty(), "node 'texts' has output type")
    assertContains(interfaceListError.message.orEmpty(), "which has no usable serializer")
    assertContains(byteListError.message.orEmpty(), "node 'chunks' has output type")
    assertContains(byteListError.message.orEmpty(), "only a top-level ByteArray is supported")
    assertContains(byteMapError.message.orEmpty(), "node 'blobs' has input type")
    assertContains(byteMapError.message.orEmpty(), "only a top-level ByteArray is supported")
    assertEquals(listOf("bytes", "totals", "messages"), built.map { it.name })
  }

  @Test
  fun typedPorts_sealedInput_decodesAParentTypedOutputAndNamesTheFixForASubclassTypedOne() {
    // Arrange: only a producer typed with the sealed parent writes the class discriminator.
    val parentTyped = node<Any?, PortShape>("parent_typed") { _, _ -> PortShape.Circle(1.0) }
    val subclassTyped =
      node<Any?, PortShape.Circle>("subclass_typed") { _, _ -> PortShape.Circle(1.0) }
    val consume = node<PortShape, PortShape>("consume") { _, shape -> shape }
    val parentJson = runOutputs(parentTyped, null).single()
    val subclassJson = runOutputs(subclassTyped, null).single()

    // Act
    val decoded = runOutputs(consume, parentJson).single()
    val error = assertFailsWith<NodeInputValidationException> { runOutputs(consume, subclassJson) }

    // Assert
    assertEquals(parentJson, decoded)
    assertContains(error.message.orEmpty(), "input of node 'consume' does not match")
    assertContains(error.message.orEmpty(), "(node<_, ")
    assertContains(error.message.orEmpty(), "so the class discriminator is written")
  }

  @Test
  fun typedPorts_classWithTypeAndValueFields_mismatchNamesNoDiscriminator() {
    // Arrange: fields named like a sealed descriptor's elements.
    val consume = node<PortReading, Unit>("consume") { _, _ -> }

    // Act
    val error =
      assertFailsWith<NodeInputValidationException> { runOutputs(consume, mapOf("type" to "t")) }

    // Assert
    assertContains(error.message.orEmpty(), "input of node 'consume' does not match")
    assertFalse("discriminator" in error.message.orEmpty())
  }

  @Test
  fun outputs_classWithADefaultValuedField_keepTheFieldAndPassASchemaRequiringIt() {
    // Arrange
    val requiresQuantity =
      Schema(
        type = Type.OBJECT,
        properties =
          mapOf(
            "id" to Schema(type = Type.STRING),
            "note" to Schema(type = Type.STRING, nullable = true),
            "quantity" to Schema(type = Type.INTEGER),
          ),
        required = listOf("quantity"),
      )
    val order =
      node<Any?, PortOrder>("order", outputSchema = requiresQuantity) { _, _ ->
        PortOrder(id = "o1", note = null)
      }

    // Act
    val output = runOutputs(order, null).single()

    // Assert
    assertEquals(mapOf("id" to "o1", "note" to null, "quantity" to 1L), output)
  }

  @Test
  fun outputs_classWithADefaultValuedField_reachAJoinNodeSuccessorWithTheField() {
    // Arrange
    val received = mutableListOf<Any?>()
    val profile = node<Any?, PortProfile>("profile") { _, _ -> PortProfile(userId = "alice") }
    val other = node<Any?, String>("other") { _, _ -> "x" }
    val join = JoinNode("join")
    val after = node<Any?, Unit>("after") { _, input -> received += input }
    val workflow =
      Workflow(
        "wf",
        listOf(
          Edge(Start, profile),
          Edge(Start, other),
          Edge(profile, join),
          Edge(other, join),
          Edge(join, after),
        ),
      )

    // Act
    val unused = runnerFor(workflow).runEvents()

    // Assert
    assertEquals(
      mapOf("profile" to mapOf("userId" to "alice", "tier" to "free"), "other" to "x"),
      received.single(),
    )
  }

  @Test
  fun nodeBody_emittingAnEventWithAnOutput_failsAtTheNode() {
    // Arrange
    val emits =
      node<Any?, String>("emits") { _, _ ->
        emit(Event(output = "x"))
        "y"
      }
    val emitsMessageOutput =
      node<Any?, Unit>("emits_message_output") { _, _ ->
        val message = Content.fromText(Role.MODEL, "hi")
        emit(Event(content = message, nodeInfo = NodeInfo(messageAsOutput = true)))
      }

    // Act
    val error = assertFailsWith<IllegalArgumentException> { runOutputs(emits, null) }
    val messageError =
      assertFailsWith<IllegalArgumentException> { runOutputs(emitsMessageOutput, null) }

    // Assert
    assertEquals(
      "node 'emits' emitted an Event with an output; return the value instead and put state " +
        "changes in ctx.updateState or the event's actions.",
      error.message,
    )
    assertContains(messageError.message.orEmpty(), "node 'emits_message_output' emitted an Event")
  }

  @Test
  fun nodeBody_emittingActionsOnly_landsTheStateDeltaAndReturnsTheOutput() {
    // Arrange
    val step =
      node<Any?, String>("step") { _, _ ->
        emit(Event(actions = EventActions(stateDelta = mutableMapOf("k" to "v"))))
        "done"
      }
    val runner = runnerFor(Workflow("wf", listOf(Edge(Start, step))))

    // Act
    val events = runner.runEvents()

    // Assert
    assertEquals("v", runner.session().state["k"])
    assertEquals("done", events.single { it.output != null }.output)
  }

  // -- Set and untyped outputs --

  @Test
  fun nodeBody_settingAnOutput_failsAtTheNode() {
    // Arrange
    val sets =
      node<Any?, String?>("sets") { ctx, _ ->
        ctx.setOutput("x")
        null
      }

    // Act
    val error = assertFailsWith<IllegalStateException> { runOutputs(sets, null) }

    // Assert
    assertEquals("node 'sets' called setOutput; return the value instead.", error.message)
  }

  @Test
  fun untypedOutputs_takeTheirSavedFormOrFailAtTheNode() {
    // Arrange
    val anyJson = node<Any?, Any?>("any_json") { _, _ -> mapOf("n" to 1) }
    val keyedByInt = node<Any?, Any?>("keyed_by_int") { _, _ -> mapOf(1 to "a") }
    val anyClass = node<Any?, Any?>("any_class") { _, _ -> PortOrder(id = "o1", note = null) }

    // Act
    val jsonOutput = runOutputs(anyJson, null).single()
    val keyedOutput = runOutputs(keyedByInt, null).single()
    val classError = assertFailsWith<IllegalArgumentException> { runOutputs(anyClass, null) }

    // Assert
    assertEquals(mapOf("n" to 1L), jsonOutput)
    // A session saves a map key as a string, so the output reads as one at once.
    assertEquals(mapOf("1" to "a"), keyedOutput)
    assertContains(classError.message.orEmpty(), "output of node 'any_class' has no JSON form")
  }

  @Test
  fun customNode_setOutput_reachesATypedSuccessorLiveAndAfterAReloadAndNeedsASerializer() {
    // Arrange
    val received = mutableListOf<PortOrder>()
    val consume = node<PortOrder, Unit>("consume") { _, order -> received += order }
    val writer = PortOrderWriter("writer")
    val runner = runnerFor(Workflow("wf", listOf(Edge(Start, writer), Edge(writer, consume))))
    val plainRunner = runnerFor(Workflow("wf", listOf(Edge(Start, PortPlainWriter("plain")))))

    // Act
    val unusedEvents = runner.runEvents()
    val saved = runner.session().events.single { it.nodeInfo?.path == "wf@1/writer@1" }
    val unusedOutputs = runOutputs(consume, reloaded(saved).output)
    val plainError = assertFailsWith<IllegalArgumentException> { plainRunner.runEvents() }

    // Assert
    assertEquals(List(2) { PortOrder(id = "o1", note = null) }, received)
    assertContains(plainError.message.orEmpty(), "output of node 'plain' has type")
    assertContains(plainError.message.orEmpty(), "Mark it @Serializable.")
  }

  // -- Node name validation --

  @Test
  fun node_invalidNodeName_throwsIllegalArgumentException() {
    // Act
    val emptyNameError =
      assertFailsWith<IllegalArgumentException> { node<Any?, Unit>("") { _, _ -> } }
    val slashNameError =
      assertFailsWith<IllegalArgumentException> { node<Any?, Unit>("bad/name") { _, _ -> } }

    // Assert
    assertContains(emptyNameError.message.orEmpty(), "must not be empty")
    assertContains(slashNameError.message.orEmpty(), "'bad/name'")
  }
}
