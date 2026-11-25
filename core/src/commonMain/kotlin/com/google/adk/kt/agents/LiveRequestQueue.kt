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

@file:OptIn(ExperimentalLiveApi::class, ExperimentalAtomicApi::class)

package com.google.adk.kt.agents

import com.google.adk.kt.annotations.ExperimentalLiveApi
import com.google.adk.kt.models.ContentInput
import com.google.adk.kt.models.RealtimeInput
import com.google.adk.kt.types.Content
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.jvm.JvmOverloads
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The input side of a live conversation: everything the user sends reaches the agent through one of
 * these, from any thread and without suspending, so audio can be pushed from a microphone callback
 * or a UI handler without a coroutine. One live connection reads a queue at a time, so give each
 * concurrent live run its own.
 *
 * The cost is an unbounded queue: a producer faster than the agent grows it without limit, an entry
 * per audio chunk spoken.
 *
 * ADK Python makes the same trade, with an `asyncio.Queue` that sets no maximum.
 */
class LiveRequestQueue @ExperimentalLiveApi constructor() : AutoCloseable {
  // Only the constructor and senders need the opt-in, so a stable type can hold a queue without it.

  /**
   * Holds a request the channel already handed to a [receiveRequest] call that was then cancelled,
   * so the next call returns it instead of losing it. That happens, for example, when a reconnect
   * or a transfer to another agent cancels the reader and starts a new one on the same queue. One
   * slot is enough because [receiving] lets only one call run at a time.
   */
  private val redelivery = AtomicReference<LiveRequest?>(null)
  private val channel =
    Channel<LiveRequest>(Channel.UNLIMITED, onUndeliveredElement = { redelivery.store(it) })

  /**
   * Lets one [receiveRequest] call run at a time, so a cancelled call's request is already in
   * [redelivery] before the next call looks there.
   */
  private val receiving = Mutex()

  /** The coroutine that last claimed this queue by reading it with an agent name. */
  private val reader = AtomicReference<Job?>(null)

  /**
   * Completed by [close], so the live flow can see the caller hang up directly rather than wait for
   * a pending receive to drain, as ADK Python reads `live_request_queue.closed`.
   */
  private val _closed = CompletableDeferred<Unit>()

  // Each set of call ids is skipped until its mark, queued behind their answers, is received.
  private class Discard(val callIds: Set<String>, val mark: LiveRequest = LiveRequest())

  private val discards = AtomicReference<List<Discard>>(emptyList())

  /** Tool answers the agent queued itself, told apart from the caller's by identity. */
  private val ownAnswers = AtomicReference<List<LiveRequest>>(emptyList())

  /** Whether the caller has closed the queue. */
  internal val isClosed: Boolean
    get() = _closed.isCompleted

  /** Completes when the caller closes the queue. */
  internal val closed: Deferred<Unit>
    get() = _closed

  /**
   * Returns the next request in send order, or null after [close] once every request sent before it
   * has been returned, skipping the answers [discardAnswersTo] dropped. Calls run one at a time: a
   * call that is already cancelled takes nothing, and a request taken by a call cancelled while it
   * waited is returned by the next one. A returned request never has [LiveRequest.close] set, and
   * one whose [LiveRequest.input] is null carries only a [LiveRequest.stateDelta], which may be
   * empty.
   */
  internal suspend fun receiveRequest(): LiveRequest? = receiving.withLock { nextKeptRequest() }

  /**
   * Returns the next request, as [receiveRequest] does, for the calling coroutine, this queue's
   * reader until it stops. A queue has one reader at a time, whichever run reads it: two reading at
   * once would each get part of the input, so the second one fails instead, naming its [agent].
   *
   * The reader is the calling coroutine's own [Job], so every read of one connection must come from
   * its reading coroutine itself.
   *
   * @throws IllegalStateException if another reader of this queue is still active; no request is
   *   taken.
   */
  internal suspend fun receiveRequest(agent: String): LiveRequest? {
    claimReader(agent, currentCoroutineContext().job)
    return receiveRequest()
  }

  /**
   * Fails, naming [agent], if another reader of this queue is already reading, so that case opens
   * no connection; [receiveRequest] still claims the reader.
   */
  internal suspend fun checkNoOtherReader(agent: String) {
    currentCoroutineContext().ensureActive()
    check(reader.load()?.isActive != true) { otherReaderMessage(agent) }
  }

  private fun claimReader(agent: String, job: Job) {
    // A cancelled caller stops, rather than failing as a second reader would.
    job.ensureActive()
    while (true) {
      val current = reader.load()
      if (current === job) return
      // A cancelled reader counts as gone, since the next one may start before it finishes.
      check(current == null || !current.isActive) { otherReaderMessage(agent) }
      if (reader.compareAndSet(current, job)) return
    }
  }

  private fun otherReaderMessage(agent: String) =
    "Live agent '$agent' found another reader already reading its LiveRequestQueue; " +
      "live agents that share a queue must read it one at a time."

  private suspend fun nextKeptRequest(): LiveRequest? {
    while (true) {
      // A queued request returns without suspending, so check for cancellation here.
      currentCoroutineContext().ensureActive()
      val request = redelivery.exchange(null) ?: channel.receiveCatching().getOrNull()
      val passed = discards.load().find { it.mark === request }
      when {
        passed != null -> discards.mutate { it - passed }
        request == null || !isDiscarded(request) -> return request
        // A dropped answer of the agent's own leaves no mark behind.
        else -> {
          val unused = takeOwnAnswer(request)
        }
      }
    }
  }

  private fun isDiscarded(request: LiveRequest): Boolean {
    val parts = (request.input as? ContentInput)?.content?.parts ?: return false
    val ids = discards.load().flatMapTo(mutableSetOf()) { it.callIds }
    return request.stateDelta.isEmpty() && parts.all { it.functionResponse?.id in ids }
  }

  /**
   * Skips the already queued requests made only of answers to [callIds], so a stopped stream
   * reaches no connection. Answers sent afterwards are kept, even under a reused call id.
   */
  internal fun discardAnswersTo(callIds: Collection<String>) {
    if (callIds.isEmpty()) return
    val discard = Discard(callIds.toSet())
    discards.mutate { it + discard }
    // Fails only once closed, when nothing new is queued, so the discard can stay.
    val unused = channel.trySend(discard.mark)
  }

  /**
   * Sends [content], tool answers the agent made itself, marked so the live flow can tell them from
   * the caller's: only a caller's answer stops a workflow node waiting on a long-running call.
   */
  internal fun sendOwnAnswer(content: Content) {
    val request = LiveRequest(ContentInput(content, partial = false))
    ownAnswers.mutate { it + request }
    // trySend only fails after close, where dropping is the documented behaviour.
    val unused = channel.trySend(request)
  }

  /** Whether [request] came from [sendOwnAnswer]; true at most once per request. */
  internal fun takeOwnAnswer(request: LiveRequest): Boolean {
    var taken = false
    ownAnswers.mutate { own ->
      val kept = own.filterNot { it === request }
      taken = kept.size < own.size
      kept
    }
    return taken
  }

  /**
   * Sends [request]; a [LiveRequest.close] request delivers its [LiveRequest.stateDelta], then
   * closes the queue.
   *
   * Copies [LiveRequest.stateDelta], so later changes to the caller's map don't reach the queued
   * request.
   *
   * Use this one to attach a [LiveRequest.stateDelta]; the other senders send input without one.
   */
  @ExperimentalLiveApi
  fun send(request: LiveRequest) {
    val queued = request.copy(stateDelta = request.stateDelta.toMap())
    // Forward the state change before closing so it is delivered like any other request.
    if (queued.close) {
      if (queued.stateDelta.isNotEmpty()) {
        val unused = channel.trySend(queued.copy(close = false))
      }
      close()
      return
    }
    // trySend only fails after close, where dropping is the documented behaviour.
    val unused = channel.trySend(queued)
  }

  /**
   * Sends content in turn-by-turn mode.
   *
   * Throws [IllegalArgumentException] if [content] breaks a [ContentInput] rule. A complete turn is
   * recorded in the session by the `LlmAgent` live flow; a partial one or a function response is
   * not.
   *
   * @param partial Whether this update leaves the model's current turn open.
   */
  @JvmOverloads
  @ExperimentalLiveApi
  fun sendContent(content: Content, partial: Boolean = false) {
    send(LiveRequest(ContentInput(content, partial)))
  }

  /**
   * Sends a [RealtimeInput.Audio] chunk, a [RealtimeInput.Video] frame, or an activity signal in
   * realtime mode.
   */
  @ExperimentalLiveApi
  fun sendRealtime(input: RealtimeInput) {
    send(LiveRequest(input))
  }

  /** Marks the start of user activity, when automatic activity detection is disabled. */
  @ExperimentalLiveApi
  fun sendActivityStart() {
    sendRealtime(RealtimeInput.ActivityStart)
  }

  /** Marks the end of user activity, when automatic activity detection is disabled. */
  @ExperimentalLiveApi
  fun sendActivityEnd() {
    sendRealtime(RealtimeInput.ActivityEnd)
  }

  /** Marks the end of the audio stream, forcing the model to flush what it has. */
  @ExperimentalLiveApi
  fun sendAudioStreamEnd() {
    sendRealtime(RealtimeInput.AudioStreamEnd)
  }

  /**
   * Closes the queue; calling it again is harmless.
   *
   * Close is ordered: requests sent before it are still delivered, and anything sent after it is
   * silently dropped. The queue cannot be reopened.
   */
  override fun close() {
    // Returns false on a second close.
    val unused = channel.close()
    // Idempotent: a second close completes nothing new.
    _closed.complete(Unit)
  }
}
