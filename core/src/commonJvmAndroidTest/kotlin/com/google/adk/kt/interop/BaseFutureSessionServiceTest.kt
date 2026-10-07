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
import com.google.adk.kt.sessions.GetSessionConfig
import com.google.adk.kt.sessions.ListEventsResponse
import com.google.adk.kt.sessions.ListSessionsResponse
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.sessions.SessionException
import com.google.adk.kt.sessions.SessionKey
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CompletableFuture
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@OptIn(AdkJavaInteropApi::class)
@RunWith(JUnit4::class)
class BaseFutureSessionServiceTest {

  private val key = SessionKey("app", "user", "session")

  @Test
  fun flush_defaultFlushAsync_completes() = runBlocking {
    val service = TestService()

    service.flush(key)
    service.flush()
  }

  @Test
  fun flush_overriddenFlushAsync_waitsForTheMatchingFuture() = runBlocking {
    val pendingOne = CompletableFuture<Void?>()
    val pendingAll = CompletableFuture<Void?>()
    val flushedKeys = mutableListOf<SessionKey?>()
    val service =
      object : TestService() {
        override fun flushAsync(key: SessionKey?): CompletableFuture<Void?> {
          flushedKeys.add(key)
          return if (key == null) pendingAll else pendingOne
        }
      }

    val flushOne = async(start = CoroutineStart.UNDISPATCHED) { service.flush(key) }
    val flushAll = async(start = CoroutineStart.UNDISPATCHED) { service.flush() }

    assertThat(flushedKeys).containsExactly(key, null).inOrder()
    assertThat(flushOne.isCompleted).isFalse()
    pendingOne.complete(null)
    flushOne.await()
    assertThat(flushAll.isCompleted).isFalse()
    pendingAll.complete(null)
    flushAll.await()
  }

  @Test
  fun flush_callerCanceled_leavesTheFutureRunning() = runBlocking {
    val pending = CompletableFuture<Void?>()
    val service =
      object : TestService() {
        override fun flushAsync(key: SessionKey?): CompletableFuture<Void?> = pending
      }

    async(start = CoroutineStart.UNDISPATCHED) { service.flush(key) }.cancelAndJoin()
    async(start = CoroutineStart.UNDISPATCHED) { service.flush() }.cancelAndJoin()

    assertThat(pending.isCancelled).isFalse()
    pending.complete(null)
    service.flush(key)
    service.flush()
  }

  @Test
  fun flush_failedFlushAsync_throwsTheFailure(): Unit = runBlocking {
    val service =
      object : TestService() {
        override fun flushAsync(key: SessionKey?): CompletableFuture<Void?> =
          failedFuture("flush failed")
      }

    val thrown = assertFailsWith<SessionException> { service.flush(key) }

    assertThat(thrown).hasMessageThat().isEqualTo("flush failed")
  }

  private fun failedFuture(message: String): CompletableFuture<Void?> =
    CompletableFuture<Void?>().apply { completeExceptionally(SessionException(message)) }

  /** A [BaseFutureSessionService] whose other hooks are never called by these tests. */
  private open class TestService : BaseFutureSessionService() {
    override fun createSessionAsync(
      key: SessionKey,
      state: Map<String, Any>?,
    ): CompletableFuture<Session> = notCalled()

    override fun getSessionAsync(
      key: SessionKey,
      config: GetSessionConfig?,
    ): CompletableFuture<Session?> = notCalled()

    override fun listSessionsAsync(
      appName: String,
      userId: String,
    ): CompletableFuture<ListSessionsResponse> = notCalled()

    override fun deleteSessionAsync(key: SessionKey): CompletableFuture<Void?> = notCalled()

    override fun listEventsAsync(key: SessionKey): CompletableFuture<ListEventsResponse> =
      notCalled()

    private fun <T> notCalled(): CompletableFuture<T> = error("Not called by these tests")
  }
}
