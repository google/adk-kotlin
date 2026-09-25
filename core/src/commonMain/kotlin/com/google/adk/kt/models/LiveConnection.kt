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

import com.google.adk.kt.singleCollectorFlow
import com.google.adk.kt.types.Content
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex

/**
 * An open bidirectional (live) connection to a model, obtained from [Model.connect].
 *
 * A connection outlives a single turn: opened once, carrying many turns, closed when the
 * conversation ends.
 *
 * Send methods are called by one writer at a time, but [closeSession] must be safe to call while a
 * send is in flight.
 */
interface LiveConnection : AutoCloseable {
  /**
   * Sends conversation history immediately after opening the connection; the model responds only if
   * the last content is from the user, and otherwise waits for new input.
   *
   * Implementations must drop audio parts: they have already been transcribed, and replaying them
   * corrupts the session rather than failing outright.
   */
  suspend fun sendHistory(history: List<Content>)

  /**
   * Sends user content, which completes the turn and prompts a response unless [partial] is true.
   *
   * Implementations reject [content] that breaks [ContentInput]'s rules with
   * [IllegalArgumentException].
   */
  suspend fun sendContent(content: Content, partial: Boolean = false)

  /**
   * Sends realtime input: a chunk of media, or a signal marking the edge of user activity.
   *
   * The model need not respond to each one; with automatic activity detection enabled it decides
   * for itself when a turn has ended.
   */
  suspend fun sendRealtime(input: RealtimeInput)

  /**
   * The stream of responses from the model, completing at the end of each model turn.
   *
   * Collect it again for the next turn. Only one collector is allowed at a time, because the
   * transport is a single channel of frames, so implementations reject a concurrent one with
   * [IllegalStateException].
   */
  fun receive(): Flow<LlmResponse>

  /**
   * Closes the connection; collectors of [receive] complete normally.
   *
   * Calling it again is safe and does nothing.
   */
  suspend fun closeSession()

  /**
   * Blocking [AutoCloseable] bridge to [closeSession], for callers outside a coroutine.
   *
   * Coroutine code must call [closeSession] instead, typically in a `finally`, and nothing should
   * call this from a main thread, because it blocks until the graceful close finishes. Unlike the
   * GenAI SDK's `LiveSession.close()`, which cancels the socket abruptly, this closes gracefully.
   */
  override fun close() = runBlocking { closeSession() }
}

private const val CONCURRENT_COLLECTION_MESSAGE =
  "LiveConnection.receive() allows one collector at a time, because the underlying transport is " +
    "a single channel of frames: a second concurrent collector would take an arbitrary " +
    "subset of them from the first."

/**
 * Base for [LiveConnection] implementations that enforces the single-collector contract of
 * [LiveConnection.receive].
 *
 * Holds the guard per connection instance because a [Flow] operator inside `receive()` would
 * allocate a fresh guard per call.
 */
internal abstract class SingleCollectorLiveConnection : LiveConnection {
  private val collecting = Mutex()

  /** The responses to expose, collected at most once at a time by [receive]. */
  protected abstract fun responses(): Flow<LlmResponse>

  final override fun receive(): Flow<LlmResponse> =
    singleCollectorFlow(collecting, CONCURRENT_COLLECTION_MESSAGE) { responses() }
}
