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
import kotlinx.coroutines.CancellationException

/**
 * A session read that failed in the store. It has no message of its own, since its [cause]'s
 * message can carry store details that must not reach a client or a log.
 */
internal class SessionReadFailure(override val cause: Exception) : RuntimeException(null, cause)

/**
 * Returns a service that delegates to this one but throws a failed read as [SessionReadFailure], so
 * a route can tell a session that could not be loaded from a run that failed.
 */
internal fun SessionService.taggingReadFailures(): SessionService =
  object : SessionService by this {
    override suspend fun getSession(key: SessionKey, config: GetSessionConfig?): Session? =
      try {
        this@taggingReadFailures.getSession(key, config)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        throw SessionReadFailure(e)
      }
  }
