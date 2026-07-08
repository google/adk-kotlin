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
@file:OptIn(FrameworkInternalApi::class)

package com.google.adk.kt.a2a.converters

import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.events.Event
import com.google.adk.kt.ids.Uuid
import com.google.adk.kt.serialization.adkJson
import com.google.adk.kt.serialization.anyToJsonElement
import com.google.adk.kt.serialization.jsonElementToAny
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FileData
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.GroundingMetadata
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.UsageMetadata
import java.util.Base64
import kotlin.reflect.KClass
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import org.a2aproject.sdk.client.ClientEvent
import org.a2aproject.sdk.client.MessageEvent
import org.a2aproject.sdk.client.TaskEvent
import org.a2aproject.sdk.client.TaskUpdateEvent
import org.a2aproject.sdk.spec.Artifact
import org.a2aproject.sdk.spec.DataPart
import org.a2aproject.sdk.spec.FileContent
import org.a2aproject.sdk.spec.FilePart
import org.a2aproject.sdk.spec.FileWithBytes
import org.a2aproject.sdk.spec.FileWithUri
import org.a2aproject.sdk.spec.Message
import org.a2aproject.sdk.spec.Part as A2APart
import org.a2aproject.sdk.spec.Task
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent
import org.a2aproject.sdk.spec.TaskState
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent
import org.a2aproject.sdk.spec.TextPart
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("A2aConverters")

private val metadataParser =
  object : A2AMetadataParser {
    override fun <T : Any> parse(metadata: Any?, clazz: KClass<T>): T? {
      if (metadata == null) return null
      val serializer = serializerFor(clazz) ?: return null
      return try {
        @Suppress("UNCHECKED_CAST")
        adkJson.decodeFromJsonElement(serializer, metadata.toMetadataJsonElement()) as T
      } catch (e: Exception) {
        logger.warn("Failed to parse metadata of type ${clazz.simpleName}", e)
        null
      }
    }
  }

// A2A metadata is only ever parsed into these two ADK types; see [updateEventMetadata].
// Visible for testing the defensive null fallback.
internal fun serializerFor(clazz: KClass<*>): KSerializer<*>? =
  when (clazz) {
    GroundingMetadata::class -> GroundingMetadata.serializer()
    UsageMetadata::class -> UsageMetadata.serializer()
    else -> null
  }

// A2A metadata values arrive either as a JSON string or as an already-decoded Map/primitive tree.
private fun Any.toMetadataJsonElement(): JsonElement =
  if (this is String) adkJson.parseToJsonElement(this) else anyToJsonElement(this)

private val PENDING_STATES = setOf(TaskState.TASK_STATE_WORKING, TaskState.TASK_STATE_SUBMITTED)

// DataPart types
internal const val TYPE_FUNCTION_CALL = "function_call"
internal const val TYPE_FUNCTION_RESPONSE = "function_response"
internal const val DEFAULT_ERROR_MESSAGE = "A2A task failed"

/** Converts a A2A [ClientEvent] to an ADK [Event]. */
internal fun ClientEvent.toAdkEvent(invocationContext: InvocationContext): Event? {
  return when (this) {
    is MessageEvent -> message.toAdkEvent(invocationContext)
    is TaskEvent -> task.toAdkEvent(invocationContext)
    is TaskUpdateEvent -> toAdkEvent(invocationContext)
  }
}

/** Returns true if the event should be buffered for streaming. */
internal fun ClientEvent.shouldBuffer(): Boolean {
  if (this is TaskUpdateEvent) {
    return this.updateEvent !is TaskStatusUpdateEvent
  }
  if (this is TaskEvent) {
    return this.task.artifacts.orEmpty().isNotEmpty()
  }
  return true
}

/** Returns true if the buffer should be reset for this event. */
internal fun ClientEvent.shouldResetBuffer(): Boolean {
  if (this is TaskEvent) {
    return true
  }
  if (this is TaskUpdateEvent) {
    val innerEvent = this.updateEvent
    if (innerEvent is TaskArtifactUpdateEvent) {
      return innerEvent.append == false && innerEvent.lastChunk == false
    }
  }
  return false
}

internal fun ClientEvent.isLastChunk(): Boolean {
  if (this is TaskUpdateEvent) {
    val innerEvent = this.updateEvent
    if (innerEvent is TaskArtifactUpdateEvent) {
      return innerEvent.lastChunk == true
    }
  }
  return false
}

/** Returns true if the event indicates task completion. */
internal fun ClientEvent.isCompleted(): Boolean {
  val state =
    when (this) {
      is TaskEvent -> this.task.status.state()
      is TaskUpdateEvent -> this.task.status.state()
      else -> TaskState.UNRECOGNIZED
    }
  return state == TaskState.TASK_STATE_COMPLETED
}

/** Converts an artifact to an ADK event. */
internal fun Artifact.toAdkEvent(invocationContext: InvocationContext): Event {
  val adkParts = parts().toAdk()
  return remoteAgentEvent(invocationContext)
    .copy(
      content = Content(role = Role.MODEL, parts = adkParts),
      longRunningToolIds = longRunningToolIds(parts(), adkParts),
    )
}

/** Converts an A2A message back to ADK events. */
internal fun Message.toAdkEvent(invocationContext: InvocationContext): Event {
  val adkParts = parts.toAdk()
  val event =
    remoteAgentEvent(invocationContext).copy(content = Content(role = Role.MODEL, parts = adkParts))
  return event.updateEventMetadata(metadata, taskId, contextId, metadataParser)
}

/** Converts an A2A message back to ADK events with thought marking. */
internal fun Message.toAdkEvent(invocationContext: InvocationContext, isPending: Boolean): Event {
  val adkParts = parts.toAdk().map { it.copy(thought = isPending) }
  return remoteAgentEvent(invocationContext)
    .copy(content = Content(role = Role.MODEL, parts = adkParts))
}

/** Converts an A2A [Task] to an ADK [Event]. */
internal fun Task.toAdkEvent(invocationContext: InvocationContext): Event {
  val adkParts = mutableListOf<Part>()
  val longRunningToolIds = mutableSetOf<String>()

  for (artifact in artifacts.orEmpty()) {
    val converted = artifact.parts().toAdk()
    longRunningToolIds.addAll(longRunningToolIds(artifact.parts(), converted))
    adkParts.addAll(converted)
  }

  var errorMessage: String? = null
  status.message()?.let { msg ->
    val msgParts = msg.parts.toAdk()
    longRunningToolIds.addAll(longRunningToolIds(msg.parts, msgParts))
    if (
      status.state() == TaskState.TASK_STATE_FAILED &&
        msgParts.size == 1 &&
        msgParts[0].text != null
    ) {
      errorMessage = msgParts[0].text
    } else {
      adkParts.addAll(msgParts)
    }
  }

  errorMessage =
    errorMessage ?: DEFAULT_ERROR_MESSAGE.takeIf { status.state() == TaskState.TASK_STATE_FAILED }
  val isFinal = status.state().isFinal || status.state() == TaskState.TASK_STATE_INPUT_REQUIRED

  if (adkParts.isEmpty() && !isFinal) {
    return emptyEvent(invocationContext)
  }

  val event =
    remoteAgentEvent(invocationContext)
      .copy(
        content = if (adkParts.isNotEmpty()) Content(role = Role.MODEL, parts = adkParts) else null,
        longRunningToolIds =
          if (status.state() == TaskState.TASK_STATE_INPUT_REQUIRED) longRunningToolIds
          else emptySet(),
        turnComplete = isFinal,
        errorMessage = errorMessage,
      )

  return event.updateEventMetadata(metadata, id, contextId, metadataParser)
}

/** Converts an A2A Part to an ADK Part. */
internal fun A2APart<*>.toAdk(): Part {
  return when (this) {
    is TextPart -> Part(text = text)
    is FilePart -> {
      val fileContent = file as FileContent
      when (fileContent) {
        is FileWithUri ->
          Part(fileData = FileData(mimeType = fileContent.mimeType(), fileUri = fileContent.uri()))
        is FileWithBytes ->
          Part(
            inlineData =
              Blob(
                fileContent.mimeType(),
                fileContent.name(),
                Base64.getDecoder().decode(fileContent.bytes()),
              )
          )
      }
    }
    is DataPart -> toAdk()
    else -> throw IllegalArgumentException("Unsupported A2A Part type: ${this::class.simpleName}")
  }
}

/** Converts a list of A2A Parts to a list of ADK Parts. */
internal fun List<A2APart<*>>.toAdk(): List<Part> = map { it.toAdk() }

private fun TaskUpdateEvent.toAdkEvent(context: InvocationContext): Event? {
  return when (val update = updateEvent) {
    is TaskArtifactUpdateEvent -> {
      val isAppend = update.append == true
      val isLastChunk = update.lastChunk == true

      if (isLastChunk && update.metadata.isPartial()) {
        return null
      }

      val eventPart = update.artifact.toAdkEvent(context)
      if (eventPart.content?.parts.isNullOrEmpty()) {
        return null
      }

      eventPart
        .copy(partial = isAppend || !isLastChunk)
        .updateEventMetadata(update.metadata, update.taskId, update.contextId, metadataParser)
    }
    is TaskStatusUpdateEvent -> {
      val status = update.status
      val taskState = task.status.state()

      val messageEvent =
        status.message()?.let { msg ->
          if (taskState == TaskState.TASK_STATE_FAILED) {
            remoteAgentEvent(context)
              .copy(errorMessage = msg.parts.filterIsInstance<TextPart>().firstOrNull()?.text)
          } else {
            msg.toAdkEvent(context, PENDING_STATES.contains(taskState))
          }
        }

      val finalEvent =
        if (update.isFinal) {
          val baseEvent = messageEvent ?: remoteAgentEvent(context)
          baseEvent.copy(
            turnComplete = true,
            partial = false,
            errorMessage =
              baseEvent.errorMessage
                ?: DEFAULT_ERROR_MESSAGE.takeIf { taskState == TaskState.TASK_STATE_FAILED },
          )
        } else {
          messageEvent
        }

      finalEvent?.updateEventMetadata(
        update.metadata,
        update.taskId,
        update.contextId,
        metadataParser,
      )
    }
  }
}

private fun longRunningToolIds(
  a2aParts: List<org.a2aproject.sdk.spec.Part<*>>,
  adkParts: List<Part>,
): Set<String> {
  return a2aParts
    .zip(adkParts)
    .filter { (a2aPart, _) ->
      a2aPart is DataPart && a2aPart.metadata?.get(MetadataKeys.IS_LONG_RUNNING) == true
    }
    .mapNotNull { (_, adkPart) -> adkPart.functionCall?.id }
    .toSet()
}

// Converts a DataPart to an ADK Part.
// Note: We use coerceToMap for arguments and response to handle cases where the data
// is received as a string or non-map type, matching Java behavior.
private fun DataPart.toAdk(): Part {
  val type = metadata?.get(MetadataKeys.TYPE) as? String
  // In A2A v1.0, DataPart.data is typed as Object (it may hold any JSON value). For ADK function
  // call/response parts the payload is always a JSON object, so coerce it to a map here.
  @Suppress("UNCHECKED_CAST") val dataMap = data as Map<String, Any?>
  return when (type) {
    TYPE_FUNCTION_CALL -> {
      val coercedData = dataMap.toMutableMap()
      coercedData["args"] = coerceToMap(dataMap["args"])
      val fc =
        adkJson.decodeFromJsonElement(FunctionCall.serializer(), anyToJsonElement(coercedData))
      Part(functionCall = fc)
    }
    TYPE_FUNCTION_RESPONSE -> {
      val coercedData = dataMap.toMutableMap()
      coercedData["response"] = coerceToMap(dataMap["response"])
      val fr =
        adkJson.decodeFromJsonElement(FunctionResponse.serializer(), anyToJsonElement(coercedData))
      Part(functionResponse = fr)
    }
    else -> throw IllegalArgumentException("Unsupported A2A DataPart type: $type")
  }
}

private fun Map<String, Any?>?.isPartial() = this?.get(MetadataKeys.PARTIAL) == true

/** Converts a GenAI Content object to a list of A2A Parts. */
internal fun Content.toA2aParts(isPartial: Boolean): List<A2APart<*>> {
  return parts.map { it.toA2A(isPartial) }
}

/** Converts an ADK Part to an A2A Part. */
internal fun Part.toA2A(isPartial: Boolean = false): A2APart<*> {
  text?.let {
    return TextPart(it)
  }
  fileData?.let { fd ->
    return FilePart(FileWithUri(fd.mimeType ?: "application/octet-stream", "", fd.fileUri ?: ""))
  }
  inlineData?.let { blob ->
    val bytesStr = blob.data?.let { Base64.getEncoder().encodeToString(it) } ?: ""
    return FilePart(
      FileWithBytes(blob.mimeType ?: "application/octet-stream", blob.displayName ?: "", bytesStr)
    )
  }
  functionCall?.let {
    return it.toA2A(isPartial)
  }
  functionResponse?.let {
    return it.toA2A()
  }
  throw IllegalArgumentException("Unsupported ADK Part content")
}

/** Converts an ADK Event to an A2A Message. */
internal fun Event.toA2aMessage(): Message {
  return Message.builder()
    .messageId(id.ifEmpty { Uuid.random() })
    .role(author.takeIf { it == "user" }?.let { Message.Role.ROLE_USER } ?: Message.Role.ROLE_AGENT)
    .parts(content?.parts?.map { it.toA2A() } ?: emptyList())
    .apply {
      if (taskId.isNotEmpty()) taskId(taskId)
      if (contextId.isNotEmpty()) contextId(contextId)
      if (author.isNotEmpty()) metadata(mapOf(MetadataKeys.AUTHOR to author))
    }
    .build()
}

/** Returns the parts from the context events that should be sent to the agent. */
internal fun InvocationContext.extractA2aParts(): List<A2APart<*>> {
  val preprocessedEvents = extractPreprocessedEvents()
  if (preprocessedEvents.isEmpty()) {
    return emptyList()
  }

  val lastResponseIndex = session.events.indexOfLast { it.author == agent.name }

  return preprocessedEvents.flatMapIndexed { index, event ->
    val actualIndex = lastResponseIndex + 1 + index
    val eventParts = event.content?.toA2aParts(event.partial) ?: emptyList()
    logger.debug(
      "Event index=$actualIndex author=${event.author} extracted parts=${eventParts.size}"
    )
    eventParts
  }
}

private fun FunctionCall.toA2A(isPartial: Boolean): DataPart {
  val metadata = mutableMapOf<String, Any>(MetadataKeys.TYPE to TYPE_FUNCTION_CALL)
  if (isPartial) {
    metadata[MetadataKeys.PARTIAL] = true
  }
  @Suppress("UNCHECKED_CAST")
  val dataMap =
    jsonElementToAny(adkJson.encodeToJsonElement(FunctionCall.serializer(), this))
      as Map<String, Any>
  return DataPart(dataMap, metadata)
}

private fun FunctionResponse.toA2A(): DataPart {
  @Suppress("UNCHECKED_CAST")
  val dataMap =
    jsonElementToAny(adkJson.encodeToJsonElement(FunctionResponse.serializer(), this))
      as Map<String, Any>
  return DataPart(dataMap, mapOf(MetadataKeys.TYPE to TYPE_FUNCTION_RESPONSE))
}

private fun coerceToMap(value: Any?): Map<String, Any?> =
  when (value) {
    null -> emptyMap()
    is Map<*, *> -> value.entries.associate { it.key.toString() to it.value }
    is String ->
      if (value.isEmpty()) {
        emptyMap()
      } else {
        try {
          @Suppress("UNCHECKED_CAST")
          (jsonElementToAny(adkJson.parseToJsonElement(value)) as? Map<String, Any?>)
            ?: mapOf("value" to value)
        } catch (e: Exception) {
          logger.warn("Failed to parse map from string payload", e)
          mapOf("value" to value)
        }
      }
    else -> mapOf("value" to value)
  }
