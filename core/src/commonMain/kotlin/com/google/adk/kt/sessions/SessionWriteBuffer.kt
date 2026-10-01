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
import com.google.adk.kt.logging.LoggerFactory
import com.google.adk.kt.sessions.OrderedEventWriter.WriteResult
import com.google.adk.kt.sessions.SessionException.Companion.SESSION_PERSISTENCE_FAILED
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Buffers the writes of many sessions until they are persisted, keeping an [OrderedEventWriter] per
 * session with writes in flight, and one lock and one coroutine scope in [context]. It orders
 * writes to shared scopes across sessions, waits for the writes a read can see, and reports failed
 * writes to flushes and to the session's next append. Given a [cache], it serves reads from copies
 * it keeps in step with the writes under the same lock.
 */
internal class SessionWriteBuffer(
  context: CoroutineContext,
  private val persist: suspend (OrderedEventWriter.Write) -> Unit,
  private val maxUnreportedFailures: Int = MAX_UNREPORTED_FAILURES,
  private val cache: SessionCache? = null,
) {
  // A plain Job, so a failure a write rethrows, such as an Error, stops every write.
  private val coroutineScope = CoroutineScope(context + Job(context[Job]))

  // Guards the cache, each writer and everything below; never held while waiting for a write.
  private val lock = Mutex()
  private var isClosed = false
  private val writerBySession = mutableMapOf<SessionKey, OrderedEventWriter>()
  // The next write to a shared scope waits for the last one.
  private val lastWriteByScope = mutableMapOf<SharedStateScope, OrderedEventWriter.Write>()
  // Each session's first unreported failure, oldest first, recorded when any of its writes fails.
  private val failureBySession = LinkedHashMap<SessionKey, Throwable>()

  /**
   * Updates the live [session] with [update] and writes the event it returns, in one step, so each
   * session's appends, and the changes to each shared scope, reach the store in the order they were
   * made. Throws a [SessionException] instead, before [update] runs, if the session has a failure
   * to report. [update] runs under the buffer's lock, so it must not call the buffer or wait for a
   * write.
   */
  suspend fun append(session: Session, update: suspend () -> Event): Event = lock.withLock {
    check(!isClosed) { "BufferedSessionService is closed." }
    check(coroutineScope.isActive) { "BufferedSessionService has stopped." }
    reportFailure(session.key)
    // The store checks a write against the session version it replaces, so read it first.
    val expectedUpdateTime = session.lastUpdateTime
    val appended = update()
    // Its own copy of the actions, so a later change to them cannot reach the store.
    val snapshot = appended.copy(actions = appended.actions.snapshot())
    val changedScopes = SharedStateScope.changedBy(session.key, snapshot.actions.stateDelta.keys)
    // Only now, so an update that throws leaves no writer behind.
    val writer =
      writerBySession.getOrPut(session.key) {
        OrderedEventWriter(session.key, coroutineScope, persist, ::beforeComplete)
      }
    val previousSharedStateWrites = changedScopes.mapNotNull { lastWriteByScope[it] }
    val write = writer.write(expectedUpdateTime, snapshot, after = previousSharedStateWrites)
    for (scope in changedScopes) lastWriteByScope[scope] = write
    cache?.append(session.key, appended, changedScopes)
    appended
  }

  /** Waits for the session [key]'s writes. */
  suspend fun awaitWritesOf(key: SessionKey) {
    awaitWrites { listOfNotNull(writerBySession[key]?.lastWrite) }
  }

  /** Waits for the writes a read of the session [key] sees: its own, and its shared scopes'. */
  suspend fun awaitWritesSeenBy(key: SessionKey) {
    awaitWrites {
      listOfNotNull(writerBySession[key]?.lastWrite) + lastWritesSeenBy(key.appName, key.userId)
    }
  }

  /** Waits for the writes a listing of [userId]'s sessions in [appName] sees. */
  suspend fun awaitWritesListedFor(appName: String, userId: String) {
    awaitWrites {
      writerBySession.values
        .filter { it.key.appName == appName && it.key.userId == userId }
        .mapNotNull { it.lastWrite } + lastWritesSeenBy(appName, userId)
    }
  }

  /**
   * Returns a copy of the cached session [key], or else waits for the writes a read of it sees and
   * reads it with [read], caching the result unless the session changed meanwhile.
   */
  suspend fun getOrRead(key: SessionKey, read: suspend () -> Session?): Session? {
    if (cache == null) {
      awaitWritesSeenBy(key)
      return read()
    }
    val storeRead = lock.withLock {
      val hit = cache.get(key)
      if (hit != null) return hit
      cache.startRead(key)
    }
    awaitWritesSeenBy(key)
    val session = read()
    // A read that fails or is canceled leaves its start for a later read, or for eviction.
    lock.withLock { cache.finishRead(key, storeRead, session) }
    return session
  }

  /**
   * Drops the cached copies of the session [key] and of the sessions that see the [changed] scopes,
   * whose stored copies changed in a way the cache cannot follow.
   */
  suspend fun dropCached(key: SessionKey, changed: Set<SharedStateScope> = emptySet()) {
    if (cache == null) return
    lock.withLock { cache.drop(key, changed) }
  }

  /**
   * Waits for the session [key]'s writes and runs [beforeReport]. Then it throws a
   * [SessionException] if one of them failed, or the session has a failure to report.
   */
  suspend fun flush(key: SessionKey, beforeReport: suspend () -> Unit = {}) {
    val waited = lock.withLock { writerBySession[key]?.lastWrite }
    val waitedFailure = (waited?.await() as? WriteResult.Failed)?.cause
    beforeReport()
    lock.withLock { reportFailure(key, waitedFailure) }
  }

  /**
   * Waits for every write and runs [beforeReport]. Then it throws a [SessionException] for the
   * unreported failures, the oldest as its cause and the rest suppressed, leaving each for its
   * session to report.
   */
  suspend fun flushAll(beforeReport: suspend () -> Unit = {}) {
    awaitWrites { writerBySession.values.mapNotNull { it.lastWrite } }
    beforeReport()
    val causes = lock.withLock { failureBySession.values.toList() }
    if (causes.isEmpty()) return
    val failure = SessionException(SESSION_PERSISTENCE_FAILED, causes.first())
    causes.drop(1).forEach(failure::addSuppressed)
    throw failure
  }

  /** Rejects later appends, flushes like [flushAll], then stops; later calls do nothing. */
  suspend fun close(beforeReport: suspend () -> Unit = {}) {
    lock.withLock {
      if (isClosed) return
      isClosed = true
    }
    try {
      flushAll(beforeReport)
    } finally {
      withContext(NonCancellable) { coroutineScope.coroutineContext.job.cancelAndJoin() }
    }
  }

  private suspend fun awaitWrites(select: () -> List<OrderedEventWriter.Write>) {
    for (write in lock.withLock { select() }) write.join()
  }

  /** Needs [lock]. */
  private fun lastWritesSeenBy(appName: String, userId: String) =
    SharedStateScope.seenBy(appName, userId).mapNotNull { lastWriteByScope[it] }

  /**
   * Runs before [write]'s waiters see [result]: forgets the write, then drops [writer] once its
   * last write is persisted, or records its first failure so the next append reports it at once. It
   * gets the [writer], since a retired writer's writes still complete after the session has a new
   * one.
   */
  private suspend fun beforeComplete(
    writer: OrderedEventWriter,
    write: OrderedEventWriter.Write,
    result: WriteResult,
  ) {
    lock.withLock {
      for (scope in SharedStateScope.changedBy(write.key, write.event.actions.stateDelta.keys)) {
        if (lastWriteByScope[scope] === write) lastWriteByScope.remove(scope)
      }
      // A retired writer's failure was reported or forgotten, and its writes no longer count.
      if (writerBySession[writer.key] !== writer) return
      when (result) {
        WriteResult.Persisted -> if (writer.lastWrite === write) writerBySession.remove(writer.key)
        is WriteResult.Failed -> {
          // Before the waiters, so none reads an event the store may lack from a copy.
          cache?.drop(write.key)
          if (writer.key !in failureBySession) {
            failureBySession[writer.key] = result.cause
            // Once stopped, keeps every failure, so the one that stopped the buffer is reported.
            if (failureBySession.size > maxUnreportedFailures && coroutineScope.isActive) {
              forgetOldestFailure()
            }
          }
        }
      }
    }
  }

  /** Needs [lock]. */
  private fun forgetOldestFailure() {
    retire(failureBySession.keys.first())
    // No key: a session id can carry user data.
    logger.warn {
      "Forgot the oldest unreported buffered-write failure; its session will not report it."
    }
  }

  /**
   * Needs [lock]. Throws the session [key]'s unreported failure, retiring its writer, or else
   * [waitedFailure]; a later call reports that failure again only if it waited for it.
   */
  private fun reportFailure(key: SessionKey, waitedFailure: Throwable? = null) {
    val failure = failureBySession[key]
    if (failure != null) retire(key)
    val cause = failure ?: waitedFailure ?: return
    throw SessionException(SESSION_PERSISTENCE_FAILED, cause)
  }

  /** Needs [lock]. Drops the session [key]'s failed writer; its next append starts a new one. */
  private fun retire(key: SessionKey) {
    writerBySession.remove(key)
    failureBySession.remove(key)
  }

  private companion object {
    // Bounds the memory unreported failures take, since a session may never be touched again.
    const val MAX_UNREPORTED_FAILURES = 1024

    val logger = LoggerFactory.getLogger(SessionWriteBuffer::class)
  }
}
