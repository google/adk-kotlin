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
// Uses ADK's @FrameworkInternalApi serialization helpers (adkJson / anyToJsonElement /
// jsonElementToAny) to (de)serialize free-form `Any` payloads; opt in once for the whole file.
@file:OptIn(FrameworkInternalApi::class)

package com.google.adk.kt.a2a.converters

import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.events.Event
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.ids.Uuid
import com.google.adk.kt.serialization.adkJson
import com.google.adk.kt.serialization.anyToJsonElement
import com.google.adk.kt.serialization.jsonElementToAny
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.CodeExecutionResult
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.ExecutableCode
import com.google.adk.kt.types.FileData
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.GroundingMetadata
import com.google.adk.kt.types.Language
import com.google.adk.kt.types.Outcome
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.PartMediaResolution
import com.google.adk.kt.types.PartMediaResolutionLevel
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.ToolCall
import com.google.adk.kt.types.ToolResponse
import com.google.adk.kt.types.UsageMetadata
import com.google.adk.kt.types.VideoMetadata
import java.util.Base64
import kotlin.enums.enumEntries
import kotlin.reflect.KClass
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
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
      val serializer = serializerFor(clazz)
      if (serializer == null) {
        logger.warn("No serializer for metadata type ${clazz.simpleName}; skipping")
        return null
      }
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

private val ANSWER_STATES =
  setOf(TaskState.TASK_STATE_COMPLETED, TaskState.TASK_STATE_FAILED, TaskState.TASK_STATE_CANCELED)

// DataPart types
internal const val TYPE_FUNCTION_CALL = "function_call"
internal const val TYPE_FUNCTION_RESPONSE = "function_response"
internal const val TYPE_EXECUTABLE_CODE = "executable_code"
internal const val TYPE_CODE_EXECUTION_RESULT = "code_execution_result"
internal const val TYPE_TOOL_CALL = "tool_call"
internal const val TYPE_TOOL_RESPONSE = "tool_response"
internal const val DEFAULT_ERROR_MESSAGE = "A2A task failed"

// Wrap a DataPart carried as an inline text blob; the other ADK ports use the same tags.
private const val DATA_PART_MIME_TYPE = "text/plain"
private const val DATA_PART_START_TAG = "<a2a_datapart_json>"
private const val DATA_PART_END_TAG = "</a2a_datapart_json>"

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
    return !this.task.artifacts.isNullOrEmpty()
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
      else -> return false
    }
  return state.isFinalState() || state.isInterruptedState()
}

/** Converts an artifact to an ADK event. */
internal fun Artifact.toAdkEvent(invocationContext: InvocationContext): Event {
  val converted = parts().convertParts()
  return remoteAgentEvent(invocationContext)
    .copy(
      content = Content(role = Role.MODEL, parts = converted.parts),
      longRunningToolIds = converted.longRunningToolIds,
    )
}

/** Converts an A2A message to an ADK event, or returns null if none of its parts converted. */
internal fun Message.toAdkEvent(invocationContext: InvocationContext): Event? {
  val adkParts = parts.toAdk()
  if (adkParts.isEmpty()) return null
  val event =
    remoteAgentEvent(invocationContext).copy(content = Content(role = Role.MODEL, parts = adkParts))
  return event.updateEventMetadata(metadata, taskId, contextId, metadataParser)
}

/**
 * Converts the status message of a task in [state] to an ADK event, or returns null if none of its
 * parts converted. A pending task's parts become thoughts, and a task waiting on the user keeps the
 * ids of the remote's long-running calls, as [Task.toAdkEvent] does.
 */
internal fun Message.toAdkEvent(invocationContext: InvocationContext, state: TaskState): Event? {
  val converted = parts.convertParts()
  val adkParts =
    converted.parts.map { if (state in PENDING_STATES) it.copy(thought = true) else it }
  if (adkParts.isEmpty()) return null
  return remoteAgentEvent(invocationContext)
    .copy(
      content = Content(role = Role.MODEL, parts = adkParts),
      longRunningToolIds =
        if (state.isInterruptedState()) converted.longRunningToolIds else emptySet(),
    )
}

/** Converts an A2A [Task] to an ADK [Event]. */
internal fun Task.toAdkEvent(invocationContext: InvocationContext): Event {
  val adkParts = mutableListOf<Part>()
  val longRunningToolIds = mutableSetOf<String>()

  for (artifact in artifacts.orEmpty()) {
    val converted = artifact.parts().convertParts()
    longRunningToolIds.addAll(converted.longRunningToolIds)
    adkParts.addAll(converted.parts)
  }

  var errorMessage: String? = null
  status.message()?.let { msg ->
    val converted = msg.parts.convertParts()
    val msgParts = converted.parts
    longRunningToolIds.addAll(converted.longRunningToolIds)
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

  // A finished task's output is the remote's answer; the default error message is not output.
  val isAnswer = status.state() in ANSWER_STATES && (adkParts.isNotEmpty() || errorMessage != null)
  errorMessage =
    errorMessage ?: DEFAULT_ERROR_MESSAGE.takeIf { status.state() == TaskState.TASK_STATE_FAILED }
  val isFinal = status.state().isFinalState() || status.state().isInterruptedState()

  if (adkParts.isEmpty() && !isFinal) {
    return emptyEvent(invocationContext)
  }
  // A task still in progress reports progress, not its answer, so ADK Python marks it as thoughts.
  if (status.state() in PENDING_STATES) adkParts.replaceAll { it.copy(thought = true) }

  val event =
    remoteAgentEvent(invocationContext)
      .copy(
        content = if (adkParts.isNotEmpty()) Content(role = Role.MODEL, parts = adkParts) else null,
        longRunningToolIds =
          if (status.state().isInterruptedState()) longRunningToolIds else emptySet(),
        turnComplete = isFinal,
        errorMessage = errorMessage,
        actions = EventActions(skipSummarization = isAnswer),
      )
      .withUserInputCall(status.state())

  return event.updateEventMetadata(metadata, id, contextId, metadataParser)
}

/** Converts an A2A Part to an ADK Part. */
internal fun A2APart<*>.toAdk(): Part {
  return when (this) {
    is TextPart -> Part(text = text, thought = metadata?.get(MetadataKeys.THOUGHT) as? Boolean)
    is FilePart -> {
      val fileContent = file as FileContent
      // Despite its non-null signature, the SDK passes null for a URI file without a name.
      val sdkName: String? = fileContent.name()
      val name = sdkName?.ifEmpty { null }
      when (fileContent) {
        is FileWithUri ->
          Part(
            fileData =
              FileData(
                mimeType = fileContent.mimeType(),
                displayName = name,
                fileUri = fileContent.uri(),
              )
          )
        is FileWithBytes ->
          Part(
            inlineData =
              Blob(fileContent.mimeType(), name, Base64.getDecoder().decode(fileContent.bytes()))
          )
      }.withMediaControl(metadata.orEmpty())
    }
    is DataPart -> toAdk()
    else -> throw IllegalArgumentException("Unsupported A2A Part type: ${this::class.simpleName}")
  }
}

/** Converts a list of A2A Parts to a list of ADK Parts, skipping any that cannot be converted. */
internal fun List<A2APart<*>>.toAdk(): List<Part> = convertParts().parts

/** ADK parts converted from A2A parts, and the ids of the long-running calls among them. */
private data class ConvertedParts(val parts: List<Part>, val longRunningToolIds: Set<String>)

/**
 * Converts peer-supplied parts, skipping any part that cannot be converted so that one malformed
 * part does not fail the whole event.
 */
private fun List<A2APart<*>>.convertParts(): ConvertedParts {
  val parts = mutableListOf<Part>()
  val longRunningToolIds = mutableSetOf<String>()
  for (a2aPart in this) {
    val part =
      try {
        a2aPart.toAdk()
      } catch (e: Exception) {
        // The exception message can quote the peer's bytes, so log only the types.
        logger.warn(
          "Skipping A2A part that could not be converted: " +
            "${a2aPart::class.simpleName} (${e::class.simpleName})"
        )
        continue
      }
    parts += part
    if (a2aPart is DataPart && a2aPart.metadata?.get(MetadataKeys.IS_LONG_RUNNING) == true) {
      part.functionCall?.id?.let { longRunningToolIds += it }
    }
  }
  return ConvertedParts(parts, longRunningToolIds)
}

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
            msg.toAdkEvent(context, taskState)
          }
        }

      val finalEvent =
        if (status.state().isFinalState() || status.state().isInterruptedState()) {
          val baseEvent =
            (messageEvent ?: remoteAgentEvent(context)).withUserInputCall(status.state())
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

// Converts a DataPart to an ADK Part.
// Note: We use coerceToMap for arguments and response to handle cases where the data
// is received as a string or non-map type, matching Java behavior.
private fun DataPart.toAdk(): Part {
  val type = metadata?.get(MetadataKeys.TYPE) as? String
  val part =
    when (type) {
      TYPE_FUNCTION_CALL -> {
        val dataMap = dataAsMap()
        val coercedData = dataMap.toMutableMap()
        coercedData["args"] = coerceToMap(dataMap["args"])
        val fc =
          adkJson.decodeFromJsonElement(FunctionCall.serializer(), anyToJsonElement(coercedData))
        Part(functionCall = fc)
      }
      TYPE_FUNCTION_RESPONSE -> {
        val dataMap = dataAsMap()
        val coercedData = dataMap.toMutableMap()
        coercedData["response"] = coerceToMap(dataMap["response"])
        val fr =
          adkJson.decodeFromJsonElement(
            FunctionResponse.serializer(),
            anyToJsonElement(coercedData),
          )
        Part(functionResponse = fr)
      }
      TYPE_EXECUTABLE_CODE ->
        Part(
          executableCode =
            decodeData(ExecutableCode.serializer(), "language", Language.LANGUAGE_UNSPECIFIED)
        )
      TYPE_CODE_EXECUTION_RESULT ->
        Part(
          codeExecutionResult =
            decodeData(CodeExecutionResult.serializer(), "outcome", Outcome.OUTCOME_UNSPECIFIED)
        )
      TYPE_TOOL_CALL -> Part(toolCall = decodeData(ToolCall.serializer()))
      TYPE_TOOL_RESPONSE -> Part(toolResponse = decodeData(ToolResponse.serializer()))
      else -> return toInlineJsonPart()
    }
  return part.copy(thoughtSignature = decodeThoughtSignature())
}

/** Decodes the thought signature this part's metadata carries in base64, if it is valid. */
private fun DataPart.decodeThoughtSignature(): ByteArray? {
  val encoded = metadata?.get(MetadataKeys.THOUGHT_SIGNATURE) as? String ?: return null
  return try {
    Base64.getDecoder().decode(encoded)
  } catch (e: IllegalArgumentException) {
    logger.warn("Ignoring a thought signature that is not valid base64")
    null
  }
}

// In A2A v1.0, DataPart.data may hold any JSON value; the ADK part payloads it carries are JSON
// objects, so any other value fails this cast.
@Suppress("UNCHECKED_CAST")
private fun DataPart.dataAsMap(): Map<String, Any?> = data as Map<String, Any?>

private fun <T> DataPart.decodeData(serializer: KSerializer<T>): T =
  adkJson.decodeFromJsonElement(serializer, anyToJsonElement(dataAsMap()))

/**
 * Decodes this DataPart's data, matching the enum value under [enumKey] case-insensitively, as ADK
 * Python does. Any other value becomes [unspecified], so the rest of the part is kept.
 */
private inline fun <T, reified E : Enum<E>> DataPart.decodeData(
  serializer: KSerializer<T>,
  enumKey: String,
  unspecified: E,
): T {
  val data = dataAsMap().toMutableMap()
  data[enumKey]?.let { value ->
    data[enumKey] = (enumEntryIgnoringCase<E>(value.toString()) ?: unspecified).name
  }
  return adkJson.decodeFromJsonElement(serializer, anyToJsonElement(data))
}

/**
 * Carries a DataPart with no matching ADK part type as inline JSON of its data, as ADK Python and
 * Go do for A2A 1.x. The DataPart's metadata becomes the part's metadata, so [toA2A] can send it
 * back.
 */
private fun DataPart.toInlineJsonPart(): Part {
  val json = adkJson.encodeToString(JsonElement.serializer(), anyToJsonElement(data))
  return Part(
    inlineData =
      Blob(
        mimeType = DATA_PART_MIME_TYPE,
        data = "$DATA_PART_START_TAG$json$DATA_PART_END_TAG".encodeToByteArray(),
      ),
    partMetadata = metadata?.ifEmpty { null },
  )
}

/**
 * Returns this part with the video metadata and media resolution that an A2A file part carries in
 * [metadata]. A value that does not decode is skipped, so the file itself is kept.
 */
private fun Part.withMediaControl(metadata: Map<String, Any?>): Part =
  copy(
    videoMetadata = metadata[MetadataKeys.VIDEO_METADATA]?.let(::decodeVideoMetadata),
    mediaResolution =
      (metadata[MetadataKeys.MEDIA_RESOLUTION] as? Map<*, *>)?.toPartMediaResolution(),
  )

private fun decodeVideoMetadata(value: Any): VideoMetadata? =
  try {
    adkJson.decodeFromJsonElement(VideoMetadata.serializer(), anyToJsonElement(value))
  } catch (e: IllegalArgumentException) {
    // Also covers SerializationException, a subclass.
    logger.warn("Ignoring A2A video metadata that does not decode (${e::class.simpleName})")
    null
  }

// Read by hand: A2A metadata is a protobuf Struct, so numTokens arrives as a Double. A level this
// build does not know, or a numTokens that is not a whole Int, is left unset instead of guessed.
private fun Map<*, *>.toPartMediaResolution(): PartMediaResolution? {
  val level =
    (this["level"] as? String)?.let { enumEntryIgnoringCase<PartMediaResolutionLevel>(it) }
  val numTokens =
    ((this["numTokens"] ?: this["num_tokens"]) as? Number)?.toDouble()?.let { number ->
      number.toInt().takeIf { it.toDouble() == number }
    }
  return if (level == null && numTokens == null) null else PartMediaResolution(level, numTokens)
}

private inline fun <reified E : Enum<E>> enumEntryIgnoringCase(name: String): E? =
  enumEntries<E>().firstOrNull { it.name.equals(name, ignoreCase = true) }

/**
 * Returns the A2A metadata that carries this part's video metadata and media resolution, empty when
 * it has neither.
 */
private fun Part.mediaControlMetadata(): Map<String, Any> = buildMap {
  videoMetadata?.let {
    put(MetadataKeys.VIDEO_METADATA, encodeAsMap(VideoMetadata.serializer(), it))
  }
  mediaResolution?.let {
    put(MetadataKeys.MEDIA_RESOLUTION, encodeAsMap(PartMediaResolution.serializer(), it))
  }
}

/** Encodes [value], whose type serializes as a JSON object, as a map of its fields. */
private fun <T> encodeAsMap(serializer: KSerializer<T>, value: T): Map<*, *> =
  jsonElementToAny(adkJson.encodeToJsonElement(serializer, value)) as Map<*, *>

/**
 * Turns tagged inline JSON, as [toInlineJsonPart] makes, back into a DataPart carrying [metadata],
 * or returns null if it cannot.
 */
private fun Blob.toTaggedDataPartOrNull(metadata: Map<String, Any?>): DataPart? {
  val json =
    taggedJsonOrNull("Sending tagged inline data that is not JSON as a file") ?: return null
  val dataValue = jsonElementToAny(json)
  if (dataValue == null) {
    // The SDK's DataPart cannot carry JSON null, so it goes out as a file, unlike in Python.
    logger.warn("Sending tagged inline JSON null as a file")
    return null
  }
  // Drops top-level nulls, unlike Python: the A2A Java SDK cannot parse them in a peer's reply.
  val dataMetadata = metadata.mapNotNull { (key, value) -> value?.let { key to it } }.toMap()
  return DataPart(dataValue, dataMetadata.ifEmpty { null })
}

/**
 * Returns the JSON in tagged inline data, as [toInlineJsonPart] makes, or null if this is not
 * tagged JSON. Logs [parseFailure] if the tagged text does not parse.
 */
private fun Blob.taggedJsonOrNull(parseFailure: String): JsonElement? {
  if (mimeType != DATA_PART_MIME_TYPE) return null
  val text = data?.decodeToString() ?: return null
  val json = text.removeSurrounding(DATA_PART_START_TAG, DATA_PART_END_TAG)
  if (json.length == text.length) return null
  return try {
    adkJson.parseToJsonElement(json)
  } catch (e: SerializationException) {
    // The exception message can quote the bytes, so log only the type.
    logger.warn("$parseFailure (${e::class.simpleName})")
    null
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
    return TextPart(it, thought?.let { thought -> mapOf(MetadataKeys.THOUGHT to thought) })
  }
  fileData?.let { fd ->
    return FilePart(
      FileWithUri(
        fd.mimeType ?: "application/octet-stream",
        fd.displayName ?: "",
        fd.fileUri ?: "",
      ),
      mediaControlMetadata().ifEmpty { null },
    )
  }
  inlineData?.let { blob ->
    blob.toTaggedDataPartOrNull(partMetadata.orEmpty())?.let {
      return it
    }
    val bytesStr = blob.data?.let { Base64.getEncoder().encodeToString(it) } ?: ""
    return FilePart(
      FileWithBytes(blob.mimeType ?: "application/octet-stream", blob.displayName ?: "", bytesStr),
      mediaControlMetadata().ifEmpty { null },
    )
  }
  functionCall?.let {
    return typedDataPart(TYPE_FUNCTION_CALL, FunctionCall.serializer(), it, isPartial)
  }
  functionResponse?.let {
    return typedDataPart(TYPE_FUNCTION_RESPONSE, FunctionResponse.serializer(), it)
  }
  codeExecutionResult?.let {
    return typedDataPart(TYPE_CODE_EXECUTION_RESULT, CodeExecutionResult.serializer(), it)
  }
  executableCode?.let {
    return typedDataPart(TYPE_EXECUTABLE_CODE, ExecutableCode.serializer(), it)
  }
  toolCall?.let {
    return typedDataPart(TYPE_TOOL_CALL, ToolCall.serializer(), it)
  }
  toolResponse?.let {
    return typedDataPart(TYPE_TOOL_RESPONSE, ToolResponse.serializer(), it)
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

  val lastResponseIndex = lastResponseIndex()

  return preprocessedEvents.flatMapIndexed { index, event ->
    val actualIndex = lastResponseIndex + 1 + index
    val eventParts = event.content?.toA2aParts(event.partial) ?: emptyList()
    logger.debug(
      "Event index=$actualIndex author=${event.author} extracted parts=${eventParts.size}"
    )
    eventParts
  }
}

/**
 * Carries a payload of this part as a DataPart whose `adk_type` metadata names its [type], with the
 * part's thought signature in base64.
 */
private fun <T> Part.typedDataPart(
  type: String,
  serializer: KSerializer<T>,
  value: T,
  isPartial: Boolean = false,
): DataPart {
  val dataMap = encodeAsMap(serializer, value)
  val metadata =
    buildMap<String, Any> {
      put(MetadataKeys.TYPE, type)
      if (isPartial) put(MetadataKeys.PARTIAL, true)
      thoughtSignature?.let {
        put(MetadataKeys.THOUGHT_SIGNATURE, Base64.getEncoder().encodeToString(it))
      }
    }
  return DataPart(dataMap, metadata)
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

internal fun TaskState.isFinalState(): Boolean {
  return when (this) {
    TaskState.TASK_STATE_COMPLETED,
    TaskState.TASK_STATE_CANCELED,
    TaskState.TASK_STATE_FAILED,
    TaskState.TASK_STATE_REJECTED,
    TaskState.TASK_STATE_UNSPECIFIED -> true
    else -> false
  }
}

internal fun TaskState.isInterruptedState(): Boolean {
  return when (this) {
    TaskState.TASK_STATE_INPUT_REQUIRED,
    TaskState.TASK_STATE_AUTH_REQUIRED -> true
    else -> false
  }
}

/**
 * Replaces the last part carrying a prompt with a long-running call when the remote task waits in
 * [state] for the user, so the app can answer a remote that sent no long-running call of its own.
 */
private fun Event.withUserInputCall(state: TaskState): Event {
  if (longRunningToolIds.isNotEmpty()) return this
  val kind =
    when (state) {
      TaskState.TASK_STATE_INPUT_REQUIRED -> UserInputCall.INPUT
      TaskState.TASK_STATE_AUTH_REQUIRED -> UserInputCall.AUTH
      else -> return this
    }
  val content = content ?: return this
  for (index in content.parts.indices.reversed()) {
    val prompt = content.parts[index].userInputPrompt()
    if (!prompt.isUsablePrompt()) continue
    val callId = Uuid.random()
    val call = FunctionCall(name = kind.callName, args = mapOf(kind.argsKey to prompt), id = callId)
    val parts = content.parts.toMutableList().apply { set(index, Part(functionCall = call)) }
    return copy(content = content.copy(parts = parts), longRunningToolIds = setOf(callId))
  }
  return this
}

/** The call made up for a remote task waiting on the user, and the key its prompt goes under. */
private enum class UserInputCall(val callName: String, val argsKey: String) {
  INPUT(MOCK_FUNCTION_CALL_FOR_REQUIRED_USER_INPUT, "input_required"),
  AUTH(MOCK_FUNCTION_CALL_FOR_REQUIRED_USER_AUTH, "auth_required"),
}

/** Returns the prompt this part carries: its text, or the data of a DataPart carried as JSON. */
private fun Part.userInputPrompt(): Any? {
  if (!text.isNullOrEmpty()) return text
  // On A2A 1.x the JSON is the DataPart's data itself, not the A2A 0.3 part whose `data` entry ADK
  // Python reads, so all of it is the prompt.
  val json = inlineData?.taggedJsonOrNull("Ignoring inline JSON that does not parse as a prompt")
  return json?.let { jsonElementToAny(it) }
}

/**
 * Whether [this] counts as a prompt: as in a Python condition, null, false, zero and empty do not.
 */
private fun Any?.isUsablePrompt(): Boolean =
  when (this) {
    null -> false
    is Boolean -> this
    is Number -> toDouble() != 0.0
    is String -> isNotEmpty()
    is Collection<*> -> isNotEmpty()
    is Map<*, *> -> isNotEmpty()
    else -> true
  }
