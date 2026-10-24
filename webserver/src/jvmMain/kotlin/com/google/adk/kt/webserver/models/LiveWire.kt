/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:OptIn(ExperimentalLiveApi::class)

package com.google.adk.kt.webserver.models

import com.google.adk.kt.agents.LiveRequest
import com.google.adk.kt.annotations.ExperimentalLiveApi
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.events.Event
import com.google.adk.kt.models.ContentInput
import com.google.adk.kt.models.RealtimeInput
import com.google.adk.kt.serialization.adkJson
import com.google.adk.kt.sessions.State
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.Content
import java.lang.invoke.MethodHandles
import kotlinx.serialization.Contextual
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass())

/**
 * One frame sent by a live client, in the shape clients put on the wire.
 *
 * Field names are snake_case, as ADK Python reads them. The camelCase spelling, which ADK Go uses
 * for activity signals, is accepted too; snake_case wins when a frame has both.
 */
@Serializable
private data class LiveRequestFrame(
  val content: Content? = null,
  val blob: Blob? = null,
  @SerialName("activity_start")
  @JsonNames("activityStart")
  val activityStart: LiveActivitySignal? = null,
  @SerialName("activity_end") @JsonNames("activityEnd") val activityEnd: LiveActivitySignal? = null,
  @SerialName("audio_stream_end") @JsonNames("audioStreamEnd") val audioStreamEnd: Boolean = false,
  val close: Boolean = false,
  val partial: Boolean = false,
  @SerialName("state_delta")
  @JsonNames("stateDelta")
  val stateDelta: Map<String, @Contextual Any>? = null,
)

/** The empty object a client sends to mark an activity boundary; its presence is the signal. */
@Serializable private data object LiveActivitySignal

/**
 * Decodes one text frame from a live client into the [LiveRequest]s it asks for, in ADK Python's
 * order.
 *
 * A close is one request that keeps the `state_delta`; otherwise the frame's one realtime input
 * (activity start, activity end, end of audio, then media) goes before its content, and the
 * `state_delta` goes where ADK Python applies it. A top-level null `state_delta` value removes that
 * key, as Kotlin's rewind does for ADK Python's `None`.
 *
 * @throws IllegalArgumentException if the frame is not valid JSON for a live request, its blob data
 *   is not base64 text, or its content violates a [ContentInput] check.
 */
@OptIn(FrameworkInternalApi::class)
internal fun decodeLiveRequests(text: String): List<LiveRequest> {
  // The decoder recurses per level, so a deep enough frame would overflow the stack.
  if (nestsDeeperThan(text, MAX_FRAME_DEPTH)) throw SerializationException("Malformed live request")
  val element = adkJson.parseToJsonElement(text)
  if (element !is JsonObject) throw SerializationException("Malformed live request")
  // Core's decoder also reads the number arrays old sessions stored; the blob takes base64 only.
  val data = (element["blob"] as? JsonObject)?.get("data")
  if (data != null && data !is JsonPrimitive) throw SerializationException("Malformed live request")
  // The key the decoder reads: snake_case wins, as @JsonNames resolves it.
  val deltaKey = if ("state_delta" in element) "state_delta" else "stateDelta"
  val delta = element[deltaKey] as? JsonObject
  // A null value (the one primitive without content) removes its key; state cannot hold null.
  val removedKeys =
    delta?.filterValues { it is JsonPrimitive && it.contentOrNull == null }?.keys.orEmpty()
  val decodable =
    if (delta == null || removedKeys.isEmpty()) element
    else JsonObject(element + (deltaKey to JsonObject(delta - removedKeys)))
  return adkJson
    .decodeFromJsonElement(LiveRequestFrame.serializer(), decodable)
    .toLiveRequests(removedKeys)
}

/** The deepest nesting a frame may have, ADK Python's JSON parser limit. */
private const val MAX_FRAME_DEPTH = 200

/** Whether the JSON in [text] has objects or arrays open [limit] + 1 deep, ignoring strings. */
private fun nestsDeeperThan(text: String, limit: Int): Boolean {
  var depth = 0
  var inString = false
  var escaped = false
  for (c in text) {
    when {
      escaped -> escaped = false
      inString && c == '\\' -> escaped = true
      c == '"' -> inString = !inString
      inString -> {}
      c == '{' || c == '[' -> if (++depth > limit) return true
      c == '}' || c == ']' -> depth--
    }
  }
  return false
}

private fun LiveRequestFrame.toLiveRequests(removedKeys: Set<String>): List<LiveRequest> {
  val delta = stateDelta.orEmpty() + removedKeys.associateWith { State.REMOVED }
  if (close) return listOf(LiveRequest(close = true, stateDelta = delta))
  val realtime =
    when {
      activityStart != null -> RealtimeInput.ActivityStart
      activityEnd != null -> RealtimeInput.ActivityEnd
      audioStreamEnd -> RealtimeInput.AudioStreamEnd
      blob != null -> blob.toRealtimeInput()
      else -> null
    }
  // ContentInput rejects `partial` on function responses, so it is masked off them.
  val contentInput = content?.let {
    ContentInput(it, partial && it.parts.none { part -> part.functionResponse != null })
  }
  if (realtime == null || contentInput == null) {
    return listOf(LiveRequest(input = realtime ?: contentInput, stateDelta = delta))
  }
  // As in ADK Python, the delta rides on the content only when it becomes a user event.
  val contentRecordsDelta =
    !partial && contentInput.content.parts.none { it.functionResponse != null }
  return if (contentRecordsDelta) {
    listOf(LiveRequest(input = realtime), LiveRequest(input = contentInput, stateDelta = delta))
  } else {
    listOf(LiveRequest(input = realtime, stateDelta = delta), LiveRequest(input = contentInput))
  }
}

/**
 * Encodes one agent event as the single JSON text frame a live client expects.
 *
 * Inline media is encoded as base64 so browser clients can decode it.
 */
@OptIn(FrameworkInternalApi::class)
internal fun encodeLiveEvent(event: Event): String = adkJson.encodeToString(event)

/**
 * Audio and image media as realtime input; any other blob is dropped with a warning, as ADK Python
 * does for Gemini 3 live models, since the Kotlin SDK has no untyped media field.
 */
private fun Blob.toRealtimeInput(): RealtimeInput? =
  when {
    mimeType?.startsWith("audio/") == true -> RealtimeInput.Audio(this)
    mimeType?.startsWith("image/") == true -> RealtimeInput.Video(this)
    else -> {
      logger.warn("A live blob frame was dropped: only audio and image media are sent.")
      null
    }
  }
