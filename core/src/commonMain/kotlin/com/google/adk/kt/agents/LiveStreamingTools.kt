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
 * Wraps [value] in a user [Content] carrying a [FunctionResponse] for [toolName] and [callId]. A
 * [FunctionResponse] [value] contributes only its payload, since the call it answers is not the
 * tool's to choose, and `Unit` becomes `{"result": null}`, like Python's `None`.
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
private inline fun <T> AtomicReference<T>.mutate(transform: (T) -> T) {
  while (true) {
    val current = load()
    if (compareAndSet(current, transform(current))) return
  }
}

/**
 * The streaming tools one agent's live run started, each collected in the background until it ends
 * or the run ends or hands the conversation over.
 *
 * Each [Event] a tool emits reaches only the caller, and the tool waits until the caller has it, as
 * in ADK Python; every other value reaches the model as a function response to the call that
 * started it.
 */
internal class LiveStreamingTools(
  private val queue: LiveRequestQueue,
  private val agentName: String,
  private val invocationId: String,
  coroutineContext: CoroutineContext,
) {
  // No parent job, so a tool that ignores cancellation is logged and left behind, not waited for.
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

  // An event whose hand-off was cancelled after it left the channel; taken first next time.
  private val undelivered = AtomicReference<ToolEvent?>(null)
  private val events =
    Channel<ToolEvent>(Channel.UNLIMITED, onUndeliveredElement = { undelivered.store(it) })

  /** One function call to a streaming tool, and the coroutine collecting what it emits. */
  private class StreamingCall(
    val toolName: String,
    val callId: String,
    val args: Map<String, Any?>,
  ) {
    lateinit var job: Job

    /** Whether [other] calls the same tool with the same arguments while this one still streams. */
    fun isRepeatedBy(other: StreamingCall): Boolean =
      other.callId != callId &&
        other.toolName == toolName &&
        other.args == args &&
        // Not yet started still counts: a call in the same response starts once answered.
        !job.isCompleted &&
        !job.isCancelled
  }

  /**
   * Runs a live tool call, collecting a returned [Flow] in the background and answering the call as
   * pending. A repeat of a call that is still streaming, with the same arguments, starts no second
   * stream and is answered that the tool is already running.
   */
  suspend fun run(tool: BaseTool, context: ToolContext, args: Map<String, Any?>): Any {
    val result = tool.run(context, args)
    val stream = result as? Flow<*> ?: return result
    // Every live call has an id: the turn fills in a missing one before any tool runs.
    val callId = checkNotNull(context.functionCallId) { "A live tool call has no id." }
    if (!start(tool, callId, args, stream)) {
      return mapOf(
        STATUS_KEY to
          "${tool.name} is already running with these arguments; wait for its updates " +
            "instead of calling again."
      )
    }
    return mapOf(STATUS_KEY to PENDING_STATUS)
  }

  /**
   * Starts the streams whose pending answers [content] carries, now that those answers are queued
   * ahead of anything the streams send. Cancels them instead when the queue is closed, since nobody
   * would hear them.
   */
  fun onAnswersQueued(content: Content) {
    val ids = content.parts.mapNotNullTo(mutableSetOf()) { it.functionResponse?.id }
    for (call in active.load()) {
      if (call.callId !in ids) continue
      if (!queue.isClosed) call.job.start() else call.job.cancel()
    }
  }

  /** Waits for the next event a tool sent the caller, taking an undelivered one first. */
  suspend fun nextEvent(): ToolEvent = undelivered.exchange(null) ?: events.receive()

  /** Puts back an event whose hand-off to the caller never reached it. */
  fun putBack(event: ToolEvent) {
    undelivered.store(event)
  }

  /**
   * Cancels the calls whose streams have not started when a connection opens. Their pending answers
   * were never queued and never will be, so a repeat of one starts a new stream.
   */
  fun onConnect() {
    for (call in active.load()) {
      if (!call.job.isActive) call.job.cancel()
    }
  }

  /** Takes every event tools sent the caller that is not delivered yet, oldest first. */
  fun drainEvents(): List<Event> = buildList {
    undelivered.exchange(null)?.let { add(it.event) }
    addAll(generateSequence { events.tryReceive().getOrNull()?.event })
  }

  /**
   * Cancels every streaming call this run started and waits up to [STOP_TIMEOUT] for them when the
   * live run ends or hands over, even if the caller is cancelled.
   */
  suspend fun stopAll(): Unit =
    withContext(NonCancellable) {
      val running = active.exchange(emptyList())
      // A call that ignores cancellation is already warned about and left behind.
      if (running.isNotEmpty()) {
        val unused = cancelAndAwait(running)
      }
    }

  /** Stops every streaming call, releases the scope and drops what no one will deliver. */
  suspend fun close() {
    stopAll()
    events.close()
    scope.cancel()
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
    // A copy, so a caller changing its map cannot change what later calls are compared with.
    val call = StreamingCall(tool.name, callId, args.toMap())
    // Waits for its pending answer to be queued, so the model hears that first.
    call.job = scope.launch(start = CoroutineStart.LAZY) { relay(tool, call, stream) }
    val stale: List<StreamingCall>
    while (true) {
      val calls = active.load()
      // Live models re-call a tool they already stream; a second stream would repeat each value.
      if (calls.any { it.isRepeatedBy(call) }) {
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
    call.job.invokeOnCompletion { active.mutate { calls -> calls - call } }
    for (old in stale) {
      old.job.cancel()
    }
    return true
  }

  private suspend fun relay(tool: BaseTool, call: StreamingCall, stream: Flow<*>) {
    try {
      stream.collect { value ->
        // A flow that never suspends would otherwise outlive a stop or a handover.
        currentCoroutineContext().ensureActive()
        if (value is Event) {
          sendToCaller(tool, call.callId, value)
        } else {
          // Not partial: Kotlin never sends a partial tool answer, and the wire ignores it anyway.
          queue.sendContent(functionResponseContent(tool.name, call.callId, value))
        }
      }
    } catch (e: Throwable) {
      // Exceptions and TODO()'s NotImplementedError reach the model, as in Python, unless stopped.
      if ((e is Error && e !is NotImplementedError) || !currentCoroutineContext().isActive) throw e
      reportFailure(tool, call, e)
    }
  }

  private fun reportFailure(tool: BaseTool, call: StreamingCall, e: Throwable) {
    // The exception's text is left out: it can carry the user's or the model's data.
    logger.warn { "Streaming tool ${tool.name} failed (${e::class.simpleName})." }
    val error = mapOf(ERROR_KEY to "Invoking `${tool.name}()` failed with an internal error.")
    queue.sendContent(functionResponseContent(tool.name, call.callId, error))
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
    // Like ADK Python, a value the tool sends next reaches the model only after this event.
    if (events.trySend(delivered).isSuccess) delivered.handled.await()
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

  private companion object {
    val logger = LoggerFactory.getLogger(LiveStreamingTools::class)
    val STOP_TIMEOUT = 1.seconds
    const val STATUS_KEY = "status"
    const val ERROR_KEY = "error"

    /** The answer a call that starts a streaming tool gets at once, as in ADK Python. */
    const val PENDING_STATUS = "The function is running asynchronously and the results are pending."
  }
}
