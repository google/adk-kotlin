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
import com.google.adk.kt.models.toTracePayload
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
import com.google.adk.kt.tools.GoogleSearchAgentTool
import com.google.adk.kt.tools.GoogleSearchTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.tools.VertexAiRagRetrieval
import com.google.adk.kt.tools.VertexAiRagRetrievalAgentTool
import com.google.adk.kt.tools.VertexAiSearchAgentTool
import com.google.adk.kt.tools.VertexAiSearchTool
import com.google.adk.kt.tools.createGoogleSearchAgent
import com.google.adk.kt.tools.createVertexAiRagRetrievalAgent
import com.google.adk.kt.tools.createVertexAiSearchAgent
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.UsageMetadata
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
   * 1. **REQUEST PREPARATION**: Builds the request with instructions and tools, registering
   *    request-scoped tools -- notably `transfer_to_agent` -- before any resume path is chosen.
   * 2. **RESUMPTION CHECK**: If this invocation paused (a long-running tool call, or an unresolved
   *    function/transfer call), resume without re-invoking the model instead of starting a new
   *    step.
   * 3. **MODEL INVOCATION**: Calls the underlying model to generate content based on the request.
   * 4. **POST-PROCESSING & ACTION EXECUTION**: Runs response processors on model outputs and
   *    executes tool calls, auth requests, or triggers agent transfers if applicable.
   */
  fun execute(): Flow<Event> = flow {
    if (context.isEndOfInvocation) return@flow

    // STAGE 1: Build the request first so request-scoped tools -- notably the `transfer_to_agent`
    // tool added by `AgentTransferProcessor` -- are registered before we choose a resume path.
    // Mirrors Python ADK 1.x `_run_one_step_async`, which always preprocesses before resuming.
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

    // STAGE 2: Resumption check.
    // If this invocation paused on a long-running tool call, do not re-execute it. HITL resumption
    // follows a separate path: the user replies with a `FunctionResponse` for the synthetic
    // `adk_request_confirmation` call, handled by the RequestConfirmationProcessor in STAGE 1.
    if (context.shouldPause()) {
      return@flow
    }

    // If the last event in this invocation is an unresolved function call (a transfer or tool call
    // that paused before producing a response), resume by executing it directly with the tools
    // registered on the request above, without re-invoking the model. A transferred-to sub-agent
    // shares its parent's branch (see `BaseAgent.runAsync`), so it sees the parent's transfer
    // response here and won't re-run the parent's still-pending call. Mirrors Python ADK 1.x
    // `base_llm_flow._run_one_step_async`.
    //
    // Ordering invariant with `shouldPause()` above: it has already returned for a still-pending
    // long-running pause (whether or not a placeholder response was persisted; a resumed one
    // arrives
    // as a user `FunctionResponse`, not a dangling call), so by this point a last function-call
    // event
    // is always a genuinely unresolved call (e.g. a transfer) -- never a long-running pause.
    val events = context.getEvents(currentInvocation = true, currentBranch = true)
    val lastEvent = events.lastOrNull()
    if (context.isResumable && lastEvent != null && lastEvent.functionCalls().isNotEmpty()) {
      emitAll(handleActions(lastEvent, getToolMap(request)))
      return@flow
    }

    // STAGES 3 & 4: Model Invocation & Post-processing
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
      canonicalDirectTools.fold(processedRequest) { req, tool ->
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
    val allTools = canonicalDirectTools + agent.toolsets.flatMap { it.getTools(readonlyCtx) }
    return (allTools + request?.toolsDict.orEmpty()).associateBy { it.name }
  }

  /**
   * The agent's directly-declared tools (its own tools plus any injected by the runtime) with the
   * built-in multi-tools-limit workaround applied, resolved once per turn.
   *
   * Built-in tools (e.g. `google_search`) cannot be combined with other tools in a single request,
   * so when the agent exposes more than one tool a built-in that opted in via its
   * `bypassMultiToolsLimit` flag is swapped for a function-tool equivalent. Mirrors the Python ADK
   * `LlmAgent.canonical_tools`, which is likewise resolved once and cached per invocation.
   */
  private val canonicalDirectTools: List<BaseTool> by lazy {
    val tools = agent.tools + context.extraTools.values
    if (tools.size + agent.toolsets.size <= 1) {
      tools
    } else {
      tools.map { tool ->
        when {
          tool is GoogleSearchTool && tool.bypassMultiToolsLimit ->
            GoogleSearchAgentTool(createGoogleSearchAgent(agent.model))
          tool is VertexAiSearchTool && tool.bypassMultiToolsLimit ->
            VertexAiSearchAgentTool(createVertexAiSearchAgent(agent.model, tool))
          tool is VertexAiRagRetrieval && tool.bypassMultiToolsLimit ->
            VertexAiRagRetrievalAgentTool(createVertexAiRagRetrievalAgent(agent.model, tool))
          else -> tool
        }
      }
    }
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
    request.config.thinkingConfig?.thinkingBudget?.let {
      this[TelemetryAttributes.GEN_AI_USAGE_REASONING_TOKENS_LIMIT] = it.toLong()
    }
    this[TelemetryAttributes.GCP_VERTEX_AGENT_LLM_REQUEST] = capturedJson {
      request.toTracePayload()
    }
  }

  /** Records response-derived `call_llm` span attributes (parity with Python `trace_call_llm`). */
  private fun Span.recordCallLlmResponse(response: LlmResponse) {
    response.usageMetadata?.let { recordTokenUsage(it) }
    response.finishReason?.let {
      this[TelemetryAttributes.GEN_AI_RESPONSE_FINISH_REASONS] = listOf(it.name.lowercase())
    }
    this[TelemetryAttributes.GCP_VERTEX_AGENT_LLM_RESPONSE] = capturedJson { response }
  }

  /**
   * Records OTEL token-usage attributes from [usage] (parity with Python
   * `TokenUsage.to_attributes`).
   *
   * Per OTEL GenAI semconv, `input_tokens` aggregates prompt and tool-use tokens, and
   * `output_tokens` aggregates candidate and reasoning ("thoughts") tokens. Cache-read and
   * reasoning counts are recorded separately when present.
   */
  private fun Span.recordTokenUsage(usage: UsageMetadata) {
    aggregateTokens(usage.promptTokenCount, usage.toolUsePromptTokenCount)?.let {
      this[TelemetryAttributes.GEN_AI_USAGE_INPUT_TOKENS] = it.toLong()
    }
    aggregateTokens(usage.candidatesTokenCount, usage.thoughtsTokenCount)?.let {
      this[TelemetryAttributes.GEN_AI_USAGE_OUTPUT_TOKENS] = it.toLong()
    }
    usage.cachedContentTokenCount?.let {
      this[TelemetryAttributes.GEN_AI_USAGE_CACHE_READ_INPUT_TOKENS] = it.toLong()
    }
    usage.thoughtsTokenCount?.let {
      this[TelemetryAttributes.GEN_AI_USAGE_REASONING_OUTPUT_TOKENS] = it.toLong()
    }
  }

  /**
   * Sums two optional token counts, returning null only when both are absent (parity with Python
   * `TokenUsage`, which treats a missing pair as "no value" rather than 0).
   */
  private fun aggregateTokens(first: Int?, second: Int?): Int? =
    if (first == null && second == null) null else (first ?: 0) + (second ?: 0)

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

    // The request turn terminates here because the placeholder function-response carries
    // `actions.skipSummarization = true` (set by `FunctionTool` on the confirmation gate), so
    // `Event.isFinalResponse` is true on the merged response event and `LlmAgent.executeTurns`
    // exits its per-turn loop. This matches ADK Python's behavior. We do NOT mark the invocation
    // ended (no `context.isEndOfInvocation = true`): doing so would prevent resume, since the
    // pause has to be observable from outside via the synthetic `adk_request_confirmation`
    // long-running event. `LlmAgent.runAsync` reads that long-running id per-event and triggers
    // pause via `InvocationContext.shouldPauseInvocation`, suppressing `endOfAgent` so the
    // session stays live for the eventual resume call. The same "do NOT mark the invocation
    // ended" rule applies to the agent-transfer branch below: after the transferred-to agent
    // finishes, control must return to this (parent) LlmAgent's `executeTurns` loop so the
    // parent can produce its own follow-up response. This mirrors Java ADK's
    // `BaseLlmFlow.runOneStep`, which simply concatenates the transferred-to agent's events and
    // lets the outer flow loop decide whether to continue (based on the last event being a final
    // response or carrying `endInvocation`). Without this, transferring into a
    // SequentialAgent/LoopAgent (or any workflow agent that doesn't itself emit a transfer back)
    // would leave the parent unable to resume.

    // If a tool requested a transfer to another agent, execute that agent's loop.
    functionResponseEvent?.actions?.transferToAgent?.let { agentName ->
      val targetAgent =
        agent.rootAgent.findAgent(agentName)
          ?: throw IllegalArgumentException("Agent '$agentName' not found in the agent tree.")
      emitAll(targetAgent.runAsync(context))
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
    // A user-authored function-response that resolves the pending long-running call is a resume
    // (the
    // awaited result arrived), not the tool's own same-turn placeholder -- so proceed and re-invoke
    // the model rather than pausing again. This is what lets a suppressed-response long-running
    // tool
    // (e.g. request_input, whose Unit return emits no placeholder FR) resume: its resumed response
    // sits directly after the still-pending FC. An agent-authored placeholder FR still pauses.
    val last = events.last()
    if (last.author == Role.USER && last.functionResponses().isNotEmpty()) {
      val pending = events[events.size - 2]
      val pausedIds =
        pending.functionCalls().mapNotNull { it.id }.filter { it in pending.longRunningToolIds }
      val resolvedIds = last.functionResponses().mapNotNull { it.id }.toSet()
      if (pausedIds.isNotEmpty() && resolvedIds.containsAll(pausedIds)) return false
    }
    return shouldPauseInvocation(last) || shouldPauseInvocation(events[events.size - 2])
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
