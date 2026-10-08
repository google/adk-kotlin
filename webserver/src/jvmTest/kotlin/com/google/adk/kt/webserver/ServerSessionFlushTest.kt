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

package com.google.adk.kt.webserver

import com.google.adk.kt.events.Event
import com.google.adk.kt.sessions.InMemorySessionService
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.sessions.SessionException
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.sessions.SessionService
import com.google.adk.kt.webserver.telemetry.ApiServerSpanExporter
import com.google.common.truth.Truth.assertThat
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.ResponseSent
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.Collections
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.awaitCancellation
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Covers when the server flushes its session service outside a run. */
@RunWith(JUnit4::class)
class ServerSessionFlushTest {

  @Test
  fun createSessionWithEvents_flushesTheSessionBeforeResponding() {
    val sessionService = RecordingSessionService()

    testApplication {
      application {
        adkApiModule(testConfig(sessionService))
        recordResponsesIn(sessionService.calls)
      }

      val response =
        client.post("/apps/mock-agent/users/u/sessions") {
          jsonBody("""{"sessionId":"s1","events":[$USER_EVENT,$MODEL_EVENT]}""")
        }

      assertThat(response.status).isEqualTo(HttpStatusCode.OK)
      assertThat(sessionService.calls)
        .containsExactly("append", "append", "flush:s1", "respond")
        .inOrder()
    }
  }

  @Test
  fun createSessionWithEventsAndNoId_flushesTheGeneratedSession() {
    val sessionService = RecordingSessionService()

    testApplication {
      application { adkApiModule(testConfig(sessionService)) }

      val response =
        client.post("/apps/mock-agent/users/u/sessions") {
          jsonBody("""{"events":[$USER_EVENT]}""")
        }

      assertThat(response.status).isEqualTo(HttpStatusCode.OK)
      val created = sessionService.listSessions("mock-agent", "u").sessions.single()
      assertThat(sessionService.calls)
        .containsExactly("append", "flush:${created.key.id}")
        .inOrder()
    }
  }

  @Test
  fun createSessionByPathWithEvents_flushesTheSessionBeforeResponding() {
    val sessionService = RecordingSessionService()

    testApplication {
      application {
        adkApiModule(testConfig(sessionService))
        recordResponsesIn(sessionService.calls)
      }

      val response =
        client.post("/apps/mock-agent/users/u/sessions/s1") {
          jsonBody("""{"events":[$USER_EVENT]}""")
        }

      assertThat(response.status).isEqualTo(HttpStatusCode.OK)
      assertThat(sessionService.calls).containsExactly("append", "flush:s1", "respond").inOrder()
    }
  }

  @Test
  fun createSessionWithEvents_flushFails_failsTheRequest() {
    val sessionService =
      RecordingSessionService(onFlush = { throw SessionException("flush failed") })

    testApplication {
      application { adkApiModule(testConfig(sessionService)) }

      val outcome = runCatching {
        client.post("/apps/mock-agent/users/u/sessions") {
          jsonBody("""{"sessionId":"s3","events":[$USER_EVENT]}""")
        }
      }

      // No StatusPages is installed, so the failure surfaces as a throw.
      assertThat(outcome.exceptionOrNull()).isInstanceOf(SessionException::class.java)
      assertThat(sessionService.calls).containsExactly("append", "flush:s3").inOrder()
    }
  }

  @Test
  fun createSessionWithoutEvents_doesNotFlush() {
    val sessionService = RecordingSessionService()

    testApplication {
      application { adkApiModule(testConfig(sessionService)) }

      val response =
        client.post("/apps/mock-agent/users/u/sessions") { jsonBody("""{"sessionId":"s2"}""") }

      assertThat(response.status).isEqualTo(HttpStatusCode.OK)
      assertThat(sessionService.calls).isEmpty()
    }
  }

  @Test
  fun applicationStop_flushesEverySession() {
    val sessionService = RecordingSessionService()

    testApplication { application { adkApiModule(testConfig(sessionService)) } }

    assertThat(sessionService.calls).containsExactly("flush:all")
  }

  @Test
  fun applicationStop_unsubscribesTheFlush() {
    val sessionService = RecordingSessionService()
    lateinit var stopped: Application

    testApplication {
      application {
        adkApiModule(testConfig(sessionService))
        stopped = this
      }
    }
    // A dev-mode reload keeps the monitor, so a later stop on it must not flush this service again.
    stopped.environment.monitor.raise(ApplicationStopping, stopped)

    assertThat(sessionService.calls).containsExactly("flush:all")
  }

  @Test
  fun applicationStop_flushFails_logsOnlyTheFailureType() {
    val sessionService =
      RecordingSessionService(onFlush = { throw IllegalStateException("store down") })

    val log = captureStandardError {
      testApplication { application { adkApiModule(testConfig(sessionService)) } }
    }

    assertThat(sessionService.calls).containsExactly("flush:all")
    assertThat(log)
      .contains("Failed to flush buffered session writes on stop: java.lang.IllegalStateException")
    assertThat(log).doesNotContain("store down")
  }

  @Test(timeout = 20_000)
  fun applicationStop_flushHangs_stopsAfterTheTimeout() {
    val sessionService = RecordingSessionService(onFlush = { awaitCancellation() })
    lateinit var stopStarted: TimeMark

    val log = captureStandardError {
      testApplication {
        application { adkApiModule(testConfig(sessionService)) }
        startApplication()
        stopStarted = TimeSource.Monotonic.markNow()
      }
    }

    val stopTime = stopStarted.elapsedNow()
    assertThat(sessionService.calls).containsExactly("flush:all")
    assertThat(stopTime).isAtLeast(3.seconds)
    assertThat(stopTime).isLessThan(10.seconds)
    assertThat(log).contains("Timed out flushing buffered session writes on stop")
  }

  /** Runs [block] and returns what it wrote to standard error, where the server logs. */
  private fun captureStandardError(block: () -> Unit): String {
    val original = System.err
    val captured = ByteArrayOutputStream()
    System.setErr(PrintStream(captured, true))
    try {
      block()
    } finally {
      System.setErr(original)
    }
    return captured.toString()
  }

  /** Records each sent response in [calls], so a test can see whether a flush came before it. */
  private fun Application.recordResponsesIn(calls: MutableList<String>) {
    install(createApplicationPlugin("RecordResponses") { on(ResponseSent) { calls += "respond" } })
  }

  private fun testConfig(sessionService: SessionService) =
    AdkServerConfig(
      agentLoader = FakeAgentLoader(),
      sessionService = sessionService,
      artifactService = FakeArtifactService(),
      apiServerSpanExporter = ApiServerSpanExporter(),
    )

  private companion object {
    const val USER_EVENT = """{"author":"user","content":{"role":"user","parts":[{"text":"hi"}]}}"""
    const val MODEL_EVENT =
      """{"author":"mock-agent","content":{"role":"model","parts":[{"text":"hello"}]}}"""
  }
}

/**
 * An [InMemorySessionService] that records appends and flushes in [calls], and runs [onFlush] on
 * each flush.
 */
internal class RecordingSessionService(
  private val onFlush: suspend () -> Unit = {},
  private val delegate: SessionService = InMemorySessionService(),
) : SessionService by delegate {
  val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())

  override suspend fun appendEvent(session: Session, event: Event): Event {
    calls += "append"
    return delegate.appendEvent(session, event)
  }

  override suspend fun flush(key: SessionKey?) {
    calls += if (key == null) "flush:all" else "flush:${key.id}"
    onFlush()
  }
}
