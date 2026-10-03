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

import com.google.adk.kt.types.GroundingMetadata
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
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test

private const val GEMINI_3X_LIVE = "gemini-3.0-flash-live"
private const val GEMINI_25_LIVE = "gemini-2.5-flash-live"

/**
 * Tests when a live connection hands a tool call to its caller.
 *
 * Gemini 3.x withholds turn completion until it has the answer, so a connection that held the call
 * back to the end of the turn would deadlock - and would do it looking like a model that went
 * quiet. Other models complete the turn first and expect the calls merged into one response.
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
  fun receive_gemini3xToolCall_deliversItWithoutWaitingForTurnComplete(): Unit = runBlocking {
    // No turn-complete frame at all: on 3.x there would never be one until the answer is sent.
    val responses =
      collect(GEMINI_3X_LIVE, listOf(toolCallFrame("GetWeather"), speechFrame("after")))

    val call = responses.indexOfFirst { toolNamesOf(it).isNotEmpty() }
    val speech = responses.indexOfFirst { response ->
      response.content?.parts.orEmpty().any { it.text == "after" }
    }
    assertThat(call).isAtLeast(0)
    assertThat(call).isLessThan(speech)
  }

  @Test
  fun receive_olderModelInterruptedMidTurn_mergesTheTurnsCallsAtTurnComplete(): Unit = runBlocking {
    val interrupted = SdkLiveServerMessage(serverContent = SdkLiveServerContent(interrupted = true))

    val responses =
      collect(
        GEMINI_25_LIVE,
        listOf(
          toolCallFrame("GetWeather"),
          interrupted,
          toolCallFrame("GetTime"),
          turnCompleteFrame(),
        ),
      )

    assertThat(responses.map(::toolNamesOf).filter { it.isNotEmpty() })
      .containsExactly(listOf("GetWeather", "GetTime"))
  }

  @Test
  fun receive_olderModelGroundingBeforeAToolCall_goesWithTheCallNotTheTurnComplete(): Unit =
    runBlocking {
      val responses =
        collect(
          GEMINI_25_LIVE,
          listOf(groundingFrame(), toolCallFrame("GetWeather"), turnCompleteFrame()),
        )

      assertGroundingOnTheCallOnly(responses)
    }

  @Test
  fun receive_gemini3xGroundingBeforeAToolCall_goesWithTheCallNotTheTurnComplete(): Unit =
    runBlocking {
      val responses =
        collect(
          GEMINI_3X_LIVE,
          listOf(groundingFrame(), toolCallFrame("GetWeather"), turnCompleteFrame()),
        )

      assertGroundingOnTheCallOnly(responses, turnCompleteGrounding = GroundingMetadata())
    }

  @Test
  fun receive_gemini3xTurnWithoutGrounding_turnCompleteCarriesEmptyGroundingMetadata(): Unit =
    runBlocking {
      val responses =
        collect(
          GEMINI_3X_LIVE,
          listOf(speechFrame("hi"), toolCallFrame("GetWeather"), turnCompleteFrame()),
        )

      assertThat(responses.filter { it.turnComplete != true }.map { it.groundingMetadata })
        .containsExactly(null, null)
      assertThat(responses.single { it.turnComplete == true }.groundingMetadata)
        .isEqualTo(GroundingMetadata())
    }

  @Test
  fun receive_olderModelTurnWithoutGrounding_turnCompleteCarriesNoGroundingMetadata(): Unit =
    runBlocking {
      val responses =
        collect(
          GEMINI_25_LIVE,
          listOf(speechFrame("hi"), toolCallFrame("GetWeather"), turnCompleteFrame()),
        )

      assertThat(responses.map { it.groundingMetadata }.toSet()).containsExactly(null)
    }

  @Test
  fun receive_gemini3xGroundingWithoutAToolCall_reachesTheTurnComplete(): Unit = runBlocking {
    val responses = collect(GEMINI_3X_LIVE, listOf(groundingFrame(), turnCompleteFrame()))

    assertThat(responses.single { it.turnComplete == true }.groundingMetadata?.webSearchQueries)
      .containsExactly("q")
  }

  @Test
  fun receive_olderModelGroundingAfterAToolCall_goesWithTheTurnComplete(): Unit = runBlocking {
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
  fun receive_olderModelStreamEndsWithAHeldCall_deliversItWithoutGrounding(): Unit = runBlocking {
    val responses = collect(GEMINI_25_LIVE, listOf(groundingFrame(), toolCallFrame("GetWeather")))

    assertThat(responses.single { toolNamesOf(it).isNotEmpty() }.groundingMetadata).isNull()
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
  fun receive_olderModelStreamEndsWithoutTurnComplete_stillDeliversTheHeldCall(): Unit =
    runBlocking {
      // A session ending mid-turn must not take the held call with it.
      val responses = collect(GEMINI_25_LIVE, listOf(toolCallFrame("GetWeather")))

      assertThat(responses.flatMap(::toolNamesOf)).containsExactly("GetWeather")
    }

  @Test
  fun receive_olderModelTurnCompletes_deliversTheHeldToolCall(): Unit = runBlocking {
    val responses =
      collect(GEMINI_25_LIVE, listOf(toolCallFrame("GetWeather"), turnCompleteFrame()))

    assertThat(responses.flatMap(::toolNamesOf)).containsExactly("GetWeather")
  }

  @Test
  fun receive_olderModelTurnCompletes_deliversToolCallsBeforeTheTurnCompleteResponse(): Unit =
    runBlocking {
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
  fun receive_olderModelSeveralToolCalls_mergesThemIntoOneResponse(): Unit = runBlocking {
    val responses =
      collect(
        GEMINI_25_LIVE,
        listOf(toolCallFrame("GetWeather"), toolCallFrame("GetTime"), turnCompleteFrame()),
      )

    val withCalls = responses.filter { toolNamesOf(it).isNotEmpty() }
    assertThat(withCalls).hasSize(1)
    assertThat(toolNamesOf(withCalls.single())).containsExactly("GetWeather", "GetTime").inOrder()
  }

  @Test
  fun receive_gemini3xSeveralToolCalls_deliversEachAsItArrives(): Unit = runBlocking {
    val responses =
      collect(GEMINI_3X_LIVE, listOf(toolCallFrame("GetWeather"), toolCallFrame("GetTime")))

    val withCalls = responses.filter { toolNamesOf(it).isNotEmpty() }
    assertThat(withCalls.map(::toolNamesOf))
      .containsExactly(listOf("GetWeather"), listOf("GetTime"))
      .inOrder()
  }

  @Test
  fun receive_toolCallSharesTheTurnCompleteFrame_isStillDeliveredFirst(): Unit = runBlocking {
    // This frame also carries the call, which must not land after the response that ends reading.
    val frame =
      SdkLiveServerMessage(
        toolCall =
          SdkLiveServerToolCall(functionCalls = listOf(SdkFunctionCall(name = "GetWeather"))),
        serverContent = SdkLiveServerContent(turnComplete = true),
      )

    val responses = collect(GEMINI_25_LIVE, listOf(frame))

    val toolCallIndex = responses.indexOfFirst { toolNamesOf(it).isNotEmpty() }
    val turnCompleteIndex = responses.indexOfFirst { it.turnComplete == true }
    assertThat(toolCallIndex).isAtLeast(0)
    assertThat(toolCallIndex).isLessThan(turnCompleteIndex)
  }

  @Test
  fun receive_speechThenToolCall_keepsBothOnOlderModels(): Unit = runBlocking {
    val responses =
      collect(
        GEMINI_25_LIVE,
        listOf(speechFrame("checking"), toolCallFrame("GetWeather"), turnCompleteFrame()),
      )

    assertThat(responses.mapNotNull { it.content?.parts?.firstOrNull()?.text }).contains("checking")
    assertThat(responses.flatMap(::toolNamesOf)).containsExactly("GetWeather")
  }

  @Test
  fun receive_collectionAbandonedWithHeldCall_callIsNotReplayedInNextTurn(): Unit = runBlocking {
    // ADK Python keeps held calls local to each receive(), so an abandoned turn leaves none.
    val connection =
      GeminiLiveConnection(
        ParkingSession(listOf(toolCallFrame("Stale"), speechFrame("x"), turnCompleteFrame())),
        modelVersion = GEMINI_25_LIVE,
      )
    try {
      // The caller stops reading turn 1 early, as a cancelled collector would.
      assertThat(connection.receive().take(1).toList()).hasSize(1)

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
  fun receive_gemini3xToolCallFrameWithUsage_emitsUsageThenTheToolCall(): Unit = runBlocking {
    // usageMetadata sits outside the server-message oneof, so it can share a tool-call frame.
    val frame =
      SdkLiveServerMessage(
        toolCall =
          SdkLiveServerToolCall(functionCalls = listOf(SdkFunctionCall(name = "GetWeather"))),
        usageMetadata = SdkLiveUsageMetadata(responseTokenCount = 5),
      )

    val responses = collect(GEMINI_3X_LIVE, listOf(frame))

    val usageIndex = responses.indexOfFirst { it.usageMetadata != null }
    val toolCallIndex = responses.indexOfFirst { toolNamesOf(it).isNotEmpty() }
    assertThat(usageIndex).isAtLeast(0)
    assertThat(toolCallIndex).isGreaterThan(usageIndex)
  }

  @Test
  fun receive_interruptedTurnCompleteFrameWithParts_emitsContentBeforeTheTurnComplete(): Unit =
    runBlocking {
      // The converter sets `interrupted` on the content response; it must still be emitted in
      // place,
      // before the turn-complete, which carries the frame's grounding.
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

      val contentIndex = responses.indexOfFirst {
        it.content?.parts?.any { p -> p.text == "x" } == true
      }
      val turnCompleteIndex = responses.indexOfFirst { it.turnComplete == true }
      assertThat(contentIndex).isAtLeast(0)
      assertThat(turnCompleteIndex).isGreaterThan(contentIndex)
      assertThat(responses.single { it.turnComplete == true }.groundingMetadata?.webSearchQueries)
        .containsExactly("q")
    }

  @Test
  fun receive_interruptedFrameWithParts_keepsContentAheadOfTheTranscript(): Unit = runBlocking {
    // The interrupted content response must precede the transcript chunks of the same frame.
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

    val contentIndex = responses.indexOfFirst {
      it.content?.parts?.any { p -> p.text == "x" } == true
    }
    val transcriptIndex = responses.indexOfFirst { it.outputTranscription != null }
    assertThat(contentIndex).isAtLeast(0)
    assertThat(transcriptIndex).isGreaterThan(contentIndex)
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
  fun receive_gemini3xInterruptedAfterGrounding_turnCompleteCarriesEmptyGrounding(): Unit =
    runBlocking {
      // On 3.x the turn-complete after an interruption marks "no grounding" with an empty value.
      val interrupted =
        SdkLiveServerMessage(serverContent = SdkLiveServerContent(interrupted = true))

      val responses =
        collect(GEMINI_3X_LIVE, listOf(groundingFrame(), interrupted, turnCompleteFrame()))

      assertThat(
          responses
            .single { it.interrupted == true && it.turnComplete != true }
            .groundingMetadata
            ?.webSearchQueries
        )
        .containsExactly("q")
      assertThat(responses.single { it.turnComplete == true }.groundingMetadata)
        .isEqualTo(GroundingMetadata())
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
    fun inputFrame(text: String) =
      SdkLiveServerMessage(
        serverContent = SdkLiveServerContent(inputTranscription = SdkTranscription(text = text))
      )
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
}
