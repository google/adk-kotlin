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
import com.google.adk.kt.testing.live.LiveConnectionContract
import com.google.adk.kt.testing.live.LiveConnectionHarness
import com.google.adk.kt.testing.live.ScriptedFrame
import com.google.genai.kotlin.types.ActivityEnd as SdkActivityEnd
import com.google.genai.kotlin.types.ActivityStart as SdkActivityStart
import com.google.genai.kotlin.types.Blob as SdkBlob
import com.google.genai.kotlin.types.Content as SdkContent
import com.google.genai.kotlin.types.FunctionResponse as SdkFunctionResponse
import com.google.genai.kotlin.types.LiveServerContent as SdkLiveServerContent
import com.google.genai.kotlin.types.LiveServerMessage as SdkLiveServerMessage
import com.google.genai.kotlin.types.Part as SdkPart
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Holds the real [GeminiLiveConnection] to the same [LiveConnectionContract] as the shared fake, so
 * the two agree on every clause a caller relies on.
 */
class GeminiLiveConnectionContractTest {

  /**
   * Feeds scripted frames to a real [GeminiLiveConnection] in place of the SDK session.
   *
   * The channel stays open, as a real socket does, until the connection closes it or the frames end
   * with [ScriptedFrame.StreamEnd], so the connection itself has to end a collection at a turn
   * boundary. Closing it after the last frame would let a connection that never ends a turn pass
   * the turn-boundary clauses.
   */
  private class ScriptedSessionHandle(
    frames: List<SdkLiveServerMessage>,
    endsStream: Boolean,
    failWith: Throwable? = null,
  ) : LiveSessionHandle {
    private val incoming = Channel<SdkLiveServerMessage>(Channel.UNLIMITED)

    init {
      for (frame in frames) check(incoming.trySend(frame).isSuccess)
      // A failing transport closes the channel with its cause, as a dropped socket does.
      if (failWith != null) incoming.close(failWith) else if (endsStream) incoming.close()
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

    private var sessionClosed = false

    override suspend fun closeSession() {
      // Unlike the SDK, rejects a repeat close, so the clause tests the connection's own guard.
      check(!sessionClosed) { "closeSession called twice on an already-closed session" }
      sessionClosed = true
      incoming.close()
    }
  }

  private class GeminiHarness : LiveConnectionHarness {
    override suspend fun withConnection(
      frames: List<ScriptedFrame>,
      block: suspend (LiveConnection) -> Unit,
    ) {
      val last = frames.lastOrNull()
      val endsStream = last == ScriptedFrame.StreamEnd
      val failWith = (last as? ScriptedFrame.StreamFails)?.cause
      val body = if (endsStream || failWith != null) frames.dropLast(1) else frames
      require(body.none { it == ScriptedFrame.StreamEnd || it is ScriptedFrame.StreamFails }) {
        "StreamEnd and StreamFails must be the last frame"
      }
      val connection =
        GeminiLiveConnection(
          ScriptedSessionHandle(
            body.map { it.toServerMessage() },
            endsStream = endsStream,
            failWith = failWith,
          ),
          modelVersion = "contract-live",
        )
      try {
        block(connection)
      } finally {
        // Bounded: the contract's clause timeout cannot interrupt a close under NonCancellable.
        withContext(NonCancellable) { withTimeoutOrNull(5.seconds) { connection.closeSession() } }
      }
    }
  }

  private val contract = LiveConnectionContract(GeminiHarness())

  @Test
  fun receive_secondConcurrentCollection_isRejected() = runBlocking {
    contract.receiveSecondConcurrentCollectionIsRejected()
  }

  @Test
  fun receive_afterEarlierCollectionFinished_resumesRatherThanRestarts() = runBlocking {
    contract.receiveAfterEarlierCollectionFinishedResumesRatherThanRestarts()
  }

  @Test
  fun closeSession_whileCollecting_completesTheCollection() = runBlocking {
    contract.closeSessionWhileCollectingCompletesTheCollection()
  }

  @Test
  fun receive_streamEnds_completesTheCollection() = runBlocking {
    contract.receiveStreamEndsCompletesTheCollection()
  }

  @Test
  fun receive_turnCompletes_endsTheCollectionAndLeavesTheConnectionOpen() = runBlocking {
    contract.receiveTurnCompletesEndsTheCollectionAndLeavesTheConnectionOpen()
  }

  @Test
  fun receive_turnCompleteSharesItsFrame_deliversBothBeforeEnding() = runBlocking {
    contract.receiveTurnCompleteSharesItsFrameDeliversBothBeforeEnding()
  }

  @Test
  fun closeSession_calledTwice_doesNotThrow() = runBlocking {
    contract.closeSessionCalledTwiceDoesNotThrow()
  }

  @Test
  fun close_afterCloseSession_doesNotThrow() = runBlocking {
    contract.closeAfterCloseSessionDoesNotThrow()
  }

  @Test
  fun sendContent_invalidContent_throws() = runBlocking {
    contract.sendContentInvalidContentThrows()
  }

  @Test
  fun receive_streamFails_throwsRatherThanCompleting() = runBlocking {
    contract.receiveStreamFailsThrowsRatherThanCompleting()
  }
}

private fun ScriptedFrame.toServerMessage(): SdkLiveServerMessage =
  when (this) {
    is ScriptedFrame.Text ->
      SdkLiveServerMessage(serverContent = SdkLiveServerContent(modelTurn = modelTurn(text)))
    is ScriptedFrame.TurnComplete ->
      SdkLiveServerMessage(
        serverContent =
          SdkLiveServerContent(modelTurn = alsoText?.let { modelTurn(it) }, turnComplete = true)
      )
    // Not a frame the server sends; the harness turns it into the channel closing instead.
    ScriptedFrame.StreamEnd -> error("StreamEnd is handled by the harness, not converted")
    is ScriptedFrame.StreamFails -> error("StreamFails is handled by the harness, not converted")
  }

private fun modelTurn(text: String) =
  SdkContent(role = "model", parts = listOf(SdkPart(text = text)))
