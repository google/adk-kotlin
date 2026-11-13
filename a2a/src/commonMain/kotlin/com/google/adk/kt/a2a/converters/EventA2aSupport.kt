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

import com.google.adk.kt.a2a.agent.BaseRemoteA2AAgent
import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.events.Event
import com.google.adk.kt.ids.Uuid
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.serialization.anyToJsonElement
import com.google.adk.kt.serialization.jsonElementToAny
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.GroundingMetadata
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.UsageMetadata
import kotlin.reflect.KClass
import kotlin.time.Clock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal object MetadataKeys {
  const val GROUNDING = "adk_grounding_metadata"
  const val USAGE = "adk_usage_metadata"
  const val PARTIAL = "adk_partial"
  const val CUSTOM = "adk_custom_metadata"
  const val ERROR_CODE = "adk_error_code"
  const val IS_LONG_RUNNING = "adk_is_long_running"
  const val TYPE = "adk_type"
  const val AUTHOR = "adk_author"
  const val THOUGHT = "adk_thought"
  const val THOUGHT_SIGNATURE = "adk_thought_signature"
  const val VIDEO_METADATA = "adk_video_metadata"
  const val MEDIA_RESOLUTION = "adk_media_resolution"
}

/**
 * Event metadata keys for a response's remote task and context ids: ADK Python's, and the legacy
 * ones earlier versions used. Readers use the legacy keys only for an event with neither new key,
 * so a hook that edits or blanks a new key changes the next request's ids, as in ADK Python. To
 * clear the ids, a hook blanks the new keys or removes all four.
 */
internal object RemoteIdKeys {
  const val TASK_ID = "a2a:task_id"
  const val CONTEXT_ID = "a2a:context_id"
  const val LEGACY_TASK_ID = "adk_task_id"
  const val LEGACY_CONTEXT_ID = "adk_context_id"
  val ALL = setOf(TASK_ID, CONTEXT_ID, LEGACY_TASK_ID, LEGACY_CONTEXT_ID)
}

/** Returns [taskId] and [contextId], each if not null, under both the new and the legacy keys. */
internal fun remoteIdsMetadata(taskId: String?, contextId: String?): Map<String, String> =
  buildMap {
    if (taskId != null) {
      put(RemoteIdKeys.TASK_ID, taskId)
      put(RemoteIdKeys.LEGACY_TASK_ID, taskId)
    }
    if (contextId != null) {
      put(RemoteIdKeys.CONTEXT_ID, contextId)
      put(RemoteIdKeys.LEGACY_CONTEXT_ID, contextId)
    }
  }

/** Name of the call made up for a remote task waiting on user input. */
internal const val MOCK_FUNCTION_CALL_FOR_REQUIRED_USER_INPUT =
  "mock_function_call_for_required_user_input"

/** Name of the call made up for a remote task waiting on user authorization. */
internal const val MOCK_FUNCTION_CALL_FOR_REQUIRED_USER_AUTH =
  "mock_function_call_for_required_user_auth"

private const val REQUEST_CREDENTIAL_CALL_NAME = "adk_request_credential"

/**
 * Calls whose answers carry credentials. Only the answers are dropped: the mock auth call itself
 * holds the remote's prompt.
 */
private val CREDENTIAL_CALL_NAMES =
  setOf(REQUEST_CREDENTIAL_CALL_NAME, MOCK_FUNCTION_CALL_FOR_REQUIRED_USER_AUTH)

private val AUTH_CONFIG_ARG_KEYS = listOf("authConfig", "auth_config")

private val AUTH_CONFIG_KEYS =
  setOf(
    "authScheme",
    "auth_scheme",
    "rawAuthCredential",
    "raw_auth_credential",
    "exchangedAuthCredential",
    "exchanged_auth_credential",
  )

/** Interface for parsing A2A metadata into ADK types. */
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
  get() = remoteId(RemoteIdKeys.TASK_ID, RemoteIdKeys.LEGACY_TASK_ID)

/** Returns the context ID from the event. */
internal val Event.contextId: String
  get() = remoteId(RemoteIdKeys.CONTEXT_ID, RemoteIdKeys.LEGACY_CONTEXT_ID)

/** Returns the id under [key], or under [legacyKey] if the event has neither new key. */
private fun Event.remoteId(key: String, legacyKey: String): String {
  val metadata = customMetadata.orEmpty()
  val hasNewKeys = RemoteIdKeys.TASK_ID in metadata || RemoteIdKeys.CONTEXT_ID in metadata
  return metadataValue(if (hasNewKeys) key else legacyKey)
}

/** Returns the last user function call event from the list of events. */
internal fun List<Event>.findUserFunctionCall(): Event? {
  val candidate = lastOrNull() ?: return null
  if (candidate.author != Role.USER) return null

  val functionId =
    candidate.functionResponses().firstOrNull()?.id?.takeIf { it.isNotEmpty() } ?: return null

  return dropLast(1).findLast { it.isUserFunctionCall(functionId) }
}

/** Whether this event records a failed request: an error without a relayed response. */
internal val Event.isFailedRequest: Boolean
  get() = !errorMessage.isNullOrEmpty() && BaseRemoteA2AAgent.RESPONSE !in customMetadata.orEmpty()

/**
 * Returns the index of this agent's latest event in the session, skipping failed requests, whose
 * turn the remote may lack. Unlike in ADK Python, which skips events without a relayed response, an
 * event for a stream that ended mid-task keeps its task's context and counts, as does a callback's
 * event. Returns -1 if there is none.
 */
internal fun InvocationContext.lastResponseIndex(): Int =
  session.events.indexOfLast {
    it.author == agent.name && !(it.isFailedRequest && it.contextId.isEmpty())
  }

/** Returns the preprocessed events that should be sent to the agent. */
internal fun InvocationContext.extractPreprocessedEvents(): List<Event> {
  val events = session.events
  if (events.isEmpty()) {
    return emptyList()
  }

  val lastResponseIndex = lastResponseIndex()
  val contextIdValue = if (lastResponseIndex != -1) events[lastResponseIndex].contextId else ""

  val credentialCallIds = events.credentialCallIds()

  return events.drop(lastResponseIndex + 1).map {
    val event = it.withoutCredentialParts(credentialCallIds)
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
  @Suppress("UNCHECKED_CAST")
  val forwarded = (metadata[MetadataKeys.CUSTOM] as? Map<String, Any>).orEmpty()
  // Last, so ids a remote agent forwards from its own remote call cannot replace this task's.
  val customMetadataMap = forwarded + remoteIdsMetadata(taskId, contextId)

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
  val rephrasedParts = parts.mapNotNull { remoteCallAsUserPart(event.author, it) }
  val customMetadata = remoteIdsMetadata(taskId = null, contextId)

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

/**
 * Rephrases [part] as text attributed to [author], returns a media or code part unchanged, and
 * returns null for anything else, such as blank text, a server-side tool call, or a part carrying
 * only a thought signature.
 */
internal fun remoteCallAsUserPart(author: String, part: Part): Part? {
  return when {
    !part.text.isNullOrBlank() -> {
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
    part.inlineData != null ||
      part.fileData != null ||
      part.executableCode != null ||
      part.codeExecutionResult != null -> part
    else -> null
  }
}

/**
 * Returns the ids of the calls in these events whose answers carry credentials; null stands for an
 * id-less one.
 */
internal fun List<Event>.credentialCallIds(): Set<String?> =
  flatMap { it.functionCalls() }
    .filter { it.name in CREDENTIAL_CALL_NAMES }
    .mapTo(mutableSetOf()) { it.id }

/**
 * Returns whether this part carries credentials: an `adk_request_credential` call, a call whose
 * `authConfig` argument holds an AuthConfig, or an answer named for one of [CREDENTIAL_CALL_NAMES],
 * answering one of [credentialCallIds], or holding an AuthConfig. An answer without an id matches a
 * call without one.
 */
private fun Part.carriesCredential(credentialCallIds: Set<String?>): Boolean {
  functionResponse?.let { response ->
    return response.name in CREDENTIAL_CALL_NAMES ||
      response.id in credentialCallIds ||
      isAuthConfig(response.response)
  }
  functionCall?.let { call ->
    return call.name == REQUEST_CREDENTIAL_CALL_NAME ||
      AUTH_CONFIG_ARG_KEYS.any { isAuthConfig(call.args[it]) }
  }
  return false
}

/** Returns this event without its credential parts, which are never sent to a remote agent. */
internal fun Event.withoutCredentialParts(credentialCallIds: Set<String?>): Event {
  val content = content ?: return this
  val kept = content.parts.filterNot { it.carriesCredential(credentialCallIds) }
  return if (kept.size == content.parts.size) this else copy(content = content.copy(parts = kept))
}

/**
 * Returns this answer with each response to a mock input call among [calls] sent as text, since the
 * remote asked a question and cannot read a response to a call it never made. If the answer also
 * responds to another call, all responses are sent as is and its text is dropped, since an ADK
 * runner rejects a message that mixes the two.
 */
internal fun Event.withMockCallAnswersAsText(calls: List<FunctionCall>): Event {
  val content = content ?: return this
  val inputCallIds =
    calls
      .filter { it.name == MOCK_FUNCTION_CALL_FOR_REQUIRED_USER_INPUT }
      .mapNotNullTo(mutableSetOf()) { it.id }
  val responses = content.parts.mapNotNull { it.functionResponse }
  if (responses.none { it.id in inputCallIds }) return this
  val staysResume = responses.any { it.id !in inputCallIds }
  val parts =
    content.parts.mapNotNull { part ->
      val response = part.functionResponse
      when {
        response == null -> part.takeUnless { staysResume && it.text != null }
        staysResume -> part
        else -> answerText(response.response)?.let { Part(text = it) }
      }
    }
  return copy(content = content.copy(parts = parts))
}

/**
 * Returns the lone `result` value, or else the whole answer, as text: a string as is and anything
 * else as JSON, which a remote can parse. Returns null if the answer is empty or its lone `result`
 * is null.
 */
@OptIn(FrameworkInternalApi::class)
private fun answerText(response: Map<String, Any?>): String? {
  if (response.isEmpty()) return null
  val value =
    if (response.size == 1 && BaseTool.RESULT_KEY in response) {
      response[BaseTool.RESULT_KEY] ?: return null
    } else {
      response
    }
  return value as? String ?: anyToJsonElement(value).toString()
}

/**
 * Decodes [text] as JSON into maps, lists and primitives, the form in which ADK Python records A2A
 * requests and responses. Returns [text] unchanged if it is not strict JSON, holds a number too
 * large to store, or nests deeper than [MAX_JSON_DEPTH], since decoding recurses once per level.
 */
@OptIn(FrameworkInternalApi::class)
internal fun decodeA2aJson(text: String): Any? {
  if (nestsDeeperThan(text, MAX_JSON_DEPTH)) return text
  val element =
    try {
      Json.parseToJsonElement(text)
    } catch (e: SerializationException) {
      return text
    }
  return if (element.isStrictJson()) jsonElementToAny(element) else text
}

private const val MAX_JSON_DEPTH = 512

/**
 * Whether [text] opens more than [limit] arrays or objects at once, ignoring brackets in strings.
 */
private fun nestsDeeperThan(text: String, limit: Int): Boolean {
  var depth = 0
  var inString = false
  var escaped = false
  for (c in text) {
    when {
      escaped -> escaped = false
      inString && c == '\\' -> escaped = true
      inString -> inString = c != '"'
      c == '"' -> inString = true
      c == '[' || c == '{' -> if (++depth > limit) return true
      c == ']' || c == '}' -> depth--
    }
  }
  return false
}

private val JSON_NUMBER = Regex("""-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?""")

// The parser also accepts unquoted values such as `INFO`, `007` or `NaN`, which JSON does not.
private fun JsonElement.isStrictJson(): Boolean =
  when (this) {
    is JsonObject -> values.all { it.isStrictJson() }
    is JsonArray -> all { it.isStrictJson() }
    is JsonPrimitive ->
      isString ||
        this is JsonNull ||
        content == "true" ||
        content == "false" ||
        (JSON_NUMBER.matches(content) && content.toDouble().isFinite())
  }

// A serialized AuthConfig, bare or wrapped as {"result": ...}, has at least one of these keys.
private fun isAuthConfig(payload: Any?): Boolean {
  val candidate =
    (payload as? Map<*, *>)?.let { if (it.size == 1 && "result" in it) it["result"] else it }
  return candidate is Map<*, *> && AUTH_CONFIG_KEYS.any { it in candidate }
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
