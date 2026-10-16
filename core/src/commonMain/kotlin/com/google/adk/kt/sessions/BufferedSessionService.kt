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

package com.google.adk.kt.sessions

import com.google.adk.kt.events.Event
import com.google.adk.kt.retry.retry
import kotlin.coroutines.CoroutineContext
import kotlin.jvm.JvmOverloads
import kotlin.time.TimeSource
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * A [SessionService] that persists writes in the background: [appendEvent] updates the live session
 * and returns, and a writer per session writes the events to [delegate] in order, and in order
 * across sessions for `app:` and `user:` state, retrying as
 * [BufferedSessionServiceConfig.retryPolicy] decides.
 *
 * Buffered writes stay in memory until they are persisted; reads that go to the store and [flush]
 * wait for the writes they depend on, and the runner flushes before each final response, which
 * bounds how many writes a session holds. When retries run out, the session's buffered writes are
 * dropped and a [SessionException] reports the failure to any flush or delete waiting on them, or
 * else to the session's next append, flush or delete unless failures in 1024 other sessions
 * displaced it; reload the session with [getSession] before appending again, since the live session
 * still holds the dropped events.
 *
 * @param delegate The store the writes are persisted to, which [close] leaves open; its
 *   [SessionService.appendEvent] receives a [Session] with only the key and the
 *   [Session.lastUpdateTime] the write replaces, so it must persist from the event alone. A retry
 *   after an attempt that failed but was persisted resends the event with that same
 *   [Session.lastUpdateTime], so the store should treat an event it already holds as persisted,
 *   rather than store it twice or, with a stale-write check, reject it and fail the session. A call
 *   that never returns holds back the session's later writes, the reads that wait for them, and
 *   [close].
 * @param context Where the writes run. They outlive the run that made them, so they take their own
 *   context rather than the caller's; a [kotlinx.coroutines.Job] in it becomes their parent and
 *   cannot complete until [close]. An `Error` in a write stops the service and fails like an
 *   uncaught child coroutine, canceling a parent job that is not a supervisor and reaching the
 *   context's exception handler or the platform default.
 * @param config How failed writes are retried, and whether a read cache serves [getSession].
 */
class BufferedSessionService
@JvmOverloads
constructor(
  private val delegate: SessionService,
  context: CoroutineContext,
  config: BufferedSessionServiceConfig = BufferedSessionServiceConfig(),
) : SessionService, AutoCloseable {
  // Not `by delegate`: a new SessionService member gets its default here until overridden.

  private val retryPolicy = config.retryPolicy
  private val buffer =
    SessionWriteBuffer(
      context,
      persist = ::persist,
      cache = config.cacheConfig?.let { SessionCache(it, TimeSource.Monotonic) },
    )

  override suspend fun createSession(key: SessionKey, state: Map<String, Any>?): Session {
    // The store may give a new session the app: and user: state that other sessions changed.
    buffer.awaitWritesSeenBy(key)
    try {
      return delegate.createSession(key, state)
    } finally {
      // A create that fails may still have stored the session and its app: and user: seeds.
      withContext(NonCancellable) {
        buffer.dropCached(key, SharedStateScope.changedBy(key, state.orEmpty().keys))
      }
    }
  }

  override suspend fun getSession(key: SessionKey, config: GetSessionConfig?): Session? {
    // A filtered read goes to the store, which filters events its own way.
    if (config != null || key.id == null) {
      buffer.awaitWritesSeenBy(key)
      return delegate.getSession(key, config)
    }
    return buffer.getOrRead(key) { delegate.getSession(key, config = null) }
  }

  override suspend fun listSessions(appName: String, userId: String): ListSessionsResponse {
    buffer.awaitWritesListedFor(appName, userId)
    return delegate.listSessions(appName, userId)
  }

  override suspend fun listEvents(key: SessionKey): ListEventsResponse {
    buffer.awaitWritesOf(key)
    return delegate.listEvents(key)
  }

  override suspend fun closeSession(session: Session) {
    buffer.awaitWritesOf(session.key)
    delegate.closeSession(session)
  }

  /**
   * Waits for the session's buffered writes, since its `app:` and `user:` changes outlive it, then
   * deletes it from [delegate]. Like [flush], it throws instead if one failed or a failure is
   * unreported, so the caller can retry. Stop appending first: an append made after the delete
   * starts is not waited for, and if it reaches the store after the delete the store may reject it,
   * leaving a failure that [flush] reports.
   */
  override suspend fun deleteSession(key: SessionKey) {
    buffer.flush(key)
    try {
      delegate.deleteSession(key)
    } finally {
      // A read that ran while the store deleted the session may have cached it.
      withContext(NonCancellable) { buffer.dropCached(key) }
    }
  }

  override suspend fun appendEvent(session: Session, event: Event): Event {
    if (event.partial) return event
    requireNotNull(session.key.id) { "BufferedSessionService requires a session id." }
    // The interface default updates the live session only; the store is not involved.
    return buffer.append(session) { super.appendEvent(session, event) }
  }

  /**
   * Waits for the writes buffered for [key], or for every session when [key] is `null`, and passes
   * the flush on to [delegate], then throws if a write of [key] it waited on failed or [key] has a
   * failure unreported. With a `null` [key], it throws every unreported failure, on each call until
   * the session whose write failed reports it on an append, flush or delete.
   */
  override suspend fun flush(key: SessionKey?) {
    if (key == null) return buffer.flushAll { delegate.flush() }
    requireNotNull(key.id) { "flush requires a session id." }
    buffer.flush(key) { delegate.flush(key) }
  }

  /**
   * Flushes every session and stops the writes, without closing [delegate]; like [flush] with a
   * `null` key, it throws a [SessionException] for failures no session has reported. It blocks the
   * calling thread, so do not call it on the Android main thread or on a thread the writes need.
   */
  override fun close() {
    runBlocking { buffer.close { delegate.flush() } }
  }

  /** The store gets only the key and the version the write replaces; delivery is at least once. */
  private suspend fun persist(write: OrderedEventWriter.Write) {
    val unused = retryPolicy.retry {
      delegate.appendEvent(
        Session(write.key, lastUpdateTime = write.expectedUpdateTime),
        write.event,
      )
    }
  }
}
