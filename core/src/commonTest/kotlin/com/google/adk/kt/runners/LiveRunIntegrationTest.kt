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
import com.google.adk.kt.sessions.InMemorySessionService
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.sessions.SessionService
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
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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

  /** The ids of the calls the session holds a stopped answer for, oldest first. */
  private suspend fun InMemoryRunner.stoppedCallIds(): List<String?> =
    sessionService
      .getSession(SessionKey(appName, USER_ID, SESSION_ID))
      ?.events
      .orEmpty()
      .flatMap { it.functionResponses() }
      .filter { it.response == STOPPED }
      .map { it.id }

  @Test
  fun runLive_runEndsWithStreamsRunning_recordsEachAsStopped() {
    runBlocking {
      val script =
        listOf(
          Respond(modelFunctionCallResponse("monitor", mapOf("symbol" to "gold"), id = "c1")),
          AwaitSent("the gold pending answer", answers("c1", PENDING)),
          Respond(modelFunctionCallResponse("monitor", mapOf("symbol" to "silver"), id = "c2")),
          AwaitSent("the silver pending answer", answers("c2", PENDING)),
          Respond(LlmResponse(turnComplete = true)),
        )
      val (runner, model) = runnerFor(script, tools = listOf(endlessTool(CompletableDeferred())))

      val unused = runner.runUntilTurnComplete()

      model.connections.single().assertAllWaitsMet()
      assertThat(runner.stoppedCallIds()).containsExactly("c1", "c2")
      // A record for later runs: the model is not told.
      assertThat(model.toolAnswers().map { it.response }).doesNotContain(STOPPED)
    }
  }

  @Test
  fun runLive_runEndsWithARepeatedCallWaiting_recordsTheRepeatAsStoppedToo() {
    runBlocking {
      val script =
        listOf(
          Respond(modelFunctionCallResponse("monitor", id = "c1")),
          AwaitSent("the pending answer", answers("c1", PENDING)),
          Respond(modelFunctionCallResponse("monitor", id = "c2")),
          AwaitSent("the repeat's answer", answers("c2", ALREADY_STREAMING)),
          Respond(LlmResponse(turnComplete = true)),
        )
      val (runner, model) = runnerFor(script, tools = listOf(endlessTool(CompletableDeferred())))

      val unused = runner.runUntilTurnComplete()

      model.connections.single().assertAllWaitsMet()
      assertThat(runner.stoppedCallIds()).containsExactly("c1", "c2")
    }
  }

  // Virtual time lets a slow caller leave tool events queued when the run ends.
  @Test
  fun runLive_runEndsWithToolEventsStillQueued_storesThemBeforeTheStoppedRecords(): Unit = runTest {
    val calls = (1..6).map { FunctionCall(name = "monitor", args = mapOf("n" to it), id = "c$it") }
    val script =
      listOf(
        Respond(LlmResponse(content = Content(Role.MODEL, calls.map { Part(functionCall = it) }))),
        AwaitSent("the pending answers") {
          it is ContentInput &&
            it.content.parts.count { part -> part.functionResponse != null } == 6
        },
        Respond(LlmResponse(turnComplete = true)),
      )
    val tool =
      DummyTool(name = "monitor") { _, args ->
        flow<Any> {
          emit(Event(content = Content(Role.USER, listOf(Part(text = "watching ${args["n"]}")))))
          awaitCancellation()
        }
      }
    val (runner, _) = runnerFor(script, tools = listOf(tool))
    val queue = LiveRequestQueue()

    val unused =
      runner
        .runLive(USER_ID, SESSION_ID, queue, liveConfig())
        .onEach { event ->
          if (event.branch?.startsWith("monitor@") == true) delay(3000.milliseconds)
          if (event.turnComplete) queue.close()
        }
        .toList()

    val stored = runner.sessionService.getSession(SessionKey(runner.appName, USER_ID, SESSION_ID))
    val events = checkNotNull(stored).events
    val toolEventsAt = events.indices.filter { events[it].branch?.startsWith("monitor@") == true }
    val recordsAt =
      events.indices.filter { index ->
        events[index].functionResponses().any { it.response == STOPPED }
      }
    // Stored after the tools' updates, so each stop is its call's last event in the session.
    assertThat(toolEventsAt).hasSize(6)
    assertThat(recordsAt).hasSize(6)
    assertThat(toolEventsAt.max()).isLessThan(recordsAt.min())
  }

  @Test
  fun runLive_callerStopsCollectingWithAStreamRunning_recordsItAsStopped() {
    runBlocking {
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
      val (runner, _) = runnerFor(script, tools = listOf(endlessTool(CompletableDeferred())))

      val unused =
        runner.runLive(USER_ID, SESSION_ID, LiveRequestQueue(), liveConfig()).first {
          it.outputTranscription != null
        }

      assertThat(runner.stoppedCallIds()).containsExactly("c1")
    }
  }

  @Test
  fun runLive_stoppedRecordFailsToSave_callerStillGetsTheEventItStoppedAt() {
    runBlocking {
      val inner = InMemorySessionService()
      val failingRecords =
        object : SessionService by inner {
          override suspend fun appendEvent(session: Session, event: Event): Event {
            if (event.functionResponses().any { it.response == STOPPED }) error("store unavailable")
            return inner.appendEvent(session, event)
          }
        }
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
      val agent =
        LlmAgent(
          name = "live_agent",
          model = DummyLiveModel(script, "fake-live-model"),
          tools = listOf(endlessTool(CompletableDeferred())),
        )
      val runner = InMemoryRunner(agent = agent, sessionService = failingRecords)

      // A teardown append that fails after `first` cancels must not replace the event it took.
      val heard =
        runner.runLive(USER_ID, SESSION_ID, LiveRequestQueue(), liveConfig()).first {
          it.outputTranscription != null
        }

      assertThat(heard.outputTranscription?.text).isEqualTo("watching")
    }
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

  /** A declared `stop_streaming` tool, whose body answers "ran" if it ever runs. */
  private fun stopStreamingTool() = DummyTool(name = "stop_streaming") { _, _ -> "ran" }

  /** The model calling `stop_streaming` on the tool [name], as call [id]. */
  private fun stopStreamingCall(name: String, id: String) =
    Respond(modelFunctionCallResponse("stop_streaming", mapOf("function_name" to name), id = id))

  /** The answer to a `stop_streaming` call that stopped the tool [name]. */
  private fun stopStreamingAnswer(name: String) =
    mapOf("status" to "Successfully stopped streaming function $name")

  /** A `monitor` tool that winds down slowly once cancelled, first completing [cancelled]. */
  private fun stubbornTool(cancelled: CompletableDeferred<Unit>? = null) =
    DummyTool(name = "monitor") { _, _ ->
      flow<String> {
        try {
          awaitCancellation()
        } finally {
          cancelled?.complete(Unit)
          // Outlasts the one-second stop timeout.
          withContext(NonCancellable) { delay(1500.milliseconds) }
        }
      }
    }

  @Test
  fun runLive_stopStreaming_stopsEveryRunningCallOfThatTool() {
    runBlocking {
      val first = CompletableDeferred<Unit>()
      val second = CompletableDeferred<Unit>()
      val cancellations = ArrayDeque(listOf(first, second))
      val monitor =
        DummyTool(name = "monitor") { _, _ -> endlessStream(cancellations.removeFirst()) }
      var stopBodyRan = false
      val stopStreaming =
        DummyTool(name = "stop_streaming") { _, _ ->
          stopBodyRan = true
          "ran"
        }
      val script =
        listOf(
          // Different arguments, since a repeat of a call that still streams starts nothing.
          Respond(modelFunctionCallResponse("monitor", mapOf("symbol" to "gold"), id = "c1")),
          AwaitSent("the first pending answer", answers("c1", PENDING)),
          Respond(modelFunctionCallResponse("monitor", mapOf("symbol" to "silver"), id = "c2")),
          AwaitSent("the second pending answer", answers("c2", PENDING)),
          stopStreamingCall("monitor", "s1"),
          AwaitSent("the stop answer", answers("s1", stopStreamingAnswer("monitor"))),
          stopStreamingCall("monitor", "s2"),
          AwaitSent(
            "the second stop answer",
            answers("s2", mapOf("status" to "No active streaming function named monitor found")),
          ),
          Respond(LlmResponse(turnComplete = true)),
        )
      val (runner, _) = runnerFor(script, tools = listOf(monitor, stopStreaming))

      val unused = runner.runUntilTurnComplete()

      assertThat(first.isCompleted).isTrue()
      assertThat(second.isCompleted).isTrue()
      assertThat(stopBodyRan).isFalse()
    }
  }

  @Test
  fun runLive_identicalCallAfterAStop_startsANewStream() {
    runBlocking {
      val streams = MutableStateFlow(0)
      val gold = mapOf("symbol" to "gold")
      val script =
        listOf(
          Respond(modelFunctionCallResponse("monitor", gold, id = "c1")),
          AwaitSent("the first value", answers("c1", mapOf("result" to "watching gold"))),
          stopStreamingCall("monitor", "s1"),
          AwaitSent("the stop answer", answers("s1", stopStreamingAnswer("monitor"))),
          Respond(modelFunctionCallResponse("monitor", gold, id = "c2")),
          AwaitSent("the new pending answer", answers("c2", PENDING)),
          AwaitSent("the new stream's value", answers("c2", mapOf("result" to "watching gold"))),
          Respond(LlmResponse(turnComplete = true)),
        )
      val stopStreaming = stopStreamingTool()
      val (runner, model) = runnerFor(script, tools = listOf(watchingTool(streams), stopStreaming))

      val unused = runner.runUntilTurnComplete()

      model.connections.single().assertAllWaitsMet()
      assertThat(streams.value).isEqualTo(2)
    }
  }

  @Test
  fun runLive_streamStoppedByStopStreaming_getsNoStoppedRecordAtTheRunsEnd() {
    runBlocking {
      val script =
        listOf(
          Respond(modelFunctionCallResponse("monitor", id = "c1")),
          AwaitSent("the pending answer", answers("c1", PENDING)),
          stopStreamingCall("monitor", "s1"),
          AwaitSent("the stop answer", answers("s1", stopStreamingAnswer("monitor"))),
          Respond(LlmResponse(turnComplete = true)),
        )
      val stopStreaming = stopStreamingTool()
      val (runner, model) =
        runnerFor(script, tools = listOf(endlessTool(CompletableDeferred()), stopStreaming))

      val unused = runner.runUntilTurnComplete()

      model.connections.single().assertAllWaitsMet()
      // The stop call's own answer is the record; the stopped call gets nothing extra.
      assertThat(runner.stoppedCallIds()).isEmpty()
    }
  }

  @Test
  fun runLive_stubbornStreamStoppedByStopStreaming_getsNoStoppedRecordAtTheRunsEnd() {
    runBlocking {
      // Still winding down when the run ends, so its final stop finds it cancelled but running.
      val stubborn = stubbornTool()
      val script =
        listOf(
          Respond(modelFunctionCallResponse("monitor", id = "c1")),
          AwaitSent("the pending answer", answers("c1", PENDING)),
          stopStreamingCall("monitor", "s1"),
          AwaitSent(
            "the stop answer",
            answers("s1", mapOf("status" to "The task is not cancelled yet for monitor.")),
          ),
          Respond(LlmResponse(turnComplete = true)),
        )
      val stopStreaming = stopStreamingTool()
      val (runner, model) = runnerFor(script, tools = listOf(stubborn, stopStreaming))

      val unused = runner.runUntilTurnComplete()

      model.connections.single().assertAllWaitsMet()
      // The stop call's answer already speaks for it; a second, conflicting record would mislead.
      assertThat(runner.stoppedCallIds()).isEmpty()
    }
  }

  @Test
  fun runLive_stoppedToolFailsWhileStopping_answersOnlyThatItStopped() {
    runBlocking {
      val failing =
        DummyTool(name = "monitor") { _, _ ->
          flow<String> {
            try {
              awaitCancellation()
            } catch (e: CancellationException) {
              throw IllegalStateException("cleanup failed", e)
            }
          }
        }
      val script =
        listOf(
          Respond(modelFunctionCallResponse("monitor", id = "c1")),
          AwaitSent("the pending answer", answers("c1", PENDING)),
          stopStreamingCall("monitor", "s1"),
          AwaitSent("the stop answer", answers("s1", stopStreamingAnswer("monitor"))),
          Respond(LlmResponse(turnComplete = true)),
        )
      val stopStreaming = stopStreamingTool()
      val (runner, model) = runnerFor(script, tools = listOf(failing, stopStreaming))

      val unused = runner.runUntilTurnComplete()

      model.connections.single().assertAllWaitsMet()
      // A failure while stopping is only logged; c1 gets no answer beyond pending.
      assertThat(model.toolAnswers().filter { it.id == "c1" }.map { it.response })
        .containsExactly(PENDING)
    }
  }

  @Test
  fun runLive_stopStreamingInJavaSpelling_stopsTheTool() {
    runBlocking {
      val cancelled = CompletableDeferred<Unit>()
      var stopBodyRan = false
      val stopStreaming =
        DummyTool(name = "stopStreaming") { _, _ ->
          stopBodyRan = true
          "ran"
        }
      val script =
        listOf(
          Respond(modelFunctionCallResponse("monitor", id = "c1")),
          AwaitSent("the pending answer", answers("c1", PENDING)),
          Respond(
            modelFunctionCallResponse(
              "stopStreaming",
              mapOf("functionName" to "monitor"),
              id = "s1",
            )
          ),
          AwaitSent("the stop answer", answers("s1", stopStreamingAnswer("monitor"))),
          Respond(LlmResponse(turnComplete = true)),
        )
      val (runner, _) = runnerFor(script, tools = listOf(endlessTool(cancelled), stopStreaming))

      val unused = runner.runUntilTurnComplete()

      assertThat(cancelled.isCompleted).isTrue()
      assertThat(stopBodyRan).isFalse()
    }
  }

  @Test
  fun runLive_stopStreamingWithMixedSpelling_runsAsAnOrdinaryTool() {
    runBlocking {
      // Each spelling pairs a tool name with its own argument; a mixed pair is not intercepted.
      val script =
        listOf(
          Respond(modelFunctionCallResponse("monitor", id = "c1")),
          AwaitSent("the pending answer", answers("c1", PENDING)),
          Respond(
            modelFunctionCallResponse(
              "stop_streaming",
              mapOf("functionName" to "monitor"),
              id = "s1",
            )
          ),
          AwaitSent("the tool's own answer", answers("s1", mapOf("result" to "ran"))),
          Respond(LlmResponse(turnComplete = true)),
        )
      val stopStreaming = stopStreamingTool()
      val (runner, model) =
        runnerFor(script, tools = listOf(endlessTool(CompletableDeferred()), stopStreaming))

      val unused = runner.runUntilTurnComplete()

      model.connections.single().assertAllWaitsMet()
    }
  }

  @Test
  fun runLive_stopStreamingWithANonStringName_failsTheRun() {
    runBlocking {
      val script =
        listOf(
          Respond(
            modelFunctionCallResponse("stop_streaming", mapOf("function_name" to 7), id = "s1")
          ),
          Respond(LlmResponse(turnComplete = true)),
        )
      val stopStreaming = stopStreamingTool()
      val (runner, _) = runnerFor(script, tools = listOf(stopStreaming))

      val failure = assertFailsWith<IllegalArgumentException> { runner.runUntilTurnComplete() }

      assertThat(failure)
        .hasMessageThat()
        .isEqualTo("stop_streaming requires a string function_name.")
    }
  }

  @Test
  fun runLive_stopStreamingAFlowThatIgnoresCancellation_stopsItAtItsNextValue() {
    runBlocking {
      // Never checks for cancellation itself, so only the check between values can stop it.
      val busy =
        object : Flow<String> {
          override suspend fun collect(collector: FlowCollector<String>) {
            for (i in 1..50) {
              withContext(NonCancellable) { delay(40.milliseconds) }
              collector.emit("v$i")
            }
          }
        }
      val script =
        listOf(
          Respond(modelFunctionCallResponse("monitor", id = "c1")),
          AwaitSent("the first value", answers("c1", mapOf("result" to "v1"))),
          stopStreamingCall("monitor", "s1"),
          AwaitSent("the stop answer", answers("s1", stopStreamingAnswer("monitor"))),
          Respond(LlmResponse(turnComplete = true)),
        )
      val stopStreaming = stopStreamingTool()
      val (runner, model) =
        runnerFor(
          script,
          tools = listOf(DummyTool(name = "monitor") { _, _ -> busy }, stopStreaming),
        )

      val unused = runner.runUntilTurnComplete()

      val sent = model.connections.single().sent
      val stopAnswer = sent.indexOfFirst { message -> message.toolAnswers().any { it.id == "s1" } }
      assertThat(sent.drop(stopAnswer + 1).flatMap { it.toolAnswers() }.map { it.id })
        .doesNotContain("c1")
    }
  }

  @Test
  fun runLive_callerStopsCollectingWhileAStopWaits_endsWithoutAnsweringTheStop() {
    runBlocking {
      val cancelled = CompletableDeferred<Unit>()
      val stubborn = stubbornTool(cancelled)
      val script =
        listOf(
          Respond(modelFunctionCallResponse("monitor", id = "c1")),
          AwaitSent("the pending answer", answers("c1", PENDING)),
          stopStreamingCall("monitor", "s1"),
        )
      val stopStreaming = stopStreamingTool()
      val (runner, model) = runnerFor(script, tools = listOf(stubborn, stopStreaming))

      val run = launch {
        runner.runLive(USER_ID, SESSION_ID, LiveRequestQueue(), liveConfig()).collect {}
      }
      // Only the stop cancels the tool, so once its cleanup starts the stop is in its wait.
      cancelled.await()
      run.cancelAndJoin()

      assertThat(model.connections.single().sent.flatMap { it.toolAnswers() }.map { it.id })
        .doesNotContain("s1")
    }
  }

  @Test
  fun runLive_stopStreamingBeforeTheStreamStarted_leavesNothingForASecondStop() {
    runBlocking {
      val monitorCall =
        Part(functionCall = FunctionCall(name = "monitor", args = emptyMap(), id = "c1"))
      val stopCall =
        Part(
          functionCall =
            FunctionCall(
              name = "stop_streaming",
              args = mapOf("function_name" to "monitor"),
              id = "s1",
            )
        )
      val none = mapOf("status" to "No active streaming function named monitor found")
      // Both calls in one turn, so the stop lands before the stream's pending answer is queued.
      val script =
        listOf(
          Respond(LlmResponse(content = Content(Role.MODEL, listOf(monitorCall, stopCall)))),
          AwaitSent("the first stop answer", answers("s1", stopStreamingAnswer("monitor"))),
          stopStreamingCall("monitor", "s2"),
          AwaitSent("the second stop answer", answers("s2", none)),
          Respond(LlmResponse(turnComplete = true)),
        )
      val stopStreaming = stopStreamingTool()
      val (runner, model) =
        runnerFor(script, tools = listOf(endlessTool(CompletableDeferred()), stopStreaming))

      val unused = runner.runUntilTurnComplete()

      model.connections.single().assertAllWaitsMet()
    }
  }

  companion object {
    private const val USER_ID = "user"
    private const val SESSION_ID = "session"
    private const val TOOL_CALL_ID = "call-1"
    private val PENDING =
      mapOf("status" to "The function is running asynchronously and the results are pending.")
    private val STOPPED =
      mapOf(
        "status" to
          "monitor stopped streaming when its live session ended; no more updates will arrive."
      )
    private val ALREADY_STREAMING =
      mapOf(
        "status" to
          "monitor is already running with these arguments; wait for its updates " +
            "instead of calling again."
      )
  }
}
