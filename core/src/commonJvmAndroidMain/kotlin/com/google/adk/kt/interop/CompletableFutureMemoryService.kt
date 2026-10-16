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
import com.google.adk.kt.memory.MemoryEntry
import com.google.adk.kt.memory.MemoryService
import com.google.adk.kt.memory.SearchMemoryResponse
import com.google.adk.kt.sessions.Session
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.future.future

/**
 * Java-friendly wrapper around a [MemoryService] that it owns. Each method calls the `suspend`
 * method of the same name and returns a [CompletableFuture] of its result, so Java callers never
 * pass a `Continuation`. Canceling a future cancels its call, and [close] cancels every call in
 * flight and closes the service.
 */
@AdkJavaInteropApi
class CompletableFutureMemoryService private constructor(private val delegate: MemoryService) :
  AutoCloseable {

  // Runs each call as a coroutine that completes its future; canceled by close().
  @Suppress("GlobalCoroutineDispatchers")
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  /** The wrapped memory service, for code that has dropped into Kotlin. */
  fun asKotlinMemoryService(): MemoryService = delegate

  /** Calls [MemoryService.addSessionToMemory]. */
  fun addSessionToMemory(session: Session): CompletableFuture<Void?> = scope.future {
    delegate.addSessionToMemory(session)
    null
  }

  /**
   * Calls [MemoryService.addEventsToMemory]. The future fails with [UnsupportedOperationException]
   * if the service does not support event deltas.
   */
  @JvmOverloads
  fun addEventsToMemory(
    appName: String,
    userId: String,
    events: List<Event>,
    sessionId: String? = null,
    customMetadata: Map<String, Any?>? = null,
  ): CompletableFuture<Void?> = scope.future {
    delegate.addEventsToMemory(appName, userId, events, sessionId, customMetadata)
    null
  }

  /**
   * Calls [MemoryService.addMemory]. The future fails with [UnsupportedOperationException] if the
   * service does not support direct memory writes.
   */
  @JvmOverloads
  fun addMemory(
    appName: String,
    userId: String,
    memories: List<MemoryEntry>,
    customMetadata: Map<String, Any?>? = null,
  ): CompletableFuture<Void?> = scope.future {
    delegate.addMemory(appName, userId, memories, customMetadata)
    null
  }

  /** Calls [MemoryService.searchMemory]. */
  fun searchMemory(
    appName: String,
    userId: String,
    query: String,
  ): CompletableFuture<SearchMemoryResponse> = scope.future {
    delegate.searchMemory(appName, userId, query)
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
     * Wraps [memoryService] and takes ownership of it: [close] also closes it if it is
     * [AutoCloseable].
     */
    @JvmStatic
    fun of(memoryService: MemoryService): CompletableFutureMemoryService =
      CompletableFutureMemoryService(memoryService)
  }
}
