/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.adk.kt.webserver.routes

import com.google.adk.kt.sessions.GetSessionConfig
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.sessions.SessionService
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException

/**
 * A session read that failed in the store. It has no message of its own, since its [cause]'s
 * message can carry store details that must not reach a client or a log.
 */
internal class SessionReadFailure(override val cause: Exception) : Exception(null, cause)

/**
 * Delegates to [delegate] but throws a failure of the first read, the runner's own session lookup,
 * as [SessionReadFailure], so a route can tell a session that could not be loaded from a run that
 * failed. Later reads come from agents, tools and plugins during the run, so their failures pass
 * through unchanged. Wrap the service anew for each run.
 */
internal class ReadFailureWrappingSessionService(private val delegate: SessionService) :
  SessionService by delegate {
  private val firstRead = AtomicBoolean(true)

  override suspend fun getSession(key: SessionKey, config: GetSessionConfig?): Session? {
    if (!firstRead.getAndSet(false)) return delegate.getSession(key, config)
    return try {
      delegate.getSession(key, config)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      throw SessionReadFailure(e)
    }
  }
}
