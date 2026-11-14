/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:OptIn(AdkJavaInteropApi::class, ExperimentalWorkflowApi::class)

package com.google.adk.kt.workflow

import com.google.adk.kt.agents.AgentMode
import com.google.adk.kt.agents.BaseAgent
import com.google.adk.kt.agents.Context
import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.LoopAgent
import com.google.adk.kt.agents.ParallelAgent
import com.google.adk.kt.agents.RunConfig
import com.google.adk.kt.agents.SequentialAgent
import com.google.adk.kt.agents.StreamingMode
import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.apps.App
import com.google.adk.kt.events.Event
import com.google.adk.kt.interop.InteropContextView
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.telemetry.Telemetry
import com.google.adk.kt.telemetry.otel.OtelTracer
import com.google.adk.kt.testing.DummyAgent
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.DummyTool
import com.google.adk.kt.testing.modelFunctionCallResponse
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.textAgent
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.tools.AgentTool
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.google.errorprone.annotations.CanIgnoreReturnValue
import com.google.errorprone.annotations.MustBeClosed
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer as OpenTelemetryTracer
import io.opentelemetry.context.Context as OpenTelemetryContext
import io.opentelemetry.context.Scope
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import java.util.Collections
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Spans a workflow run emits, mirroring Python ADK's `test_node_tracing.py`. */
@RunWith(JUnit4::class)
class NodeTracingTest {

  private val exported: MutableList<SpanData> = Collections.synchronizedList(mutableListOf())
  private val provider =
    SdkTracerProvider.builder()
      .addSpanProcessor(
        SimpleSpanProcessor.create(
          object : SpanExporter {
            override fun export(spans: Collection<SpanData>): CompletableResultCode {
              exported += spans
              return CompletableResultCode.ofSuccess()
            }

            override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()

            override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()
          }
        )
      )
      .build()

  @Before
  fun setUp() {
    Telemetry.setTracerForTest(OtelTracer(provider.get("test")))
  }

  @After
  fun tearDown() {
    Telemetry.resetTracer()
    provider.close()
  }

  @Test
  fun run_workflowWithAFunctionNode_nestsInvokeNodeUnderInvokeWorkflowUnderInvocation() {
    val greet = node<Any?, String>("greet") { _, _ -> "hello" }

    val session = runAsApp(Workflow("wf", edges = listOf(Edge(Start, greet))))

    val workflow = span("invoke_workflow wf")
    assertThat(workflow.parentSpanId).isEqualTo(span("invocation").spanId)
    assertThat(workflow.attributes.asMap().mapKeys { it.key.key })
      .containsExactly(
        "gen_ai.operation.name",
        "invoke_workflow",
        "gen_ai.conversation.id",
        SESSION_ID,
        "gen_ai.workflow.name",
        "wf",
      )
    val greetSpan = span("invoke_node greet")
    assertThat(greetSpan.parentSpanId).isEqualTo(workflow.spanId)
    assertThat(greetSpan.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
      .isEqualTo("invoke_node")
    assertThat(greetSpan.attributes[AttributeKey.stringKey("gen_ai.conversation.id")])
      .isEqualTo(SESSION_ID)
    val associated = greetSpan.attributes[ASSOCIATED_EVENT_IDS]
    assertThat(associated).isNotEmpty()
    assertThat(session.events.map { it.id }).containsAtLeastElementsIn(associated)
  }

  @Test
  fun run_nestedWorkflow_flagsOnlyTheInnerWorkflowAsNested() {
    val step = node<Any?, String>("step") { _, _ -> "done" }
    val inner = Workflow("inner", edges = listOf(Edge(Start, step)))

    runAsApp(Workflow("outer", edges = listOf(Edge(Start, inner))))

    val outer = span("invoke_workflow outer")
    val innerSpan = span("invoke_workflow inner")
    assertThat(outer.attributes[NESTED]).isNull()
    assertThat(innerSpan.attributes[NESTED]).isTrue()
    assertThat(innerSpan.parentSpanId).isEqualTo(outer.spanId)
    assertThat(span("invoke_node step").parentSpanId).isEqualTo(innerSpan.spanId)
  }

  @Test
  fun run_workflowStartedByANode_isNestedUnderThatNode() {
    val leaf = node<Any?, String>("leaf") { _, _ -> "done" }
    val inner = Workflow("inner", edges = listOf(Edge(Start, leaf)))
    val caller =
      node<Any?, Unit>("caller", rerunOnResume = true) { context, _ ->
        val unused = context.runNode(inner)
      }

    runAsApp(Workflow("outer", edges = listOf(Edge(Start, caller))))

    val innerSpan = span("invoke_workflow inner")
    assertThat(innerSpan.attributes[NESTED]).isTrue()
    assertThat(innerSpan.parentSpanId).isEqualTo(span("invoke_node caller").spanId)
  }

  @Test
  fun run_workflowStartedThroughJavaInterop_isNested() {
    val leaf = node<Any?, String>("leaf") { _, _ -> "done" }
    val inner = Workflow("inner", edges = listOf(Edge(Start, leaf)))
    val caller =
      node<Any?, Unit>("caller", rerunOnResume = true) { context, _ ->
        val unused = InteropContextView.of(context).runNode(inner).await()
      }

    runAsApp(Workflow("outer", edges = listOf(Edge(Start, caller))))

    assertThat(span("invoke_workflow inner").attributes[NESTED]).isTrue()
  }

  @Test
  fun run_workflowRootOfARunnerStartedByANode_isNested() {
    val leaf = node<Any?, String>("leaf") { _, _ -> "done" }
    val innerRunner = runnerFor(Workflow("inner", edges = listOf(Edge(Start, leaf))))
    val caller =
      node<Any?, Unit>("caller") { _, _ ->
        innerRunner
          .runAsync(userId = "user", sessionId = "inner", newMessage = userMessage("go"))
          .toList()
      }

    runAsApp(Workflow("outer", edges = listOf(Edge(Start, caller))))

    assertThat(span("invoke_workflow outer").attributes[NESTED]).isNull()
    assertThat(span("invoke_workflow inner").attributes[NESTED]).isTrue()
  }

  @Test
  fun run_agentNode_opensOnlyItsOwnInvokeAgentSpan() {
    runAsApp(Workflow("wf", edges = listOf(Edge(Start, DummyAgent(name = "helper")))))

    assertThat(exported.map { it.name }).doesNotContain("invoke_node helper")
    assertThat(span("invoke_agent helper").parentSpanId)
      .isEqualTo(span("invoke_workflow wf").spanId)
  }

  @Test
  fun run_agentNodeCallingATool_keepsEveryModelCallUnderItsAgent() {
    runAsApp(Workflow("wf", edges = listOf(Edge(Start, toolCallingAgent()))))

    assertModelCallsAndToolsUnderHelper(modelCalls = 3)
  }

  @Test
  fun run_agentAsRootNodeCallingATool_keepsEveryModelCallUnderItsAgent() {
    runAsApp(toolCallingAgent())

    assertModelCallsAndToolsUnderHelper(modelCalls = 3)
  }

  @Test
  fun run_appRootAgentCallingATool_keepsEveryModelCallUnderItsAgent() {
    InMemoryRunner(app = App(appName = APP, rootAgent = toolCallingAgent())).runTurn()

    assertModelCallsAndToolsUnderHelper(modelCalls = 3)
  }

  @Test
  fun run_agentAsRootNodeStreamingAToolCall_keepsEveryModelCallUnderItsAgent() {
    val model =
      DummyModel(
        "test-model",
        listOf(
          flowOf(
            LlmResponse(content = modelMessage("thinking"), partial = true),
            modelFunctionCallResponse("dummy_tool"),
          ),
          flowOf(
            LlmResponse(content = modelMessage("text "), partial = true),
            LlmResponse(content = modelMessage("text response")),
          ),
        ),
      )
    val agent = LlmAgent(name = "helper", model = model, tools = listOf(DummyTool(declares = true)))

    runAsApp(agent, RunConfig(streamingMode = StreamingMode.SSE))

    assertModelCallsAndToolsUnderHelper(modelCalls = 2)
  }

  @Test
  fun run_agentNodeCallingAnAgentTool_nestsTheToolsRunUnderItsCall() {
    val delegate =
      LlmAgent(
        name = "delegate",
        description = "Answers a request.",
        model =
          DummyModel.createSequential(
            "test-model",
            listOf(LlmResponse(content = modelMessage("answer"))),
          ),
      )
    val caller =
      LlmAgent(
        name = "helper",
        model =
          DummyModel.createSequential(
            "test-model",
            listOf(
              modelFunctionCallResponse("delegate", mapOf("request" to "question")),
              LlmResponse(content = modelMessage("done")),
            ),
          ),
        tools = listOf(AgentTool(delegate)),
      )

    runAsApp(Workflow("wf", edges = listOf(Edge(Start, caller))))

    val callerSpan = span("invoke_agent helper")
    val toolSpan = span("execute_tool delegate")
    assertThat(toolSpan.parentSpanId).isEqualTo(callerSpan.spanId)
    val toolInvocation = exported.single {
      it.name == "invocation" && it.parentSpanId == toolSpan.spanId
    }
    val delegateSpan = span("invoke_agent delegate")
    assertThat(delegateSpan.parentSpanId).isEqualTo(toolInvocation.spanId)
    assertThat(exported.filter { it.name == "call_llm" }.map { it.parentSpanId })
      .containsExactly(callerSpan.spanId, callerSpan.spanId, delegateSpan.spanId)
  }

  @Test
  fun run_singleTurnToolPausedForApproval_recordsNoFailure() {
    val approve = DummyTool("approve", isLongRunning = true, onRun = { _, _ -> Unit })
    val helper =
      LlmAgent(
        name = "helper",
        model =
          DummyModel.createSequential("test-model", listOf(modelFunctionCallResponse("approve"))),
        description = "A helper.",
        tools = listOf(approve),
        mode = AgentMode.SINGLE_TURN,
      )
    val root =
      LlmAgent(
        name = "root_agent",
        model =
          DummyModel.createSequential(
            "test-model",
            listOf(modelFunctionCallResponse("helper", mapOf("request" to "hi"))),
          ),
        subAgents = listOf(helper),
      )

    runAsApp(root)

    assertThat(exported.map { it.name }).contains("execute_tool helper")
    assertThat(exported.filter { it.attributes[ERROR_TYPE] != null }.map { it.name }).isEmpty()
    assertThat(exported.filter { span -> span.events.any { it.name == "exception" } }).isEmpty()
    assertThat(exported.map { it.status.statusCode }.toSet()).containsExactly(StatusCode.UNSET)
  }

  @Test
  fun run_sequentialAgentNode_nestsEverySubAgentUnderIt() {
    runAsApp(Workflow("wf", edges = listOf(Edge(Start, sequential("seq", "a", "b")))))

    assertThat(span("invoke_agent seq").parentSpanId).isEqualTo(span("invoke_workflow wf").spanId)
    assertAgentsUnder("seq", "a", "b")
  }

  @Test
  fun run_sequentialAgentNodeWhoseFirstSubAgentCallsATool_nestsBothUnderIt() {
    val seq = SequentialAgent("seq", subAgents = listOf(toolCallingAgent(), textAgent("b", "ok")))

    runAsApp(Workflow("wf", edges = listOf(Edge(Start, seq))))

    assertAgentsUnder("seq", "helper", "b")
    val helper = span("invoke_agent helper").spanId
    assertThat(exported.filter { it.name == "execute_tool dummy_tool" }.map { it.parentSpanId })
      .containsExactly(helper, helper)
  }

  @Test
  fun run_appRootSequentialAgentWhoseFirstSubAgentCallsATool_nestsBothUnderIt() {
    val seq = SequentialAgent("seq", subAgents = listOf(toolCallingAgent(), textAgent("b", "ok")))

    InMemoryRunner(app = App(appName = APP, rootAgent = seq)).runTurn()

    assertAgentsUnder("seq", "helper", "b")
  }

  @Test
  fun run_loopAgentNode_nestsEveryIterationUnderIt() {
    val loop =
      LoopAgent(
        "loop",
        maxIterations = 2,
        subAgents = listOf(textAgent("a", "ok"), textAgent("b", "ok")),
      )

    runAsApp(Workflow("wf", edges = listOf(Edge(Start, loop))))

    assertThat(exported.filter { it.name == "invoke_agent a" }).hasSize(2)
    assertAgentsUnder("loop", "a", "b")
  }

  @Test
  fun run_nestedSequentialAgentNode_nestsEachLevelUnderItsParent() {
    val outer =
      SequentialAgent(
        "outer",
        subAgents = listOf(sequential("inner", "a", "b"), textAgent("c", "ok")),
      )

    runAsApp(Workflow("wf", edges = listOf(Edge(Start, outer))))

    assertAgentsUnder("outer", "inner", "c")
    assertAgentsUnder("inner", "a", "b")
  }

  @Test
  fun run_parallelAgentNode_nestsSubAgentsUnderIt() {
    val parallel =
      ParallelAgent("par", subAgents = listOf(textAgent("a", "ok"), textAgent("b", "ok")))

    runAsApp(Workflow("wf", edges = listOf(Edge(Start, parallel))))

    assertAgentsUnder("par", "a", "b")
  }

  @Test
  fun run_agentNodeTransferringToASequentialAgent_keepsItsSubAgentsUnderIt() {
    val root =
      LlmAgent(
        name = "root",
        model =
          DummyModel.createSequential(
            "test-model",
            listOf(modelFunctionCallResponse("transfer_to_agent", mapOf("agent_name" to "seq"))),
          ),
        subAgents = listOf(sequential("seq", "a", "b")),
      )

    runAsApp(Workflow("wf", edges = listOf(Edge(Start, root))))

    assertAgentsUnder("seq", "a", "b")
  }

  @Test
  fun run_agentUnderAUserContextElement_nestsUnderTheUserSpan() {
    val wrapper = UserSpanAgent(sequential("seq", "a", "b"), provider.get("user"))

    runAsApp(Workflow("wf", edges = listOf(Edge(Start, wrapper))))

    assertThat(span("user_span").parentSpanId).isEqualTo(span("invoke_agent wrapper").spanId)
    assertThat(span("invoke_agent seq").parentSpanId).isEqualTo(span("user_span").spanId)
    assertAgentsUnder("seq", "a", "b")
  }

  @Test
  fun run_fannedOutAndDynamicChildren_nestUnderTheirCaller() {
    // Branches without output, since a workflow allows only one terminal output.
    val left = node<Any?, Unit>("left") { _, _ -> }
    val right = node<Any?, Unit>("right") { _, _ -> }
    val child = node<Any?, String>("child") { _, _ -> "c" }
    val caller =
      node<Any?, Unit>("caller", rerunOnResume = true) { context, _ ->
        val unused = context.runNode(child)
      }

    runAsApp(
      Workflow("wf", edges = listOf(Edge(Start, left), Edge(Start, right), Edge(Start, caller)))
    )

    val workflow = span("invoke_workflow wf")
    assertThat(span("invoke_node left").parentSpanId).isEqualTo(workflow.spanId)
    assertThat(span("invoke_node right").parentSpanId).isEqualTo(workflow.spanId)
    assertThat(span("invoke_node child").parentSpanId).isEqualTo(span("invoke_node caller").spanId)
  }

  @Test
  fun run_retriedNode_getsASpanPerAttemptAndTheFailedOneRecordsTheError() {
    var calls = 0
    val flaky =
      node<Any?, String>(
        "flaky",
        config = NodeConfig(RetryConfig(maxAttempts = 2, initialDelay = Duration.ZERO)),
      ) { _, _ ->
        calls += 1
        if (calls == 1) throw TestNodeException("first try")
        "ok"
      }

    runAsApp(Workflow("wf", edges = listOf(Edge(Start, flaky))))

    val attempts =
      exported.filter { it.name == "invoke_node flaky" }.sortedBy { it.startEpochNanos }
    assertThat(attempts).hasSize(2)
    assertThat(attempts[0].attributes[ERROR_TYPE]).isEqualTo("TestNodeException")
    assertThat(attempts[0].events.map { it.name }).contains("exception")
    assertThat(attempts[0].status.statusCode).isEqualTo(StatusCode.ERROR)
    assertThat(attempts[0].status.description).isEqualTo("TestNodeException: first try")
    assertThat(attempts[1].attributes[ERROR_TYPE]).isNull()
    assertThat(attempts[1].status.statusCode).isEqualTo(StatusCode.UNSET)
  }

  @Test
  fun run_failedNodeAttempt_listsNoAssociatedEvents() {
    var calls = 0
    val flaky =
      node<Any?, String>(
        "flaky",
        config = NodeConfig(RetryConfig(maxAttempts = 2, initialDelay = Duration.ZERO)),
      ) { _, _ ->
        calls += 1
        emit(Event(content = modelMessage("attempt $calls")))
        if (calls == 1) throw TestNodeException("first try")
        "ok"
      }

    val session = runAsApp(Workflow("wf", edges = listOf(Edge(Start, flaky))))

    val attempts =
      exported.filter { it.name == "invoke_node flaky" }.sortedBy { it.startEpochNanos }
    assertThat(attempts.map { it.attributes[ERROR_TYPE] })
      .containsExactly("TestNodeException", null)
      .inOrder()
    assertThat(attempts[0].attributes[ASSOCIATED_EVENT_IDS]).isNull()
    val second = session.events.single { it.content?.parts?.firstOrNull()?.text == "attempt 2" }
    assertThat(attempts[1].attributes[ASSOCIATED_EVENT_IDS]).contains(second.id)
  }

  @Test
  fun run_nodeWhoseChildPauses_listsItsEvents() {
    val ask =
      node<Any?, String>("ask") { _, _ -> requestInput(interruptId = "ask_1", message = "name?") }
    val caller =
      node<Any?, Any?>("caller", rerunOnResume = true) { context, _ ->
        emit(Event(content = modelMessage("before the child")))
        context.runNode(ask)
      }

    val session = runAsApp(Workflow("wf", edges = listOf(Edge(Start, caller))))

    val emitted =
      session.events.single { it.content?.parts?.firstOrNull()?.text == "before the child" }
    assertThat(span("invoke_node caller").attributes[ASSOCIATED_EVENT_IDS]).contains(emitted.id)
  }

  @Test
  fun run_childFailure_marksEveryEnclosingWorkflowSpan() {
    val boom = node<Any?, String>("boom") { _, _ -> throw TestNodeException("boom") }
    val inner = Workflow("inner", edges = listOf(Edge(Start, boom)))

    assertFailsWith<TestNodeException> {
      runAsApp(Workflow("outer", edges = listOf(Edge(Start, inner))))
    }

    assertThat(span("invoke_node boom").attributes[ERROR_TYPE]).isEqualTo("TestNodeException")
    assertThat(span("invoke_workflow inner").attributes[ERROR_TYPE]).isEqualTo("TestNodeException")
    assertThat(span("invoke_workflow outer").attributes[ERROR_TYPE]).isEqualTo("TestNodeException")
    for (name in listOf("invoke_node boom", "invoke_workflow inner", "invoke_workflow outer")) {
      assertWithMessage(name).that(span(name).status.statusCode).isEqualTo(StatusCode.ERROR)
    }
    assertThat(span("invoke_node boom").status.description).isEqualTo("TestNodeException: boom")
    // A nested workflow gets its child's failure back as data, which Python describes by the
    // message.
    assertThat(span("invoke_workflow inner").status.description).isEqualTo("boom")
    // Python raises the root's failure through its span.
    assertThat(span("invoke_workflow outer").status.description)
      .isEqualTo("TestNodeException: boom")
  }

  @Test
  fun run_retriedRootWorkflow_keepsOneSpanAcrossItsAttempts() {
    var calls = 0
    val flaky =
      node<Any?, String>("flaky") { _, _ ->
        calls += 1
        if (calls == 1) throw TestNodeException("first try")
        "ok"
      }
    val retry = NodeConfig(RetryConfig(maxAttempts = 2, initialDelay = Duration.ZERO))

    runAsApp(Workflow("wf", edges = listOf(Edge(Start, flaky)), config = retry))

    assertThat(exported.count { it.name == "invoke_workflow wf" }).isEqualTo(1)
    assertThat(exported.count { it.name == "invoke_node flaky" }).isEqualTo(2)
    assertThat(span("invoke_workflow wf").attributes[ERROR_TYPE]).isNull()
  }

  @Test
  fun run_nodeAskingForInput_pausesWithoutAnErrorAndListsTheRequestEvent() {
    val ask =
      node<Any?, String>("ask") { _, _ -> requestInput(interruptId = "ask_1", message = "name?") }

    val session = runAsApp(Workflow("wf", edges = listOf(Edge(Start, ask))))

    val askSpan = span("invoke_node ask")
    assertThat(askSpan.attributes[ERROR_TYPE]).isNull()
    val request = session.events.single { "ask_1" in it.longRunningToolIds }
    assertThat(askSpan.attributes[ASSOCIATED_EVENT_IDS]).contains(request.id)
  }

  @Test
  fun run_timedOutNode_recordsNodeTimeoutException() {
    val slow =
      node<Any?, String>("slow", config = NodeConfig(timeout = 10.milliseconds)) { _, _ ->
        emit(Event(content = modelMessage("started")))
        delay(10.seconds)
        "late"
      }

    assertFailsWith<NodeTimeoutException> {
      runAsApp(Workflow("wf", edges = listOf(Edge(Start, slow))))
    }

    assertThat(span("invoke_node slow").attributes[ERROR_TYPE]).isEqualTo("NodeTimeoutException")
    assertThat(span("invoke_node slow").attributes[ASSOCIATED_EVENT_IDS]).isNull()
  }

  @Test
  fun run_nodeCancelledByAFailingSibling_isNotMarkedFailed() {
    val waiter =
      node<Any?, Unit>("waiter") { _, _ ->
        emit(Event(content = modelMessage("waiting")))
        delay(10.seconds)
      }

    assertFailsWith<TestNodeException> {
      runAsApp(Workflow("wf", edges = listOf(Edge(Start, waiter), Edge(Start, failingSibling()))))
    }

    assertThat(span("invoke_node waiter").attributes[ERROR_TYPE]).isNull()
    assertThat(span("invoke_node waiter").status.statusCode).isEqualTo(StatusCode.UNSET)
    assertThat(span("invoke_node waiter").attributes[ASSOCIATED_EVENT_IDS]).isNull()
  }

  @Test
  fun run_nodeSwallowingItsCancellation_stillHasItsStateSaved() {
    val stubborn = SwallowingNode("stubborn")
    val runner =
      runnerFor(
        Workflow("wf", edges = listOf(Edge(Start, stubborn), Edge(Start, failingSibling())))
      )

    assertFailsWith<TestNodeException> { runner.runTurn() }

    assertThat(runner.session().state["swallowed"]).isEqualTo("yes")
  }

  @Test
  fun run_rootPlainNode_opensInvokeNodeUnderInvocation() {
    runAsApp(node<Any?, String>("solo") { _, _ -> "alone" })

    assertThat(span("invoke_node solo").parentSpanId).isEqualTo(span("invocation").spanId)
  }

  /** A node that fails shortly after it starts, cancelling the nodes running beside it. */
  private fun failingSibling() =
    node<Any?, Unit>("boom") { _, _ ->
      delay(50.milliseconds)
      throw TestNodeException("boom")
    }

  /** Runs [root] as an app's root node and returns the session it left. */
  @CanIgnoreReturnValue
  private fun runAsApp(root: Node, runConfig: RunConfig? = null): Session =
    runnerFor(root).runTurn(runConfig)

  private fun runnerFor(root: Node) = InMemoryRunner(app = App(appName = APP, rootNode = root))

  /** Runs one turn and returns the session it left. */
  @CanIgnoreReturnValue
  private fun InMemoryRunner.runTurn(runConfig: RunConfig? = null): Session = runBlocking {
    runAsync(
        userId = "user",
        sessionId = SESSION_ID,
        newMessage = userMessage("go"),
        runConfig = runConfig,
      )
      .toList()
    storedSession()
  }

  private fun InMemoryRunner.session(): Session = runBlocking { storedSession() }

  private suspend fun InMemoryRunner.storedSession(): Session =
    checkNotNull(sessionService.getSession(SessionKey(APP, "user", SESSION_ID)))

  private fun span(name: String): SpanData = exported.single { it.name == name }

  /**
   * Asserts every `invoke_agent` span of [children] has the `invoke_agent [parent]` span as parent.
   */
  private fun assertAgentsUnder(parent: String, vararg children: String) {
    val parentId = span("invoke_agent $parent").spanId
    for (child in children) {
      assertWithMessage(child)
        .that(exported.filter { it.name == "invoke_agent $child" }.map { it.parentSpanId }.toSet())
        .containsExactly(parentId)
    }
  }

  private fun sequential(name: String, vararg children: String) =
    SequentialAgent(name, subAgents = children.map { textAgent(it, "ok") })

  /** An agent named `helper` whose model calls `dummy_tool` twice, then answers. */
  private fun toolCallingAgent() =
    LlmAgent(
      name = "helper",
      model =
        DummyModel.createSequential(
          "test-model",
          listOf(
            modelFunctionCallResponse("dummy_tool"),
            modelFunctionCallResponse("dummy_tool"),
            LlmResponse(content = modelMessage("done")),
          ),
        ),
      tools = listOf(DummyTool(declares = true)),
    )

  /** Asserts [modelCalls] `call_llm` spans and one fewer `dummy_tool` call, all under `helper`. */
  private fun assertModelCallsAndToolsUnderHelper(modelCalls: Int) {
    val agentSpanId = span("invoke_agent helper").spanId
    assertThat(exported.filter { it.name == "call_llm" }.map { it.parentSpanId })
      .containsExactlyElementsIn(List(modelCalls) { agentSpanId })
    assertThat(exported.filter { it.name == "execute_tool dummy_tool" }.map { it.parentSpanId })
      .containsExactlyElementsIn(List(modelCalls - 1) { agentSpanId })
  }

  companion object {
    private const val APP = "app"
    private const val SESSION_ID = "session"
    private val ASSOCIATED_EVENT_IDS: AttributeKey<List<String>> =
      AttributeKey.stringArrayKey("gcp.vertex.agent.associated_event_ids")
    private val NESTED: AttributeKey<Boolean> = AttributeKey.booleanKey("gen_ai.workflow.nested")
    private val ERROR_TYPE: AttributeKey<String> = AttributeKey.stringKey("error.type")
  }
}

/** Writes state, then swallows the cancellation a failing sibling causes and ends normally. */
private class SwallowingNode(override val name: String) : Node {
  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    context.updateState("swallowed", "yes")
    try {
      delay(10.seconds)
    } catch (e: CancellationException) {
      // Swallowed on purpose, so the node returns instead of rethrowing.
    }
  }
}

/** Makes [otelContext] current while a coroutine runs, as opentelemetry-extension-kotlin does. */
private class UserContextElement(private val otelContext: OpenTelemetryContext) :
  ThreadContextElement<Scope> {
  companion object Key : CoroutineContext.Key<UserContextElement>

  override val key: CoroutineContext.Key<UserContextElement>
    get() = Key

  @MustBeClosed
  override fun updateThreadContext(context: CoroutineContext): Scope = otelContext.makeCurrent()

  override fun restoreThreadContext(context: CoroutineContext, oldState: Scope) {
    oldState.close()
  }
}

/** Runs its one sub-agent under a span of the user's own, installed with a user context element. */
private class UserSpanAgent(sub: BaseAgent, private val tracer: OpenTelemetryTracer) :
  BaseAgent(name = "wrapper", subAgents = listOf(sub)) {
  override fun runAsyncImpl(context: InvocationContext): Flow<Event> = flow {
    val userSpan = tracer.spanBuilder("user_span").startSpan()
    try {
      emitAll(
        subAgents
          .single()
          .runAsync(context)
          .flowOn(UserContextElement(OpenTelemetryContext.current().with(userSpan)))
      )
    } finally {
      userSpan.end()
    }
  }
}
