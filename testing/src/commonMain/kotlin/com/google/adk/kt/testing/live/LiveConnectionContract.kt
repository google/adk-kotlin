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

package com.google.adk.kt.testing.live

import com.google.adk.kt.annotations.ExperimentalLiveApi
import com.google.adk.kt.models.LiveConnection
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** How long a clause waits for the connection to finish before failing instead of hanging. */
private val DEFAULT_CLAUSE_TIMEOUT: Duration = 10.seconds

/** What a connection under test will send, described without reference to any transport. */
@ExperimentalLiveApi
sealed interface ScriptedFrame {
  /** Carries one text response. */
  data class Text(val text: String) : ScriptedFrame

  /**
   * Completes the turn.
   *
   * [alsoText] rides on the same frame when set, and must still reach the caller: a connection that
   * stops the instant it sees turn-complete would drop it.
   */
  data class TurnComplete(val alsoText: String? = null) : ScriptedFrame

  /**
   * Ends the stream itself, as a closing socket does.
   *
   * A clause that expects a collection to finish without a turn boundary must end its frames with
   * this; otherwise the stream stays open, as a real connection does between turns. Ending the
   * stream on its own when the frames run out would let a connection that never ends a turn pass
   * the turn-boundary clauses.
   */
  data object StreamEnd : ScriptedFrame

  /**
   * Fails the stream with [cause], as a dropped transport does.
   *
   * A collection in flight must surface this as a failure rather than a quiet end; a connection
   * that swallowed it would let a caller treat a lost connection as a finished turn. Like
   * [StreamEnd] it is terminal, so it must be the last frame.
   */
  data class StreamFails(val cause: Throwable) : ScriptedFrame
}

/**
 * The connection a suite supplies to [LiveConnectionContract], so one set of clauses runs against
 * every [LiveConnection]. For the fake it plays a script; for a real connection it stands up a fake
 * transport behind that connection.
 */
@ExperimentalLiveApi
interface LiveConnectionHarness {
  /**
   * Opens a connection that will send [frames] in order, runs [block] against it, and tears it down
   * afterwards.
   *
   * Teardown must be bounded, for example with a timeout: the contract runs each clause under a
   * timeout, but that cannot interrupt a close running under `NonCancellable`, so an unbounded
   * close would hang the clause instead of reporting it.
   */
  suspend fun withConnection(frames: List<ScriptedFrame>, block: suspend (LiveConnection) -> Unit)
}

/**
 * The behavior every ADK Kotlin [LiveConnection] must have, whatever its transport; some clauses,
 * such as rejecting a second concurrent collector, are stricter than other ADKs.
 *
 * Each implementation's suite supplies a [LiveConnectionHarness] and adds one thin test per
 * function here, so a new clause covers an implementation only once its suite adds that test.
 */
@ExperimentalLiveApi
class LiveConnectionContract
internal constructor(
  private val harness: LiveConnectionHarness,
  private val clauseTimeout: Duration,
) {

  /** Uses the default clause timeout; the module's own tests pass a shorter one internally. */
  constructor(harness: LiveConnectionHarness) : this(harness, DEFAULT_CLAUSE_TIMEOUT)

  /**
   * A second collector arriving while one is active is rejected, not allowed to split the stream.
   */
  suspend fun receiveSecondConcurrentCollectionIsRejected(): Unit =
    withConnection(
      "receiveSecondConcurrentCollectionIsRejected",
      listOf(ScriptedFrame.Text("first"), ScriptedFrame.Text("second"), ScriptedFrame.StreamEnd),
    ) { connection ->
      coroutineScope {
        val received = mutableListOf<String?>()
        val collecting = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val collector = launch {
          connection.receive().collect {
            received.add(textOf(it))
            collecting.complete(Unit)
            release.await()
          }
        }
        collecting.await()

        val failure = failureOf { connection.receive().take(1).toList() }

        assertTrue(
          failure is IllegalStateException && failure !is CancellationException,
          "second collection threw ${failure?.let { it::class.simpleName }}",
        )
        release.complete(Unit)
        collector.join()
        assertTrue(
          received == listOf("first", "second"),
          "The rejected collection took responses from the active one (${received.size} seen).",
        )
      }
    }

  /**
   * Collecting again after an earlier collection finished continues the stream rather than
   * replaying it.
   */
  suspend fun receiveAfterEarlierCollectionFinishedResumesRatherThanRestarts(): Unit =
    withConnection(
      "receiveAfterEarlierCollectionFinishedResumesRatherThanRestarts",
      listOf(ScriptedFrame.Text("first"), ScriptedFrame.Text("second"), ScriptedFrame.StreamEnd),
    ) { connection ->
      val firstPass = connection.receive().take(1).toList().map(::textOf)

      val secondPass = connection.receive().toList().map(::textOf)

      assertTrue(
        firstPass == listOf("first"),
        "The first collection read the wrong responses (${firstPass.size} seen).",
      )
      assertTrue(
        "first" !in secondPass,
        "Collecting again replayed the stream from the start (${secondPass.size} seen). A " +
          "caller reading one turn at a time would see the same turn twice.",
      )
      assertTrue(
        "second" in secondPass,
        "Collecting again did not resume the stream (${secondPass.size} seen).",
      )
    }

  /** Closing completes a collection that is in flight, rather than leaving it hanging. */
  suspend fun closeSessionWhileCollectingCompletesTheCollection(): Unit =
    withConnection(
      "closeSessionWhileCollectingCompletesTheCollection",
      listOf(ScriptedFrame.Text("first"), ScriptedFrame.Text("second")),
    ) { connection ->
      coroutineScope {
        val collecting = CompletableDeferred<Unit>()
        var completedNormally = false
        val collector = launch {
          connection.receive().collect { collecting.complete(Unit) }
          completedNormally = true
        }
        collecting.await()

        connection.closeSession()

        collector.join()
        assertTrue(completedNormally, "Closing cancelled the collection instead of completing it.")
      }
    }

  /** A stream that ends on its own completes the collection normally. */
  suspend fun receiveStreamEndsCompletesTheCollection(): Unit =
    withConnection(
      "receiveStreamEndsCompletesTheCollection",
      listOf(ScriptedFrame.Text("only"), ScriptedFrame.StreamEnd),
    ) { connection ->
      val responses = connection.receive().toList()

      assertTrue(
        responses.map(::textOf) == listOf("only"),
        "A stream that ran out did not complete its collection cleanly (${responses.size} seen).",
      )
    }

  /**
   * A transport failure reaches the collector as a failure, not as a quiet end.
   *
   * A connection that swallowed a dropped transport and completed the collection would let a caller
   * treat a lost connection as a finished turn.
   */
  suspend fun receiveStreamFailsThrowsRatherThanCompleting() {
    val cause = IllegalStateException("scripted transport failure")
    withConnection(
      "receiveStreamFailsThrowsRatherThanCompleting",
      listOf(ScriptedFrame.Text("before"), ScriptedFrame.StreamFails(cause)),
    ) { connection ->
      val failure = failureOf { connection.receive().toList() }

      assertTrue(
        failure != null && failure !is CancellationException,
        "A transport failure ended the collection cleanly instead of failing it (saw " +
          "${failure?.let { it::class.simpleName } ?: "a clean completion"}).",
      )
      assertTrue(
        failure?.message == cause.message,
        "The collection failed with a different error than the transport failure (saw " +
          "${failure?.let { it::class.simpleName }}: ${failure?.message}).",
      )
    }
  }

  /**
   * A completed turn ends the collection but leaves the connection open for the next one.
   *
   * This is how a caller reads one turn at a time. A connection that kept the collection open would
   * make every turn boundary invisible to it.
   */
  suspend fun receiveTurnCompletesEndsTheCollectionAndLeavesTheConnectionOpen(): Unit =
    withConnection(
      "receiveTurnCompletesEndsTheCollectionAndLeavesTheConnectionOpen",
      listOf(
        ScriptedFrame.Text("during turn"),
        ScriptedFrame.TurnComplete(),
        ScriptedFrame.Text("next turn"),
        ScriptedFrame.StreamEnd,
      ),
    ) { connection ->
      val firstTurnResponses = connection.receive().toList()
      val firstTurn = firstTurnResponses.map(::textOf)

      val secondTurn = connection.receive().toList().map(::textOf)

      assertTrue(
        "during turn" in firstTurn,
        "The first collection did not deliver the turn's own content (${firstTurn.size} seen).",
      )
      assertTrue(
        "next turn" !in firstTurn,
        "The collection ran past the turn boundary and swallowed the next turn " +
          "(${firstTurn.size} seen).",
      )
      assertTrue(
        "next turn" in secondTurn,
        "The connection did not survive the turn boundary; collecting again saw " +
          "${secondTurn.size} responses.",
      )
      val completeAt = firstTurnResponses.indexOfFirst { it.turnComplete == true }
      assertTrue(
        completeAt == firstTurnResponses.lastIndex,
        "The first collection did not end on a delivered turn-complete response " +
          "(${firstTurnResponses.size} seen).",
      )
    }

  /**
   * Everything on the turn-completing frame reaches the caller before the collection ends.
   *
   * A connection that stops the instant it sees turn-complete can drop whatever shared that frame,
   * such as the turn's last model content.
   */
  suspend fun receiveTurnCompleteSharesItsFrameDeliversBothBeforeEnding(): Unit =
    withConnection(
      "receiveTurnCompleteSharesItsFrameDeliversBothBeforeEnding",
      listOf(ScriptedFrame.TurnComplete(alsoText = "last words")),
    ) { connection ->
      val responses = connection.receive().toList()

      val completeAt = responses.indexOfFirst { it.turnComplete == true }
      assertTrue(
        completeAt == responses.lastIndex,
        "The collection did not end on a delivered turn-complete response (${responses.size} seen).",
      )
      assertTrue(
        responses.indexOfFirst { textOf(it) == "last words" } in 0 until completeAt,
        "The shared text did not reach the caller before turn complete (${responses.size} seen).",
      )
    }

  /**
   * Closing twice is safe.
   *
   * [LiveConnection.closeSession] promises the second call does nothing; one that threw would turn
   * an ordinary double close, such as a graceful close followed by a teardown, into a logged
   * failure.
   */
  suspend fun closeSessionCalledTwiceDoesNotThrow(): Unit =
    withConnection("closeSessionCalledTwiceDoesNotThrow", listOf(ScriptedFrame.Text("only"))) {
      connection ->
      connection.closeSession()

      val failure = failureOf { connection.closeSession() }

      assertNull(
        failure,
        "Closing an already-closed connection threw ${failure?.let { it::class.simpleName }}.",
      )
    }

  /**
   * Calling the blocking [AutoCloseable] bridge after [LiveConnection.closeSession] does not throw.
   *
   * A `use` block calls `close()` implicitly, often after the caller already closed gracefully.
   */
  suspend fun closeAfterCloseSessionDoesNotThrow(): Unit =
    withConnection("closeAfterCloseSessionDoesNotThrow", listOf(ScriptedFrame.Text("only"))) {
      connection ->
      connection.closeSession()

      val failure = failureOf { connection.close() }

      assertNull(
        failure,
        "close() after closeSession() threw ${failure?.let { it::class.simpleName }}. " +
          "The bridge exists for `use` and Java callers, so a second teardown through it must be " +
          "as harmless as a second closeSession().",
      )
    }

  /**
   * Content that breaks [com.google.adk.kt.models.ContentInput]'s rules is rejected with
   * [IllegalArgumentException].
   *
   * A connection that sent it anyway would let a caller pass here that the model then rejects.
   */
  suspend fun sendContentInvalidContentThrows(): Unit =
    withConnection("sendContentInvalidContentThrows", listOf(ScriptedFrame.Text("only"))) {
      connection ->
      val answer = Part(functionResponse = FunctionResponse(name = "f", id = "1"))
      val call = Part(functionCall = FunctionCall(name = "f", id = "1"))
      val invalid =
        listOf(
          InvalidContent("no parts", Content(role = Role.USER, parts = emptyList())),
          InvalidContent("a function call", Content(role = Role.USER, parts = listOf(call))),
          InvalidContent(
            "mixed parts",
            Content(role = Role.USER, parts = listOf(answer, Part(text = "x"))),
          ),
          InvalidContent(
            "a partial answer",
            Content(role = Role.USER, parts = listOf(answer)),
            partial = true,
          ),
        )
      for ((shape, content, partial) in invalid) {
        val failure = failureOf { connection.sendContent(content, partial) }

        assertTrue(
          failure is IllegalArgumentException,
          "sendContent accepted $shape (threw ${failure?.let { it::class.simpleName }}).",
        )
      }
    }

  /**
   * Runs one clause's connection block, failing with the clause name if it never finishes.
   *
   * Without this bound, a connection that breaks the very rule a clause tests hangs the clause
   * until the test harness times out, with no indication of which clause it was.
   */
  private suspend fun withConnection(
    clause: String,
    frames: List<ScriptedFrame>,
    block: suspend (LiveConnection) -> Unit,
  ) {
    withTimeoutOrNull(clauseTimeout) { harness.withConnection(frames, block) }
      ?: throw AssertionError(
        "$clause did not finish within $clauseTimeout; a collection, send or close on the " +
          "connection never returned."
      )
  }

  private fun textOf(response: LlmResponse): String? = response.content?.parts?.firstOrNull()?.text

  private data class InvalidContent(
    val shape: String,
    val content: Content,
    val partial: Boolean = false,
  )

  // Local, not kotlin.test: this file ships in the module's main source set.
  private fun assertTrue(actual: Boolean, message: String) {
    if (!actual) throw AssertionError(message)
  }

  private fun assertNull(actual: Any?, message: String) {
    if (actual != null) throw AssertionError(message)
  }

  /**
   * The failure [block] threw, or null. This clause's own cancellation is rethrown so the test can
   * unwind, but a cancellation [block] itself threw is reported as the failure rather than hidden.
   */
  private suspend fun failureOf(block: suspend () -> Unit): Throwable? =
    try {
      block()
      null
    } catch (e: CancellationException) {
      currentCoroutineContext().ensureActive()
      e
    } catch (e: Throwable) {
      e
    }
}
