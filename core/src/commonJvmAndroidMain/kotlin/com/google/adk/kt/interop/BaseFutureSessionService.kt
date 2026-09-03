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

package com.google.adk.kt.interop

import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.events.Event
import com.google.adk.kt.sessions.GetSessionConfig
import com.google.adk.kt.sessions.ListEventsResponse
import com.google.adk.kt.sessions.ListSessionsResponse
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.sessions.SessionService
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.future.await
import kotlinx.coroutines.future.future

/**
 * Java-friendly base for implementing a [SessionService]. Every method of the engine interface is
 * `suspend`; this base final-overrides each and asks the Java subclass for a [CompletableFuture]
 * instead. Return each future promptly and do any blocking work inside it, not before returning it.
 * The two methods with default bodies ([closeSession], [appendEvent]) keep those defaults
 * reachable: leave the hook unoverridden and the engine's own behaviour runs.
 */
@AdkJavaInteropApi
abstract class BaseFutureSessionService : SessionService {

  @Suppress("GlobalCoroutineDispatchers")
  private val defaultScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  final override suspend fun createSession(key: SessionKey, state: Map<String, Any>?): Session =
    createSessionAsync(key, state).await()

  final override suspend fun getSession(key: SessionKey, config: GetSessionConfig?): Session? =
    getSessionAsync(key, config).await()

  final override suspend fun listSessions(appName: String, userId: String): ListSessionsResponse =
    listSessionsAsync(appName, userId).await()

  final override suspend fun deleteSession(key: SessionKey) {
    deleteSessionAsync(key).await()
  }

  final override suspend fun listEvents(key: SessionKey): ListEventsResponse =
    listEventsAsync(key).await()

  final override suspend fun closeSession(session: Session) {
    closeSessionAsync(session).await()
  }

  final override suspend fun appendEvent(session: Session, event: Event): Event =
    appendEventAsync(session, event).await()

  protected abstract fun createSessionAsync(
    key: SessionKey,
    state: Map<String, @JvmSuppressWildcards Any>?,
  ): CompletableFuture<Session>

  protected abstract fun getSessionAsync(
    key: SessionKey,
    config: GetSessionConfig?,
  ): CompletableFuture<Session?>

  protected abstract fun listSessionsAsync(
    appName: String,
    userId: String,
  ): CompletableFuture<ListSessionsResponse>

  protected abstract fun deleteSessionAsync(key: SessionKey): CompletableFuture<Void?>

  protected abstract fun listEventsAsync(key: SessionKey): CompletableFuture<ListEventsResponse>

  /** Defaults to a no-op, mirroring the engine's default body. */
  protected open fun closeSessionAsync(session: Session): CompletableFuture<Void?> =
    CompletableFuture.completedFuture(null)

  /**
   * Appends an event. The default runs the engine's own `appendEvent` body via
   * [appendEventDefault]; override to add persistence, composing [appendEventDefault] into the
   * future you return (e.g. `appendEventDefault(session, event).thenCompose { persist(...) }`) so
   * the caller's in-memory [Session] stays in sync.
   */
  protected open fun appendEventAsync(session: Session, event: Event): CompletableFuture<Event> =
    appendEventDefault(session, event)

  /**
   * Runs the engine's own `appendEvent` body (which mutates [session]) on a background dispatcher.
   * Compose it into the future returned from [appendEventAsync] -- e.g. `.thenCompose { ... }` --
   * rather than firing it off alongside a separate future: the [Session] it mutates is not
   * thread-safe, so awaiting/chaining it is what orders that mutation before your own work.
   */
  protected fun appendEventDefault(session: Session, event: Event): CompletableFuture<Event> =
    defaultScope.future {
      superAppendEvent(session, event)
    }

  private suspend fun superAppendEvent(session: Session, event: Event): Event =
    super.appendEvent(session, event)
}
