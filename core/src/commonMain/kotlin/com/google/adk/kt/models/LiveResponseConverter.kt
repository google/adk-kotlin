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

package com.google.adk.kt.models

import com.google.adk.kt.types.fromGenaiSdk
import com.google.adk.kt.types.toKt
import com.google.genai.kotlin.types.LiveServerMessage as GenAiLiveServerMessage

/**
 * Maps one live server frame onto the [LlmResponse]s it carries.
 *
 * A frame carries several independent signals, so this returns a list; the order follows ADK
 * Python's and callers must preserve it, since usage metadata precedes the turn-complete response
 * it accounts for.
 *
 * This mapping is stateless and per-frame: accumulating across frames, collecting tool calls and
 * carrying grounding forward are the caller's job.
 *
 * @param modelVersion Stamped onto every response, since the frame does not carry it.
 * @param liveSessionId Stamped onto every response so a consumer can attribute it to a session.
 */
internal fun GenAiLiveServerMessage.toLlmResponses(
  modelVersion: String? = null,
  liveSessionId: String? = null,
): List<LlmResponse> = buildList {
  val base = LlmResponse(modelVersion = modelVersion, liveSessionId = liveSessionId)

  // Usage metadata first: the turn-complete response must not precede its own accounting.
  usageMetadata?.let { usage -> add(base.copy(usageMetadata = usage.fromGenaiSdk())) }

  serverContent?.let { content ->
    val interrupted = content.interrupted == true

    val turnWithParts = content.modelTurn?.takeIf { !it.parts.isNullOrEmpty() }
    val turnIsComplete = content.turnComplete == true
    val reason = content.turnCompleteReason?.toKt()
    val grounding = content.groundingMetadata?.fromGenaiSdk()
    // A frame that ends the turn carries its grounding on the turn-complete response instead.
    val earlyGrounding = if (turnIsComplete) null else grounding

    // Grounding without content still gets a response here, or nothing could cite it.
    if (turnWithParts != null || earlyGrounding != null) {
      add(
        base.copy(
          content = turnWithParts?.fromGenaiSdk(),
          interrupted = interrupted,
          turnCompleteReason = reason,
          // Text is a streamed chunk, as ADK Python marks it; audio is not.
          partial = turnWithParts?.parts?.any { !it.text.isNullOrEmpty() } == true,
          groundingMetadata = earlyGrounding,
        )
      )
    }

    // Emit the end marker even without text: the caller flushes its transcript on it.
    content.inputTranscription
      ?.takeIf { !it.text.isNullOrEmpty() || it.finished == true }
      ?.let {
        add(base.copy(inputTranscription = it.fromGenaiSdk(), partial = it.finished != true))
      }
    content.outputTranscription
      ?.takeIf { !it.text.isNullOrEmpty() || it.finished == true }
      ?.let {
        add(base.copy(outputTranscription = it.fromGenaiSdk(), partial = it.finished != true))
      }

    when {
      turnIsComplete ->
        add(
          base.copy(
            turnComplete = true,
            interrupted = interrupted,
            turnCompleteReason = reason,
            interactionStatus = content.interactionStatus?.toKt(),
            groundingMetadata = grounding,
          )
        )
      // An interruption with no content still reaches the caller, which cancels playback.
      interrupted && turnWithParts == null -> add(base.copy(interrupted = true))
    }
  }

  sessionResumptionUpdate?.let { update ->
    add(base.copy(liveSessionResumptionUpdate = update.fromGenaiSdk()))
  }
  voiceActivity?.let { activity -> add(base.copy(voiceActivity = activity.fromGenaiSdk())) }
  goAway?.let { away -> add(base.copy(goAway = away.fromGenaiSdk())) }
}
