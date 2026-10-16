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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.future.future

/**
 * Java-friendly wrapper around a [SessionService] that it owns. Each method calls the `suspend`
 * method of the same name and returns a [CompletableFuture] of its result, so Java callers never
 * pass a `Continuation`. Canceling a future cancels its call, and [close] cancels every call in
 * flight and closes the service.
 */
@AdkJavaInteropApi
class CompletableFutureSessionService private constructor(private val delegate: SessionService) :
  AutoCloseable {

  // Runs each call as a coroutine that completes its future; canceled by close().
  @Suppress("GlobalCoroutineDispatchers")
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  /** The wrapped session service, for code that has dropped into Kotlin. */
  fun asKotlinSessionService(): SessionService = delegate

  /** Calls [SessionService.createSession]. */
  @JvmOverloads
  fun createSession(key: SessionKey, state: Map<String, Any>? = null): CompletableFuture<Session> =
    scope.future {
      delegate.createSession(key, state)
    }

  /** Calls [SessionService.getSession]; the future completes with `null` if there is no session. */
  @JvmOverloads
  fun getSession(key: SessionKey, config: GetSessionConfig? = null): CompletableFuture<Session?> =
    scope.future {
      delegate.getSession(key, config)
    }

  /** Calls [SessionService.listSessions]. */
  fun listSessions(appName: String, userId: String): CompletableFuture<ListSessionsResponse> =
    scope.future {
      delegate.listSessions(appName, userId)
    }

  /** Calls [SessionService.deleteSession]. */
  fun deleteSession(key: SessionKey): CompletableFuture<Void?> = scope.future {
    delegate.deleteSession(key)
    null
  }

  /** Calls [SessionService.listEvents]. */
  fun listEvents(key: SessionKey): CompletableFuture<ListEventsResponse> = scope.future {
    delegate.listEvents(key)
  }

  /** Calls [SessionService.closeSession]. */
  fun closeSession(session: Session): CompletableFuture<Void?> = scope.future {
    delegate.closeSession(session)
    null
  }

  /**
   * Calls [SessionService.appendEvent], which updates [session] in place on another thread, so
   * leave [session] alone until the future completes.
   */
  fun appendEvent(session: Session, event: Event): CompletableFuture<Event> = scope.future {
    delegate.appendEvent(session, event)
  }

  /** Calls [SessionService.flush]. */
  @JvmOverloads
  fun flush(key: SessionKey? = null): CompletableFuture<Void?> = scope.future {
    delegate.flush(key)
    null
  }

  /**
   * Cancels calls in flight, then closes the service if it is [AutoCloseable]. Every later call
   * returns a canceled future.
   */
  override fun close() {
    scope.cancel()
    (delegate as? AutoCloseable)?.close()
  }

  companion object {
    /**
     * Wraps [sessionService] and takes ownership of it: [close] also closes it if it is
     * [AutoCloseable].
     */
    @JvmStatic
    fun of(sessionService: SessionService): CompletableFutureSessionService =
      CompletableFutureSessionService(sessionService)
  }
}
