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

@file:OptIn(ExperimentalEncodingApi::class, FrameworkInternalApi::class)

package com.google.adk.kt.serialization

import com.google.adk.kt.agents.TypedData
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.events.Event
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.events.EventCompaction
import com.google.adk.kt.events.ToolConfirmation
import com.google.adk.kt.ids.Uuid
import com.google.adk.kt.sessions.State
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.Citation
import com.google.adk.kt.types.CitationMetadata
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FileData
import com.google.adk.kt.types.FinishReason
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.GroundingMetadata
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.PartialArg
import com.google.adk.kt.types.PartialArgValue
import com.google.adk.kt.types.UsageMetadata
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Reflection-free conversion between an [Event] and a JSON-friendly `Map<String, Any?>`.
 *
 * This is the multiplatform counterpart to a reflective object mapper (Gson/Jackson/kotlinx): every
 * field of the [Event] graph is mapped by hand, so there is no runtime reflection (R8-safe) and no
 * dependency on Gson or kotlinx.serialization. Pair it with [Json] (`org.json` on Android) to get a
 * string: `Json.toJsonString(event.toMap())` and `eventFromMap(Json.fromJsonToMap(string))`.
 *
 * Encoding rules for values that JSON cannot represent directly:
 * * The [State.REMOVED] sentinel (which may appear in [EventActions.stateDelta]) is encoded as the
 *   literal marker [REMOVED_MARKER] and re-hydrated on read.
 * * [ByteArray] fields ([Part.thoughtSignature], [Blob.data]) are Base64-encoded strings.
 * * The sealed hierarchies [TypedData] and [PartialArgValue] are encoded as tagged maps (`{
 *   "$TYPE_KEY": <tag>, ... }`).
 * * Free-form `Any?` values ([Event.customMetadata], [EventActions.stateDelta],
 *   [FunctionCall.args], [FunctionResponse.response], [Part.opaqueData],
 *   [ToolConfirmation.payload]) are passed through recursively; only JSON-native scalars, maps, and
 *   lists are supported — any other value type throws [IllegalArgumentException] on serialization
 *   (callers must keep free-form values JSON-native).
 */

/**
 * JSON stand-in for the [State.REMOVED] sentinel so a key deletion survives the round-trip
 * ([State.REMOVED] is an opaque object that JSON can't represent). Kept equal to the sentinel's own
 * string form, so even a value that reaches the JSON serializer un-encoded yields the same token.
 */
internal const val REMOVED_MARKER: String = "__ADK_SENTINEL_REMOVED__"

private const val TYPE_KEY: String = "__type"
private const val VALUE_KEY: String = "value"

// ---------------------------------------------------------------------------
// Event
// ---------------------------------------------------------------------------

internal fun Event.toMap(): Map<String, Any?> = buildMap {
  put("id", id)
  invocationId?.let { put("invocationId", it) }
  put("author", author)
  content?.let { put("content", it.toMap()) }
  put("actions", actions.toMap())
  if (longRunningToolIds.isNotEmpty()) put("longRunningToolIds", longRunningToolIds.toList())
  if (partial) put("partial", true)
  if (turnComplete) put("turnComplete", true)
  errorCode?.let { put("errorCode", it) }
  errorMessage?.let { put("errorMessage", it) }
  finishReason?.let { put("finishReason", it.name) }
  usageMetadata?.let { put("usageMetadata", it.toMap()) }
  avgLogProbs?.let { put("avgLogProbs", it) }
  if (interrupted) put("interrupted", true)
  branch?.let { put("branch", it) }
  groundingMetadata?.let { put("groundingMetadata", it.toMap()) }
  modelVersion?.let { put("modelVersion", it) }
  citationMetadata?.let { put("citationMetadata", it.toMap()) }
  customMetadata?.let { put("customMetadata", encodeFree(it)) }
  put("timestamp", timestamp)
}

internal fun eventFromMap(map: Map<String, Any?>): Event =
  Event(
    id = map.str("id") ?: Uuid.random(),
    invocationId = map.str("invocationId"),
    author = map.str("author") ?: "",
    content = map.obj("content")?.let { contentFromMap(it) },
    actions = map.obj("actions")?.let { eventActionsFromMap(it) } ?: EventActions(),
    longRunningToolIds = map.arr("longRunningToolIds")?.map { it as String }?.toSet() ?: emptySet(),
    partial = map.bool("partial") ?: false,
    turnComplete = map.bool("turnComplete") ?: false,
    errorCode = map.str("errorCode"),
    errorMessage = map.str("errorMessage"),
    // Tolerant lookup: an unknown name (e.g. persisted by a newer version that added a reason)
    // decodes to null rather than throwing, so an old reader can still load the event.
    finishReason =
      map.str("finishReason")?.let { name -> FinishReason.entries.firstOrNull { it.name == name } },
    usageMetadata = map.obj("usageMetadata")?.let { usageMetadataFromMap(it) },
    avgLogProbs = map.dbl("avgLogProbs"),
    interrupted = map.bool("interrupted") ?: false,
    branch = map.str("branch"),
    groundingMetadata = map.obj("groundingMetadata")?.let { groundingMetadataFromMap(it) },
    modelVersion = map.str("modelVersion"),
    citationMetadata = map.obj("citationMetadata")?.let { citationMetadataFromMap(it) },
    customMetadata = map.obj("customMetadata")?.let { decodeFreeNonNullMap(it) },
    timestamp = map.long("timestamp") ?: 0L,
  )

// ---------------------------------------------------------------------------
// EventActions
// ---------------------------------------------------------------------------

private fun EventActions.toMap(): Map<String, Any?> = buildMap {
  if (skipSummarization) put("skipSummarization", true)
  if (stateDelta.isNotEmpty()) put("stateDelta", encodeFree(stateDelta))
  if (artifactDelta.isNotEmpty()) put("artifactDelta", artifactDelta.toMap())
  transferToAgent?.let { put("transferToAgent", it) }
  if (escalate) put("escalate", true)
  if (endOfAgent) put("endOfAgent", true)
  if (requestedToolConfirmations.isNotEmpty()) {
    put("requestedToolConfirmations", requestedToolConfirmations.mapValues { it.value.toMap() })
  }
  rewindBeforeInvocationId?.let { put("rewindBeforeInvocationId", it) }
  agentState?.let { put("agentState", typedDataToMap(it)) }
  compaction?.let { put("compaction", it.toMap()) }
}

private fun eventActionsFromMap(map: Map<String, Any?>): EventActions =
  EventActions(
    skipSummarization = map.bool("skipSummarization") ?: false,
    stateDelta =
      map.obj("stateDelta")?.let { decodeFreeNonNullMap(it).toMutableMap() } ?: mutableMapOf(),
    artifactDelta =
      map.obj("artifactDelta")?.mapValues { (_, v) -> (v as Number).toInt() }?.toMutableMap()
        ?: mutableMapOf(),
    transferToAgent = map.str("transferToAgent"),
    escalate = map.bool("escalate") ?: false,
    endOfAgent = map.bool("endOfAgent") ?: false,
    requestedToolConfirmations =
      map
        .obj("requestedToolConfirmations")
        ?.mapValues { (_, v) -> toolConfirmationFromMap(asMap(v)) }
        ?.toMutableMap() ?: mutableMapOf(),
    rewindBeforeInvocationId = map.str("rewindBeforeInvocationId"),
    agentState = map.obj("agentState")?.let { typedDataFromMap(it) },
    compaction = map.obj("compaction")?.let { eventCompactionFromMap(it) },
  )

// ---------------------------------------------------------------------------
// ToolConfirmation / EventCompaction
// ---------------------------------------------------------------------------

private fun ToolConfirmation.toMap(): Map<String, Any?> = buildMap {
  put("confirmed", confirmed)
  payload?.let { put("payload", encodeFree(it)) }
  hint?.let { put("hint", it) }
}

private fun toolConfirmationFromMap(map: Map<String, Any?>): ToolConfirmation =
  ToolConfirmation(
    confirmed = map.bool("confirmed") ?: false,
    payload = map["payload"]?.let { decodeFree(it) },
    hint = map.str("hint"),
  )

private fun EventCompaction.toMap(): Map<String, Any?> =
  mapOf(
    "startTimestamp" to startTimestamp,
    "endTimestamp" to endTimestamp,
    "compactedContent" to compactedContent.toMap(),
  )

private fun eventCompactionFromMap(map: Map<String, Any?>): EventCompaction =
  EventCompaction(
    startTimestamp = map.long("startTimestamp") ?: 0L,
    endTimestamp = map.long("endTimestamp") ?: 0L,
    compactedContent = map.obj("compactedContent")?.let { contentFromMap(it) } ?: Content(),
  )

// ---------------------------------------------------------------------------
// Content / Part / Blob / FileData
// ---------------------------------------------------------------------------

private fun Content.toMap(): Map<String, Any?> = buildMap {
  role?.let { put("role", it) }
  if (parts.isNotEmpty()) put("parts", parts.map { it.toMap() })
}

private fun contentFromMap(map: Map<String, Any?>): Content =
  Content(
    role = map.str("role"),
    parts = map.arr("parts")?.map { partFromMap(asMap(it)) } ?: emptyList(),
  )

private fun Part.toMap(): Map<String, Any?> = buildMap {
  text?.let { put("text", it) }
  inlineData?.let { put("inlineData", it.toMap()) }
  fileData?.let { put("fileData", it.toMap()) }
  functionCall?.let { put("functionCall", it.toMap()) }
  functionResponse?.let { put("functionResponse", it.toMap()) }
  thought?.let { put("thought", it) }
  thoughtSignature?.let { put("thoughtSignature", Base64.encode(it)) }
  opaqueData?.let { put("opaqueData", encodeFree(it)) }
}

private fun partFromMap(map: Map<String, Any?>): Part =
  Part(
    text = map.str("text"),
    inlineData = map.obj("inlineData")?.let { blobFromMap(it) },
    fileData = map.obj("fileData")?.let { fileDataFromMap(it) },
    functionCall = map.obj("functionCall")?.let { functionCallFromMap(it) },
    functionResponse = map.obj("functionResponse")?.let { functionResponseFromMap(it) },
    thought = map.bool("thought"),
    thoughtSignature = map.str("thoughtSignature")?.let { Base64.decode(it) },
    opaqueData = map["opaqueData"]?.let { decodeFree(it) },
  )

private fun Blob.toMap(): Map<String, Any?> = buildMap {
  mimeType?.let { put("mimeType", it) }
  displayName?.let { put("displayName", it) }
  data?.let { put("data", Base64.encode(it)) }
}

private fun blobFromMap(map: Map<String, Any?>): Blob =
  Blob(
    mimeType = map.str("mimeType"),
    displayName = map.str("displayName"),
    data = map.str("data")?.let { Base64.decode(it) },
  )

private fun FileData.toMap(): Map<String, Any?> = buildMap {
  mimeType?.let { put("mimeType", it) }
  displayName?.let { put("displayName", it) }
  fileUri?.let { put("fileUri", it) }
}

private fun fileDataFromMap(map: Map<String, Any?>): FileData =
  FileData(
    mimeType = map.str("mimeType"),
    displayName = map.str("displayName"),
    fileUri = map.str("fileUri"),
  )

// ---------------------------------------------------------------------------
// FunctionCall / FunctionResponse / PartialArg
// ---------------------------------------------------------------------------

private fun FunctionCall.toMap(): Map<String, Any?> = buildMap {
  put("name", name)
  if (args.isNotEmpty()) put("args", encodeFree(args))
  id?.let { put("id", it) }
  partialArgs?.let { put("partialArgs", it.map { arg -> arg.toMap() }) }
  willContinue?.let { put("willContinue", it) }
}

private fun functionCallFromMap(map: Map<String, Any?>): FunctionCall =
  FunctionCall(
    name = map.str("name") ?: "",
    args = map.obj("args")?.let { decodeFreeMap(it) } ?: emptyMap(),
    id = map.str("id"),
    partialArgs = map.arr("partialArgs")?.map { partialArgFromMap(asMap(it)) },
    willContinue = map.bool("willContinue"),
  )

private fun FunctionResponse.toMap(): Map<String, Any?> = buildMap {
  put("name", name)
  if (response.isNotEmpty()) put("response", encodeFree(response))
  id?.let { put("id", it) }
}

private fun functionResponseFromMap(map: Map<String, Any?>): FunctionResponse =
  FunctionResponse(
    name = map.str("name") ?: "",
    response = map.obj("response")?.let { decodeFreeMap(it) } ?: emptyMap(),
    id = map.str("id"),
  )

private fun PartialArg.toMap(): Map<String, Any?> = buildMap {
  value?.let { put("value", partialArgValueToMap(it)) }
  jsonPath?.let { put("jsonPath", it) }
  willContinue?.let { put("willContinue", it) }
}

private fun partialArgFromMap(map: Map<String, Any?>): PartialArg =
  PartialArg(
    value = map.obj("value")?.let { partialArgValueFromMap(it) },
    jsonPath = map.str("jsonPath"),
    willContinue = map.bool("willContinue"),
  )

private fun partialArgValueToMap(value: PartialArgValue): Map<String, Any?> =
  when (value) {
    is PartialArgValue.BoolValue -> mapOf(TYPE_KEY to "bool", VALUE_KEY to value.value)
    is PartialArgValue.NumberValue -> mapOf(TYPE_KEY to "number", VALUE_KEY to value.value)
    is PartialArgValue.StringValue -> mapOf(TYPE_KEY to "string", VALUE_KEY to value.value)
    PartialArgValue.NullValue -> mapOf(TYPE_KEY to "null")
  }

private fun partialArgValueFromMap(map: Map<String, Any?>): PartialArgValue =
  when (val type = map.str(TYPE_KEY)) {
    "bool" -> PartialArgValue.BoolValue(map.bool(VALUE_KEY) ?: false)
    "number" -> PartialArgValue.NumberValue(map.dbl(VALUE_KEY) ?: 0.0)
    "string" -> PartialArgValue.StringValue(map.str(VALUE_KEY) ?: "")
    "null" -> PartialArgValue.NullValue
    else -> throw IllegalArgumentException("Unknown PartialArgValue type: $type")
  }

// ---------------------------------------------------------------------------
// UsageMetadata / GroundingMetadata / CitationMetadata / Citation
// ---------------------------------------------------------------------------

private fun UsageMetadata.toMap(): Map<String, Any?> = buildMap {
  promptTokenCount?.let { put("promptTokenCount", it) }
  candidatesTokenCount?.let { put("candidatesTokenCount", it) }
  totalTokenCount?.let { put("totalTokenCount", it) }
}

private fun usageMetadataFromMap(map: Map<String, Any?>): UsageMetadata =
  UsageMetadata(
    promptTokenCount = map.int("promptTokenCount"),
    candidatesTokenCount = map.int("candidatesTokenCount"),
    totalTokenCount = map.int("totalTokenCount"),
  )

private fun GroundingMetadata.toMap(): Map<String, Any?> =
  mapOf("imageSearchQueries" to imageSearchQueries)

private fun groundingMetadataFromMap(map: Map<String, Any?>): GroundingMetadata =
  GroundingMetadata(
    imageSearchQueries = map.arr("imageSearchQueries")?.map { it as String } ?: emptyList()
  )

private fun CitationMetadata.toMap(): Map<String, Any?> =
  mapOf("citationSources" to citationSources.map { it.toMap() })

private fun citationMetadataFromMap(map: Map<String, Any?>): CitationMetadata =
  CitationMetadata(
    citationSources = map.arr("citationSources")?.map { citationFromMap(asMap(it)) } ?: emptyList()
  )

private fun Citation.toMap(): Map<String, Any?> = buildMap { title?.let { put("title", it) } }

private fun citationFromMap(map: Map<String, Any?>): Citation = Citation(title = map.str("title"))

// ---------------------------------------------------------------------------
// TypedData (sealed)
// ---------------------------------------------------------------------------

private fun typedDataToMap(value: TypedData): Map<String, Any?> =
  when (value) {
    TypedData.NullValue -> mapOf(TYPE_KEY to "null")
    is TypedData.IntValue -> mapOf(TYPE_KEY to "int", VALUE_KEY to value.value)
    is TypedData.LongValue -> mapOf(TYPE_KEY to "long", VALUE_KEY to value.value)
    is TypedData.StringValue -> mapOf(TYPE_KEY to "string", VALUE_KEY to value.value)
    is TypedData.BooleanValue -> mapOf(TYPE_KEY to "bool", VALUE_KEY to value.value)
    is TypedData.DoubleValue -> mapOf(TYPE_KEY to "double", VALUE_KEY to value.value)
    is TypedData.ListValue ->
      mapOf(TYPE_KEY to "list", "elements" to value.elements.map { typedDataToMap(it) })
    is TypedData.MapValue ->
      mapOf(TYPE_KEY to "map", "fields" to value.fields.mapValues { typedDataToMap(it.value) })
  }

private fun typedDataFromMap(map: Map<String, Any?>): TypedData =
  when (val type = map.str(TYPE_KEY)) {
    "null" -> TypedData.NullValue
    "int" -> TypedData.IntValue(map.int(VALUE_KEY) ?: 0)
    "long" -> TypedData.LongValue(map.long(VALUE_KEY) ?: 0L)
    "string" -> TypedData.StringValue(map.str(VALUE_KEY) ?: "")
    "bool" -> TypedData.BooleanValue(map.bool(VALUE_KEY) ?: false)
    "double" -> TypedData.DoubleValue(map.dbl(VALUE_KEY) ?: 0.0)
    "list" ->
      TypedData.ListValue(map.arr("elements")?.map { typedDataFromMap(asMap(it)) } ?: emptyList())
    "map" ->
      TypedData.MapValue(
        map.obj("fields")?.mapValues { typedDataFromMap(asMap(it.value)) } ?: emptyMap()
      )
    else -> throw IllegalArgumentException("Unknown TypedData type: $type")
  }

// ---------------------------------------------------------------------------
// Free-form value handling + typed accessors
// ---------------------------------------------------------------------------

/**
 * Recursively encodes a free-form value, substituting [State.REMOVED] and Base64-ing byte arrays.
 */
private fun encodeFree(value: Any?): Any? =
  when (value) {
    null -> null
    State.REMOVED -> REMOVED_MARKER
    is String,
    is Boolean,
    is Int,
    is Long,
    is Double -> value
    is Number -> value
    is ByteArray -> Base64.encode(value)
    is Map<*, *> -> value.entries.associate { (k, v) -> k.toString() to encodeFree(v) }
    is Collection<*> -> value.map { encodeFree(it) }
    else ->
      throw IllegalArgumentException(
        "Unsupported value type for JSON serialization: ${value::class.simpleName}"
      )
  }

/** Inverse of [encodeFree]: re-hydrates the [State.REMOVED] sentinel inside nested structures. */
private fun decodeFree(value: Any?): Any? =
  when (value) {
    null -> null
    REMOVED_MARKER -> State.REMOVED
    is Map<*, *> -> value.entries.associate { (k, v) -> k.toString() to decodeFree(v) }
    is List<*> -> value.map { decodeFree(it) }
    else -> value
  }

/** Decodes a free-form map preserving nullable values (e.g. `FunctionCall.args`). */
private fun decodeFreeMap(map: Map<String, Any?>): Map<String, Any?> =
  map.entries.associate { (k, v) -> k to decodeFree(v) }

/** Decodes a free-form map dropping null values (for `Map<String, Any>` fields). */
private fun decodeFreeNonNullMap(map: Map<String, Any?>): MutableMap<String, Any> {
  val out = mutableMapOf<String, Any>()
  for ((k, v) in map) {
    val decoded = decodeFree(v)
    if (decoded != null) out[k] = decoded
  }
  return out
}

@Suppress("UNCHECKED_CAST")
private fun asMap(value: Any?): Map<String, Any?> = value as Map<String, Any?>

private fun Map<String, Any?>.str(key: String): String? = this[key] as? String

private fun Map<String, Any?>.bool(key: String): Boolean? = this[key] as? Boolean

private fun Map<String, Any?>.int(key: String): Int? = (this[key] as? Number)?.toInt()

private fun Map<String, Any?>.long(key: String): Long? = (this[key] as? Number)?.toLong()

private fun Map<String, Any?>.dbl(key: String): Double? = (this[key] as? Number)?.toDouble()

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any?>.obj(key: String): Map<String, Any?>? = this[key] as? Map<String, Any?>

private fun Map<String, Any?>.arr(key: String): List<Any?>? = this[key] as? List<*>
