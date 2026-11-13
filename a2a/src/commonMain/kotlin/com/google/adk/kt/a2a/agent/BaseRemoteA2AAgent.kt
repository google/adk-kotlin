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

import com.google.adk.kt.a2a.converters.contextId
import com.google.adk.kt.a2a.converters.credentialCallIds
import com.google.adk.kt.a2a.converters.decodeA2aJson
import com.google.adk.kt.a2a.converters.extractPreprocessedEvents
import com.google.adk.kt.a2a.converters.findUserFunctionCall
import com.google.adk.kt.a2a.converters.isFailedRequest
import com.google.adk.kt.a2a.converters.remoteIdsMetadata
import com.google.adk.kt.a2a.converters.taskId
import com.google.adk.kt.a2a.converters.withMockCallAnswersAsText
import com.google.adk.kt.a2a.converters.withoutCredentialParts
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
      logger.warn { "No parts to send; skipping remote A2A call." }
      // Keeps the remote context id, which the next turn reads from this agent's last event.
      return flowOf(
        Event(
          invocationId = context.invocationId,
          author = name,
          turnComplete = true,
          customMetadata = outboundEvent.customMetadata,
        )
      )
    }

    return createA2aCallbackFlow(context, outboundEvent)
  }

  /** Returns the prepared event to be sent to the remote agent. */
  protected fun prepareOutboundEvent(context: InvocationContext): Event {
    val callEvent = context.session.events.findUserFunctionCall()

    if (callEvent != null) {
      val responseEvent =
        context.session.events
          .last()
          .withoutCredentialParts(context.session.events.credentialCallIds())
          .withMockCallAnswersAsText(callEvent.functionCalls())
      // With nothing left to resume with, the scrubbed history below is sent instead.
      if (!responseEvent.content?.parts.isNullOrEmpty()) {
        val updatedMetadata =
          responseEvent.customMetadata.orEmpty() +
            remoteIdsMetadata(callEvent.taskId, callEvent.contextId)

        return responseEvent.copy(author = Role.USER, customMetadata = updatedMetadata)
      }
    }
    // Skips events with neither a context id nor a relayed response, such as a failed request's.
    val contextId =
      context.session.events
        .findLast {
          it.author == name &&
            (it.contextId.isNotEmpty() || A2AMetadata.RESPONSE in it.customMetadata.orEmpty())
        }
        ?.contextId
        ?.ifEmpty { null }
    val customMetadata = contextId?.let {
      remoteIdsMetadata(resumableTaskId(context.session.events, it), it)
    }
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

  /**
   * Returns the id of the task that the remote agent's latest response left waiting on the user, if
   * that response is in [contextId], so a reply that answers no call resumes it. Returns null
   * otherwise.
   */
  private fun resumableTaskId(events: List<Event>, contextId: String): String? {
    for (event in events.asReversed()) {
      if (event.author != name) continue
      val metadata = event.customMetadata.orEmpty()
      // A failed request may have resumed the task, so its recorded state is stale.
      if (event.isFailedRequest) return null
      // A response that could not be recorded hides the task's state.
      if (A2AMetadata.RESPONSE_ERROR in metadata) return null
      // Checks the key: a recorded null response holds no task state and ends the search.
      if (A2AMetadata.RESPONSE !in metadata) continue
      val state = taskState(metadata[A2AMetadata.RESPONSE])
      if (event.contextId != contextId || state !in PAUSED_TASK_STATES) return null
      return event.taskId.ifEmpty { null }
    }
    return null
  }

  /**
   * Returns a copy of [event] whose metadata records [debugRequest] (only if the event is
   * turn-complete) and [debugResponse], decoded from JSON into maps, lists and primitives as ADK
   * Python records them; text it cannot decode, such as invalid JSON or JSON nested more than 512
   * levels deep, is recorded as is. A failed result is recorded under the matching
   * `*_serialization_error` key.
   */
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
                .map(::decodeA2aJson)
                .onSuccess { put(A2AMetadata.REQUEST, it) }
                .onFailure { put(A2AMetadata.REQUEST_ERROR, it.message ?: it.toString()) }
            }
            if (debugResponse != null) {
              debugResponse
                .map(::decodeA2aJson)
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

// A2A 1.x names, plus the A2A 0.3 names in sessions recorded by the removed v0.3 JvmA2AAgent.
private val PAUSED_TASK_STATES =
  setOf("TASK_STATE_INPUT_REQUIRED", "TASK_STATE_AUTH_REQUIRED", "input-required", "auth-required")

/**
 * Returns the task state in a recorded A2A response, or null if it holds no task. Reads both the
 * decoded object and the JSON string that earlier versions recorded.
 */
private fun taskState(response: Any?): String? {
  val root = if (response is String) decodeA2aJson(response) else response
  val status = (root as? Map<*, *>)?.get("status") as? Map<*, *>
  return status?.get("state") as? String
}
