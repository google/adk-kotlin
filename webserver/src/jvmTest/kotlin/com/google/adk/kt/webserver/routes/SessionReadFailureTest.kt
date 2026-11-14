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
import com.google.adk.kt.sessions.InMemorySessionService
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.sessions.SessionService
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

private val KEY = SessionKey("app", "user", "session")

private const val STORE_DETAILS = "jdbc:secret://db.internal/password=hunter2"

/** A [SessionService] whose reads run [read], so a test can make the store answer or fail. */
private fun sessionServiceReading(read: () -> Session?): SessionService =
  object : SessionService by InMemorySessionService() {
    override suspend fun getSession(key: SessionKey, config: GetSessionConfig?): Session? = read()
  }

@RunWith(JUnit4::class)
class SessionReadFailureTest {
  @Test
  fun getSession_storeThrows_rethrowsItAsSessionReadFailureWithTheOriginalAsCause() {
    val failure = IllegalStateException(STORE_DETAILS)
    val sessions = sessionServiceReading { throw failure }.taggingReadFailures()

    val thrown =
      assertThrows(SessionReadFailure::class.java) {
        runBlocking { sessions.getSession(KEY, null) }
      }

    assertThat(thrown.cause).isSameInstanceAs(failure)
    assertThat(thrown.message).isNull()
  }

  @Test
  fun getSession_storeIsCancelled_rethrowsTheCancellationUnchanged() {
    val cancellation = CancellationException("cancelled")
    val sessions = sessionServiceReading { throw cancellation }.taggingReadFailures()

    val thrown =
      assertThrows(CancellationException::class.java) {
        runBlocking { sessions.getSession(KEY, null) }
      }

    assertThat(thrown).isSameInstanceAs(cancellation)
  }

  @Test
  fun getSession_storeReturnsASession_returnsIt() {
    val session = Session(KEY)
    val sessions = sessionServiceReading { session }.taggingReadFailures()

    assertThat(runBlocking { sessions.getSession(KEY, null) }).isSameInstanceAs(session)
  }

  @Test
  fun getSession_storeReturnsNull_returnsNull() {
    val sessions = sessionServiceReading { null }.taggingReadFailures()

    assertThat(runBlocking { sessions.getSession(KEY, null) }).isNull()
  }
}
