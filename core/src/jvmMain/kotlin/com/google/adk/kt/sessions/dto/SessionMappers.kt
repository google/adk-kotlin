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

import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.events.Event
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.serialization.adkJson
import com.google.adk.kt.serialization.anyToJsonElement
import com.google.adk.kt.serialization.jsonElementToAny
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.sessions.State
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.GroundingMetadata
import com.google.adk.kt.types.UsageMetadata
import kotlin.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

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
      usageMetadata = usageMetadata?.let { adkJson.encodeToJsonElement(it) },
      customMetadata = customMetadata?.let { customMetadataToDto(it) },
    )
  val actionsDto =
    EventActionsDto(
      skipSummarization = actions.skipSummarization.takeIf { it },
      stateDelta = stateDeltaToDto(actions.stateDelta),
      artifactDelta = actions.artifactDelta.takeIf { it.isNotEmpty() }?.toMap(),
      transferAgent = actions.transferToAgent,
      escalate = actions.escalate.takeIf { it },
      endOfAgent = actions.endOfAgent.takeIf { it },
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
  )
}

@OptIn(FrameworkInternalApi::class)
internal fun SessionEventDto.toAdk(): Event {
  val id = name?.substringAfterLast('/') ?: ""
  val metadata = eventMetadata
  return Event(
    id = id,
    invocationId = invocationId,
    author = author ?: "",
    content = content?.let { decodeContentFromWire(it) },
    actions = actions?.toAdk() ?: EventActions(),
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
      metadata?.usageMetadata?.let { adkJson.decodeFromJsonElement<UsageMetadata>(it) },
    customMetadata = metadata?.customMetadata?.let { customMetadataFromDto(it) },
    timestamp = timestamp?.toEpochMillis() ?: 0L,
  )
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
  (stateDelta as? JsonObject)?.let { delta ->
    for ((key, value) in delta) {
      actions.stateDelta[key] =
        if (value is JsonNull) State.REMOVED else (jsonElementToAny(value) ?: State.REMOVED)
    }
  }
  artifactDelta?.let { actions.artifactDelta.putAll(it) }
  actions.transferToAgent = transferAgent ?: transferToAgent
  actions.escalate = escalate ?: false
  actions.endOfAgent = endOfAgent ?: false
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

/**
 * Serializes the free-form event custom metadata into the `google.protobuf.Struct` carried by
 * `EventMetadata.custom_metadata`.
 */
@OptIn(FrameworkInternalApi::class)
private fun customMetadataToDto(customMetadata: Map<String, Any?>): JsonElement =
  JsonObject(customMetadata.mapValues { (_, value) -> anyToJsonElement(value) })

/**
 * Reads back the `google.protobuf.Struct` written by [customMetadataToDto]. Entries whose value is
 * JSON `null` round-trip as `null`, matching [Event.customMetadata].
 */
@OptIn(FrameworkInternalApi::class)
private fun customMetadataFromDto(customMetadata: JsonElement): Map<String, Any?>? =
  (customMetadata as? JsonObject)?.let { obj ->
    buildMap {
      for ((key, value) in obj) {
        put(key, jsonElementToAny(value))
      }
    }
  }
