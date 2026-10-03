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

package com.google.adk.kt.models

import com.google.adk.kt.annotations.ExperimentalLiveApi
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FileData
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.GroundingChunk
import com.google.adk.kt.types.GroundingChunkWeb
import com.google.adk.kt.types.GroundingMetadata
import com.google.adk.kt.types.GroundingSupport
import com.google.adk.kt.types.Part
import com.google.genai.kotlin.types.ActivityEnd as SdkActivityEnd
import com.google.genai.kotlin.types.ActivityStart as SdkActivityStart
import com.google.genai.kotlin.types.Blob as SdkBlob
import com.google.genai.kotlin.types.Content as SdkContent
import com.google.genai.kotlin.types.FunctionResponse as SdkFunctionResponse
import com.google.genai.kotlin.types.GroundingChunk as SdkGroundingChunk
import com.google.genai.kotlin.types.GroundingChunkWeb as SdkGroundingChunkWeb
import com.google.genai.kotlin.types.GroundingMetadata as SdkGroundingMetadata
import com.google.genai.kotlin.types.GroundingSupport as SdkGroundingSupport
import com.google.genai.kotlin.types.LiveServerContent as SdkLiveServerContent
import com.google.genai.kotlin.types.LiveServerMessage as SdkLiveServerMessage
import com.google.genai.kotlin.types.LiveServerSessionResumptionUpdate as SdkSessionResumptionUpdate
import com.google.genai.kotlin.types.LiveServerSetupComplete as SdkLiveServerSetupComplete
import com.google.genai.kotlin.types.Part as SdkPart
import com.google.genai.kotlin.types.Transcription as SdkTranscription
import com.google.genai.kotlin.types.UsageMetadata as SdkLiveUsageMetadata
import kotlin.concurrent.Volatile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield

/** Covers the send translation, frame fan-out and close behavior of [GeminiLiveConnection]. */
class GeminiLiveConnectionTest {

  /** Records what reached the session, and replays a scripted frame sequence. */
  private class RecordingSessionHandle(
    private val frames: List<SdkLiveServerMessage> = emptyList()
  ) : LiveSessionHandle {
    val clientContents = mutableListOf<Pair<List<SdkContent>, Boolean>>()
    val realtimeInputs = mutableListOf<String>()
    val toolResponses = mutableListOf<List<SdkFunctionResponse>>()
    var closeCount = 0
      private set

    var cancelCount = 0
      private set

    /**
     * Advances across collections, as a real websocket does: a second collection resumes at the
     * first frame the previous one did not consume, rather than replaying from the start.
     */
    private var cursor = 0

    override fun receive(): Flow<SdkLiveServerMessage> = flow {
      while (cursor < frames.size) emit(frames[cursor++])
    }

    override suspend fun sendClientContent(turns: List<SdkContent>, turnComplete: Boolean) {
      clientContents.add(turns to turnComplete)
    }

    override suspend fun sendRealtimeInput(
      audio: SdkBlob?,
      video: SdkBlob?,
      audioStreamEnd: Boolean?,
      text: String?,
      activityStart: SdkActivityStart?,
      activityEnd: SdkActivityEnd?,
    ) {
      realtimeInputs.add(
        when {
          audio != null -> "audio:${audio.mimeType}"
          video != null -> "video:${video.mimeType}"
          audioStreamEnd != null -> "audioStreamEnd:$audioStreamEnd"
          text != null -> "text:$text"
          activityStart != null -> "activityStart"
          activityEnd != null -> "activityEnd"
          else -> "none"
        }
      )
    }

    override suspend fun sendToolResponse(functionResponses: List<SdkFunctionResponse>) {
      toolResponses.add(functionResponses)
    }

    override fun cancelSession() {
      cancelCount++
    }

    override suspend fun closeSession() {
      closeCount++
    }
  }

  /** Delivers [frame], then fails the way a dropped socket does. */
  private class FailingReceiveHandle(private val frame: SdkLiveServerMessage) :
    LiveSessionHandle by RecordingSessionHandle() {
    override fun receive(): Flow<SdkLiveServerMessage> = flow {
      emit(frame)
      throw IllegalStateException("socket dropped")
    }
  }

  /** Delivers one frame, then fails the way a cancelled SDK websocket job does. */
  private class ForeignCancelReceiveHandle : LiveSessionHandle by RecordingSessionHandle() {
    override fun receive(): Flow<SdkLiveServerMessage> = flow {
      emit(
        SdkLiveServerMessage(
          serverContent =
            SdkLiveServerContent(
              modelTurn = SdkContent(role = "model", parts = listOf(SdkPart(text = "x")))
            )
        )
      )
      throw CancellationException("transport gone")
    }
  }

  private fun connection(handle: LiveSessionHandle, modelVersion: String? = "gemini-live") =
    GeminiLiveConnection(handle, modelVersion)

  @Test
  fun sendContent_plainText_goesAsClientContentCompletingTheTurn() = runBlocking {
    val handle = RecordingSessionHandle()

    connection(handle).sendContent(userMessage("hello"))

    assertEquals(1, handle.clientContents.size)
    assertEquals(true, handle.clientContents.single().second)
    assertEquals("hello", handle.clientContents.single().first.single().parts?.single()?.text)
    assertTrue(handle.toolResponses.isEmpty())
  }

  @Test
  fun sendContent_partial_leavesTheTurnOpen() = runBlocking {
    // Hardcoding turnComplete would make the model answer an unfinished turn.
    val handle = RecordingSessionHandle()

    connection(handle).sendContent(userMessage("half a thought"), partial = true)

    assertEquals(false, handle.clientContents.single().second)
  }

  @Test
  fun sendContent_notPartial_completesTheTurn() = runBlocking {
    // The control that gives the case above its meaning: same call, flag absent.
    val handle = RecordingSessionHandle()

    connection(handle).sendContent(userMessage("a whole thought"))

    assertEquals(true, handle.clientContents.single().second)
  }

  @Test
  fun sendContent_allFunctionResponses_goesAsAToolResponse() = runBlocking {
    // Content made only of function responses is a tool response, not client content.
    val handle = RecordingSessionHandle()
    val content =
      Content(
        role = "user",
        parts =
          listOf(
            Part(
              functionResponse = FunctionResponse(name = "f", response = mapOf("x" to 1), id = "1")
            ),
            Part(
              functionResponse = FunctionResponse(name = "g", response = mapOf("y" to 2), id = "2")
            ),
          ),
      )

    connection(handle).sendContent(content)

    assertEquals(listOf("1", "2"), handle.toolResponses.single().map { it.id })
    assertTrue(handle.clientContents.isEmpty())
  }

  @Test
  fun sendContent_mixedFunctionResponseAndText_throwsAndSendsNothing() = runBlocking {
    // The LiveConnection contract: content that breaks ContentInput's rules is rejected, not sent.
    val handle = RecordingSessionHandle()
    val content =
      Content(
        role = "user",
        parts =
          listOf(
            Part(
              functionResponse = FunctionResponse(name = "f", response = mapOf("x" to 1), id = "1")
            ),
            Part(text = "and also this"),
          ),
      )

    assertFailsWith<IllegalArgumentException> { connection(handle).sendContent(content) }

    assertTrue(handle.toolResponses.isEmpty())
    assertTrue(handle.clientContents.isEmpty())
  }

  @Test
  fun sendContent_functionCall_throwsAndSendsNothing() = runBlocking {
    val handle = RecordingSessionHandle()

    assertFailsWith<IllegalArgumentException> {
      connection(handle).sendContent(userMessage(Part(functionCall = FunctionCall(name = "doIt"))))
    }

    assertTrue(handle.clientContents.isEmpty())
    assertTrue(handle.toolResponses.isEmpty())
  }

  @Test
  fun sendContent_partialFunctionResponses_throwsAndSendsNothing() = runBlocking {
    val handle = RecordingSessionHandle()
    val content =
      userMessage(
        Part(functionResponse = FunctionResponse(name = "f", response = mapOf("x" to 1), id = "1"))
      )

    assertFailsWith<IllegalArgumentException> {
      connection(handle).sendContent(content, partial = true)
    }

    assertTrue(handle.toolResponses.isEmpty())
    assertTrue(handle.clientContents.isEmpty())
  }

  @Test
  fun sendHistory_endingWithUser_completesTheTurn() = runBlocking {
    val handle = RecordingSessionHandle()

    connection(handle).sendHistory(listOf(modelMessage("hi"), userMessage("and you?")))

    assertEquals(true, handle.clientContents.single().second)
  }

  @Test
  fun sendHistory_endingWithModel_leavesTheTurnOpen() = runBlocking {
    // The model should wait for new user input rather than answering itself.
    val handle = RecordingSessionHandle()

    connection(handle).sendHistory(listOf(userMessage("hello"), modelMessage("hi")))

    assertEquals(false, handle.clientContents.single().second)
  }

  @Test
  fun sendHistory_endingWithFunctionResponse_completesTheTurnAsClientContent() = runBlocking {
    // A function response has the user role, so the model answers it, as in ADK Python.
    val handle = RecordingSessionHandle()
    val response =
      Content(
        role = "user",
        parts =
          listOf(
            Part(
              functionResponse = FunctionResponse(name = "f", response = mapOf("x" to 1), id = "1")
            )
          ),
      )

    connection(handle).sendHistory(listOf(userMessage("hi"), response))

    val (turns, turnComplete) = handle.clientContents.single()
    assertEquals(true, turnComplete)
    assertEquals(2, turns.size)
    assertEquals("1", turns.last().parts?.single()?.functionResponse?.id)
    assertTrue(handle.toolResponses.isEmpty())
  }

  @Test
  fun sendHistory_dropsAudioParts() = runBlocking {
    // Replaying audio into a live session corrupts it, and the audio is already transcribed.
    val handle = RecordingSessionHandle()
    val withAudio =
      Content(
        role = "user",
        parts =
          listOf(
            Part(inlineData = Blob(mimeType = "audio/pcm;rate=16000", data = byteArrayOf(1))),
            Part(text = "keep me"),
          ),
      )

    connection(handle).sendHistory(listOf(withAudio))

    val parts = handle.clientContents.single().first.single().parts
    assertEquals(1, parts?.size)
    assertEquals("keep me", parts?.single()?.text)
  }

  @Test
  fun sendHistory_onlyAudio_sendsNothing() = runBlocking {
    // Dropping every part must not leave an empty content on the wire.
    val handle = RecordingSessionHandle()
    val audioOnly =
      Content(
        role = "user",
        parts =
          listOf(Part(inlineData = Blob(mimeType = "audio/pcm;rate=16000", data = byteArrayOf(1)))),
      )

    connection(handle).sendHistory(listOf(audioOnly))

    assertTrue(handle.clientContents.isEmpty())
  }

  @Test
  fun sendHistory_dropsFileDataAudioParts() = runBlocking {
    // Audio referenced by file corrupts a replayed session exactly as inline audio does.
    val handle = RecordingSessionHandle()
    val withAudio =
      Content(
        role = "user",
        parts =
          listOf(
            Part(fileData = FileData(mimeType = "audio/pcm", fileUri = "gs://bucket/clip.pcm")),
            Part(text = "keep me"),
          ),
      )

    connection(handle).sendHistory(listOf(withAudio))

    val parts = handle.clientContents.single().first.single().parts
    assertEquals(1, parts?.size)
    assertEquals("keep me", parts?.single()?.text)
  }

  @Test
  fun sendRealtime_mapsEachArmOntoItsOwnWireField() = runBlocking {
    val handle = RecordingSessionHandle()
    val live = connection(handle)

    live.sendRealtime(RealtimeInput.Audio(Blob(mimeType = "audio/pcm;rate=16000")))
    live.sendRealtime(RealtimeInput.Video(Blob(mimeType = "image/jpeg")))
    live.sendRealtime(RealtimeInput.AudioStreamEnd)
    live.sendRealtime(RealtimeInput.ActivityStart)
    live.sendRealtime(RealtimeInput.ActivityEnd)

    assertEquals(
      listOf(
        "audio:audio/pcm;rate=16000",
        "video:image/jpeg",
        "audioStreamEnd:true",
        "activityStart",
        "activityEnd",
      ),
      handle.realtimeInputs,
    )
  }

  @Test
  fun receive_oneFrameCarryingSeveralSignals_fansOutToSeveralResponses() = runBlocking {
    val handle =
      RecordingSessionHandle(
        listOf(
          SdkLiveServerMessage(
            serverContent = SdkLiveServerContent(turnComplete = true),
            usageMetadata = SdkLiveUsageMetadata(responseTokenCount = 5),
          )
        )
      )

    val responses = connection(handle).receive().toList()

    assertEquals(2, responses.size)
    assertEquals(5, responses[0].usageMetadata?.candidatesTokenCount)
    assertEquals(true, responses[1].turnComplete)
  }

  @Test
  fun receive_stampsModelVersionAndTheSessionIdFromSetupComplete() = runBlocking {
    // The SDK does not retain the session id, so it is read off setup-complete.
    val handle =
      RecordingSessionHandle(
        listOf(
          SdkLiveServerMessage(setupComplete = SdkLiveServerSetupComplete(sessionId = "sess-1")),
          SdkLiveServerMessage(
            serverContent =
              SdkLiveServerContent(
                modelTurn = SdkContent(role = "model", parts = listOf(SdkPart(text = "hi")))
              )
          ),
        )
      )

    val responses = connection(handle).receive().toList()

    // setupComplete itself produces no response.
    assertEquals(1, responses.size)
    assertEquals("sess-1", responses.single().liveSessionId)
    assertEquals("gemini-live", responses.single().modelVersion)
  }

  @Test
  fun receive_emptyServerContentFrames_produceNoResponses() = runBlocking {
    val handle =
      RecordingSessionHandle(
        listOf(
          SdkLiveServerMessage(setupComplete = SdkLiveServerSetupComplete()),
          SdkLiveServerMessage(serverContent = SdkLiveServerContent()),
        )
      )

    assertEquals(emptyList(), connection(handle).receive().toList())
  }

  @Test
  fun receive_endsTheCollectionAtTurnComplete() = runBlocking {
    // One turn per collection, mirroring Python's break after turn-complete.
    val handle =
      RecordingSessionHandle(
        listOf(
          SdkLiveServerMessage(
            serverContent =
              SdkLiveServerContent(
                modelTurn = SdkContent(role = "model", parts = listOf(SdkPart(text = "hi")))
              )
          ),
          SdkLiveServerMessage(serverContent = SdkLiveServerContent(turnComplete = true)),
          SdkLiveServerMessage(
            serverContent =
              SdkLiveServerContent(
                modelTurn = SdkContent(role = "model", parts = listOf(SdkPart(text = "next turn")))
              )
          ),
        )
      )

    val firstTurn = connection(handle).receive().toList()

    assertEquals(2, firstTurn.size)
    assertEquals("hi", firstTurn[0].content?.parts?.single()?.text)
    assertEquals(true, firstTurn[1].turnComplete)
  }

  @Test
  fun receive_collectedAgain_resumesAfterTheTurnBoundary() = runBlocking {
    // The trailing sessionResumptionUpdate arrives after turn-complete, in its own frame.
    val handle =
      RecordingSessionHandle(
        listOf(
          SdkLiveServerMessage(serverContent = SdkLiveServerContent(turnComplete = true)),
          SdkLiveServerMessage(
            sessionResumptionUpdate = SdkSessionResumptionUpdate(newHandle = "handle-1")
          ),
        )
      )
    val live = connection(handle)

    val firstTurn = live.receive().toList()
    val afterTurn = live.receive().toList()

    assertEquals(true, firstTurn.single().turnComplete)
    assertEquals("handle-1", afterTurn.single().liveSessionResumptionUpdate?.newHandle)
    // Ending a turn must not end the session.
    assertEquals(0, handle.closeCount)
  }

  @Test
  fun receive_frameWithTurnCompleteAndAnotherSignal_emitsBothBeforeStopping() = runBlocking {
    // Deliberately differs from Python: the whole frame is emitted before stopping.
    val handle =
      RecordingSessionHandle(
        listOf(
          SdkLiveServerMessage(
            serverContent = SdkLiveServerContent(turnComplete = true),
            sessionResumptionUpdate = SdkSessionResumptionUpdate(newHandle = "same-frame"),
          )
        )
      )

    val responses = connection(handle).receive().toList()

    assertEquals(2, responses.size)
    assertEquals(true, responses[0].turnComplete)
    assertEquals("same-frame", responses[1].liveSessionResumptionUpdate?.newHandle)
  }

  @Test
  fun receive_transcriptionChunks_arePartialThenFlushedAsOneAggregate() = runBlocking {
    // The server does not mark a transcription's last piece, so the aggregate flushes at turn end.
    val handle =
      RecordingSessionHandle(
        listOf(
          SdkLiveServerMessage(
            serverContent =
              SdkLiveServerContent(outputTranscription = SdkTranscription(text = "ba"))
          ),
          SdkLiveServerMessage(
            serverContent =
              SdkLiveServerContent(outputTranscription = SdkTranscription(text = "nana"))
          ),
          SdkLiveServerMessage(serverContent = SdkLiveServerContent(turnComplete = true)),
        )
      )

    val responses = connection(handle).receive().toList()

    val chunks = responses.filter { it.partial }.mapNotNull { it.outputTranscription?.text }
    assertEquals(listOf("ba", "nana"), chunks)
    val aggregate = responses.single { it.outputTranscription?.finished == true }
    assertEquals("banana", aggregate.outputTranscription?.text)
    assertEquals(false, aggregate.partial)
  }

  @Test
  fun receive_transcriptionFlush_landsBeforeTheTurnCompleteResponse() = runBlocking {
    // Turn-complete ends the collection, so an aggregate after it never reaches the caller.
    val handle =
      RecordingSessionHandle(
        listOf(
          SdkLiveServerMessage(
            serverContent =
              SdkLiveServerContent(outputTranscription = SdkTranscription(text = "hi"))
          ),
          SdkLiveServerMessage(serverContent = SdkLiveServerContent(turnComplete = true)),
        )
      )

    val responses = connection(handle).receive().toList()

    val flushIndex = responses.indexOfFirst { it.outputTranscription?.finished == true }
    val turnCompleteIndex = responses.indexOfFirst { it.turnComplete == true }
    assertTrue(
      flushIndex in 0 until turnCompleteIndex,
      "flush=$flushIndex turnComplete=$turnCompleteIndex",
    )
  }

  @Test
  fun receive_generationCompleteAlone_flushesTheTranscription() = runBlocking {
    // generationComplete produces no response of its own but must still flush.
    val handle =
      RecordingSessionHandle(
        listOf(
          SdkLiveServerMessage(
            serverContent =
              SdkLiveServerContent(inputTranscription = SdkTranscription(text = "what is"))
          ),
          SdkLiveServerMessage(serverContent = SdkLiveServerContent(generationComplete = true)),
        )
      )

    val responses = connection(handle).receive().toList()

    assertEquals(
      "what is",
      responses.single { it.inputTranscription?.finished == true }.inputTranscription?.text,
    )
  }

  @Test
  fun receive_interrupted_flushesTheTranscription() = runBlocking {
    val handle =
      RecordingSessionHandle(
        listOf(
          SdkLiveServerMessage(
            serverContent =
              SdkLiveServerContent(outputTranscription = SdkTranscription(text = "half a sen"))
          ),
          SdkLiveServerMessage(serverContent = SdkLiveServerContent(interrupted = true)),
        )
      )

    val responses = connection(handle).receive().toList()

    assertEquals(
      "half a sen",
      responses.single { it.outputTranscription?.finished == true }.outputTranscription?.text,
    )
  }

  @Test
  fun receive_serverMarksTheChunkFinished_flushesWithoutWaiting() = runBlocking {
    val handle =
      RecordingSessionHandle(
        listOf(
          SdkLiveServerMessage(
            serverContent =
              SdkLiveServerContent(
                inputTranscription = SdkTranscription(text = "done", finished = true)
              )
          )
        )
      )

    val responses = connection(handle).receive().toList()

    assertEquals("done", responses.single { !it.partial }.inputTranscription?.text)
    // Only the aggregate is finished; a chunk marked finished would read as the whole transcript.
    assertEquals(false, responses.single { it.partial }.inputTranscription?.finished)
  }

  @Test
  fun receive_serverMarksTheOutputChunkFinished_flushesWithoutWaiting() = runBlocking {
    val handle =
      RecordingSessionHandle(
        listOf(
          SdkLiveServerMessage(
            serverContent =
              SdkLiveServerContent(
                outputTranscription = SdkTranscription(text = "done", finished = true)
              )
          )
        )
      )

    val responses = connection(handle).receive().toList()

    assertEquals("done", responses.single { !it.partial }.outputTranscription?.text)
    assertEquals(false, responses.single { it.partial }.outputTranscription?.finished)
  }

  @Test
  fun receive_gemini3XLiveInputTranscription_isReportedExactlyOnce() = runBlocking {
    // 3.x sends the whole transcript in one final frame; a partial as well would record it twice.
    val handle =
      RecordingSessionHandle(
        listOf(
          SdkLiveServerMessage(
            serverContent =
              SdkLiveServerContent(
                inputTranscription = SdkTranscription(text = "what is the weather", finished = true)
              )
          )
        )
      )

    val responses =
      connection(handle, modelVersion = "gemini-3.0-flash-live").receive().toList().filter {
        it.inputTranscription != null
      }

    assertEquals(1, responses.size)
    assertEquals("what is the weather", responses.single().inputTranscription?.text)
    assertEquals(false, responses.single().partial)
    assertEquals(true, responses.single().inputTranscription?.finished)
  }

  @Test
  fun receive_transcriptionFlushedOnce_doesNotRepeatOnTheNextTurn() = runBlocking {
    // A cleared buffer must not resurface, or the second turn reports the first turn's speech.
    val handle =
      RecordingSessionHandle(
        listOf(
          SdkLiveServerMessage(
            serverContent =
              SdkLiveServerContent(outputTranscription = SdkTranscription(text = "one"))
          ),
          SdkLiveServerMessage(serverContent = SdkLiveServerContent(turnComplete = true)),
          SdkLiveServerMessage(serverContent = SdkLiveServerContent(turnComplete = true)),
        )
      )
    val live = connection(handle)

    live.receive().toList()
    val secondTurn = live.receive().toList()

    assertTrue(
      secondTurn.none { it.outputTranscription != null },
      "the first turn's transcription came back: $secondTurn",
    )
  }

  @Test
  fun receive_sessionFails_rethrowsTheFailure() = runBlocking {
    val handle =
      FailingReceiveHandle(
        SdkLiveServerMessage(
          serverContent =
            SdkLiveServerContent(inputTranscription = SdkTranscription(text = "partial"))
        )
      )
    val seen = mutableListOf<LlmResponse>()

    val failure =
      runCatching { connection(handle).receive().collect { seen.add(it) } }.exceptionOrNull()

    assertEquals(1, seen.size)
    assertEquals("socket dropped", (failure as? IllegalStateException)?.message)
  }

  @Test
  fun sendContent_contentWithoutParts_throwsAndSendsNothing() = runBlocking {
    val handle = RecordingSessionHandle()

    assertFailsWith<IllegalArgumentException> {
      connection(handle).sendContent(Content(role = "user", parts = emptyList()))
    }

    assertTrue(handle.clientContents.isEmpty())
  }

  @Test
  fun close_onOpenConnection_performsGracefulTeardown() = runBlocking {
    // The blocking bridge must reach closeSession()'s graceful path, not the SDK's abrupt cancel.
    val handle = RecordingSessionHandle()

    connection(handle).close()

    assertEquals(1, handle.closeCount, "close() must reach the graceful session close")
    assertEquals(0, handle.cancelCount, "close() must not fall back to the abrupt cancel")
  }

  @Test
  fun close_afterCloseSession_doesNothing() = runBlocking {
    // Idempotent via tearDown()'s CAS - a second teardown neither closes nor cancels again.
    val handle = RecordingSessionHandle()
    val connection = connection(handle)
    connection.closeSession()
    val closesAfterFirst = handle.closeCount

    connection.close()

    assertEquals(1, closesAfterFirst, "the first closeSession() closes exactly once")
    assertEquals(closesAfterFirst, handle.closeCount, "close() after closeSession() is a no-op")
    assertEquals(0, handle.cancelCount, "no abrupt cancel on either teardown")
  }

  @Test
  fun receive_afterOurOwnCloseSession_completesEmptyWithoutThrowing(): Unit = runBlocking {
    // A receive() collected after our own closeSession() must end empty, not throw.
    val connection = connection(ParkingSessionHandle(emptyList()))
    connection.closeSession()

    val responses = withTimeout(5.seconds) { connection.receive().toList() }

    assertEquals(emptyList<LlmResponse>(), responses)
  }

  @Test
  fun receive_inFlightWhenClosedFromAnotherCoroutine_endsWithoutThrowing(): Unit = runBlocking {
    // A collection in flight when closeSession() runs from another coroutine ends cleanly.
    val handle =
      ParkingSessionHandle(
        listOf(
          SdkLiveServerMessage(
            serverContent =
              SdkLiveServerContent(
                modelTurn = SdkContent(role = "model", parts = listOf(SdkPart(text = "hi")))
              )
          )
        )
      )
    val connection = connection(handle)
    val firstFrameSeen = CompletableDeferred<Unit>()
    val seen = mutableListOf<LlmResponse>()

    val collector = launch {
      connection.receive().collect {
        seen.add(it)
        firstFrameSeen.complete(Unit)
      }
    }
    firstFrameSeen.await()
    connection.closeSession()

    withTimeout(5.seconds) { collector.join() }
    assertTrue(seen.isNotEmpty(), "the in-flight collection saw no frames")
  }

  @Test
  fun receive_sessionFlowThrowsForeignCancellation_surfacesAsFailureNotQuietEnd(): Unit =
    runBlocking {
      // The SDK's own websocket job, cancelled under us, rethrows a CancellationException; the
      // collector must see a failure, not a normal close.
      val connection = connection(ForeignCancelReceiveHandle())

      val error =
        runCatching { withTimeout(5.seconds) { connection.receive().toList() } }.exceptionOrNull()

      assertTrue(
        error is IllegalStateException,
        "receive() must fail when the session is cancelled under us; got $error",
      )
    }

  @Test
  fun closeSession_closesTheSessionGracefully() = runBlocking {
    val handle = RecordingSessionHandle()

    connection(handle).closeSession()

    assertEquals(1, handle.closeCount)
    // The graceful close answered, so the forceful drop must not also run.
    assertEquals(0, handle.cancelCount)
  }

  // The suppressed lint names the hazard under test: closing from a cancelled `finally`.
  @Suppress("SuspendInFinally")
  @Test
  fun closeSession_calledFromACancelledCoroutine_stillClosesTheSession() = runBlocking {
    // Without an uninterruptible teardown the suspending close abandons itself here.
    val handle = SuspendingCloseSessionHandle()
    val liveConnection = connection(handle)
    val started = CompletableDeferred<Unit>()

    val job = launch {
      try {
        started.complete(Unit)
        awaitCancellation()
      } finally {
        liveConnection.closeSession()
      }
    }
    started.await()
    job.cancelAndJoin()

    assertTrue(handle.closed)
  }

  @Test
  fun closeSession_whenTheGracefulCloseFails_stillStopsTheReader(): Unit = runBlocking {
    // A throwing close is likeliest on the dropped connection this cleanup exists for.
    val handle = FailingCloseSessionHandle()
    val failing = connection(handle)
    // Without this the pump may not have started, and the assertion below would misfire.
    handle.started.await()

    assertFailsWith<IllegalStateException> { failing.closeSession() }

    // `isCompleted`, not `await()`: the reader must ALREADY have unwound when close returned.
    assertTrue(handle.unwound.isCompleted)
    // The graceful close did not complete, so the socket is dropped on this path too.
    assertTrue(handle.cancelled)
  }

  @Test
  fun closeSession_whenTheGracefulCloseNeverAnswers_givesUpAndStopsTheReader(): Unit = runBlocking {
    // The teardown is uninterruptible, so a peer that never answers would hang the run.
    val handle = HangingCloseSessionHandle()
    val liveConnection =
      GeminiLiveConnection(handle, "gemini-live", closeTimeout = 100.milliseconds)
    handle.started.await()

    assertNotNull(withTimeoutOrNull(60.seconds) { liveConnection.closeSession() })
    // The close never answered, so the socket is dropped and the reader must have unwound.
    assertTrue(handle.cancelled)
    assertTrue(handle.unwound.isCompleted)
  }

  /** Long enough that a missing `pump.join()` is observable, short enough to cost nothing. */
  private companion object {
    val UNWIND_DELAY = 200.milliseconds
  }

  /** A session whose graceful close never returns, as one to a dead peer does. */
  private class HangingCloseSessionHandle : LiveSessionHandle {
    @Volatile var cancelled = false
    /** Completed once the reader is parked, so a test can close only after the pump has started. */
    val started = CompletableDeferred<Unit>()
    /** Completed when the reader unwinds; `isCompleted` is what pins `tearDown`'s join. */
    val unwound = CompletableDeferred<Unit>()

    override fun cancelSession() {
      cancelled = true
    }

    override fun receive(): Flow<SdkLiveServerMessage> = flow {
      try {
        started.complete(Unit)
        awaitCancellation()
      } finally {
        // Unwinding takes a moment, under NonCancellable, so the assertion can detect the join.
        withContext(NonCancellable) { delay(UNWIND_DELAY) }
        unwound.complete(Unit)
      }
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

    override suspend fun closeSession() {
      awaitCancellation()
    }
  }

  /** A session whose graceful close suspends, as a real websocket handshake does. */
  private class SuspendingCloseSessionHandle : LiveSessionHandle {
    var closed = false

    override fun receive(): Flow<SdkLiveServerMessage> = flow { awaitCancellation() }

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

    override suspend fun closeSession() {
      yield()
      closed = true
    }
  }

  /** A session whose graceful close fails, as one on a dropped socket does. */
  private class FailingCloseSessionHandle : LiveSessionHandle {
    @Volatile var cancelled = false
    /** Completed once the reader is parked, so a test can close only after the pump has started. */
    val started = CompletableDeferred<Unit>()
    /** Completed when the reader unwinds; `isCompleted` is what pins `tearDown`'s join. */
    val unwound = CompletableDeferred<Unit>()

    override fun cancelSession() {
      cancelled = true
    }

    override fun receive(): Flow<SdkLiveServerMessage> = flow {
      try {
        started.complete(Unit)
        awaitCancellation()
      } finally {
        // Unwinding takes a moment, under NonCancellable, so the assertion can detect the join.
        withContext(NonCancellable) { delay(UNWIND_DELAY) }
        unwound.complete(Unit)
      }
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

    override suspend fun closeSession() {
      throw IllegalStateException("socket already gone")
    }
  }

  @Test
  fun receive_nullModelVersion_leavesItUnset() = runBlocking {
    val handle =
      RecordingSessionHandle(
        listOf(SdkLiveServerMessage(serverContent = SdkLiveServerContent(turnComplete = true)))
      )

    assertNull(connection(handle, modelVersion = null).receive().single().modelVersion)
  }

  private suspend fun Flow<LlmResponse>.single(): LlmResponse = toList().single()

  /**
   * A session that delivers its frames and then stays open, the way a socket does.
   *
   * [RecordingSessionHandle] completes when its frames run out, so tests using it check that the
   * SCRIPT ended rather than that the TURN did, and a connection that never ends a collection
   * passes them. A real server does not oblige: the frames arrive, the read parks, and a caller
   * that expected its turn back never gets one.
   */
  private class ParkingSessionHandle(private val frames: List<SdkLiveServerMessage>) :
    LiveSessionHandle {
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

  /**
   * Models the SDK's own receive loop, which catches Exception and then awaits the close reason.
   *
   * CancellationException IS an Exception in Kotlin, so an operator that stops early has its
   * cancellation swallowed by that catch, and the handler then suspends on a close that has not
   * happened. See google_genai Live.kt: `catch (e: Exception) { session.closeReason.await() }`.
   */
  private class SdkShapedSessionHandle(private val frames: List<SdkLiveServerMessage>) :
    LiveSessionHandle {
    private val closeReason = CompletableDeferred<Unit>()

    override fun receive(): Flow<SdkLiveServerMessage> = flow {
      try {
        for (frame in frames) emit(frame)
        awaitCancellation()
      } catch (e: Exception) {
        closeReason.await()
        throw e
      }
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

    override suspend fun closeSession() {
      closeReason.complete(Unit)
    }
  }

  @Test
  fun receive_turnCompleteAgainstAnSdkShapedStream_endsTheCollection() = runBlocking {
    val handle =
      SdkShapedSessionHandle(
        listOf(SdkLiveServerMessage(serverContent = SdkLiveServerContent(turnComplete = true)))
      )

    val seen = mutableListOf<LlmResponse>()
    val liveConnection = connection(handle)
    val ended =
      try {
        withTimeoutOrNull(5.seconds) {
          liveConnection.receive().collect { seen.add(it) }
          true
        } ?: false
      } finally {
        withContext(NonCancellable) { liveConnection.closeSession() }
      }

    assertTrue(ended, "the collection never ended; ${seen.size} responses arrived first")
  }

  /** The same, but channel-backed, which is the shape the SDK's session actually exposes. */
  private class ChannelSessionHandle(frames: List<SdkLiveServerMessage>) : LiveSessionHandle {
    private val incoming = Channel<SdkLiveServerMessage>(Channel.UNLIMITED)

    init {
      for (frame in frames) check(incoming.trySend(frame).isSuccess)
    }

    override fun receive(): Flow<SdkLiveServerMessage> = incoming.receiveAsFlow()

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

  @Test
  fun receive_turnCompleteOnAnOpenChannel_endsTheCollection() = runBlocking {
    val handle =
      ChannelSessionHandle(
        listOf(SdkLiveServerMessage(serverContent = SdkLiveServerContent(turnComplete = true)))
      )

    val seen = mutableListOf<LlmResponse>()
    val liveConnection = connection(handle)
    val ended =
      try {
        withTimeoutOrNull(5.seconds) {
          liveConnection.receive().collect { seen.add(it) }
          true
        } ?: false
      } finally {
        withContext(NonCancellable) { liveConnection.closeSession() }
      }

    assertTrue(ended, "the collection never ended; ${seen.size} responses arrived first")
  }

  @Test
  fun receive_turnCompleteOnAStreamThatStaysOpen_endsTheCollection() = runBlocking {
    // Multi-turn: if the collection does not end, there is no second turn.
    val handle =
      ParkingSessionHandle(
        listOf(
          SdkLiveServerMessage(
            serverContent =
              SdkLiveServerContent(
                modelTurn = SdkContent(role = "model", parts = listOf(SdkPart(text = "hi")))
              )
          ),
          SdkLiveServerMessage(serverContent = SdkLiveServerContent(turnComplete = true)),
        )
      )

    // Collected into a list this test owns, so a timeout still reports what arrived.
    val seen = mutableListOf<LlmResponse>()
    val liveConnection = connection(handle)
    val ended =
      try {
        withTimeoutOrNull(5.seconds) {
          liveConnection.receive().collect { seen.add(it) }
          true
        } ?: false
      } finally {
        withContext(NonCancellable) { liveConnection.closeSession() }
      }

    assertTrue(ended, "the collection never ended; ${seen.size} responses arrived first")
    assertTrue(
      seen.any { it.turnComplete == true },
      "expected turn completion among ${seen.size} responses",
    )
  }

  @Test
  fun receive_groundingOnEarlierFrames_reachesTurnCompleteWithRebasedIndices() = runBlocking {
    // Chunk indices are frame-local, so the second frame's [0] has to become [1] once merged.
    val handle =
      ParkingSessionHandle(
        listOf(
          groundedFrame("Paris", "atlas"),
          groundedFrame(" is the capital.", "almanac"),
          SdkLiveServerMessage(serverContent = SdkLiveServerContent(turnComplete = true)),
        )
      )

    val seen = mutableListOf<LlmResponse>()
    val liveConnection = connection(handle)
    try {
      withTimeoutOrNull(5.seconds) { liveConnection.receive().collect { seen.add(it) } }
    } finally {
      withContext(NonCancellable) { liveConnection.closeSession() }
    }

    val merged = assertNotNull(seen.single { it.turnComplete == true }.groundingMetadata)
    val chunks = assertNotNull(merged.groundingChunks)
    val supports = assertNotNull(merged.groundingSupports)
    // Resolved through the indices: a count would pass even with every citation mis-pointed.
    assertEquals(
      listOf("atlas", "almanac"),
      supports.map { chunks[it.groundingChunkIndices!!.single()].web?.title },
    )
  }

  @Test
  fun mergeGroundingMetadata_queryRepeatedInOneFrame_isKeptOnce() {
    val merged =
      mergeGroundingMetadata(
        GroundingMetadata(webSearchQueries = listOf("a")),
        GroundingMetadata(webSearchQueries = listOf("b", "b")),
      )

    assertEquals(listOf("a", "b"), merged?.webSearchQueries)
  }

  @Test
  fun mergeGroundingMetadata_shiftsLaterSupportIndicesByTheChunksAlreadyHeld() {
    val existing =
      GroundingMetadata(
        groundingChunks = listOf(GroundingChunk(web = GroundingChunkWeb(title = "atlas"))),
        groundingSupports = listOf(GroundingSupport(groundingChunkIndices = listOf(0))),
      )
    val incoming =
      GroundingMetadata(
        groundingChunks = listOf(GroundingChunk(web = GroundingChunkWeb(title = "almanac"))),
        groundingSupports = listOf(GroundingSupport(groundingChunkIndices = listOf(0))),
      )

    val merged = assertNotNull(mergeGroundingMetadata(existing, incoming))

    assertEquals(
      listOf(listOf(0), listOf(1)),
      merged.groundingSupports?.map { it.groundingChunkIndices },
    )
  }

  private fun groundedFrame(text: String, title: String) =
    SdkLiveServerMessage(
      serverContent =
        SdkLiveServerContent(
          modelTurn = SdkContent(role = "model", parts = listOf(SdkPart(text = text))),
          groundingMetadata =
            SdkGroundingMetadata(
              groundingChunks =
                listOf(SdkGroundingChunk(web = SdkGroundingChunkWeb(title = title))),
              groundingSupports = listOf(SdkGroundingSupport(groundingChunkIndices = listOf(0))),
            ),
        )
    )
}
