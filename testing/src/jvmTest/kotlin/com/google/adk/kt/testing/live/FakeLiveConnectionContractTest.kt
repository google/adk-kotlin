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

package com.google.adk.kt.testing.live

import com.google.adk.kt.annotations.ExperimentalLiveApi
import com.google.adk.kt.models.LiveConnection
import com.google.adk.kt.models.LlmResponse
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test

/**
 * Holds [FakeLiveConnection] to the shared [LiveConnectionContract], as core's
 * GeminiLiveConnectionContractTest does for the real connection.
 */
class FakeLiveConnectionContractTest {

  private val contract =
    LiveConnectionContract(
      object : LiveConnectionHarness {
        override suspend fun withConnection(
          frames: List<ScriptedFrame>,
          block: suspend (LiveConnection) -> Unit,
        ) {
          val script = frames.fold(LiveScript.builder(), ::appendFrame).build()
          val connection = FakeLiveConnection(script)
          try {
            block(connection)
          } finally {
            // Bounded: the contract's clause timeout cannot interrupt a close under NonCancellable.
            withContext(NonCancellable) {
              withTimeoutOrNull(5.seconds) { connection.closeSession() }
            }
          }
        }
      }
    )

  private fun appendFrame(builder: LiveScript.Builder, frame: ScriptedFrame): LiveScript.Builder =
    when (frame) {
      is ScriptedFrame.Text -> builder.text(frame.text)
      is ScriptedFrame.TurnComplete -> {
        // The real connection yields the frame's text as its own response before turn complete.
        val afterText = frame.alsoText?.let { builder.text(it, partial = false) } ?: builder
        afterText.respond(LlmResponse(turnComplete = true))
      }
      ScriptedFrame.StreamEnd -> builder.endStream()
      is ScriptedFrame.StreamFails -> builder.failWith(frame.cause)
    }

  @Test
  fun receive_secondConcurrentCollection_isRejected(): Unit = runBlocking {
    contract.receiveSecondConcurrentCollectionIsRejected()
  }

  @Test
  fun receive_afterEarlierCollectionFinished_resumesRatherThanRestarts(): Unit = runBlocking {
    contract.receiveAfterEarlierCollectionFinishedResumesRatherThanRestarts()
  }

  @Test
  fun closeSession_whileCollecting_completesTheCollection(): Unit = runBlocking {
    contract.closeSessionWhileCollectingCompletesTheCollection()
  }

  @Test
  fun receive_streamEnds_completesTheCollection(): Unit = runBlocking {
    contract.receiveStreamEndsCompletesTheCollection()
  }

  @Test
  fun receive_turnCompletes_endsTheCollectionAndLeavesTheConnectionOpen(): Unit = runBlocking {
    contract.receiveTurnCompletesEndsTheCollectionAndLeavesTheConnectionOpen()
  }

  @Test
  fun receive_turnCompleteSharesItsFrame_deliversBothBeforeEnding(): Unit = runBlocking {
    contract.receiveTurnCompleteSharesItsFrameDeliversBothBeforeEnding()
  }

  @Test
  fun closeSession_calledTwice_doesNotThrow(): Unit = runBlocking {
    contract.closeSessionCalledTwiceDoesNotThrow()
  }

  @Test
  fun close_afterCloseSession_doesNotThrow(): Unit = runBlocking {
    contract.closeAfterCloseSessionDoesNotThrow()
  }

  @Test
  fun sendContent_invalidContent_throws(): Unit = runBlocking {
    contract.sendContentInvalidContentThrows()
  }

  @Test
  fun receive_streamFails_throwsRatherThanCompleting(): Unit = runBlocking {
    contract.receiveStreamFailsThrowsRatherThanCompleting()
  }
}
