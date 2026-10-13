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

package com.google.adk.kt.agents

import com.google.adk.kt.annotations.ExperimentalLiveApi
import com.google.adk.kt.events.Event
import com.google.adk.kt.models.ContentInput
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.RealtimeInput
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.testing.DummyLiveModel
import com.google.adk.kt.testing.DummyLiveStep
import com.google.adk.kt.testing.DummyLiveStep.AwaitSent
import com.google.adk.kt.testing.DummyLiveStep.Respond
import com.google.adk.kt.testing.DummyTool
import com.google.adk.kt.testing.modelFunctionCallResponse
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Transcription
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/**
 * Covers the live turn loop: sends reach the connection, responses become events, close ends it.
 *
 * Unlike the runner tests, these tests use a real `LlmAgent` because its live flow is what stores
 * the caller's turns; a custom agent would store nothing and pass without exercising that path.
 */
class LlmAgentLiveTest {

  /** One turn answering with [texts], each a whole response. */
  private fun replyingWith(vararg texts: String): List<DummyLiveStep> =
    texts.map { wholeText(it) } + turnComplete()

  private fun wholeText(text: String) =
    Respond(LlmResponse(content = modelMessage(text), partial = false))

  private fun turnComplete() = Respond(LlmResponse(turnComplete = true))

  /** Runs live until [turns] turns have completed, then hangs up as a caller would. */
  private suspend fun runUntilTurnsComplete(
    runner: InMemoryRunner,
    queue: LiveRequestQueue,
    runConfig: RunConfig? = null,
    turns: Int = 1,
  ): List<Event> {
    var completed = 0
    return runner
      .runLive("u", "s", queue, runConfig)
      .onEach { if (it.turnComplete && ++completed == turns) queue.close() }
      .toList()
  }

  private fun clientContents(model: DummyLiveModel) =
    model.connections.single().sent.filterIsInstance<ContentInput>()

  private fun textOf(events: List<Event>) = events.mapNotNull { e ->
    e.content?.parts?.mapNotNull { it.text }?.joinToString()
  }

  private suspend fun persistedEvents(runner: InMemoryRunner) =
    runner.sessionService.getSession(SessionKey(runner.appName, "u", "s"))?.events ?: emptyList()

  private fun runnerFor(model: DummyLiveModel) =
    InMemoryRunner(agent = LlmAgent(name = "replier", model = model))

  /** One spoken chunk followed by the turn boundary, which is what triggers a recording flush. */
  private suspend fun runOneSpokenTurn(runConfig: RunConfig? = null): List<Event> {
    val audio = Blob(mimeType = "audio/pcm;rate=24000", data = ByteArray(960))
    val script =
      listOf(Respond(LlmResponse(content = modelMessage(Part(inlineData = audio)))), turnComplete())
    return runUntilTurnsComplete(runnerFor(DummyLiveModel(script)), LiveRequestQueue(), runConfig)
  }

  @Test
  fun runLive_inputTranscription_isAuthoredAsTheUser(): Unit = runBlocking {
    // Speech is the user's too; runLive_persistsTheUsersTurnAuthoredAsTheUser covers typed input.
    val model =
      DummyLiveModel(
        listOf(
          Respond(
            LlmResponse(
              inputTranscription = Transcription(text = "what is the weather", finished = true),
              partial = false,
            )
          ),
          turnComplete(),
        )
      )
    val runner = runnerFor(model)

    val events = runUntilTurnsComplete(runner, LiveRequestQueue())

    val transcribed = events.filter { it.inputTranscription != null }
    assertTrue(
      transcribed.isNotEmpty(),
      "no transcription reached the caller (${events.size} events)",
    )
    for (event in transcribed) {
      assertEquals(
        "user",
        event.author,
        "a transcript of what the caller said was attributed to the agent",
      )
    }
  }

  @Test
  fun runLive_persistsBothSidesOfTheConversationInOrder(): Unit = runBlocking {
    // Rebuilt history needs the user's side too, or the agent's replies answer nothing.
    val model =
      DummyLiveModel(
        listOf(
          // Wait for the user's turn, so the fake model cannot reply before it is sent.
          AwaitSent("the user's turn") { it is ContentInput },
          wholeText("sunny"),
          turnComplete(),
        )
      )
    val runner = runnerFor(model)
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("what is the weather"))

    val unused = runUntilTurnsComplete(runner, queue)

    val transcript = persistedEvents(runner).mapNotNull { it.content?.parts?.firstOrNull()?.text }
    assertEquals(
      listOf("what is the weather", "sunny"),
      transcript,
      "the session should hold both halves, in the order they happened",
    )
    model.connections.forEach { it.assertAllWaitsMet() }
  }

  @Test
  fun runLive_persistsTheUsersTurnAuthoredAsTheUser(): Unit = runBlocking {
    val runner = runnerFor(DummyLiveModel(replyingWith("sunny")))
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("hello"))

    val unused = runUntilTurnsComplete(runner, queue)

    val stored = persistedEvents(runner).first { it.content?.parts?.firstOrNull()?.text == "hello" }
    assertEquals("user", stored.author, "the caller's own turn was attributed to the agent")
  }

  @Test
  fun runLive_spokenTurn_persistsNoUserContentEvent(): Unit = runBlocking {
    // saveLiveBlob's audio cache records speech; storing it here too would keep it twice.
    val runner = runnerFor(DummyLiveModel(replyingWith("sunny")))
    val queue = LiveRequestQueue()
    queue.sendRealtime(RealtimeInput.Audio(Blob(mimeType = "audio/pcm", data = ByteArray(4))))

    val unused = runUntilTurnsComplete(runner, queue)

    val stored = persistedEvents(runner)
    assertTrue(
      stored.none { it.author == "user" },
      "a spoken turn must not also be stored as a user turn (${stored.size} stored)",
    )
  }

  @Test
  fun runLive_partialContent_isNotPersisted(): Unit = runBlocking {
    // A partial is superseded by the whole, so storing it would leave a half-sentence in history.
    val runner = runnerFor(DummyLiveModel(replyingWith("sunny")))
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("half a th"), partial = true)

    val unused = runUntilTurnsComplete(runner, queue)

    assertTrue(
      persistedEvents(runner).none { it.author == "user" },
      "a partial turn should not be stored",
    )
  }

  @Test
  fun runLive_partialContent_reachesTheConnectionAsAnOpenTurn(): Unit = runBlocking {
    // Send-side tests drive the connection directly, so this pins the turn loop's forwarding.
    val model = DummyLiveModel(replyingWith("sunny"))
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("half a th"), partial = true)

    val unused = runUntilTurnsComplete(runnerFor(model), queue)

    assertEquals(listOf(true), clientContents(model).map { it.partial })
  }

  @Test
  fun runLive_completeContent_reachesTheConnectionAsAClosedTurn(): Unit = runBlocking {
    // The control: without it, forwarding that always sent `true` would pass.
    val model = DummyLiveModel(replyingWith("sunny"))
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("a whole thought"))

    val unused = runUntilTurnsComplete(runnerFor(model), queue)

    assertEquals(listOf(false), clientContents(model).map { it.partial })
  }

  @Test
  fun runLive_turnsModelResponsesIntoEvents(): Unit = runBlocking {
    val model = DummyLiveModel(replyingWith("hello there"))
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("hi"))

    val events = runUntilTurnsComplete(runnerFor(model), queue)

    assertTrue(
      textOf(events).any { it.contains("hello there") },
      "expected the model's text (${events.size} events)",
    )
  }

  @Test
  fun runLive_sendsQueuedContentToTheConnection(): Unit = runBlocking {
    val model = DummyLiveModel(replyingWith("ok"))
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("what is the weather"))

    val unused = runUntilTurnsComplete(runnerFor(model), queue)

    val sentTexts =
      clientContents(model).map { sent -> sent.content.parts.mapNotNull { it.text }.joinToString() }
    assertEquals(listOf("what is the weather"), sentTexts)
  }

  @Test
  fun runLive_contentWithNoRole_reachesTheModelAsUser(): Unit = runBlocking {
    // The model must get the settled role too, not only the stored copy, as in Python.
    val model = DummyLiveModel(replyingWith("ok"))
    val queue = LiveRequestQueue()
    queue.sendContent(Content(parts = listOf(Part(text = "hi"))))

    val unused = runUntilTurnsComplete(runnerFor(model), queue)

    assertEquals("user", clientContents(model).single().content.role)
  }

  @Test
  fun runLive_functionResponse_reachesTheModelWithItsRoleUnset(): Unit = runBlocking {
    // A function response keeps its role unset on the wire, as Python guards it too.
    val model = DummyLiveModel(replyingWith("ok"))
    val queue = LiveRequestQueue()
    queue.sendContent(
      Content(
        parts =
          listOf(
            Part(functionResponse = FunctionResponse(name = "f", response = mapOf(), id = "call-1"))
          )
      )
    )

    val unused = runUntilTurnsComplete(runnerFor(model), queue)

    assertNull(clientContents(model).single().content.role)
  }

  @Test
  fun runLive_sendsRealtimeInputToTheConnection(): Unit = runBlocking {
    val model = DummyLiveModel(replyingWith("ok"))
    val queue = LiveRequestQueue()
    queue.sendRealtime(
      RealtimeInput.Audio(Blob(mimeType = "audio/pcm;rate=16000", data = byteArrayOf(1)))
    )
    queue.sendActivityEnd()

    val unused = runUntilTurnsComplete(runnerFor(model), queue)

    val sentRealtime = model.connections.single().sent.filterIsInstance<RealtimeInput>()
    assertEquals(RealtimeInput.ActivityEnd, sentRealtime.last())
    assertTrue(sentRealtime.first() is RealtimeInput.Audio)
  }

  @Test
  fun runLive_closesTheConnectionWhenTheRunEnds(): Unit = runBlocking {
    val model = DummyLiveModel(replyingWith("ok"))
    val queue = LiveRequestQueue()
    queue.close()

    val unused = runnerFor(model).runLive("u", "s", queue).toList()

    assertTrue(model.connections.single().isClosed, "the connection must not be left open")
  }

  @Test
  fun runLive_readsSeveralTurnsOnOneConnection(): Unit = runBlocking {
    // The loop re-enters after each boundary, so a second turn arrives on the same connection.
    val script = listOf(wholeText("first"), turnComplete(), wholeText("second"), turnComplete())
    val model = DummyLiveModel(script)

    val events = runUntilTurnsComplete(runnerFor(model), LiveRequestQueue(), turns = 2)
    val text = textOf(events).joinToString(" ")

    assertEquals(1, model.connections.size, "both turns should arrive on one connection")
    assertTrue(text.contains("first"), "expected turn 1 in ${text.length} characters of text")
    assertTrue(text.contains("second"), "expected turn 2 in ${text.length} characters of text")
  }

  @Test
  fun runLive_sendsAToolResponseBackToTheConnection(): Unit = runBlocking {
    // The model must receive the tool answer; Gemini 3.x otherwise withholds turn completion.
    val model =
      DummyLiveModel(
        listOf(
          Respond(modelFunctionCallResponse("get_weather")),
          AwaitSent("a tool response") { sent ->
            sent is ContentInput &&
              sent.content.parts.isNotEmpty() &&
              sent.content.parts.all { it.functionResponse != null }
          },
          turnComplete(),
        )
      )
    val agent =
      LlmAgent(
        name = "live_agent",
        model = model,
        tools = listOf(DummyTool(name = "get_weather") { _, _ -> mapOf("tempF" to 72) }),
      )

    // The answer goes out through the caller's queue, so the queue stays open until the turn ends.
    val unused = runUntilTurnsComplete(InMemoryRunner(agent = agent), LiveRequestQueue())

    val answered =
      clientContents(model).flatMap { it.content.parts }.mapNotNull { it.functionResponse?.name }
    assertEquals(listOf("get_weather"), answered, "expected exactly one tool response")
    model.connections.forEach { it.assertAllWaitsMet() }
  }

  @Test
  fun runLive_withSaveLiveBlob_writesTheTurnsAudioToAnArtifact(): Unit = runBlocking {
    val events = runOneSpokenTurn(RunConfig(saveLiveBlob = true))

    val references = events.mapNotNull { it.content?.parts?.singleOrNull()?.fileData?.fileUri }
    assertEquals(
      1,
      references.size,
      "the turn's audio should be written once (${events.size} events)",
    )
    assertTrue(
      references.single().contains("adk_live_audio_storage_output_audio_"),
      "unexpected artifact reference",
    )
  }

  @Test
  fun runLive_withoutSaveLiveBlob_keepsNoRecording(): Unit = runBlocking {
    // The flag is the whole opt-in: unset, nothing is cached and nothing is written.
    val events = runOneSpokenTurn()

    assertTrue(
      events.none { event -> event.content?.parts?.any { it.fileData != null } == true },
      "no recording should be written when the flag is unset (${events.size} events)",
    )
  }

  @Test
  fun runLive_withoutSaveLiveBlob_deliversTheSpokenAudioEvent(): Unit = runBlocking {
    // Proves the fixture produced audio, so a missing recording comes from the unset flag.
    val events = runOneSpokenTurn()

    assertTrue(
      events.any { event -> event.content?.parts?.any { it.inlineData != null } == true },
      "the model's audio must reach the caller when saveLiveBlob is unset (${events.size} events)",
    )
  }

  @Test
  fun runLive_withSaveLiveBlob_emitsTheRecordingBeforeTheControlEventAndLeavesItIntact(): Unit =
    runBlocking {
      // The recording must come after the audio and before the turn boundary.
      val events = runOneSpokenTurn(RunConfig(saveLiveBlob = true))

      val audio = events.indexOfFirst { e ->
        e.content?.parts?.any { it.inlineData != null } == true
      }
      val recording = events.indexOfFirst { e ->
        e.content?.parts?.any { it.fileData != null } == true
      }
      val control = events.indexOfFirst { it.turnComplete }

      assertTrue(audio >= 0, "saveLiveBlob removed the model's audio (${events.size} events)")
      assertTrue(recording >= 0, "saveLiveBlob emitted no recording (${events.size} events)")
      assertTrue(
        control >= 0,
        "saveLiveBlob consumed the turnComplete that flushed it (${events.size} events)",
      )
      assertTrue(
        audio < recording && recording < control,
        "expected audio < recording < control, got $audio, $recording, $control",
      )
    }

  @Test
  fun runLive_passesTheLiveConfigThroughToConnect(): Unit = runBlocking {
    val model = DummyLiveModel(replyingWith("ok"))
    val agent =
      LlmAgent(
        name = "live_agent",
        model = model,
        staticInstruction = Content(parts = listOf(Part(text = "Answer briefly."))),
      )
    val queue = LiveRequestQueue()
    queue.close()

    val unused = InMemoryRunner(agent = agent).runLive("u", "s", queue).toList()

    // The request the processors built is what reaches connect, not a fresh empty one.
    assertEquals(
      "Answer briefly.",
      model.connectRequests.single().config.systemInstruction?.parts?.firstOrNull()?.text,
    )
  }
}
