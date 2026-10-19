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

package com.google.adk.kt.testing

import com.google.adk.kt.annotations.ExperimentalLiveApi
import com.google.adk.kt.models.ContentInput
import com.google.adk.kt.models.LiveConnection
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.Model
import com.google.adk.kt.models.RealtimeInput
import com.google.adk.kt.types.Content
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

/** One scripted step that a [DummyLiveConnection] runs when `receive()` is collected. */
@ExperimentalLiveApi
sealed interface DummyLiveStep {
  /**
   * Emits [response] from `receive()`. If `response.turnComplete` is true, `receive()` finishes so
   * the next `receive()` starts at the following step.
   */
  data class Respond(val response: LlmResponse) : DummyLiveStep

  /**
   * Pauses `receive()` until the code under test sends a message that [matches] accepts, such as a
   * tool response. Each sent message is checked once, in send order, so a message one wait skips
   * cannot satisfy a later one. Fails the test if nothing matches within 5 seconds (test time under
   * `runTest`); closing the connection ends the wait without failing.
   */
  class AwaitSent(val description: String, val matches: (Any) -> Boolean) : DummyLiveStep

  /** Makes this and every later `receive()` throw [cause] to test connection failures. */
  data class Fail(val cause: Throwable) : DummyLiveStep

  /** Ends the stream so this and every later `receive()` completes without emitting more items. */
  data object EndStream : DummyLiveStep
}

/**
 * A fake live [Model] for testing live agents and runners without a network connection.
 *
 * Each call to [connect] records the request and returns a new [DummyLiveConnection] that plays
 * [steps].
 *
 * Use [perConnection] to give each connection its own steps, for example to script a reconnect.
 */
@ExperimentalLiveApi
class DummyLiveModel
@JvmOverloads
constructor(
  private val steps: List<DummyLiveStep>,
  override val name: String = "dummy-live-model",
) : Model {

  // One StateFlow keeps requests and connections aligned under concurrent connect() calls.
  private val opened = MutableStateFlow<List<Pair<LlmRequest, DummyLiveConnection>>>(emptyList())

  // Set only by [perConnection]; empty scripts keep every connection on [steps].
  private var scripts: List<List<DummyLiveStep>> = emptyList()
  private var afterScripts: List<DummyLiveStep> = emptyList()
  private var onConnect: (Int) -> Unit = {}
  private var onSend: suspend (Int, Any) -> Unit = { _, _ -> }

  /** Every connection opened so far, oldest first. */
  val connections: List<DummyLiveConnection>
    get() = opened.value.map { it.second }

  /** The requests passed to [connect], oldest first. */
  val connectRequests: List<LlmRequest>
    get() = opened.value.map { it.first }

  private constructor(
    scripts: List<List<DummyLiveStep>>,
    name: String,
    afterScripts: List<DummyLiveStep>,
    onConnect: (Int) -> Unit,
    onSend: suspend (Int, Any) -> Unit,
  ) : this(emptyList(), name) {
    this.scripts = scripts
    this.afterScripts = afterScripts
    this.onConnect = onConnect
    this.onSend = onSend
  }

  override suspend fun connect(request: LlmRequest): LiveConnection {
    if (scripts.isNotEmpty()) return connectPerConnection(request)
    val connection = DummyLiveConnection(steps)
    opened.update { it + (request to connection) }
    return connection
  }

  /** Opens the next connection of a [perConnection] model. */
  private suspend fun connectPerConnection(request: LlmRequest): LiveConnection {
    val all = opened.updateAndGet { current ->
      val index = current.size
      val script =
        scripts.getOrNull(index)
          ?: afterScripts.ifEmpty { null }
          ?: throw AssertionError(
            "DummyLiveModel.perConnection has ${scripts.size} script(s), but connection " +
              "${index + 1} was opened."
          )
      val connection = DummyLiveConnection(script) { message -> onSend(index, message) }
      current + (request to connection)
    }
    onConnect(all.lastIndex)
    return all.last().second
  }

  /** Returns a flow that throws [AssertionError] when collected; only [connect] is supported. */
  override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> = flow {
    throw AssertionError("DummyLiveModel serves only connect()")
  }

  companion object {
    /**
     * Creates a model whose connection `i` plays `scripts[i]`, and a connection past the last plays
     * [afterScripts], or fails the test when that is empty. [onConnect] gets each connection's
     * index once it is recorded. [onSend] runs after each send is recorded and may suspend or throw
     * to fake a slow or failed write.
     */
    @JvmStatic
    @JvmOverloads
    fun perConnection(
      scripts: List<List<DummyLiveStep>>,
      name: String = "dummy-live-model",
      afterScripts: List<DummyLiveStep> = emptyList(),
      onConnect: (Int) -> Unit = {},
      onSend: suspend (connection: Int, message: Any) -> Unit = { _, _ -> },
    ): DummyLiveModel {
      require(scripts.isNotEmpty()) { "DummyLiveModel.perConnection needs at least one script." }
      return DummyLiveModel(scripts, name, afterScripts, onConnect, onSend)
    }
  }
}

/**
 * A fake [LiveConnection] that runs [steps] in order and records every message sent to it.
 *
 * Only one `receive()` may run at a time, a turn-complete response ends the current `receive()`,
 * and the next `receive()` resumes at the following step. If the steps run out without
 * [DummyLiveStep.EndStream], `receive()` waits up to 5 seconds for [closeSession] and then fails
 * the test.
 */
@ExperimentalLiveApi
class DummyLiveConnection
internal constructor(private val steps: List<DummyLiveStep>, private val timeout: Duration) :
  LiveConnection {

  /**
   * Creates a connection that waits up to 5 seconds for each [DummyLiveStep.AwaitSent] and for
   * [closeSession] once the steps run out.
   */
  constructor(steps: List<DummyLiveStep>) : this(steps, 5.seconds)

  /** Creates a connection that runs [onSend] after it records each message sent to it. */
  internal constructor(steps: List<DummyLiveStep>, onSend: suspend (Any) -> Unit) : this(steps) {
    this.onSend = onSend
  }

  private val sentState = MutableStateFlow<List<Any>>(emptyList())
  private val closedState = MutableStateFlow(false)
  private val collecting = Mutex()
  private var next = 0
  private var matched = 0
  private var failure: Throwable? = null
  private var onSend: suspend (Any) -> Unit = {}

  /**
   * All messages sent to this connection in order: a `List<Content>` from [sendHistory], a
   * [ContentInput] from [sendContent], or a [RealtimeInput] from [sendRealtime]. Sends made after
   * [closeSession] are still recorded.
   */
  val sent: List<Any>
    get() = sentState.value

  /** Whether [closeSession] has been called. */
  val isClosed: Boolean
    get() = closedState.value

  /**
   * Throws [AssertionError] naming the first [DummyLiveStep.AwaitSent] that `receive()` never got
   * past, even if a matching message was sent later. Call it at the end of a test: closing the
   * connection ends a waiting `receive()` without failing, so an early close would otherwise pass.
   */
  fun assertAllWaitsMet() {
    val unmet = steps.drop(next).filterIsInstance<DummyLiveStep.AwaitSent>().firstOrNull() ?: return
    throw AssertionError(
      "DummyLiveConnection did not reach the wait for: ${unmet.description}. Sent: " +
        sent.joinToString { kindOf(it) }
    )
  }

  override fun receive(): Flow<LlmResponse> = flow {
    check(collecting.tryLock()) { "DummyLiveConnection allows one receive() at a time." }
    try {
      failure?.let { throw it }
      while (next < steps.size && !closedState.value) {
        when (val step = steps[next]) {
          is DummyLiveStep.Respond -> {
            next++
            emit(step.response)
            if (step.response.turnComplete == true) return@flow
          }
          is DummyLiveStep.AwaitSent -> {
            // Advance `next` only after a match so a canceled `receive()` retries this wait.
            val index = awaitSent(step) ?: return@flow
            matched = index + 1
            next++
          }
          is DummyLiveStep.Fail -> {
            failure = step.cause
            throw step.cause
          }
          DummyLiveStep.EndStream -> {
            return@flow
          }
        }
      }
      if (!closedState.value) {
        withTimeoutOrNull(timeout) { closedState.first { it } }
          ?: throw AssertionError(
            "DummyLiveConnection ran out of steps and was not closed within $timeout. End the " +
              "stream with DummyLiveStep.EndStream, or close the connection."
          )
      }
    } finally {
      collecting.unlock()
    }
  }

  override suspend fun sendHistory(history: List<Content>) {
    // LiveConnection allows history once, right after connecting and before any other send.
    check(sentState.value.isEmpty()) {
      "DummyLiveConnection allows one sendHistory(), before any other send."
    }
    record(history)
  }

  override suspend fun sendContent(content: Content, partial: Boolean) {
    // The genai Kotlin SDK rejects a function response without an id, on every backend.
    val input = ContentInput(content, partial)
    require(input.content.parts.mapNotNull { it.functionResponse }.all { it.id != null }) {
      "A function response needs the id of the call it answers; the genai Kotlin SDK rejects one " +
        "without it."
    }
    record(input)
  }

  override suspend fun sendRealtime(input: RealtimeInput) = record(input)

  override suspend fun closeSession() {
    closedState.value = true
  }

  private fun record(message: Any) {
    sentState.update { it + message }
  }

  // sendHistory, sendContent and sendRealtime resolve to these more specific overloads, so [onSend]
  // runs after each record. Each passes an Any-typed value so it reaches record(Any), not itself.
  private suspend fun record(history: List<Content>) {
    val message: Any = history
    record(message)
    onSend(history)
  }

  private suspend fun record(input: ContentInput) {
    val message: Any = input
    record(message)
    onSend(input)
  }

  private suspend fun record(input: RealtimeInput) {
    val message: Any = input
    record(message)
    onSend(input)
  }

  /** Returns the index of the matching message, or null when the connection closed first. */
  private suspend fun awaitSent(step: DummyLiveStep.AwaitSent): Int? {
    val found =
      withTimeoutOrNull(timeout) { scanForMatch(step) }
        ?: throw AssertionError(
          "DummyLiveConnection waited $timeout for: ${step.description}. Sent so far: " +
            sent.joinToString { kindOf(it) }
        )
    return found.takeIf { it >= 0 }
  }

  /** Returns the index of the first unchecked message matching [step], or -1 if closed first. */
  private suspend fun scanForMatch(step: DummyLiveStep.AwaitSent): Int {
    var index = matched
    while (true) {
      val snapshot = sentState.value
      while (index < snapshot.size) {
        if (step.matches(snapshot[index])) return index
        index++
      }
      if (closedState.value) return -1
      val seen = index
      // Suspend until a new message is sent or the connection closes.
      combine(sentState, closedState) { sent, closed -> sent.size > seen || closed }.first { it }
    }
  }

  private fun kindOf(message: Any): String =
    when (message) {
      is List<*> -> "history"
      is ContentInput -> "content"
      is RealtimeInput -> "realtime input"
      else -> message::class.simpleName ?: "?"
    }
}
