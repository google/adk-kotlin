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

package com.google.adk.kt.a2a.agent

import com.google.adk.kt.a2a.converters.isCompleted
import com.google.adk.kt.a2a.converters.isFinalState
import com.google.adk.kt.a2a.converters.isInterruptedState
import com.google.adk.kt.a2a.converters.isLastChunk
import com.google.adk.kt.a2a.converters.remoteAgentEvent
import com.google.adk.kt.a2a.converters.remoteIdsMetadata
import com.google.adk.kt.a2a.converters.shouldBuffer
import com.google.adk.kt.a2a.converters.shouldResetBuffer
import com.google.adk.kt.a2a.converters.toA2aMessage
import com.google.adk.kt.a2a.converters.toAdkEvent
import com.google.adk.kt.agents.BaseAgent
import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.agents.StreamingMode
import com.google.adk.kt.callbacks.AfterAgentCallback
import com.google.adk.kt.callbacks.BeforeAgentCallback
import com.google.adk.kt.events.Event
import com.google.adk.kt.logging.LoggerFactory
import java.util.concurrent.atomic.AtomicReference
import java.util.function.BiConsumer
import java.util.function.Consumer
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import org.a2aproject.sdk.client.Client
import org.a2aproject.sdk.client.ClientEvent
import org.a2aproject.sdk.client.MessageEvent
import org.a2aproject.sdk.client.TaskEvent
import org.a2aproject.sdk.client.TaskUpdateEvent
import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil
import org.a2aproject.sdk.spec.AgentCard
import org.a2aproject.sdk.spec.CancelTaskParams
import org.a2aproject.sdk.spec.Message
import org.a2aproject.sdk.spec.Task
import org.a2aproject.sdk.spec.TaskState

/**
 * Agent that communicates with a remote A2A agent. [clientProvider] builds an SDK [Client] for the
 * streaming mode chosen per invocation from the run's `RunConfig`, so the client is built per turn
 * rather than injected once. [transportSupportsStreaming] is false for transports that only do
 * blocking `message/send`.
 */
internal class A2AAgentImpl(
  name: String,
  private val userDescription: String? = null,
  private val clientProvider: (streaming: Boolean) -> Client,
  private val agentCard: AgentCard,
  private val transportSupportsStreaming: Boolean,
  subAgents: List<BaseAgent> = emptyList(),
  beforeAgentCallbacks: List<BeforeAgentCallback> = emptyList(),
  afterAgentCallbacks: List<AfterAgentCallback> = emptyList(),
) :
  BaseRemoteA2AAgent(
    name = name,
    description = userDescription ?: "",
    subAgents = subAgents,
    beforeAgentCallbacks = beforeAgentCallbacks,
    afterAgentCallbacks = afterAgentCallbacks,
  ) {
  private val logger = LoggerFactory.getLogger(A2AAgentImpl::class)

  override val description: String
    get() = userDescription ?: agentCard.description() ?: ""

  // Whether streaming is possible at all: the transport supports it and the remote advertises it.
  // The actual per-turn mode also depends on the invocation's RunConfig -- see
  // createA2aCallbackFlow.
  override val isStreamingEnabled: Boolean by lazy {
    transportSupportsStreaming && agentCard.capabilities().streaming()
  }

  override fun createA2aCallbackFlow(
    context: InvocationContext,
    outboundEvent: Event,
  ): Flow<Event> = callbackFlow {
    val activeTaskId = AtomicReference<String>()
    val isTaskTerminal = AtomicReference<Boolean>(false)

    // Bridges the non-suspendable Java client handler to suspendable coroutines.
    // Note: Channel integration asynchronously defers processing, which removes natural
    // backpressure.
    // We use an UNLIMITED channel because local processing should outpace remote A2A network
    // responses, which are bounded by LLM limits.
    // Alternatively, block the thread using: runBlocking { eventChannel.send(responseEvent) }
    val eventChannel = Channel<ClientEvent>(Channel.UNLIMITED)
    val message = outboundEvent.toA2aMessage()
    val debugRequest = serializeMessageToJson(message)

    // Per-invocation streaming: RunConfig SSE, only if streaming is possible (isStreamingEnabled).
    val streaming = isStreamingEnabled && context.runConfig?.streamingMode == StreamingMode.SSE
    val a2aClient = clientProvider(streaming)

    // Suppress because processing is serialized via eventChannel, avoiding concurrent execution.
    @Suppress("UnsafeCoroutineCrossing")
    val processorJob = launch {
      val a2aAggregator = A2AStreamingResponseAggregator(context.invocationId, name)
      var lastTask: Task? = null

      try {
        for (responseEvent in eventChannel) {
          val result = processClientEvent(responseEvent, context, a2aAggregator)

          result.taskId?.let { activeTaskId.set(it) }
          result.isTerminal?.let { isTaskTerminal.set(it) }
          responseEvent.taskOrNull?.let { lastTask = it }

          // After aggregation, so a merged event also records the response that flushed it.
          val debugResponse by lazy { serializeResponse(responseEvent) }
          for (event in result.eventsToEmit) {
            send(addA2AMetadata(event, debugRequest, debugResponse))
          }

          if (result.shouldClose) {
            break
          }
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        val errorMessage = "A2A request failed (${e::class.simpleName})"
        send(failedTurnEvent(context, errorMessage, debugRequest, contextId = null))
        close(e)
        return@launch
      }
      // Report an unfinished task as ADK Python does, but keep its context for the next turn.
      if (lastTask != null && !isTerminal(lastTask.status.state())) {
        val errorMessage = "A2A response ended before the remote task reached a terminal state"
        send(failedTurnEvent(context, errorMessage, debugRequest, lastTask.contextId))
      }
      close()
    }

    // This handler may be called multiple times for each response from A2A.
    val handler =
      BiConsumer<ClientEvent, AgentCard> { responseEvent, _ ->
        // UNLIMITED channel: trySend only fails once the flow is closed; dropping a late event then
        // is fine.
        eventChannel.trySend(responseEvent).getOrNull()
      }

    // A cancellation closes the flow directly; other failures become an error event.
    fun failRequest(e: Throwable) {
      if (e is CancellationException) close(e) else eventChannel.close(e)
    }

    val errorHandler =
      Consumer<Throwable> { ex ->
        val e: Throwable? = ex
        if (e != null) failRequest(e) else eventChannel.close()
      }

    // A synchronous throw goes through failRequest, so awaitClose below still does the cleanup.
    try {
      a2aClient.sendMessage(message, listOf(handler), errorHandler, null)
      // A blocking send hands its one response to the handler before it returns.
      if (!streaming) eventChannel.close()
    } catch (e: Exception) {
      failRequest(e)
    }

    awaitClose {
      eventChannel.close()
      processorJob.cancel()
      val abandonedTaskId = if (!isTaskTerminal.get()) activeTaskId.get() else null
      if (abandonedTaskId != null) {
        cancelAndClose(a2aClient, abandonedTaskId, coroutineContext)
      } else {
        a2aClient.close()
      }
    }
  }

  private fun processClientEvent(
    responseEvent: ClientEvent,
    context: InvocationContext,
    a2aAggregator: A2AStreamingResponseAggregator,
  ): EventProcessResult {
    val task = responseEvent.taskOrNull
    val events = mutableListOf<Event>()
    val adkEvent = responseEvent.toAdkEvent(context)
    if (adkEvent != null) {
      events.addAll(
        a2aAggregator.processEvent(
          adkEvent = adkEvent,
          isCompleted = responseEvent.isCompleted(),
          shouldBuffer = responseEvent.shouldBuffer(),
          shouldResetBuffer = responseEvent.shouldResetBuffer(),
          isLastChunk = responseEvent.isLastChunk(),
        )
      )
    }

    return EventProcessResult(
      eventsToEmit = events,
      taskId = task?.id,
      isTerminal = if (task != null) isTerminal(task.status.state()) else null,
      shouldClose = responseEvent.isCompleted(),
    )
  }

  /**
   * Returns an error event that records [request] as failed, with [contextId] if it is not null.
   */
  private fun failedTurnEvent(
    context: InvocationContext,
    errorMessage: String,
    request: Result<String>,
    contextId: String?,
  ): Event =
    remoteAgentEvent(context)
      .copy(
        errorMessage = errorMessage,
        customMetadata =
          buildMap {
            putAll(remoteIdsMetadata(taskId = null, contextId))
            request.onSuccess { put(A2AMetadata.REQUEST, it) }
          },
      )

  /**
   * Cancels [taskId] and closes [a2aClient] on `Dispatchers.IO` without [callerContext]'s [Job], so
   * both calls still run after the abandoned flow is cancelled. Keeps the rest of [callerContext],
   * such as MDC, so logs carry the caller's context.
   */
  private fun cancelAndClose(a2aClient: Client, taskId: String, callerContext: CoroutineContext) {
    @Suppress("GlobalCoroutineDispatchers", "UnsafeCoroutineCrossing")
    val unusedJob =
      CoroutineScope(callerContext.minusKey(Job) + Dispatchers.IO).launch {
        try {
          a2aClient.cancelTask(CancelTaskParams(taskId))
        } catch (e: Exception) {
          logger.warn(e) { "Failed to cancel task $taskId" }
        } finally {
          a2aClient.close()
        }
      }
  }

  // Debug metadata via the SDK's reflection-free JsonUtil so it also works under Android R8.
  private fun serializeMessageToJson(message: Message): Result<String> =
    runCatching { JsonUtil.toJson(message) }
      .onFailure { e -> logger.warn(e) { "Failed to serialize request" } }

  // Unwraps to the underlying spec type, which JsonUtil can serialize (unlike the client wrapper).
  private fun serializeResponse(responseEvent: ClientEvent): Result<String> =
    runCatching {
        when (responseEvent) {
          is MessageEvent -> JsonUtil.toJson(responseEvent.message)
          is TaskEvent -> JsonUtil.toJson(responseEvent.task)
          is TaskUpdateEvent -> JsonUtil.toJson(responseEvent.task)
        }
      }
      .onFailure { e -> logger.warn(e) { "Failed to serialize response metadata" } }

  private fun isTerminal(state: TaskState): Boolean =
    state.isFinalState() || state.isInterruptedState()

  private val ClientEvent.taskOrNull: Task?
    get() =
      when (this) {
        is MessageEvent -> null
        is TaskEvent -> task
        is TaskUpdateEvent -> task
      }
}
