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

@file:OptIn(ExperimentalLiveApi::class)

package com.google.adk.kt.agents

import com.google.adk.kt.annotations.ExperimentalLiveApi
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.callbacks.CallbackChoice
import com.google.adk.kt.callbacks.runAfterModelCallbacksPipeline
import com.google.adk.kt.callbacks.runBeforeModelCallbacksPipeline
import com.google.adk.kt.callbacks.runOnModelErrorCallbacksPipeline
import com.google.adk.kt.events.Event
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.events.getLongRunningFunctionIds
import com.google.adk.kt.ids.Uuid
import com.google.adk.kt.logging.LoggerFactory
import com.google.adk.kt.models.ContentInput
import com.google.adk.kt.models.LiveConnection
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.RealtimeInput
import com.google.adk.kt.models.toTracePayload
import com.google.adk.kt.processors.LlmRequestProcessor
import com.google.adk.kt.processors.LlmResponseProcessor
import com.google.adk.kt.processors.createFinalModelResponseEvent
import com.google.adk.kt.processors.findTransferTargets
import com.google.adk.kt.processors.generateRequestConfirmationEvent
import com.google.adk.kt.processors.getStructuredModelResponse
import com.google.adk.kt.processors.liveTransferTargets
import com.google.adk.kt.processors.offersTaskCompletedTool
import com.google.adk.kt.serialization.adkJson
import com.google.adk.kt.telemetry.EMPTY_JSON
import com.google.adk.kt.telemetry.Span
import com.google.adk.kt.telemetry.TelemetryAttributes
import com.google.adk.kt.telemetry.TelemetryContextElement
import com.google.adk.kt.telemetry.capturedJson
import com.google.adk.kt.telemetry.noop.NoOpSpan
import com.google.adk.kt.telemetry.tracedFlow
import com.google.adk.kt.telemetry.withSpan
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.GoogleSearchAgentTool
import com.google.adk.kt.tools.GoogleSearchTool
import com.google.adk.kt.tools.TaskCompletedTool.Companion.TASK_COMPLETED_TOOL_NAME
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.tools.VertexAiSearchAgentTool
import com.google.adk.kt.tools.VertexAiSearchTool
import com.google.adk.kt.tools.createGoogleSearchAgent
import com.google.adk.kt.tools.createVertexAiSearchAgent
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.SessionResumptionConfig
import com.google.adk.kt.types.Transcription
import com.google.adk.kt.types.UsageMetadata
import com.google.adk.kt.workflow.BranchPath
import com.google.genai.kotlin.GenAiApiException
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.jvm.JvmSynthetic
import kotlin.jvm.Volatile
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement

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
 * @property liveTools The live run's streaming tools, set by [executeLive] on the turns it runs;
 *   null otherwise.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class LlmAgentTurn(
  private val agent: LlmAgent,
  private val context: InvocationContext,
  private val requestProcessors: List<LlmRequestProcessor>,
  private val responseProcessors: List<LlmResponseProcessor>,
  private val liveTools: LiveStreamingTools? = null,
) {

  /**
   * Keeps the run's audio when `LiveRunConfig.saveLiveBlob` asks for it; null records nothing. It
   * is also null without an artifact service, which leaves nowhere to save a recording.
   */
  private val audioCache: AudioCacheManager? by lazy {
    when {
      context.runConfig?.live?.saveLiveBlob != true -> null
      context.artifactService == null -> {
        logger.warn {
          "LiveRunConfig.saveLiveBlob is set but the run has no artifact service, so live audio is " +
            "not recorded."
        }
        null
      }
      else -> AudioCacheManager(context)
    }
  }

  /** Returns this turn's live run, which the runner opens before any live turn starts. */
  private fun liveRun(): LiveRun =
    checkNotNull(context.liveRun) { "a live run needs a live request queue on its context" }

  /**
   * The request the send loop has taken off the queue but not yet written.
   *
   * Kept across reconnects because the unlimited channel preserves only requests still in the
   * queue; a request already taken is lost when the sender is cancelled.
   */
  private val pendingRequest = AtomicReference<PendingSend?>(null)

  /**
   * The newest session resumption handle, or null when there is none; a reconnect resumes from it.
   *
   * Held on this turn and never persisted, so a restart or a transfer begins with a new turn and no
   * handle. Without a handle, a dropped connection fails rather than silently starting a new
   * conversation.
   */
  private val resumptionHandle = AtomicReference<String?>(null)

  /**
   * The transfer or `task_completed` the model asked for, kept so that a drop before the handover
   * does not lose it.
   */
  private val pendingHandover = AtomicReference<PendingHandover?>(null)

  /**
   * A callback's reply to a blocked input, held until the collector confirms delivery so a drop
   * mid-emit cannot lose it; [runLiveSession] emits it after the attempt if still undelivered, as
   * ADK Python enqueues the reply before waiting.
   */
  private val pendingBlockedReply = AtomicReference<Event?>(null)

  /** Saves recordings for the connection being read; null between connections. */
  private val recordingSaves = AtomicReference<RecordingSaves?>(null)

  /** Whether the store timed out on the saves still running when the last connection ended. */
  @Volatile private var storeTimedOut = false

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
    // Ordering invariant with `shouldPause()` above: it has already returned for a still-unanswered
    // long-running call (a `Unit` defer that emitted no response), so by this point a last
    // function-call event is always a genuinely unresolved call (e.g. a transfer) -- never a
    // long-running pause. A long-running call answered by a response (the tool's own value, or a
    // user-injected resume) does not pause; the model summarizes it instead.
    val events = context.getEvents(currentInvocation = true, currentBranch = true)
    val lastEvent = events.lastOrNull()
    // A user answer on the sub-branch of a paused single-turn tool replays the call that opened it,
    // as Python's `_is_sub_branch_answer` does.
    val subBranchCall = lastEvent?.let { callThatOpenedBranchOf(it, events) }
    if (subBranchCall != null) {
      emitAll(handleActions(subBranchCall, getToolMap(request)))
      return@flow
    }
    if (context.isResumable && lastEvent != null && lastEvent.functionCalls().isNotEmpty()) {
      emitAll(handleActions(lastEvent, getToolMap(request)))
      return@flow
    }

    // STAGES 3 & 4: Model Invocation & Post-processing
    invokeAndProcessModel(request).collect { emit(it) }
  }

  /**
   * Returns the call event to replay when [answer] resumes a single-turn tool that paused, else
   * null. For example, with this history:
   * ```
   * coordinator          calls helper (id c1) and lookup (id c2)
   * coordinator.helper@c1 calls the long-running approve (id a1), so the turn pauses
   * user                 answers approve on branch coordinator.helper@c1
   * ```
   *
   * the answer's branch names `c1`, so this returns the coordinator's event cut to the calls with
   * no response yet. Python replays every unanswered call on that event too.
   */
  private fun callThatOpenedBranchOf(answer: Event, events: List<Event>): Event? {
    // Only a user's function response resumes a paused tool.
    if (answer.author != Role.USER || answer.functionResponses().isEmpty()) return null
    // A tool's sub-branch ends in `<tool>@<call id>`, so its run ids name the calls that opened it.
    val runIds = BranchPath.runIds(answer.branch)
    if (runIds.isEmpty()) return null
    val callIndex = events.indexOfLast { event ->
      event.author == agent.name && event.functionCalls().any { it.id in runIds }
    }
    if (callIndex < 0) return null
    val callEvent = events[callIndex]
    val content = callEvent.content ?: return null
    // Calls answered after the call event are done; the rest run again.
    val answeredIds =
      events
        .drop(callIndex + 1)
        .flatMap { it.functionResponses() }
        .mapNotNullTo(mutableSetOf()) { it.id }
    val parts =
      content.parts.filter { it.functionCall == null || it.functionCall.id !in answeredIds }
    return callEvent.copy(content = content.copy(parts = parts))
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
    // The injected task_completed tool counts toward the limit too, as Python's len(tools) > 1.
    val injectedToolCount = if (offersTaskCompletedTool(context)) 1 else 0
    if (tools.size + agent.toolsets.size + injectedToolCount <= 1) {
      tools
    } else {
      tools.map { tool ->
        when {
          tool is GoogleSearchTool && tool.bypassMultiToolsLimit ->
            GoogleSearchAgentTool(createGoogleSearchAgent(agent.model))
          tool is VertexAiSearchTool && tool.bypassMultiToolsLimit ->
            VertexAiSearchAgentTool(createVertexAiSearchAgent(agent.model, tool))
          else -> tool
        }
      }
    }
  }

  /**
   * Runs the live conversation over a live connection, preparing the request as [execute] does,
   * draining the context's [LiveRequestQueue] onto the connection while reading one model turn at a
   * time, and screening both with the model callbacks.
   *
   * A connection that drops or retires resumes from the resumption handle when there is one;
   * without one, a server go-away continues in a fresh session and a drop fails the run. A blocked
   * turn always continues in a fresh session, and a transfer hands the conversation to another
   * agent that can run live.
   */
  // finishLiveRun stops the tools under NonCancellable, so the finally's stop survives.
  @Suppress("SuspendInFinally")
  fun executeLive(): Flow<Event> = flow {
    // One set per run, so streaming tools outlive reconnects and restarts but not the run itself.
    val tools =
      context.liveRequestQueue?.let { queue ->
        LiveStreamingTools(queue, agent.name, context.invocationId, currentCoroutineContext())
      }
    var turn = LlmAgentTurn(agent, context, requestProcessors, responseProcessors, tools)
    var ended = false
    try {
      // Restarting in a loop keeps repeated restarts from nesting flows.
      while (turn.runLiveSession(this) && !turn.context.isEndOfInvocation) {
        turn = turn.restartedTurn()
      }
      // Each restart runs on a copy, so an invocation one of them ended has to end here too.
      if (turn.context.isEndOfInvocation) context.isEndOfInvocation = true
      ended = true
    } finally {
      finishLiveRun(tools, ended)
    }
  }

  /**
   * Stops the run's streaming [tools] however the run ended. After a normal end ([ended]), it first
   * hands the caller what the tools sent.
   */
  @Suppress("SuspendInFinally") // close runs under NonCancellable itself.
  private suspend fun FlowCollector<Event>.finishLiveRun(
    tools: LiveStreamingTools?,
    ended: Boolean,
  ) {
    if (tools == null) return
    try {
      if (ended) {
        tools.stopAll()
        for (event in tools.drainEvents()) emit(event)
      }
    } finally {
      tools.close()
    }
  }

  /**
   * Runs one live session, reconnecting it as needed, and emits its events into [collector].
   *
   * @return whether the session must continue in a [restartedTurn]: a callback blocked the model's
   *   output or spoken input, or a go-away left no handle; the caller has handled every event by
   *   the time this returns.
   */
  // closeQuietly runs under NonCancellable itself, so the finally's close survives cancellation.
  @Suppress("SuspendInFinally")
  private suspend fun runLiveSession(collector: FlowCollector<Event>): Boolean {
    if (context.isEndOfInvocation) return false

    val queue = liveRun().requestQueue
    val request =
      when (val prepared = prepareRequest { collector.emit(it) }) {
        is RequestOrResponse.Request -> prepared.request
        is RequestOrResponse.Response -> {
          collector.emit(createModelResponseEvent().copy(content = prepared.response.content))
          return false
        }
      }
    // A request processor can end the invocation, as in ADK Python.
    if (context.isEndOfInvocation) return false

    // A non-empty caller handle seeds the first connection, so it resumes and can retry.
    request.liveConnectConfig.sessionResumption
      ?.handle
      ?.takeIf { it.isNotEmpty() }
      ?.let { resumptionHandle.store(it) }

    var attempt = 0
    while (true) {
      liveTools?.onBeforeConnect()
      // A timeout at the last connection's end says nothing about this connection's saves.
      storeTimedOut = false
      val handle = resumptionHandle.load()
      val attemptRequest = if (handle == null) request else request.resumingFrom(handle)
      // Null until the open returns, so the teardown can tell a failed open from a live connection.
      var connection: LiveConnection? = null
      // Closed at most once per connection, so a close that never returns holds one bound, not two.
      var closed = false
      val close: suspend () -> Boolean = {
        val conn = connection
        // A failed open leaves nothing to close, so the teardown is a no-op.
        if (closed || conn == null) true
        else {
          closed = true
          closeQuietly(conn)
        }
      }
      val result = LiveAttemptResult()
      var droppedBy: Throwable? = null
      // Set when the downstream collector threw, so its failure is never taken for a drop.
      var collectorFailed = false
      try {
        // Inside the try so a drop while opening is retried; a model without live support throws.
        val opened = agent.model.connect(attemptRequest)
        connection = opened
        // The model replies only if history ends with the user; a resumed session already has it.
        if (handle == null && attemptRequest.contents.isNotEmpty()) {
          // Safe inside `flow { }`: withSpan switches context, but nothing in this block emits.
          withSpan(
            SEND_DATA_SPAN,
            {
              this[TelemetryAttributes.GCP_VERTEX_AGENT_INVOCATION_ID] = context.invocationId
              this[TelemetryAttributes.GCP_VERTEX_AGENT_EVENT_ID] = Uuid.random()
              this[TelemetryAttributes.GCP_VERTEX_AGENT_DATA] = capturedJson {
                attemptRequest.contents.toLiveTracePayload()
              }
            },
          ) {
            opened.sendHistory(attemptRequest.contents)
          }
        }
        collectLiveTurns(attemptRequest, opened, queue, close, result).collect { event ->
          // A failure thrown here is the caller's, not the connection's; never retry it.
          try {
            collector.emit(event)
            // Delivered, so a blocked reply no longer needs re-emitting after the attempt.
            if (event === pendingBlockedReply.load()) pendingBlockedReply.store(null)
            pendingHandover.load()?.recordDelivery(event)
          } catch (t: Throwable) {
            // Flow.trace's flowOn runs emit in the caller's context; only the caller throws here.
            collectorFailed = true
            throw t
          }
        }
      } catch (e: Throwable) {
        // A failed open wraps its cause, so only then is the cause checked for a drop.
        val dropped =
          e.isLiveTransportDrop() ||
            (connection == null &&
              e !is CancellationException &&
              e.cause?.isLiveTransportDrop() == true)
        if (collectorFailed || !dropped) throw e
        droppedBy = e
      } finally {
        // The one teardown per connection, and it precedes any restart, transfer or reconnect.
        val unused = close()
      }
      // The teardown is uninterruptible, so re-check before opening any further connection.
      currentCoroutineContext().ensureActive()
      // A write the connection outlived failed on its own, so only a later drop can still resume.
      if (droppedBy == null && result.outlivedWriteFailure) result.writeFailure?.let { throw it }
      // Otherwise a failed write fails the run wherever it would end, but not where it goes on.
      val failure = droppedBy ?: result.writeFailure
      // Deliver any blocked reply the sender could not finish emitting before branching.
      pendingBlockedReply.exchange(null)?.let { collector.emit(it) }

      // A blocked turn starts a fresh session, as ADK Python's restart does; it is not a drop.
      if (result.end == LiveTurnsEnd.BLOCKED) {
        // Save both sides' audio heard before the block, so the blocked sentence is its own
        // recording rather than joined with the next turn's; the next turn then starts clean.
        saveRecordingsNow(flushUserAudio = true, flushModelAudio = true, "a blocked turn").forEach {
          collector.emit(it)
        }
        return true
      }

      val goAway = result.end == LiveTurnsEnd.GO_AWAY
      // Stop once the caller hung up, unless a go-away still has queued input left to drain.
      if (result.closedByCaller || (!goAway && queue.isClosed)) {
        // Best effort: save what both sides said since the last turn ended, unlike ADK Python.
        saveRecordingsNow(flushUserAudio = true, flushModelAudio = true, "close").forEach {
          collector.emit(it)
        }
        failure?.let { throw it }
        break
      }

      // The handover the model asked for, even when a drop ended the attempt before it.
      val handover =
        pendingHandover.exchange(null)?.also { lost ->
          // A drop may have cut its emit short; the caller still sees it before the handover.
          if (!lost.delivered) collector.emit(lost.event)
          // Likewise its held speech; a callback that blocks it cannot stop the handover.
          lost.input?.let { input ->
            val unused = handleLiveResponse(attemptRequest, input, "", queue, collector::emit)
          }
        }
      val transferTo = handover?.transferTo
      // Transfer before the drop rethrow, so a drop during the handover still hands over.
      transferTo?.let { agentName ->
        // Save the audio so far, so the next agent does not save this agent's audio as its own.
        saveRecordingsNow(flushUserAudio = true, flushModelAudio = true, "a transfer").forEach {
          collector.emit(it)
        }
        val target =
          if (agentName == agent.name) agent
          else
            liveTransferTargets(agent).firstOrNull { it.name == agentName }
              ?: throw IllegalArgumentException(
                "Agent '${agent.name}' is not allowed to transfer to the requested agent."
              )
        collector.emitAll(target.runLive(context.forLiveChild(target)))
        return false
      }

      // Any other handover is a task_completed, which ends its run (in a sequence, the step).
      if (handover != null) {
        // Save the audio so far, so the next step does not save this step's audio as its own.
        saveRecordingsNow(flushUserAudio = true, flushModelAudio = true, "a completed step")
          .forEach { collector.emit(it) }
        return false
      }

      // Without a handle a drop fails the run; a go-away continues in a fresh session below.
      if (droppedBy != null && !goAway && resumptionHandle.load() == null) {
        // Save the audio so far, so the failed run keeps it, as the close branch does.
        saveRecordingsNow(flushUserAudio = true, flushModelAudio = true, "a drop").forEach {
          collector.emit(it)
        }
        throw droppedBy
      }
      // A go-away with no handle cannot resume, so it reopens a fresh session.
      val freshRestart = goAway && resumptionHandle.load() == null
      // A clean close with a handle reconnects, as ADK Python does; with none the run ends.
      if (!freshRestart && resumptionHandle.load() == null) {
        // Save the audio so far, so a later step does not save this agent's audio as its own.
        saveRecordingsNow(flushUserAudio = true, flushModelAudio = true, "a server close").forEach {
          collector.emit(it)
        }
        failure?.let { throw it }
        break
      }
      // A go-away never counts against the budget; a completed turn resets it too.
      if (result.completedATurn || goAway) attempt = 0
      if (attempt >= MAX_RECONNECT_ATTEMPTS) {
        // Save the audio so far, so an exhausted reconnect keeps it, as the close branch does.
        saveRecordingsNow(flushUserAudio = true, flushModelAudio = true, "exhausted reconnects")
          .forEach { collector.emit(it) }
        // The exhausting failure surfaces as itself; a go-away is reset above and never gets here.
        failure?.let { throw it }
        error("live connection could not be resumed after $MAX_RECONNECT_ATTEMPTS attempts")
      }
      attempt++
      if (freshRestart) {
        logger.info {
          "Restarting the live session fresh after a go-away with no handle to resume."
        }
      } else {
        // A drop names its close code, even when a failed open wrapped it.
        val closeCode =
          (droppedBy as? GenAiApiException ?: droppedBy?.cause as? GenAiApiException)?.code
        logger.info {
          "Reconnecting the live session (attempt $attempt of $MAX_RECONNECT_ATTEMPTS)." +
            (closeCode?.let { " Close code $it." } ?: "")
        }
      }
      // Waiting before every reconnect and restart keeps a go-away-only server from spinning.
      when {
        // A go-away still drains queued input, so a closed queue does not cut the wait short.
        goAway -> delay(liveReconnectDelay(attempt))
        withTimeoutOrNull(liveReconnectDelay(attempt)) { queue.closed.await() } != null -> {
          // A hang-up while waiting saves what both sides said, as the close above does.
          saveRecordingsNow(flushUserAudio = true, flushModelAudio = true, "close").forEach {
            collector.emit(it)
          }
          failure?.let { throw it }
          break
        }
      }
      // No handle to resume, so leave the reconnect loop and start a fresh turn.
      if (freshRestart) return true
    }
    return false
  }

  /**
   * Pumps the caller's queue into [connection] while reading turns back out of it, until the caller
   * closes the queue, the connection ends or retires, a handover starts, or a callback blocks the
   * model's output or spoken input. Records how it stopped in [result]; the flow completes only
   * once the sender has stopped.
   */
  // Only the sender writes the connection; the session service must accept concurrent appends.
  @Suppress("UnsafeCoroutineCrossing")
  private fun collectLiveTurns(
    request: LlmRequest,
    connection: LiveConnection,
    queue: LiveRequestQueue,
    close: suspend () -> Boolean,
    result: LiveAttemptResult,
  ): Flow<Event> =
    channelFlow {
        // Returns once the caller has handled the event, so neither loop runs ahead of the caller.
        val deliver: suspend (Event) -> Unit = { event ->
          val delivery = Delivery(event)
          send(delivery)
          delivery.handled.await()
        }
        val saves = RecordingSaves(this, deliver)
        recordingSaves.store(saves)
        // A transport drop the sender saw, kept so it surfaces after the saves finish instead of
        // cancelling the scope and its in-flight saves; read once the sender has joined below.
        var sendDrop: Throwable? = null
        val receiver = async {
          try {
            receiveTurns(request, connection, queue, result, deliver)
          } catch (e: Throwable) {
            // A handover cut short stops its streams first; the saves below can take seconds.
            if (pendingHandover.load() != null) liveTools?.stopAll()
            // A drop or error cancels this scope; let running saves deliver their recordings first.
            if (e !is CancellationException && !saves.finish()) storeTimedOut = true
            throw e
          }
        }
        /**
         * Writes [pending]; on a [LiveWriteFailure] it records the error when the turn was already
         * stored, then waits for `receiver` to end, and if it is still running after
         * [CONNECTION_TEARDOWN_TIMEOUT] records the error anyway and closes the connection. Returns
         * false after a failed write, so the sender stops and keeps the request to replay.
         */
        suspend fun sendOrReplay(pending: PendingSend): Boolean {
          try {
            writeRequest(request, connection, pending, deliver)
            return true
          } catch (e: LiveWriteFailure) {
            // A turn the session already stored must reach the model, or the run fails with it.
            if (pendingRequest.load()?.storedTurn == true) result.writeFailure = e.cause
            if (withTimeoutOrNull(CONNECTION_TEARDOWN_TIMEOUT) { receiver.join() } == null) {
              result.writeFailure = e.cause
              result.outlivedWriteFailure = true
              if (!close()) receiver.cancel()
            }
            return false
          } catch (e: Throwable) {
            // A transport drop would otherwise cancel the scope and its saves. Keep it, stop the
            // receiver, and let the saves finish below; it is rethrown like a receive-side drop.
            if (e is CancellationException || !e.isLiveTransportDrop()) throw e
            sendDrop = e
            receiver.cancel()
            return false
          }
        }
        val sender = launch {
          // Re-send what the dead attempt had taken off the queue but not yet written.
          pendingRequest.load()?.let { pending -> if (!sendOrReplay(pending)) return@launch }
          pendingRequest.store(null)
          while (true) {
            val liveRequest = queue.receiveRequest() ?: break
            // Held until the write returns, so a cancellation in between re-sends it.
            val pending = PendingSend(liveRequest, persisted = false, screened = null)
            pendingRequest.store(pending)
            if (!sendOrReplay(pending)) return@launch
            pendingRequest.store(null)
          }
          // receiveRequest returns null only once the caller has closed the queue.
          result.closedByCaller = true
          val closedInTime = close()
          // A close that timed out may never end the connection's receive, so stop reading too.
          if (!closedInTime) receiver.cancel()
        }
        // Streaming tools' messages join the turn's events in arrival order.
        val toolEvents =
          if (liveTools == null) {
            null
          } else {
            launch {
              while (true) {
                val next = liveTools.nextEvent()
                val delivery = Delivery(next.event, next.handled)
                try {
                  send(delivery)
                  delivery.handled.await()
                } catch (e: CancellationException) {
                  // Unclaimed means the emitter never took it, sent or not, so it goes back.
                  if (delivery.claim()) liveTools.putBack(next)
                  throw e
                }
              }
            }
          }

        try {
          receiver.await()
        } catch (e: CancellationException) {
          // Only the sender cancels the receiver; a cancellation of this scope still throws.
          ensureActive()
        }
        // Joined so the sender has stopped before the caller restarts or reconnects.
        sender.cancelAndJoin()
        toolEvents?.cancelAndJoin()
        // Recordings still saving go out before a transfer, restart, reconnect or the run's end.
        if (!saves.finish()) storeTimedOut = true
        recordingSaves.store(null)
        // Surfaced after the saves, so a send-side drop fails or resumes as a receive-side one
        // does.
        sendDrop?.let { throw it }
      }
      .buffer(Channel.RENDEZVOUS)
      .transform { delivery ->
        // A producer that claimed it puts it back, so its tool waits for the later delivery.
        if (delivery.claim()) {
          emit(delivery.event)
          delivery.handled.complete(Unit)
        }
      }

  /**
   * Reads the model's turns off [connection] and emits their events, answering tool calls through
   * [queue] as ADK Python does, and records in [result] how the reading stopped.
   */
  private suspend fun receiveTurns(
    request: LlmRequest,
    connection: LiveConnection,
    queue: LiveRequestQueue,
    result: LiveAttemptResult,
    emitEvent: suspend (Event) -> Unit,
  ) {
    // The model's output transcription so far in this turn, which the after-model callbacks screen.
    var spoken = ""
    // The user's speech in this turn that the connection has not yet marked finished.
    var unfinishedInput = ""
    // Speech stored ahead of a tool call, so the connection's own finished copy is skipped.
    var storedInput: String? = null
    while (true) {
      var sawResponse = false
      var end: LiveTurnsEnd? = null
      connection
        .receive()
        .takeWhile { response ->
          // Store any handle update as-is, so a withdrawn handle clears it, as ADK Python does.
          response.liveSessionResumptionUpdate?.let {
            resumptionHandle.store(it.newHandle?.takeIf { h -> h.isNotEmpty() })
          }
          // A go-away warns of a close: stop and let the loop reconnect; it is not an event.
          if (response.goAway != null) {
            end = LiveTurnsEnd.GO_AWAY
            return@takeWhile false
          }
          sawResponse = true
          if (response.turnComplete == true) {
            result.completedATurn = true
          }
          storedInput?.let { stored ->
            val rest = response.finishedInputAfter(stored) ?: return@let
            storedInput = null
            unfinishedInput = ""
            // The turn went on after the stored speech, so keep only what the user said after it.
            if (
              rest.isNotEmpty() &&
                emitAsFinishedInput(request, rest, response, spoken, queue, emitEvent)
            ) {
              end = LiveTurnsEnd.BLOCKED
              return@takeWhile false
            }
            return@takeWhile true
          }
          // Cut-off audio or grounding comes before GeminiLiveConnection's finished copy, so wait.
          val bareInterruption =
            response.interrupted && response.content == null && response.groundingMetadata == null
          if (response.turnComplete == true || bareInterruption) storedInput = null
          spoken = spokenSoFar(spoken, response)
          unfinishedInput = unfinishedInputSoFar(unfinishedInput, response)
          // Store and screen the speech before any tool runs, as ADK Python does.
          if (unfinishedInput.isNotEmpty() && response.content?.hasFunctionCall() == true) {
            val speech = unfinishedInput
            unfinishedInput = ""
            // A blocked question runs no tool and ends the turn, as ADK Python's restart does.
            if (emitAsFinishedInput(request, speech, response, spoken, queue, emitEvent)) {
              end = LiveTurnsEnd.BLOCKED
              return@takeWhile false
            }
            storedInput = storedInput.orEmpty() + speech
          }
          val blocked =
            handleLiveResponse(request, response, spoken, queue, emitEvent, unfinishedInput)
          pendingHandover.load()?.let { handover ->
            // Only a transfer set without a tool call still has unstored speech here.
            if (unfinishedInput.isNotEmpty()) {
              // The transfer already reached the caller, so blocking this speech cannot stop it.
              val unused =
                emitAsFinishedInput(request, unfinishedInput, response, spoken, queue, emitEvent)
              unfinishedInput = ""
            }
            // Stopped before the handover, so this agent's streams reach no other agent.
            liveTools?.stopAll()
            handOverConnection(handover, queue)
            end =
              if (handover.transferTo != null) LiveTurnsEnd.TRANSFER
              else LiveTurnsEnd.TASK_COMPLETED
          }
          // A handover beats a blocked input in the same response: any answer is already queued.
          if (blocked && end == null) end = LiveTurnsEnd.BLOCKED
          end == null
        }
        .collect()
      end?.let {
        result.end = it
        return
      }
      // An empty collection is the connection's end, per `LiveConnection.receive`.
      if (!sawResponse) {
        logger.info { "The live connection produced no further responses." }
        return
      }
    }
  }

  /**
   * Emits the user's [speech] as the finished transcription the connection has not sent yet.
   *
   * @return whether a callback blocked it.
   */
  private suspend fun emitAsFinishedInput(
    request: LlmRequest,
    speech: String,
    response: LlmResponse,
    spoken: String,
    queue: LiveRequestQueue,
    emitEvent: suspend (Event) -> Unit,
  ): Boolean =
    handleLiveResponse(request, response.asFinishedInput(speech), spoken, queue, emitEvent)

  /** The user's unfinished speech in this turn, [unfinished], updated with [response]. */
  private fun unfinishedInputSoFar(unfinished: String, response: LlmResponse): String {
    val input = response.inputTranscription
    return when {
      input?.finished == true || response.turnComplete == true || response.interrupted -> ""
      input != null -> unfinished + input.text.orEmpty()
      else -> unfinished
    }
  }

  /** The turn's output transcription so far, [spoken], updated with [response]. */
  private fun spokenSoFar(spoken: String, response: LlmResponse): String {
    val turnSoFar = if (response.turnComplete == true || response.interrupted) "" else spoken
    val heard = response.outputTranscription ?: return turnSoFar
    return if (heard.finished == true) "" else turnSoFar + heard.text.orEmpty()
  }

  /**
   * Screens and emits one live [response], answering its tool calls through [queue]. A handover it
   * asks for keeps [unfinishedInput], the user's speech still to emit after it, so a drop cannot
   * lose it.
   *
   * @return whether a callback blocked the model's output or the user's spoken input.
   */
  private suspend fun handleLiveResponse(
    request: LlmRequest,
    response: LlmResponse,
    spoken: String,
    queue: LiveRequestQueue,
    emitEvent: suspend (Event) -> Unit,
    unfinishedInput: String = "",
  ): Boolean {
    val callbackContext = CallbackContext(context)
    if (response.outputTranscription != null && spoken.isNotEmpty()) {
      val blockedOutput = screenModelOutput(request, response, spoken, callbackContext)
      if (blockedOutput != null) {
        emitEvent(blockedOutput)
        return true
      }
    }
    emitLiveResponse(request, response, callbackContext) { event ->
      val transferTo = event.actions.transferToAgent
      // A transfer from any event of the response wins, so a task_completed never replaces it.
      if (
        transferTo != null ||
          (event.signalsTaskCompleted() &&
            pendingHandover.load()?.transferTo == null &&
            // A tool-not-found answer to an unoffered task_completed call does not count.
            TASK_COMPLETED_TOOL_NAME in getToolMap(request))
      ) {
        // Kept before the emit, so a drop mid-emit cannot lose it; runLiveSession emits it if lost.
        pendingHandover.store(
          PendingHandover(
            // An answer to wait for only when there is one; a non-tool transfer has none.
            event.content?.takeIf { c -> c.parts.any { it.functionResponse != null } },
            event,
            transferTo,
            unfinishedInput.takeIf { it.isNotEmpty() }?.let { response.asFinishedInput(it) },
          )
        )
      }
      emitEvent(event)
      // Cut-off audio is left out; audio on a turnComplete response is flushed with this turn.
      if (!response.interrupted) cacheModelAudio(event)
      sendToolResponse(queue, event)
    }
    val heard = response.inputTranscription
    if (heard?.finished == true && !heard.text.isNullOrEmpty()) {
      // Screened after its own event is emitted, so the user's words still reach the caller.
      val spokenInput = Content(Role.USER, listOf(Part(text = heard.text)))
      val screened = screenUserContent(request, spokenInput)
      if (screened is CallbackChoice.Break) {
        emitEvent(screened.value)
        return true
      }
    }
    return false
  }

  /**
   * The turn that replaces this one's live session after a callback blocked the model's output or
   * spoken input, as ADK Python's restart does, or after a go-away with no handle.
   *
   * It runs with the resumption handle cleared and reads the same queue, which redelivers a request
   * whose receive was cancelled; one the sender was already writing is lost, as in ADK Python. It
   * also starts with a fresh reconnect budget.
   */
  private fun restartedTurn(): LlmAgentTurn {
    val restartContext = context.copy(runConfig = context.runConfig?.forNewLiveSession())
    return LlmAgentTurn(agent, restartContext, requestProcessors, responseProcessors, liveTools)
  }

  /**
   * Gives the model's turn time to finish before the connection is handed over, on a transfer or a
   * `task_completed` end.
   *
   * The [handover]'s tool answer queues behind whatever the caller sent first, so this waits up to
   * [TRANSFER_ANSWER_TIMEOUT] for the sender to write it to this connection rather than leave it
   * for the next one, then pauses for [TRANSFER_HANDOVER_DELAY] or [TASK_COMPLETION_DELAY] as ADK
   * Python does. Both waits end early if the caller closes the queue, since nothing follows a
   * hang-up.
   */
  // The queue is only ever awaited here, never written, so the cross-coroutine read is safe.
  @Suppress("UnsafeCoroutineCrossing")
  private suspend fun handOverConnection(handover: PendingHandover, queue: LiveRequestQueue) {
    if (handover.answer != null) {
      val wrote =
        withTimeoutOrNull(TRANSFER_ANSWER_TIMEOUT) {
          select {
            handover.written.onAwait { true }
            queue.closed.onAwait { false }
          }
        }
      if (wrote == null) logger.warn { "The tool answer was not sent before the handover." }
    }
    val pause = if (handover.transferTo != null) TRANSFER_HANDOVER_DELAY else TASK_COMPLETION_DELAY
    // The pause lets the model's turn settle, unless the caller has already hung up.
    withTimeoutOrNull(pause) { queue.closed.await() }
  }

  /**
   * Closes [connection], logging rather than throwing a failure, which would replace an exception
   * already unwinding the run.
   *
   * Runs under [NonCancellable] so a cancelled run still frees the connection, bounded by
   * [CONNECTION_TEARDOWN_TIMEOUT].
   *
   * @return false when the close timed out.
   */
  private suspend fun closeQuietly(connection: LiveConnection): Boolean =
    withContext(NonCancellable) {
      val finished =
        withTimeoutOrNull(CONNECTION_TEARDOWN_TIMEOUT) {
          try {
            connection.closeSession()
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            logger.warn(e) { "Closing the live connection failed." }
          }
        }
      if (finished == null) {
        logger.warn { "Closing the live connection timed out after $CONNECTION_TEARDOWN_TIMEOUT." }
      }
      finished != null
    }

  /**
   * Records what the caller said, so the session holds both halves of a live conversation and a
   * later or resumed run sees what it is replying to.
   *
   * Partial content is skipped as superseded, and function responses reach the session by their own
   * path. Realtime audio is not stored here; `saveLiveBlob` records it through the audio cache.
   */
  private suspend fun persistUserTurn(
    liveRequest: LiveRequest,
    input: ContentInput,
    content: Content,
    answersTools: Boolean,
  ): Boolean {
    if (input.partial || answersTools) return false
    val liveRun = liveRun()
    if (!liveRun.storesEvents) return false
    liveRun.append(
      Event(
        invocationId = context.invocationId,
        author = Role.USER,
        content = content,
        actions = EventActions(stateDelta = liveRequest.stateDelta.toMutableMap()),
      )
    )
    return true
  }

  /** Records the request's state change on an event of its own. */
  private suspend fun persistStateDeltaOnly(liveRequest: LiveRequest) {
    val delta = liveRequest.stateDelta
    if (delta.isEmpty()) return
    val event =
      Event(
        invocationId = context.invocationId,
        author = Role.USER,
        actions = EventActions(stateDelta = delta.toMutableMap()),
      )
    liveRun().append(event)
  }

  /** Saves a continuing callback's state and artifact writes on an event of their own. */
  private suspend fun saveCallbackWrites(callbackContext: CallbackContext) {
    val actions = callbackContext.eventActions
    if (actions.stateDelta.isEmpty() && actions.artifactDelta.isEmpty()) return
    liveRun().append(createModelResponseEvent().copy(actions = actions.snapshot()))
  }

  /**
   * Writes one queued request to the connection: records it, screens it with the before-model
   * callbacks unless it only answers tool calls, as ADK Python does, then sends it.
   *
   * A blocked input is not sent; the callback's response is emitted as a completed turn instead.
   * [pending] carries what a replay after a drop already did, so neither step runs twice, and a
   * replay sends the content the callbacks returned.
   */
  private suspend fun writeRequest(
    request: LlmRequest,
    connection: LiveConnection,
    pending: PendingSend,
    emitEvent: suspend (Event) -> Unit,
  ) {
    val liveRequest = pending.request
    val input = liveRequest.input
    val original = (input as? ContentInput)?.content
    // A ContentInput's parts are non-empty and all tool answers or none, so the first tells which.
    val answersTools = original?.parts?.first()?.functionResponse != null
    // Content without a role is the user's, as in ADK Python; tool answers keep theirs unset.
    val content =
      if (original?.role == null && !answersTools) original?.copy(role = Role.USER) else original

    // Skipped on a replay, so a re-sent request does not record the same user turn twice.
    if (!pending.persisted) {
      // Cached just before the send, as Python does.
      if (input is RealtimeInput.Audio) audioCache?.cacheInput(input.blob)
      val storedTurn =
        input is ContentInput &&
          content != null &&
          persistUserTurn(liveRequest, input, content, answersTools)
      // A state delta reaches the session even when nothing was said to carry it.
      if (!storedTurn) persistStateDeltaOnly(liveRequest)
      // Marked before the write, so a replay after a drop redoes none of the above.
      pendingRequest.store(pendingRequest.load()?.copy(persisted = true, storedTurn = storedTurn))
    }
    // A sender cancelled mid-append stops here, so the request replays on the next connection.
    currentCoroutineContext().ensureActive()
    when (input) {
      is RealtimeInput -> sendingToConnection { connection.sendRealtime(input) }
      is ContentInput -> {
        val sent = checkNotNull(content)
        val toSend =
          when {
            answersTools -> sent
            pending.screened != null -> pending.screened
            else ->
              when (val screened = screenUserContent(request, sent)) {
                // A callback that rewrites the request rewrites what the model receives.
                is CallbackChoice.Continue -> {
                  checkSendable(screened.value, input.partial)
                  // Kept before the write, so a replay after a drop is not screened again.
                  pendingRequest.store(pendingRequest.load()?.copy(screened = screened.value))
                  // A sender cancelled mid-screen stops here, so a replay sends, not re-screens.
                  currentCoroutineContext().ensureActive()
                  screened.value
                }
                is CallbackChoice.Break -> {
                  // Dropped before the event, so a reconnect neither re-sends nor re-screens it.
                  pendingRequest.store(null)
                  // Kept so a drop mid-emit cannot lose it; runLiveSession re-emits it after.
                  pendingBlockedReply.store(screened.value)
                  emitEvent(screened.value)
                  return
                }
              }
          }
        sendingToConnection { connection.sendContent(toSend, input.partial) }
        val handover = pendingHandover.load()
        if (handover?.answer == sent) {
          val unused = handover.written.complete(Unit)
        }
      }
      null -> {}
    }
  }

  /**
   * Runs a connection [write], letting a cancellation or a transport drop pass through unchanged
   * and wrapping any other failure as a [LiveWriteFailure], which the sender can resume from.
   *
   * The SDK's send throws a plain channel or IO error on a dead socket, not the drop the receive
   * side reports; wrapping keeps that from failing the run before the receiver can resume it.
   */
  private suspend inline fun sendingToConnection(write: () -> Unit) {
    try {
      write()
    } catch (e: Exception) {
      if (e is CancellationException || e.isLiveTransportDrop()) throw e
      throw LiveWriteFailure(e)
    }
  }

  /** Fails with a message naming the before-model callback when its rewrite can't be sent. */
  private fun checkSendable(rewrite: Content, partial: Boolean) {
    try {
      val unused = ContentInput(rewrite, partial)
    } catch (e: IllegalArgumentException) {
      throw IllegalArgumentException(
        "A before-model callback rewrote a live input: ${e.message}",
        e,
      )
    }
  }

  /**
   * Answers a tool call by queuing its response like the caller's input, as ADK Python does.
   *
   * A live model may hold the turn open until the answer arrives. Once the caller has closed the
   * queue the answer is dropped, as it is in ADK Python.
   */
  private suspend fun sendToolResponse(queue: LiveRequestQueue, event: Event) {
    val content = event.content ?: return
    if (content.parts.none { it.functionResponse != null }) return
    queue.sendContent(content)
    liveTools?.onAnswersQueued(content)
  }

  /**
   * Records the model's audio, so a completed turn can be written out as one artifact.
   *
   * Reading the emitted event rather than the raw response records what the response processors
   * produced, which is what the caller heard and what ADK Python records.
   */
  private suspend fun cacheModelAudio(event: Event) {
    val cache = audioCache ?: return
    event.content?.parts?.forEach { part -> part.inlineData?.let { cache.cacheOutput(it) } }
  }

  /**
   * Saves the requested caches now, outside any connection, for a blocked turn, a close by either
   * side, a transfer, a completed step, a drop with no handle or an exhausted reconnect. It waits
   * at most [RecordingSaves.SAVE_TIMEOUT], and skips the save when the store already timed out at
   * this connection's end, so a stuck store holds the run for one timeout, not two, and costs the
   * recording rather than the run; [where] names the moment in the log.
   */
  // cache is shared with later connections but its caches are lock-guarded; taken is this save's
  // own; saved is read while the job may still run and is safe because it is atomic.
  @Suppress("UnsafeCoroutineCrossing")
  private suspend fun saveRecordingsNow(
    flushUserAudio: Boolean,
    flushModelAudio: Boolean,
    where: String,
  ): List<Event> {
    val cache = audioCache ?: return emptyList()
    // Taken first, so the audio leaves the caches even when it is not saved.
    val taken = cache.take(flushUserAudio, flushModelAudio)
    if (taken.input.isEmpty() && taken.output.isEmpty()) return emptyList()
    if (storeTimedOut) {
      logger.error { "Dropped live audio at $where: the artifact store already timed out." }
      return emptyList()
    }
    // The save runs detached, so a store that ignores cancellation cannot hold the run past the
    // bound; each recording is published as it saves and read back after a bounded wait. A save
    // past
    // the bound still finishes detached -- its artifact lands in the store -- but its event is
    // never
    // read, so none is delivered after the run moved on. The scope is cancelled in the finally so a
    // cancel during the wait does not leave the save running.
    val saved = AtomicReference(emptyList<Event>())
    val saveScope = CoroutineScope(currentCoroutineContext() + SupervisorJob())
    try {
      val job = saveScope.launch {
        try {
          val acc = mutableListOf<Event>()
          cache.write(taken) { event ->
            acc += event
            saved.store(acc.toList())
          }
        } catch (e: CancellationException) {
          throw e
        } catch (e: Throwable) {
          // Logged, not rethrown: an uncaught throw here would crash the process on Android.
          logger.error { "Failed to save live audio at $where (${e::class.simpleName})." }
        }
      }
      if (withTimeoutOrNull(RecordingSaves.SAVE_TIMEOUT) { job.join() } == null) {
        storeTimedOut = true
        logger.error {
          "Dropped live audio still saving at $where after ${RecordingSaves.SAVE_TIMEOUT}."
        }
      }
    } finally {
      saveScope.cancel()
    }
    return saved.load()
  }

  /**
   * Writes the recorded audio out when a turn ends, emitting one event per recording after the
   * control event. An interruption flushes only the model's side, since the caller is mid-sentence.
   *
   * The control event goes out first, as in ADK Go, so a slow artifact save cannot delay a
   * barge-in; ADK Python waits for the save and, when it saved a recording, drops the control
   * event.
   */
  private suspend fun flushAudioOnControlEvent(
    response: LlmResponse,
    emitEvent: suspend (Event) -> Unit,
  ) {
    val cache = audioCache ?: return
    val flushUserAudio =
      when {
        response.interrupted -> false
        response.turnComplete == true -> true
        else -> return
      }
    // Taken now, so the turn ends here; the save runs in the background when a connection is read.
    val taken = cache.take(flushUserAudio = flushUserAudio, flushModelAudio = true)
    val saves = recordingSaves.load()
    if (saves != null && saves.isActive) saves.start(cache, taken)
    else cache.write(taken, emitEvent)
  }

  /**
   * Saves recordings off the receive loop, so a slow artifact store delays no other event; each is
   * delivered once saved, in turn order. [finish] waits up to [SAVE_TIMEOUT], then leaves a
   * still-running save to finish detached: its artifact still lands in the store, but its event is
   * dropped, so nothing is delivered after the run moved on. A store that ignores cancellation can
   * leave such detached saves running across reconnects until it returns; a cancellable one is
   * cancelled when this connection's scope ends.
   */
  private class RecordingSaves(
    private val scope: CoroutineScope,
    private val deliver: suspend (Event) -> Unit,
  ) {
    // Saves run here, detached from [scope], so a store that ignores cancellation cannot hold the
    // run open past [finish]; the dispatcher is [scope]'s, the job an independent supervisor.
    private val saveScope = CoroutineScope(scope.coroutineContext + SupervisorJob())
    // The delivery jobs, children of [scope] so [finish] can bound them; read once [finish]
    // stopped.
    private val jobs = mutableListOf<Job>()
    // Completed by [finish] when it gives up waiting, so a delivery job stops waiting for a save
    // still running and emits only what that save has already stored.
    private val cutOff = CompletableDeferred<Unit>()

    init {
      // Cancel the detached saves whenever this connection's scope ends, including the abnormal
      // exits that skip [finish] (a caller cancel, a collector or sender failure), so no save
      // outlives the run; a cancellable store then stops and frees its taken audio.
      scope.coroutineContext.job.invokeOnCompletion { saveScope.cancel() }
    }

    // The caches lock, writes go to the artifact service, and taken audio never changes. Each save
    // publishes an immutable snapshot as it stores each recording, so a delivery cut short still
    // sees what was saved before the cut-off without racing the save.
    @Suppress("UnsafeCoroutineCrossing")
    fun start(cache: AudioCacheManager, taken: AudioCacheManager.TakenAudio) {
      val previous = jobs.lastOrNull()
      val saved = AtomicReference(emptyList<Event>())
      // Detached, so a stuck save never blocks the run.
      val write = saveScope.launch {
        try {
          val acc = mutableListOf<Event>()
          cache.write(taken) { event ->
            acc += event
            saved.store(acc.toList())
          }
        } catch (e: CancellationException) {
          throw e
        } catch (e: Throwable) {
          // Logged, not rethrown: an uncaught throw here would crash the process on Android.
          logger.error { "Failed to save live audio (${e::class.simpleName})." }
        }
      }
      jobs += scope.launch {
        // Wait for the save, unless [finish] cuts the wait short first.
        select {
          write.onJoin {}
          cutOff.onAwait {}
        }
        // A later turn's save can finish first; its recordings still wait for this one.
        previous?.join()
        // All of it on a normal finish, what was stored so far on a cut-off.
        for (recording in saved.load()) deliver(recording)
      }
    }

    /** Whether recordings can still be started here; false once this connection's scope ended. */
    val isActive: Boolean
      get() = scope.isActive

    /**
     * Waits up to [SAVE_TIMEOUT] for the saves to finish and deliver in turn order. On timeout it
     * stops waiting and returns false: each delivery then emits only what its save had already
     * stored, and a save that ignores cancellation is left to finish detached, its later recordings
     * dropped, so it cannot hold the run open.
     */
    suspend fun finish(): Boolean {
      val running = jobs.filter { it.isActive }
      if (running.isEmpty()) {
        saveScope.cancel()
        return true
      }
      if (withTimeoutOrNull(SAVE_TIMEOUT) { running.joinAll() } != null) {
        saveScope.cancel()
        return true
      }
      logger.error { "Cut off live audio save(s) still running after $SAVE_TIMEOUT." }
      // Release the delivery jobs to emit what was stored so far; they are bounded now, so join
      // them, then stop any lingering save (best effort; a non-cancellable one finishes detached).
      cutOff.complete(Unit)
      running.joinAll()
      saveScope.cancel()
      return false
    }

    companion object {
      /** How long to wait for recordings still saving before moving on without them. */
      val SAVE_TIMEOUT = 10.seconds
    }
  }

  /**
   * Runs one live response through the response processors and tool calls, as ADK Python's live
   * postprocessing does; the model callbacks run only where [receiveTurns] calls them.
   */
  private suspend fun emitLiveResponse(
    request: LlmRequest,
    response: LlmResponse,
    callbackContext: CallbackContext,
    emitEvent: suspend (Event) -> Unit,
  ) {
    val baseEvent =
      createModelResponseEvent()
        .copy(author = liveAuthorFor(response))
        .withActionsFrom(callbackContext)
    // Live runs record no call_llm span, as in ADK Python.
    processModelResponse(request, response, baseEvent, span = NoOpSpan, emitEvent = emitEvent)
    flushAudioOnControlEvent(response, emitEvent)
  }

  /**
   * Runs the before-model callbacks over [request] carrying only [content], as ADK Python screens
   * each live user input; a continuing callback's state and artifact writes go straight to the
   * session on an event of their own, as ADK Python adds no event to the stream for them.
   *
   * @return the completed turn to emit when a callback returns [CallbackChoice.Break], else the
   *   content to continue with: the returned request's content when it holds exactly one, else
   *   [content].
   */
  private suspend fun screenUserContent(
    request: LlmRequest,
    content: Content,
  ): CallbackChoice<Content, Event> {
    val screened = request.copy(contents = listOf(content))
    val callbackContext = CallbackContext(context)
    val result =
      runBeforeModelCallbacksPipeline(
        callbacks = context.pluginManager.beforeModelCallbacks + agent.beforeModelCallbacks,
        context = callbackContext,
        request = screened,
      )
    return when (result) {
      is CallbackChoice.Continue -> {
        saveCallbackWrites(callbackContext)
        val contents = result.value.contents
        if (contents.size != 1) {
          logger.warn {
            "Before-model callbacks returned ${contents.size} contents for a live input; keeping " +
              "the original. Return a response to block it."
          }
        }
        CallbackChoice.Continue(contents.singleOrNull() ?: content)
      }
      is CallbackChoice.Break ->
        CallbackChoice.Break(blockedTurnEvent(screened, result.value, callbackContext))
    }
  }

  /**
   * Runs the after-model callbacks over [response] carrying the turn's output transcription so far,
   * [spoken], as ADK Python does. A continuing callback's state and artifact writes are saved on an
   * event of their own, since the partial chunk that carries them is never saved.
   *
   * @return the completed turn to emit when a callback returns a different response, else null.
   */
  private suspend fun screenModelOutput(
    request: LlmRequest,
    response: LlmResponse,
    spoken: String,
    callbackContext: CallbackContext,
  ): Event? {
    val screened =
      response.copy(outputTranscription = Transcription(text = spoken, finished = false))
    val processed =
      runAfterModelCallbacksPipeline(
        callbacks = context.pluginManager.afterModelCallbacks + agent.afterModelCallbacks,
        context = callbackContext,
        response = screened,
      )
    // A Kotlin callback cannot return "nothing", so passing means returning an equal response.
    if (processed == screened) {
      if (response.partial) saveCallbackWrites(callbackContext)
      return null
    }
    // The accumulated transcription is ours: drop it unless the callback replaced it.
    val transcription = processed.outputTranscription.takeIf { it != screened.outputTranscription }
    return blockedTurnEvent(
      request,
      processed.copy(outputTranscription = transcription),
      callbackContext,
    )
  }

  /**
   * The completed model turn that stands in for input or output a callback blocked; it is final
   * even when the callback copied a partial chunk, so the caller stores it.
   */
  private suspend fun blockedTurnEvent(
    request: LlmRequest,
    response: LlmResponse,
    callbackContext: CallbackContext,
  ): Event =
    createModelResponseEvent()
      .withActionsFrom(callbackContext)
      .finalizeModelResponseEvent(
        response.copy(partial = false, turnComplete = true),
        getToolMap(request),
      )

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
      // One context for this step's before/after-model and on-model-error callbacks; their
      // accumulated actions reach the emitted event via withActionsFrom.
      var modelResponseEvent = createModelResponseEvent()
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
            modelResponseEvent = modelResponseEvent.withActionsFrom(callbackContext)
            processModelResponse(request, result.value, modelResponseEvent, span) { emit(it) }
            return@tracedFlow
          }
        }

      span.recordCallLlmRequest(currentRequest)

      // Enforce RunConfig.maxLlmCalls. After before-model callbacks so a short-circuiting callback
      // doesn't consume the budget (parity with Python ADK base_llm_flow); throwing aborts the run.
      context.incrementLlmCallsCount()

      // Present even if the call emits nothing: trace consumers drop call_llm spans without it.
      span[TelemetryAttributes.GCP_VERTEX_AGENT_EVENT_ID] = modelResponseEvent.id
      // Tracks the last response seen so response-derived span attributes (usage, finish reasons,
      // serialized response) reflect the final value, matching Python's single `trace_call_llm`.
      var lastResponse: LlmResponse? = null

      invokeModel(currentRequest, callbackContext, span, spanContext).collect { response ->
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

        modelResponseEvent = modelResponseEvent.withActionsFrom(callbackContext)
        processModelResponse(currentRequest, currentResponse, modelResponseEvent, span) {
          modelResponseEvent =
            modelResponseEvent.copy(
              // A streamed reply keeps one id until its complete event, as in ADK Python.
              id = if (it.partial) modelResponseEvent.id else Uuid.random(),
              timestamp = Clock.System.now().toEpochMilliseconds(),
            )
          emit(it)
        }
      }

      // Response-derived span attributes (parity with Python `trace_call_llm`).
      lastResponse?.let { span.recordCallLlmResponse(it) }
    }

  /**
   * Streams the model's responses to [request]. Only the model's own failures go to the
   * onModelError callbacks, which may replace a failure with a response, as Python's
   * `run_and_handle_error` does.
   */
  private fun invokeModel(
    request: LlmRequest,
    callbackContext: CallbackContext,
    span: Span,
    spanContext: TelemetryContextElement,
  ): Flow<LlmResponse> {
    val isStreaming = context.runConfig?.streamingMode == StreamingMode.SSE
    // Inside the flow so a model throwing before returning its stream still reaches onModelError.
    return flow { emitAll(agent.model.generateContent(request, stream = isStreaming)) }
      // flowOn(spanContext) puts the model client's stream under the span without affecting the
      // outer flow's emission context (which must stay synchronous w.r.t. the collector).
      .flowOn(spanContext)
      .onEach { span.addEvent("chunk_received") }
      .catch { e ->
        // Recover only Exceptions, as Python does; rethrow cancellation, which is one in Kotlin.
        if (e !is Exception || e is CancellationException) throw e
        val allOnModelErrorCallbacks =
          context.pluginManager.onModelErrorCallbacks + agent.onModelErrorCallbacks
        when (
          val result =
            runOnModelErrorCallbacksPipeline(
              callbacks = allOnModelErrorCallbacks,
              context = callbackContext,
              request = request,
              error = e,
            )
        ) {
          is CallbackChoice.Continue -> throw e
          is CallbackChoice.Break -> {
            span.recordException(e)
            emit(result.value)
          }
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
    // OTel wants the exact string sent to the provider, which is the enum name (e.g. "HIGH").
    request.config.thinkingConfig?.thinkingLevel?.let {
      this[TelemetryAttributes.GEN_AI_REQUEST_REASONING_LEVEL] = it.name
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
    this[TelemetryAttributes.GCP_VERTEX_AGENT_LLM_RESPONSE] = capturedJson {
      response.toTracePayload()
    }
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

  /**
   * Who a live response is from: the user for an input transcription or user-role content, or this
   * agent otherwise. A live model reports what it heard as well as what it said.
   */
  private fun liveAuthorFor(response: LlmResponse): String =
    if (response.inputTranscription != null || response.content?.role == Role.USER) Role.USER
    else agent.name

  private fun createModelResponseEvent() =
    Event(
      id = Uuid.random(),
      invocationId = context.invocationId,
      author = agent.name,
      branch = context.branch,
    )

  /**
   * Returns this event carrying the actions [callbackContext] accumulated, so a model callback's
   * writes reach the session. Python and Java ADK instead alias the event's actions into the
   * context; Kotlin cannot, because [CallbackContext.updateState] replaces the actions object
   * rather than mutating it. The whole object moves, so control-flow signals cross too, not just
   * the deltas.
   *
   * The base event holds the live [EventActions][com.google.adk.kt.events.EventActions] that
   * in-step writers such as `CallbackContext.saveArtifact` and
   * [EventActions.removeStateByKey][com.google.adk.kt.events.EventActions.removeStateByKey] mutate
   * in place. `finalizeModelResponseEvent` snapshots those actions onto each emitted event (via
   * `EventActions.snapshot`), so a later write -- e.g. `LlmAgent.maybeSaveOutputToState` on the
   * final event -- no longer leaks back into an already-emitted partial. This matches ADK Python
   * 1.x's per-event snapshot; Python 2.x and Java share one mutable instance across the step.
   */
  private fun Event.withActionsFrom(callbackContext: CallbackContext): Event =
    if (actions === callbackContext.eventActions) this
    else copy(actions = callbackContext.eventActions)

  private suspend fun processModelResponse(
    request: LlmRequest,
    response: LlmResponse,
    baseEvent: Event,
    span: Span,
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
    // Last write wins, so a trace lookup by the last saved event's id finds this span.
    span[TelemetryAttributes.GCP_VERTEX_AGENT_EVENT_ID] = finalizedEvent.id
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
          // Snapshot so a later in-step write can't mutate an already-emitted partial.
          actions = actions.snapshot(),
          content = response.content,
          usageMetadata = response.usageMetadata,
          finishReason = response.finishReason,
          errorMessage = response.errorMessage,
          partial = response.partial,
          interrupted = response.interrupted,
          cacheMetadata = response.cacheMetadata,
          modelVersion = response.modelVersion,
          avgLogProbs = response.avgLogprobs,
          groundingMetadata = response.groundingMetadata,
          citationMetadata = response.citationMetadata,
          errorCode = response.errorCode,
          customMetadata = response.customMetadata,
          turnComplete = response.turnComplete ?: false,
          turnCompleteReason = response.turnCompleteReason,
          interactionStatus = response.interactionStatus,
          inputTranscription = response.inputTranscription,
          outputTranscription = response.outputTranscription,
          liveSessionId = response.liveSessionId,
          liveSessionResumptionUpdate = response.liveSessionResumptionUpdate,
          voiceActivity = response.voiceActivity,
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

  /**
   * Whether this response carries nothing worth emitting as an event.
   *
   * An error code or grounding metadata alone still counts, as in ADK Python and Java. A live
   * signal or usage metadata alone counts only on a live run, as in ADK Python, whose turn-based
   * check keeps neither; a go-away has no [Event] field, so it never counts.
   */
  private fun LlmResponse.isEmpty(): Boolean {
    return content == null &&
      errorMessage == null &&
      errorCode.isNullOrEmpty() &&
      finishReason == null &&
      !interrupted &&
      groundingMetadata == null &&
      (context.liveRequestQueue == null ||
        (turnComplete != true &&
          inputTranscription == null &&
          outputTranscription == null &&
          liveSessionResumptionUpdate == null &&
          voiceActivity == null &&
          usageMetadata == null))
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

    // A tool's transfer runs the target here, unless a node loop follows it.
    if (!agent.runsInNodeLoop(context)) {
      functionResponseEvent?.actions?.transferToAgent?.let { agentName ->
        // Live runs hand over to the target in `runLiveSession`, so they skip this path.
        if (context.liveRequestQueue != null) return@let
        require(agentName != agent.name) { "Agent '${agent.name}' cannot transfer to itself." }
        // Declared targets only, so the model cannot reach any other agent in the tree.
        val targetAgent =
          requireNotNull(findTransferTargets(agent).firstOrNull { it.name == agentName }) {
            if (agent.rootAgent.findAgent(agentName) == null) {
              "Agent '${agent.name}' requested a transfer to an agent that is not in the agent " +
                "tree."
            } else {
              "Agent '${agent.name}' is not allowed to transfer to the requested agent."
            }
          }
        emitAll(targetAgent.runAsync(context))
      }
    }
  }

  private suspend fun handleFunctionCalls(
    actionEvent: Event,
    tools: Map<String, BaseTool>,
    emitEvent: suspend (Event) -> Unit,
  ): Event? {
    val functionCalls = actionEvent.functionCalls()
    val functionResponseEvent =
      functionCalls
        .takeIf { it.isNotEmpty() }
        ?.let { calls ->
          // A live run runs tools through its streaming tools, so a returned Flow streams.
          val runner: ToolRunner = if (liveTools == null) DEFAULT_TOOL_RUNNER else liveTools::run
          context.handleFunctionCallsWith(calls, tools, toolRunner = runner)
        }
        // A live answer names the session its call came from, as in ADK Python.
        ?.copy(liveSessionId = actionEvent.liveSessionId)

    functionResponseEvent?.let { responseEvent ->
      // A live run can't take an approval yet, so it asks for none, as in ADK Python.
      if (context.liveRequestQueue == null) {
        generateRequestConfirmationEvent(context, actionEvent, responseEvent)?.let { emitEvent(it) }
      }
      emitEvent(responseEvent)
      // When the output-schema-with-tools workaround is active, the model produces its final answer
      // by calling the `set_model_response` tool. Convert that structured response into a synthetic
      // final model-response event so the turn terminates and the output is saved to state.
      getStructuredModelResponse(responseEvent, tools)?.let { json ->
        emitEvent(createFinalModelResponseEvent(context, json))
      }
    }
    return functionResponseEvent
  }

  // Pauses while any long-running call remains unanswered, mirroring Python decide_resume.
  private suspend fun InvocationContext.shouldPause(): Boolean = hasUnansweredPausedCall()
}

private val logger = LoggerFactory.getLogger(LlmAgentTurn::class)

/**
 * How long closing the live connection may take before the run gives up on it.
 *
 * Generous, because a normal close finishes in milliseconds; the bound exists only so a server that
 * never answers cannot hold the run open. A close that blocks its thread is not bounded.
 */
private val CONNECTION_TEARDOWN_TIMEOUT = 10.seconds

/**
 * How the receive loop of one connection stopped: the connection or the caller closed it, a
 * callback blocked the model's output or spoken input, the server announced a go-away, the model
 * handed the conversation to another agent, or a `task_completed` tool answered.
 */
private enum class LiveTurnsEnd {
  CLOSED,
  BLOCKED,
  GO_AWAY,
  TRANSFER,
  TASK_COMPLETED,
}

/**
 * How one connection's turn loop ended.
 *
 * @property end why the receive loop stopped; a transport drop is signalled out of band, by a
 *   thrown failure, and leaves this at [LiveTurnsEnd.CLOSED].
 * @property completedATurn whether the connection delivered at least one turn-complete, which marks
 *   a healthy session and resets the reconnect budget.
 * @property closedByCaller whether the sender drained the caller's closed queue, after which
 *   nothing is resumed.
 * @property writeFailure the error of a failed write of a turn already stored in the session, or of
 *   any write the connection outlived; it fails the run wherever the run would end, but not where
 *   it resumes, restarts or continues after a go-away.
 * @property outlivedWriteFailure whether the receive loop was still running
 *   [CONNECTION_TEARDOWN_TIMEOUT] after the failed write, so the write failed on its own.
 */
private class LiveAttemptResult {
  @Volatile var end: LiveTurnsEnd = LiveTurnsEnd.CLOSED
  @Volatile var completedATurn: Boolean = false
  @Volatile var closedByCaller: Boolean = false
  @Volatile var writeFailure: Throwable? = null
  @Volatile var outlivedWriteFailure: Boolean = false
}

/** A connection write failure other than cancellation or a recoverable transport drop. */
private class LiveWriteFailure(override val cause: Throwable) : Exception(cause)

/**
 * One event on its way to the caller, with the signal that the caller has handled it.
 *
 * The emitter and a producer whose send was cancelled can both see it; whichever claims it first
 * owns it, so it is delivered once.
 */
@OptIn(ExperimentalAtomicApi::class)
private class Delivery(
  val event: Event,
  val handled: CompletableDeferred<Unit> = CompletableDeferred(),
) {
  private val claimed = AtomicBoolean(false)

  fun claim(): Boolean = claimed.compareAndSet(false, true)
}

/**
 * A request taken off the queue, whether its session writes have run, and the content the
 * before-model callbacks returned for it (null until they run).
 *
 * These fields live here rather than on [LiveRequest] because they track this delivery attempt, and
 * [LiveRequest] is public API.
 *
 * @property storedTurn whether those writes stored a user turn, which a failed write must not drop
 *   silently.
 */
private data class PendingSend(
  val request: LiveRequest,
  val persisted: Boolean,
  val screened: Content?,
  val storedTurn: Boolean = false,
)

/**
 * A handover the model asked for: a transfer to [transferTo], or, when that is null, the answer of
 * a `task_completed` tool the agent has, which ends its live run (in a sequence, the step). It
 * holds the tool's [answer], the [event] that carries it, and the signal the sender completes once
 * it has written that answer.
 *
 * @property input the user's speech still to emit after [event], as a finished transcription; null
 *   when there is none or it has reached the caller.
 */
private class PendingHandover(
  val answer: Content?,
  val event: Event,
  val transferTo: String?,
  @Volatile var input: LlmResponse?,
) {
  val written = CompletableDeferred<Unit>()

  /** Whether [event] reached the caller; if not, it is emitted before the handover. */
  @Volatile var delivered = false

  /** Records that [emitted] reached the caller, so the handover does not emit it again. */
  fun recordDelivery(emitted: Event) {
    if (emitted === event) delivered = true
    if (emitted.inputTranscription == input?.inputTranscription) input = null
  }
}

/**
 * How long a handover waits for its `transfer_to_agent` or `task_completed` answer to reach this
 * connection.
 *
 * The answer queues behind the caller's earlier input, so a backlog of audio can delay it; past the
 * bound the handover goes ahead anyway and the answer goes to the next connection, whereas ADK
 * Python never waits for the answer.
 */
private val TRANSFER_ANSWER_TIMEOUT = 5.seconds

/**
 * How long the old connection stays open after a transfer's answer, the figure ADK Python holds in
 * `DEFAULT_TRANSFER_AGENT_DELAY`.
 *
 * Nothing is read from it meanwhile, so anything the model streams during the pause is dropped with
 * the connection, as in ADK Python.
 */
private val TRANSFER_HANDOVER_DELAY = 1.seconds

/**
 * How long a live agent's connection stays open after a `task_completed` answer, the figure ADK
 * Python holds in `DEFAULT_TASK_COMPLETION_DELAY`; as after a transfer, anything the model streams
 * meanwhile is dropped.
 */
private val TASK_COMPLETION_DELAY = 1.seconds

/**
 * Whether this event carries a `task_completed` function response. The model may call it alongside
 * other tools, whose responses merge into one event, so every response is scanned, not just the
 * first (parity with ADK Python).
 */
@JvmSynthetic
internal fun Event.signalsTaskCompleted(): Boolean =
  functionResponses().any { it.name == TASK_COMPLETED_TOOL_NAME }

/**
 * The rest of a finished transcription that starts with the already stored [speech], or null when
 * this response is not one; the connection sends such a transcription on its own.
 */
private fun LlmResponse.finishedInputAfter(speech: String): String? {
  val text = inputTranscription?.takeIf { it.finished == true }?.text ?: return null
  val alone =
    copy(inputTranscription = null, partial = false, modelVersion = null, liveSessionId = null) ==
      LlmResponse()
  return if (alone && text.startsWith(speech)) text.removePrefix(speech).trimStart() else null
}

/** The user's [speech] as a finished transcription on a response of its own, from this session. */
private fun LlmResponse.asFinishedInput(speech: String): LlmResponse =
  LlmResponse(
    inputTranscription = Transcription(text = speech, finished = true),
    modelVersion = modelVersion,
    liveSessionId = liveSessionId,
  )

/**
 * Span over the history a live run sends at connect, which no `call_llm` span covers; named as in
 * Python.
 */
private const val SEND_DATA_SPAN = "send_data"

/**
 * Replaces inline media with its MIME type and byte count and drops thought signatures so the span
 * stays small and readable.
 */
@OptIn(FrameworkInternalApi::class)
private fun List<Content>.toLiveTracePayload(): JsonElement =
  adkJson.encodeToJsonElement(
    map { content ->
      content.copy(
        parts =
          content.parts.map { part ->
            val blob = part.inlineData
            if (blob == null) {
              part.copy(thoughtSignature = null)
            } else {
              val mimeType = blob.mimeType?.takeIf { it.isNotEmpty() } ?: "unknown"
              Part(text = "<inline_data: $mimeType, ${blob.data?.size ?: 0} bytes>")
            }
          }
      )
    }
  )

/**
 * Whether this failure is a recoverable live transport drop rather than a server refusal.
 *
 * Checks the close code because the SDK raises every abnormal close with the same type and status;
 * only an abnormal disconnect (1006) or an internal server error (1011) is retried, codes ADK
 * Python also retries. Policy, invalid-argument and auth closes are refusals; retrying would loop.
 */
private fun Throwable.isLiveTransportDrop(): Boolean =
  this is GenAiApiException && status == CONNECTION_CLOSED_STATUS && code in RECOVERABLE_CLOSE_CODES

/** The status the Gen AI SDK gives a websocket that closed abnormally. */
private const val CONNECTION_CLOSED_STATUS = "ConnectionClosed"

/**
 * The websocket close codes a dropped live connection is reopened for: an abnormal disconnect and
 * an internal server error. A clean close raises nothing in the SDK, so 1000 is not among them.
 */
private val RECOVERABLE_CLOSE_CODES = setOf(1006, 1011)

/**
 * How many reconnects in a row a live run makes before giving up.
 *
 * Drops and clean closes count toward the limit; a go-away resets it, since ADK Python never bounds
 * one, and so does any connection that completes a turn. This bounds reconnects when a server
 * accepts connections but drops or closes them before completing a turn.
 */
private const val MAX_RECONNECT_ATTEMPTS = 5

private val BASE_RECONNECT_DELAY = 250.milliseconds

/**
 * How long to wait before opening the next live connection: full jitter up to an exponential
 * ceiling (at most 4 s under [MAX_RECONNECT_ATTEMPTS]), so clients that lost the same server do not
 * reconnect in lockstep.
 *
 * Takes [random] for testing, and is `@JvmSynthetic` to stay off the Java surface.
 */
@JvmSynthetic
internal fun liveReconnectDelay(attempt: Int, random: Random = Random.Default): Duration {
  // attempt is 1-based, so the first reconnect's ceiling is the base, not twice it.
  val ceiling = BASE_RECONNECT_DELAY * (1 shl (attempt - 1))
  return random.nextLong(0, ceiling.inWholeMilliseconds + 1).milliseconds
}

/**
 * Returns a copy of this request configured to resume [handle].
 *
 * Preserves the caller's [SessionResumptionConfig] (such as `transparent`) and replaces only the
 * handle with the newest one.
 */
private fun LlmRequest.resumingFrom(handle: String): LlmRequest =
  copy(
    liveConnectConfig =
      liveConnectConfig.copy(
        sessionResumption =
          liveConnectConfig.sessionResumption?.copy(handle = handle)
            ?: SessionResumptionConfig(handle = handle)
      )
  )

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
