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

@file:OptIn(ExperimentalLiveApi::class, ExperimentalAtomicApi::class)

package com.google.adk.kt.agents

import com.google.adk.kt.annotations.ExperimentalLiveApi
import com.google.adk.kt.events.Event
import com.google.adk.kt.logging.LoggerFactory
import com.google.adk.kt.models.ContentInput
import com.google.adk.kt.processors.OUT_OF_TURN_ANSWER_KEY
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.CoroutineContext
import kotlin.jvm.JvmSynthetic
import kotlin.jvm.Volatile
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Maps each stop-tool name a live run intercepts to the argument naming the tool to stop:
 * `stop_streaming(function_name)` and `stopStreaming(functionName)`, the camelCase name a Kotlin
 * `@Tool` function declares by default.
 */
private val STOP_STREAMING_ARGS =
  mapOf("stop_streaming" to "function_name", "stopStreaming" to "functionName")

private const val ERROR_KEY = "error"

/**
 * Wraps [value] in a user [Content] carrying a [FunctionResponse] for [tool] and [callId], with the
 * tool's [BaseTool.responseScheduling]. `Unit` becomes `{"result": null}`, and a [FunctionResponse]
 * [value] contributes its payload and, when set, its own scheduling; its name and id are replaced,
 * since the call it answers is not the tool's to choose.
 */
private fun functionResponseContent(tool: BaseTool, callId: String, value: Any?): Content {
  val payload = if (value is FunctionResponse) value.response else value
  return Content(
    role = Role.USER,
    parts =
      listOf(
        Part(
          functionResponse =
            FunctionResponse(
              name = tool.name,
              response = toFinalResponseMap(payload.takeUnless { it === Unit }),
              id = callId,
              scheduling = (value as? FunctionResponse)?.scheduling ?: tool.responseScheduling,
            )
        )
      ),
  )
}

/**
 * Tells the model that its live call [callId] to [tool] failed. The exception's text is left out:
 * it can carry the user's or the model's data.
 */
@JvmSynthetic
internal fun internalErrorContent(tool: BaseTool, callId: String): Content =
  functionResponseContent(
    tool,
    callId,
    mapOf(ERROR_KEY to "Invoking `${tool.name}()` failed with an internal error."),
  )

/** Replaces the value with [transform] of it, retrying when another thread got there first. */
@JvmSynthetic
internal inline fun <T> AtomicReference<T>.mutate(transform: (T) -> T) {
  while (true) {
    val current = load()
    if (compareAndSet(current, transform(current))) return
  }
}

/**
 * Runs one agent's live streams and non-blocking calls in the background until each finishes, a
 * stream is stopped, or the run ends or hands the conversation over.
 *
 * Each [Event] a tool emits reaches only the caller, and the tool waits until the caller has
 * handled it before continuing; every other value reaches the model as a function response to the
 * call that started it.
 */
internal class LiveStreamingTools(
  private val queue: LiveRequestQueue,
  private val agentName: String,
  private val invocationId: String,
  coroutineContext: CoroutineContext,
) {
  // No parent job, so a stuck tool is left behind; supervised, so one failure spares the others.
  private val scope =
    CoroutineScope(
      coroutineContext.minusKey(Job) +
        SupervisorJob() +
        CoroutineExceptionHandler { _, e ->
          logger.warn { "A live tool failed with ${e::class.simpleName}, not reported." }
        }
    )

  // Each call leaves the list when its job completes, however it ends, started or not.
  private val active = AtomicReference<List<StreamingCall>>(emptyList())

  // Calls that finished since the last stop, whose last values may still be queued.
  private val finished = AtomicReference<List<String>>(emptyList())

  // Failed calls' ids, whose failure answers the next stop must not discard.
  private val kept = AtomicReference<Set<String>>(emptySet())

  // Background answers queued and not yet taken off the queue.
  private val queuedAnswers = AtomicReference<List<QueuedAnswer>>(emptyList())

  // Calls the next stopAll owes a final answer: stopped, ended or never started.
  private val owed = AtomicReference<List<StreamingCall>>(emptyList())

  // Calls whose stream failed, so their final answer says so.
  private val failed = AtomicReference<Set<String>>(emptySet())

  // An event whose hand-off was cancelled after it left the channel; taken first next time.
  private val undelivered = AtomicReference<ToolEvent?>(null)
  private val events =
    Channel<ToolEvent>(Channel.UNLIMITED, onUndeliveredElement = { undelivered.store(it) })

  /**
   * Whether the run's current turn has a handover pending, in which case [onAnswersQueued] starts
   * no stream, since it would only reach the next agent. Each live turn points it at its own
   * handover when its session starts.
   */
  @Volatile var handingOver: () -> Boolean = { false }

  /**
   * One function call to a streaming tool, or to a non-blocking tool running [inBackground], and
   * the coroutine running it.
   */
  private class StreamingCall(
    val tool: BaseTool,
    val callId: String,
    val args: Map<String, Any?>,
    val job: Job,
    val inBackground: Boolean = false,
  ) {
    val toolName: String
      get() = tool.name

    // Repeats told this call is running need a final answer too when this stream is stopped.
    val repeatIds = AtomicReference<List<String>>(emptyList())

    /** Whether [other] calls the same tool with the same arguments while this one still streams. */
    fun isRepeatedBy(other: StreamingCall): Boolean = isRepeatedBy(other.toolName, other.args)

    /**
     * Whether a call to [toolName] with [args], under any id, repeats this one while it streams.
     */
    fun isRepeatedBy(toolName: String, args: Map<String, Any?>): Boolean =
      !inBackground &&
        toolName == this.toolName &&
        args == this.args &&
        // Not yet started still counts: a call in the same response starts once answered.
        !job.isCompleted &&
        !job.isCancelled
  }

  /**
   * One collection of the run's input, with the context it was made through, so [closeInputOf] can
   * end it.
   */
  private class InputCollector(val owner: Context, val channel: Channel<LiveRequest>)

  /** The open collections of the run's input, and whether that input has closed. */
  private class InputState(val collectors: List<InputCollector>, val closed: Boolean)

  private val inputState = AtomicReference(InputState(emptyList(), closed = false))

  /**
   * The run's input from when this flow is collected until the queue closes, the run ends or
   * [owner]'s stream ends. Each collection gets its own channel, dropped when the collection stops.
   */
  fun requests(owner: Context): Flow<LiveRequest> = flow {
    val collector = InputCollector(owner, Channel(Channel.UNLIMITED))
    while (true) {
      val current = inputState.load()
      if (current.closed) return@flow
      val next = InputState(current.collectors + collector, closed = false)
      if (inputState.compareAndSet(current, next)) break
    }
    try {
      // If the owner's stream ended before this registered, closeInputOf has already run.
      if (owner.liveInputSource !== this@LiveStreamingTools) return@flow
      emitAll(collector.channel)
    } finally {
      inputState.mutate { current -> InputState(current.collectors - collector, current.closed) }
    }
  }

  /**
   * Copies [request], just taken off the caller's queue, to every collection of the run's input. It
   * never suspends, so a request the sender takes is copied even if the sender is cancelled before
   * writing it.
   */
  fun copyInput(request: LiveRequest) {
    // A copy to a collection that has stopped is dropped.
    for (collector in inputState.load().collectors) {
      val unused = collector.channel.trySend(request)
    }
  }

  /** Completes every collection of the run's input: the caller hung up, or the run is over. */
  fun closeInput() {
    for (collector in inputState.exchange(InputState(emptyList(), closed = true)).collectors) {
      collector.channel.close()
    }
  }

  /** Completes every collection of the run's input made through [owner], whose stream ended. */
  private fun closeInputOf(owner: Context) {
    for (collector in inputState.load().collectors) {
      if (collector.owner === owner) collector.channel.close()
    }
  }

  /**
   * Runs a live tool call: intercepts a stop tool to cancel the tool it names, or collects a
   * returned [Flow] in the background and answers the call as pending. A repeat of a
   * still-streaming call with the same arguments gets an "already running" answer instead of a
   * second stream.
   */
  suspend fun run(tool: BaseTool, context: ToolContext, args: Map<String, Any?>): Any {
    val stopArg = STOP_STREAMING_ARGS[tool.name]
    if (stopArg != null && stopArg in args) {
      val target = args[stopArg]
      require(target is String) { "${tool.name} requires a string $stopArg." }
      return mapOf(STATUS_KEY to stop(target))
    }
    // Checked before the tool runs, so a repeat skips the work a tool does to start a stream.
    val original = active.load().firstOrNull { it.isRepeatedBy(tool.name, args) }
    if (original != null) {
      // The repeat then gets a final answer too when the running call's stream is stopped.
      val repeatId = context.functionCallId
      if (repeatId != null) original.repeatIds.mutate { ids -> ids + repeatId }
      return alreadyRunning(tool.name)
    }
    val result = tool.run(context, args)
    val stream = result as? Flow<*> ?: return result
    // Every live call has an id: the turn fills in a missing one before any tool runs.
    val callId = checkNotNull(context.functionCallId) { "A live tool call has no id." }
    if (!start(tool, callId, args, context, stream)) return alreadyRunning(tool.name)
    return mapOf(STATUS_KEY to PENDING_STATUS)
  }

  private fun alreadyRunning(toolName: String): Map<String, Any?> =
    mapOf(
      STATUS_KEY to
        "$toolName is already running with these arguments; wait for its updates instead of " +
          "calling again."
    )

  /**
   * Runs call [callId] to the non-blocking [tool] in the background, where [execute] runs its tool
   * pipeline. Its answer reaches the caller, then the model; a failure is answered with an error on
   * a copy of [errorEvent]. A stream it returns starts only if the run is not [handingOver] when
   * the answer arrives; `stop_streaming` leaves the call itself running.
   */
  // Only the launched coroutine reads errorEvent, and the state it shares here is atomic.
  @Suppress("UnsafeCoroutineCrossing")
  fun runInBackground(
    tool: BaseTool,
    callId: String,
    errorEvent: Event,
    execute: suspend () -> Event?,
  ) {
    // Lazy, so the call is listed before it can finish and leave the list.
    val job =
      scope.launch(start = CoroutineStart.LAZY) {
        val answer =
          try {
            execute()
          } catch (e: Throwable) {
            // An Error is answered too: rethrown, it would only be logged and leave the call open.
            val error = failureAnswer(tool, callId, e)
            dropFailedStream(callId)
            // Stamped when the call fails, as a successful answer is when it arrives.
            errorEvent.copy(content = error, timestamp = Clock.System.now().toEpochMilliseconds())
          }
        if (answer != null) deliver(answer)
      }
    val call = StreamingCall(tool, callId, emptyMap(), job, inBackground = true)
    active.mutate { calls -> calls + call }
    kept.mutate { ids -> ids - callId }
    job.invokeOnCompletion {
      finished.mutate { ids -> ids + callId }
      active.mutate { calls -> calls - call }
    }
    job.start()
  }

  /**
   * Starts the streams whose pending answers [content] carries, now that those answers are queued
   * ahead of anything the streams send. Cancels them instead when the queue is closed, since nobody
   * would hear them, or while the run is [handingOver].
   */
  fun onAnswersQueued(content: Content) {
    val ids = content.parts.mapNotNullTo(mutableSetOf()) { it.functionResponse?.id }
    for (call in active.load()) {
      // A background call delivering its own answer is not a stream to start or stop.
      if (call.inBackground || call.callId !in ids) continue
      if (!handingOver() && !queue.isClosed) {
        call.job.start()
      } else {
        call.job.cancel()
        owed.mutate { calls -> calls + call }
      }
    }
  }

  /**
   * Drops what call [callId] set up before it failed, since it is answered with the failure: its
   * stream never starts, and as a repeat it waits for no stream's final answer. A stream already
   * running under the same id keeps running. The next stop keeps answers to the id, since the
   * failure answer shares it with any stream still running or ending under it.
   */
  fun onCallFailed(callId: String) {
    dropFailedStream(callId)
    kept.mutate { ids -> ids + callId }
  }

  private fun dropFailedStream(callId: String) {
    for (call in active.load()) {
      call.repeatIds.mutate { ids -> ids - callId }
      // A started stream under the id belongs to an earlier call.
      if (call.callId == callId && !call.inBackground && !call.job.isActive) call.job.cancel()
    }
  }

  /** Waits for the next event a tool sent the caller, taking an undelivered one first. */
  suspend fun nextEvent(): ToolEvent = undelivered.exchange(null) ?: events.receive()

  /** Puts back an event whose hand-off to the caller never reached it. */
  fun putBack(event: ToolEvent) {
    undelivered.store(event)
  }

  /**
   * Cancels the calls whose streams have not started, before a connection opens. Their pending
   * answers were never queued and never will be, so a repeat of one starts a new stream.
   */
  fun onBeforeConnect() {
    val current = active.load()
    // A stream whose background call has not answered yet starts once that answer is queued.
    val answeredLater = current.filter { it.inBackground }.mapTo(mutableSetOf()) { it.callId }
    for (call in current) {
      if (call.callId in answeredLater) continue
      // Its pending answer may already be stored, so stopAll still owes it a final answer.
      if (!call.job.isActive && !call.job.isCompleted && !call.job.isCancelled) {
        owed.mutate { calls -> calls + call }
      }
      if (!call.job.isActive) call.job.cancel()
    }
  }

  /** Takes every event tools sent the caller that is not delivered yet, oldest first. */
  fun drainEvents(): List<Event> = buildList {
    undelivered.exchange(null)?.let { add(it.event) }
    addAll(generateSequence { events.tryReceive().getOrNull()?.event })
  }

  /**
   * Cancels every running call and waits for them as [stopAll] does, keeping their final answers
   * for the next [stopAll]. The queued answers to [keepAnswersTo] still reach the model.
   */
  suspend fun stopRunning(keepAnswersTo: Set<String> = emptySet()): Unit =
    withContext(NonCancellable) {
      val running = active.exchange(emptyList())
      // Before cancelling: a replaced call, or one stop_streaming ended, needs no stopped answer.
      val ended = running.filter { !it.job.isCancelled }
      owed.mutate { calls -> calls + ended }
      // A call that ignores cancellation is already warned about and left behind.
      if (running.isNotEmpty()) {
        val unused = cancelAndAwait(running)
      }
      // Their values may still be queued, behind an answer whose write dropped the connection.
      val stale = finished.exchange(emptyList()) + running.map { it.callId }
      queue.discardAnswersTo(stale - kept.exchange(emptySet()) - keepAnswersTo)
    }

  /**
   * Cancels every call this run started and waits up to [STOP_TIMEOUT] for them when the live run
   * ends or hands over, even if the caller is cancelled.
   *
   * @return the [FinalAnswers] owed.
   */
  suspend fun stopAll(): FinalAnswers {
    stopRunning()
    // The caller records a stream's answer once its call was answered, a background call's if not.
    val (background, streams) = owed.exchange(emptyList()).partition { it.inBackground }
    return FinalAnswers(streams = finalAnswers(streams), background = finalAnswers(background))
  }

  /** One final answer per id [calls] owe, from the first call that owes it. */
  private fun finalAnswers(calls: List<StreamingCall>): List<Content> {
    val callById: Map<String, StreamingCall> = buildMap {
      for (call in calls) {
        for (callId in listOf(call.callId) + call.repeatIds.load()) {
          getOrPut(callId) { call }
        }
      }
    }
    return callById.map { (callId, call) ->
      functionResponseContent(call.tool, callId, mapOf(STATUS_KEY to finalStatus(call)))
    }
  }

  /** Why [call] ended: a background call was stopped; a stream was stopped, finished, or failed. */
  private fun finalStatus(call: StreamingCall): String =
    when {
      call.inBackground -> backgroundStoppedStatus(call.toolName)
      call.job.isCancelled -> stoppedStatus(call.toolName)
      call.callId in failed.load() -> failedStatus(call.toolName)
      else -> finishedStatus(call.toolName)
    }

  /**
   * Stops every call, closes the run's input ([closeInput]), and releases the scope.
   *
   * @return what [stopAll] returns.
   */
  suspend fun close(): FinalAnswers {
    val stopped = stopAll()
    closeInput()
    events.close()
    scope.cancel()
    return stopped
  }

  /**
   * Starts streaming [stream] for [callId], unless an earlier call to the same tool with the same
   * [args] still streams.
   *
   * @return whether the stream started.
   */
  @Suppress("UnsafeCoroutineCrossing") // Only the launched coroutine touches the stream.
  private fun start(
    tool: BaseTool,
    callId: String,
    args: Map<String, Any?>,
    context: ToolContext,
    stream: Flow<*>,
  ): Boolean {
    // Waits for its pending answer to be queued, so the model hears that first.
    val job = scope.launch(start = CoroutineStart.LAZY) { relay(tool, callId, context, stream) }
    // A copy, so a caller changing its map cannot change what later calls are compared with.
    val call = StreamingCall(tool, callId, args.toMap(), job)
    val stale: List<StreamingCall>
    while (true) {
      val calls = active.load()
      // Live models re-call a tool they already stream; a second stream would repeat each value.
      val original = calls.firstOrNull { it.isRepeatedBy(call) }
      if (original != null) {
        original.repeatIds.mutate { ids -> ids + callId }
        call.job.cancel()
        return false
      }
      // An id answered twice streams once, from the newer call; a background call is not replaced.
      val (sameId, others) = calls.partition { it.callId == callId && !it.inBackground }
      if (active.compareAndSet(calls, others + call)) {
        stale = sameId
        break
      }
    }
    call.job.invokeOnCompletion {
      finished.mutate { ids -> ids + call.callId }
      active.mutate { calls -> calls - call }
    }
    // A new call under a failed call's id has not failed, and the stop discards its answers.
    failed.mutate { ids -> ids - callId }
    kept.mutate { ids -> ids - callId }
    // A stream that ends on its own is owed a final answer too.
    call.job.invokeOnCompletion { cause ->
      if (cause == null) owed.mutate { calls -> calls + call }
    }
    // Repeats told the replaced call was running still wait on this stream's final answer.
    call.repeatIds.mutate { ids -> ids + stale.flatMap { it.repeatIds.load() } }
    for (old in stale) {
      old.job.cancel()
    }
    return true
  }

  private suspend fun relay(tool: BaseTool, callId: String, context: ToolContext, stream: Flow<*>) {
    // Set only while the tool's flow runs; its end closes every collection made through context.
    context.liveInputSource = this
    try {
      stream.collect { value ->
        // A flow that never suspends would otherwise outlive a stop or a handover.
        currentCoroutineContext().ensureActive()
        if (value is Event) {
          sendToCaller(tool, callId, value)
        } else {
          // Not partial: Kotlin never sends a partial tool answer, and the wire ignores it anyway.
          queue.sendContent(functionResponseContent(tool, callId, value))
        }
      }
    } catch (e: Throwable) {
      // Exceptions and TODO()'s NotImplementedError reach the model unless stopped.
      if (e is Error && e !is NotImplementedError) throw e
      val error = failureAnswer(tool, callId, e)
      failed.mutate { ids -> ids + callId }
      queue.sendContent(error)
    } finally {
      context.liveInputSource = null
      closeInputOf(context)
    }
  }

  /**
   * Logs [e]'s class and returns the error answer to call [callId] to [tool]. Rethrows [e] instead
   * when the call was cancelled.
   */
  private suspend fun failureAnswer(tool: BaseTool, callId: String, e: Throwable): Content {
    if (!currentCoroutineContext().isActive) throw e
    logger.warn { "Live tool ${tool.name} failed (${e::class.simpleName})." }
    return internalErrorContent(tool, callId)
  }

  /** Hands a background call's [answer] to the caller and, once the caller has it, to the model. */
  private suspend fun deliver(answer: Event) {
    // A stopped call is owed a stopped answer, so its own late one must not reach the caller.
    currentCoroutineContext().ensureActive()
    val content = answer.content ?: return
    val request = LiveRequest(ContentInput(content))
    // A background answer can land after later turns, which the next run's history must keep.
    val marked = answer.customMetadata.orEmpty() + (OUT_OF_TURN_ANSWER_KEY to true)
    val delivered = ToolEvent(answer.copy(customMetadata = marked))
    val job = currentCoroutineContext().job
    val queued = CompletableDeferred<Unit>()
    // Queued once the caller has it, so a restart then finds it, unless a stop came first.
    delivered.handled.invokeOnCompletion { cause ->
      try {
        if (cause != null || !job.isActive) return@invokeOnCompletion
        // Tracked before it is queued, so the sender taking it always finds the entry.
        val queuedAnswer = QueuedAnswer(answer.id, request)
        queuedAnswers.mutate { answers -> answers + queuedAnswer }
        queue.enqueue(request)
        // A stop since the check may have queued its discard ahead of the answer.
        if (!job.isActive) {
          queuedAnswers.mutate { answers -> answers - queuedAnswer }
          queue.discardRequests(listOf(request))
        }
      } finally {
        queued.complete(Unit)
      }
    }
    // Stored before the model hears it, as an answer in the call's turn would be.
    if (!events.trySend(delivered).isSuccess) return
    // Waits for the handler too, so a stream the call returned starts after its answer is queued.
    queued.await()
    onAnswersQueued(content)
  }

  /**
   * Drops the queued background answers whose events a fresh session's history holds, by their
   * [heldEventIds]; the others stay tracked, so a later fresh session can still drop them.
   */
  fun discardQueuedAnswers(heldEventIds: Set<String>) {
    var held = emptyList<QueuedAnswer>()
    queuedAnswers.mutate { answers ->
      val (inHistory, others) = answers.partition { it.eventId in heldEventIds }
      held = inHistory
      others
    }
    queue.discardRequests(held.map { it.request })
  }

  /** Stops tracking a background answer once the sender takes [request] off the queue. */
  fun onRequestTaken(request: LiveRequest) {
    if (queuedAnswers.load().none { it.request === request }) return
    queuedAnswers.mutate { answers -> answers.filterNot { it.request === request } }
  }

  private suspend fun sendToCaller(tool: BaseTool, callId: String, event: Event) {
    val content = event.content
    if (content == null) {
      warnNotAMessage(tool, "it has no content, so there is nothing to deliver")
      return
    }
    if (event != Event(id = event.id, content = content, timestamp = event.timestamp)) {
      warnNotAMessage(tool, "it sets fields beyond the message, which are ignored")
    }
    // Built fresh, so the caller gets the message alone and a reused event gets its own id.
    val delivered =
      ToolEvent(
        Event(
          invocationId = invocationId,
          author = agentName,
          content = content.copy(role = Role.USER),
          branch = "${tool.name}@$callId",
        )
      )
    // A value the tool sends next reaches the model only after the caller has this event.
    if (events.trySend(delivered).isSuccess) delivered.handled.await()
  }

  private fun warnNotAMessage(tool: BaseTool, problem: String) {
    logger.warn {
      "Streaming tool `${tool.name}` yielded an Event that is not a purely user-facing message: " +
        "$problem. To send a message, use Event(content=...)"
    }
  }

  /** Stops every running call of [toolName] and returns the status message for the model. */
  private suspend fun stop(toolName: String): String {
    val running = active.load().filter { it.toolName == toolName && !it.inBackground }
    if (running.isEmpty()) return "No active streaming function named $toolName found"
    if (!cancelAndAwait(running)) return "The task is not cancelled yet for $toolName."
    return "Successfully stopped streaming function $toolName"
  }

  /**
   * Cancels [calls] and waits up to [STOP_TIMEOUT] for them, warning about any that ignore it. The
   * caller's own cancellation still propagates out of the wait.
   *
   * @return whether all of them stopped in time.
   */
  private suspend fun cancelAndAwait(calls: List<StreamingCall>): Boolean {
    for (call in calls) {
      call.job.cancel()
    }
    val stopped = withTimeoutOrNull(STOP_TIMEOUT) { calls.map { it.job }.joinAll() } != null
    for (call in calls) {
      if (!call.job.isCompleted) {
        logger.warn { "Live tool ${call.toolName} ignored cancellation." }
      }
    }
    return stopped
  }

  private class QueuedAnswer(val eventId: String, val request: LiveRequest)

  /** An event a tool sent the caller, and the signal that the caller has handled it. */
  class ToolEvent(val event: Event) {
    val handled = CompletableDeferred<Unit>()
  }

  /**
   * The final answers a stop owes: [streams] to streaming calls and their repeats, and [background]
   * to background calls stopped before they answered.
   */
  data class FinalAnswers(val streams: List<Content>, val background: List<Content>)

  companion object {
    private val logger = LoggerFactory.getLogger(LiveStreamingTools::class)
    private val STOP_TIMEOUT = 1.seconds
    private const val STATUS_KEY = "status"

    /**
     * The answer recorded, when the run ends or hands over, for a call to [toolName], or a repeat
     * of one, that it stopped or that never started.
     */
    private fun stoppedStatus(toolName: String) =
      "$toolName stopped streaming when its live session ended; no more updates will arrive."

    /** The answer recorded for a call to [toolName], or a repeat of one, whose stream finished. */
    private fun finishedStatus(toolName: String) =
      "$toolName finished streaming; no more updates will arrive."

    /** The answer recorded for a call to [toolName], or a repeat of one, whose stream failed. */
    private fun failedStatus(toolName: String) =
      "$toolName stopped streaming after an error; no more updates will arrive."

    /** The answer recorded for a background call to [toolName] that the run stopped. */
    private fun backgroundStoppedStatus(toolName: String) =
      "$toolName was stopped when its live session ended; its result will not arrive."

    /** The answer a call that starts a streaming tool gets at once. */
    private const val PENDING_STATUS =
      "The function is running asynchronously and the results are pending."
  }
}
