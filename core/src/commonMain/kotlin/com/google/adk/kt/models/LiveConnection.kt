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

import com.google.adk.kt.types.Content
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * An open bidirectional (live) connection to a model.
 *
 * A connection outlives a single turn: opened once, carrying many turns, closed when the
 * conversation ends. Obtain one from [LiveModel]. Implementations need not be safe for concurrent
 * senders; drive the send side from one coroutine.
 */
interface LiveConnection {
  /**
   * Sends conversation history, which primes the model without asking it to respond; call this
   * immediately after opening the connection.
   *
   * The model responds only if the last content is from the user; otherwise it waits for new input.
   *
   * Implementations must drop audio parts: they have already been transcribed, and replaying them
   * corrupts the session rather than failing outright.
   */
  suspend fun sendHistory(history: List<Content>)

  /**
   * Sends user content, which the model responds to immediately.
   *
   * When sending function responses, every part of [content] must be a function response.
   *
   * Set [partial] when more of the same turn follows: the turn is left open and the model waits
   * rather than answering what it has, so only a streaming caller sets it.
   */
  suspend fun sendContent(content: Content, partial: Boolean = false)

  /**
   * Sends realtime input: a chunk of media, or a signal marking the edge of user activity.
   *
   * The model need not respond to each one; with automatic activity detection enabled it decides
   * for itself when a turn has ended.
   *
   * Takes a [RealtimeInput] rather than a bare blob, because three of the things travelling this
   * path are not media at all.
   */
  suspend fun sendRealtime(input: RealtimeInput)

  /**
   * The stream of responses from the model.
   *
   * **One collector at a time.** The transport is a single channel of frames, not a broadcast, so
   * two concurrent collectors would each see an arbitrary half; an implementation must reject a
   * second *concurrent* collection, and one outside this module is responsible for its own. Use
   * `.shareIn(scope)` to fan out; collecting again after one finishes reads a turn at a time.
   */
  fun receive(): Flow<LlmResponse>

  /** Closes the connection. Collectors of [receive] complete normally. */
  suspend fun close()

  /**
   * Releases the transport without reporting a close.
   *
   * Every path out of a live run has to free the socket, including the ones the caller never
   * reached; routing those through [close] would make the runtime appear to end conversations it
   * had not. Tear down in [close]'s exact order; a connection owning no transport does nothing.
   */
  suspend fun release()
}

private const val CONCURRENT_COLLECTION_MESSAGE =
  "LiveConnection.receive() allows one collector at a time, because the underlying transport is " +
    "a single channel of frames: a second concurrent collector would take an arbitrary half " +
    "of them from the first. Use .shareIn(scope) to fan out to several consumers."

/**
 * Base for [LiveConnection] implementations that enforces the single-collector contract of
 * [LiveConnection.receive].
 *
 * The guard is held per connection instance rather than offered as a [Flow] operator, which applied
 * inside `receive()` would allocate a fresh guard per call and protect nothing. It reaches only
 * in-module subclasses; [LiveConnection] is public and this base is not.
 */
internal abstract class SingleCollectorLiveConnection : LiveConnection {
  private val collecting = atomic(false)

  /** The responses to expose, collected at most once at a time by [receive]. */
  protected abstract fun responses(): Flow<LlmResponse>

  final override fun receive(): Flow<LlmResponse> = flow {
    check(collecting.compareAndSet(expect = false, update = true)) { CONCURRENT_COLLECTION_MESSAGE }
    try {
      emitAll(responses())
    } finally {
      collecting.value = false
    }
  }
}
