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

package com.google.adk.kt.agents

import com.google.adk.kt.events.Event
import com.google.adk.kt.logging.LoggerFactory
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.sessions.SessionService
import kotlin.jvm.JvmSynthetic
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Appends [event] to the session under the invocation's append lock, shared by the live turn and
 * the runner; nothing is emitted while the lock is held. Both the lock wait and the append are
 * bounded by [LIVE_SESSION_TIMEOUT], so a hung session service fails the run instead of stalling
 * it.
 */
@JvmSynthetic
internal suspend fun InvocationContext.appendLiveEvent(event: Event) {
  val sessionService = this.sessionService
  if (sessionService == null) {
    logger.warn { "A live session event was dropped: the context has no session service." }
    return
  }
  withLiveSessionLock("append") {
    // Non-cancellable so a committed append is not repeated; a timeout still fails the run.
    withContext(NonCancellable) {
      withTimeoutOrNull(LIVE_SESSION_TIMEOUT) {
        val unused = sessionService.appendEvent(session, event)
      } ?: error("A live session append did not finish in $LIVE_SESSION_TIMEOUT.")
    }
  }
}

/**
 * Flushes the session under the invocation's append lock, acquired separately from
 * [appendLiveEvent] so a live turn's own append can interleave between the runner's append and
 * flush. Both the lock wait and the flush are bounded by [LIVE_SESSION_TIMEOUT].
 */
@JvmSynthetic
internal suspend fun InvocationContext.flushLiveSession() {
  // Nothing to flush: appendLiveEvent already warned that this context drops live events.
  val sessionService = this.sessionService ?: return
  withLiveSessionLock("flush") {
    // Unlike the append, the flush stays cancellable: re-running a flush is safe.
    sessionService.boundedFlush(session.key)
  }
}

/**
 * Runs [block] under the invocation's append lock, failing the run if the lock wait exceeds
 * [LIVE_SESSION_TIMEOUT].
 */
private suspend inline fun InvocationContext.withLiveSessionLock(
  action: String,
  block: () -> Unit,
) {
  // Checked in the finally: lock() can return and the wait still time out or be cancelled.
  var locked = false
  try {
    // Bound the wait for the lock, so a hung holder fails the run instead of hanging it.
    withTimeoutOrNull(LIVE_SESSION_TIMEOUT) {
      sessionAppendLock.lock()
      locked = true
    }
    check(locked) { "A live session $action waited over $LIVE_SESSION_TIMEOUT for the session." }
    block()
  } finally {
    if (locked) sessionAppendLock.unlock()
  }
}

/** Flushes [key], failing the run if the flush takes longer than [LIVE_SESSION_TIMEOUT]. */
@JvmSynthetic
internal suspend fun SessionService.boundedFlush(key: SessionKey) {
  withTimeoutOrNull(LIVE_SESSION_TIMEOUT) { flush(key) }
    ?: error("A live session flush did not finish in $LIVE_SESSION_TIMEOUT.")
}

/** How long a live session's lock wait, append, or flush may take before failing the run. */
private val LIVE_SESSION_TIMEOUT = 10.seconds

private val logger = LoggerFactory.getLogger(LlmAgentTurn::class)
