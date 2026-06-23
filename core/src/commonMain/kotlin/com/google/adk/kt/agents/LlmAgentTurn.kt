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

package com.google.adk.kt.agents

import com.google.adk.kt.callbacks.CallbackChoice
import com.google.adk.kt.callbacks.runAfterModelCallbacksPipeline
import com.google.adk.kt.callbacks.runBeforeModelCallbacksPipeline
import com.google.adk.kt.callbacks.runOnModelErrorCallbacksPipeline
import com.google.adk.kt.events.Event
import com.google.adk.kt.events.getLongRunningFunctionIds
import com.google.adk.kt.ids.Uuid
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.processors.LlmRequestProcessor
import com.google.adk.kt.processors.LlmResponseProcessor
import com.google.adk.kt.processors.createFinalModelResponseEvent
import com.google.adk.kt.processors.generateRequestConfirmationEvent
import com.google.adk.kt.processors.getStructuredModelResponse
import com.google.adk.kt.telemetry.EMPTY_JSON
import com.google.adk.kt.telemetry.Span
import com.google.adk.kt.telemetry.TelemetryAttributes
import com.google.adk.kt.telemetry.capturedJson
import com.google.adk.kt.telemetry.tracedFlow
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.ToolContext
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Encapsulates the logic for a single turn of an [LlmAgent].
 *
 * A turn consists of preparing a request, calling the model, processing the response, and handling
 * any ensuing actions (like function calls or transfers).
 *
 * @property agent The agent executing the turn.
 * @property context The current invocation context.
 * @property requestProcessors The pipeline of request processors to run before model call.
 * @property responseProcessors The pipeline of response processors to run after model call.
 */
internal class LlmAgentTurn(
  private val agent: LlmAgent,
  private val context: InvocationContext,
  private val requestProcessors: List<LlmRequestProcessor>,
  private val responseProcessors: List<LlmResponseProcessor>,
) {

  /**
   * Executes the turn logic and returns a flow of events.
   *
   * The execution follows these stages:
   * 1. **PREPARATION & RESUMPTION CHECK**: Checks if resuming from a tool call (Function Response)
   *    OR builds a new request with instructions and tools.
   * 2. **MODEL INVOCATION**: Calls the underlying model to generate content based on the request.
   * 3. **POST-PROCESSING & ACTION EXECUTION**: Runs response processors on model outputs and
   *    executes tool calls, auth requests, or triggers agent transfers if applicable.
   */
  fun execute(): Flow<Event> = flow {
    if (context.isEndOfInvocation) return@flow

    // STAGE 1: Preparation & Resumption Check
    // If the last event in this invocation is an unresolved function call (i.e. a long-running
    // tool that paused on its previous turn), resume by emitting the tool result events directly
    // without re-invoking the model. HITL resumption follows a separate path: the user replies
    // with a `FunctionResponse` for the synthetic `adk_request_confirmation` call, which the
    // RequestConfirmationProcessor picks up at the top of the request pipeline.
    val events = context.getEvents(currentInvocation = true, currentBranch = true)
    val hasFunctionCall = events.lastOrNull()?.functionCalls()?.isNotEmpty() == true

    if (context.isResumable && hasFunctionCall) {
      val toolsDict = getToolMap(null)
      emitAll(handleActions(events.last(), toolsDict))
      return@flow
    }

    // Build the request using the pipeline of callbacks.
    val requestOrResponse = prepareRequest { emit(it) }

    val request =
      when (requestOrResponse) {
        is RequestOrResponse.Request -> requestOrResponse.request
        is RequestOrResponse.Response -> {
          val response = requestOrResponse.response
          emit(
            Event(
              id = Uuid.random(),
              invocationId = context.invocationId,
              author = agent.name,
              branch = context.branch,
              content = response.content,
            )
          )
          return@flow
        }
      }

    // Check if the invocation context indicates we should pause (e.g., long-running tool results).
    if (context.shouldPause()) {
      return@flow
    }

    // STAGES 2 & 3: Model Invocation & Post-processing
    invokeAndProcessModel(request).collect { emit(it) }
  }

  private suspend fun prepareRequest(emitEvent: suspend (Event) -> Unit): RequestOrResponse {
    val request = LlmRequest()

    val processedRequest =
      requestProcessors.fold(request) { req, processor ->
        processor.process(context, req) { emitEvent(it) }
      }

    val toolContext = ToolContext(invocationContext = context)
    val reqAfterCodeExecutor =
      (agent.tools + context.extraTools.values).fold(processedRequest) { req, tool ->
        tool.processLlmRequest(toolContext, req)
      }

    val finalRequest =
      agent.toolsets.fold(reqAfterCodeExecutor) { req, toolset ->
        val setReq = toolset.processLlmRequest(toolContext, req)
        toolset.getTools(context.toReadonlyContext()).fold(setReq) { r, tool ->
          tool.processLlmRequest(toolContext, r)
        }
      }
    return RequestOrResponse.Request(finalRequest)
  }

  private suspend fun getToolMap(request: LlmRequest?): Map<String, BaseTool> {
    val readonlyCtx = context.toReadonlyContext()
    val allTools =
      agent.tools + agent.toolsets.flatMap { it.getTools(readonlyCtx) } + context.extraTools.values
    return (allTools + request?.toolsDict.orEmpty()).associateBy { it.name }
  }

  private fun invokeAndProcessModel(request: LlmRequest): Flow<Event> =
    tracedFlow<Event>(
      "call_llm",
      {
        this[TelemetryAttributes.GEN_AI_SYSTEM] = TelemetryAttributes.SYSTEM_GCP_VERTEX_AGENT
        this[TelemetryAttributes.GEN_AI_REQUEST_MODEL] = agent.model.name
        this[TelemetryAttributes.GCP_VERTEX_AGENT_INVOCATION_ID] = context.invocationId
        context.session.key.id?.let { this[TelemetryAttributes.GCP_VERTEX_AGENT_SESSION_ID] = it }
        // Safe defaults, refined once the request/response are known. Always present because the
        // ADK Dev UI JSON.parses these on call_llm spans (including early-return paths).
        this[TelemetryAttributes.GCP_VERTEX_AGENT_LLM_REQUEST] = EMPTY_JSON
        this[TelemetryAttributes.GCP_VERTEX_AGENT_LLM_RESPONSE] = EMPTY_JSON
      },
    ) { span, spanContext ->
      val callbackContext = CallbackContext(context)

      // 1. Run before model callbacks.
      // Plugin callbacks first - they can short-circuit by returning a response.
      val allBeforeModelCallbacks =
        context.pluginManager.beforeModelCallbacks + agent.beforeModelCallbacks
      val currentRequest =
        when (
          val result =
            runBeforeModelCallbacksPipeline(
              callbacks = allBeforeModelCallbacks,
              context = callbackContext,
              request = request,
            )
        ) {
          is CallbackChoice.Continue -> result.value
          is CallbackChoice.Break -> {
            processModelResponse(request, result.value, createModelResponseEvent()) { emit(it) }
            return@tracedFlow
          }
        }

      span.recordCallLlmRequest(currentRequest)

      // Enforce RunConfig.maxLlmCalls. After before-model callbacks so a short-circuiting callback
      // doesn't consume the budget (parity with Python ADK base_llm_flow); throwing aborts the run.
      context.incrementLlmCallsCount()

      var modelResponseEvent = createModelResponseEvent()
      span[TelemetryAttributes.GCP_VERTEX_AGENT_EVENT_ID] = modelResponseEvent.id
      // Tracks the last response seen so response-derived span attributes (usage, finish reasons,
      // serialized response) reflect the final value, matching Python's single `trace_call_llm`.
      var lastResponse: LlmResponse? = null

      try {
        // flowOn(spanContext) puts the model client's stream under the span without affecting the
        // outer flow's emission context (which must stay synchronous w.r.t. the collector).
        agent.model
          .generateContent(
            currentRequest,
            stream = context.runConfig?.streamingMode == StreamingMode.SSE,
          )
          .flowOn(spanContext)
          .collect { response ->
            span.addEvent("chunk_received")
            // 2. Run after model callbacks
            val allAfterModelCallbacks =
              context.pluginManager.afterModelCallbacks + agent.afterModelCallbacks
            val currentResponse =
              runAfterModelCallbacksPipeline(
                callbacks = allAfterModelCallbacks,
                context = callbackContext,
                response = response,
              )
            lastResponse = currentResponse

            processModelResponse(currentRequest, currentResponse, modelResponseEvent) { event ->
              modelResponseEvent =
                modelResponseEvent.copy(
                  id = Uuid.random(),
                  timestamp = Clock.System.now().toEpochMilliseconds(),
                )
              emit(event)
            }
          }

        // Response-derived span attributes (parity with Python `trace_call_llm`).
        lastResponse?.let { span.recordCallLlmResponse(it) }
      } catch (e: Exception) {
        val allOnModelErrorCallbacks =
          context.pluginManager.onModelErrorCallbacks + agent.onModelErrorCallbacks
        val recoveredResponse =
          when (
            val result =
              runOnModelErrorCallbacksPipeline(
                callbacks = allOnModelErrorCallbacks,
                context = callbackContext,
                request = currentRequest,
                error = e,
              )
          ) {
            is CallbackChoice.Break -> result.value
            is CallbackChoice.Continue -> null
          }
        if (recoveredResponse != null) {
          if (e !is CancellationException) {
            span.recordException(e)
          }
          processModelResponse(currentRequest, recoveredResponse, modelResponseEvent) { emit(it) }
        } else {
          throw e
        }
      }
    }

  /** Records request-derived `call_llm` span attributes (parity with Python `trace_call_llm`). */
  private fun Span.recordCallLlmRequest(request: LlmRequest) {
    request.config.topP?.let { this[TelemetryAttributes.GEN_AI_REQUEST_TOP_P] = it.toDouble() }
    request.config.maxOutputTokens?.let {
      this[TelemetryAttributes.GEN_AI_REQUEST_MAX_TOKENS] = it.toLong()
    }
    this[TelemetryAttributes.GCP_VERTEX_AGENT_LLM_REQUEST] = capturedJson {
      mapOf(
        "model" to request.model?.name,
        "config" to request.config,
        "contents" to request.contents,
      )
    }
  }

  /** Records response-derived `call_llm` span attributes (parity with Python `trace_call_llm`). */
  private fun Span.recordCallLlmResponse(response: LlmResponse) {
    response.usageMetadata?.promptTokenCount?.let {
      this[TelemetryAttributes.GEN_AI_USAGE_INPUT_TOKENS] = it.toLong()
    }
    response.usageMetadata?.candidatesTokenCount?.let {
      this[TelemetryAttributes.GEN_AI_USAGE_OUTPUT_TOKENS] = it.toLong()
    }
    response.finishReason?.let {
      this[TelemetryAttributes.GEN_AI_RESPONSE_FINISH_REASONS] = listOf(it.name.lowercase())
    }
    this[TelemetryAttributes.GCP_VERTEX_AGENT_LLM_RESPONSE] = capturedJson { response }
  }

  private fun createModelResponseEvent() =
    Event(
      id = Uuid.random(),
      invocationId = context.invocationId,
      author = agent.name,
      branch = context.branch,
    )

  private suspend fun processModelResponse(
    request: LlmRequest,
    response: LlmResponse,
    baseEvent: Event,
    emitEvent: suspend (Event) -> Unit,
  ) {
    val callbackContext = CallbackContext(context)
    val processedResponse =
      responseProcessors.fold(response) { res, processor ->
        processor.process(callbackContext, res) { event -> emitEvent(event) }
      }

    if (processedResponse.isEmpty()) return

    val toolsDict = getToolMap(request)
    val finalizedEvent = baseEvent.finalizeModelResponseEvent(processedResponse, toolsDict)
    emitEvent(finalizedEvent)

    // Skip partial function call events - they should not trigger execution since partial events
    // are not saved to session. Only execute function calls in the non-partial events.
    val hasActions = finalizedEvent.functionCalls().isNotEmpty()
    if (hasActions && !finalizedEvent.partial) {
      // HITL resumption happens via the wire-format path handled by RequestConfirmationProcessor;
      // there is no longer an in-memory toolConfirmations injection point on InvocationContext.
      handleActions(finalizedEvent, toolsDict).collect { emitEvent(it) }
    }
  }

  /**
   * Finalizes the model response event by populating content, usage metadata, and long-running tool
   * IDs.
   *
   * This method takes a base event and updates it with the final response from the model. It also
   * checks for function calls and determines if any of them are long-running tools, updating the
   * event with these IDs.
   */
  private fun Event.finalizeModelResponseEvent(
    response: LlmResponse,
    toolsDict: Map<String, BaseTool>,
  ): Event {
    val finalModelResponseEvent =
      copy(
          content = response.content,
          usageMetadata = response.usageMetadata,
          finishReason = response.finishReason,
          errorMessage = response.errorMessage,
          partial = response.partial,
          interrupted = response.interrupted,
        )
        .populateClientFunctionCallId()

    val functionCalls = finalModelResponseEvent.functionCalls()
    val longRunningIds =
      if (functionCalls.isNotEmpty()) {
        functionCalls.getLongRunningFunctionIds(toolsDict)
      } else {
        emptySet()
      }

    return finalModelResponseEvent.copy(longRunningToolIds = longRunningIds)
  }

  private fun LlmResponse.isEmpty(): Boolean {
    return content == null && errorMessage == null && finishReason == null && !interrupted
  }

  private fun handleActions(actionEvent: Event, tools: Map<String, BaseTool>): Flow<Event> = flow {
    // Execute function calls and code blocks identified in the model response.
    val functionResponseEvent = handleFunctionCalls(actionEvent, tools) { emit(it) }

    // Check if any tool executions requested a confirmation.
    // If so, pause execution and wait for the user to reply.
    if (functionResponseEvent?.actions?.requestedToolConfirmations?.isNotEmpty() == true) {
      context.isEndOfInvocation = true
      return@flow
    }

    // If a tool requested a transfer to another agent, execute that agent's loop.
    functionResponseEvent?.actions?.transferToAgent?.let { agentName ->
      val targetAgent =
        agent.rootAgent.findAgent(agentName)
          ?: throw IllegalArgumentException("Agent '$agentName' not found in the agent tree.")
      emitAll(targetAgent.runAsync(context))
      context.isEndOfInvocation = true
    }
  }

  private suspend fun handleFunctionCalls(
    actionEvent: Event,
    tools: Map<String, BaseTool>,
    emitEvent: suspend (Event) -> Unit,
  ): Event? {
    val functionCalls = actionEvent.functionCalls()
    val functionResponseEvent =
      functionCalls.takeIf { it.isNotEmpty() }?.let { context.handleFunctionCalls(it, tools) }

    functionResponseEvent?.let { responseEvent ->
      generateRequestConfirmationEvent(context, actionEvent, responseEvent)?.let { emitEvent(it) }
      emitEvent(responseEvent)
      // When the output-schema-with-tools workaround is active, the model produces its final answer
      // by calling the `set_model_response` tool. Convert that structured response into a synthetic
      // final model-response event so the turn terminates and the output is saved to state.
      getStructuredModelResponse(responseEvent)?.let { json ->
        emitEvent(createFinalModelResponseEvent(context, json))
      }
    }
    return functionResponseEvent
  }

  private suspend fun InvocationContext.shouldPause(): Boolean {
    if (!isResumable) return false
    val events = getEvents(currentInvocation = true, currentBranch = true)
    if (events.size < 2) return false
    return shouldPauseInvocation(events.last()) || shouldPauseInvocation(events[events.size - 2])
  }
}

/**
 * A sealed class representing either a [LlmRequest] or a [LlmResponse].
 *
 * This is used to represent the intermediate results of the turn execution flow, where a step can
 * either continue with a modified request, or break/short-circuit with a final response.
 */
private sealed class RequestOrResponse {
  /** Represents a continuing [LlmRequest]. */
  data class Request(val request: LlmRequest) : RequestOrResponse()

  /** Represents a short-circuiting or terminal [LlmResponse]. */
  data class Response(val response: LlmResponse) : RequestOrResponse()
}
