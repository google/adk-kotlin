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

package com.google.adk.kt.webserver.telemetry

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger
import org.slf4j.event.Level

/** A local OTLP/HTTP endpoint that records every request and answers it with an empty 200. */
internal class OtlpReceiver : AutoCloseable {

  /** One request as received; header names are lower-cased. */
  class Request(val path: String, val headers: Map<String, List<String>>, val body: ByteArray) {
    fun header(name: String): String? = headers[name.lowercase()]?.firstOrNull()
  }

  // IPv4 by name: some test hosts resolve the loopback address to ::1.
  private val server: HttpServer = HttpServer.create(InetSocketAddress(LOOPBACK, 0), 0)

  val requests: MutableList<Request> = CopyOnWriteArrayList()

  /** Base URL, as `OTEL_EXPORTER_OTLP_ENDPOINT` takes it. */
  val endpoint: String
    get() = "http://$LOOPBACK:${server.address.port}"

  init {
    server.createContext("/") { exchange ->
      exchange.use {
        requests +=
          Request(
            path = it.requestURI.path.orEmpty(),
            headers = it.requestHeaders.entries.associate { (k, v) -> k.lowercase() to v.toList() },
            body = it.requestBody.readBytes(),
          )
        it.responseHeaders.add("Content-Type", "application/x-protobuf")
        it.sendResponseHeaders(200, -1)
      }
    }
    server.start()
  }

  /** Whether some request body holds [text]; OTLP protobuf carries strings as plain bytes. */
  fun received(text: String): Boolean = countReceived(text) > 0

  /** How many times [text], which must be ASCII, occurs across the request bodies. */
  fun countReceived(text: String): Int = requests.sumOf {
    Regex.fromLiteral(text).findAll(String(it.body, Charsets.ISO_8859_1)).count()
  }

  override fun close() {
    server.stop(0)
  }

  private companion object {
    const val LOOPBACK = "127.0.0.1"
  }
}

/** Runs [block] with each system property set to its value, restoring them afterwards. */
internal fun <T> withSystemProperties(vararg properties: Pair<String, String>, block: () -> T): T {
  val previous = properties.associate { (key, _) -> key to System.getProperty(key) }
  for ((key, value) in properties) setOrClear(key, value)
  try {
    return block()
  } finally {
    for ((key, value) in previous) setOrClear(key, value)
  }
}

private fun setOrClear(key: String, value: String?) {
  if (value == null) System.clearProperty(key) else System.setProperty(key, value)
}

/** A [TelemetryLog] that keeps what it was told. */
internal class RecordingLog : TelemetryLog {
  val entries: MutableList<Pair<Level, String>> = CopyOnWriteArrayList()

  val warnings: List<String>
    get() = entries.filter { it.first == Level.WARN }.map { it.second }

  override fun invoke(level: Level, message: String) {
    entries += level to message
  }
}

/** Runs [block] and returns everything OpenTelemetry logged meanwhile, messages and causes. */
internal fun captureOpenTelemetryLogs(block: () -> Unit): String {
  val logged = StringBuilder()
  val handler =
    object : Handler() {
      override fun publish(record: LogRecord?) {
        if (record == null) return
        synchronized(logged) {
          logged.appendLine(record.message)
          for (parameter in record.parameters.orEmpty()) logged.appendLine(parameter.toString())
          // Bounded, since a cause chain can loop back on itself.
          for (cause in generateSequence(record.thrown) { it.cause }.take(16)) {
            logged.appendLine(cause.toString())
          }
        }
      }

      override fun flush() {}

      override fun close() {}
    }
  val otelLogger = Logger.getLogger("io.opentelemetry")
  val previousLevel = otelLogger.level
  otelLogger.level = java.util.logging.Level.ALL
  otelLogger.addHandler(handler)
  try {
    block()
  } finally {
    otelLogger.removeHandler(handler)
    otelLogger.level = previousLevel
  }
  return synchronized(logged) { logged.toString() }
}
