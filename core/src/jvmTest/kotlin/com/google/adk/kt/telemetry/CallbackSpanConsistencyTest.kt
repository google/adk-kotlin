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

package com.google.adk.kt.telemetry

import com.google.adk.kt.agents.CallbackContext
import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.apps.App
import com.google.adk.kt.callbacks.CallbackChoice
import com.google.adk.kt.events.Event
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.Model
import com.google.adk.kt.plugins.Plugin
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.telemetry.otel.OtelTracer
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.DummyTool
import com.google.adk.kt.testing.modelFunctionCallResponse
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.Content
import com.google.adk.kt.workflow.Edge
import com.google.adk.kt.workflow.Node
import com.google.adk.kt.workflow.Start
import com.google.adk.kt.workflow.Workflow
import com.google.common.truth.Truth.assertThat
import io.opentelemetry.api.trace.Span as OpenTelemetrySpan
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.sdk.trace.ReadableSpan
import io.opentelemetry.sdk.trace.SdkTracerProvider
import kotlin.test.assertFailsWith
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Every plugin callback runs inside the span of the call it observes, mirroring Python ADK's
 * `test_llm_callback_span_consistency.py` at telemetry schema v1. Not ported: `onAgentError`, tool
 * node callbacks, CFC and live calls, and `onModelError` on cancellation, which Kotlin doesn't have
 * or run.
 */
@RunWith(JUnit4::class)
class CallbackSpanConsistencyTest {

  private val provider = SdkTracerProvider.builder().build()

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
  fun recorder_coversEveryPluginCallback() {
    val callbacks =
      Plugin::class.java.declaredMethods.map { it.name }.filterNot { '$' in it }.toSet() -
        NOT_CALLBACKS
    val recorded = ActiveSpanRecorder::class.java.declaredMethods.map { it.name }

    assertThat(recorded).containsAtLeastElementsIn(callbacks)
  }

  @Test
  fun agentRoot_callbacksRunInsideTheSpanTheyObserve() {
    val recorder = ActiveSpanRecorder()

    runTurn(App(appName = APP, rootAgent = assistant(), plugins = listOf(recorder)))

    assertThat(recorder.activeSpans)
      .containsExactly(
        "onUserMessage",
        setOf(INVOCATION_SPAN),
        "beforeRun",
        setOf(INVOCATION_SPAN),
        "onEvent",
        setOf(INVOCATION_SPAN),
        "afterRun",
        setOf(INVOCATION_SPAN),
        "beforeAgent",
        setOf(AGENT_SPAN),
        "afterAgent",
        setOf(AGENT_SPAN),
        "beforeModel",
        setOf(MODEL_SPAN),
        "afterModel",
        setOf(MODEL_SPAN),
        "beforeTool agent_tool",
        setOf("execute_tool agent_tool"),
        "afterTool agent_tool",
        setOf("execute_tool agent_tool"),
      )
  }

  @Test
  fun workflowRoot_callbacksRunInsideTheSpanTheyObserve() {
    val recorder = ActiveSpanRecorder()

    runTurn(App(appName = APP, rootNode = workflow(assistant()), plugins = listOf(recorder)))

    // Python's runner opens `invoke_workflow` here, so run-level callbacks see that.
    assertThat(recorder.activeSpans)
      .containsExactly(
        "onUserMessage",
        setOf(INVOCATION_SPAN),
        "beforeRun",
        setOf(INVOCATION_SPAN),
        "onEvent",
        setOf(INVOCATION_SPAN),
        "afterRun",
        setOf(INVOCATION_SPAN),
        "beforeAgent",
        setOf(AGENT_SPAN),
        "afterAgent",
        setOf(AGENT_SPAN),
        "beforeModel",
        setOf(MODEL_SPAN),
        "afterModel",
        setOf(MODEL_SPAN),
        "beforeTool agent_tool",
        setOf("execute_tool agent_tool"),
        "afterTool agent_tool",
        setOf("execute_tool agent_tool"),
      )
  }

  @Test
  fun agentRoot_toolErrorCallbacksRunInsideTheFailedCallsSpan() {
    val recorder = ActiveSpanRecorder(toolErrorAnswer = mapOf("error" to "handled"))
    val failingTool =
      DummyTool(name = "failing_agent_tool", declares = true) { _, _ ->
        throw IllegalArgumentException("no data")
      }

    runTurn(App(appName = APP, rootAgent = assistant(failingTool), plugins = listOf(recorder)))

    val toolSpan = setOf("execute_tool failing_agent_tool")
    assertThat(recorder.activeSpans["beforeTool failing_agent_tool"]).isEqualTo(toolSpan)
    assertThat(recorder.activeSpans["onToolError failing_agent_tool"]).isEqualTo(toolSpan)
    assertThat(recorder.activeSpans["afterTool failing_agent_tool"]).isEqualTo(toolSpan)
  }

  @Test
  fun agentRoot_modelFailureCallbacksRunInsideTheSpansItFails() {
    val recorder = ActiveSpanRecorder()

    assertFailsWith<IllegalStateException> {
      runTurn(App(appName = APP, rootAgent = failingAssistant(), plugins = listOf(recorder)))
    }

    assertThat(recorder.activeSpans["onModelError"]).containsExactly(MODEL_SPAN)
    assertThat(recorder.activeSpans["onRunError"]).containsExactly(INVOCATION_SPAN)
  }

  @Test
  fun workflowRoot_modelFailureCallbacksRunInsideTheSpansItFails() {
    val recorder = ActiveSpanRecorder()

    assertFailsWith<IllegalStateException> {
      runTurn(
        App(appName = APP, rootNode = workflow(failingAssistant()), plugins = listOf(recorder))
      )
    }

    assertThat(recorder.activeSpans["onModelError"]).containsExactly(MODEL_SPAN)
    // Python sees `invoke_workflow root_workflow`, for the reason the workflow-root test gives.
    assertThat(recorder.activeSpans["onRunError"]).containsExactly(INVOCATION_SPAN)
  }

  @Test
  fun beforeAndAfterModelCallbacks_shareOneSpan() {
    val recorder = ActiveSpanRecorder()

    runTurn(App(appName = APP, rootAgent = answeringAssistant(), plugins = listOf(recorder)))

    val before = recorder.onlySpan("beforeModel")
    assertThat(recorder.activeSpans["beforeModel"]).containsExactly(MODEL_SPAN)
    assertThat(recorder.onlySpan("afterModel").spanId).isEqualTo(before.spanId)
  }

  @Test
  fun beforeAndAfterModelCallbacks_shareOneTrace() {
    val recorder = ActiveSpanRecorder()

    runTurn(App(appName = APP, rootAgent = answeringAssistant(), plugins = listOf(recorder)))

    val before = recorder.onlySpan("beforeModel")
    assertThat(recorder.activeSpans["beforeModel"]).containsExactly(MODEL_SPAN)
    assertThat(recorder.onlySpan("afterModel").traceId).isEqualTo(before.traceId)
  }

  @Test
  fun beforeModelAndModelErrorCallbacks_shareOneSpan() {
    val recorder = ActiveSpanRecorder(modelErrorAnswer = LlmResponse(content = modelMessage("ok")))

    runTurn(App(appName = APP, rootAgent = failingAssistant(), plugins = listOf(recorder)))

    val before = recorder.onlySpan("beforeModel")
    assertThat(recorder.activeSpans["beforeModel"]).containsExactly(MODEL_SPAN)
    assertThat(recorder.onlySpan("onModelError").spanId).isEqualTo(before.spanId)
  }

  @Test
  fun shortCircuitingBeforeModelCallback_seesAValidSpan() {
    val recorder =
      ActiveSpanRecorder(beforeModelAnswer = LlmResponse(content = modelMessage("short")))

    runTurn(App(appName = APP, rootAgent = answeringAssistant(), plugins = listOf(recorder)))

    assertThat(recorder.activeSpans["beforeModel"]).containsExactly(MODEL_SPAN)
    assertThat(recorder.spans["afterModel"]).isNull()
  }

  @Test
  fun allThreeModelCallbacks_shareOneSpanOnErrorRecovery() {
    val recorder = ActiveSpanRecorder(modelErrorAnswer = LlmResponse(content = modelMessage("ok")))

    runTurn(App(appName = APP, rootAgent = failingAssistant(), plugins = listOf(recorder)))

    val before = recorder.onlySpan("beforeModel")
    assertThat(recorder.activeSpans["beforeModel"]).containsExactly(MODEL_SPAN)
    assertThat(recorder.onlySpan("onModelError").spanId).isEqualTo(before.spanId)
    assertThat(recorder.onlySpan("afterModel").spanId).isEqualTo(before.spanId)
  }

  private fun runTurn(app: App) = runBlocking {
    InMemoryRunner(app = app)
      .runAsync(userId = "user", sessionId = "session", newMessage = userMessage("hi"))
      .collect {}
  }

  /** An agent that calls [tool] once, then answers. */
  private fun assistant(tool: BaseTool = agentTool()): LlmAgent =
    assistantWith(
      DummyModel.createSequential(
        "test-model",
        listOf(
          modelFunctionCallResponse(tool.name, mapOf("city" to "Paris")),
          LlmResponse(content = modelMessage("done")),
        ),
      ),
      tool,
    )

  /** An agent whose model answers at once. */
  private fun answeringAssistant(): LlmAgent =
    assistantWith(
      DummyModel.createSequential(
        "test-model",
        listOf(LlmResponse(content = modelMessage("hello"))),
      )
    )

  /** An agent whose model fails. */
  private fun failingAssistant(): LlmAgent =
    assistantWith(DummyModel("test-model") { flow { throw IllegalStateException("model down") } })

  private fun assistantWith(model: Model, tool: BaseTool = agentTool()) =
    LlmAgent(name = "assistant", model = model, tools = listOf(tool))

  private fun agentTool() =
    DummyTool(name = "agent_tool", declares = true) { _, args -> mapOf("city" to args["city"]) }

  private fun workflow(assistant: Node) =
    Workflow("root_workflow", edges = listOf(Edge(Start, assistant)))

  /**
   * Records the span current in each callback, and its name, null when none is. It can answer for
   * the model before it runs or after it fails, and for a failed tool when [toolErrorAnswer] isn't
   * empty.
   */
  private class ActiveSpanRecorder(
    private val beforeModelAnswer: LlmResponse? = null,
    private val modelErrorAnswer: LlmResponse? = null,
    private val toolErrorAnswer: Map<String, Any?> = emptyMap(),
  ) : Plugin {
    override val name = "active_span_recorder"
    val activeSpans = mutableMapOf<String, MutableSet<String?>>()
    val spans = mutableMapOf<String, MutableList<SpanContext>>()

    fun onlySpan(callback: String): SpanContext = spans.getValue(callback).single()

    private fun record(callback: String) {
      val current = OpenTelemetrySpan.current()
      activeSpans.getOrPut(callback) { mutableSetOf() } += (current as? ReadableSpan)?.name
      spans.getOrPut(callback) { mutableListOf() } += current.spanContext
    }

    override suspend fun onUserMessage(
      invocationContext: InvocationContext,
      userMessage: Content,
    ): Content {
      record("onUserMessage")
      return userMessage
    }

    override suspend fun beforeRun(
      invocationContext: InvocationContext
    ): CallbackChoice<Unit, Content> {
      record("beforeRun")
      return CallbackChoice.Continue(Unit)
    }

    override suspend fun onEvent(invocationContext: InvocationContext, event: Event): Event {
      record("onEvent")
      return event
    }

    override suspend fun afterRun(invocationContext: InvocationContext) {
      record("afterRun")
    }

    override suspend fun onRunError(invocationContext: InvocationContext, error: Throwable) {
      record("onRunError")
    }

    override suspend fun beforeAgent(
      context: CallbackContext
    ): CallbackChoice<EventActions, Content> {
      record("beforeAgent")
      return CallbackChoice.Continue(EventActions())
    }

    override suspend fun afterAgent(context: CallbackContext): CallbackChoice<Unit, Content> {
      record("afterAgent")
      return CallbackChoice.Continue(Unit)
    }

    override suspend fun beforeModel(
      context: CallbackContext,
      request: LlmRequest,
    ): CallbackChoice<LlmRequest, LlmResponse> {
      record("beforeModel")
      return beforeModelAnswer?.let { CallbackChoice.Break(it) } ?: CallbackChoice.Continue(request)
    }

    override suspend fun afterModel(context: CallbackContext, response: LlmResponse): LlmResponse {
      record("afterModel")
      return response
    }

    override suspend fun onModelError(
      context: CallbackContext,
      request: LlmRequest,
      error: Throwable,
    ): CallbackChoice<Unit, LlmResponse> {
      record("onModelError")
      return modelErrorAnswer?.let { CallbackChoice.Break(it) } ?: CallbackChoice.Continue(Unit)
    }

    override suspend fun beforeTool(
      context: ToolContext,
      tool: BaseTool,
      args: Map<String, Any?>,
    ): CallbackChoice<Map<String, Any?>, Map<String, Any?>> {
      record("beforeTool ${tool.name}")
      return CallbackChoice.Continue(args)
    }

    override suspend fun afterTool(
      context: ToolContext,
      tool: BaseTool,
      args: Map<String, Any?>,
      result: Map<String, Any?>,
    ): Map<String, Any?> {
      record("afterTool ${tool.name}")
      return result
    }

    override suspend fun onToolError(
      context: ToolContext,
      tool: BaseTool,
      args: Map<String, Any?>,
      error: Throwable,
    ): CallbackChoice<Unit, Map<String, Any?>> {
      record("onToolError ${tool.name}")
      return if (toolErrorAnswer.isEmpty()) CallbackChoice.Continue(Unit)
      else CallbackChoice.Break(toolErrorAnswer)
    }
  }

  private companion object {
    const val APP = "app"
    const val INVOCATION_SPAN = "invocation"
    const val AGENT_SPAN = "invoke_agent assistant"
    const val MODEL_SPAN = "call_llm"
    val NOT_CALLBACKS = setOf("getName", "close")
  }
}
