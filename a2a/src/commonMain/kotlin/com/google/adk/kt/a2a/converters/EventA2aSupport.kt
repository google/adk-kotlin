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
package com.google.adk.kt.a2a.converters

import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.events.Event
import com.google.adk.kt.ids.Uuid
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.GroundingMetadata
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.UsageMetadata
import kotlin.reflect.KClass
import kotlin.time.Clock

internal object MetadataKeys {
  const val GROUNDING = "adk_grounding_metadata"
  const val USAGE = "adk_usage_metadata"
  const val PARTIAL = "adk_partial"
  const val CUSTOM = "adk_custom_metadata"
  const val ERROR_CODE = "adk_error_code"
  const val IS_LONG_RUNNING = "adk_is_long_running"
  const val TYPE = "adk_type"
  const val AUTHOR = "adk_author"
}

internal const val ADK_METADATA_TASK_ID = "adk_task_id"
internal const val ADK_METADATA_CONTEXT_ID = "adk_context_id"

/** Interface for parsing A2A metadata without depending on a specific JSON library. */
internal interface A2AMetadataParser {
  fun <T : Any> parse(metadata: Any?, clazz: KClass<T>): T?
}

/** Converts an LlmResponse to an ADK Event. */
internal fun LlmResponse.toEvent(context: InvocationContext): Event {
  return Event(
    id = Uuid.random(),
    invocationId = context.invocationId,
    author = context.agent.name,
    content = this.content,
    usageMetadata = this.usageMetadata,
    finishReason = this.finishReason,
    errorMessage = this.errorMessage,
    partial = this.partial,
    interrupted = this.interrupted,
    groundingMetadata = this.groundingMetadata,
    citationMetadata = this.citationMetadata,
    modelVersion = this.modelVersion,
    timestamp = Clock.System.now().toEpochMilliseconds(),
  )
}

/** Returns the task ID from the event. */
internal val Event.taskId: String
  get() = metadataValue(ADK_METADATA_TASK_ID)

/** Returns the context ID from the event. */
internal val Event.contextId: String
  get() = metadataValue(ADK_METADATA_CONTEXT_ID)

/**
 * Whether this user event has no content and is therefore not a turn. A message-less resume appends
 * one to carry its state delta; rewind markers and compactions have the same shape.
 */
private fun Event.isStateOnlyUserEvent(): Boolean = author == Role.USER && content == null

/** Returns the last turn event, skipping trailing state-only user events. */
internal fun List<Event>.lastTurnEvent(): Event? = lastOrNull { !it.isStateOnlyUserEvent() }

/** Returns the last user function call event from the list of events. */
internal fun List<Event>.findUserFunctionCall(): Event? {
  val candidate = lastTurnEvent() ?: return null
  if (candidate.author != Role.USER) return null

  val functionId =
    candidate.functionResponses().firstOrNull()?.id?.takeIf { it.isNotEmpty() } ?: return null

  return take(lastIndexOf(candidate)).findLast { it.isUserFunctionCall(functionId) }
}

/** Returns the preprocessed events that should be sent to the agent. */
internal fun InvocationContext.extractPreprocessedEvents(): List<Event> {
  val events = session.events
  if (events.isEmpty()) {
    return emptyList()
  }

  val lastResponseIndex = events.indexOfLast { it.author == agent.name }
  val contextIdValue = if (lastResponseIndex != -1) events[lastResponseIndex].contextId else ""

  return events.drop(lastResponseIndex + 1).map { event ->
    if (event.author != Role.USER && event.author != agent.name) {
      presentAsUserMessage(event, contextIdValue, invocationId)
    } else {
      event
    }
  }
}

/** Updates event metadata using the provided parser. */
internal fun Event.updateEventMetadata(
  clientMetadata: Map<String, Any?>?,
  taskId: String?,
  contextId: String?,
  parser: A2AMetadataParser,
): Event {
  if (taskId == null || contextId == null) {
    return this
  }

  val metadata = clientMetadata ?: emptyMap()
  val customMetadataMap =
    buildMap<String, Any> {
      put(ADK_METADATA_TASK_ID, taskId)
      put(ADK_METADATA_CONTEXT_ID, contextId)
      @Suppress("UNCHECKED_CAST")
      (metadata[MetadataKeys.CUSTOM] as? Map<String, Any>)?.let { putAll(it) }
    }

  return copy(
    groundingMetadata = parser.parse(metadata[MetadataKeys.GROUNDING], GroundingMetadata::class),
    usageMetadata = parser.parse(metadata[MetadataKeys.USAGE], UsageMetadata::class),
    customMetadata = customMetadataMap,
    errorCode = metadata[MetadataKeys.ERROR_CODE] as? String,
  )
}

/** Converts an event to a user message. */
internal fun presentAsUserMessage(event: Event, contextId: String, invocationId: String): Event {
  val parts = event.content?.parts?.filter { it.thought != true } ?: emptyList()
  val rephrasedParts = parts.map { remoteCallAsUserPart(event.author, it) }
  val customMetadata = mapOf(ADK_METADATA_CONTEXT_ID to contextId)

  if (rephrasedParts.isEmpty()) {
    return Event(invocationId = invocationId, author = Role.USER, customMetadata = customMetadata)
  }

  val forContextPart = Part(text = "For context:")
  val allParts = listOf(forContextPart) + rephrasedParts
  return Event(
    invocationId = invocationId,
    author = Role.USER,
    content = Content(role = event.content?.role ?: Role.USER, parts = allParts),
    customMetadata = customMetadata,
  )
}

/** Converts a part to a user message. */
internal fun remoteCallAsUserPart(author: String, part: Part): Part {
  return when {
    part.text != null -> {
      Part(text = "[$author] said: ${part.text}")
    }
    part.functionCall != null -> {
      val fc = part.functionCall
      Part(text = "[$author] called tool ${fc?.name} with parameters: ${fc?.args}")
    }
    part.functionResponse != null -> {
      val fr = part.functionResponse
      Part(text = "[$author] ${fr?.name} tool returned result: ${fr?.response}")
    }
    else -> part
  }
}

/** Returns an empty event with the given invocation context. */
internal fun emptyEvent(invocationContext: InvocationContext): Event {
  return Event(
    invocationId = invocationContext.invocationId,
    author = invocationContext.agent.name,
    branch = invocationContext.branch,
    content = Content(role = Role.USER, parts = emptyList()),
  )
}

/** Returns an event with the given invocation context and the agent as the author. */
internal fun remoteAgentEvent(invocationContext: InvocationContext): Event {
  return Event(
    invocationId = invocationContext.invocationId,
    author = invocationContext.agent.name,
    branch = invocationContext.branch,
  )
}

private fun Event.isUserFunctionCall(functionResponseId: String): Boolean {
  return functionCalls().any { it.id == functionResponseId }
}

private fun Event.metadataValue(key: String): String {
  return customMetadata?.get(key)?.toString() ?: ""
}
