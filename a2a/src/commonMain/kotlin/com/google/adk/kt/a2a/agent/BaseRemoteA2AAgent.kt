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

import com.google.adk.kt.a2a.converters.ADK_METADATA_CONTEXT_ID
import com.google.adk.kt.a2a.converters.ADK_METADATA_TASK_ID
import com.google.adk.kt.a2a.converters.contextId
import com.google.adk.kt.a2a.converters.extractPreprocessedEvents
import com.google.adk.kt.a2a.converters.findUserFunctionCall
import com.google.adk.kt.a2a.converters.taskId
import com.google.adk.kt.a2a.converters.trustedCallNamesById
import com.google.adk.kt.a2a.converters.withoutCredentialResponses
import com.google.adk.kt.agents.BaseAgent
import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.callbacks.AfterAgentCallback
import com.google.adk.kt.callbacks.BeforeAgentCallback
import com.google.adk.kt.events.Event
import com.google.adk.kt.logging.LoggerFactory
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Role
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Abstract base class for Remote A2A Agents. Marker base class for remote agent implementations.
 */
abstract class BaseRemoteA2AAgent(
  name: String,
  override val description: String = "",
  subAgents: List<BaseAgent> = emptyList(),
  beforeAgentCallbacks: List<BeforeAgentCallback> = emptyList(),
  afterAgentCallbacks: List<AfterAgentCallback> = emptyList(),
) :
  BaseAgent(
    name = name,
    description = description,
    subAgents = subAgents,
    beforeAgentCallbacks = beforeAgentCallbacks,
    afterAgentCallbacks = afterAgentCallbacks,
  ) {
  private val logger = LoggerFactory.getLogger(BaseRemoteA2AAgent::class)

  /** Returns `true` if this agent supports streaming responses. */
  abstract val isStreamingEnabled: Boolean

  /** Creates the A2A callback flow. */
  protected abstract fun createA2aCallbackFlow(
    context: InvocationContext,
    outboundEvent: Event,
  ): Flow<Event>

  override fun runAsyncImpl(context: InvocationContext): Flow<Event> {
    val outboundEvent = prepareOutboundEvent(context)

    if (outboundEvent.content?.parts.isNullOrEmpty()) {
      logger.debug { "No parts to send; skipping remote A2A call." }
      return flowOf(Event(invocationId = context.invocationId, author = name, turnComplete = true))
    }

    return createA2aCallbackFlow(context, outboundEvent)
  }

  /**
   * Returns the prepared event to be sent to the remote agent.
   *
   * The event may have no parts, when dropping credentials emptied it and no history remains;
   * callers must not send it in that state.
   */
  protected fun prepareOutboundEvent(context: InvocationContext): Event {
    val callEvent = context.session.events.findUserFunctionCall()

    // Classified by the name the CALL was made under, so a mislabelled response is still caught.
    val responseEvent =
      context.session.events
        .lastOrNull()
        ?.withoutCredentialResponses(callEvent?.trustedCallNamesById() ?: emptyMap())

    // A resume the drop left empty falls through to the history rebuild: an empty Message is
    // rejected by the SDK.
    if (callEvent != null && responseEvent?.content?.parts?.isNotEmpty() == true) {
      val updatedMetadata = responseEvent.customMetadata.orEmpty().toMutableMap()
      updatedMetadata[ADK_METADATA_TASK_ID] = callEvent.taskId
      updatedMetadata[ADK_METADATA_CONTEXT_ID] = callEvent.contextId

      return responseEvent.copy(author = Role.USER, customMetadata = updatedMetadata)
    }
    val contextIdValue = context.session.events.findLast { it.author == name }?.contextId
    val customMetadata =
      contextIdValue?.takeIf { it.isNotEmpty() }?.let { mapOf(ADK_METADATA_CONTEXT_ID to it) }
    return Event(
      invocationId = context.invocationId,
      author = Role.USER,
      content =
        Content(
          role = Role.USER,
          parts =
            context
              .extractPreprocessedEvents()
              .flatMap { it.content?.parts.orEmpty() }
              .toMutableList(),
        ),
      customMetadata = customMetadata,
    )
  }

  /** Adds A2A metadata to the event. */
  protected fun addA2AMetadata(
    event: Event,
    debugRequest: Result<String>? = null,
    debugResponse: Result<String>? = null,
  ): Event {
    return event.copy(
      customMetadata =
        event.customMetadata.orEmpty() +
          buildMap {
            if (debugRequest != null && event.turnComplete) {
              debugRequest
                .onSuccess { put(A2AMetadata.REQUEST, it) }
                .onFailure { put(A2AMetadata.REQUEST_ERROR, it.message ?: it.toString()) }
            }
            if (debugResponse != null) {
              debugResponse
                .onSuccess { put(A2AMetadata.RESPONSE, it) }
                .onFailure { put(A2AMetadata.RESPONSE_ERROR, it.message ?: it.toString()) }
            }
          }
    )
  }

  /** Exception thrown when the agent card cannot be resolved. */
  class AgentCardResolutionError(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

  protected data class EventProcessResult(
    val eventsToEmit: List<Event>,
    val taskId: String?,
    val isTerminal: Boolean?,
    val shouldClose: Boolean,
  )

  internal companion object A2AMetadata {
    const val REQUEST = "a2a:request"
    const val RESPONSE = "a2a:response"
    const val REQUEST_ERROR = "a2a:request_serialization_error"
    const val RESPONSE_ERROR = "a2a:response_serialization_error"
  }
}
