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
package com.google.adk.kt.testing

import com.google.adk.kt.events.Event
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.events.EventCompaction
import com.google.adk.kt.events.ToolConfirmation
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.UsageMetadata

/** A `user`-authored [Event] carrying a single text part. */
fun userEvent(text: String, timestamp: Long = 0L, invocationId: String? = null): Event =
  Event(
    author = Role.USER,
    invocationId = invocationId,
    content = userMessage(text),
    timestamp = timestamp,
  )

/** A `model`-authored [Event] carrying a single text part. */
fun modelEvent(text: String, timestamp: Long = 0L, invocationId: String? = null): Event =
  Event(
    author = Role.MODEL,
    invocationId = invocationId,
    content = modelMessage(text),
    timestamp = timestamp,
  )

/** A `model`-authored [Event] reporting [promptTokenCount] in its [Event.usageMetadata]. */
fun modelEventWithPromptTokens(
  promptTokenCount: Int,
  text: String = "ok",
  timestamp: Long = 0L,
  invocationId: String? = null,
): Event =
  Event(
    author = Role.MODEL,
    invocationId = invocationId,
    content = modelMessage(text),
    usageMetadata = UsageMetadata(promptTokenCount = promptTokenCount),
    timestamp = timestamp,
  )

/**
 * A `model`-authored [Event] carrying a single [FunctionCall].
 *
 * Set [longRunning] to mark [callId] as a long-running call via [Event.longRunningToolIds].
 */
fun eventWithFunctionCall(
  invocationId: String,
  timestamp: Long,
  callName: String,
  callId: String,
  longRunning: Boolean = false,
): Event =
  Event(
    author = Role.MODEL,
    invocationId = invocationId,
    content =
      Content(
        role = Role.MODEL,
        parts = listOf(Part(functionCall = FunctionCall(name = callName, id = callId))),
      ),
    longRunningToolIds = if (longRunning) setOf(callId) else emptySet(),
    timestamp = timestamp,
  )

/**
 * A `user`-authored [Event] carrying the [name] function response that closes [callId], with an
 * optional [response] payload (defaults to an empty map).
 */
fun eventWithFunctionResponse(
  invocationId: String,
  timestamp: Long,
  name: String,
  callId: String,
  response: Map<String, Any?> = emptyMap(),
): Event =
  Event(
    author = Role.USER,
    invocationId = invocationId,
    content = userFunctionResponse(name = name, id = callId, response = response),
    timestamp = timestamp,
  )

/** A `model`-authored [Event] requesting tool confirmation for [callId]. */
fun eventWithHitlRequest(invocationId: String, timestamp: Long, callId: String): Event =
  Event(
    author = Role.MODEL,
    invocationId = invocationId,
    actions =
      EventActions(
        requestedToolConfirmations = mutableMapOf(callId to ToolConfirmation(confirmed = false))
      ),
    timestamp = timestamp,
  )

/** A `user`-authored marker [Event] that rewinds history before [rewoundInvocationId]. */
fun rewindEvent(invocationId: String, rewoundInvocationId: String, timestamp: Long = 0L): Event =
  Event(
    author = Role.USER,
    invocationId = invocationId,
    actions = EventActions(rewindBeforeInvocationId = rewoundInvocationId),
    timestamp = timestamp,
  )

/** An [Event] carrying an [EventCompaction] [summary] spanning [startTs]..[endTs]. */
fun compactionEvent(
  startTs: Long,
  endTs: Long,
  timestamp: Long = 0L,
  summary: String = "summary",
): Event =
  Event(
    author = Role.USER,
    actions =
      EventActions(
        compaction =
          EventCompaction(
            startTimestamp = startTs,
            endTimestamp = endTs,
            compactedContent = modelMessage(summary),
          )
      ),
    timestamp = timestamp,
  )
