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
import com.google.adk.kt.events.Event
import com.google.adk.kt.models.ContentInput
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.RealtimeInput
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.testing.DummyLiveModel
import com.google.adk.kt.testing.DummyLiveStep
import com.google.adk.kt.testing.DummyLiveStep.AwaitSent
import com.google.adk.kt.testing.DummyLiveStep.Respond
import com.google.adk.kt.testing.DummyTool
import com.google.adk.kt.testing.audioFrame
import com.google.adk.kt.testing.awaitToolResponse
import com.google.adk.kt.testing.modelFunctionCallResponse
import com.google.adk.kt.testing.simpleAudioTurn
import com.google.adk.kt.testing.spokenModelTurn
import com.google.adk.kt.testing.turnCompleteStep
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.Transcription
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlin.test.Test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

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

  private fun Any.toolAnswers(): List<FunctionResponse> =
    (this as? ContentInput)?.content?.parts?.mapNotNull { it.functionResponse }.orEmpty()

  private fun answers(callId: String, response: Map<String, Any?>): (Any) -> Boolean = { message ->
    message.toolAnswers().any { it.id == callId && it.response == response }
  }

  private fun DummyLiveModel.toolAnswers(): List<FunctionResponse> =
    connections.flatMap { connection ->
      connection.sent.flatMap { it.toolAnswers() }
    }

  /** Runs live until the model completes a turn, then hangs up as a caller would. */
  private suspend fun InMemoryRunner.runUntilTurnComplete() =
    LiveRequestQueue().let { queue ->
      runLive(USER_ID, SESSION_ID, queue, liveConfig())
        .onEach { if (it.turnComplete) queue.close() }
        .toList()
    }

  /** A `monitor` tool that emits "watching <symbol>" once, then waits to be cancelled. */
  private fun watchingTool(streams: MutableStateFlow<Int>) =
    DummyTool(name = "monitor") { _, args ->
      flow {
        streams.update { it + 1 }
        emit("watching ${args["symbol"]}")
        awaitCancellation()
      }
    }

  /** A stream that emits nothing until it is cancelled, completing [cancelled] when it is. */
  private fun endlessStream(cancelled: CompletableDeferred<Unit>) =
    flow<String> {
      try {
        awaitCancellation()
      } finally {
        cancelled.complete(Unit)
      }
    }

  /** A `monitor` tool returning an [endlessStream]. */
  private fun endlessTool(cancelled: CompletableDeferred<Unit>) =
    DummyTool(name = "monitor") { _, _ -> endlessStream(cancelled) }

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

  @Test
  fun runLive_toolReturnsAFlow_answersPendingThenEachValueToTheSameCall(): Unit = runBlocking {
    val script =
      listOf(
        Respond(modelFunctionCallResponse("monitor", id = "c1")),
        AwaitSent("the pending answer", answers("c1", PENDING)),
        AwaitSent("the first value", answers("c1", mapOf("result" to "300"))),
        AwaitSent("the second value", answers("c1", mapOf("result" to "400"))),
        Respond(LlmResponse(turnComplete = true)),
      )
    val tool = DummyTool(name = "monitor") { _, _ -> flowOf("300", "400") }
    val (runner, model) = runnerFor(script, tools = listOf(tool))

    val unused = runner.runUntilTurnComplete()

    val answers = model.toolAnswers()
    assertThat(answers.map { it.response })
      .containsExactly(PENDING, mapOf("result" to "300"), mapOf("result" to "400"))
      .inOrder()
    assertThat(answers.map { it.id to it.name }.distinct()).containsExactly("c1" to "monitor")
  }

  @Test
  fun runLive_streamingToolEmitsAnEvent_deliversItsMessageToTheCallerOnly(): Unit = runBlocking {
    val message =
      Event(author = "someone_else", content = Content(Role.MODEL, listOf(Part(text = "watching"))))
    val script =
      listOf(
        Respond(modelFunctionCallResponse("monitor", id = "c1")),
        AwaitSent("the pending answer", answers("c1", PENDING)),
        AwaitSent("the value", answers("c1", mapOf("result" to "300"))),
        Respond(LlmResponse(turnComplete = true)),
      )
    // The content-less event has nothing to deliver and is dropped.
    val tool = DummyTool(name = "monitor") { _, _ -> flowOf(message, Event(), "300") }
    val (runner, model) = runnerFor(script, tools = listOf(tool))

    val events = runner.runUntilTurnComplete()

    val delivered = events.filter { it.branch == "monitor@c1" }
    assertThat(delivered.map { it.content })
      .containsExactly(Content(Role.USER, listOf(Part(text = "watching"))))
    assertThat(delivered.single().author).isEqualTo("live_agent")
    assertThat(delivered.single().id).isNotEqualTo(message.id)
    assertThat(model.toolAnswers().map { it.response })
      .containsExactly(PENDING, mapOf("result" to "300"))
      .inOrder()
  }

  @Test
  fun runLive_streamingToolThrows_answersWithAGenericError(): Unit = runBlocking {
    val error = mapOf("error" to "Invoking `monitor()` failed with an internal error.")
    val script =
      listOf(
        Respond(modelFunctionCallResponse("monitor", id = "c1")),
        AwaitSent("the pending answer", answers("c1", PENDING)),
        AwaitSent("the error", answers("c1", error)),
        Respond(LlmResponse(turnComplete = true)),
      )
    val tool =
      DummyTool(name = "monitor") { _, _ ->
        flow<String> { throw IllegalStateException("account 1234 is overdrawn") }
      }
    val (runner, model) = runnerFor(script, tools = listOf(tool))

    val unused = runner.runUntilTurnComplete()

    assertThat(model.toolAnswers().map { it.response }).containsExactly(PENDING, error).inOrder()
  }

  @Test
  fun runLive_runEnds_stopsItsStreamingTools(): Unit = runBlocking {
    val cancelled = CompletableDeferred<Unit>()
    val script =
      listOf(
        Respond(modelFunctionCallResponse("monitor", id = "c1")),
        AwaitSent("the pending answer", answers("c1", PENDING)),
        Respond(LlmResponse(turnComplete = true)),
      )
    val (runner, _) = runnerFor(script, tools = listOf(endlessTool(cancelled)))

    val unused = runner.runUntilTurnComplete()

    assertThat(cancelled.isCompleted).isTrue()
  }

  @Test
  fun runLive_callerStopsCollecting_stopsTheStreamingTools(): Unit = runBlocking {
    val cancelled = CompletableDeferred<Unit>()
    val script =
      listOf(
        Respond(modelFunctionCallResponse("monitor", id = "c1")),
        AwaitSent("the pending answer", answers("c1", PENDING)),
        Respond(
          LlmResponse(
            outputTranscription = Transcription(text = "watching", finished = false),
            partial = true,
          )
        ),
      )
    val (runner, _) = runnerFor(script, tools = listOf(endlessTool(cancelled)))

    val unused =
      runner.runLive(USER_ID, SESSION_ID, LiveRequestQueue(), liveConfig()).first {
        it.outputTranscription != null
      }

    assertThat(cancelled.isCompleted).isTrue()
  }

  @Test
  fun runLive_streamingToolThrowsAKotlinError_answersNothingAndTheRunGoesOn(): Unit = runBlocking {
    val script =
      listOf(
        Respond(modelFunctionCallResponse("monitor", id = "c1")),
        AwaitSent("the pending answer", answers("c1", PENDING)),
        Respond(
          LlmResponse(
            outputTranscription = Transcription(text = "still here", finished = true),
            partial = false,
          )
        ),
        Respond(LlmResponse(turnComplete = true)),
      )
    var started = false
    val broken =
      flow<String> {
        started = true
        throw AssertionError()
      }
    val tool = DummyTool(name = "monitor") { _, _ -> broken }
    val (runner, model) = runnerFor(script, tools = listOf(tool))

    val events = runner.runUntilTurnComplete()

    assertThat(started).isTrue()
    assertThat(model.toolAnswers().map { it.response }).containsExactly(PENDING)
    assertThat(events.mapNotNull { it.outputTranscription?.text }).contains("still here")
  }

  @Test
  fun runLive_sameCallIdTwiceInOneTurn_streamsOnlyTheNewerCall(): Unit = runBlocking {
    val streams = MutableStateFlow(0)
    val calls =
      listOf("gold", "silver").map { symbol ->
        Part(
          functionCall = FunctionCall(name = "monitor", args = mapOf("symbol" to symbol), id = "c1")
        )
      }
    val script =
      listOf(
        Respond(LlmResponse(content = Content(Role.MODEL, calls))),
        AwaitSent("the silver value", answers("c1", mapOf("result" to "watching silver"))),
        Respond(LlmResponse(turnComplete = true)),
      )
    val (runner, model) = runnerFor(script, tools = listOf(watchingTool(streams)))

    val unused = runner.runUntilTurnComplete()

    model.connections.single().assertAllWaitsMet()
    assertThat(streams.value).isEqualTo(1)
    assertThat(model.toolAnswers().map { it.response }.filter { it != PENDING })
      .containsExactly(mapOf("result" to "watching silver"))
  }

  @Test
  fun runLive_identicalCallWhileItStreams_answersAlreadyStreamingAndStartsNoSecondStream(): Unit =
    runBlocking {
      val streams = MutableStateFlow(0)
      val gold = mapOf("symbol" to "gold")
      val script =
        listOf(
          Respond(modelFunctionCallResponse("monitor", gold, id = "c1")),
          AwaitSent("the first value", answers("c1", mapOf("result" to "watching gold"))),
          Respond(modelFunctionCallResponse("monitor", gold, id = "c2")),
          AwaitSent("the repeat's answer", answers("c2", ALREADY_STREAMING)),
          Respond(LlmResponse(turnComplete = true)),
        )
      val (runner, model) = runnerFor(script, tools = listOf(watchingTool(streams)))

      val unused = runner.runUntilTurnComplete()

      model.connections.single().assertAllWaitsMet()
      assertThat(streams.value).isEqualTo(1)
      assertThat(model.toolAnswers().filter { it.id == "c2" }.map { it.response })
        .containsExactly(ALREADY_STREAMING)
    }

  @Test
  fun runLive_identicalCallsInOneResponse_streamOnceAndAnswerTheSecondAlreadyStreaming(): Unit =
    runBlocking {
      val streams = MutableStateFlow(0)
      val calls =
        listOf("c1", "c2").map { id ->
          Part(
            functionCall = FunctionCall(name = "monitor", args = mapOf("symbol" to "gold"), id = id)
          )
        }
      val script =
        listOf(
          Respond(LlmResponse(content = Content(Role.MODEL, calls))),
          AwaitSent("the value", answers("c1", mapOf("result" to "watching gold"))),
          Respond(LlmResponse(turnComplete = true)),
        )
      // Suspends before returning its flow, so both calls pass run's check and start refuses c2.
      val monitor =
        DummyTool(name = "monitor") { _, args ->
          yield()
          flow {
            streams.update { it + 1 }
            emit("watching ${args["symbol"]}")
            awaitCancellation()
          }
        }
      val (runner, model) = runnerFor(script, tools = listOf(monitor))

      val unused = runner.runUntilTurnComplete()

      model.connections.single().assertAllWaitsMet()
      assertThat(streams.value).isEqualTo(1)
      assertThat(model.toolAnswers().filter { it.id == "c2" }.map { it.response })
        .containsExactly(ALREADY_STREAMING)
    }

  @Test
  fun runLive_identicalCallReusingTheRunningId_answersAlreadyStreaming(): Unit = runBlocking {
    val streams = MutableStateFlow(0)
    val gold = mapOf("symbol" to "gold")
    val script =
      listOf(
        Respond(modelFunctionCallResponse("monitor", gold, id = "c1")),
        AwaitSent("the first value", answers("c1", mapOf("result" to "watching gold"))),
        Respond(modelFunctionCallResponse("monitor", gold, id = "c1")),
        AwaitSent("the repeat's answer", answers("c1", ALREADY_STREAMING)),
        Respond(LlmResponse(turnComplete = true)),
      )
    val (runner, model) = runnerFor(script, tools = listOf(watchingTool(streams)))

    val unused = runner.runUntilTurnComplete()

    model.connections.single().assertAllWaitsMet()
    assertThat(streams.value).isEqualTo(1)
    assertThat(model.toolAnswers().map { it.response })
      .containsExactly(PENDING, mapOf("result" to "watching gold"), ALREADY_STREAMING)
      .inOrder()
  }

  @Test
  fun runLive_identicalCallWhileItStreams_doesNotRunTheToolAgain(): Unit = runBlocking {
    val runs = MutableStateFlow(0)
    val monitor =
      DummyTool(name = "monitor") { _, _ ->
        runs.update { it + 1 }
        flow {
          emit("watching gold")
          awaitCancellation()
        }
      }
    val gold = mapOf("symbol" to "gold")
    val script =
      listOf(
        Respond(modelFunctionCallResponse("monitor", gold, id = "c1")),
        AwaitSent("the first value", answers("c1", mapOf("result" to "watching gold"))),
        Respond(modelFunctionCallResponse("monitor", gold, id = "c2")),
        AwaitSent("the repeat's answer", answers("c2", ALREADY_STREAMING)),
        Respond(LlmResponse(turnComplete = true)),
      )
    val (runner, model) = runnerFor(script, tools = listOf(monitor))

    val unused = runner.runUntilTurnComplete()

    model.connections.single().assertAllWaitsMet()
    assertThat(runs.value).isEqualTo(1)
  }

  @Test
  fun runLive_sameToolWithOtherArgumentsWhileItStreams_startsASecondStream(): Unit = runBlocking {
    val streams = MutableStateFlow(0)
    val script =
      listOf(
        Respond(modelFunctionCallResponse("monitor", mapOf("symbol" to "gold"), id = "c1")),
        AwaitSent("the gold value", answers("c1", mapOf("result" to "watching gold"))),
        Respond(modelFunctionCallResponse("monitor", mapOf("symbol" to "silver"), id = "c2")),
        AwaitSent("the silver pending answer", answers("c2", PENDING)),
        AwaitSent("the silver value", answers("c2", mapOf("result" to "watching silver"))),
        Respond(LlmResponse(turnComplete = true)),
      )
    val (runner, model) = runnerFor(script, tools = listOf(watchingTool(streams)))

    val unused = runner.runUntilTurnComplete()

    model.connections.single().assertAllWaitsMet()
    assertThat(streams.value).isEqualTo(2)
  }

  @Test
  fun runLive_identicalCallAfterTheStreamEnded_startsANewStream(): Unit = runBlocking {
    val firstEnded = CompletableDeferred<Unit>()
    val streams = MutableStateFlow(0)
    val monitor =
      DummyTool(name = "monitor") { _, _ ->
        flow {
            streams.update { it + 1 }
            emit("300")
          }
          .onCompletion { firstEnded.complete(Unit) }
      }
    val gold = mapOf("symbol" to "gold")
    val script =
      listOf(
        Respond(modelFunctionCallResponse("monitor", gold, id = "c1")),
        AwaitSent("the first stream's value", answers("c1", mapOf("result" to "300"))),
        Respond(LlmResponse(turnComplete = true)),
        AwaitSent("the caller's next turn") { message ->
          (message as? ContentInput)?.content?.parts?.any { it.text == "again" } == true
        },
        Respond(modelFunctionCallResponse("monitor", gold, id = "c2")),
        AwaitSent("the second pending answer", answers("c2", PENDING)),
        AwaitSent("the second stream's value", answers("c2", mapOf("result" to "300"))),
        Respond(LlmResponse(turnComplete = true)),
      )
    val (runner, model) = runnerFor(script, tools = listOf(monitor))
    val queue = LiveRequestQueue()
    var turns = 0

    val unused =
      runner
        .runLive(USER_ID, SESSION_ID, queue, liveConfig())
        .onEach {
          if (!it.turnComplete) return@onEach
          turns++
          if (turns == 1) {
            firstEnded.await()
            queue.sendContent(Content(Role.USER, listOf(Part(text = "again"))))
          } else {
            queue.close()
          }
        }
        .toList()

    model.connections.single().assertAllWaitsMet()
    assertThat(streams.value).isEqualTo(2)
  }

  @Test
  fun runLive_streamingToolEmitsAFunctionResponse_sendsItsPayloadUnderTheCall(): Unit =
    runBlocking {
      val script =
        listOf(
          Respond(modelFunctionCallResponse("monitor", id = "c1")),
          AwaitSent("the payload", answers("c1", mapOf("price" to 300))),
          Respond(LlmResponse(turnComplete = true)),
        )
      val emitted = FunctionResponse(name = "other", id = "x", response = mapOf("price" to 300))
      val (runner, model) =
        runnerFor(script, tools = listOf(DummyTool(name = "monitor") { _, _ -> flowOf(emitted) }))

      val unused = runner.runUntilTurnComplete()

      assertThat(
          model
            .toolAnswers()
            .filter { it.response == mapOf("price" to 300) }
            .map { it.id to it.name }
        )
        .containsExactly("c1" to "monitor")
    }

  @Test
  fun runLive_twoStreamingToolsInterleaveEventsAndValues_eachArrivesOnceAndIsStored(): Unit =
    runBlocking {
      val note = Event(content = Content(Role.MODEL, listOf(Part(text = "note"))))
      val first = DummyTool(name = "first") { _, _ -> flowOf(note, "a1", note, "a2") }
      val second = DummyTool(name = "second") { _, _ -> flowOf("b1") }
      val script =
        listOf(
          Respond(modelFunctionCallResponse("first", id = "c1")),
          AwaitSent("the first tool's last value", answers("c1", mapOf("result" to "a2"))),
          Respond(modelFunctionCallResponse("second", id = "c2")),
          AwaitSent("the second tool's value", answers("c2", mapOf("result" to "b1"))),
          Respond(LlmResponse(turnComplete = true)),
        )
      val (runner, model) = runnerFor(script, tools = listOf(first, second))

      val events = runner.runUntilTurnComplete()

      assertThat(model.toolAnswers().filter { it.id == "c1" }.map { it.response })
        .containsExactly(PENDING, mapOf("result" to "a1"), mapOf("result" to "a2"))
        .inOrder()
      assertThat(model.toolAnswers().filter { it.id == "c2" }.map { it.response })
        .containsExactly(PENDING, mapOf("result" to "b1"))
        .inOrder()
      // The same Event object emitted twice reaches the caller as two events of its own.
      val notes = events.filter { it.branch == "first@c1" }
      assertThat(notes).hasSize(2)
      assertThat(notes.map { it.id }.distinct()).hasSize(2)
      val session =
        runner.sessionService.getSession(SessionKey(runner.appName, USER_ID, SESSION_ID))
      assertThat(session?.events?.count { it.branch == "first@c1" }).isEqualTo(2)
    }

  companion object {
    private const val USER_ID = "user"
    private const val SESSION_ID = "session"
    private const val TOOL_CALL_ID = "call-1"
    private val PENDING =
      mapOf("status" to "The function is running asynchronously and the results are pending.")
    private val ALREADY_STREAMING =
      mapOf(
        "status" to
          "monitor is already running with these arguments; wait for its updates " +
            "instead of calling again."
      )
  }
}
