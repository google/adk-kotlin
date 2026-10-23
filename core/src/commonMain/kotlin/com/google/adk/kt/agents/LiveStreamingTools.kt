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
import kotlinx.coroutines.isActive
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

/**
 * Wraps [value] in a user [Content] carrying a [FunctionResponse] for [toolName] and [callId]. A
 * [FunctionResponse] [value] contributes only its payload, since the call it answers is not the
 * tool's to choose, and `Unit` becomes `{"result": null}`.
 */
private fun functionResponseContent(toolName: String, callId: String, value: Any?): Content {
  val payload = if (value is FunctionResponse) value.response else value
  return Content(
    role = Role.USER,
    parts =
      listOf(
        Part(
          functionResponse =
            FunctionResponse(
              name = toolName,
              response = toFinalResponseMap(payload.takeUnless { it === Unit }),
              id = callId,
            )
        )
      ),
  )
}

/** Replaces the value with [transform] of it, retrying when another thread got there first. */
@JvmSynthetic
internal inline fun <T> AtomicReference<T>.mutate(transform: (T) -> T) {
  while (true) {
    val current = load()
    if (compareAndSet(current, transform(current))) return
  }
}

/**
 * The streaming tools one agent's live run started, each collected in the background until it ends,
 * is stopped, or the run ends or hands the conversation over.
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
          logger.warn { "A streaming tool failed with ${e::class.simpleName}, not reported." }
        }
    )

  // Each call leaves the list when its job completes, however it ends, started or not.
  private val active = AtomicReference<List<StreamingCall>>(emptyList())

  // Calls that finished since the last stop, whose last values may still be queued.
  private val finished = AtomicReference<List<String>>(emptyList())

  // Calls answered as pending that stopped, ended or never started; the next stopAll answers them.
  private val owed = AtomicReference<List<StreamingCall>>(emptyList())

  // Calls whose stream failed, so their final answer says so.
  private val failed = AtomicReference<Set<String>>(emptySet())

  // An event whose hand-off was cancelled after it left the channel; taken first next time.
  private val undelivered = AtomicReference<ToolEvent?>(null)
  private val events =
    Channel<ToolEvent>(Channel.UNLIMITED, onUndeliveredElement = { undelivered.store(it) })

  /** One function call to a streaming tool, and the coroutine collecting what it emits. */
  private class StreamingCall(
    val toolName: String,
    val callId: String,
    val args: Map<String, Any?>,
    val job: Job,
  ) {
    // Repeats told this call is running need a final answer too when this stream is stopped.
    val repeatIds = AtomicReference<List<String>>(emptyList())

    /** Whether [other] calls the same tool with the same arguments while this one still streams. */
    fun isRepeatedBy(other: StreamingCall): Boolean = isRepeatedBy(other.toolName, other.args)

    /**
     * Whether a call to [toolName] with [args], under any id, repeats this one while it streams.
     */
    fun isRepeatedBy(toolName: String, args: Map<String, Any?>): Boolean =
      toolName == this.toolName &&
        args == this.args &&
        // Not yet started still counts: a call in the same response starts once answered.
        !job.isCompleted &&
        !job.isCancelled
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
    if (!start(tool, callId, args, stream)) return alreadyRunning(tool.name)
    return mapOf(STATUS_KEY to PENDING_STATUS)
  }

  private fun alreadyRunning(toolName: String): Map<String, Any?> =
    mapOf(
      STATUS_KEY to
        "$toolName is already running with these arguments; wait for its updates instead of " +
          "calling again."
    )

  /**
   * Starts the streams whose pending answers [content] carries, now that those answers are queued
   * ahead of anything the streams send. Cancels them instead when the queue is closed, since nobody
   * would hear them, or when [mayStart] is false, as during a handover.
   */
  fun onAnswersQueued(content: Content, mayStart: Boolean = true) {
    val ids = content.parts.mapNotNullTo(mutableSetOf()) { it.functionResponse?.id }
    for (call in active.load()) {
      if (call.callId !in ids) continue
      if (mayStart && !queue.isClosed) {
        call.job.start()
      } else {
        call.job.cancel()
        owed.mutate { calls -> calls + call }
      }
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
    for (call in active.load()) {
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
   * for the next [stopAll].
   */
  suspend fun stopRunning(): Unit =
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
      queue.discardAnswersTo(finished.exchange(emptyList()) + running.map { it.callId })
    }

  /**
   * Cancels every streaming call this run started and waits up to [STOP_TIMEOUT] for them when the
   * live run ends or hands over, even if the caller is cancelled.
   *
   * @return one final answer per call stopped, ended or never started, and per repeat of one told
   *   it was already running.
   */
  suspend fun stopAll(): List<Content> {
    stopRunning()
    // An id owed twice is answered once, by the first call that owes it.
    val callById: Map<String, StreamingCall> = buildMap {
      for (call in owed.exchange(emptyList())) {
        for (callId in listOf(call.callId) + call.repeatIds.load()) {
          getOrPut(callId) { call }
        }
      }
    }
    return callById.map { (callId, call) ->
      functionResponseContent(call.toolName, callId, mapOf(STATUS_KEY to finalStatus(call)))
    }
  }

  /** Why [call]'s stream ended: stopped by the run, finished, or failed. */
  private fun finalStatus(call: StreamingCall): String =
    when {
      call.job.isCancelled -> stoppedStatus(call.toolName)
      call.callId in failed.load() -> failedStatus(call.toolName)
      else -> finishedStatus(call.toolName)
    }

  /**
   * Stops every streaming call, releases the scope and drops what no one will deliver.
   *
   * @return what [stopAll] returns.
   */
  suspend fun close(): List<Content> {
    val stopped = stopAll()
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
    stream: Flow<*>,
  ): Boolean {
    // Waits for its pending answer to be queued, so the model hears that first.
    val job = scope.launch(start = CoroutineStart.LAZY) { relay(tool, callId, stream) }
    // A copy, so a caller changing its map cannot change what later calls are compared with.
    val call = StreamingCall(tool.name, callId, args.toMap(), job)
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
      // A call id answered twice must not stream twice; the newer call wins.
      val (sameId, others) = calls.partition { it.callId == callId }
      if (active.compareAndSet(calls, others + call)) {
        stale = sameId
        break
      }
    }
    call.job.invokeOnCompletion {
      finished.mutate { ids -> ids + call.callId }
      active.mutate { calls -> calls - call }
    }
    // A new call under a failed call's id has not failed.
    failed.mutate { ids -> ids - callId }
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

  private suspend fun relay(tool: BaseTool, callId: String, stream: Flow<*>) {
    try {
      stream.collect { value ->
        // A flow that never suspends would otherwise outlive a stop or a handover.
        currentCoroutineContext().ensureActive()
        if (value is Event) {
          sendToCaller(tool, callId, value)
        } else {
          // Not partial: Kotlin never sends a partial tool answer, and the wire ignores it anyway.
          queue.sendContent(functionResponseContent(tool.name, callId, value))
        }
      }
    } catch (e: Throwable) {
      // Exceptions and TODO()'s NotImplementedError reach the model unless stopped.
      if ((e is Error && e !is NotImplementedError) || !currentCoroutineContext().isActive) throw e
      failed.mutate { ids -> ids + callId }
      reportFailure(tool, callId, e)
    }
  }

  private fun reportFailure(tool: BaseTool, callId: String, e: Throwable) {
    // The exception's text is left out: it can carry the user's or the model's data.
    logger.warn { "Streaming tool ${tool.name} failed (${e::class.simpleName})." }
    val error = mapOf(ERROR_KEY to "Invoking `${tool.name}()` failed with an internal error.")
    queue.sendContent(functionResponseContent(tool.name, callId, error))
  }

  private suspend fun sendToCaller(tool: BaseTool, callId: String, event: Event) {
    val content = event.content
    if (content == null) {
      logger.warn { "Streaming tool ${tool.name} yielded an Event with no content; it is dropped." }
      return
    }
    if (event != Event(id = event.id, content = content, timestamp = event.timestamp)) {
      logger.warn {
        "Streaming tool ${tool.name} yielded an Event with fields beyond its content; they are " +
          "ignored."
      }
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

  /** Stops every running call of [toolName] and returns the status message for the model. */
  private suspend fun stop(toolName: String): String {
    val running = active.load().filter { it.toolName == toolName }
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
        logger.warn { "Streaming tool ${call.toolName} ignored cancellation." }
      }
    }
    return stopped
  }

  /** An event a tool sent the caller, and the signal that the caller has handled it. */
  class ToolEvent(val event: Event) {
    val handled = CompletableDeferred<Unit>()
  }

  companion object {
    private val logger = LoggerFactory.getLogger(LiveStreamingTools::class)
    private val STOP_TIMEOUT = 1.seconds
    private const val STATUS_KEY = "status"
    private const val ERROR_KEY = "error"

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

    /** The answer a call that starts a streaming tool gets at once. */
    private const val PENDING_STATUS =
      "The function is running asynchronously and the results are pending."
  }
}
