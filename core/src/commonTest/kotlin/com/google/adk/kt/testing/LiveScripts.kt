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

package com.google.adk.kt.testing

import com.google.adk.kt.annotations.ExperimentalLiveApi
import com.google.adk.kt.events.Event
import com.google.adk.kt.models.ContentInput
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.testing.DummyLiveStep.AwaitSent
import com.google.adk.kt.testing.DummyLiveStep.Respond
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.LiveServerSessionResumptionUpdate
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.UsageMetadata

/** A whole model text response, not a streamed chunk. */
internal fun wholeText(text: String): DummyLiveStep =
  Respond(LlmResponse(content = modelMessage(text), partial = false))

/** The model's turn boundary. */
internal fun turnCompleteStep(): DummyLiveStep = Respond(turnComplete())

/** One 20 ms frame of 24 kHz model audio (960 bytes). */
internal fun audioFrame(): DummyLiveStep =
  Respond(
    LlmResponse(
      content =
        modelMessage(
          Part(inlineData = Blob(mimeType = "audio/pcm;rate=24000", data = ByteArray(960)))
        )
    )
  )

/** A transcription of the model's speech, partial until it is [finished]. */
internal fun outputTranscriptionStep(text: String, finished: Boolean): DummyLiveStep =
  Respond(outputTranscription(text, finished))

/** Waits until the agent sends content made only of function responses. */
internal fun awaitToolResponse(): DummyLiveStep =
  AwaitSent("a tool response") { sent ->
    sent is ContentInput &&
      sent.content.parts.isNotEmpty() &&
      sent.content.parts.all { it.functionResponse != null }
  }

/** A spoken model turn: transcription, [audioFrames] of audio, usage, finished copy, turn end. */
internal fun spokenModelTurn(transcript: String, audioFrames: Int): List<DummyLiveStep> =
  listOf(outputTranscriptionStep(transcript, finished = false)) +
    List(audioFrames) { audioFrame() } +
    listOf(
      Respond(
        LlmResponse(
          usageMetadata =
            UsageMetadata(promptTokenCount = 12, candidatesTokenCount = 34, totalTokenCount = 46)
        )
      ),
      outputTranscriptionStep(transcript, finished = true),
      turnCompleteStep(),
    )

/** A spoken "hello there" turn, then a resumption handle on the next receive. */
internal fun simpleAudioTurn(audioFrames: Int = 1): List<DummyLiveStep> =
  spokenModelTurn("hello there", audioFrames) +
    Respond(
      LlmResponse(
        liveSessionResumptionUpdate =
          LiveServerSessionResumptionUpdate(newHandle = "handle-after-turn", resumable = true)
      )
    )

/** The events this runner stored for [userId]'s session [sessionId]. */
internal suspend fun InMemoryRunner.storedEvents(
  userId: String = "u",
  sessionId: String = "s",
): List<Event> = sessionService.getSession(SessionKey(appName, userId, sessionId))?.events.orEmpty()
