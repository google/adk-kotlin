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

import kotlinx.coroutines.runBlocking

/**
 * Java-friendly blocking wrapper around a [SessionService]. Each method blocks the calling thread
 * until the wrapped `suspend` call completes, so Java callers can use the service without dealing
 * with continuations. Call these from ordinary (non-coroutine) threads.
 */
class BlockingSessionService(private val delegate: SessionService) {

  /** @see SessionService.createSession */
  @JvmOverloads
  fun createSession(key: SessionKey, state: Map<String, Any>? = null): Session = runBlocking {
    delegate.createSession(key, state)
  }

  /** @see SessionService.getSession */
  @JvmOverloads
  fun getSession(key: SessionKey, config: GetSessionConfig? = null): Session? = runBlocking {
    delegate.getSession(key, config)
  }

  /** @see SessionService.listSessions */
  fun listSessions(appName: String, userId: String): ListSessionsResponse = runBlocking {
    delegate.listSessions(appName, userId)
  }

  /** @see SessionService.deleteSession */
  fun deleteSession(key: SessionKey): Unit = runBlocking { delegate.deleteSession(key) }

  /** @see SessionService.listEvents */
  fun listEvents(key: SessionKey): ListEventsResponse = runBlocking { delegate.listEvents(key) }
}
