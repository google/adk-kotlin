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
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionResponse
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

/** Returns the last user function call event from the list of events. */
internal fun List<Event>.findUserFunctionCall(): Event? {
  val candidate = lastOrNull() ?: return null
  if (candidate.author != Role.USER) return null

  val functionId =
    candidate.functionResponses().firstOrNull()?.id?.takeIf { it.isNotEmpty() } ?: return null

  return dropLast(1).findLast { it.isUserFunctionCall(functionId) }
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
    // Before presentAsUserMessage, which would stringify a response beyond the classifier's reach.
    val sanitized = event.withoutCredentialResponses()
    if (sanitized.author != Role.USER && sanitized.author != agent.name) {
      presentAsUserMessage(sanitized, contextIdValue, invocationId)
    } else {
      sanitized
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

/**
 * Function-call names whose responses carry credential material.
 *
 * A response to one of these is consumed locally by ADK; it is never part of what the remote agent
 * asked for.
 */
private val CREDENTIAL_FUNCTION_CALL_NAMES = setOf(FunctionCall.REQUEST_EUC_FUNCTION_CALL_NAME)

/**
 * Response keys that identify an auth-config payload, in both the snake_case the A2A wire uses and
 * the camelCase Kotlin callers tend to write.
 */
private val CREDENTIAL_PAYLOAD_KEYS =
  setOf(
    "auth_scheme",
    "authScheme",
    "exchanged_auth_credential",
    "exchangedAuthCredential",
    "raw_auth_credential",
    "rawAuthCredential",
  )

/**
 * Maps each of this call event's pending call ids to the names it was called under.
 *
 * Classification uses the name the *call* was made under, so it does not depend on what the
 * response calls itself.
 */
internal fun Event.trustedCallNamesById(): Map<String?, Set<String>> =
  functionCalls().groupBy({ it.id }, { it.name }).mapValues { (_, names) -> names.toSet() }

/**
 * Returns this event without any function-response part that carries credential material.
 *
 * A response counts as credential-bearing when the call it answers is a known credential request
 * (resolved through [trustedCallNames]), when it names one itself, or when its payload is shaped
 * like an auth config, looking through a single-key `result` envelope.
 */
internal fun Event.withoutCredentialResponses(
  trustedCallNames: Map<String?, Set<String>> = emptyMap()
): Event {
  val content = content ?: return this
  val kept =
    content.parts.filterNot { part ->
      val response = part.functionResponse
      response != null && response.isCredential(trustedCallNames[response.id])
    }
  if (kept.size == content.parts.size) return this
  return copy(content = content.copy(parts = kept))
}

private fun FunctionResponse.isCredential(trustedNames: Set<String>?): Boolean =
  trustedNames?.any { it in CREDENTIAL_FUNCTION_CALL_NAMES } == true ||
    name in CREDENTIAL_FUNCTION_CALL_NAMES ||
    response.unwrapResult().isAuthConfigShaped()

/** Looks through the `{"result": {...}}` envelope ADK wraps a tool result in. */
private fun Map<String, Any?>.unwrapResult(): Map<*, *> =
  (entries.singleOrNull()?.takeIf { it.key == RESULT_KEY }?.value as? Map<*, *>) ?: this

private fun Map<*, *>.isAuthConfigShaped(): Boolean = keys.any { it in CREDENTIAL_PAYLOAD_KEYS }

private const val RESULT_KEY = "result"
