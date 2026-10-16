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

package com.google.adk.kt.runners

import com.google.adk.kt.agents.LiveRequestQueue
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.RunConfig
import com.google.adk.kt.agents.StreamingMode
import com.google.adk.kt.annotations.ExperimentalLiveApi
import com.google.adk.kt.models.ContentInput
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.RealtimeInput
import com.google.adk.kt.testing.DummyLiveModel
import com.google.adk.kt.testing.DummyLiveStep
import com.google.adk.kt.testing.DummyLiveStep.Respond
import com.google.adk.kt.testing.DummyTool
import com.google.adk.kt.testing.audioFrame
import com.google.adk.kt.testing.awaitToolResponse
import com.google.adk.kt.testing.modelFunctionCallResponse
import com.google.adk.kt.testing.simpleAudioTurn
import com.google.adk.kt.testing.spokenModelTurn
import com.google.adk.kt.testing.turnCompleteStep
import com.google.adk.kt.types.Blob
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlin.test.Test
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/** Drives whole live runs through the public runner against scripted presets of real traffic. */
class LiveRunIntegrationTest {

  private fun runnerFor(
    script: List<DummyLiveStep>,
    tools: List<DummyTool> = emptyList(),
    modelName: String = "fake-live-model",
  ): Pair<InMemoryRunner, DummyLiveModel> {
    val model = DummyLiveModel(script, modelName)
    val agent = LlmAgent(name = "live_agent", model = model, tools = tools)
    return InMemoryRunner(agent = agent) to model
  }

  /** The model starts speaking, then the user's speech interrupts it. */
  private fun bargeIn(audioFramesBeforeInterrupt: Int): List<DummyLiveStep> =
    List(audioFramesBeforeInterrupt) { audioFrame() } +
      listOf(Respond(LlmResponse(interrupted = true)), turnCompleteStep())

  /** The model calls [toolName], waits for its answer, then speaks the result. */
  private fun toolRoundTrip(toolName: String): List<DummyLiveStep> =
    listOf(Respond(modelFunctionCallResponse(toolName, id = TOOL_CALL_ID)), awaitToolResponse()) +
      spokenModelTurn("done", audioFrames = 2)

  /** Whether [this] sent content answers the [toolName] call [callId] with a non-empty response. */
  private fun Any.isToolResponseFor(toolName: String, callId: String): Boolean =
    this is ContentInput &&
      content.parts.any { part ->
        val response = part.functionResponse
        response != null &&
          response.name == toolName &&
          response.id == callId &&
          response.response.isNotEmpty()
      }

  /** Sends one 20 ms chunk of 16 kHz, 16-bit PCM caller audio (640 bytes). */
  private fun LiveRequestQueue.sendAudioChunk() {
    sendRealtime(
      RealtimeInput.Audio(Blob(mimeType = "audio/pcm;rate=16000", data = ByteArray(640)))
    )
  }

  private fun liveConfig() = RunConfig(streamingMode = StreamingMode.BIDI)

  @Test
  fun runLive_spokenTurn_emitsTheTurnsContentAndCompletesWhenTheQueueCloses(): Unit = runBlocking {
    val (runner, _) = runnerFor(simpleAudioTurn(audioFrames = 2))
    val queue = LiveRequestQueue()

    queue.sendAudioChunk()
    // Closed at turn complete, so the run is not ended before the scripted turn arrives.
    val events =
      runner
        .runLive(USER_ID, SESSION_ID, queue, liveConfig())
        .onEach { if (it.turnComplete) queue.close() }
        .toList()

    assertThat(events.mapNotNull { it.outputTranscription?.text }).contains("hello there")
    assertThat(events.count { e -> e.content?.parts?.any { it.inlineData != null } == true })
      .isEqualTo(2)
    assertThat(events.map { it.turnComplete }).contains(true)
  }

  @Test
  fun runLive_userSpeaksOver_surfacesTheInterruption(): Unit = runBlocking {
    val (runner, _) = runnerFor(bargeIn(audioFramesBeforeInterrupt = 2))
    val queue = LiveRequestQueue()

    queue.sendAudioChunk()
    // Closed once the interruption arrives: closing the queue ends the run, as in ADK Python.
    val events =
      runner
        .runLive(USER_ID, SESSION_ID, queue, liveConfig())
        .onEach { if (it.interrupted) queue.close() }
        .toList()

    assertThat(events.map { it.interrupted }).contains(true)
  }

  @Test
  fun runLive_modelCallsATool_sendsTheResponseBackOverTheConnection(): Unit = runBlocking {
    val (runner, model) =
      runnerFor(
        toolRoundTrip("GetWeather"),
        tools = listOf(DummyTool(name = "GetWeather") { _, _ -> mapOf("tempF" to 72) }),
        // The script models a Gemini 3.x live model, so the run is named as one.
        modelName = "gemini-3.8-live",
      )
    val queue = LiveRequestQueue()

    queue.sendAudioChunk()
    // Closed at turn complete so the tool answer is sent before the caller hangs up.
    runner
      .runLive(USER_ID, SESSION_ID, queue, liveConfig())
      .onEach { if (it.turnComplete) queue.close() }
      .toList()

    val connection = model.connections.single()
    assertWithMessage(
        "no answer to $TOOL_CALL_ID reached the model; sent: " +
          connection.sent.joinToString { it::class.simpleName ?: "?" }
      )
      .that(connection.sent.any { it.isToolResponseFor("GetWeather", TOOL_CALL_ID) })
      .isTrue()
    model.connections.forEach { it.assertAllWaitsMet() }
  }

  @Test
  fun runLive_spokenTurn_opensExactlyOneConnection(): Unit = runBlocking {
    // One conversation, one connection: per-turn connections lose state and redo the handshake.
    val (runner, model) = runnerFor(simpleAudioTurn(audioFrames = 1))
    val queue = LiveRequestQueue()

    queue.sendAudioChunk()
    queue.close()
    runner.runLive(USER_ID, SESSION_ID, queue, liveConfig()).toList()

    assertThat(model.connections).hasSize(1)
  }

  companion object {
    private const val USER_ID = "user"
    private const val SESSION_ID = "session"
    private const val TOOL_CALL_ID = "call-1"
  }
}
