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
import com.google.adk.kt.webserver.redactedJson
import java.lang.invoke.MethodHandles
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass())

/**
 * One frame sent by a live client, in the shape clients put on the wire.
 *
 * Field names are camelCase; the snake_case spelling is accepted too, and camelCase wins when a
 * frame has both.
 */
@Serializable
private data class LiveRequestFrame(
  val content: Content? = null,
  val blob: Blob? = null,
  @JsonNames("activity_start") val activityStart: LiveActivitySignal? = null,
  @JsonNames("activity_end") val activityEnd: LiveActivitySignal? = null,
  @JsonNames("audio_stream_end") val audioStreamEnd: Boolean = false,
  val close: Boolean = false,
  val partial: Boolean = false,
  @JsonNames("state_delta") val stateDelta: Map<String, @Contextual Any?> = emptyMap(),
)

/** The empty object a client sends to mark an activity boundary; its presence is the signal. */
@Serializable private data object LiveActivitySignal

/**
 * Decodes one text frame from a live client into the [LiveRequest]s it asks for, in the order to
 * send them.
 *
 * A close is one request that keeps the `state_delta`. Otherwise the frame's one realtime input
 * (activity start, activity end, end of audio, then media) goes before its content, and the
 * `state_delta` rides on the content when it becomes a user event, else on the first request.
 *
 * @throws SerializationException if the frame is not valid JSON for a live request, nests deeper
 *   than 200 levels, or carries blob or inline data that is not base64 text.
 * @throws IllegalArgumentException if the frame's content violates a [ContentInput] check.
 */
internal fun decodeLiveRequests(text: String): List<LiveRequest> {
  // The decoder recurses per level, so a deep enough frame would overflow the stack.
  if (nestsDeeperThan(text, MAX_FRAME_DEPTH)) throw SerializationException("Malformed live request")
  val frame =
    try {
      decodeFrame(text)
    } catch (_: IllegalArgumentException) {
      // Decoding errors quote the frame or carry it in their cause, and the frame is user content.
      throw SerializationException("Malformed live request")
    }
  return frame.toLiveRequests()
}

@OptIn(FrameworkInternalApi::class)
private fun decodeFrame(text: String): LiveRequestFrame {
  val element = adkJson.parseToJsonElement(text)
  if (element !is JsonObject) throw SerializationException("Malformed live request")
  // Core reads the number arrays old sessions stored; blob and inline data take base64 only.
  if (element.carriesNumberArrayBytes()) throw SerializationException("Malformed live request")
  // adkJson won't coerce a null delta to the empty default, so drop both spellings if it's null.
  val deltaKey = if ("stateDelta" in element) "stateDelta" else "state_delta"
  val decodable =
    if (element[deltaKey] is JsonNull) JsonObject(element - "stateDelta" - "state_delta")
    else element
  return adkJson.decodeFromJsonElement(LiveRequestFrame.serializer(), decodable)
}

/** Whether the frame's blob, or the inline data of a content part, holds bytes as a JSON array. */
private fun JsonObject.carriesNumberArrayBytes(): Boolean {
  val parts = ((this["content"] as? JsonObject)?.get("parts") as? JsonArray).orEmpty()
  val blobs =
    listOf(this["blob"]) +
      parts.flatMap { part ->
        listOf("inlineData", "inline_data").map { (part as? JsonObject)?.get(it) }
      }
  return blobs.any { blob ->
    val data = (blob as? JsonObject)?.get("data")
    data != null && data !is JsonPrimitive
  }
}

/** The deepest nesting a frame may have; the decoder recurses once per level. */
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

private fun LiveRequestFrame.toLiveRequests(): List<LiveRequest> {
  // A top-level null value removes its key, since session state cannot hold null.
  val delta = stateDelta.mapValues { (_, value) -> value ?: State.REMOVED }
  if (close) return listOf(LiveRequest(close = true, stateDelta = delta))
  val realtime =
    when {
      activityStart != null -> RealtimeInput.ActivityStart
      activityEnd != null -> RealtimeInput.ActivityEnd
      audioStreamEnd -> RealtimeInput.AudioStreamEnd
      blob != null -> blob.toRealtimeInput()
      else -> null
    }
  val answersTools = content?.parts?.any { it.functionResponse != null } == true
  // ContentInput rejects `partial` on function responses, so it is masked off them.
  val contentInput = if (content != null) ContentInput(content, partial && !answersTools) else null
  if (realtime == null || contentInput == null) {
    return listOf(LiveRequest(input = realtime ?: contentInput, stateDelta = delta))
  }
  // The delta rides on the content only when the content becomes a user event.
  val contentRecordsDelta = !partial && !answersTools
  return if (contentRecordsDelta) {
    listOf(LiveRequest(input = realtime), LiveRequest(input = contentInput, stateDelta = delta))
  } else {
    listOf(LiveRequest(input = realtime, stateDelta = delta), LiveRequest(input = contentInput))
  }
}

/**
 * Encodes one agent event, without credential secrets, as the single JSON text frame a live client
 * expects.
 *
 * Inline media is encoded as base64 so browser clients can decode it.
 */
internal fun encodeLiveEvent(event: Event): String = redactedJson(event).toString()

/**
 * Audio and image media as realtime input; any other blob is dropped with a warning, since the
 * Kotlin SDK has no untyped media field.
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
