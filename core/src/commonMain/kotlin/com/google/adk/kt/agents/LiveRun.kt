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
import com.google.adk.kt.events.Event
import com.google.adk.kt.logging.LoggerFactory
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.sessions.SessionService
import com.google.errorprone.annotations.CanIgnoreReturnValue
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.jvm.JvmSynthetic
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * State shared across every context derived from a live run's root: the caller's [requestQueue] and
 * the audio recorded since the last flush. The runner opens one per live run with [openLiveRun]; a
 * turn-based invocation has none.
 */
internal class LiveRun(
  val requestQueue: LiveRequestQueue,
  private val session: Session,
  private val sessionService: SessionService?,
) {
  /**
   * Live audio the caller has sent since the last flush, filled only through [AudioCacheManager]
   * when [LiveRunConfig.saveLiveBlob] is set. It lives on the run rather than in the manager, so a
   * turn restarted on a new connection keeps one recording.
   */
  val inputRealtimeCache = RealtimeCache()

  /** The audio the model has spoken since the last flush. See [inputRealtimeCache]. */
  val outputRealtimeCache = RealtimeCache()

  /** Whether a live session of this run has claimed the caller's; see [claimCallersSession]. */
  private val callersSessionClaimed = AtomicBoolean(false)

  /**
   * Claims the caller's session for the live session about to open: true for the run's first, which
   * may resume it with the caller's resumption handle, and false for any later one, which starts a
   * new session.
   */
  fun claimCallersSession(): Boolean = callersSessionClaimed.compareAndSet(false, true)

  /** The final answers of stopped streams, by the path of the live node that is to emit them. */
  private val deferredFinalAnswers = AtomicReference(emptyMap<String, List<Event>>())

  /**
   * Leaves [records], a live node turn's stopped streams' final answers, for the node at [nodePath]
   * to emit after its round's held events, so each is stored after its first answer.
   */
  fun deferFinalAnswers(nodePath: String, records: List<Event>) {
    deferredFinalAnswers.mutate { it + (nodePath to it[nodePath].orEmpty() + records) }
  }

  /** Takes the first final answer [deferFinalAnswers] left for the node at [nodePath], if any. */
  fun takeDeferredFinalAnswer(nodePath: String): Event? {
    while (true) {
      val deferred = deferredFinalAnswers.load()
      val records = deferred[nodePath] ?: return null
      val rest = records.drop(1)
      val next = if (rest.isEmpty()) deferred - nodePath else deferred + (nodePath to rest)
      if (deferredFinalAnswers.compareAndSet(deferred, next)) return records.first()
    }
  }

  /** Takes the final answers [deferFinalAnswers] left for the node at [nodePath]. */
  fun takeDeferredFinalAnswers(nodePath: String): List<Event> {
    while (true) {
      val deferred = deferredFinalAnswers.load()
      val taken = deferred[nodePath] ?: return emptyList()
      if (deferredFinalAnswers.compareAndSet(deferred, deferred - nodePath)) return taken
    }
  }

  /** Whether [append] stores events; a run without a session service drops them. */
  val storesEvents: Boolean
    get() = sessionService != null

  /** Returns the next request from [requestQueue] for [agent], one reader at a time. */
  suspend fun receiveRequest(agent: String): LiveRequest? = requestQueue.receiveRequest(agent)

  /** Fails, naming [agent], if another reader of [requestQueue] is already reading. */
  suspend fun checkNoOtherReader(agent: String) = requestQueue.checkNoOtherReader(agent)

  /**
   * Appends [event] to the session, bounded by [LIVE_SESSION_TIMEOUT] so a hung session service
   * fails the run instead of stalling it. This does not serialize appends: the runner and the live
   * turn append concurrently, so the session service must accept concurrent appends.
   */
  suspend fun append(event: Event) {
    if (sessionService == null) {
      logger.warn { "A live session event was dropped: the run has no session service." }
      return
    }
    // Non-cancellable so a committed append is not repeated; a timeout still fails the run.
    withContext(NonCancellable) {
      withTimeoutOrNull(LIVE_SESSION_TIMEOUT) {
        val unused = sessionService.appendEvent(session, event)
      } ?: error("A live session append did not finish in $LIVE_SESSION_TIMEOUT.")
    }
  }

  /** Flushes the session, bounded by [LIVE_SESSION_TIMEOUT]. */
  suspend fun flush() {
    // Nothing to flush: append already warned that this run drops its events.
    if (sessionService == null) return
    // Unlike the append, the flush stays cancellable: re-running a flush is safe.
    sessionService.boundedFlush(session.key)
  }
}

/**
 * Opens a live run for [requestQueue] on this context's session. Contexts copied from this one
 * share its [ContextFrameworkData], so they all see the same run. Opening a second one fails,
 * because it would replace the run those contexts share and mix two runs' queues and audio caches.
 */
@CanIgnoreReturnValue
@JvmSynthetic
internal fun InvocationContext.openLiveRun(requestQueue: LiveRequestQueue): LiveRun {
  check(frameworkData.liveRun == null) { "a live run is already open" }
  return LiveRun(requestQueue, session, sessionService).also { frameworkData.liveRun = it }
}

/** Flushes [key], failing the run if the flush takes longer than [LIVE_SESSION_TIMEOUT]. */
@JvmSynthetic
internal suspend fun SessionService.boundedFlush(key: SessionKey) {
  withTimeoutOrNull(LIVE_SESSION_TIMEOUT) { flush(key) }
    ?: error("A live session flush did not finish in $LIVE_SESSION_TIMEOUT.")
}

/** How long a live session's append or flush may take before failing the run. */
private val LIVE_SESSION_TIMEOUT = 10.seconds

private val logger = LoggerFactory.getLogger(LiveRun::class)
