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

import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.types.LiveServerGoAway
import com.google.adk.kt.types.LiveServerSessionResumptionUpdate
import com.google.adk.kt.types.Transcription
import com.google.genai.kotlin.GenAiApiException
import kotlin.time.Duration

/** The error a live connection fails with when its socket drops abnormally (close code 1006). */
internal fun connectionDrop(): GenAiApiException =
  GenAiApiException(1006, "ConnectionClosed", "socket closed")

/** A session resumption update that hands out [handle] for the next connection. */
internal fun handleUpdate(handle: String): LlmResponse =
  LlmResponse(
    liveSessionResumptionUpdate =
      LiveServerSessionResumptionUpdate(newHandle = handle, resumable = true)
  )

/** A notice that the server closes the connection after [timeLeft], or soon when it is null. */
internal fun goAway(timeLeft: Duration? = null): LlmResponse =
  LlmResponse(goAway = LiveServerGoAway(timeLeft = timeLeft))

internal fun turnComplete(): LlmResponse = LlmResponse(turnComplete = true)

/** A transcription of the user's speech, partial until it is [finished]. */
internal fun inputTranscription(text: String, finished: Boolean): LlmResponse =
  LlmResponse(
    inputTranscription = Transcription(text = text, finished = finished),
    partial = !finished,
  )

/** A transcription of the model's speech, partial until it is [finished]. */
internal fun outputTranscription(text: String, finished: Boolean): LlmResponse =
  LlmResponse(
    outputTranscription = Transcription(text = text, finished = finished),
    partial = !finished,
  )
