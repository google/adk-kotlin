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

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.events.Event
import com.google.adk.kt.ids.Uuid
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FileData
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import io.a2a.client.ClientEvent
import io.a2a.client.MessageEvent
import io.a2a.client.TaskEvent
import io.a2a.client.TaskUpdateEvent
import io.a2a.spec.Artifact
import io.a2a.spec.DataPart
import io.a2a.spec.FileContent
import io.a2a.spec.FilePart
import io.a2a.spec.FileWithBytes
import io.a2a.spec.FileWithUri
import io.a2a.spec.Message
import io.a2a.spec.Part as A2APart
import io.a2a.spec.Task
import io.a2a.spec.TaskArtifactUpdateEvent
import io.a2a.spec.TaskState
import io.a2a.spec.TaskStatusUpdateEvent
import io.a2a.spec.TextPart
import java.util.Base64
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("A2aConverters")
private val objectMapper =
  ObjectMapper().registerModule(KotlinModule.Builder().build()).registerModule(JavaTimeModule())

private val metadataParser =
  object : A2AMetadataParser {
    override fun <T : Any> parse(metadata: Any?, clazz: kotlin.reflect.KClass<T>): T? {
      if (metadata == null) return null
      return try {
        if (metadata is String) {
          objectMapper.readValue(metadata, clazz.java)
        } else {
          objectMapper.convertValue(metadata, clazz.java)
        }
      } catch (e: Exception) {
        logger.warn("Failed to parse metadata of type ${clazz.simpleName}", e)
        null
      }
    }
  }

private val PENDING_STATES = setOf(TaskState.WORKING, TaskState.SUBMITTED)

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
    return this.task.artifacts.isNotEmpty()
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
      return innerEvent.isAppend == false && innerEvent.isLastChunk == false
    }
  }
  return false
}

internal fun ClientEvent.isLastChunk(): Boolean {
  if (this is TaskUpdateEvent) {
    val innerEvent = this.updateEvent
    if (innerEvent is TaskArtifactUpdateEvent) {
      return innerEvent.isLastChunk == true
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
      else -> TaskState.UNKNOWN
    }
  return state == TaskState.COMPLETED
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

  for (artifact in artifacts) {
    val converted = artifact.parts().toAdk()
    longRunningToolIds.addAll(longRunningToolIds(artifact.parts(), converted))
    adkParts.addAll(converted)
  }

  var errorMessage: String? = null
  status.message()?.let { msg ->
    val msgParts = msg.parts.toAdk()
    longRunningToolIds.addAll(longRunningToolIds(msg.parts, msgParts))
    if (status.state() == TaskState.FAILED && msgParts.size == 1 && msgParts[0].text != null) {
      errorMessage = msgParts[0].text
    } else {
      adkParts.addAll(msgParts)
    }
  }

  errorMessage = errorMessage ?: DEFAULT_ERROR_MESSAGE.takeIf { status.state() == TaskState.FAILED }
  val isFinal = status.state().isFinal || status.state() == TaskState.INPUT_REQUIRED

  if (adkParts.isEmpty() && !isFinal) {
    return emptyEvent(invocationContext)
  }

  val event =
    remoteAgentEvent(invocationContext)
      .copy(
        content = if (adkParts.isNotEmpty()) Content(role = Role.MODEL, parts = adkParts) else null,
        longRunningToolIds =
          if (status.state() == TaskState.INPUT_REQUIRED) longRunningToolIds else emptySet(),
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
      val isAppend = update.isAppend == true
      val isLastChunk = update.isLastChunk == true

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
          if (taskState == TaskState.FAILED) {
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
                ?: DEFAULT_ERROR_MESSAGE.takeIf { taskState == TaskState.FAILED },
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
  a2aParts: List<io.a2a.spec.Part<*>>,
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
  val type = metadata[MetadataKeys.TYPE] as? String
  return when (type) {
    TYPE_FUNCTION_CALL -> {
      val coercedData = data.toMutableMap()
      coercedData["args"] = coerceToMap(data["args"])
      val fc = objectMapper.convertValue(coercedData, FunctionCall::class.java)
      Part(functionCall = fc)
    }
    TYPE_FUNCTION_RESPONSE -> {
      val coercedData = data.toMutableMap()
      coercedData["response"] = coerceToMap(data["response"])
      val fr = objectMapper.convertValue(coercedData, FunctionResponse::class.java)
      Part(functionResponse = fr)
    }
    else -> throw IllegalArgumentException("Unsupported A2A DataPart type: $type")
  }
}

private fun Map<String, Any?>?.isPartial() = this?.get(MetadataKeys.PARTIAL) == true

/** Converts a GenAI Content object to a list of A2A Parts. */
internal fun Content.toLegacyA2aParts(isPartial: Boolean): List<A2APart<*>> {
  return parts.map { it.toLegacyA2aPart(isPartial) }
}

/** Converts an ADK Part to an A2A Part. */
internal fun Part.toLegacyA2aPart(isPartial: Boolean = false): A2APart<*> {
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
internal fun Event.toLegacyA2aMessage(): Message {
  return Message.Builder()
    .messageId(id.ifEmpty { Uuid.random() })
    .role(author.takeIf { it == "user" }?.let { Message.Role.USER } ?: Message.Role.AGENT)
    .parts(content?.parts?.map { it.toLegacyA2aPart() } ?: emptyList())
    .apply {
      if (taskId.isNotEmpty()) taskId(taskId)
      if (contextId.isNotEmpty()) contextId(contextId)
      if (author.isNotEmpty()) metadata(mapOf(MetadataKeys.AUTHOR to author))
    }
    .build()
}

/** Returns the parts from the context events that should be sent to the agent. */
internal fun InvocationContext.extractLegacyA2aParts(): List<A2APart<*>> {
  val preprocessedEvents = extractPreprocessedEvents()
  if (preprocessedEvents.isEmpty()) {
    return emptyList()
  }

  val lastResponseIndex = session.events.indexOfLast { it.author == agent.name }

  return preprocessedEvents.flatMapIndexed { index, event ->
    val actualIndex = lastResponseIndex + 1 + index
    val eventParts = event.content?.toLegacyA2aParts(event.partial) ?: emptyList()
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
  val dataMap = objectMapper.convertValue(this, Map::class.java) as Map<String, Any>
  return DataPart(dataMap, metadata)
}

private fun FunctionResponse.toA2A(): DataPart {
  @Suppress("UNCHECKED_CAST")
  val dataMap = objectMapper.convertValue(this, Map::class.java) as Map<String, Any>
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
          objectMapper.readValue(value, Map::class.java) as Map<String, Any?>
        } catch (e: Exception) {
          logger.warn("Failed to parse map from string payload", e)
          mapOf("value" to value)
        }
      }
    else -> mapOf("value" to value)
  }
