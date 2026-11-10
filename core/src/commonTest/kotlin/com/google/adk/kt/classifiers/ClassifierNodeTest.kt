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

@file:OptIn(
  ExperimentalClassifierApi::class,
  ExperimentalWorkflowApi::class,
  AdkJavaInteropApi::class,
)

package com.google.adk.kt.classifiers

import com.google.adk.kt.agents.Context
import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.annotations.ExperimentalClassifierApi
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.apps.App
import com.google.adk.kt.events.Event
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.testing.testInvocationContext
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import com.google.adk.kt.workflow.Edge
import com.google.adk.kt.workflow.GraphValidationException
import com.google.adk.kt.workflow.Node
import com.google.adk.kt.workflow.NodeConfig
import com.google.adk.kt.workflow.NodeExecutionException
import com.google.adk.kt.workflow.RetryConfig
import com.google.adk.kt.workflow.Route
import com.google.adk.kt.workflow.Start
import com.google.adk.kt.workflow.Workflow
import com.google.adk.kt.workflow.WorkflowConfigurationException
import com.google.adk.kt.workflow.edges
import com.google.adk.kt.workflow.node
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ClassifierNodeTest {

  private val triage =
    Choice(
      instructions = "Route this ticket.",
      criteria = mapOf("billing" to "Invoices or refunds", "technical" to "Bugs"),
    )
  private val billing = Sink("billing")
  private val technical = Sink("technical")

  /** Answers every question with [answer], or with nothing when it is null, and records calls. */
  private class FixedClassifier(private val answer: Answer?) : Classifier {
    val calls = mutableListOf<ClassifierRequest>()
    override val name = "fixed"

    override suspend fun classify(request: ClassifierRequest): ClassifierResponse {
      calls += request
      val answers = if (answer == null) emptyMap() else request.questions.mapValues { answer }
      return ClassifierResponse(answers = answers, modelVersion = name)
    }
  }

  /** Records the inputs it receives and produces no output. */
  private class Sink(override val name: String) : Node {
    val inputs = mutableListOf<Any?>()

    override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
      inputs += nodeInput
    }
  }

  /** Emits [value] as its output, so a null value is no output. */
  private class Emit(private val value: Any?) : Node {
    override val name = "src"

    override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow { emit(value) }
  }

  private val billingChoice =
    ChoiceAnswer(choice = "billing", probabilities = mapOf("billing" to 0.9, "technical" to 0.1))
  private val technicalChoice =
    ChoiceAnswer(choice = "technical", probabilities = mapOf("billing" to 0.1, "technical" to 0.9))

  private fun triageNode(answer: Answer? = billingChoice, answerKey: String? = null) =
    ClassifierNode(
      name = "triage",
      classifier = FixedClassifier(answer),
      question = triage,
      answerKey = answerKey,
    )

  private fun calls(node: ClassifierNode) = (node.classifier as FixedClassifier).calls

  /** START -> [node], with `billing` and `technical` routed to their sinks. */
  private fun triageWorkflow(node: ClassifierNode) =
    Workflow(
      name = "wf",
      edges =
        edges {
          Start then node
          node route
            {
              "billing" then billing
              "technical" then technical
            }
        },
    )

  /** START -> a source emitting [input] -> [node] -> [sink], every route leading to [sink]. */
  private fun sourcedWorkflow(input: Any?, node: ClassifierNode, sink: Node = Sink("sink")) =
    Workflow(
      name = "wf",
      edges =
        edges {
          val source = Emit(input)
          Start then source
          source then node
          node then sink
        },
    )

  /** Runs [workflow] as the invocation root on [message] and returns the events it emits. */
  private fun run(workflow: Workflow, message: String = "go"): List<Event> = runBlocking {
    workflow.runAsync(testInvocationContext(userContent = userMessage(message))).toList()
  }

  /** Runs [workflow] through [InMemoryRunner], so state reaches the session, which it returns. */
  private fun runAsApp(workflow: Workflow): Session = runBlocking {
    val runner = InMemoryRunner(app = App(appName = "app", rootNode = workflow))
    val unused =
      runner
        .runAsync(userId = "user", sessionId = "session", newMessage = userMessage("go"))
        .toList()
    checkNotNull(runner.sessionService.getSession(SessionKey("app", "user", "session")))
  }

  private fun runBinaryGate(probability: Double, threshold: Double = 0.6): Pair<Sink, Sink> {
    val node =
      ClassifierNode(
        name = "gate",
        classifier = FixedClassifier(BinaryAnswer(probability)),
        question = Binary(instructions = "Is this risky?"),
        threshold = threshold,
      )
    val yes = Sink("yes")
    val no = Sink("no")
    val workflow =
      Workflow(
        name = "wf",
        edges =
          edges {
            Start then node
            node route
              {
                on(true) then yes
                on(false) then no
              }
          },
      )
    val unused = run(workflow)
    return yes to no
  }

  private fun assertClassifiedAndForwarded(input: Any, expected: ClassifierInput) {
    val node = triageNode()
    val sink = Sink("sink")
    val unused = run(sourcedWorkflow(input, node, sink))
    assertEquals(expected, calls(node).single().input)
    assertEquals(listOf<Any?>(input), sink.inputs)
  }

  private tailrec fun nestedLists(depth: Int, value: Any): Any =
    if (depth == 0) value else nestedLists(depth - 1, listOf(value))

  private fun dispatchedTriageEvent(input: ClassifierInput, node: ClassifierNode): Event =
    run(
        Workflow(
          name = "wf",
          edges =
            edges {
              // Context.runNode requires rerunOnResume = true on the calling node.
              val dispatch =
                node<Any?, Any?>("dispatch", rerunOnResume = true) { ctx, _ ->
                  ctx.runNode(node, nodeInput = input)
                }
              Start then dispatch
            },
        )
      )
      .single { it.nodeInfo?.path == "wf@1/dispatch@1/triage@1" }

  private fun assertInvalidInputFails(input: Any, secret: String = "s3cret-ticket-payload") {
    val node = triageNode()
    val error = assertFailsWith<NodeExecutionException> { run(sourcedWorkflow(input, node)) }
    assertEquals(emptyList(), calls(node))
    assertFalse(secret in error.message.orEmpty())
    assertFalse(secret in error.cause?.message.orEmpty())
  }

  private fun assertAnswerFails(answer: Answer?, expectedMessage: String) {
    val error =
      assertFailsWith<NodeExecutionException> { run(sourcedWorkflow("x", triageNode(answer))) }
    assertEquals(expectedMessage, error.message)
  }

  private fun assertInvalidThresholdFails(threshold: Double) {
    val error =
      assertFailsWith<WorkflowConfigurationException> {
        ClassifierNode(
          name = "gate",
          classifier = FixedClassifier(null),
          question = Binary(),
          threshold = threshold,
        )
      }
    assertEquals("ClassifierNode 'gate' needs a threshold in [0, 1].", error.message)
  }

  @Test
  fun choice_routesToTheChosenLabelAndPassesTheInputThrough() {
    val node = triageNode(technicalChoice)
    val message = userMessage("The API returns 500")

    val unused = run(triageWorkflow(node), message = "The API returns 500")

    assertEquals(emptyList(), billing.inputs)
    assertEquals(listOf<Any?>(message), technical.inputs)
    assertEquals(listOf(ClassifierRequest(message, mapOf("route" to triage))), calls(node))
  }

  @Test
  fun choice_emitsOneEventWithTheOutputTheRouteAndTheAnswer() {
    val node = triageNode(answerKey = "triage_answer")

    val event = run(triageWorkflow(node)).single { it.nodeInfo?.path == "wf@1/triage@1" }

    assertEquals(userMessage("go"), event.output)
    assertEquals(listOf(Route.Tag("billing")), event.actions.route)
    assertEquals(setOf("triage_answer"), event.actions.stateDelta.keys)
  }

  @Test
  fun binary_probabilityAboveThreshold_routesTrue() {
    val (yes, no) = runBinaryGate(probability = 0.7)

    assertEquals(1, yes.inputs.size)
    assertEquals(0, no.inputs.size)
  }

  @Test
  fun binary_probabilityAtThreshold_routesTrue() {
    val (yes, no) = runBinaryGate(probability = 0.6)

    assertEquals(1, yes.inputs.size)
    assertEquals(0, no.inputs.size)
  }

  @Test
  fun binary_probabilityBelowThreshold_routesFalse() {
    val (yes, no) = runBinaryGate(probability = 0.59)

    assertEquals(0, yes.inputs.size)
    assertEquals(1, no.inputs.size)
  }

  @Test
  fun binary_defaultThresholdIsOneHalf() {
    val node =
      ClassifierNode(name = "gate", classifier = FixedClassifier(null), question = Binary())

    assertEquals(0.5, node.threshold)
  }

  @Test
  fun answerKey_storesTheAnswerInSessionState() {
    val node = triageNode(answerKey = "triage_answer")

    val session = runAsApp(triageWorkflow(node))

    assertEquals(
      mapOf(
        "type" to "choice",
        "choice" to "billing",
        "probabilities" to mapOf("billing" to 0.9, "technical" to 0.1),
        "confidence" to null,
      ),
      session.state["triage_answer"],
    )
  }

  @Test
  fun noAnswerKey_leavesStateUntouched() {
    val session = runAsApp(triageWorkflow(triageNode()))

    assertEquals(emptyMap(), session.state.toMap())
  }

  @Test
  fun jsonMapInput_isClassifiedAndForwardedAsIs() {
    assertClassifiedAndForwarded(
      mapOf("subject" to "x", "tags" to listOf("a", "b")),
      ClassifierContent.parse("""{"subject": "x", "tags": ["a", "b"]}"""),
    )
  }

  @Test
  fun jsonListInput_isClassifiedAndForwardedAsIs() {
    assertClassifiedAndForwarded(
      listOf("first", mapOf("k" to 1)),
      ClassifierContent.parse("""["first", {"k": 1}]"""),
    )
  }

  @Test
  fun stringAndJsonPrimitiveTextInputs_areClassifiedAndForwardedAsIs() {
    assertClassifiedAndForwarded("refund please", ClassifierContent.Text("refund please"))
    assertClassifiedAndForwarded(JsonPrimitive("refund"), ClassifierContent.Text("refund"))
  }

  @Test
  fun jsonObjectAndArrayElementInputs_areClassifiedAndForwardedAsIs() {
    val jsonObject = buildJsonObject {
      put("subject", "refund")
      put("note", JsonNull)
    }
    val jsonArray = buildJsonArray {
      add("first")
      add(2)
    }

    assertClassifiedAndForwarded(jsonObject, ClassifierContent.JsonObject(jsonObject))
    assertClassifiedAndForwarded(jsonArray, ClassifierContent.JsonArray(jsonArray))
  }

  @Test
  fun classifierContentTextInput_isPassedToTheClassifierAsIs() {
    val input = ClassifierContent.Text("refund please")
    val node = triageNode()

    val event = dispatchedTriageEvent(input, node)

    assertSame(input, calls(node).single().input)
    assertEquals("refund please", event.output)
    assertEquals(listOf(Route.Tag("billing")), event.actions.route)
  }

  @Test
  fun classifierContentJsonObjectInput_isPassedToTheClassifierAsIs() {
    val input = ClassifierContent.parse("""{"subject": "x"}""") as ClassifierContent.JsonObject
    val node = triageNode()

    val event = dispatchedTriageEvent(input, node)

    assertSame(input, calls(node).single().input)
    assertEquals(input.json, event.output)
  }

  @Test
  fun classifierContentJsonArrayInput_isPassedToTheClassifierAsIs() {
    val input = ClassifierContent.parse("""["a", 1]""") as ClassifierContent.JsonArray
    val node = triageNode()

    val event = dispatchedTriageEvent(input, node)

    assertSame(input, calls(node).single().input)
    assertEquals(input.json, event.output)
  }

  @Test
  fun classifierInputContent_isPassedToTheClassifierAsIs() {
    val message = userMessage("refund")
    val input = ClassifierInput.Content(message)
    val node = triageNode()

    val event = dispatchedTriageEvent(input, node)

    assertSame(input, calls(node).single().input)
    assertEquals(message, event.output)
    assertEquals(listOf(Route.Tag("billing")), event.actions.route)
  }

  @Test
  fun functionNodeReturningContent_isClassifiedAsContentAndForwarded() {
    val content =
      Content(
        role = Role.USER,
        parts =
          listOf(
            Part(text = "Damaged item"),
            Part(inlineData = Blob(mimeType = "image/png", data = byteArrayOf(1, 2, 3))),
          ),
      )
    val prep = node<Any?, Content>("prep") { _, _ -> content }
    val received = mutableListOf<Content>()
    val handle = node("handle") { _, input: Content -> received += input }
    val node = triageNode()
    val workflow =
      Workflow(
        name = "wf",
        edges =
          edges {
            Start then prep
            prep then node
            node route
              {
                "billing" then handle
                "technical" then technical
              }
          },
      )

    val unused = run(workflow)

    assertEquals(ClassifierInput.Content(content), calls(node).single().input)
    assertEquals(listOf(content), received)
    assertEquals(emptyList(), technical.inputs)
  }

  @Test
  fun mapsWithRoleOrNonContentParts_areClassifiedAsJsonObjects() {
    assertClassifiedAndForwarded(
      mapOf("role" to "admin"),
      ClassifierContent.parse("""{"role": "admin"}"""),
    )
    assertClassifiedAndForwarded(
      mapOf("parts" to emptyList<Any>()),
      ClassifierContent.parse("""{"parts": []}"""),
    )
    assertClassifiedAndForwarded(
      mapOf("parts" to listOf(emptyMap<String, Any?>())),
      ClassifierContent.parse("""{"parts": [{}]}"""),
    )
    assertClassifiedAndForwarded(
      mapOf("parts" to listOf("bolt", "nut")),
      ClassifierContent.parse("""{"parts": ["bolt", "nut"]}"""),
    )
    assertClassifiedAndForwarded(
      mapOf("parts" to listOf(mapOf("sku" to "B1"))),
      ClassifierContent.parse("""{"parts": [{"sku": "B1"}]}"""),
    )
  }

  @Test
  fun nullInput_failsWithoutCallingClassifier() {
    val node = triageNode()

    val error = assertFailsWith<NodeExecutionException> { run(sourcedWorkflow(null, node)) }

    assertEquals("ClassifierNode 'triage' received no input to classify.", error.message)
    assertEquals(emptyList(), calls(node))
  }

  @Test
  fun scalarNumberInput_failsWithoutCallingClassifier() {
    assertInvalidInputFails(42)
    assertInvalidInputFails(JsonPrimitive(42))
  }

  @Test
  fun invalidJsonMapInput_failsWithoutEchoingPayload() {
    val secret = "s3cret-ticket-payload"

    assertInvalidInputFails(mapOf(secret to Double.NaN), secret)
    assertInvalidInputFails(mapOf(1 to secret), secret)
    assertInvalidInputFails(mapOf(secret to userMessage(secret)), secret)
  }

  @Test
  fun invalidJsonListInput_failsWithoutEchoingPayload() {
    val secret = "s3cret-ticket-payload"

    assertInvalidInputFails(listOf(secret, Double.NaN), secret)
    assertInvalidInputFails(nestedLists(MAX_JSON_CONTENT_DEPTH + 1, secret), secret)
  }

  @Test
  fun invalidJsonElementInput_failsWithoutEchoingPayload() {
    val secret = "s3cret-ticket-payload"

    assertInvalidInputFails(buildJsonObject { put(secret, Double.NaN) }, secret)
    assertInvalidInputFails(buildJsonArray { add(Double.POSITIVE_INFINITY) }, secret)
  }

  @Test
  fun missingAnswer_failsWithNodeExecutionException() {
    assertAnswerFails(null, "Classifier for node 'triage' returned no answer.")
  }

  @Test
  fun unknownChoiceLabel_failsWithoutEchoingLabel() {
    assertAnswerFails(
      billingChoice.copy(choice = "sales"),
      "Classifier for node 'triage' chose a label that is not one of its question's labels.",
    )
  }

  @Test
  fun wrongAnswerType_failsWithNodeExecutionException() {
    assertAnswerFails(
      BinaryAnswer(0.5),
      "Classifier for node 'triage' returned a BinaryAnswer to a Choice question.",
    )
    assertAnswerFails(
      ScoreAnswer(score = 1.0, probabilities = mapOf(0 to 0.0, 1 to 1.0)),
      "Classifier for node 'triage' returned a ScoreAnswer to a Choice question.",
    )
  }

  @Test
  fun refusalAnswer_failsWithNodeExecutionException() {
    assertAnswerFails(
      RefusalAnswer,
      "Classifier for node 'triage' declined to answer, so the node has no route to take.",
    )
  }

  @Test
  fun nodeExecutionException_isRetriedByName() {
    val node =
      ClassifierNode(
        name = "triage",
        classifier = FixedClassifier(RefusalAnswer),
        question = triage,
        config =
          NodeConfig(
            retryConfig =
              RetryConfig(
                maxAttempts = 2,
                exceptions = listOf("NodeExecutionException"),
                jitter = 0.0,
              )
          ),
      )

    val unused = assertFailsWith<NodeExecutionException> { run(triageWorkflow(node)) }

    assertEquals(2, calls(node).size)
  }

  @Test
  fun edgeForAnUnknownRoute_failsGraphValidation() {
    val node = triageNode()

    val error =
      assertFailsWith<GraphValidationException> {
        Workflow(
          name = "wf",
          edges =
            edges {
              Start then node
              node route
                {
                  "biling" then billing
                  "technical" then technical
                }
            },
        )
      }

    assertEquals(
      "Graph validation failed. ClassifierNode 'triage' has edges for routes it never emits: " +
        "[\"biling\"]. Its routes are [\"billing\", \"technical\"].",
      error.message,
    )
  }

  @Test
  fun labelWithoutAnEdge_failsGraphValidation() {
    val node = triageNode()

    val error =
      assertFailsWith<GraphValidationException> {
        Workflow(
          name = "wf",
          edges =
            edges {
              Start then node
              node route { "billing" then billing }
            },
        )
      }

    assertEquals(
      "Graph validation failed. ClassifierNode 'triage' has no edge for routes [\"technical\"], " +
        "so the branch would end when the classifier picks one. Add an edge for each, or a " +
        "default or unconditional edge.",
      error.message,
    )
  }

  @Test
  fun binaryWithStringRoutes_failsGraphValidation() {
    val node =
      ClassifierNode(name = "gate", classifier = FixedClassifier(null), question = Binary())

    val error =
      assertFailsWith<GraphValidationException> {
        Workflow(
          name = "wf",
          edges =
            edges {
              Start then node
              node route
                {
                  "true" then billing
                  "false" then technical
                }
            },
        )
      }

    assertContains(
      error.message.orEmpty(),
      "never emits: [\"false\", \"true\"]. Its routes are [false, true].",
    )
  }

  @Test
  fun defaultEdge_coversLabelsWithoutAnEdge() {
    val node = triageNode(technicalChoice)
    val workflow =
      Workflow(
        name = "wf",
        edges =
          edges {
            Start then node
            node route
              {
                "billing" then billing
                otherwise(technical)
              }
          },
      )

    val unused = run(workflow)

    assertEquals(emptyList(), billing.inputs)
    assertEquals(1, technical.inputs.size)
  }

  @Test
  fun unconditionalEdge_coversLabelsWithoutAnEdge() {
    val node = triageNode(technicalChoice)
    val workflow =
      Workflow(
        name = "wf",
        edges =
          edges {
            Start then node
            node route { "billing" then billing }
            node then technical
          },
      )

    val unused = run(workflow)

    assertEquals(emptyList(), billing.inputs)
    assertEquals(1, technical.inputs.size)
  }

  @Test
  fun onlyUnconditionalEdges_skipRouteValidation() {
    val node = triageNode()
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, node), Edge(node, billing)))

    val unused = run(workflow)

    assertEquals(1, billing.inputs.size)
  }

  @Test
  fun noOutgoingEdges_skipsRouteValidation() {
    val node = triageNode(answerKey = "triage_answer")

    val session = runAsApp(Workflow(name = "wf", edges = edges { Start then node }))

    assertEquals("billing", (session.state["triage_answer"] as Map<*, *>)["choice"])
  }

  @Test
  fun scoreQuestion_throwsWorkflowConfigurationException() {
    val error =
      assertFailsWith<WorkflowConfigurationException> {
        ClassifierNode(
          name = "rate",
          classifier = FixedClassifier(null),
          question = Score(instructions = null, criteria = listOf("low", "high")),
        )
      }

    assertEquals(
      "ClassifierNode 'rate' cannot route on a Score question; use a Choice or a Binary.",
      error.message,
    )
  }

  @Test
  fun thresholdWithChoiceQuestion_throwsWorkflowConfigurationException() {
    val error =
      assertFailsWith<WorkflowConfigurationException> {
        ClassifierNode(
          name = "triage",
          classifier = FixedClassifier(null),
          question = triage,
          threshold = 0.5,
        )
      }

    assertEquals(
      "ClassifierNode 'triage' takes a threshold only with a Binary question; a Choice always " +
        "routes to the label the classifier chose.",
      error.message,
    )
  }

  @Test
  fun thresholdOutsideZeroToOne_throwsWorkflowConfigurationException() {
    assertInvalidThresholdFails(-0.1)
    assertInvalidThresholdFails(1.5)
    assertInvalidThresholdFails(Double.NaN)
  }

  @Test
  fun defaultRouteSentinelAsLabel_throwsWorkflowConfigurationException() {
    val error =
      assertFailsWith<WorkflowConfigurationException> {
        ClassifierNode(
          name = "triage",
          classifier = FixedClassifier(null),
          question = Choice(criteria = mapOf("billing" to null, "__DEFAULT__" to null)),
        )
      }

    assertContains(error.message.orEmpty(), "reserved for the default route")
  }

  @Test
  fun invalidNodeName_throwsIllegalArgumentException() {
    assertFailsWith<IllegalArgumentException> {
      ClassifierNode(name = "a.b", classifier = FixedClassifier(null), question = triage)
    }
  }

  @Test
  fun builder_setsEveryProperty() {
    val classifier = FixedClassifier(null)
    val config = NodeConfig(retryConfig = RetryConfig(maxAttempts = 2))
    val schema = Schema(type = Type.STRING)

    val built =
      ClassifierNode.builder()
        .name("gate")
        .classifier(classifier)
        .question(Binary())
        .threshold(0.7)
        .answerKey("gate_answer")
        .description("Gates risky tickets.")
        .rerunOnResume(true)
        .waitForOutput(true)
        .config(config)
        .inputSchema(schema)
        .outputSchema(schema)
        .stateSchema(Schema(type = Type.OBJECT))
        .build()

    assertEquals("gate", built.name)
    assertSame(classifier, built.classifier)
    assertEquals(Binary(), built.question)
    assertEquals(0.7, built.threshold)
    assertEquals("gate_answer", built.answerKey)
    assertEquals("Gates risky tickets.", built.description)
    assertTrue(built.rerunOnResume)
    assertTrue(built.waitForOutput)
    assertEquals(config, built.config)
    assertEquals(schema, built.inputSchema)
    assertEquals(schema, built.outputSchema)
    assertEquals(Schema(type = Type.OBJECT), built.stateSchema)
  }

  @Test
  fun builder_defaultsMatchTheConstructor() {
    val constructed =
      ClassifierNode(name = "triage", classifier = FixedClassifier(null), question = triage)
    val built =
      ClassifierNode.builder()
        .name("triage")
        .classifier(FixedClassifier(null))
        .question(triage)
        .build()

    assertEquals(0.5, constructed.threshold)
    assertNull(constructed.answerKey)
    assertEquals("", constructed.description)
    assertFalse(constructed.rerunOnResume)
    assertFalse(constructed.waitForOutput)
    assertEquals(NodeConfig(), constructed.config)
    assertNull(constructed.inputSchema)
    assertNull(constructed.outputSchema)
    assertNull(constructed.stateSchema)

    assertEquals(constructed.threshold, built.threshold)
    assertEquals(constructed.answerKey, built.answerKey)
    assertEquals(constructed.description, built.description)
    assertEquals(constructed.rerunOnResume, built.rerunOnResume)
    assertEquals(constructed.waitForOutput, built.waitForOutput)
    assertEquals(constructed.config, built.config)
    assertEquals(constructed.inputSchema, built.inputSchema)
    assertEquals(constructed.outputSchema, built.outputSchema)
    assertEquals(constructed.stateSchema, built.stateSchema)
  }

  @Test
  fun builder_missingRequiredProperty_throwsIllegalStateException() {
    val classifier = FixedClassifier(null)

    assertFailsWith<IllegalStateException> {
      ClassifierNode.builder().classifier(classifier).question(triage).build()
    }
    assertFailsWith<IllegalStateException> {
      ClassifierNode.builder().name("triage").question(triage).build()
    }
    assertFailsWith<IllegalStateException> {
      ClassifierNode.builder().name("triage").classifier(classifier).build()
    }
  }
}
