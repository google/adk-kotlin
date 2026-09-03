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
import kotlinx.coroutines.future.await

/**
 * Java-friendly base for implementing a [MemoryService]. The engine's methods are `suspend`; a Java
 * subclass returns [CompletableFuture]s instead. [addEventsToMemoryAsync] and [addMemoryAsync] are
 * optional -- their default throws [UnsupportedOperationException] (as the engine does), so
 * override them only if the service supports those writes. Return each future promptly and do any
 * blocking work inside it, not before returning it.
 */
@AdkJavaInteropApi
abstract class BaseFutureMemoryService : MemoryService {

  final override suspend fun addSessionToMemory(session: Session) {
    addSessionToMemoryAsync(session).await()
  }

  final override suspend fun searchMemory(
    appName: String,
    userId: String,
    query: String,
  ): SearchMemoryResponse = searchMemoryAsync(appName, userId, query).await()

  final override suspend fun addEventsToMemory(
    appName: String,
    userId: String,
    events: List<Event>,
    sessionId: String?,
    customMetadata: Map<String, Any?>?,
  ) {
    addEventsToMemoryAsync(appName, userId, events, sessionId, customMetadata).await()
  }

  final override suspend fun addMemory(
    appName: String,
    userId: String,
    memories: List<MemoryEntry>,
    customMetadata: Map<String, Any?>?,
  ) {
    addMemoryAsync(appName, userId, memories, customMetadata).await()
  }

  protected abstract fun addSessionToMemoryAsync(session: Session): CompletableFuture<Void?>

  protected abstract fun searchMemoryAsync(
    appName: String,
    userId: String,
    query: String,
  ): CompletableFuture<SearchMemoryResponse>

  /**
   * Override to support event-delta ingestion; the default throws [UnsupportedOperationException].
   */
  protected open fun addEventsToMemoryAsync(
    appName: String,
    userId: String,
    events: List<Event>,
    sessionId: String?,
    customMetadata: Map<String, @JvmSuppressWildcards Any?>?,
  ): CompletableFuture<Void?> =
    throw UnsupportedOperationException(
      "This memory service does not support adding event deltas; call addSessionToMemory instead."
    )

  /**
   * Override to support direct memory writes; the default throws [UnsupportedOperationException].
   */
  protected open fun addMemoryAsync(
    appName: String,
    userId: String,
    memories: List<MemoryEntry>,
    customMetadata: Map<String, @JvmSuppressWildcards Any?>?,
  ): CompletableFuture<Void?> =
    throw UnsupportedOperationException(
      "This memory service does not support direct memory writes; call addSessionToMemory instead."
    )
}
