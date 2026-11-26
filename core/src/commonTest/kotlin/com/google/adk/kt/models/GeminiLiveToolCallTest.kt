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

@file:OptIn(ExperimentalLiveApi::class, ExperimentalWorkflowApi::class)

package com.google.adk.kt.models

import com.google.adk.kt.agents.AgentMode
import com.google.adk.kt.agents.BaseAgent
import com.google.adk.kt.agents.LiveRequestQueue
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.SequentialAgent
import com.google.adk.kt.annotations.ExperimentalLiveApi
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.apps.App
import com.google.adk.kt.callbacks.AfterModelCallback
import com.google.adk.kt.callbacks.BeforeModelCallback
import com.google.adk.kt.callbacks.CallbackChoice
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.testing.DummyLiveModel
import com.google.adk.kt.testing.DummyLiveStep
import com.google.adk.kt.testing.DummyTool
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.tools.TaskCompletedTool.Companion.TASK_COMPLETED_TOOL_NAME
import com.google.adk.kt.tools.TransferToAgentTool.Companion.TRANSFER_TO_AGENT_TOOL_NAME
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.GroundingMetadata
import com.google.adk.kt.workflow.Edge
import com.google.adk.kt.workflow.Start
import com.google.adk.kt.workflow.Workflow
import com.google.adk.kt.workflow.node
import com.google.common.truth.Truth.assertThat
import com.google.genai.kotlin.types.ActivityEnd as SdkActivityEnd
import com.google.genai.kotlin.types.ActivityStart as SdkActivityStart
import com.google.genai.kotlin.types.Blob as SdkBlob
import com.google.genai.kotlin.types.Content as SdkContent
import com.google.genai.kotlin.types.FunctionCall as SdkFunctionCall
import com.google.genai.kotlin.types.FunctionResponse as SdkFunctionResponse
import com.google.genai.kotlin.types.GroundingMetadata as SdkGroundingMetadata
import com.google.genai.kotlin.types.LiveServerContent as SdkLiveServerContent
import com.google.genai.kotlin.types.LiveServerMessage as SdkLiveServerMessage
import com.google.genai.kotlin.types.LiveServerToolCall as SdkLiveServerToolCall
import com.google.genai.kotlin.types.Part as SdkPart
import com.google.genai.kotlin.types.Transcription as SdkTranscription
import com.google.genai.kotlin.types.UsageMetadata as SdkLiveUsageMetadata
import kotlin.test.Test
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive

private const val MODEL = "gemini-live-test"
private const val GEMINI_25_LIVE = "gemini-2.5-flash-live"

/**
 * Tests when a live connection hands a tool call to its caller.
 *
 * Each tool call is delivered as its frame arrives, carrying the turn's grounding, so a caller can
 * answer it without waiting for the turn to complete. The `runLive` tests check what a run then
 * stores of the speech around the call.
 */
class GeminiLiveToolCallTest {

  private class ScriptedSession(private val frames: List<SdkLiveServerMessage>) :
    LiveSessionHandle {
    override fun receive(): Flow<SdkLiveServerMessage> = frames.asFlow()

    override suspend fun sendClientContent(turns: List<SdkContent>, turnComplete: Boolean) {}

    override suspend fun sendRealtimeInput(
      audio: SdkBlob?,
      video: SdkBlob?,
      audioStreamEnd: Boolean?,
      text: String?,
      activityStart: SdkActivityStart?,
      activityEnd: SdkActivityEnd?,
    ) {}

    override suspend fun sendToolResponse(functionResponses: List<SdkFunctionResponse>) {}

    override suspend fun closeSession() {}
  }

  private fun toolCallFrame(vararg names: String) =
    SdkLiveServerMessage(
      toolCall = SdkLiveServerToolCall(functionCalls = names.map { SdkFunctionCall(name = it) })
    )

  private fun turnCompleteFrame() =
    SdkLiveServerMessage(serverContent = SdkLiveServerContent(turnComplete = true))

  private fun speechFrame(text: String) =
    SdkLiveServerMessage(
      serverContent =
        SdkLiveServerContent(
          modelTurn = SdkContent(role = "model", parts = listOf(SdkPart(text = text)))
        )
    )

  private fun thoughtFrame(text: String) =
    SdkLiveServerMessage(
      serverContent =
        SdkLiveServerContent(
          modelTurn =
            SdkContent(role = "model", parts = listOf(SdkPart(text = text, thought = true)))
        )
    )

  /** Replays [frames] into the shared channel, then stays open like a real socket. */
  private class ParkingSession(private val frames: List<SdkLiveServerMessage>) : LiveSessionHandle {
    override fun receive(): Flow<SdkLiveServerMessage> = flow {
      for (frame in frames) emit(frame)
      awaitCancellation()
    }

    override suspend fun sendClientContent(turns: List<SdkContent>, turnComplete: Boolean) {}

    override suspend fun sendRealtimeInput(
      audio: SdkBlob?,
      video: SdkBlob?,
      audioStreamEnd: Boolean?,
      text: String?,
      activityStart: SdkActivityStart?,
      activityEnd: SdkActivityEnd?,
    ) {}

    override suspend fun sendToolResponse(functionResponses: List<SdkFunctionResponse>) {}

    override suspend fun closeSession() {}
  }

  private suspend fun collect(model: String, frames: List<SdkLiveServerMessage>) =
    GeminiLiveConnection(ScriptedSession(frames), modelVersion = model).receive().toList()

  private fun toolNamesOf(response: LlmResponse) =
    response.content?.parts.orEmpty().mapNotNull { it.functionCall?.name }

  private fun groundingFrame() =
    SdkLiveServerMessage(
      serverContent =
        SdkLiveServerContent(
          groundingMetadata = SdkGroundingMetadata(webSearchQueries = listOf("q"))
        )
    )

  private fun assertGroundingOnTheCallOnly(
    responses: List<LlmResponse>,
    turnCompleteGrounding: GroundingMetadata? = null,
  ) {
    val call = responses.single { toolNamesOf(it).isNotEmpty() }
    assertThat(call.groundingMetadata?.webSearchQueries).containsExactly("q")
    assertThat(responses.single { it.turnComplete == true }.groundingMetadata)
      .isEqualTo(turnCompleteGrounding)
  }

  @Test
  fun receive_groundingBeforeAToolCall_goesWithTheCallNotTheTurnComplete(): Unit = runBlocking {
    val responses =
      collect(
        GEMINI_25_LIVE,
        listOf(groundingFrame(), toolCallFrame("GetWeather"), turnCompleteFrame()),
      )

    assertGroundingOnTheCallOnly(responses)
  }

  @Test
  fun receive_groundingWithoutAToolCall_reachesTheTurnComplete(): Unit = runBlocking {
    val responses = collect(MODEL, listOf(groundingFrame(), turnCompleteFrame()))

    assertThat(responses.single { it.turnComplete == true }.groundingMetadata?.webSearchQueries)
      .containsExactly("q")
  }

  @Test
  fun receive_groundingAfterAToolCall_goesWithTheTurnComplete(): Unit = runBlocking {
    val responses =
      collect(
        GEMINI_25_LIVE,
        listOf(toolCallFrame("GetWeather"), groundingFrame(), turnCompleteFrame()),
      )

    assertThat(responses.single { toolNamesOf(it).isNotEmpty() }.groundingMetadata).isNull()
    assertThat(responses.single { it.turnComplete == true }.groundingMetadata?.webSearchQueries)
      .containsExactly("q")
  }

  @Test
  fun receive_groundingOnTheTurnCompleteFrame_isWhatTheTurnCompleteCarries(): Unit = runBlocking {
    val turnCompleteWithGrounding =
      SdkLiveServerMessage(
        serverContent =
          SdkLiveServerContent(
            turnComplete = true,
            groundingMetadata = SdkGroundingMetadata(webSearchQueries = listOf("tc")),
          )
      )

    val responses = collect(GEMINI_25_LIVE, listOf(groundingFrame(), turnCompleteWithGrounding))

    assertThat(responses.single { it.turnComplete == true }.groundingMetadata?.webSearchQueries)
      .containsExactly("tc")
  }

  @Test
  fun receive_streamEndsWithoutTurnComplete_stillDeliversTheCall(): Unit = runBlocking {
    // A session ending mid-turn still delivers the call it already received.
    val responses = collect(GEMINI_25_LIVE, listOf(toolCallFrame("GetWeather")))

    assertThat(responses.flatMap(::toolNamesOf)).containsExactly("GetWeather")
  }

  @Test
  fun receive_turnCompletes_deliversToolCallsBeforeTheTurnCompleteResponse(): Unit = runBlocking {
    // Indices, not presence: the broken ordering also has both responses.
    val responses =
      collect(GEMINI_25_LIVE, listOf(toolCallFrame("GetWeather"), turnCompleteFrame()))

    val toolCallIndex = responses.indexOfFirst { toolNamesOf(it).isNotEmpty() }
    val turnCompleteIndex = responses.indexOfFirst { it.turnComplete == true }
    assertThat(toolCallIndex).isAtLeast(0)
    assertThat(turnCompleteIndex).isAtLeast(0)
    assertThat(toolCallIndex).isLessThan(turnCompleteIndex)
  }

  @Test
  fun receive_speechThenToolCall_keepsBoth(): Unit = runBlocking {
    val responses =
      collect(
        GEMINI_25_LIVE,
        listOf(speechFrame("checking"), toolCallFrame("GetWeather"), turnCompleteFrame()),
      )

    assertThat(responses.mapNotNull { it.content?.parts?.firstOrNull()?.text }).contains("checking")
    assertThat(responses.flatMap(::toolNamesOf)).containsExactly("GetWeather")
  }

  @Test
  fun receive_collectionAbandoned_callIsNotReplayedInNextTurn(): Unit = runBlocking {
    // The receive-path aggregates are local to each receive(), so an abandoned turn leaves none.
    val connection =
      GeminiLiveConnection(
        ParkingSession(listOf(toolCallFrame("Stale"), speechFrame("x"), turnCompleteFrame())),
        modelVersion = GEMINI_25_LIVE,
      )
    try {
      // The caller stops reading turn 1 early, as a cancelled collector would; the call arrived in
      // it.
      assertThat(connection.receive().take(1).toList().flatMap(::toolNamesOf))
        .containsExactly("Stale")

      val turn2 = withTimeout(5_000) { connection.receive().toList() }

      assertThat(turn2.flatMap(::toolNamesOf)).isEmpty()
    } finally {
      connection.close()
    }
  }

  @Test
  fun receive_collectionAbandonedAfterGrounding_groundingIsNotCarriedIntoNextTurn(): Unit =
    runBlocking {
      val connection =
        GeminiLiveConnection(
          ParkingSession(listOf(groundingFrame(), speechFrame("x"), turnCompleteFrame())),
          modelVersion = GEMINI_25_LIVE,
        )
      try {
        assertThat(connection.receive().take(1).toList()).hasSize(1)

        val turn2 = withTimeout(5_000) { connection.receive().toList() }

        assertThat(turn2.single { it.turnComplete == true }.groundingMetadata).isNull()
      } finally {
        connection.close()
      }
    }

  @Test
  fun receive_interruptedAfterGrounding_carriesTheGroundingNotTheTurnComplete(): Unit =
    runBlocking {
      // Python hands the turn's grounding to the interrupted response and clears it.
      val interrupted =
        SdkLiveServerMessage(serverContent = SdkLiveServerContent(interrupted = true))

      val responses =
        collect(GEMINI_25_LIVE, listOf(groundingFrame(), interrupted, turnCompleteFrame()))

      assertThat(responses.single { it.interrupted == true }.groundingMetadata?.webSearchQueries)
        .containsExactly("q")
      assertThat(responses.single { it.turnComplete == true }.groundingMetadata).isNull()
    }

  @Test
  fun receive_interruptedFrameWithTranscript_flushesTheTranscriptFirst(): Unit = runBlocking {
    // Python flushes transcripts before the interrupted response.
    val frame =
      SdkLiveServerMessage(
        serverContent =
          SdkLiveServerContent(
            outputTranscription = SdkTranscription(text = "half"),
            interrupted = true,
          )
      )

    val responses = collect(GEMINI_25_LIVE, listOf(frame))

    val chunkIndex = responses.indexOfFirst { it.outputTranscription?.finished == false }
    val aggregateIndex = responses.indexOfFirst { it.outputTranscription?.finished == true }
    val interruptedIndex = responses.indexOfFirst { it.interrupted == true }
    assertThat(chunkIndex).isAtLeast(0)
    assertThat(aggregateIndex).isGreaterThan(chunkIndex)
    assertThat(interruptedIndex).isGreaterThan(aggregateIndex)
  }

  @Test
  fun receive_toolCallFrameWithUsage_emitsUsageThenTheToolCall(): Unit = runBlocking {
    // usageMetadata sits outside the server-message oneof, so it can share a tool-call frame.
    val frame =
      SdkLiveServerMessage(
        toolCall =
          SdkLiveServerToolCall(functionCalls = listOf(SdkFunctionCall(name = "GetWeather"))),
        usageMetadata = SdkLiveUsageMetadata(responseTokenCount = 5),
      )

    val responses = collect(MODEL, listOf(frame))

    val usageIndex = responses.indexOfFirst { it.usageMetadata != null }
    val toolCallIndex = responses.indexOfFirst { toolNamesOf(it).isNotEmpty() }
    assertThat(usageIndex).isAtLeast(0)
    assertThat(toolCallIndex).isGreaterThan(usageIndex)
  }

  @Test
  fun receive_textThenToolCallWithUsage_leadsWithUsageBeforeTheFullText(): Unit = runBlocking {
    // The tool-call frame's usage leads, then the aggregated text is flushed for the call, as ADK
    // Python's receive() in gemini_llm_connection.py yields usage before the call's text.
    val toolCallWithUsage =
      SdkLiveServerMessage(
        toolCall =
          SdkLiveServerToolCall(functionCalls = listOf(SdkFunctionCall(name = "GetWeather"))),
        usageMetadata = SdkLiveUsageMetadata(responseTokenCount = 5),
      )

    val responses = collect(MODEL, listOf(speechFrame("partial answer"), toolCallWithUsage))

    val usageIndex = responses.indexOfFirst { it.usageMetadata != null }
    val fullTextIndex = responses.indexOfFirst {
      !it.partial && it.content?.parts?.any { p -> p.text == "partial answer" } == true
    }
    assertThat(usageIndex).isAtLeast(0)
    assertThat(fullTextIndex).isGreaterThan(usageIndex)
  }

  @Test
  fun receive_interruptedTurnCompleteFrameWithParts_emitsTheFullTextBeforeTheTurnComplete(): Unit =
    runBlocking {
      // The frame's text is aggregated into one non-partial full text with interrupted=true and the
      // turn's grounding, delivered before turn-complete (which also carries the grounding).
      val frame =
        SdkLiveServerMessage(
          serverContent =
            SdkLiveServerContent(
              modelTurn = SdkContent(role = "model", parts = listOf(SdkPart(text = "x"))),
              groundingMetadata = SdkGroundingMetadata(webSearchQueries = listOf("q")),
              interrupted = true,
              turnComplete = true,
            )
        )

      val responses = collect(GEMINI_25_LIVE, listOf(frame))

      val fullText = responses.single {
        !it.partial && it.content?.parts?.any { p -> p.text == "x" } == true
      }
      assertThat(fullText.interrupted).isTrue()
      assertThat(fullText.groundingMetadata?.webSearchQueries).containsExactly("q")
      val turnCompleteIndex = responses.indexOfFirst { it.turnComplete == true }
      assertThat(responses.indexOf(fullText)).isLessThan(turnCompleteIndex)
      assertThat(responses.single { it.turnComplete == true }.groundingMetadata?.webSearchQueries)
        .containsExactly("q")
    }

  @Test
  fun receive_interruptedFrameWithText_flushesTheFullTextAfterTheTranscript(): Unit = runBlocking {
    // ADK Python flushes transcripts before the interrupted full text, so the full text follows the
    // transcript of the same frame.
    val frame =
      SdkLiveServerMessage(
        serverContent =
          SdkLiveServerContent(
            modelTurn = SdkContent(role = "model", parts = listOf(SdkPart(text = "x"))),
            outputTranscription = SdkTranscription(text = "half"),
            interrupted = true,
          )
      )

    val responses = collect(GEMINI_25_LIVE, listOf(frame))

    val fullText = responses.single {
      !it.partial && it.content?.parts?.any { p -> p.text == "x" } == true
    }
    assertThat(fullText.interrupted).isTrue()
    val transcriptIndex = responses.indexOfFirst { it.outputTranscription != null }
    assertThat(transcriptIndex).isAtLeast(0)
    assertThat(responses.indexOf(fullText)).isGreaterThan(transcriptIndex)
  }

  @Test
  fun receive_groundingInterruptedFrame_deliversGroundingThenTheMarkerCarriesIt(): Unit =
    runBlocking {
      // The early-grounding response carries G in place; the bare marker then carries the merged
      // grounding - the branch must hold only the marker, not overwrite it with the bare one.
      val frame =
        SdkLiveServerMessage(
          serverContent =
            SdkLiveServerContent(
              groundingMetadata = SdkGroundingMetadata(webSearchQueries = listOf("q")),
              interrupted = true,
            )
        )

      val responses = collect(GEMINI_25_LIVE, listOf(frame))

      assertThat(responses).hasSize(2)
      assertThat(responses.first().groundingMetadata?.webSearchQueries).containsExactly("q")
      assertThat(responses.last().groundingMetadata?.webSearchQueries).containsExactly("q")
    }

  @Test
  fun mergeGroundingMetadata_listFieldAbsentInBoth_staysNull() {
    val merged =
      mergeGroundingMetadata(
        GroundingMetadata(webSearchQueries = listOf("a")),
        GroundingMetadata(webSearchQueries = listOf("b")),
      )

    // retrievalQueries is absent in both inputs, so it must stay null, not become [].
    assertThat(merged?.retrievalQueries).isNull()
    assertThat(merged?.webSearchQueries).containsExactly("a", "b")
  }

  @Test
  fun receive_inputTranscriptionAcrossTwoTurns_aggregatesEachSeparately(): Unit = runBlocking {
    // The input transcript must reset at each turn's flush; otherwise turn 2 carries turn 1's text.
    val connection =
      GeminiLiveConnection(
        ParkingSession(
          listOf(inputFrame("one"), turnCompleteFrame(), inputFrame("two"), turnCompleteFrame())
        ),
        modelVersion = GEMINI_25_LIVE,
      )
    try {
      val turn1 = withTimeout(5_000) { connection.receive().toList() }
      val turn2 = withTimeout(5_000) { connection.receive().toList() }

      assertThat(
          turn1.mapNotNull { it.inputTranscription?.takeIf { t -> t.finished == true }?.text }
        )
        .containsExactly("one")
      assertThat(
          turn2.mapNotNull { it.inputTranscription?.takeIf { t -> t.finished == true }?.text }
        )
        .containsExactly("two")
    } finally {
      connection.close()
    }
  }

  @Test
  fun receive_groundingWithRetrievalQueriesNoChunks_stillReachesTheTurnComplete(): Unit =
    runBlocking {
      // Exercises the grounding-warn branch (retrieval queries, no chunks); grounding still lands.
      val grounding =
        SdkLiveServerMessage(
          serverContent =
            SdkLiveServerContent(
              groundingMetadata = SdkGroundingMetadata(retrievalQueries = listOf("q"))
            )
        )

      val responses = collect(GEMINI_25_LIVE, listOf(grounding, turnCompleteFrame()))

      assertThat(responses.single { it.turnComplete == true }.groundingMetadata?.retrievalQueries)
        .containsExactly("q")
    }

  @Test
  fun receive_interruptedAudioFrameWithNoPendingText_emitsNoBareMarker(): Unit = runBlocking {
    // Kept divergence from ADK Python: an interrupted audio-only frame (no pending text) yields its
    // audio content response only, with no extra bare marker (which would save the audio twice).
    val frame =
      SdkLiveServerMessage(
        serverContent =
          SdkLiveServerContent(
            modelTurn =
              SdkContent(
                role = "model",
                parts =
                  listOf(
                    SdkPart(inlineData = SdkBlob(data = byteArrayOf(1, 2), mimeType = "audio/pcm"))
                  ),
              ),
            interrupted = true,
          )
      )

    val responses = collect(GEMINI_25_LIVE, listOf(frame))

    // The audio content response is delivered (interrupted); no separate bare marker follows.
    assertThat(
        responses.any {
          it.interrupted && it.content?.parts?.any { p -> p.inlineData != null } == true
        }
      )
      .isTrue()
    assertThat(
        responses.none { it.interrupted && it.content == null && it.groundingMetadata == null }
      )
      .isTrue()
  }

  @Test
  fun receive_multiChunkTextTurn_aggregatesOneFullTextBeforeTurnComplete(): Unit = runBlocking {
    // The streamed chunks are still delivered, and the turn's full text is delivered once,
    // non-partial, before turn-complete. Mirrors ADK Python's receive() in
    // gemini_llm_connection.py.
    val responses =
      collect(
        GEMINI_25_LIVE,
        listOf(speechFrame("Hello "), speechFrame("world"), turnCompleteFrame()),
      )

    val fullText = responses.single {
      !it.partial && it.content?.parts?.any { p -> p.text != null } == true
    }
    assertThat(fullText.content?.parts?.single()?.text).isEqualTo("Hello world")
    assertThat(responses.indexOf(fullText))
      .isLessThan(responses.indexOfFirst { it.turnComplete == true })
    // The streamed chunks still arrive, so a chunk-rendering consumer is unaffected.
    assertThat(
        responses.filter { it.partial }.mapNotNull { it.content?.parts?.firstOrNull()?.text }
      )
      .containsExactly("Hello ", "world")
      .inOrder()
  }

  @Test
  fun receive_textThenToolCall_flushesTheFullTextBeforeTheCall(): Unit = runBlocking {
    // A tool call ends the text run, so the aggregated text is delivered before the call. Mirrors
    // ADK Python's receive() in gemini_llm_connection.py.
    val responses =
      collect(
        GEMINI_25_LIVE,
        listOf(speechFrame("thinking"), toolCallFrame("GetWeather"), turnCompleteFrame()),
      )

    val fullTextIndex = responses.indexOfFirst {
      !it.partial && it.content?.parts?.any { p -> p.text == "thinking" } == true
    }
    val callIndex = responses.indexOfFirst { toolNamesOf(it).isNotEmpty() }
    assertThat(fullTextIndex).isAtLeast(0)
    assertThat(callIndex).isAtLeast(0)
    assertThat(fullTextIndex).isLessThan(callIndex)
  }

  @Test
  fun receive_thoughtThenAnswer_deliversEachRunAsItsOwnFullText(): Unit = runBlocking {
    // A change of the thought flag ends the current text run. Mirrors ADK Python's receive() in
    // gemini_llm_connection.py.
    val responses =
      collect(
        GEMINI_25_LIVE,
        listOf(thoughtFrame("pondering"), speechFrame("answer"), turnCompleteFrame()),
      )

    val fullTexts = responses.filter {
      !it.partial && it.content?.parts?.any { p -> p.text != null } == true
    }
    assertThat(fullTexts.map { it.content?.parts?.single()?.text })
      .containsExactly("pondering", "answer")
      .inOrder()
    assertThat(fullTexts.first().content?.parts?.single()?.thought).isTrue()
    assertThat(fullTexts.last().content?.parts?.single()?.thought).isNull()
  }

  @Test
  fun receive_interruptedTextFrame_mergesPendingTextIntoOneInterruptedFullText(): Unit =
    runBlocking {
      // A text run interrupted by a frame that also carries text yields one non-partial full text
      // with interrupted=true. Mirrors ADK Python's receive() in gemini_llm_connection.py.
      val interruptedText =
        SdkLiveServerMessage(
          serverContent =
            SdkLiveServerContent(
              modelTurn = SdkContent(role = "model", parts = listOf(SdkPart(text = "b"))),
              interrupted = true,
            )
        )

      val responses = collect(GEMINI_25_LIVE, listOf(speechFrame("a"), interruptedText))

      val fullTexts = responses.filter {
        !it.partial && it.content?.parts?.any { p -> p.text != null } == true
      }
      assertThat(fullTexts).hasSize(1)
      assertThat(fullTexts.single().content?.parts?.single()?.text).isEqualTo("ab")
      assertThat(fullTexts.single().interrupted).isTrue()
    }

  @Test
  fun receive_textThenInterruptedAudioFrame_flushesPendingTextAsInterruptedFullText(): Unit =
    runBlocking {
      // Prior text, then an interrupted audio frame: the pending text is flushed as the interrupted
      // full text. Mirrors ADK Python's receive() in gemini_llm_connection.py.
      val interruptedAudio =
        SdkLiveServerMessage(
          serverContent =
            SdkLiveServerContent(
              modelTurn =
                SdkContent(
                  role = "model",
                  parts =
                    listOf(
                      SdkPart(
                        inlineData = SdkBlob(data = byteArrayOf(1, 2), mimeType = "audio/pcm")
                      )
                    ),
                ),
              interrupted = true,
            )
        )

      val responses = collect(GEMINI_25_LIVE, listOf(speechFrame("a"), interruptedAudio))

      val fullText = responses.single {
        !it.partial && it.content?.parts?.any { p -> p.text != null } == true
      }
      assertThat(fullText.content?.parts?.single()?.text).isEqualTo("a")
      assertThat(fullText.interrupted).isTrue()
    }

  @Test
  fun receive_textAndTurnCompleteInOneFrame_doesNotForwardThePartialChunk(): Unit = runBlocking {
    // When text and turn-complete share a frame, only the full text is delivered, not the streamed
    // chunk. Mirrors ADK Python's receive() in gemini_llm_connection.py.
    val frame =
      SdkLiveServerMessage(
        serverContent =
          SdkLiveServerContent(
            modelTurn = SdkContent(role = "model", parts = listOf(SdkPart(text = "done"))),
            turnComplete = true,
          )
      )

    val responses = collect(GEMINI_25_LIVE, listOf(frame))

    assertThat(
        responses.none { it.partial && it.content?.parts?.any { p -> p.text != null } == true }
      )
      .isTrue()
    val fullText = responses.single {
      !it.partial && it.content?.parts?.any { p -> p.text != null } == true
    }
    assertThat(fullText.content?.parts?.single()?.text).isEqualTo("done")
    assertThat(responses.any { it.turnComplete == true }).isTrue()
  }

  @Test
  fun receive_textNonTextTextInOneNonEndingFrame_flushesFirstTextAndKeepsTheRest(): Unit =
    runBlocking {
      // A non-text part ends the first text run (flushed as a full text); the chunk keeps that part
      // and the following text. Parts are stripped by identity, so a repeated "a" is not dropped.
      val frame =
        SdkLiveServerMessage(
          serverContent =
            SdkLiveServerContent(
              modelTurn =
                SdkContent(
                  role = "model",
                  parts =
                    listOf(
                      SdkPart(text = "a"),
                      SdkPart(functionCall = SdkFunctionCall(name = "fc")),
                      SdkPart(text = "a"),
                    ),
                )
            )
        )

      val responses = collect(GEMINI_25_LIVE, listOf(frame))

      val fullText = responses.single {
        !it.partial && it.content?.parts?.singleOrNull()?.text == "a"
      }
      assertThat(fullText.content?.parts?.single()?.text).isEqualTo("a")
      val chunk = responses.single {
        it.partial && it.content?.parts?.any { p -> p.functionCall != null } == true
      }
      assertThat(chunk.content?.parts?.map { it.functionCall?.name ?: it.text })
        .containsExactly("fc", "a")
        .inOrder()
    }

  @Test
  fun receive_toolCall_isDeliveredAsItArrivesNotHeldToTurnComplete(): Unit = runBlocking {
    // A call reaches the caller as its frame arrives, before later content, on every model.
    val responses =
      collect(GEMINI_25_LIVE, listOf(toolCallFrame("GetWeather"), speechFrame("after")))

    val call = responses.indexOfFirst { toolNamesOf(it).isNotEmpty() }
    val speech = responses.indexOfFirst { r ->
      r.content?.parts.orEmpty().any { it.text == "after" }
    }
    assertThat(call).isAtLeast(0)
    assertThat(call).isLessThan(speech)
  }

  @Test
  fun receive_severalToolCallFrames_areEachDeliveredAsTheyArrive(): Unit = runBlocking {
    // Each tool-call frame is its own response; they are not merged into one, on every model.
    val responses =
      collect(GEMINI_25_LIVE, listOf(toolCallFrame("GetWeather"), toolCallFrame("GetTime")))

    val withCalls = responses.filter { toolNamesOf(it).isNotEmpty() }
    assertThat(withCalls).hasSize(2)
    assertThat(withCalls.flatMap(::toolNamesOf)).containsExactly("GetWeather", "GetTime").inOrder()
  }

  @Test
  fun receive_turnWithoutGrounding_turnCompleteCarriesNoGrounding(): Unit = runBlocking {
    // With no grounding anywhere in the turn, turn-complete carries null on every model.
    val responses =
      collect(MODEL, listOf(speechFrame("hi"), toolCallFrame("GetWeather"), turnCompleteFrame()))

    assertThat(responses.single { it.turnComplete == true }.groundingMetadata).isNull()
  }

  private fun outputFrame(text: String?, finished: Boolean? = null) =
    SdkLiveServerMessage(
      serverContent =
        SdkLiveServerContent(
          outputTranscription = SdkTranscription(text = text, finished = finished)
        )
    )

  private fun inputFrame(text: String?, finished: Boolean? = null) =
    SdkLiveServerMessage(
      serverContent =
        SdkLiveServerContent(
          inputTranscription = SdkTranscription(text = text, finished = finished)
        )
    )

  /** Each response as one line: a tool call, a transcription chunk or aggregate, or turn end. */
  private fun timeline(responses: List<LlmResponse>): List<String> =
    responses.mapNotNull { response ->
      val output = response.outputTranscription
      val input = response.inputTranscription
      when {
        toolNamesOf(response).isNotEmpty() -> "call:" + toolNamesOf(response).joinToString()
        output != null -> (if (output.finished == true) "said:" else "saying:") + output.text
        input != null -> (if (input.finished == true) "heard:" else "hearing:") + input.text
        response.turnComplete == true -> "turnComplete"
        else -> null
      }
    }

  @Test
  fun receive_speechThenToolCall_finishesTheSpeechBeforeTheCall(): Unit = runBlocking {
    // A handover stops reading at the call, before the server marks the speech finished.
    val responses =
      collect(
        GEMINI_25_LIVE,
        listOf(outputFrame("One "), outputFrame("moment."), toolCallFrame("t")),
      )

    assertThat(timeline(responses))
      .containsExactly("saying:One ", "saying:moment.", "said:One moment.", "call:t")
      .inOrder()
  }

  @Test
  fun receive_userAndModelSpeechThenToolCall_leavesTheUsersSpeechToTheTurn(): Unit = runBlocking {
    // The caller finishes the user's speech itself, so its finished copy still follows the call.
    val responses =
      collect(
        GEMINI_25_LIVE,
        listOf(
          inputFrame("Book a table"),
          outputFrame("One moment."),
          toolCallFrame("t"),
          turnCompleteFrame(),
        ),
      )

    assertThat(timeline(responses))
      .containsExactly(
        "hearing:Book a table",
        "saying:One moment.",
        "said:One moment.",
        "call:t",
        "heard:Book a table",
        "turnComplete",
      )
      .inOrder()
  }

  @Test
  fun receive_speechOnBothSidesOfACall_finishesEachPartOnce(): Unit = runBlocking {
    val responses =
      collect(
        GEMINI_25_LIVE,
        listOf(
          outputFrame("Let me check."),
          toolCallFrame("lookup"),
          outputFrame(" It's sunny."),
          turnCompleteFrame(),
        ),
      )

    assertThat(timeline(responses))
      .containsExactly(
        "saying:Let me check.",
        "said:Let me check.",
        "call:lookup",
        "saying: It's sunny.",
        "said: It's sunny.",
        "turnComplete",
      )
      .inOrder()
  }

  @Test
  fun receive_endMarkerAfterTheCall_addsNoEmptyTranscription(): Unit = runBlocking {
    // The server's own end marker can arrive after the call, once the speech is finished.
    val responses =
      collect(
        GEMINI_25_LIVE,
        listOf(
          outputFrame("One moment."),
          toolCallFrame("t"),
          outputFrame(text = null, finished = true),
          turnCompleteFrame(),
        ),
      )

    assertThat(timeline(responses))
      .containsExactly("saying:One moment.", "said:One moment.", "call:t", "turnComplete")
      .inOrder()
  }

  @Test
  fun receive_finishedChunkAfterTheCall_finishesOnlyTheRest(): Unit = runBlocking {
    val responses =
      collect(
        GEMINI_25_LIVE,
        listOf(outputFrame("One "), toolCallFrame("t"), outputFrame("moment.", finished = true)),
      )

    assertThat(timeline(responses))
      .containsExactly("saying:One ", "said:One ", "call:t", "saying:moment.", "said:moment.")
      .inOrder()
  }

  @Test
  fun receive_toolCallWithNothingSaid_addsNoTranscription(): Unit = runBlocking {
    val responses = collect(GEMINI_25_LIVE, listOf(toolCallFrame("t"), turnCompleteFrame()))

    assertThat(timeline(responses)).containsExactly("call:t", "turnComplete").inOrder()
  }

  /** A live model whose one connection replays [frames], then stays open like a real socket. */
  private class ScriptedLiveModel(private val frames: List<SdkLiveServerMessage>) : Model {
    override val name = GEMINI_25_LIVE

    override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> =
      throw UnsupportedOperationException("Live only.")

    override suspend fun connect(request: LlmRequest): LiveConnection =
      GeminiLiveConnection(ParkingSession(frames), modelVersion = name)
  }

  /** A tool call with an id and arguments, which a runner needs to answer it. */
  private fun callFrame(name: String, args: Map<String, String> = emptyMap()) =
    SdkLiveServerMessage(
      toolCall =
        SdkLiveServerToolCall(
          functionCalls =
            listOf(
              SdkFunctionCall(
                id = "call-$name",
                name = name,
                args = args.mapValues { JsonPrimitive(it.value) },
              )
            )
        )
    )

  private val helper =
    LlmAgent(
      name = "helper",
      description = "Helps.",
      model = DummyLiveModel(listOf(DummyLiveStep.EndStream)),
    )

  /** What [runner] stored, one line per event: who spoke or heard what, and the calls. */
  private suspend fun storedTimeline(runner: InMemoryRunner): List<String> =
    runner.sessionService
      .getSession(SessionKey(runner.appName, "u", "s"))
      ?.events
      .orEmpty()
      .mapNotNull { event ->
        when {
          event.functionCalls().isNotEmpty() -> "call:" + event.functionCalls().single().name
          event.functionResponses().isNotEmpty() ->
            "answer:" + event.functionResponses().single().name
          event.outputTranscription != null ->
            "${event.author} said:${event.outputTranscription.text}"
          event.inputTranscription != null -> "user said:${event.inputTranscription.text}"
          else -> null
        }
      }

  private suspend fun runLiveAndStore(agent: BaseAgent, endOnTurnComplete: Boolean = false) =
    InMemoryRunner(agent = agent).also { runner ->
      val queue = LiveRequestQueue()
      runner
        .runLive("u", "s", queue)
        .onEach { if (endOnTurnComplete && it.turnComplete) queue.close() }
        .onCompletion { queue.close() }
        .toList()
    }

  @Test
  fun runLive_speechBeforeATransfer_isStoredBeforeTheCall(): Unit = runBlocking {
    val router =
      LlmAgent(
        name = "router",
        model =
          ScriptedLiveModel(
            listOf(
              inputFrame("I need help."),
              outputFrame("One moment."),
              callFrame(TRANSFER_TO_AGENT_TOOL_NAME, mapOf("agent_name" to "helper")),
            )
          ),
        subAgents = listOf(helper),
      )

    val runner = runLiveAndStore(router)

    assertThat(storedTimeline(runner))
      .containsExactly(
        "user said:I need help.",
        "router said:One moment.",
        "call:transfer_to_agent",
        "answer:transfer_to_agent",
      )
      .inOrder()
  }

  @Test
  fun runLive_speechBeforeTaskCompleted_isStoredBeforeTheNextStep(): Unit = runBlocking {
    val step1 =
      LlmAgent(
        name = "step1",
        description = "s",
        model =
          ScriptedLiveModel(listOf(outputFrame("All done."), callFrame(TASK_COMPLETED_TOOL_NAME))),
      )
    val step2 =
      LlmAgent(
        name = "step2",
        description = "s",
        model = ScriptedLiveModel(listOf(outputFrame("Next."), turnCompleteFrame())),
      )

    val runner =
      runLiveAndStore(
        SequentialAgent("sequence", subAgents = listOf(step1, step2)),
        endOnTurnComplete = true,
      )

    assertThat(storedTimeline(runner))
      .containsExactly(
        "step1 said:All done.",
        "call:task_completed",
        "answer:task_completed",
        "step2 said:Next.",
      )
      .inOrder()
  }

  @Test
  fun runLive_speechAroundAnOrdinaryToolCall_isStoredInTwoPartsInSpokenOrder(): Unit = runBlocking {
    val lookup = DummyTool(name = "lookup", declares = true) { _, _ -> "sunny" }
    val agent =
      LlmAgent(
        name = "agent",
        model =
          ScriptedLiveModel(
            listOf(
              outputFrame("Let me check."),
              callFrame("lookup"),
              outputFrame(" It's sunny."),
              turnCompleteFrame(),
            )
          ),
        tools = listOf(lookup),
      )

    val runner = runLiveAndStore(agent, endOnTurnComplete = true)

    assertThat(storedTimeline(runner))
      .containsExactly(
        "agent said:Let me check.",
        "call:lookup",
        "answer:lookup",
        "agent said: It's sunny.",
      )
      .inOrder()
  }

  @Test
  fun runLive_userSpeechBeforeAReplyAndACall_isStoredOnce(): Unit = runBlocking {
    // The connection finishes the user's speech after the call; that copy is not stored again.
    val agent =
      LlmAgent(
        name = "agent",
        model =
          ScriptedLiveModel(
            listOf(
              inputFrame("I need help."),
              outputFrame("Let me check."),
              callFrame("lookup"),
              inputFrame(text = null, finished = true),
              turnCompleteFrame(),
            )
          ),
        tools = listOf(DummyTool(name = "lookup", declares = true)),
      )

    val runner = runLiveAndStore(agent, endOnTurnComplete = true)

    assertThat(storedTimeline(runner))
      .containsExactly(
        "user said:I need help.",
        "agent said:Let me check.",
        "call:lookup",
        "answer:lookup",
      )
      .inOrder()
  }

  @Test
  fun runLive_callbackTransferHeldForTheUsersSpeech_yieldsToATransferCall(): Unit = runBlocking {
    // The callback's transfer waits for the user's speech to be screened; the call's transfer wins.
    val screened = mutableListOf<String>()
    val fallback =
      LlmAgent(
        name = "fallback",
        description = "Takes over.",
        model = DummyLiveModel(listOf(DummyLiveStep.EndStream)),
      )
    val router =
      LlmAgent(
        name = "router",
        model =
          ScriptedLiveModel(
            listOf(
              inputFrame("I need help."),
              outputFrame("One moment."),
              callFrame(TRANSFER_TO_AGENT_TOOL_NAME, mapOf("agent_name" to "helper")),
            )
          ),
        subAgents = listOf(helper, fallback),
        beforeModelCallbacks =
          listOf(
            BeforeModelCallback { _, request ->
              request.contents.flatMap { it.parts }.mapNotNullTo(screened) { it.text }
              CallbackChoice.Continue(request)
            }
          ),
        afterModelCallbacks =
          listOf(
            AfterModelCallback { context, response ->
              context.actions.transferToAgent = "fallback"
              response
            }
          ),
      )

    val runner = runLiveAndStore(router)

    assertThat(screened).containsExactly("I need help.")
    assertThat(storedTimeline(runner))
      .containsExactly(
        "user said:I need help.",
        "router said:One moment.",
        "call:transfer_to_agent",
        "answer:transfer_to_agent",
      )
      .inOrder()
    val events = runner.sessionService.getSession(SessionKey(runner.appName, "u", "s"))?.events
    assertThat(events.orEmpty().mapNotNull { it.actions.transferToAgent }).containsExactly("helper")
  }

  @Test
  fun runLive_speechBeforeATransfer_leavesTheRequestFirstForAnIncludeContentsNoneTarget(): Unit =
    runBlocking {
      val targetModel = DummyLiveModel(listOf(DummyLiveStep.EndStream))
      val target =
        LlmAgent(
          name = "target",
          description = "Helps.",
          model = targetModel,
          includeContents = LlmAgent.IncludeContents.NONE,
        )
      val router =
        LlmAgent(
          name = "router",
          model =
            ScriptedLiveModel(
              listOf(
                inputFrame("I need help with my booking."),
                outputFrame("One moment."),
                callFrame(TRANSFER_TO_AGENT_TOOL_NAME, mapOf("agent_name" to "target")),
              )
            ),
          subAgents = listOf(target),
        )

      val unused = runLiveAndStore(router)

      val history = targetModel.connections.first().sent.first { it is List<*> } as List<*>
      assertThat(history.filterIsInstance<Content>().first().parts.map { it.text })
        .containsExactly("I need help with my booking.")
    }

  @Test
  fun runLive_speechBeforeFinishTaskInATaskNode_isStoredBeforeTheCall(): Unit = runBlocking {
    val writer =
      LlmAgent(
        name = "writer",
        description = "Writes.",
        model =
          ScriptedLiveModel(
            listOf(outputFrame("One moment."), callFrame("finish_task", mapOf("result" to "done")))
          ),
        mode = AgentMode.TASK,
      )
    val start =
      node<Any?, Any?>("start_writer", rerunOnResume = true) { context, input ->
        context.runNode(writer, nodeInput = input, useAsOutput = true)
      }
    val app = App(appName = "app", rootNode = Workflow("wf", listOf(Edge(Start, start))))
    val runner = InMemoryRunner(app = app)
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("go"))

    withTimeout(30_000) { runner.runLive("u", "s", queue).onCompletion { queue.close() }.toList() }

    assertThat(storedTimeline(runner))
      .containsExactly("writer said:One moment.", "call:finish_task", "answer:finish_task")
      .inOrder()
  }

  @Test
  fun runLive_finishedReplyWithoutACallWhileTheUserSpeaks_isStoredInTheConnectionsOrder(): Unit =
    runBlocking {
      // Only a call moves the user's speech ahead of the model's words.
      val agent =
        LlmAgent(
          name = "agent",
          model =
            ScriptedLiveModel(
              listOf(
                inputFrame("Book a table"),
                outputFrame("Sure.", finished = true),
                inputFrame(" for two"),
                outputFrame(" Done."),
                turnCompleteFrame(),
              )
            ),
        )

      val runner = runLiveAndStore(agent, endOnTurnComplete = true)

      assertThat(storedTimeline(runner))
        .containsExactly("agent said:Sure.", "user said:Book a table for two", "agent said: Done.")
        .inOrder()
    }

  @Test
  fun runLive_thoughtAndSpeechBeforeATransfer_storesTheUsersSpeechBeforeTheAgentsWords(): Unit =
    runBlocking {
      // The thought's full text comes before the call too; it must not release the agent's words.
      val router =
        LlmAgent(
          name = "router",
          model =
            ScriptedLiveModel(
              listOf(
                inputFrame("I need help."),
                thoughtFrame("The user needs help."),
                outputFrame("One moment."),
                callFrame(TRANSFER_TO_AGENT_TOOL_NAME, mapOf("agent_name" to "helper")),
              )
            ),
          subAgents = listOf(helper),
        )

      val runner = runLiveAndStore(router)

      assertThat(storedTimeline(runner))
        .containsExactly(
          "user said:I need help.",
          "router said:One moment.",
          "call:transfer_to_agent",
          "answer:transfer_to_agent",
        )
        .inOrder()
    }
}
