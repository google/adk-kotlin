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
import com.google.adk.kt.a2a.converters.isLastChunk
import com.google.adk.kt.a2a.converters.shouldBuffer
import com.google.adk.kt.a2a.converters.shouldResetBuffer
import com.google.adk.kt.a2a.converters.toA2aMessage
import com.google.adk.kt.a2a.converters.toAdkEvent
import com.google.adk.kt.agents.BaseAgent
import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.callbacks.AfterAgentCallback
import com.google.adk.kt.callbacks.BeforeAgentCallback
import com.google.adk.kt.events.Event
import com.google.adk.kt.logging.LoggerFactory
import java.util.concurrent.atomic.AtomicReference
import java.util.function.BiConsumer
import java.util.function.Consumer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import org.a2aproject.sdk.spec.TaskState

/** Agent that communicates with a remote A2A agent via an A2A client. */
internal class A2AAgentImpl(
  name: String,
  private val userDescription: String? = null,
  private val a2aClient: Client,
  private val agentCard: AgentCard,
  private val streaming: Boolean = true,
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

  override val isStreamingEnabled: Boolean by lazy {
    streaming && agentCard.capabilities().streaming()
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

    // Suppress because processing is serialized via eventChannel, avoiding concurrent execution.
    @Suppress("UnsafeCoroutineCrossing")
    val processorJob = launch {
      val a2aAggregator = A2AStreamingResponseAggregator(context.invocationId, name)
      val debugRequest = serializeMessageToJson(message)

      for (responseEvent in eventChannel) {
        val result = processClientEvent(responseEvent, context, debugRequest, a2aAggregator)

        result.taskId?.let { activeTaskId.set(it) }
        result.isTerminal?.let { isTaskTerminal.set(it) }

        for (event in result.eventsToEmit) {
          send(event)
        }

        if (result.shouldClose) {
          break
        }
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

    val errorHandler =
      Consumer<Throwable> { ex ->
        val e: Throwable? = ex
        if (e != null) close(e) else eventChannel.close()
      }

    a2aClient.sendMessage(message, listOf(handler), errorHandler, null)

    awaitClose {
      eventChannel.close()
      processorJob.cancel()
      if (!isTaskTerminal.get()) {
        activeTaskId.get()?.let { id -> cancelTask(id) }
      }
    }
  }

  private fun processClientEvent(
    responseEvent: ClientEvent,
    context: InvocationContext,
    debugRequest: Result<String>,
    a2aAggregator: A2AStreamingResponseAggregator,
  ): EventProcessResult {
    var taskId: String? = null
    var isTerminal: Boolean? = null

    when (responseEvent) {
      is TaskEvent -> {
        taskId = responseEvent.task.id
        isTerminal = isTerminal(responseEvent.task.status.state())
      }
      is TaskUpdateEvent -> {
        taskId = responseEvent.task.id
        isTerminal = isTerminal(responseEvent.task.status.state())
      }
      else -> {}
    }

    val events = mutableListOf<Event>()
    val adkEvent = responseEvent.toAdkEvent(context)
    if (adkEvent != null) {
      events.addAll(
        a2aAggregator.processEvent(
          adkEvent = addMetadata(adkEvent, responseEvent, debugRequest),
          isCompleted = responseEvent.isCompleted(),
          shouldBuffer = responseEvent.shouldBuffer(),
          shouldResetBuffer = responseEvent.shouldResetBuffer(),
          isLastChunk = responseEvent.isLastChunk(),
        )
      )
    }

    return EventProcessResult(
      eventsToEmit = events,
      taskId = taskId,
      isTerminal = isTerminal,
      shouldClose = responseEvent.isCompleted(),
    )
  }

  private fun cancelTask(taskId: String) {
    @Suppress("GlobalCoroutineDispatchers", "UnsafeCoroutineCrossing")
    val unusedJob =
      CoroutineScope(Dispatchers.IO).launch {
        try {
          a2aClient.cancelTask(CancelTaskParams(taskId))
        } catch (e: Exception) {
          logger.warn(e) { "Failed to cancel task $taskId" }
        }
      }
  }

  // Debug metadata via the SDK's reflection-free JsonUtil so it also works under Android R8.
  private fun serializeMessageToJson(message: Message): Result<String> =
    runCatching { JsonUtil.toJson(message) }
      .onFailure { e -> logger.warn(e) { "Failed to serialize request" } }

  private fun addMetadata(
    event: Event,
    responseEvent: ClientEvent?,
    debugRequest: Result<String>,
  ): Event {
    val debugResponse = responseEvent?.let {
      runCatching { serializeClientEvent(it) }
        .onFailure { e -> logger.warn(e) { "Failed to serialize response metadata" } }
    }
    return addA2AMetadata(event = event, debugRequest = debugRequest, debugResponse = debugResponse)
  }

  // Unwrap to the underlying spec type, which JsonUtil can serialize (unlike the client wrapper).
  private fun serializeClientEvent(event: ClientEvent): String =
    when (event) {
      is MessageEvent -> JsonUtil.toJson(event.message)
      is TaskEvent -> JsonUtil.toJson(event.task)
      is TaskUpdateEvent -> JsonUtil.toJson(event.task)
    }

  private fun isTerminal(state: TaskState): Boolean =
    state.isFinal || state == TaskState.TASK_STATE_INPUT_REQUIRED
}
