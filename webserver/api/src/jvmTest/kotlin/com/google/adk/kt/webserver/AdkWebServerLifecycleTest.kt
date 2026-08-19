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

import com.google.adk.kt.webserver.telemetry.ApiServerSpanExporter
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.util.Collections
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Start and stop against a real socket, which `testApplication` based tests cannot reach. */
@RunWith(JUnit4::class)
class AdkWebServerLifecycleTest {

  @Test
  fun stop_shutsDownAServerStartedWithWait() {
    val port = freePort()
    val server = newServer(port)
    val serverThread =
      thread(name = "adk-webserver-lifecycle-test", isDaemon = true) { server.start(wait = true) }

    try {
      awaitHealthy(port)

      server.stop()

      // start(wait = true) returns only after shutdown, so a live thread means stop() did nothing.
      serverThread.join(SHUTDOWN_TIMEOUT_MILLIS)
      assertThat(serverThread.isAlive).isFalse()
    } finally {
      server.stop()
    }
  }

  @Test
  fun concurrentStarts_startASingleServer() {
    val port = freePort()
    val server = newServer(port)
    // A barrier, not a latch: every racer must be parked before any of them calls start().
    val startLine = CyclicBarrier(RACING_THREADS)
    val failures = Collections.synchronizedList(mutableListOf<Throwable>())
    val racers =
      (1..RACING_THREADS).map {
        thread(name = "adk-webserver-start-race-$it", isDaemon = true) {
          try {
            startLine.await(STARTUP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            server.start()
          } catch (t: Throwable) {
            failures.add(t)
          }
        }
      }

    try {
      racers.forEach {
        it.join(STARTUP_TIMEOUT_MILLIS)
        assertThat(it.isAlive).isFalse()
      }

      // Losing the race must be a no-op, not a second engine fighting for the same port.
      assertThat(failures).isEmpty()
      awaitHealthy(port)
    } finally {
      server.stop()
    }

    assertThat(portIsFree(port)).isTrue()
  }

  @Test
  fun startAfterAFailedStart_needsStopFirst() {
    val port = freePort()
    val server = newServer(port)

    ServerSocket(port).use { assertThrows(Exception::class.java) { server.start() } }

    // The failed engine stays recorded, so retrying without stop() hits the already-started
    // guard and binds nothing, even though the port is free again.
    server.start()
    assertThat(portIsFree(port)).isTrue()

    server.stop()
    try {
      server.start()
      awaitHealthy(port)
    } finally {
      server.stop()
    }
  }

  private fun newServer(port: Int) =
    AdkWebServer(
      port = port,
      sessionService = FakeSessionService(),
      artifactService = FakeArtifactService(),
      agentLoader = FakeAgentLoader(),
      apiServerSpanExporter = ApiServerSpanExporter(),
    )

  private fun portIsFree(port: Int): Boolean =
    try {
      ServerSocket(port).close()
      true
    } catch (_: IOException) {
      false
    }

  private fun awaitHealthy(port: Int) {
    val deadline = System.nanoTime() + STARTUP_TIMEOUT_MILLIS * NANOS_PER_MILLI
    while (System.nanoTime() < deadline) {
      if (healthStatusOrNull(port) == HttpURLConnection.HTTP_OK) return
      Thread.sleep(POLL_INTERVAL_MILLIS)
    }
    throw AssertionError("Server did not serve /health within $STARTUP_TIMEOUT_MILLIS ms")
  }

  private fun healthStatusOrNull(port: Int): Int? =
    try {
      val connection = URL("http://127.0.0.1:$port/health").openConnection() as HttpURLConnection
      connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
      connection.readTimeout = CONNECT_TIMEOUT_MILLIS
      try {
        connection.responseCode
      } finally {
        connection.disconnect()
      }
    } catch (_: IOException) {
      null
    }

  private fun freePort(): Int = ServerSocket(0).use { it.localPort }

  private companion object {
    const val STARTUP_TIMEOUT_MILLIS = 20_000L
    const val SHUTDOWN_TIMEOUT_MILLIS = 10_000L
    const val POLL_INTERVAL_MILLIS = 50L
    const val CONNECT_TIMEOUT_MILLIS = 1_000
    const val RACING_THREADS = 8
    const val NANOS_PER_MILLI = 1_000_000L
  }
}
