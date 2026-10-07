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
import com.google.adk.kt.models.ContentInput
import com.google.adk.kt.models.LiveConnection
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.RealtimeInput
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.types.Content
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test

/**
 * Shows that [LiveConnectionContract] catches an almost-right connection: each test breaks one rule
 * and checks that the clause for it fails with an [AssertionError]. A clause that can only see a
 * hang fails by its timeout instead, whose message names the clause.
 */
class LiveConnectionContractTest {

  @Test
  fun secondConcurrentCollectionClause_connectionAllowsIt_fails(): Unit = runBlocking {
    val broken =
      BrokenConnection(
        responses = listOf(modelText("first"), modelText("second")),
        parkAfterResponses = true,
        replayEveryCollection = true,
      )
    val error =
      assertFailsWith<AssertionError> {
        contractOver(broken).receiveSecondConcurrentCollectionIsRejected()
      }
    assertContains(error.message ?: "", "second collection threw")
  }

  @Test
  fun resumesClause_connectionReplaysFromTheStart_fails(): Unit = runBlocking {
    val broken =
      BrokenConnection(
        responses = listOf(modelText("first"), modelText("second")),
        replayEveryCollection = true,
      )
    val error =
      assertFailsWith<AssertionError> {
        contractOver(broken).receiveAfterEarlierCollectionFinishedResumesRatherThanRestarts()
      }
    assertContains(error.message ?: "", "replayed the stream")
  }

  @Test
  fun closeWhileCollectingClause_closeDoesNotEndTheCollection_fails(): Unit = runBlocking {
    val broken = BrokenConnection(responses = listOf(modelText("first")), parkAfterResponses = true)
    val error =
      assertFailsWith<AssertionError> {
        contractOver(broken).closeSessionWhileCollectingCompletesTheCollection()
      }
    // The broken connection hangs, so this clause can only fail by its timeout.
    assertContains(
      error.message ?: "",
      "closeSessionWhileCollectingCompletesTheCollection did not finish within",
    )
  }

  @Test
  fun streamEndsClause_connectionNeverEnds_fails(): Unit = runBlocking {
    val broken = BrokenConnection(responses = listOf(modelText("only")), parkAfterResponses = true)
    val error =
      assertFailsWith<AssertionError> {
        contractOver(broken).receiveStreamEndsCompletesTheCollection()
      }
    // The broken connection hangs, so this clause can only fail by its timeout.
    assertContains(
      error.message ?: "",
      "receiveStreamEndsCompletesTheCollection did not finish within",
    )
  }

  @Test
  fun turnCompletesClause_connectionRunsPastTheBoundary_fails(): Unit = runBlocking {
    val broken =
      BrokenConnection(
        responses =
          listOf(modelText("during turn"), LlmResponse(turnComplete = true), modelText("next turn"))
      )
    val error =
      assertFailsWith<AssertionError> {
        contractOver(broken).receiveTurnCompletesEndsTheCollectionAndLeavesTheConnectionOpen()
      }
    assertContains(error.message ?: "", "ran past the turn boundary")
  }

  @Test
  fun turnCompleteSharesFrameClause_connectionDropsTheSharedContent_fails(): Unit = runBlocking {
    val broken = BrokenConnection(responses = listOf(LlmResponse(turnComplete = true)))
    val error =
      assertFailsWith<AssertionError> {
        contractOver(broken).receiveTurnCompleteSharesItsFrameDeliversBothBeforeEnding()
      }
    assertContains(error.message ?: "", "The shared text did not reach")
  }

  @Test
  fun turnCompleteSharesFrameClause_connectionSwallowsTurnComplete_fails(): Unit = runBlocking {
    // Ends the collection at turn complete but never delivers that response.
    val broken = BrokenConnection(responses = listOf(modelText("last words")))
    val error =
      assertFailsWith<AssertionError> {
        contractOver(broken).receiveTurnCompleteSharesItsFrameDeliversBothBeforeEnding()
      }
    assertContains(error.message ?: "", "did not end on a delivered turn-complete response")
  }

  @Test
  fun calledTwiceClause_secondCloseThrows_fails(): Unit = runBlocking {
    val broken = BrokenConnection(responses = listOf(modelText("only")), secondCloseThrows = true)
    val error =
      assertFailsWith<AssertionError> { contractOver(broken).closeSessionCalledTwiceDoesNotThrow() }
    assertContains(error.message ?: "", "Closing an already-closed")
  }

  @Test
  fun calledTwiceClause_secondCloseThrowsCancellation_fails(): Unit = runBlocking {
    val broken =
      BrokenConnection(responses = listOf(modelText("only")), secondCloseThrowsCancellation = true)
    val error =
      assertFailsWith<AssertionError> { contractOver(broken).closeSessionCalledTwiceDoesNotThrow() }
    assertContains(error.message ?: "", "Closing an already-closed")
  }

  @Test
  fun calledTwiceClause_secondCloseHangs_reportsClauseTimeout(): Unit = runBlocking {
    val broken = BrokenConnection(responses = listOf(modelText("only")), secondCloseHangs = true)
    val error =
      assertFailsWith<AssertionError> { contractOver(broken).closeSessionCalledTwiceDoesNotThrow() }
    assertContains(error.message ?: "", "closeSessionCalledTwiceDoesNotThrow did not finish within")
  }

  @Test
  fun closeAfterCloseSessionClause_bridgeThrows_fails(): Unit = runBlocking {
    val broken = BrokenConnection(responses = listOf(modelText("only")), closeBridgeThrows = true)
    val error =
      assertFailsWith<AssertionError> { contractOver(broken).closeAfterCloseSessionDoesNotThrow() }
    assertContains(error.message ?: "", "close() after closeSession()")
  }

  @Test
  fun invalidContentClause_connectionAcceptsIt_fails(): Unit = runBlocking {
    val broken = BrokenConnection(responses = listOf(modelText("only")), validatesContent = false)
    val error =
      assertFailsWith<AssertionError> { contractOver(broken).sendContentInvalidContentThrows() }
    assertContains(error.message ?: "", "sendContent accepted")
  }

  @Test
  fun streamFailsClause_connectionEndsQuietly_fails(): Unit = runBlocking {
    // Ends the collection cleanly instead of surfacing the transport failure.
    val broken = BrokenConnection(responses = listOf(modelText("before")))
    val error =
      assertFailsWith<AssertionError> {
        contractOver(broken).receiveStreamFailsThrowsRatherThanCompleting()
      }
    assertContains(error.message ?: "", "ended the collection cleanly")
  }

  @Test
  fun streamFailsClause_connectionThrowsADifferentError_fails(): Unit = runBlocking {
    // Fails, but not with the scripted transport failure.
    val broken =
      BrokenConnection(
        responses = listOf(modelText("before")),
        failsWith = IllegalStateException("some other failure"),
      )
    val error =
      assertFailsWith<AssertionError> {
        contractOver(broken).receiveStreamFailsThrowsRatherThanCompleting()
      }
    assertContains(error.message ?: "", "failed with a different error")
  }

  @Test
  fun turnCompletesClause_connectionEndsWithoutTurnComplete_fails(): Unit = runBlocking {
    // The first collection ends after the turn's content but never delivers turn-complete.
    val broken =
      BrokenConnection(
        responses = listOf(modelText("during turn")),
        secondCollectionResponses = listOf(modelText("next turn")),
      )
    val error =
      assertFailsWith<AssertionError> {
        contractOver(broken).receiveTurnCompletesEndsTheCollectionAndLeavesTheConnectionOpen()
      }
    assertContains(error.message ?: "", "did not end on a delivered turn-complete response")
  }

  @Test
  fun closeWhileCollectingClause_closeCancelsTheCollection_fails(): Unit = runBlocking {
    // Close cancels the in-flight collection rather than completing it.
    val broken =
      BrokenConnection(responses = listOf(modelText("first")), cancelCollectionOnClose = true)
    val error =
      assertFailsWith<AssertionError> {
        contractOver(broken).closeSessionWhileCollectingCompletesTheCollection()
      }
    assertContains(error.message ?: "", "Closing cancelled the collection")
  }

  @Test
  fun secondConcurrentCollectionClause_rejectedCollectorStealsAFrame_fails(): Unit = runBlocking {
    // The rejected second collector takes a frame from the shared source before throwing.
    val broken =
      BrokenConnection(
        responses = listOf(modelText("first"), modelText("second")),
        stealsOnSecondCollect = true,
      )
    val error =
      assertFailsWith<AssertionError> {
        contractOver(broken).receiveSecondConcurrentCollectionIsRejected()
      }
    assertContains(error.message ?: "", "took responses from the active one")
  }

  private fun contractOver(connection: LiveConnection): LiveConnectionContract =
    LiveConnectionContract(
      object : LiveConnectionHarness {
        override suspend fun withConnection(
          frames: List<ScriptedFrame>,
          block: suspend (LiveConnection) -> Unit,
        ) {
          try {
            block(connection)
          } finally {
            // Bounded: the contract's clause timeout cannot interrupt a close under NonCancellable.
            withContext(NonCancellable) {
              withTimeoutOrNull(5.seconds) { connection.closeSession() }
            }
          }
        }
      },
      // A short timeout, so the clauses that would otherwise hang fail fast here.
      1.seconds,
    )
}

private fun modelText(text: String): LlmResponse = LlmResponse(content = modelMessage(text))

/**
 * A [LiveConnection] broken in exactly one way, so a clause can be shown to fail on an almost-right
 * connection rather than only to pass on a correct one. Each flag breaks one rule.
 */
private class BrokenConnection(
  private val responses: List<LlmResponse> = emptyList(),
  private val secondCollectionResponses: List<LlmResponse>? = null,
  private val parkAfterResponses: Boolean = false,
  private val replayEveryCollection: Boolean = false,
  private val cancelCollectionOnClose: Boolean = false,
  private val stealsOnSecondCollect: Boolean = false,
  private val failsWith: Throwable? = null,
  private val secondCloseThrows: Boolean = false,
  private val secondCloseThrowsCancellation: Boolean = false,
  private val secondCloseHangs: Boolean = false,
  private val closeBridgeThrows: Boolean = false,
  private val validatesContent: Boolean = true,
) : LiveConnection {
  private var alreadyCollected = false
  private var closeCount = 0
  private var collectorCount = 0
  private var sharedCursor = 0
  private val closedSignal = CompletableDeferred<Unit>()

  /**
   * Content accepted after validation, kept so the validated value is used rather than discarded.
   */
  val accepted = mutableListOf<ContentInput>()

  override fun receive(): Flow<LlmResponse> = flow {
    val collector = ++collectorCount
    if (stealsOnSecondCollect) {
      // The second collector takes one frame from the shared source, then rejects as a real one
      // does.
      if (collector == 2) {
        if (sharedCursor < responses.size) sharedCursor++
        throw IllegalStateException("broken: second collection stole a frame")
      }
      while (sharedCursor < responses.size) emit(responses[sharedCursor++])
      return@flow
    }
    val toEmit =
      when {
        secondCollectionResponses != null && alreadyCollected -> secondCollectionResponses
        replayEveryCollection || !alreadyCollected -> responses
        else -> emptyList()
      }
    alreadyCollected = true
    for (response in toEmit) emit(response)
    if (failsWith != null) throw failsWith
    // Cancels the collection when the connection closes, instead of completing it.
    if (cancelCollectionOnClose) {
      closedSignal.await()
      throw CancellationException("broken: collection cancelled on close")
    }
    // Parks instead of ending, to break the clauses that require a collection to finish.
    if (parkAfterResponses) awaitCancellation()
  }

  override suspend fun sendHistory(history: List<Content>) {}

  override suspend fun sendContent(content: Content, partial: Boolean) {
    // Skipping validation is the break: a broken connection accepts content a real one rejects.
    if (validatesContent) accepted.add(ContentInput(content, partial))
  }

  override suspend fun sendRealtime(input: RealtimeInput) {}

  override suspend fun closeSession() {
    closeCount++
    closedSignal.complete(Unit)
    // Throws only on the clause's own second close, not the harness teardown that follows it.
    if (secondCloseThrows && closeCount == 2) error("broken: second closeSession threw")
    // A foreign cancellation the connection itself throws, not a cancellation of this scope.
    if (secondCloseThrowsCancellation && closeCount == 2) {
      throw CancellationException("broken: second closeSession cancelled")
    }
    // Hangs on every close after the first, so the clause can only end by its own timeout.
    if (secondCloseHangs && closeCount >= 2) awaitCancellation()
  }

  override fun close() {
    if (closeBridgeThrows) throw IllegalStateException("broken: close() bridge threw")
  }
}
