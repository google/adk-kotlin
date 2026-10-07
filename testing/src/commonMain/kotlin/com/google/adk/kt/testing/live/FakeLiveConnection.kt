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
import com.google.adk.kt.models.ContentInput
import com.google.adk.kt.models.LiveConnection
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.RealtimeInput
import com.google.adk.kt.types.Content
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

/** How long a gate waits before failing the test rather than hanging it. */
private val DEFAULT_GATE_TIMEOUT: Duration = 5.seconds

private const val CONCURRENT_COLLECTION_MESSAGE =
  "FakeLiveConnection.receive() allows one collector at a time, matching LiveConnection. Collect " +
    "again after the previous collection finishes."

/**
 * A [LiveConnection] that plays a [LiveScript] instead of talking to a model.
 *
 * It reproduces the parts of the real contract that callers get wrong - one collector at a time,
 * re-collection resuming rather than replaying, close completing an in-flight collection - because
 * a double that is more permissive than production lets a broken caller pass. After close it
 * delivers nothing more, whereas a real connection may still deliver frames it had already read.
 */
@ExperimentalLiveApi
class FakeLiveConnection
internal constructor(private val script: LiveScript, private val gateTimeout: Duration) :
  LiveConnection {

  /** Uses the default gate timeout; the module's own tests pass a shorter one internally. */
  constructor(script: LiveScript) : this(script, DEFAULT_GATE_TIMEOUT)

  private val sentState = MutableStateFlow<List<SentLiveMessage>>(emptyList())
  private val closedState = MutableStateFlow(false)
  private val collecting = Mutex()

  /**
   * Position in the script, scoped to the connection rather than to one collection.
   *
   * A second [receive] therefore continues where the first stopped, which is how the real caller
   * reads one turn at a time. Restarting here would let a caller that re-collects silently replay a
   * turn and still pass.
   */
  private var stepCursor = 0

  /** How far the gates have consumed [sent]; only ever touched by the single collector. */
  private var gateCursor = 0

  /** Set by [LiveScriptStep.EndStream]; an ended stream stays ended for every later collection. */
  private var streamEnded = false

  /**
   * Set by [LiveScriptStep.Fail]; a broken transport rethrows the same cause on every collection.
   */
  private var failure: Throwable? = null

  /** Everything sent so far, oldest first. */
  val sent: List<SentLiveMessage>
    get() = sentState.value

  /** Whether [closeSession] has been called. */
  val isClosed: Boolean
    get() = closedState.value

  override fun receive(): Flow<LlmResponse> = flow {
    check(collecting.tryLock()) { CONCURRENT_COLLECTION_MESSAGE }
    try {
      // A failed transport stays broken: every later collection rethrows, as a closed channel does.
      failure?.let { throw it }
      if (streamEnded) return@flow
      while (stepCursor < script.steps.size && !closedState.value) {
        val step = script.steps[stepCursor]
        // A gate advances only once satisfied, so a collection that stops on it re-enters it.
        if (step !is LiveScriptStep.Await) stepCursor++
        when (step) {
          is LiveScriptStep.Respond -> {
            emit(step.response)
            // Turn complete ends the collection, as it does on the real connection.
            if (step.response.turnComplete == true) return@flow
          }
          is LiveScriptStep.Await -> {
            if (!awaitMatch(step)) return@flow
            stepCursor++
          }
          is LiveScriptStep.Fail -> {
            failure = step.cause
            throw step.cause
          }
          // The stream itself ends: return rather than parking, so the collection completes.
          LiveScriptStep.EndStream -> {
            streamEnded = true
            return@flow
          }
        }
      }
      // Parks like an open socket; completing would pass a caller that never ends its collection.
      if (!closedState.value) parkAsAnIdleSocketWould()
    } finally {
      collecting.unlock()
    }
  }

  /** Parks the way an idle socket does, until the connection closes or the test gives up. */
  private suspend fun parkAsAnIdleSocketWould() {
    withTimeoutOrNull(gateTimeout) { closedState.first { it } }
      ?: throw AssertionError(
        "FakeLiveConnection: the script is exhausted and the connection is still open, but the " +
          "collection has not ended after $gateTimeout. A real connection parks here too, so " +
          "whatever ends a turn has to end the collection itself rather than relying on the " +
          "responses running out."
      )
  }

  override suspend fun sendHistory(history: List<Content>) {
    record(SentLiveMessage.History(history))
  }

  override suspend fun sendContent(content: Content, partial: Boolean) {
    // Validates before anything is recorded, as a real connection does before sending.
    val input = ContentInput(content, partial)
    record(SentLiveMessage.ClientContent(input.content, input.partial))
  }

  override suspend fun sendRealtime(input: RealtimeInput) {
    record(SentLiveMessage.Realtime(input))
  }

  override suspend fun closeSession() {
    // A repeated close is a no-op, as the LiveConnection contract requires.
    if (!closedState.compareAndSet(expect = false, update = true)) return
    // Recorded last, so the state a waiting gate wakes up to already says closed.
    record(SentLiveMessage.Closed)
  }

  private fun record(message: SentLiveMessage) {
    sentState.update { it + message }
  }

  /** Returns false when the connection closed before the gate was satisfied. */
  private suspend fun awaitMatch(step: LiveScriptStep.Await): Boolean =
    withTimeoutOrNull(gateTimeout) { scanForMatch(step) }
      ?: throw AssertionError(gateTimeoutMessage(step))

  private suspend fun scanForMatch(step: LiveScriptStep.Await): Boolean {
    while (true) {
      // A cursor lets a send made before this gate match unless an earlier gate passed over it.
      val snapshot = sentState.value
      while (gateCursor < snapshot.size) {
        val candidate = snapshot[gateCursor]
        gateCursor++
        if (step.predicate(candidate)) return true
      }
      if (closedState.value) return false
      // StateFlow replays its current value, so a send between the scan and here is not missed.
      sentState.first { it.size > gateCursor }
    }
  }

  private fun gateTimeoutMessage(step: LiveScriptStep.Await): String {
    val messages = sentState.value
    return buildString {
      append("FakeLiveConnection timed out after $gateTimeout waiting for: ${step.description}.\n")
      append("Sent so far (${messages.size}):")
      if (messages.isEmpty()) {
        append(" nothing")
      } else {
        for (message in messages) append("\n  - ${describeShape(message)}")
      }
    }
  }
}
