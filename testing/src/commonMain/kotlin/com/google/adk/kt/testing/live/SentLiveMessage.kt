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
@file:OptIn(ExperimentalLiveApi::class)

package com.google.adk.kt.testing.live

import com.google.adk.kt.annotations.ExperimentalLiveApi
import com.google.adk.kt.models.RealtimeInput
import com.google.adk.kt.types.Content

/**
 * Something the code under test sent through a [FakeLiveConnection].
 *
 * One ordered list holds every kind, because the interesting assertions are about order *across*
 * kinds - that a tool response went out after the audio that provoked it, and before the connection
 * closed.
 */
@ExperimentalLiveApi
sealed interface SentLiveMessage {
  /**
   * A `sendHistory` call, recorded as the caller passed it; a real connection drops audio and
   * replay-only artifact parts before sending.
   */
  data class History(val history: List<Content>) : SentLiveMessage

  /**
   * A `sendContent` call.
   *
   * A tool response arrives this way too: the live connection has no `sendToolResponse`, so a
   * response is a content whose parts are all function responses.
   */
  data class ClientContent(val content: Content, val partial: Boolean = false) : SentLiveMessage

  /**
   * Records a `sendRealtime` call with its full [RealtimeInput] subtype rather than only a media
   * blob.
   *
   * Preserving the subtype lets tests assert on control signals like [RealtimeInput.ActivityStart],
   * [RealtimeInput.ActivityEnd], and [RealtimeInput.AudioStreamEnd] alongside audio and video
   * blobs.
   */
  data class Realtime(val input: RealtimeInput) : SentLiveMessage

  /**
   * A `closeSession` call (`close` delegates to it), in the same list so a test can assert what
   * preceded it.
   */
  data object Closed : SentLiveMessage
}

/** True when this is a tool response: a content whose every part is a function response. */
internal val SentLiveMessage.isToolResponse: Boolean
  get() =
    this is SentLiveMessage.ClientContent &&
      content.parts.isNotEmpty() &&
      content.parts.all { it.functionResponse != null }

/**
 * True when any part of this message answers call [callId] to [toolName] with a non-empty result,
 * so a content carrying several parallel answers still matches.
 *
 * Checks the name and id the server correlates on, because a mismatched answer is silently
 * mis-associated or dropped rather than reported. The result is checked only for presence so
 * payloads stay out of failure messages, and a test that cares about the value asserts it itself.
 */
@ExperimentalLiveApi
fun SentLiveMessage.isToolResponseFor(toolName: String, callId: String): Boolean =
  this is SentLiveMessage.ClientContent &&
    content.parts.any { part ->
      val response = part.functionResponse
      response != null &&
        response.name == toolName &&
        response.id == callId &&
        response.response.isNotEmpty()
    }

/**
 * Describes [message] without its payload: kinds, counts, sizes and mime types, plus function names
 * and call ids, but never text, media bytes or tool results.
 *
 * Assertion messages must not echo model or user content, so this reports that a content had two
 * parts rather than what they said.
 */
internal fun describeShape(message: SentLiveMessage): String =
  when (message) {
    is SentLiveMessage.History -> "History(contents=${message.history.size})"
    is SentLiveMessage.ClientContent -> {
      val parts = message.content.parts
      val functionResponses = parts.mapNotNull { it.functionResponse }
      // Names and ids are safe and make correlation failures readable; results show only presence.
      val answered = functionResponses.joinToString {
        "${it.name}#${it.id ?: "no-id"}${if (it.response.isEmpty()) " empty" else ""}"
      }
      "ClientContent(parts=${parts.size}, functionResponses=${functionResponses.size}" +
        (if (answered.isEmpty()) "" else ", answered=[$answered]") +
        (if (message.partial) ", partial)" else ")")
    }
    is SentLiveMessage.Realtime -> "Realtime(${describeShape(message.input)})"
    SentLiveMessage.Closed -> "Closed"
  }

private fun describeShape(input: RealtimeInput): String =
  when (input) {
    is RealtimeInput.Audio ->
      "Audio, mimeType=${input.blob.mimeType}, bytes=${input.blob.data?.size ?: 0}"
    is RealtimeInput.Video ->
      "Video, mimeType=${input.blob.mimeType}, bytes=${input.blob.data?.size ?: 0}"
    RealtimeInput.ActivityStart -> "ActivityStart"
    RealtimeInput.ActivityEnd -> "ActivityEnd"
    RealtimeInput.AudioStreamEnd -> "AudioStreamEnd"
  }
