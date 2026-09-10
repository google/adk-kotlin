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
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import kotlin.reflect.typeOf
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
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

@Serializable private data class TicketQuery(val project: String, val maxResults: Int = 5)

class FunctionNodeTest {

  private val secret = "SENTINEL-SECRET-9912"

  private fun nodeContext(node: Node): Context =
    Context(
      invocationContext =
        testInvocationContext(session = Session(key = SessionKey("app", "u", "s"))),
      node = node,
      eventSink = EventSink {},
    )

  /** Runs [node] alone on [input], as the engine does, and returns the outputs it emits. */
  private fun runOutputs(node: BaseNode, input: Any?): List<Any?> = runBlocking {
    node.run(nodeContext(node), input).toList().map { it.output }
  }

  // -- node(...) core behavior --

  @Test
  fun node_typedInputToTypedOutput_infersTypesAndSchemasAndRuns() {
    // Arrange
    val wordCount = node("word_count") { _, text: String -> text.trim().split(Regex("\\s+")).size }
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, wordCount)))

    // Act
    val events =
      runWorkflowThroughRunner(workflow, Content.fromText(Role.USER, "hello brave world"))

    // Assert
    assertEquals(typeOf<String>(), wordCount.inputType)
    assertEquals(typeOf<Int>(), wordCount.outputType)
    assertEquals(Schema(type = Type.STRING), wordCount.inputSchema)
    assertEquals(Schema(type = Type.INTEGER), wordCount.outputSchema)
    assertEquals(3, events.single { it.output != null }.output)
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
  fun node_emitsProgressEventsAndReturnsOutput_preservesOutputTypeAndStampsAuthor() {
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
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, streamChunks)))

    // Act
    val events = runWorkflowThroughRunner(workflow, Content.fromText(Role.USER, "a b"))

    // Assert
    assertEquals(typeOf<Int>(), streamChunks.outputType)
    assertEquals(Schema(type = Type.INTEGER), streamChunks.outputSchema)
    val progressEvents = events.filter { it.content != null }
    assertEquals(listOf("Processing a", "Processing b"), progressEvents.map { it.content?.text() })
    assertEquals(listOf("wf", "wf"), progressEvents.map { it.author })
    val outputEvent = events.single { it.output != null }
    assertEquals(2, outputEvent.output)
    assertEquals(2, outputEvent.actions.stateDelta["processed_count"])
  }

  // -- Runtime input validation --

  @Test
  fun node_wrongOrNullInput_throwsValueFreeNodeInputValidationException() {
    // Arrange
    val domainConsumer = node("domain_consumer") { _, profile: UserProfileDomain -> profile.userId }
    val listConsumer = node("list_consumer") { _, xs: List<Int> -> xs.sum() }
    val countConsumer = node("count_consumer") { _, n: Int -> n }
    val nullableConsumer = node("nullable_consumer") { _, s: String? -> s ?: "fallback" }

    // Act
    val domainError =
      assertFailsWith<NodeInputValidationException> { runOutputs(domainConsumer, secret) }
    val listError =
      assertFailsWith<NodeInputValidationException> { runOutputs(listConsumer, listOf(1, secret)) }
    val quotedNumberError =
      assertFailsWith<NodeInputValidationException> { runOutputs(countConsumer, "42") }
    val nullError =
      assertFailsWith<NodeInputValidationException> { runOutputs(countConsumer, null) }
    val nullableOutputs = runOutputs(nullableConsumer, null)

    // Assert
    assertFalse(secret in domainError.message.orEmpty())
    assertFalse(secret in listError.message.orEmpty())
    assertContains(listError.message.orEmpty(), "input of node 'list_consumer'[1]")
    assertContains(quotedNumberError.message.orEmpty(), "expected kotlin.Int, but got String")
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
  fun node_contentReturn_persistsSerializableMessageEventThatFeedsSuccessor() {
    // Arrange
    val produceContent =
      node<Any?, Content>("produce_content") { _, _ ->
        Content.fromText(Role.MODEL, "cached answer")
      }
    val consumeContent = node("consume_content") { _, c: Content -> c.text() }
    val workflow =
      Workflow(
        name = "wf",
        edges = listOf(Edge(Start, produceContent), Edge(produceContent, consumeContent)),
      )
    val runner = runnerFor(workflow)

    // Act
    val events = runner.runEvents()
    val messageEvent =
      runner.session().events.single { it.nodeInfo?.path == "wf@1/produce_content@1" }
    val roundTripped =
      adkJson.decodeFromString(
        Event.serializer(),
        adkJson.encodeToString(Event.serializer(), messageEvent),
      )

    // Assert
    assertEquals(Content.fromText(Role.MODEL, "cached answer"), messageEvent.content)
    assertNull(messageEvent.output)
    assertEquals(true, messageEvent.nodeInfo?.messageAsOutput)
    assertEquals(messageEvent.content, roundTripped.content)
    assertEquals("cached answer", events.last { it.output != null }.output)
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
            required = listOf("project", "maxResults"),
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
    assertEquals(listOf(TicketQuery("ADK")), matchingOutputs)
    assertContains(outputError.message.orEmpty(), "output of node 'wrong_output'")
  }

  @Test
  fun node_explicitInputSchema_checksContentAsTextAndAnyClassValueByItsJson() {
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
        (input as UserProfile).id
      }

    // Act
    val anyOutputs = runOutputs(greetAny, Content.fromText(Role.USER, "Ada"))
    val contentOutputs = runOutputs(greetContent, Content.fromText(Role.USER, "Grace"))
    val profileOutputs = runOutputs(describeAny, UserProfile("u1", 3))
    val domainError =
      assertFailsWith<NodeInputValidationException> {
        runOutputs(describeAny, UserProfileDomain("u2", secret))
      }

    // Assert
    assertEquals(listOf("Ada"), anyOutputs)
    assertEquals(listOf("Grace"), contentOutputs)
    assertEquals(listOf("u1"), profileOutputs)
    assertContains(domainError.message.orEmpty(), "input of node 'describe_any'")
    assertFalse(secret in domainError.message.orEmpty())
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
