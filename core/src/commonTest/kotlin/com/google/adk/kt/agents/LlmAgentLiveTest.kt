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
import com.google.adk.kt.models.RealtimeInput
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.testing.DummyLiveModel
import com.google.adk.kt.testing.DummyLiveStep
import com.google.adk.kt.testing.DummyLiveStep.AwaitSent
import com.google.adk.kt.testing.DummyLiveStep.Respond
import com.google.adk.kt.testing.audioFrame
import com.google.adk.kt.testing.inputTranscription
import com.google.adk.kt.testing.storedEvents
import com.google.adk.kt.testing.turnCompleteStep
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.testing.wholeText
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import kotlin.test.Test
import kotlin.test.assertEquals
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
    texts.map { wholeText(it) } + turnCompleteStep()

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

  private fun runnerFor(model: DummyLiveModel) =
    InMemoryRunner(agent = LlmAgent(name = "replier", model = model))

  /** One spoken chunk followed by the turn boundary, which is what triggers a recording flush. */
  private suspend fun runOneSpokenTurn(runConfig: RunConfig? = null): List<Event> {
    val script = listOf(audioFrame(), turnCompleteStep())
    return runUntilTurnsComplete(runnerFor(DummyLiveModel(script)), LiveRequestQueue(), runConfig)
  }

  @Test
  fun runLive_inputTranscription_isAuthoredAsTheUser(): Unit = runBlocking {
    // Speech is the user's too; runLive_persistsTheUsersTurnAuthoredAsTheUser covers typed input.
    val model =
      DummyLiveModel(
        listOf(
          Respond(inputTranscription("what is the weather", finished = true)),
          turnCompleteStep(),
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
          turnCompleteStep(),
        )
      )
    val runner = runnerFor(model)
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("what is the weather"))

    val unused = runUntilTurnsComplete(runner, queue)

    val transcript = runner.storedEvents().mapNotNull { it.content?.parts?.firstOrNull()?.text }
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

    val stored = runner.storedEvents().first { it.content?.parts?.firstOrNull()?.text == "hello" }
    assertEquals("user", stored.author, "the caller's own turn was attributed to the agent")
  }

  @Test
  fun runLive_spokenTurn_persistsNoUserContentEvent(): Unit = runBlocking {
    // saveLiveBlob's audio cache records speech; storing it here too would keep it twice.
    val runner = runnerFor(DummyLiveModel(replyingWith("sunny")))
    val queue = LiveRequestQueue()
    queue.sendRealtime(RealtimeInput.Audio(Blob(mimeType = "audio/pcm", data = ByteArray(4))))

    val unused = runUntilTurnsComplete(runner, queue)

    val stored = runner.storedEvents()
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
      runner.storedEvents().none { it.author == "user" },
      "a partial turn should not be stored",
    )
  }

  @Test
  fun runLive_completeContent_reachesTheConnectionAsAClosedTurn(): Unit = runBlocking {
    // No LlmAgentTurnTest case checks partial=false on the wire.
    val model = DummyLiveModel(replyingWith("sunny"))
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("a whole thought"))

    val unused = runUntilTurnsComplete(runnerFor(model), queue)

    assertEquals(listOf(false), clientContents(model).map { it.partial })
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
  fun runLive_readsSeveralTurnsOnOneConnection(): Unit = runBlocking {
    // The loop re-enters after each boundary, so a second turn arrives on the same connection.
    val script =
      listOf(wholeText("first"), turnCompleteStep(), wholeText("second"), turnCompleteStep())
    val model = DummyLiveModel(script)

    val events = runUntilTurnsComplete(runnerFor(model), LiveRequestQueue(), turns = 2)
    val text = textOf(events).joinToString(" ")

    assertEquals(1, model.connections.size, "both turns should arrive on one connection")
    assertTrue("first" in text, "expected turn 1 in ${text.length} characters of text")
    assertTrue("second" in text, "expected turn 2 in ${text.length} characters of text")
  }

  @Test
  fun runLive_withSaveLiveBlob_writesTheTurnsAudioToAnArtifact(): Unit = runBlocking {
    val events = runOneSpokenTurn(RunConfig(live = LiveRunConfig(saveLiveBlob = true)))

    val references = events.mapNotNull { it.content?.parts?.singleOrNull()?.fileData?.fileUri }
    assertEquals(
      1,
      references.size,
      "the turn's audio should be written once (${events.size} events)",
    )
    assertTrue(
      "adk_live_audio_storage_output_audio_" in references.single(),
      "unexpected artifact reference",
    )
  }

  @Test
  fun runLive_withoutSaveLiveBlob_deliversTheSpokenAudioEvent(): Unit = runBlocking {
    val events = runOneSpokenTurn()

    assertTrue(
      events.any { event -> event.content?.parts?.any { it.inlineData != null } == true },
      "the model's audio must reach the caller when saveLiveBlob is unset (${events.size} events)",
    )
  }

  @Test
  fun runLive_withSaveLiveBlob_emitsTheRecordingAfterTheControlEventAndLeavesBothIntact(): Unit =
    runBlocking {
      // The turn boundary goes out before the save, so the recording comes after it.
      val events = runOneSpokenTurn(RunConfig(live = LiveRunConfig(saveLiveBlob = true)))

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
        audio < control && control < recording,
        "expected audio < control < recording, got $audio, $control, $recording",
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
