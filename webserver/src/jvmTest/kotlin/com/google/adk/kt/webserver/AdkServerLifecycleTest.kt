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

import com.google.adk.kt.webserver.dev.AdkDevServer
import com.google.adk.kt.webserver.telemetry.ApiServerSpanExporter
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.URL
import java.util.Collections
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Server classes against a real socket, which `testApplication` based tests cannot reach.
 *
 * Covers start and stop, which surface each class mounts, and which interface each binds.
 */
@RunWith(JUnit4::class)
class AdkServerLifecycleTest {

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

  @Test
  fun devServer_addsTheDevelopmentSurface() {
    val port = freePort()
    val server = AdkDevServer(testConfig(port))

    try {
      server.start()
      awaitHealthy(port)

      // An unmounted route is Ktor's 404; a mounted stub answers with some other status.
      assertThat(statusOf(port, DEV_ONLY_PATH)).isNotEqualTo(HttpURLConnection.HTTP_NOT_FOUND)
      assertThat(statusOf(port, "/dev-ui/")).isNotEqualTo(HttpURLConnection.HTTP_NOT_FOUND)
    } finally {
      server.stop()
    }
  }

  @Test
  fun apiServer_servesTheDevUiWhenTheConfigAsks() {
    val port = freePort()
    val server = AdkApiServer(testConfig(port).copy(webUiEnabled = true))

    try {
      server.start()
      awaitHealthy(port)

      assertThat(statusOf(port, "/dev-ui/")).isNotEqualTo(HttpURLConnection.HTTP_NOT_FOUND)
      // Still the API server: the development-only routes stay off.
      assertThat(statusOf(port, DEV_ONLY_PATH)).isEqualTo(HttpURLConnection.HTTP_NOT_FOUND)
    } finally {
      server.stop()
    }
  }

  @Test
  fun apiServer_omitsTheDevelopmentSurface() {
    val port = freePort()
    val server = newServer(port)

    try {
      server.start()
      awaitHealthy(port)

      assertThat(statusOf(port, DEV_ONLY_PATH)).isEqualTo(HttpURLConnection.HTTP_NOT_FOUND)
      assertThat(statusOf(port, "/dev-ui/")).isEqualTo(HttpURLConnection.HTTP_NOT_FOUND)
    } finally {
      server.stop()
    }
  }

  @Test
  fun deprecatedAdkWebServer_stillStartsAndStops() {
    val port = freePort()
    val server = newDeprecatedServer(port)

    try {
      server.start()
      awaitHealthy(port)

      // The shim must keep the full development surface its callers already had.
      assertThat(statusOf(port, DEV_ONLY_PATH)).isNotEqualTo(HttpURLConnection.HTTP_NOT_FOUND)
      assertThat(statusOf(port, "/dev-ui/")).isNotEqualTo(HttpURLConnection.HTTP_NOT_FOUND)
    } finally {
      server.stop()
    }
    assertThat(portIsFree(port)).isTrue()
  }

  @Test
  fun apiServer_bindsLoopbackByDefault() {
    val offLoopback = assumeOffLoopbackAddress()
    val port = freePort()
    val server = newServer(port)

    try {
      server.start()
      awaitHealthy(port)

      // Reachable on loopback, and refused elsewhere: dropping `host` would serve both.
      assertThat(healthStatusOrNull(port)).isEqualTo(HttpURLConnection.HTTP_OK)
      assertThat(healthStatusOrNull(port, offLoopback)).isNull()
    } finally {
      server.stop()
    }
  }

  @Test
  fun deprecatedAdkWebServer_stillBindsEveryInterface() {
    val offLoopback = assumeOffLoopbackAddress()
    val port = freePort()
    val server = newDeprecatedServer(port)

    try {
      server.start()
      awaitHealthy(port)

      // Reachable on loopback, and off it too, which a container deployment needs.
      assertThat(healthStatusOrNull(port)).isEqualTo(HttpURLConnection.HTTP_OK)
      assertThat(healthStatusOrNull(port, offLoopback)).isEqualTo(HttpURLConnection.HTTP_OK)
    } finally {
      server.stop()
    }
  }

  private fun newServer(port: Int) = AdkApiServer(testConfig(port))

  @Suppress("DEPRECATION") // AdkWebServer is deprecated by this change.
  private fun newDeprecatedServer(port: Int) =
    AdkWebServer(
      port = port,
      sessionService = FakeSessionService(),
      artifactService = FakeArtifactService(),
      agentLoader = FakeAgentLoader(),
      apiServerSpanExporter = ApiServerSpanExporter(),
    )

  private fun testConfig(port: Int) =
    AdkServerConfig(
      agentLoader = FakeAgentLoader(),
      sessionService = FakeSessionService(),
      artifactService = FakeArtifactService(),
      port = port,
    )

  /** HTTP status for [path]: 404 means the route is not mounted. */
  private fun statusOf(port: Int, path: String): Int {
    val connection = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
    connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
    connection.readTimeout = CONNECT_TIMEOUT_MILLIS
    return try {
      connection.responseCode
    } finally {
      connection.disconnect()
    }
  }

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

  private fun healthStatusOrNull(port: Int, host: String = "127.0.0.1"): Int? =
    try {
      val connection = URL("http://$host:$port/health").openConnection() as HttpURLConnection
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

  /** A non-loopback address of this host; skips the test when it has none. */
  private fun assumeOffLoopbackAddress(): String {
    val address = offLoopbackAddressOrNull()
    assumeTrue("No off-loopback address on this host", address != null)
    return address!!
  }

  /** A non-loopback address of this host, or null when it has none to distinguish binds with. */
  private fun offLoopbackAddressOrNull(): String? =
    NetworkInterface.getNetworkInterfaces()
      .asSequence()
      .filter { it.isUp && !it.isLoopback }
      .flatMap { it.inetAddresses.asSequence() }
      .filterIsInstance<Inet4Address>()
      .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
      ?.hostAddress

  private companion object {
    const val STARTUP_TIMEOUT_MILLIS = 20_000L
    const val SHUTDOWN_TIMEOUT_MILLIS = 10_000L
    const val POLL_INTERVAL_MILLIS = 50L
    const val CONNECT_TIMEOUT_MILLIS = 1_000
    const val RACING_THREADS = 8
    const val DEV_ONLY_PATH = "/apps/mock-agent/eval_sets"
    const val NANOS_PER_MILLI = 1_000_000L
  }
}
