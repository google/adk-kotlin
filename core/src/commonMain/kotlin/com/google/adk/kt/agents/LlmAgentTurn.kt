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
import com.google.adk.kt.processors.generateRequestConfirmationEvent
import com.google.adk.kt.processors.getStructuredModelResponse
import com.google.adk.kt.processors.liveTransferTargets
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
import com.google.genai.kotlin.GenAiApiException
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
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
 */
@OptIn(ExperimentalAtomicApi::class)
internal class LlmAgentTurn(
  private val agent: LlmAgent,
  private val context: InvocationContext,
  private val requestProcessors: List<LlmRequestProcessor>,
  private val responseProcessors: List<LlmResponseProcessor>,
) {

  /**
   * Keeps the run's audio when `RunConfig.saveLiveBlob` asks for it; null records nothing. It is
   * also null without an artifact service, which leaves nowhere to save a recording.
   */
  private val audioCache: AudioCacheManager? by lazy {
    when {
      context.runConfig?.saveLiveBlob != true -> null
      context.artifactService == null -> {
        logger.warn {
          "RunConfig.saveLiveBlob is set but the run has no artifact service, so live audio is " +
            "not recorded."
        }
        null
      }
      else -> AudioCacheManager(context)
    }
  }

  /**
   * The request the send loop has taken off the queue but not yet written.
   *
   * Kept across reconnects because the unlimited channel preserves only requests still in the
   * queue; a request already taken is lost when the sender is cancelled.
   */
  private val unsentRequest = AtomicReference<PendingSend?>(null)

  /**
   * The newest session resumption handle, or null when there is none; a reconnect resumes from it.
   *
   * Held on this turn and never persisted, so a restart or a transfer begins with a new turn and no
   * handle. Without a handle, a dropped connection fails rather than silently starting a new
   * conversation.
   */
  private val resumptionHandle = AtomicReference<String?>(null)

  /** The transfer the model asked for, kept so that a drop before the handover does not lose it. */
  private val pendingTransfer = AtomicReference<PendingTransfer?>(null)

  /**
   * A callback's reply to a blocked input, held until the collector confirms delivery so a drop
   * mid-emit cannot lose it; [runLiveSession] emits it after the attempt if still undelivered, as
   * ADK Python enqueues the reply before waiting.
   */
  private val pendingBlockedReply = AtomicReference<Event?>(null)

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
  fun executeLive(): Flow<Event> = flow {
    var turn = this@LlmAgentTurn
    // Restarting in a loop keeps repeated restarts from nesting flows.
    while (turn.runLiveSession(this) && !turn.context.isEndOfInvocation) {
      turn = turn.restartedTurn()
    }
    // Each restart runs on a copy, so an invocation one of them ended has to end here too.
    if (turn.context.isEndOfInvocation) context.isEndOfInvocation = true
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

    val queue =
      checkNotNull(context.liveRequestQueue) {
        "a live run needs a live request queue on its context"
      }
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

    // One event id for this live session's history seeding.
    val sendDataEventId = Uuid.random()

    var attempt = 0
    while (true) {
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
              this[TelemetryAttributes.GCP_VERTEX_AGENT_EVENT_ID] = sendDataEventId
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
          } catch (t: Throwable) {
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
      // A write error with no drop behind it fails the run here; a drop is handled below.
      if (droppedBy == null) result.writeFailure?.let { throw it }
      // Deliver any blocked reply the sender could not finish emitting before branching.
      pendingBlockedReply.exchange(null)?.let { collector.emit(it) }

      // A blocked turn starts a fresh session, as ADK Python's restart does; it is not a drop.
      if (result.end == LiveTurnsEnd.BLOCKED) return true

      val goAway = result.end == LiveTurnsEnd.GO_AWAY
      // Stop once the caller hung up, unless a go-away still has queued input left to drain.
      if (result.closedByCaller || (!goAway && queue.isClosed)) {
        if (droppedBy != null) throw droppedBy
        break
      }

      // A drop can end the attempt before the receive loop records the transfer; take it from here.
      val transferTo = result.transferTo ?: pendingTransfer.exchange(null)?.agentName
      // Transfer before the drop rethrow, so a drop during the handover still hands over.
      transferTo?.let { agentName ->
        val target =
          if (agentName == agent.name) agent
          else
            liveTransferTargets(agent).firstOrNull { it.name == agentName }
              ?: throw IllegalArgumentException("Transfer target is not allowed from this agent.")
        collector.emitAll(target.runLive(context.forLiveChild(target)))
        return false
      }

      // Without a handle a drop fails the run; a go-away continues in a fresh session below.
      if (droppedBy != null && !goAway && resumptionHandle.load() == null) throw droppedBy
      // A go-away with no handle cannot resume, so it reopens a fresh session.
      val freshRestart = goAway && resumptionHandle.load() == null
      // A clean close with a handle reconnects, as ADK Python does; with none the run ends.
      if (!freshRestart && resumptionHandle.load() == null) break
      // A go-away never counts against the budget; a completed turn resets it too.
      if (result.completedATurn || goAway) attempt = 0
      if (attempt >= MAX_RECONNECT_ATTEMPTS) {
        // The exhausting drop surfaces as itself; a go-away is reset above and never reaches here.
        if (droppedBy != null) throw droppedBy
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
          if (droppedBy != null) throw droppedBy
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
   * closes the queue, the connection ends or retires, a transfer starts, or a callback blocks the
   * model's output or spoken input. Records how it stopped in [result]; the flow completes only
   * once the sender has stopped.
   */
  // Only the sender writes the connection; the turn and its caller append under one shared lock.
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
          val handled = CompletableDeferred<Unit>()
          send(event to handled)
          handled.await()
        }
        val receiver = async { receiveTurns(request, connection, queue, result, deliver) }
        /**
         * Writes [pending]; on a [LiveWriteFailure] it waits for `receiver` to end and, if it is
         * still running after [CONNECTION_TEARDOWN_TIMEOUT], records the error and closes. Returns
         * false after a failed write, so the sender stops and keeps the request to replay.
         */
        suspend fun sendOrReplay(pending: PendingSend): Boolean {
          try {
            writeRequest(request, connection, pending, deliver)
            return true
          } catch (e: LiveWriteFailure) {
            if (withTimeoutOrNull(CONNECTION_TEARDOWN_TIMEOUT) { receiver.join() } == null) {
              result.writeFailure = e.cause
              if (!close()) receiver.cancel()
            }
            return false
          }
        }
        val sender = launch {
          // Re-send what the dead attempt had taken off the queue but not yet written.
          unsentRequest.load()?.let { pending -> if (!sendOrReplay(pending)) return@launch }
          unsentRequest.store(null)
          while (true) {
            val liveRequest = queue.receiveRequest() ?: break
            // Held until the write returns, so a cancellation in between re-sends it.
            val pending = PendingSend(liveRequest, persisted = false, screened = null)
            unsentRequest.store(pending)
            if (!sendOrReplay(pending)) return@launch
            unsentRequest.store(null)
          }
          // receiveRequest returns null only once the caller has closed the queue.
          result.closedByCaller = true
          val closedInTime = close()
          // A close that timed out may never end the connection's receive, so stop reading too.
          if (!closedInTime) receiver.cancel()
        }

        try {
          receiver.await()
        } catch (e: CancellationException) {
          // Only the sender cancels the receiver; a cancellation of this scope still throws.
          ensureActive()
        }
        // Joined so the sender has stopped before the caller restarts or reconnects.
        sender.cancelAndJoin()
      }
      .buffer(Channel.RENDEZVOUS)
      .transform { (event, handled) ->
        emit(event)
        handled.complete(Unit)
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
          spoken = spokenSoFar(spoken, response)
          val blocked = handleLiveResponse(request, response, spoken, queue, emitEvent)
          pendingTransfer.load()?.let { transfer ->
            // Recorded before the handover, so a drop during it still hands over.
            result.transferTo = transfer.agentName
            handOverConnection(transfer, queue)
            end = LiveTurnsEnd.TRANSFER
          }
          // A transfer beats a blocked input in the same response: any answer is already queued.
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

  /** The turn's output transcription so far, [spoken], updated with [response]. */
  private fun spokenSoFar(spoken: String, response: LlmResponse): String {
    val turnSoFar = if (response.turnComplete == true || response.interrupted) "" else spoken
    val heard = response.outputTranscription ?: return turnSoFar
    return if (heard.finished == true) "" else turnSoFar + heard.text.orEmpty()
  }

  /**
   * Screens and emits one live [response], answering its tool calls through [queue].
   *
   * @return whether a callback blocked the model's output or the user's spoken input.
   */
  private suspend fun handleLiveResponse(
    request: LlmRequest,
    response: LlmResponse,
    spoken: String,
    queue: LiveRequestQueue,
    emitEvent: suspend (Event) -> Unit,
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
      emitEvent(event)
      // Cut-off audio, or audio that would land in the next turn, is left out.
      if (!response.interrupted && response.turnComplete != true) cacheModelAudio(event)
      event.actions.transferToAgent?.let { name ->
        // Keep an answer to wait for only when there is one; a non-tool transfer has none.
        pendingTransfer.store(
          PendingTransfer(
            name,
            event.content?.takeIf { c -> c.parts.any { it.functionResponse != null } },
          )
        )
      }
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
    return LlmAgentTurn(agent, restartContext, requestProcessors, responseProcessors)
  }

  /**
   * Gives the model's turn time to finish before another agent takes the conversation over.
   *
   * The `transfer_to_agent` answer queues behind whatever the caller sent first, so this waits up
   * to [TRANSFER_ANSWER_TIMEOUT] for the sender to write it to this connection rather than leave it
   * for the child's, then pauses for [TRANSFER_HANDOVER_DELAY] as ADK Python does. Both waits end
   * early if the caller closes the queue, since no transfer follows a hang-up.
   */
  // The queue is only ever awaited here, never written, so the cross-coroutine read is safe.
  @Suppress("UnsafeCoroutineCrossing")
  private suspend fun handOverConnection(transfer: PendingTransfer, queue: LiveRequestQueue) {
    if (transfer.answer != null) {
      val wrote =
        withTimeoutOrNull(TRANSFER_ANSWER_TIMEOUT) {
          select {
            transfer.written.onAwait { true }
            queue.closed.onAwait { false }
          }
        }
      if (wrote == null) logger.warn { "The transfer answer was not sent before the handover." }
    }
    // The pause lets the model's turn settle, unless the caller has already hung up.
    withTimeoutOrNull(TRANSFER_HANDOVER_DELAY) { queue.closed.await() }
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
    if (context.sessionService == null) return false
    appendToSession(
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
    // Taken off the queue already, so a restart or end must not cancel it mid-save and lose it.
    withContext(NonCancellable) { appendToSession(event) }
  }

  /**
   * Appends [event] to the session under the invocation's append lock, which the caller also takes
   * for its own appends; nothing is emitted while the lock is held.
   */
  private suspend fun appendToSession(event: Event) {
    val sessionService = context.sessionService
    if (sessionService == null) {
      logger.warn { "A live session event was dropped: the context has no session service." }
      return
    }
    // Checked in the finally: lock() can return and the wait still time out or be cancelled.
    var locked = false
    try {
      // Bound the wait for the lock, so a hung holder fails the run instead of hanging it.
      withTimeoutOrNull(SESSION_APPEND_TIMEOUT) {
        context.sessionAppendLock.lock()
        locked = true
      }
      check(locked) { "A live session append waited over $SESSION_APPEND_TIMEOUT for the session." }
      // Non-cancellable so a committed append is not repeated; a timeout still fails the run.
      withContext(NonCancellable) {
        withTimeoutOrNull(SESSION_APPEND_TIMEOUT) {
          val unused = sessionService.appendEvent(context.session, event)
        } ?: error("A live session append did not finish in $SESSION_APPEND_TIMEOUT.")
      }
    } finally {
      if (locked) context.sessionAppendLock.unlock()
    }
  }

  /** Saves a continuing callback's state and artifact writes on an event of their own. */
  private suspend fun saveCallbackWrites(callbackContext: CallbackContext) {
    val actions = callbackContext.eventActions
    if (actions.stateDelta.isEmpty() && actions.artifactDelta.isEmpty()) return
    appendToSession(createModelResponseEvent().copy(actions = actions.snapshot()))
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
      val persisted =
        input is ContentInput &&
          content != null &&
          persistUserTurn(liveRequest, input, content, answersTools)
      // A state delta reaches the session even when nothing was said to carry it.
      if (!persisted) persistStateDeltaOnly(liveRequest)
      // Marked before the write, so a replay after a drop redoes none of the above.
      unsentRequest.store(unsentRequest.load()?.copy(persisted = true))
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
                  unsentRequest.store(unsentRequest.load()?.copy(screened = screened.value))
                  // A sender cancelled mid-screen stops here, so a replay sends, not re-screens.
                  currentCoroutineContext().ensureActive()
                  screened.value
                }
                is CallbackChoice.Break -> {
                  // Dropped before the event, so a reconnect neither re-sends nor re-screens it.
                  unsentRequest.store(null)
                  // Kept so a drop mid-emit cannot lose it; runLiveSession re-emits it after.
                  pendingBlockedReply.store(screened.value)
                  emitEvent(screened.value)
                  return
                }
              }
          }
        sendingToConnection { connection.sendContent(toSend, input.partial) }
        val transfer = pendingTransfer.load()
        if (transfer?.answer == sent) {
          val unused = transfer.written.complete(Unit)
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
  private fun sendToolResponse(queue: LiveRequestQueue, event: Event) {
    val content = event.content ?: return
    if (content.parts.none { it.functionResponse != null }) return
    queue.sendContent(content)
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
   * Writes the recorded audio out when a turn ends, emitting one event per recording ahead of the
   * control event. An interruption flushes only the model's side, since the caller is mid-sentence.
   *
   * The control event still follows, unlike in ADK Python, so turning on `RunConfig.saveLiveBlob`
   * does not hide `interrupted` or `turnComplete` from the caller.
   */
  private suspend fun flushAudioOnControlEvent(
    response: LlmResponse,
    emitEvent: suspend (Event) -> Unit,
  ) {
    val cache = audioCache ?: return
    val flushed =
      when {
        response.interrupted -> cache.flush(flushUserAudio = false, flushModelAudio = true)
        response.turnComplete == true -> cache.flush(flushUserAudio = true, flushModelAudio = true)
        else -> return
      }
    for (event in flushed) emitEvent(event)
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
    flushAudioOnControlEvent(response, emitEvent)
    val baseEvent =
      createModelResponseEvent()
        .copy(author = liveAuthorFor(response))
        .withActionsFrom(callbackContext)
    // Live runs record no call_llm span, as in ADK Python.
    processModelResponse(request, response, baseEvent, span = NoOpSpan, emitEvent = emitEvent)
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

    // If a tool requested a transfer to another agent, execute that agent's loop.
    functionResponseEvent?.actions?.transferToAgent?.let { agentName ->
      // Live runs hand over to the target in `runLiveSession`, so they skip this path.
      if (context.liveRequestQueue != null) return@let
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
      functionCalls
        .takeIf { it.isNotEmpty() }
        ?.let { context.handleFunctionCalls(it, tools) }
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
      getStructuredModelResponse(responseEvent)?.let { json ->
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

/** How long a live session append may wait for the lock or the write before failing the run. */
private val SESSION_APPEND_TIMEOUT = 10.seconds

/**
 * How the receive loop of one connection stopped: the connection or the caller closed it, a
 * callback blocked the model's output or spoken input, the server announced a go-away, or the model
 * handed the conversation to another agent.
 */
private enum class LiveTurnsEnd {
  CLOSED,
  BLOCKED,
  GO_AWAY,
  TRANSFER,
}

/**
 * How one connection's turn loop ended.
 *
 * @property end why the receive loop stopped; a transport drop is signalled out of band, by a
 *   thrown failure, and leaves this at [LiveTurnsEnd.CLOSED].
 * @property completedATurn whether the connection delivered at least one turn-complete, which marks
 *   a healthy session and resets the reconnect budget.
 * @property transferTo the agent the model handed the conversation to, or null.
 * @property closedByCaller whether the sender drained the caller's closed queue, after which
 *   nothing is resumed.
 * @property writeFailure a non-drop write error when the receive loop did not end within
 *   [CONNECTION_TEARDOWN_TIMEOUT], which fails the run after the attempt unless the receive side
 *   then drops.
 */
private class LiveAttemptResult {
  @Volatile var end: LiveTurnsEnd = LiveTurnsEnd.CLOSED
  @Volatile var completedATurn: Boolean = false
  @Volatile var transferTo: String? = null
  @Volatile var closedByCaller: Boolean = false
  @Volatile var writeFailure: Throwable? = null
}

/** A connection write failure other than cancellation or a recoverable transport drop. */
private class LiveWriteFailure(override val cause: Throwable) : Exception(cause)

/**
 * A request taken off the queue, whether it has been recorded in the session, and the content the
 * before-model callbacks returned for it (null until they run).
 *
 * These fields live here rather than on [LiveRequest] because they track this delivery attempt, and
 * [LiveRequest] is public API.
 */
private data class PendingSend(
  val request: LiveRequest,
  val persisted: Boolean,
  val screened: Content?,
)

/**
 * A handover to [agentName], with its `transfer_to_agent` [answer] and the signal the sender
 * completes once it has written that answer.
 */
private class PendingTransfer(val agentName: String, val answer: Content?) {
  val written = CompletableDeferred<Unit>()
}

/**
 * How long a handover waits for the `transfer_to_agent` answer to reach this connection.
 *
 * The answer queues behind the caller's earlier input, so a backlog of audio can delay it; past the
 * bound the handover goes ahead anyway and the answer goes to the next connection, whereas ADK
 * Python never waits for the answer.
 */
private val TRANSFER_ANSWER_TIMEOUT = 5.seconds

/**
 * How long the old connection stays open after the handover answer, the figure ADK Python holds in
 * `DEFAULT_TRANSFER_AGENT_DELAY`.
 *
 * Nothing is read from it meanwhile, so anything the model streams during the pause is dropped with
 * the connection, as in ADK Python.
 */
private val TRANSFER_HANDOVER_DELAY = 1.seconds

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
