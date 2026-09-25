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
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.apps.App
import com.google.adk.kt.events.Event
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.sessions.State
import com.google.adk.kt.testing.testInvocationContext
import com.google.adk.kt.testing.testSession
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/** Runs [workflow] against a context whose session starts with [state], and collects its events. */
private fun runWorkflowWithState(workflow: Workflow, state: Map<String, Any>): List<Event> =
  runBlocking {
    val session =
      Session(key = SessionKey("test_app", "u", "s"), state = State(initialState = state))
    workflow.runAsync(testInvocationContext(session = session)).toList()
  }

/** Runs [workflow] through a real runner, so state deltas propagate between nodes as it streams. */
private fun runWorkflowThroughRunner(workflow: Workflow): List<Event> = runBlocking {
  val runner = InMemoryRunner(App(appName = workflow.name, rootNode = workflow))
  val unused = runner.sessionService.createSession(SessionKey(runner.appName, "u", "s"))
  val message = Content(role = "user", parts = listOf(Part(text = "start")))
  runner.runAsync(userId = "u", sessionId = "s", newMessage = message).toList()
}

/**
 * Unit binding tests and workflow behavior tests for [FunctionNode], ported from the Python
 * workflow suite's FunctionNode tests.
 */
class FunctionNodeTest {

  // -- Direct FunctionNode.runNode and stateSchema validation unit tests --

  @Test
  fun bind_stateBinding_readsParametersFromState() = runBlocking {
    // Arrange
    var captured: Map<String, Any?>? = null
    val node =
      FunctionNode(
        name = "greet",
        params = listOf(NodeParam.required("name"), NodeParam.required("count")),
        parameterBinding = ParameterBinding.STATE,
        body = { _, args ->
          captured = args
          null
        },
      )

    // Act
    node.runNode(nodeContext(node, state = mapOf("name" to "Ada", "count" to 3)), null).toList()

    // Assert
    assertEquals(mapOf("name" to "Ada", "count" to 3), captured)
  }

  @Test
  fun bind_nodeInputBinding_readsParametersFromTheUpstreamMap() = runBlocking {
    // Arrange
    var captured: Map<String, Any?>? = null
    val node =
      FunctionNode(
        name = "sum",
        params = listOf(NodeParam.required("a"), NodeParam.required("b")),
        parameterBinding = ParameterBinding.NODE_INPUT,
        body = { _, args ->
          captured = args
          null
        },
      )

    // Act: state carries a stale "a" that must be ignored under node-input binding.
    val context = nodeContext(node, state = mapOf("a" to 999))
    node.runNode(context, nodeInput = mapOf("a" to 1, "b" to 2)).toList()

    // Assert
    assertEquals(mapOf("a" to 1, "b" to 2), captured)
  }

  @Test
  fun bind_reservedNodeInputParamUnderStateBinding_receivesTheUpstreamOutput() = runBlocking {
    // Arrange
    var captured: Map<String, Any?>? = null
    val node =
      FunctionNode(
        name = "consume",
        params = listOf(NodeParam.required(FunctionNode.NODE_INPUT_PARAM)),
        parameterBinding = ParameterBinding.STATE,
        body = { _, args ->
          captured = args
          null
        },
      )

    // Act: the reserved name is absent from state, yet resolves from the upstream output.
    node.runNode(nodeContext(node, state = emptyMap()), nodeInput = "from-upstream").toList()

    // Assert
    assertEquals(mapOf(FunctionNode.NODE_INPUT_PARAM to "from-upstream"), captured)
  }

  @Test
  fun bind_requiredParameterMissingFromState_fails() {
    // Arrange
    val node =
      FunctionNode(
        name = "needsValue",
        params = listOf(NodeParam.required("needed")),
        parameterBinding = ParameterBinding.STATE,
        body = { _, _ -> null },
      )

    // Act, Assert
    val error =
      assertFailsWith<IllegalArgumentException> {
        runBlocking { node.runNode(nodeContext(node, state = emptyMap()), null).toList() }
      }
    assertContains(error.message ?: "", "Missing value for parameter \"needed\"")
    assertContains(error.message ?: "", "not found in state")
  }

  @Test
  fun bind_optionalParameterAbsent_usesItsDefault() = runBlocking {
    // Arrange
    var captured: Map<String, Any?>? = null
    val node =
      FunctionNode(
        name = "paged",
        params = listOf(NodeParam.optional("limit", defaultValue = 10)),
        parameterBinding = ParameterBinding.STATE,
        body = { _, args ->
          captured = args
          null
        },
      )

    // Act
    node.runNode(nodeContext(node, state = emptyMap()), null).toList()

    // Assert
    assertEquals(mapOf("limit" to 10), captured)
  }

  @Test
  fun bind_stringForInteger_isRejected() {
    // Arrange
    val node =
      FunctionNode(
        name = "counter",
        params = listOf(NodeParam.required("count", schema = Schema(type = Type.INTEGER))),
        parameterBinding = ParameterBinding.STATE,
        body = { _, _ -> null },
      )

    // Act: a numeric string is not converted to an integer.
    val error =
      assertFailsWith<IllegalArgumentException> {
        runBlocking {
          node.runNode(nodeContext(node, state = mapOf("count" to "42")), null).toList()
        }
      }

    // Assert
    assertContains(error.message ?: "", "parameter \"count\"")
    assertContains(error.message ?: "", "validation error: count")
  }

  @Test
  fun bind_objectFieldOfTheWrongType_keepsTheDetailedMessage() {
    // Arrange: an object parameter whose property has the wrong type.
    val schema =
      Schema(type = Type.OBJECT, properties = mapOf("count" to Schema(type = Type.INTEGER)))
    val node =
      FunctionNode(
        name = "counter",
        params = listOf(NodeParam.required("settings", schema = schema)),
        parameterBinding = ParameterBinding.STATE,
        body = { _, _ -> null },
      )

    // Act, Assert: the underlying validation detail and cause both survive.
    val error =
      assertFailsWith<IllegalArgumentException> {
        runBlocking {
          node
            .runNode(nodeContext(node, state = mapOf("settings" to mapOf("count" to "many"))), null)
            .toList()
        }
      }
    val cause = assertNotNull(error.cause)
    assertContains(error.message ?: "", "parameter \"settings\"")
    assertContains(error.message ?: "", "validation error: settings")
    assertContains(error.message ?: "", assertNotNull(cause.message))
  }

  @Test
  fun bind_jsonContentForAnObject_bindsTheParsedObject() = runBlocking {
    // Arrange: as in Python, a bound Content's text is read as JSON for a non-string parameter.
    var captured: Map<String, Any?>? = null
    val schema =
      Schema(type = Type.OBJECT, properties = mapOf("count" to Schema(type = Type.INTEGER)))
    val node =
      FunctionNode(
        name = "counter",
        params = listOf(NodeParam.required("settings", schema = schema)),
        parameterBinding = ParameterBinding.STATE,
        body = { _, args ->
          captured = args
          null
        },
      )
    val json = Content.fromText("user", """{"count": 1}""")

    // Act
    node.runNode(nodeContext(node, state = mapOf("settings" to json)), null).toList()

    // Assert
    assertEquals(mapOf("settings" to mapOf("count" to 1L)), captured)
  }

  @Test
  fun bind_jsonStringForAnObject_isRejected() {
    // Arrange: unlike a Content, a String is never parsed as JSON.
    val schema =
      Schema(type = Type.OBJECT, properties = mapOf("count" to Schema(type = Type.INTEGER)))
    val node =
      FunctionNode(
        name = "counter",
        params = listOf(NodeParam.required("settings", schema = schema)),
        parameterBinding = ParameterBinding.STATE,
        body = { _, _ -> null },
      )

    // Act
    val error =
      assertFailsWith<IllegalArgumentException> {
        runBlocking {
          node
            .runNode(nodeContext(node, state = mapOf("settings" to """{"count": 1}""")), null)
            .toList()
        }
      }

    // Assert
    assertContains(error.message ?: "", "validation error: settings")
  }

  @Test
  fun runNode_bodyEmitsValues_emitsEachInOrder() = runBlocking {
    // Arrange
    val node =
      FunctionNode(
        name = "worker",
        body = { _, _ ->
          emit("step-1")
          emit("step-2")
          null
        },
      )

    // Act
    val emissions = node.runNode(nodeContext(node), null).toList()

    // Assert: the null return is passed on for the engine to skip.
    assertEquals(listOf("step-1", "step-2", null), emissions)
  }

  @Test
  fun runNode_bodyReturnsAValue_emitsIt() = runBlocking {
    // Arrange
    val node = FunctionNode(name = "producer", body = { _, _ -> "result" })

    // Act
    val emissions = node.runNode(nodeContext(node), null).toList()

    // Assert
    assertEquals(listOf("result"), emissions)
  }

  @Test
  fun workflow_bodyReturnsUnit_producesNoOutput() {
    // Arrange
    val fn = FunctionNode(name = "fn") { _, _ -> }
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, fn)))

    // Act
    val events = runWorkflow(workflow)

    // Assert
    assertEquals(emptyList(), events)
  }

  @Test
  fun runNode_bodyEmitsContent_sendsItOnAnEvent() = runBlocking {
    // Arrange
    val node =
      FunctionNode(name = "streamer") { _, _ ->
        emit(Content.fromText("model", "working"))
        "final"
      }

    // Act
    val emissions = node.runNode(nodeContext(node), null).toList()

    // Assert: the emitted Content is progress and the return is the output.
    assertEquals(2, emissions.size)
    val progress = emissions.first() as Event
    assertEquals("working", progress.content?.text())
    assertNull(progress.output)
    assertEquals("final", emissions.last())
  }

  @Test
  fun bind_nonMapNodeInput_fallsBackToDefaults() = runBlocking {
    // Arrange
    var captured: Map<String, Any?>? = null
    val node =
      FunctionNode(
        name = "paged",
        params = listOf(NodeParam.optional("limit", defaultValue = 10)),
        parameterBinding = ParameterBinding.NODE_INPUT,
        body = { _, args ->
          captured = args
          null
        },
      )

    // Act: a non-map input has no keys to bind from, so every parameter falls back.
    node.runNode(nodeContext(node), nodeInput = "not a map").toList()

    // Assert
    assertEquals(mapOf("limit" to 10), captured)
  }

  @Test
  fun bind_contentForAStringUnion_bindsTheText() = runBlocking {
    // Arrange: a union with a string takes the text, not the Content itself.
    var captured: Map<String, Any?>? = null
    val schema = Schema(anyOf = listOf(Schema(type = Type.INTEGER), Schema(type = Type.STRING)))
    val node =
      FunctionNode(
        name = "consume",
        params = listOf(NodeParam.required(FunctionNode.NODE_INPUT_PARAM, schema)),
        body = { _, args ->
          captured = args
          null
        },
      )

    // Act
    node.runNode(nodeContext(node), nodeInput = Content.fromText("user", "42")).toList()

    // Assert
    assertEquals(mapOf(FunctionNode.NODE_INPUT_PARAM to "42"), captured)
  }

  @Test
  fun nodeParam_defaultNotMatchingItsSchema_isRejected() {
    // Act
    val error =
      assertFailsWith<IllegalArgumentException> {
        NodeParam.optional("count", defaultValue = "many", schema = Schema(type = Type.INTEGER))
      }

    // Assert
    assertContains(error.message ?: "", "parameter \"count\"")
  }

  @Test
  fun init_duplicateParameterNames_areRejected() {
    // Act
    val error =
      assertFailsWith<IllegalArgumentException> {
        FunctionNode(
          name = "twice",
          params = listOf(NodeParam.required("x"), NodeParam.optional("x", defaultValue = 1)),
          body = { _, _ -> null },
        )
      }

    // Assert
    assertContains(error.message ?: "", "[x]")
  }

  @Test
  fun workflow_nodeInputBoundParameter_isNotCheckedAgainstStateSchema() {
    // Arrange: "payload" is bound from the predecessor's output, not declared in the schema.
    val node =
      FunctionNode(
        name = "consumer",
        params = listOf(NodeParam.required("payload")),
        parameterBinding = ParameterBinding.NODE_INPUT,
        body = { _, _ -> null },
      )

    // Act, Assert: constructing the workflow validates the schema and must not reject the node.
    val workflow =
      Workflow(
        name = "wf",
        edges = listOf(Edge(Start, node)),
        stateSchema =
          Schema(type = Type.OBJECT, properties = mapOf("declared" to Schema(type = Type.STRING))),
      )
    assertNotNull(workflow)
  }

  @Test
  fun workflow_stateBoundReservedNodeInputParameter_isNotCheckedAgainstStateSchema() {
    // Arrange: the reserved name is fed the upstream output, so it need not be declared.
    val node =
      FunctionNode(
        name = "consumer",
        params = listOf(NodeParam.required(FunctionNode.NODE_INPUT_PARAM)),
        parameterBinding = ParameterBinding.STATE,
        body = { _, _ -> null },
      )

    // Act, Assert
    val workflow =
      Workflow(
        name = "wf",
        edges = listOf(Edge(Start, node)),
        stateSchema =
          Schema(type = Type.OBJECT, properties = mapOf("declared" to Schema(type = Type.STRING))),
      )
    assertNotNull(workflow)
  }

  @Test
  fun workflow_stateBoundParameterNotInStateSchema_fails() {
    // Arrange
    val node =
      FunctionNode(
        name = "consumer",
        params = listOf(NodeParam.required("payload")),
        parameterBinding = ParameterBinding.STATE,
        body = { _, _ -> null },
      )

    // Act, Assert
    val error =
      assertFailsWith<IllegalArgumentException> {
        Workflow(
          name = "wf",
          edges = listOf(Edge(Start, node)),
          stateSchema =
            Schema(type = Type.OBJECT, properties = mapOf("declared" to Schema(type = Type.STRING))),
        )
      }
    assertContains(error.message ?: "", "parameter 'payload' is not declared in state_schema")
  }

  @Test
  fun workflow_nodeWithItsOwnStateSchema_overridesTheWorkflowSchema() {
    // Arrange: the node declares its own stateSchema containing "payload", overriding the workflow.
    val node =
      FunctionNode(
        name = "consumer",
        params = listOf(NodeParam.required("payload")),
        parameterBinding = ParameterBinding.STATE,
        stateSchema =
          Schema(type = Type.OBJECT, properties = mapOf("payload" to Schema(type = Type.STRING))),
        body = { _, _ -> null },
      )

    // Act, Assert
    val workflow =
      Workflow(
        name = "wf",
        edges = listOf(Edge(Start, node)),
        stateSchema =
          Schema(type = Type.OBJECT, properties = mapOf("declared" to Schema(type = Type.STRING))),
      )
    assertNotNull(workflow)
  }

  // -- End-to-end Workflow behavior tests --

  @Test
  fun workflow_bodyReturnsAValue_emitsItAsTheOutput() {
    // Arrange
    val fn = FunctionNode(name = "fn") { _, _ -> "Hello from the body" }
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, fn)))

    // Act
    val events = runWorkflow(workflow)

    // Assert
    assertEquals("Hello from the body", events.single().output)
  }

  @Test
  fun workflow_bodyReturnsNull_producesNoOutput() {
    // Arrange
    val fn = FunctionNode(name = "fn") { _, _ -> null }
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, fn)))

    // Act
    val events = runWorkflow(workflow)

    // Assert
    assertEquals(emptyList(), events)
  }

  @Test
  fun workflow_bodyEmitsAnEventWithOutput_passesItThrough() {
    // Arrange: a body may emit an Event directly rather than returning a value.
    val fn = FunctionNode(name = "fn") { _, _ -> emit(Event(author = "", output = "emitted")) }
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, fn)))

    // Act
    val events = runWorkflow(workflow)

    // Assert
    assertEquals("emitted", events.single { it.output != null }.output)
  }

  @Test
  fun workflow_bodyReturnsContent_emitsItAsProgressNotOutput() {
    // Arrange: a returned Content is progress the successors do not act on, so it rides on the
    // event's content rather than becoming the node's output.
    val fn = FunctionNode(name = "fn") { _, _ -> Content.fromText("model", "some content") }
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, fn)))

    // Act
    val events = runWorkflow(workflow)

    // Assert
    val event = events.single()
    assertNull(event.output)
    assertEquals("some content", event.content?.parts?.single()?.text)
  }

  @Test
  fun workflow_bodyEmitsAnEventWithContent_passesItThroughAsProgress() {
    // Arrange
    val fn =
      FunctionNode(name = "fn") { _, _ ->
        emit(Event(author = "", content = Content.fromText("model", "progress")))
      }
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, fn)))

    // Act
    val events = runWorkflow(workflow)

    // Assert
    val event = events.single()
    assertNull(event.output)
    assertEquals("progress", event.content?.parts?.single()?.text)
  }

  @Test
  fun workflow_bodyEmitsSeveralEvents_emitsThemBeforeTheOutput() {
    // Arrange
    val fn =
      FunctionNode(name = "fn") { _, _ ->
        emit(Event(author = "", content = Content.fromText("model", "event 1")))
        emit(Event(author = "", content = Content.fromText("model", "event 2")))
        "final"
      }
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, fn)))

    // Act
    val events = runWorkflow(workflow)

    // Assert
    assertEquals(
      listOf("event 1", "event 2"),
      events.filter { it.content != null }.map { it.content?.parts?.single()?.text },
    )
    assertEquals("final", events.single { it.output != null }.output)
  }

  @Test
  fun workflow_stateBinding_bindsParametersFromState() {
    // Arrange: under state binding each parameter is looked up in session state by name.
    val fn =
      FunctionNode(
        name = "fn",
        params =
          listOf(
            NodeParam.required("param1", Schema(type = Type.STRING)),
            NodeParam.optional("param2", "default2", Schema(type = Type.STRING)),
          ),
      ) { _, args ->
        "param1=${args["param1"]}, param2=${args["param2"]}"
      }
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, fn)))

    // Act
    val events = runWorkflowWithState(workflow, mapOf("param1" to "value1"))

    // Assert
    assertEquals("param1=value1, param2=default2", events.single().output)
  }

  @Test
  fun workflow_requiredStateParameterMissing_failsTheNode() {
    // Arrange
    val fn =
      FunctionNode(name = "fn", params = listOf(NodeParam.required("param1"))) { _, args ->
        args["param1"]
      }
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, fn)))

    // Act
    val error = assertFailsWith<IllegalArgumentException> { runWorkflow(workflow) }

    // Assert
    assertContains(error.message!!, "Missing value for parameter \"param1\"")
  }

  @Test
  fun workflow_nodeInputBinding_bindsParametersFromTheUpstreamOutput() {
    // Arrange: under node-input binding each parameter is looked up in the upstream output map.
    val producer = Emitter("producer", mapOf("p1" to "value1", "p2" to 100L))
    val consumer =
      FunctionNode(
        name = "consumer",
        params = listOf(NodeParam.required("p1"), NodeParam.required("p2")),
        parameterBinding = ParameterBinding.NODE_INPUT,
      ) { _, args ->
        "p1=${args["p1"]}, p2=${args["p2"]}"
      }
    val workflow =
      Workflow(name = "wf", edges = listOf(Edge(Start, producer), Edge(producer, consumer)))

    // Act
    val events = runWorkflow(workflow)

    // Assert
    assertEquals("p1=value1, p2=100", events.last().output)
  }

  @Test
  fun workflow_nodeInputParameterAbsent_usesItsDefault() {
    // Arrange
    val producer = Emitter("producer", mapOf("x" to 5L))
    val consumer =
      FunctionNode(
        name = "consumer",
        params = listOf(NodeParam.required("x"), NodeParam.optional("y", 10L)),
        parameterBinding = ParameterBinding.NODE_INPUT,
      ) { _, args ->
        (args["x"] as Long) + (args["y"] as Long)
      }
    val workflow =
      Workflow(name = "wf", edges = listOf(Edge(Start, producer), Edge(producer, consumer)))

    // Act
    val events = runWorkflow(workflow)

    // Assert
    assertEquals(15L, events.last().output)
  }

  @Test
  fun workflow_requiredNodeInputParameterMissing_failsTheNode() {
    // Arrange
    val producer = Emitter("producer", mapOf("x" to 5L))
    val consumer =
      FunctionNode(
        name = "consumer",
        params = listOf(NodeParam.required("x"), NodeParam.required("y")),
        parameterBinding = ParameterBinding.NODE_INPUT,
      ) { _, args ->
        args["x"]
      }
    val workflow =
      Workflow(name = "wf", edges = listOf(Edge(Start, producer), Edge(producer, consumer)))

    // Act
    val error = assertFailsWith<IllegalArgumentException> { runWorkflow(workflow) }

    // Assert
    assertContains(error.message!!, "Missing value for parameter \"y\"")
  }

  @Test
  fun workflow_reservedNodeInputParameterUnderStateBinding_receivesTheUpstreamOutput() {
    // Arrange: under state binding, a parameter named `node_input` receives the upstream output
    // itself rather than a state lookup, so a node can read what its predecessor produced.
    val producer = Emitter("producer", "upstream value")
    val consumer =
      FunctionNode(
        name = "consumer",
        params = listOf(NodeParam.required(FunctionNode.NODE_INPUT_PARAM)),
      ) { _, args ->
        "received: ${args[FunctionNode.NODE_INPUT_PARAM]}"
      }
    val workflow =
      Workflow(name = "wf", edges = listOf(Edge(Start, producer), Edge(producer, consumer)))

    // Act
    val events = runWorkflow(workflow)

    // Assert
    assertEquals("received: upstream value", events.last().output)
  }

  @Test
  fun workflow_contentInputForAStringParameter_bindsTheText() {
    // Arrange: the workflow's input arrives from START wrapped in Content; a string parameter
    // receives its text rather than the Content object.
    val fn =
      FunctionNode(
        name = "fn",
        params =
          listOf(NodeParam.required(FunctionNode.NODE_INPUT_PARAM, Schema(type = Type.STRING))),
      ) { _, args ->
        "got: ${args[FunctionNode.NODE_INPUT_PARAM]}"
      }
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, fn)))

    // Act
    val events = runBlocking {
      workflow
        .runAsync(testInvocationContext(userContent = Content.fromText("user", "hello")))
        .toList()
    }

    // Assert
    assertEquals("got: hello", events.single { it.output != null }.output)
  }

  @Test
  fun workflow_contentWithNonTextPartsForAStringParameter_bindsOnlyTheText() {
    // Arrange: the text parts are joined and the inline image is dropped.
    val fn =
      FunctionNode(
        name = "fn",
        params =
          listOf(NodeParam.required(FunctionNode.NODE_INPUT_PARAM, Schema(type = Type.STRING))),
      ) { _, args ->
        args[FunctionNode.NODE_INPUT_PARAM]
      }
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, fn)))
    val input =
      Content(
        role = "user",
        parts =
          listOf(
            Part(text = "Hello "),
            Part(inlineData = Blob(mimeType = "image/png", data = byteArrayOf(1, 2, 3))),
            Part(text = "World"),
          ),
      )

    // Act
    val events = runBlocking {
      workflow.runAsync(testInvocationContext(userContent = input)).toList()
    }

    // Assert
    assertEquals("Hello World", events.single { it.output != null }.output)
  }

  @Test
  fun workflow_bodyEmitsContentThenReturns_producesProgressThenOutput() {
    // Arrange: like a generator yielding a Content and then a value.
    val fn =
      FunctionNode(name = "fn") { _, _ ->
        emit(Content.fromText("model", "working"))
        "final"
      }
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, fn)))

    // Act
    val events = runWorkflow(workflow)

    // Assert
    assertEquals(2, events.size)
    assertEquals("working", events.first().content?.text())
    assertNull(events.first().output)
    assertEquals("final", events.last().output)
  }

  @Test
  fun workflow_scalarForAListParameter_isRejected() {
    // Arrange
    val producer = Emitter("producer", mapOf("items" to "notalist"))
    val consumer =
      FunctionNode(
        name = "consumer",
        params =
          listOf(
            NodeParam.required(
              "items",
              Schema(type = Type.ARRAY, items = Schema(type = Type.STRING)),
            )
          ),
        parameterBinding = ParameterBinding.NODE_INPUT,
      ) { _, args ->
        args["items"]
      }
    val workflow =
      Workflow(name = "wf", edges = listOf(Edge(Start, producer), Edge(producer, consumer)))

    // Act
    val error = assertFailsWith<IllegalArgumentException> { runWorkflow(workflow) }

    // Assert
    assertContains(error.message!!, "validation error: items")
  }

  @Test
  fun workflow_failingBodyWithARetryConfig_isRerun() {
    // Arrange
    var attempts = 0
    val fn =
      FunctionNode(
        name = "fn",
        config =
          NodeConfig(
            retryConfig = RetryConfig(maxAttempts = 2, initialDelay = Duration.ZERO, jitter = 0.0)
          ),
      ) { _, _ ->
        attempts += 1
        check(attempts > 1) { "transient failure" }
        "recovered"
      }
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, fn)))

    // Act
    val events = runWorkflow(workflow)

    // Assert
    assertEquals(2, attempts)
    assertEquals("recovered", events.single { it.output != null }.output)
  }

  @Test
  fun workflow_stateWrittenViaContext_ridesOnTheOutputEvent() {
    // Arrange
    val fn =
      FunctionNode(name = "fn") { ctx, _ ->
        ctx.updateState("user_request", "build a tracker app")
        "done"
      }
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, fn)))

    // Act
    val events = runWorkflow(workflow)

    // Assert
    val event = events.single { it.output != null }
    assertEquals("done", event.output)
    assertEquals("build a tracker app", event.actions.stateDelta["user_request"])
  }

  @Test
  fun workflow_stateWrittenWithoutOutput_stillRidesOnAnEvent() {
    // Arrange: a body that only writes state returns no output, but the state change still rides on
    // an emitted event.
    val fn =
      FunctionNode(name = "fn") { ctx, _ ->
        ctx.updateState("my_key", "my_value")
        null
      }
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, fn)))

    // Act
    val events = runWorkflow(workflow)

    // Assert
    val event = events.single()
    assertNull(event.output)
    assertEquals("my_value", event.actions.stateDelta["my_key"])
  }

  @Test
  fun workflow_stateWrittenByANode_isVisibleDownstream() {
    // Arrange: driven through a runner, which applies each event's state delta to the session, so a
    // later node reads what an earlier one wrote.
    val writer = FunctionNode(name = "writer") { ctx, _ -> ctx.updateState("shared", "written") }
    val reader =
      FunctionNode(name = "reader", params = listOf(NodeParam.required("shared"))) { _, args ->
        "read: ${args["shared"]}"
      }
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, writer), Edge(writer, reader)))

    // Act
    val events = runWorkflowThroughRunner(workflow)

    // Assert
    assertEquals("read: written", events.single { it.nodeInfo?.path == "wf@1/reader@1" }.output)
  }

  @Test
  fun workflow_bodySelectsARouteWithoutOutput_emitsTheRoute() {
    // Arrange: a node that signals a route but produces no output still emits an event carrying the
    // route.
    val fn =
      FunctionNode(name = "fn") { ctx, _ ->
        ctx.routes = listOf(Route.Tag("some_route"))
        null
      }
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, fn)))

    // Act
    val events = runWorkflow(workflow)

    // Assert
    val event = events.single()
    assertNull(event.output)
    assertEquals(listOf(Route.Tag("some_route")), event.actions.route)
  }

  @Test
  fun workflow_stringOutputForAnIntegerOutputSchema_isRejected() {
    // Arrange: the returned string field is not converted to the declared integer.
    val schema =
      Schema(
        type = Type.OBJECT,
        properties = mapOf("value" to Schema(type = Type.INTEGER)),
        required = listOf("value"),
      )
    val fn = FunctionNode(name = "fn", outputSchema = schema) { _, _ -> mapOf("value" to "42") }
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, fn)))

    // Act
    val error = assertFailsWith<IllegalArgumentException> { runWorkflow(workflow) }

    // Assert
    assertContains(error.message!!, "validation error")
  }

  @Test
  fun workflow_outputNotMatchingTheOutputSchema_isRejected() {
    // Arrange
    val schema =
      Schema(
        type = Type.OBJECT,
        properties = mapOf("value" to Schema(type = Type.INTEGER)),
        required = listOf("value"),
      )
    val fn = FunctionNode(name = "fn", outputSchema = schema) { _, _ -> mapOf("wrong" to 1L) }
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, fn)))

    // Act
    val error = assertFailsWith<IllegalArgumentException> { runWorkflow(workflow) }

    // Assert
    assertContains(error.message!!, "validation error")
  }

  @Test
  fun workflow_stringInputForAnIntegerInputSchema_isRejected() {
    // Arrange: the input schema validates the upstream output before the parameters bind from it.
    val schema =
      Schema(
        type = Type.OBJECT,
        properties = mapOf("value" to Schema(type = Type.INTEGER)),
        required = listOf("value"),
      )
    val producer = Emitter("producer", mapOf("value" to "5"))
    val consumer =
      FunctionNode(
        name = "consumer",
        inputSchema = schema,
        params = listOf(NodeParam.required("value")),
        parameterBinding = ParameterBinding.NODE_INPUT,
      ) { _, args ->
        args["value"]
      }
    val workflow =
      Workflow(name = "wf", edges = listOf(Edge(Start, producer), Edge(producer, consumer)))

    // Act
    val error = assertFailsWith<IllegalArgumentException> { runWorkflow(workflow) }

    // Assert
    assertContains(error.message!!, "validation error")
  }

  @Test
  fun workflow_jsonStringInputUnderAnObjectInputSchema_isRejected() {
    // Arrange: only Content is parsed as JSON; a String stays a String, which an object rejects.
    val schema =
      Schema(
        type = Type.OBJECT,
        properties = mapOf("value" to Schema(type = Type.INTEGER)),
        required = listOf("value"),
      )
    val producer = Emitter("producer", "{\"value\": 5}")
    val consumer =
      FunctionNode(
        name = "consumer",
        inputSchema = schema,
        params = listOf(NodeParam.required("value", Schema(type = Type.INTEGER))),
        parameterBinding = ParameterBinding.NODE_INPUT,
      ) { _, args ->
        args["value"]
      }
    val workflow =
      Workflow(name = "wf", edges = listOf(Edge(Start, producer), Edge(producer, consumer)))

    // Act
    val error = assertFailsWith<IllegalArgumentException> { runWorkflow(workflow) }

    // Assert
    assertContains(error.message!!, "validation error")
  }

  @Test
  fun workflow_jsonContentInputUnderAnObjectInputSchema_bindsItsFields() {
    // Arrange: the workflow's input arrives from START as Content carrying JSON text.
    val schema =
      Schema(
        type = Type.OBJECT,
        properties = mapOf("value" to Schema(type = Type.INTEGER)),
        required = listOf("value"),
      )
    val fn =
      FunctionNode(
        name = "fn",
        inputSchema = schema,
        params = listOf(NodeParam.required(FunctionNode.NODE_INPUT_PARAM, schema)),
      ) { _, args ->
        args[FunctionNode.NODE_INPUT_PARAM]
      }
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, fn)))

    // Act
    val events = runBlocking {
      workflow
        .runAsync(testInvocationContext(userContent = Content.fromText("user", "{\"value\": 5}")))
        .toList()
    }

    // Assert
    assertEquals(mapOf("value" to 5L), events.single { it.output != null }.output)
  }

  @Test
  fun description_notGiven_isEmpty() {
    // Act + Assert
    assertEquals("", FunctionNode(name = "fn") { _, _ -> null }.description)
  }

  /** A [Context] with session [state], for driving [Node.runNode] directly. */
  private fun nodeContext(node: Node, state: Map<String, Any> = emptyMap()): Context =
    Context(
      invocationContext = testInvocationContext(session = testSession().copy(state = State(state))),
      node = node,
      eventSink = EventSink {},
    )
}
