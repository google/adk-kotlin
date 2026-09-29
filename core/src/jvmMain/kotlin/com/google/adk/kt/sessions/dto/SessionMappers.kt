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

package com.google.adk.kt.sessions.dto

import com.google.adk.kt.agents.TypedData
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.events.Event
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.events.EventCompaction
import com.google.adk.kt.events.ToolConfirmation
import com.google.adk.kt.logging.LoggerFactory
import com.google.adk.kt.models.CacheMetadata
import com.google.adk.kt.serialization.adkJson
import com.google.adk.kt.serialization.anyToJsonElement
import com.google.adk.kt.serialization.jsonElementToAny
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.sessions.State
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.GroundingMetadata
import com.google.adk.kt.types.UsageMetadata
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.floor
import kotlin.math.round
import kotlin.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

// customMetadata keys under which ADK Python stores fields the API does not keep.
private const val USAGE_METADATA_KEY = "_usage_metadata"
private const val COMPACTION_KEY = "_compaction"

private val logger = LoggerFactory.getLogger(SessionEventDto::class)

/** Mappers between the wire-level DTOs in this package and the ADK domain types. */
@OptIn(FrameworkInternalApi::class)
internal fun Event.toDto(): SessionEventDto {
  val metadata =
    EventMetadataDto(
      partial = partial,
      turnComplete = turnComplete,
      interrupted = interrupted,
      branch = branch,
      longRunningToolIds = longRunningToolIds.takeIf { it.isNotEmpty() }?.toList(),
      groundingMetadata = groundingMetadata?.let { adkJson.encodeToJsonElement(it) },
      customMetadata = storedCustomMetadata(),
    )
  // The API does not store the other actions fields; rawEvent carries all of them but route.
  val actionsDto =
    EventActionsDto(
      skipSummarization = actions.skipSummarization.takeIf { it },
      stateDelta = stateDeltaToDto(actions.stateDelta),
      artifactDelta = actions.artifactDelta.takeIf { it.isNotEmpty() }?.toMap(),
      transferAgent = actions.transferToAgent,
      escalate = actions.escalate.takeIf { it },
    )
  return SessionEventDto(
    author = author,
    invocationId = invocationId,
    timestamp = TimestampDto.fromEpochMillis(timestamp),
    errorCode = errorCode,
    errorMessage = errorMessage,
    content = content?.let { encodeContentToWire(it) },
    actions = actionsDto,
    eventMetadata = metadata,
    rawEvent = toRawEvent(),
  )
}

/**
 * Returns the `customMetadata` Struct: the event's own entries plus usage metadata and compaction
 * under ADK Python's keys, or null when there is nothing to store.
 */
@OptIn(FrameworkInternalApi::class)
private fun Event.storedCustomMetadata(): JsonObject? =
  buildJsonObject {
      customMetadata?.forEach { (key, value) -> put(key, anyToJsonElement(value)) }
      if (usageMetadata != null) {
        put(USAGE_METADATA_KEY, adkJson.encodeToJsonElement(usageMetadata))
      }
      actions.compaction?.let { put(COMPACTION_KEY, it.toStoredJson()) }
    }
    .takeIf { it.isNotEmpty() }

/** Serializes the event in the shape ADK Python writes to and reads from `rawEvent`. */
@OptIn(FrameworkInternalApi::class)
private fun Event.toRawEvent(): JsonObject {
  // Leave out an output that is not JSON-native rather than fail the append.
  val storableOutput =
    if (output == null) null else convertOrNull("output") { anyToJsonElement(output) }
  val fields = adkJson.encodeToJsonElement(copy(output = storableOutput)).jsonObject.toMutableMap()
  fields["timestamp"] = JsonPrimitive(timestamp / 1000.0)
  // ADK Python strips partMetadata from rawEvent too.
  fields["content"]?.let { fields["content"] = stripUnsupportedPartFields(it) }
  if (cacheMetadata != null) fields["cacheMetadata"] = cacheMetadata.toStoredJson()
  fields["citationMetadata"]?.let {
    fields["citationMetadata"] = it.renameKey(from = "citationSources", to = "citations")
  }
  val encodedActions = fields["actions"] as? JsonObject
  if (encodedActions != null) {
    val rawActions = encodedActions.toMutableMap()
    // ADK Python 1.x rejects unknown EventActions keys.
    rawActions.remove("route")
    // ADK Python reads JSON null, not this library's sentinel, as a removed key.
    if (actions.stateDelta.isNotEmpty()) {
      rawActions["stateDelta"] = stateDeltaToDto(actions.stateDelta)
    }
    actions.compaction?.let { rawActions["compaction"] = it.toStoredJson() }
    fields["actions"] = JsonObject(rawActions)
  }
  return JsonObject(fields)
}

/**
 * Serializes cache metadata with the snake_case keys and epoch-second times ADK Python requires.
 */
private fun CacheMetadata.toStoredJson(): JsonObject = buildJsonObject {
  put("fingerprint", fingerprint)
  put("contents_count", contentsCount)
  if (cacheName != null) put("cache_name", cacheName)
  if (expireTime != null) put("expire_time", expireTime / 1000.0)
  if (invocationsUsed != null) put("invocations_used", invocationsUsed)
  if (createdAt != null) put("created_at", createdAt / 1000.0)
}

/** Serializes a compaction with the epoch-second timestamps ADK Python uses. */
@OptIn(FrameworkInternalApi::class)
private fun EventCompaction.toStoredJson(): JsonObject = buildJsonObject {
  put("startTimestamp", startTimestamp / 1000.0)
  put("endTimestamp", endTimestamp / 1000.0)
  put("compactedContent", adkJson.encodeToJsonElement(compactedContent))
}

/**
 * Converts an API event into an [Event], from `rawEvent` when it is readable and from the typed
 * fields otherwise.
 */
internal fun SessionEventDto.toAdk(): Event {
  val fromRaw =
    if (rawEvent.isNullOrEmpty()) null else convertOrNull("rawEvent") { fromRawEvent(rawEvent) }
  return fromRaw ?: fromTypedFields()
}

@OptIn(FrameworkInternalApi::class)
private fun SessionEventDto.fromRawEvent(raw: JsonObject): Event {
  val fields = raw.toMutableMap()
  fields["invocationId"] = JsonPrimitive(invocationId)
  fields["author"] = JsonPrimitive(author ?: "")
  // As in ADK Python, the envelope timestamp wins over rawEvent's.
  fields.remove("timestamp")
  // As in ADK Python 2.x, keep the stored id so a reloaded event matches the streamed one.
  if ((fields["id"] as? JsonPrimitive)?.contentOrNull.isNullOrEmpty()) {
    fields["id"] = JsonPrimitive(eventIdFromName())
  }
  fields["citationMetadata"]?.let {
    fields["citationMetadata"] = it.renameKey(from = "citations", to = "citationSources")
  }
  val rawActions = fields["actions"] as? JsonObject
  if (rawActions != null) {
    // Decoded below: JSON null marks a removed key, and ADK Python's agentState is a plain object.
    val decodable = rawActions.toMutableMap()
    decodable.remove("stateDelta")
    decodable.remove("agentState")
    decodable["compaction"]?.let { decodable["compaction"] = it.withFlooredTimestamps() }
    fields["actions"] = JsonObject(decodable)
  }
  val event =
    adkJson
      .decodeFromJsonElement<Event>(JsonObject(fields))
      .copy(timestamp = timestamp?.toEpochMillis() ?: 0L)
  rawActions?.get("stateDelta")?.putStateDeltaInto(event.actions.stateDelta)
  event.actions.agentState = rawActions?.get("agentState")?.let { decodeAgentState(it) }
  return event
}

@OptIn(FrameworkInternalApi::class)
private fun SessionEventDto.fromTypedFields(): Event {
  val metadata = eventMetadata
  val storedCustomMetadata = metadata?.customMetadata as? JsonObject
  val eventActions = actions?.toAdk() ?: EventActions()
  // The API drops these typed fields; an unreadable rawEvent may still carry them.
  val rawActions = rawEvent?.get("actions") as? JsonObject
  eventActions.endOfAgent = (rawActions?.get("endOfAgent") as? JsonPrimitive)?.booleanOrNull == true
  eventActions.agentState = rawActions?.get("agentState")?.let { decodeAgentState(it) }
  val confirmations =
    rawActions?.get("requestedToolConfirmations")?.let { json ->
      convertOrNull("requestedToolConfirmations") {
        adkJson.decodeFromJsonElement<Map<String, ToolConfirmation?>>(json)
      }
    }
  // Skip a null entry rather than lose the whole map.
  for ((callId, confirmation) in confirmations.orEmpty()) {
    if (confirmation != null) eventActions.requestedToolConfirmations[callId] = confirmation
  }
  eventActions.compaction =
    storedCustomMetadata?.get(COMPACTION_KEY)?.let { json ->
      convertOrNull("compaction") {
        adkJson.decodeFromJsonElement<EventCompaction>(json.withFlooredTimestamps())
      }
    }
  return Event(
    id = eventIdFromName(),
    invocationId = invocationId,
    author = author ?: "",
    content = content?.let { decodeContentFromWire(it) },
    actions = eventActions,
    longRunningToolIds = metadata?.longRunningToolIds?.toSet() ?: emptySet(),
    partial = metadata?.partial ?: false,
    turnComplete = metadata?.turnComplete ?: false,
    errorCode = errorCode,
    errorMessage = errorMessage,
    interrupted = metadata?.interrupted ?: false,
    branch = metadata?.branch,
    groundingMetadata =
      metadata?.groundingMetadata?.let { adkJson.decodeFromJsonElement<GroundingMetadata>(it) },
    usageMetadata =
      storedCustomMetadata?.get(USAGE_METADATA_KEY)?.let { json ->
        convertOrNull("usageMetadata") { adkJson.decodeFromJsonElement<UsageMetadata>(json) }
      },
    customMetadata =
      storedCustomMetadata
        ?.filterKeys { it != USAGE_METADATA_KEY && it != COMPACTION_KEY }
        ?.takeIf { it.isNotEmpty() }
        ?.mapValues { (_, value) -> jsonElementToAny(value) },
    timestamp = timestamp?.toEpochMillis() ?: 0L,
  )
}

private fun SessionEventDto.eventIdFromName(): String = name?.substringAfterLast('/') ?: ""

/** Decodes a stored agentState, or returns null for ADK Python's plain-object form. */
@OptIn(FrameworkInternalApi::class)
private fun decodeAgentState(json: JsonElement): TypedData? =
  convertOrNull("agentState") { adkJson.decodeFromJsonElement<TypedData>(json) }

/**
 * Converts a compaction's epoch-second timestamps the way an ADK Python event timestamp reaches
 * this library: the fraction is rounded half-even to micros, as CPython's `datetime.fromtimestamp`
 * does, then floored to millis. The first compacted event then stays in range.
 */
private fun JsonElement.withFlooredTimestamps(): JsonElement {
  val json = this as? JsonObject ?: return this
  return JsonObject(
    json.mapValues { (key, value) ->
      val seconds = (value as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
      if (key in COMPACTION_TIMESTAMP_KEYS && seconds != null) {
        val wholeSeconds = floor(seconds)
        val micros = round((seconds - wholeSeconds) * 1_000_000).toLong()
        val flooredMillis = wholeSeconds.toLong() * 1000 + micros.floorDiv(1000L)
        // Emit seconds: the lenient decoder would read small millis as seconds.
        JsonPrimitive(flooredMillis / 1000.0)
      } else {
        value
      }
    }
  )
}

private val COMPACTION_TIMESTAMP_KEYS =
  setOf("startTimestamp", "endTimestamp", "start_timestamp", "end_timestamp")

/**
 * Returns this object with key [from] renamed to [to], or this element unchanged when it is not an
 * object or has no [from] key.
 */
private fun JsonElement.renameKey(from: String, to: String): JsonElement {
  val json = this as? JsonObject ?: return this
  val value = json[from] ?: return this
  return JsonObject(json - from + Pair(to, value))
}

/**
 * Runs [convert], or logs the failure's type and returns null so that one bad value cannot fail a
 * whole session load or append. The log omits the exception message, which can quote user data.
 */
private inline fun <T> convertOrNull(what: String, convert: () -> T): T? =
  try {
    convert()
  } catch (e: CancellationException) {
    throw e
  } catch (e: IllegalArgumentException) {
    // SerializationException is an IllegalArgumentException.
    logger.warn { "Ignoring $what that cannot be converted (${e::class.simpleName})." }
    null
  } catch (e: IllegalStateException) {
    // AnySerializer throws this for a JSON null where a non-null value is required.
    logger.warn { "Ignoring $what that cannot be converted (${e::class.simpleName})." }
    null
  }

/**
 * Serializes [content] to the Vertex wire JSON. The wire is proto3-JSON, where a `bytes` field is a
 * base64 string, which is what the domain [Content] now serializes to directly.
 *
 * `Part.partMetadata` is stripped: it is an ADK-only field with no counterpart in the Vertex
 * `Content.Part`, so the strict proto-JSON parser rejects it (400 INVALID_ARGUMENT). It is dropped
 * from this wire only - the domain type keeps it for local persistence - so it does not round-trip
 * through the Vertex backend.
 */
@OptIn(FrameworkInternalApi::class)
private fun encodeContentToWire(content: Content): JsonElement =
  stripUnsupportedPartFields(adkJson.encodeToJsonElement(content))

/** Inverse of [encodeContentToWire]: decodes Vertex wire JSON back to [Content]. */
@OptIn(FrameworkInternalApi::class)
private fun decodeContentFromWire(element: JsonElement): Content =
  adkJson.decodeFromJsonElement<Content>(element)

/**
 * Removes ADK-only part fields the Vertex `Content.Part` does not define. `Part.partMetadata` has
 * no counterpart in the Vertex proto, so the strict proto-JSON parser rejects it; it is dropped
 * from the Vertex wire only.
 */
private fun stripUnsupportedPartFields(content: JsonElement): JsonElement =
  mapParts(content) { part ->
    if ("partMetadata" !in part) {
      part
    } else {
      JsonObject(part.toMutableMap().apply { remove("partMetadata") })
    }
  }

/**
 * Applies [transform] to each `parts[*]` object, passing through non-object entries and content.
 */
private fun mapParts(content: JsonElement, transform: (JsonObject) -> JsonObject): JsonElement {
  val obj = content as? JsonObject ?: return content
  val parts = obj["parts"] as? JsonArray ?: return content
  val mapped = parts.map { (it as? JsonObject)?.let(transform) ?: it }
  return JsonObject(obj.toMutableMap().apply { this["parts"] = JsonArray(mapped) })
}

@OptIn(FrameworkInternalApi::class)
internal fun SessionDto.toAdk(appName: String, userId: String, fallbackId: String?): Session {
  val sessionId =
    name?.substringAfterLast('/')
      ?: fallbackId
      ?: error("Session response is missing a name and no fallback session id was provided.")
  val lastUpdateTime =
    updateTime?.let { Instant.fromEpochMilliseconds(java.time.Instant.parse(it).toEpochMilli()) }
      ?: Instant.fromEpochMilliseconds(0)
  val initialState: Map<String, Any> =
    (sessionState as? JsonObject)?.let { jsonObj ->
      jsonObj.mapValues { (_, v) -> jsonElementToAny(v) ?: State.REMOVED }
    } ?: emptyMap()
  return Session(
    key = SessionKey(appName, userId, sessionId),
    state = State(initialState),
    lastUpdateTime = lastUpdateTime,
  )
}

@OptIn(FrameworkInternalApi::class)
private fun EventActionsDto.toAdk(): EventActions {
  val actions = EventActions()
  actions.skipSummarization = skipSummarization ?: false
  stateDelta?.putStateDeltaInto(actions.stateDelta)
  artifactDelta?.let { actions.artifactDelta.putAll(it) }
  actions.transferToAgent = transferAgent ?: transferToAgent
  actions.escalate = escalate ?: false
  return actions
}

/**
 * Serializes the in-memory state delta into a [JsonObject] where [State.REMOVED] is emitted as JSON
 * `null` (matches the Java ADK).
 */
@OptIn(FrameworkInternalApi::class)
private fun stateDeltaToDto(stateDelta: Map<String, Any>): JsonElement {
  val entries = stateDelta.mapValues { (_, value) ->
    if (value === State.REMOVED) JsonNull else anyToJsonElement(value)
  }
  return JsonObject(entries)
}

/** Inverse of [stateDeltaToDto]: adds each entry to [target], JSON `null` as [State.REMOVED]. */
@OptIn(FrameworkInternalApi::class)
private fun JsonElement.putStateDeltaInto(target: MutableMap<String, Any>) {
  (this as? JsonObject)?.forEach { (key, value) ->
    target[key] =
      if (value is JsonNull) State.REMOVED else (jsonElementToAny(value) ?: State.REMOVED)
  }
}
