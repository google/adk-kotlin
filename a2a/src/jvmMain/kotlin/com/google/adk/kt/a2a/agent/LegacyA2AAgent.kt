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

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.google.adk.kt.a2a.converters.isCompleted
import com.google.adk.kt.a2a.converters.isLastChunk
import com.google.adk.kt.a2a.converters.shouldBuffer
import com.google.adk.kt.a2a.converters.shouldResetBuffer
import com.google.adk.kt.a2a.converters.toAdkEvent
import com.google.adk.kt.a2a.converters.toLegacyA2aMessage
import com.google.adk.kt.agents.BaseAgent
import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.callbacks.AfterAgentCallback
import com.google.adk.kt.callbacks.BeforeAgentCallback
import com.google.adk.kt.events.Event
import com.google.adk.kt.logging.LoggerFactory
import io.a2a.client.Client
import io.a2a.client.ClientEvent
import io.a2a.client.TaskEvent
import io.a2a.client.TaskUpdateEvent
import io.a2a.spec.AgentCard
import io.a2a.spec.Message
import io.a2a.spec.TaskIdParams
import io.a2a.spec.TaskState
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

/**
 * Agent that communicates with a remote A2A agent via an A2A v0.3 client.
 *
 * This is the legacy (deprecated) implementation backed by the A2A Java SDK v0.3 (`io.a2a.*`). New
 * code should use [A2AAgent], which is backed by the A2A Java SDK v1.0 and also runs on Android.
 */
internal class LegacyA2AAgent(
  name: String,
  private val userDescription: String? = null,
  private val a2aClient: Client,
  private val agentCard: AgentCard? = null,
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
  private val logger = LoggerFactory.getLogger(LegacyA2AAgent::class)
  private val objectMapper = ObjectMapper().registerModule(JavaTimeModule())

  override val description: String
    get() = userDescription ?: effectiveAgentCard.description() ?: ""

  private val effectiveAgentCard: AgentCard by lazy {
    // Depending on the A2A v0.3 SDK point version, a missing card surfaces as either a null return
    // or a thrown exception; treat both as "unresolved".
    agentCard
      ?: runCatching { a2aClient.agentCard }.getOrNull()
      ?: throw AgentCardResolutionError("Failed to resolve agent card")
  }

  override val isStreamingEnabled: Boolean by lazy {
    streaming && effectiveAgentCard.capabilities().streaming()
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
    val message = outboundEvent.toLegacyA2aMessage()

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
        val send = eventChannel.trySend(responseEvent)
        if (send.isFailure) {
          val error =
            IllegalStateException(
              "A2AAgent internal event queue is full; downstream is too slow to consume events.",
              send.exceptionOrNull(),
            )
          logger.error(error) { "Failed to queue client event" }
          close(error)
        }
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
          a2aClient.cancelTask(TaskIdParams(taskId))
        } catch (e: Exception) {
          logger.warn(e) { "Failed to cancel task $taskId" }
        }
      }
  }

  private fun serializeMessageToJson(message: Message): Result<String> {
    return runCatching { objectMapper.writeValueAsString(message) }
      .onFailure { e -> logger.warn(e) { "Failed to serialize request" } }
  }

  private fun addMetadata(
    event: Event,
    responseEvent: ClientEvent?,
    debugRequest: Result<String>,
  ): Event {
    val debugResponse = responseEvent?.let {
      runCatching { objectMapper.writeValueAsString(it) }
        .onFailure { e -> logger.warn(e) { "Failed to serialize response metadata" } }
    }
    return addA2AMetadata(event = event, debugRequest = debugRequest, debugResponse = debugResponse)
  }

  private fun isTerminal(state: TaskState): Boolean =
    state.isFinal || state == TaskState.INPUT_REQUIRED
}

/**
 * Factory function to create a [BaseRemoteA2AAgent] backed by the A2A Java SDK v0.3 [Client].
 *
 * This path is JVM-only because the v0.3 SDK depends on `java.net.http`, which is unavailable on
 * Android.
 */
@Deprecated(
  "Use A2AAgent with the A2A v1.0 SDK",
  ReplaceWith(
    "A2AAgent(name, client, agentCard, streaming, subAgents, beforeAgentCallbacks," +
      " afterAgentCallbacks)"
  ),
)
fun JvmA2AAgent(
  name: String,
  client: Client,
  agentCard: AgentCard? = null,
  streaming: Boolean = true,
  subAgents: List<BaseAgent> = emptyList(),
  beforeAgentCallbacks: List<BeforeAgentCallback> = emptyList(),
  afterAgentCallbacks: List<AfterAgentCallback> = emptyList(),
): BaseRemoteA2AAgent {
  return LegacyA2AAgent(
    name = name,
    a2aClient = client,
    agentCard = agentCard,
    streaming = streaming,
    subAgents = subAgents,
    beforeAgentCallbacks = beforeAgentCallbacks,
    afterAgentCallbacks = afterAgentCallbacks,
  )
}
