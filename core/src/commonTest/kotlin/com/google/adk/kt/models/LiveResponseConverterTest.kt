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

import com.google.adk.kt.types.InteractionStatus
import com.google.adk.kt.types.TurnCompleteReason
import com.google.adk.kt.types.VoiceActivityType
import com.google.genai.kotlin.types.Blob as SdkBlob
import com.google.genai.kotlin.types.Content as SdkContent
import com.google.genai.kotlin.types.FunctionCall as SdkFunctionCall
import com.google.genai.kotlin.types.GroundingMetadata as SdkGroundingMetadata
import com.google.genai.kotlin.types.InteractionStatus as SdkInteractionStatus
import com.google.genai.kotlin.types.LiveServerContent as SdkLiveServerContent
import com.google.genai.kotlin.types.LiveServerGoAway as SdkLiveServerGoAway
import com.google.genai.kotlin.types.LiveServerMessage as SdkLiveServerMessage
import com.google.genai.kotlin.types.LiveServerSessionResumptionUpdate as SdkLiveServerSessionResumptionUpdate
import com.google.genai.kotlin.types.LiveServerSetupComplete as SdkLiveServerSetupComplete
import com.google.genai.kotlin.types.LiveServerToolCall as SdkLiveServerToolCall
import com.google.genai.kotlin.types.LiveServerToolCallCancellation as SdkLiveServerToolCallCancellation
import com.google.genai.kotlin.types.ModalityTokenCount as SdkModalityTokenCount
import com.google.genai.kotlin.types.Part as SdkPart
import com.google.genai.kotlin.types.Transcription as SdkTranscription
import com.google.genai.kotlin.types.TurnCompleteReason as SdkTurnCompleteReason
import com.google.genai.kotlin.types.UrlContextMetadata as SdkUrlContextMetadata
import com.google.genai.kotlin.types.UsageMetadata as SdkLiveUsageMetadata
import com.google.genai.kotlin.types.VadSignalType as SdkVadSignalType
import com.google.genai.kotlin.types.VoiceActivity as SdkVoiceActivity
import com.google.genai.kotlin.types.VoiceActivityDetectionSignal as SdkVoiceActivityDetectionSignal
import com.google.genai.kotlin.types.VoiceActivityType as SdkVoiceActivityType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Covers the per-frame mapping from a live server frame onto [LlmResponse]s, including the frames
 * that must produce nothing and the ordering of a frame that produces several.
 */
class LiveResponseConverterTest {

  @Test
  fun toLlmResponses_emptyServerContent_producesNothing() {
    // A bare serverContent frame is real and must not become an empty response to filter out.
    val message = SdkLiveServerMessage(serverContent = SdkLiveServerContent())

    assertEquals(emptyList(), message.toLlmResponses())
  }

  @Test
  fun toLlmResponses_emptyMessage_producesNothing() {
    assertEquals(emptyList(), SdkLiveServerMessage().toLlmResponses())
  }

  @Test
  fun toLlmResponses_setupCompleteOnly_producesNothing() {
    val message = SdkLiveServerMessage(setupComplete = SdkLiveServerSetupComplete())

    assertEquals(emptyList(), message.toLlmResponses())
  }

  @Test
  fun toLlmResponses_generationCompleteOnly_producesNothing() {
    // generationComplete flushes state this converter does not hold, so it maps to nothing.
    val message =
      SdkLiveServerMessage(serverContent = SdkLiveServerContent(generationComplete = true))

    assertEquals(emptyList(), message.toLlmResponses())
  }

  @Test
  fun toLlmResponses_toolCallOnly_producesNothing() {
    // This converter leaves tool calls to its caller.
    val message =
      SdkLiveServerMessage(
        toolCall = SdkLiveServerToolCall(functionCalls = listOf(SdkFunctionCall(id = "call-1")))
      )

    assertEquals(emptyList(), message.toLlmResponses())
  }

  @Test
  fun toLlmResponses_unmappedSignals_produceNothing() {
    val toolCallCancellation =
      SdkLiveServerMessage(
        toolCallCancellation = SdkLiveServerToolCallCancellation(ids = listOf("call-1"))
      )
    val voiceActivityDetectionSignal =
      SdkLiveServerMessage(
        voiceActivityDetectionSignal =
          SdkVoiceActivityDetectionSignal(vadSignalType = SdkVadSignalType.VAD_SIGNAL_TYPE_SOS)
      )
    val waitingForInput =
      SdkLiveServerMessage(serverContent = SdkLiveServerContent(waitingForInput = true))
    val interimInputTranscription =
      SdkLiveServerMessage(
        serverContent =
          SdkLiveServerContent(
            interimInputTranscription = SdkTranscription(text = "kotlin adk", finished = false)
          )
      )
    val urlContextMetadata =
      SdkLiveServerMessage(
        serverContent = SdkLiveServerContent(urlContextMetadata = SdkUrlContextMetadata())
      )

    assertEquals(emptyList(), toolCallCancellation.toLlmResponses())
    assertEquals(emptyList(), voiceActivityDetectionSignal.toLlmResponses())
    assertEquals(emptyList(), waitingForInput.toLlmResponses())
    assertEquals(emptyList(), interimInputTranscription.toLlmResponses())
    assertEquals(emptyList(), urlContextMetadata.toLlmResponses())
  }

  @Test
  fun toLlmResponses_modelTurnWithEmptyParts_producesNothing() {
    val message =
      SdkLiveServerMessage(
        serverContent =
          SdkLiveServerContent(modelTurn = SdkContent(role = "model", parts = emptyList()))
      )

    assertEquals(emptyList(), message.toLlmResponses())
  }

  @Test
  fun toLlmResponses_modelTurnWithEmptyPartsAndGrounding_stillEmitsTheGrounding() {
    val message =
      SdkLiveServerMessage(
        serverContent =
          SdkLiveServerContent(
            modelTurn = SdkContent(role = "model", parts = emptyList()),
            groundingMetadata = SdkGroundingMetadata(webSearchQueries = listOf("kotlin adk")),
          )
      )

    assertEquals(
      listOf("kotlin adk"),
      message.toLlmResponses().single().groundingMetadata?.webSearchQueries,
    )
  }

  @Test
  fun toLlmResponses_modelTurnCarryingText_marksTheResponsePartial() {
    // Without partial, every streamed chunk would count as the turn's final response.
    val message =
      SdkLiveServerMessage(
        serverContent =
          SdkLiveServerContent(
            modelTurn = SdkContent(role = "model", parts = listOf(SdkPart(text = "half a sen")))
          )
      )

    assertTrue(message.toLlmResponses().single().partial, "text chunks must be partial")
  }

  @Test
  fun toLlmResponses_modelTurnCarryingOnlyAudio_isNotPartial() {
    // ADK Python marks text parts alone; audio is not a sentence fragment.
    val message =
      SdkLiveServerMessage(
        serverContent =
          SdkLiveServerContent(
            modelTurn =
              SdkContent(
                role = "model",
                parts =
                  listOf(
                    SdkPart(inlineData = SdkBlob(mimeType = "audio/pcm", data = byteArrayOf(1, 2)))
                  ),
              )
          )
      )

    assertFalse(message.toLlmResponses().single().partial, "audio frames are not text fragments")
  }

  @Test
  fun toLlmResponses_modelTurnWithAudioAndTextParts_marksTheResponsePartial() {
    val message =
      SdkLiveServerMessage(
        serverContent =
          SdkLiveServerContent(
            modelTurn =
              SdkContent(
                role = "model",
                parts =
                  listOf(
                    SdkPart(inlineData = SdkBlob(mimeType = "audio/pcm", data = byteArrayOf(1))),
                    SdkPart(text = "kotlin adk"),
                  ),
              )
          )
      )

    assertTrue(message.toLlmResponses().single().partial)
  }

  @Test
  fun toLlmResponses_modelTurnWithEmptyTextPart_isNotPartial() {
    val message =
      SdkLiveServerMessage(
        serverContent =
          SdkLiveServerContent(
            modelTurn = SdkContent(role = "model", parts = listOf(SdkPart(text = "")))
          )
      )

    assertFalse(message.toLlmResponses().single().partial)
  }

  @Test
  fun toLlmResponses_transcriptionsWithNeitherTextNorEndMarker_produceNothing() {
    val message =
      SdkLiveServerMessage(
        serverContent =
          SdkLiveServerContent(
            inputTranscription = SdkTranscription(text = "", finished = false),
            outputTranscription = SdkTranscription(),
          )
      )

    assertEquals(emptyList(), message.toLlmResponses())
  }

  @Test
  fun toLlmResponses_outputTranscriptionWithEmptyText_producesNothing() {
    val message =
      SdkLiveServerMessage(
        serverContent =
          SdkLiveServerContent(outputTranscription = SdkTranscription(text = "", finished = false))
      )

    assertEquals(emptyList(), message.toLlmResponses())
  }

  @Test
  fun toLlmResponses_transcriptionFinishedWithoutText_stillReachesTheCaller() {
    // The end marker is one of the caller's flush cues, so it must reach them without text.
    val message =
      SdkLiveServerMessage(
        serverContent =
          SdkLiveServerContent(outputTranscription = SdkTranscription(finished = true))
      )

    val responses = message.toLlmResponses()

    assertEquals(1, responses.size, "the end marker must not be dropped")
    assertEquals(true, responses.single().outputTranscription?.finished)
  }

  @Test
  fun toLlmResponses_inputTranscriptionFinishedWithoutText_stillReachesTheCallerAndIsNotPartial() {
    val message =
      SdkLiveServerMessage(
        serverContent = SdkLiveServerContent(inputTranscription = SdkTranscription(finished = true))
      )

    val response = message.toLlmResponses().single()

    assertEquals(true, response.inputTranscription?.finished)
    assertFalse(response.partial)
  }

  @Test
  fun toLlmResponses_outputTranscriptionUnfinishedChunk_reachesCallerAsPartial() {
    val message =
      SdkLiveServerMessage(
        serverContent =
          SdkLiveServerContent(
            outputTranscription = SdkTranscription(text = "kotlin adk", finished = false)
          )
      )

    val response = message.toLlmResponses().single()

    assertEquals("kotlin adk", response.outputTranscription?.text)
    assertTrue(response.partial)
  }

  @Test
  fun toLlmResponses_groundingWithoutAModelTurn_stillReachesTheCaller() {
    val message =
      SdkLiveServerMessage(
        serverContent =
          SdkLiveServerContent(
            groundingMetadata = SdkGroundingMetadata(webSearchQueries = listOf("kotlin adk"))
          )
      )

    val responses = message.toLlmResponses()

    val grounding = responses.single().groundingMetadata
    assertNotNull(grounding, "standalone grounding must reach the caller")
    assertEquals(listOf("kotlin adk"), grounding.webSearchQueries)
  }

  @Test
  fun toLlmResponses_groundingWithAModelTurn_ridesOnTheContentResponse() {
    val message =
      SdkLiveServerMessage(
        serverContent =
          SdkLiveServerContent(
            modelTurn = SdkContent(role = "model", parts = listOf(SdkPart(text = "kotlin adk"))),
            groundingMetadata = SdkGroundingMetadata(webSearchQueries = listOf("kotlin adk")),
          )
      )

    val responses = message.toLlmResponses()

    // One response, not two: grounding rides with the content rather than arriving separately.
    val grounding = responses.single().groundingMetadata
    assertNotNull(grounding)
    assertEquals(listOf("kotlin adk"), grounding.webSearchQueries)
  }

  @Test
  fun toLlmResponses_groundingOnTheTurnCompleteFrame_ridesOnTheTurnCompleteResponse() {
    val message =
      SdkLiveServerMessage(
        serverContent =
          SdkLiveServerContent(
            turnComplete = true,
            groundingMetadata = SdkGroundingMetadata(webSearchQueries = listOf("kotlin adk")),
          )
      )

    val responses = message.toLlmResponses()

    val response = responses.single()
    assertEquals(true, response.turnComplete)
    val grounding = response.groundingMetadata
    assertNotNull(grounding, "grounding delivered at turn-complete must reach the caller")
    assertEquals(listOf("kotlin adk"), grounding.webSearchQueries)
  }

  @Test
  fun toLlmResponses_groundingWithAModelTurnThatCompletes_ridesOnlyOnTheTurnCompleteResponse() {
    val message =
      SdkLiveServerMessage(
        serverContent =
          SdkLiveServerContent(
            modelTurn = SdkContent(role = "model", parts = listOf(SdkPart(text = "kotlin adk"))),
            turnComplete = true,
            groundingMetadata = SdkGroundingMetadata(webSearchQueries = listOf("kotlin adk")),
          )
      )

    val responses = message.toLlmResponses()

    // Both responses stay; only the turn-complete one carries the grounding.
    assertEquals(2, responses.size, "the content response and the turn-complete one")
    assertNull(responses[0].groundingMetadata)
    assertEquals(listOf("kotlin adk"), responses[1].groundingMetadata?.webSearchQueries)
    assertEquals(true, responses[1].turnComplete)
  }

  @Test
  fun toLlmResponses_modelTurn_carriesContentModelVersionAndSessionId() {
    val message =
      SdkLiveServerMessage(
        serverContent =
          SdkLiveServerContent(
            modelTurn = SdkContent(role = "model", parts = listOf(SdkPart(text = "hello")))
          )
      )

    val responses = message.toLlmResponses(modelVersion = "gemini-live", liveSessionId = "s-1")

    assertEquals("hello", responses.single().content?.parts?.single()?.text)
    assertEquals("gemini-live", responses.single().modelVersion)
    assertEquals("s-1", responses.single().liveSessionId)
  }

  @Test
  fun toLlmResponses_usageMetadata_mapsResponseTokensOntoCandidateTokens() {
    // Live names output tokens responseTokenCount; ADK names them candidatesTokenCount.
    val message =
      SdkLiveServerMessage(
        usageMetadata =
          SdkLiveUsageMetadata(
            promptTokenCount = 11,
            responseTokenCount = 22,
            totalTokenCount = 33,
            responseTokensDetails = listOf(SdkModalityTokenCount(modality = null, tokenCount = 22)),
          )
      )

    val usage = message.toLlmResponses().single().usageMetadata

    assertNotNull(usage)
    assertEquals(11, usage.promptTokenCount)
    assertEquals(22, usage.candidatesTokenCount)
    assertEquals(33, usage.totalTokenCount)
    assertEquals(22, usage.candidatesTokensDetails?.single()?.tokenCount)
  }

  @Test
  fun toLlmResponses_usageMetadataWithTurnComplete_ordersUsageFirst() {
    // Recorded traffic puts usageMetadata on the same frame as turnComplete, so the order is real.
    val message =
      SdkLiveServerMessage(
        serverContent = SdkLiveServerContent(turnComplete = true),
        usageMetadata = SdkLiveUsageMetadata(responseTokenCount = 7),
      )

    val responses = message.toLlmResponses()

    assertEquals(2, responses.size)
    assertEquals(7, responses[0].usageMetadata?.candidatesTokenCount)
    assertNull(responses[0].turnComplete)
    assertEquals(true, responses[1].turnComplete)
    assertNull(responses[1].usageMetadata)
  }

  @Test
  fun toLlmResponses_turnComplete_carriesTheReason() {
    val message =
      SdkLiveServerMessage(
        serverContent =
          SdkLiveServerContent(
            turnComplete = true,
            turnCompleteReason = SdkTurnCompleteReason("RESPONSE_REJECTED"),
          )
      )

    val response = message.toLlmResponses().single()

    assertEquals(true, response.turnComplete)
    assertEquals(TurnCompleteReason.RESPONSE_REJECTED, response.turnCompleteReason)
  }

  @Test
  fun toLlmResponses_modelTurnWithTurnCompleteReason_carriesTheReason() {
    val message =
      SdkLiveServerMessage(
        serverContent =
          SdkLiveServerContent(
            modelTurn = SdkContent(role = "model", parts = listOf(SdkPart(text = "kotlin adk"))),
            turnCompleteReason = SdkTurnCompleteReason("RESPONSE_REJECTED"),
          )
      )

    assertEquals(
      TurnCompleteReason.RESPONSE_REJECTED,
      message.toLlmResponses().single().turnCompleteReason,
    )
  }

  @Test
  fun toLlmResponses_turnComplete_carriesTheInteractionStatus() {
    // turnComplete alone is not terminal on a model answering one prompt with several turns.
    val message =
      SdkLiveServerMessage(
        serverContent =
          SdkLiveServerContent(
            turnComplete = true,
            interactionStatus = SdkInteractionStatus("IN_PROGRESS"),
          )
      )

    val response = message.toLlmResponses().single()

    assertEquals(true, response.turnComplete)
    assertEquals(InteractionStatus.IN_PROGRESS, response.interactionStatus)
  }

  @Test
  fun toLlmResponses_unknownInteractionStatus_fallsBackToUnspecified() {
    val message =
      SdkLiveServerMessage(
        serverContent =
          SdkLiveServerContent(
            turnComplete = true,
            interactionStatus = SdkInteractionStatus("STATUS_FROM_A_LATER_API"),
          )
      )

    assertEquals(
      InteractionStatus.INTERACTION_STATUS_UNSPECIFIED,
      message.toLlmResponses().single().interactionStatus,
    )
  }

  @Test
  fun toLlmResponses_noInteractionStatus_leavesItNull() {
    val message = SdkLiveServerMessage(serverContent = SdkLiveServerContent(turnComplete = true))

    assertNull(message.toLlmResponses().single().interactionStatus)
  }

  @Test
  fun toLlmResponses_standaloneGrounding_keepsTheTurnCompleteReason() {
    // No other response on this frame carries the reason, so dropping it here loses it outright.
    val message =
      SdkLiveServerMessage(
        serverContent =
          SdkLiveServerContent(
            groundingMetadata = SdkGroundingMetadata(webSearchQueries = listOf("kotlin adk")),
            turnCompleteReason = SdkTurnCompleteReason("RESPONSE_REJECTED"),
          )
      )

    val response = message.toLlmResponses().single()

    assertNotNull(response.groundingMetadata)
    assertEquals(TurnCompleteReason.RESPONSE_REJECTED, response.turnCompleteReason)
  }

  @Test
  fun toLlmResponses_standaloneGroundingWhileInterrupted_flagsBothResponses() {
    // ADK Python flags both and repeats the grounding on the second; here only the first has it.
    val message =
      SdkLiveServerMessage(
        serverContent =
          SdkLiveServerContent(
            groundingMetadata = SdkGroundingMetadata(webSearchQueries = listOf("kotlin adk")),
            interrupted = true,
          )
      )

    val responses = message.toLlmResponses()

    assertEquals(2, responses.size)
    assertNotNull(responses[0].groundingMetadata)
    assertEquals(true, responses[0].interrupted)
    assertNull(responses[1].groundingMetadata)
    assertEquals(true, responses[1].interrupted)
  }

  @Test
  fun toLlmResponses_standaloneGroundingWhileInterrupted_stampsEveryResponse() {
    val message =
      SdkLiveServerMessage(
        serverContent =
          SdkLiveServerContent(
            groundingMetadata = SdkGroundingMetadata(webSearchQueries = listOf("kotlin adk")),
            interrupted = true,
          )
      )

    val responses = message.toLlmResponses(modelVersion = "m", liveSessionId = "s")

    assertEquals(listOf("m", "m"), responses.map { it.modelVersion })
    assertEquals(listOf("s", "s"), responses.map { it.liveSessionId })
  }

  @Test
  fun toLlmResponses_uninterruptedFrame_leavesEveryResponseUninterrupted() {
    val withTurn =
      SdkLiveServerMessage(
        serverContent =
          SdkLiveServerContent(
            modelTurn = SdkContent(role = "model", parts = listOf(SdkPart(text = "kotlin adk"))),
            turnComplete = true,
          )
      )
    val standaloneGrounding =
      SdkLiveServerMessage(
        serverContent =
          SdkLiveServerContent(
            groundingMetadata = SdkGroundingMetadata(webSearchQueries = listOf("kotlin adk"))
          )
      )

    assertEquals(listOf(false, false), withTurn.toLlmResponses().map { it.interrupted })
    assertFalse(standaloneGrounding.toLlmResponses().single().interrupted)
  }

  @Test
  fun toLlmResponses_unknownTurnCompleteReason_fallsBackToUnspecified() {
    val message =
      SdkLiveServerMessage(
        serverContent =
          SdkLiveServerContent(
            turnComplete = true,
            turnCompleteReason = SdkTurnCompleteReason("REASON_FROM_A_LATER_API"),
          )
      )

    assertEquals(
      TurnCompleteReason.TURN_COMPLETE_REASON_UNSPECIFIED,
      message.toLlmResponses().single().turnCompleteReason,
    )
  }

  @Test
  fun toLlmResponses_interruptedWithoutContent_stillReportsTheInterruption() {
    val message = SdkLiveServerMessage(serverContent = SdkLiveServerContent(interrupted = true))

    val response = message.toLlmResponses().single()

    assertTrue(response.interrupted)
    assertNull(response.turnComplete)
  }

  @Test
  fun toLlmResponses_textTurnWhileInterrupted_flagsOnlyTheContentResponse() {
    // ADK Python folds this text into one aggregated response; aggregating is the caller's job.
    val message =
      SdkLiveServerMessage(
        serverContent =
          SdkLiveServerContent(
            modelTurn = SdkContent(role = "model", parts = listOf(SdkPart(text = "kotlin adk"))),
            interrupted = true,
          )
      )

    val response = message.toLlmResponses().single()

    assertEquals("kotlin adk", response.content?.parts?.single()?.text)
    assertTrue(response.interrupted)
  }

  @Test
  fun toLlmResponses_audioTurnWhileInterrupted_flagsOnlyTheContentResponse() {
    // Unlike ADK Python, no extra bare response: it would save this audio as a second recording.
    val message =
      SdkLiveServerMessage(
        serverContent =
          SdkLiveServerContent(
            modelTurn =
              SdkContent(
                role = "model",
                parts =
                  listOf(
                    SdkPart(inlineData = SdkBlob(mimeType = "audio/pcm", data = byteArrayOf(1, 2)))
                  ),
              ),
            interrupted = true,
          )
      )

    val response = message.toLlmResponses().single()

    assertTrue(response.interrupted)
    assertFalse(response.partial)
  }

  @Test
  fun toLlmResponses_turnCompleteWhileInterrupted_marksTheTurnCompleteResponseInterrupted() {
    val message =
      SdkLiveServerMessage(
        serverContent = SdkLiveServerContent(turnComplete = true, interrupted = true)
      )

    val response = message.toLlmResponses().single()

    assertEquals(true, response.turnComplete)
    assertTrue(response.interrupted)
  }

  @Test
  fun toLlmResponses_transcriptions_produceOneResponseEach() {
    val message =
      SdkLiveServerMessage(
        serverContent =
          SdkLiveServerContent(
            inputTranscription = SdkTranscription(text = "what is", finished = false),
            outputTranscription = SdkTranscription(text = "it is", finished = true),
          )
      )

    val responses = message.toLlmResponses()

    assertEquals(2, responses.size)
    assertEquals("what is", responses[0].inputTranscription?.text)
    assertEquals(false, responses[0].inputTranscription?.finished)
    assertEquals("it is", responses[1].outputTranscription?.text)
    assertEquals(true, responses[1].outputTranscription?.finished)
  }

  @Test
  fun toLlmResponses_transcriptionChunk_isPartialUntilTheEndMarker() {
    // Stateless: only the server's end marker can tell this converter a transcription is done.
    val message =
      SdkLiveServerMessage(
        serverContent =
          SdkLiveServerContent(
            inputTranscription = SdkTranscription(text = "what is", finished = false),
            outputTranscription = SdkTranscription(text = "it is", finished = true),
          )
      )

    val responses = message.toLlmResponses()

    assertEquals(true, responses[0].partial)
    assertEquals(false, responses[1].partial)
  }

  @Test
  fun toLlmResponses_transcriptionAndTurnComplete_ordersTranscriptionBeforeTurnComplete() {
    // The last transcription chunk must reach the caller before the turn ends.
    val message =
      SdkLiveServerMessage(
        serverContent =
          SdkLiveServerContent(
            outputTranscription = SdkTranscription(text = "goodbye", finished = true),
            turnComplete = true,
          )
      )

    val responses = message.toLlmResponses()

    assertEquals(2, responses.size, "expected a transcription response and a turn-complete one")
    assertEquals(
      "goodbye",
      responses[0].outputTranscription?.text,
      "the transcription response must come first, before turn-complete",
    )
    assertEquals(
      true,
      responses[1].turnComplete,
      "the turn-complete response must come last, after the transcription",
    )
  }

  @Test
  fun toLlmResponses_sessionResumptionUpdate_carriesTheHandle() {
    // Arrives at points of the server's choosing, so it is mapped independently of turn state.
    val message =
      SdkLiveServerMessage(
        sessionResumptionUpdate =
          SdkLiveServerSessionResumptionUpdate(newHandle = "handle-1", resumable = true)
      )

    val update = message.toLlmResponses().single().liveSessionResumptionUpdate

    assertEquals("handle-1", update?.newHandle)
    assertEquals(true, update?.resumable)
  }

  @Test
  fun toLlmResponses_goAway_carriesTimeLeft() {
    val message = SdkLiveServerMessage(goAway = SdkLiveServerGoAway(timeLeft = 30.seconds))

    assertEquals(30.seconds, message.toLlmResponses().single().goAway?.timeLeft)
  }

  @Test
  fun toLlmResponses_voiceActivity_carriesTypeAndOffset() {
    val message =
      SdkLiveServerMessage(
        voiceActivity =
          SdkVoiceActivity(
            voiceActivityType = SdkVoiceActivityType("ACTIVITY_START"),
            audioOffset = 2.seconds,
          )
      )

    val activity = message.toLlmResponses().single().voiceActivity

    assertEquals(VoiceActivityType.ACTIVITY_START, activity?.voiceActivityType)
    assertEquals(2.seconds, activity?.audioOffset)
  }

  @Test
  fun toLlmResponses_frameWithEverything_ordersSignalsAsPythonReadsThem() {
    val message =
      SdkLiveServerMessage(
        serverContent =
          SdkLiveServerContent(
            modelTurn = SdkContent(role = "model", parts = listOf(SdkPart(text = "hi"))),
            inputTranscription = SdkTranscription(text = "hey", finished = true),
            outputTranscription = SdkTranscription(text = "hi", finished = true),
            turnComplete = true,
          ),
        usageMetadata = SdkLiveUsageMetadata(responseTokenCount = 4),
        sessionResumptionUpdate = SdkLiveServerSessionResumptionUpdate(newHandle = "h"),
        voiceActivity =
          SdkVoiceActivity(
            voiceActivityType = SdkVoiceActivityType("ACTIVITY_START"),
            audioOffset = 2.seconds,
          ),
        goAway = SdkLiveServerGoAway(timeLeft = 5.seconds),
      )

    val responses = message.toLlmResponses(modelVersion = "m", liveSessionId = "s")

    assertEquals(8, responses.size)
    assertEquals(4, responses[0].usageMetadata?.candidatesTokenCount)
    assertEquals("hi", responses[1].content?.parts?.single()?.text)
    assertEquals("hey", responses[2].inputTranscription?.text)
    assertEquals("hi", responses[3].outputTranscription?.text)
    assertEquals(true, responses[4].turnComplete)
    // Precedes the go-away, so a consumer that reconnects has the newest handle.
    assertEquals("h", responses[5].liveSessionResumptionUpdate?.newHandle)
    assertEquals(VoiceActivityType.ACTIVITY_START, responses[6].voiceActivity?.voiceActivityType)
    assertEquals(5.seconds, responses[7].goAway?.timeLeft)
    assertEquals(List(8) { "m" }, responses.map { it.modelVersion })
    assertEquals(List(8) { "s" }, responses.map { it.liveSessionId })
  }
}
