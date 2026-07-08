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

package com.google.adk.kt.tools.mcp

import com.google.adk.kt.logging.LoggerFactory
import io.modelcontextprotocol.client.McpAsyncClient
import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.spec.McpSchema
import io.modelcontextprotocol.spec.McpSchema.ClientCapabilities
import java.time.Duration
import kotlin.jvm.JvmStatic
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns and manages MCP client sessions.
 *
 * Sessions are pooled by a key derived from the connection parameters and the per-call headers: a
 * stdio connection ignores headers and always maps to a single shared session, while SSE and
 * Streamable HTTP connections get one session per distinct header set. The pool is the single owner
 * of every session, so [closeAll] can tear them all down and [getSession] replaces a dead one in
 * place (via its `stale` parameter) for everyone sharing it.
 */
internal class McpSessionManager(
  private val connectionParams: McpConnectionParameters,
  private val transportBuilder: McpTransportBuilder = DefaultMcpTransportBuilder(),
  private val progressConsumers: List<(McpSchema.ProgressNotification) -> Unit> = emptyList(),
  // Test seam: builds and initializes a ready-to-use session for the given headers. Defaults to the
  // real transport-backed client; unit tests inject a fake to exercise pooling without a server.
  private val sessionOpener: ((Map<String, String>) -> McpAsyncClient)? = null,
) : SessionManager {

  /** Guards [sessions] across the suspending create+initialize critical section. */
  private val mutex = Mutex()
  private val sessions = mutableMapOf<String, McpAsyncClient>()

  override suspend fun getSession(
    headers: Map<String, String>,
    stale: McpAsyncClient?,
  ): McpAsyncClient {
    val key = sessionKey(headers)
    // Evict-then-get-or-create, all under one lock. If a known-dead `stale` is named and still
    // pooled, drop it so we recreate below; whichever caller wins that race closes it, while other
    // sharers holding the same dead client find the replacement already in place (created once).
    val (session, evicted) =
      mutex.withLock {
        val evicted = if (stale != null && sessions[key] === stale) sessions.remove(key) else null
        val session = sessions[key] ?: openSession(headers).also { sessions[key] = it }
        session to evicted
      }
    evicted?.closeQuietly() // Closed outside the lock; the stale client is already dead.
    return session
  }

  /**
   * Builds and initializes a fresh session, or delegates to the injected [sessionOpener] in tests.
   */
  private suspend fun openSession(headers: Map<String, String>): McpAsyncClient =
    sessionOpener?.invoke(headers)
      ?: createAsyncSession(headers).also { client ->
        val initResult = client.initialize().awaitSingle()
        logger.debug { "Initialized pooled McpAsyncClient: $initResult" }
      }

  // Not suspend: it's driven by the non-suspend AutoCloseable.close(). runBlocking bridges the
  // coroutine Mutex that getSession holds across a suspending initialize(). The lock is brief:
  // snapshot + clear so an in-flight getSession can't re-pool afterwards, then the client.close()
  // calls run outside it (fire-and-forget).
  override fun closeAll() {
    val toClose = runBlocking {
      mutex.withLock { sessions.values.toList().also { sessions.clear() } }
    }
    toClose.forEach { it.closeQuietly() }
  }

  /**
   * Builds (but does not initialize) a client for [headers], merging them into the base params.
   *
   * Not part of [SessionManager]: callers must go through [getSession] so sessions stay pooled and
   * owned. Exposed within the module only so unit tests can assert transport/timeout configuration.
   */
  fun createAsyncSession(headers: Map<String, String> = emptyMap()): McpAsyncClient {
    val params =
      if (headers.isNotEmpty()) {
        when (connectionParams) {
          is McpConnectionParameters.Sse ->
            connectionParams.copy(headers = connectionParams.headers + headers)
          is McpConnectionParameters.StreamableHttp ->
            connectionParams.copy(headers = connectionParams.headers + headers)
          else -> connectionParams
        }
      } else {
        connectionParams
      }
    return initializeAsyncSession(params, transportBuilder, progressConsumers)
  }

  /**
   * Pool key for [headers]. Stdio ignores headers (a single shared session); SSE/Streamable HTTP
   * get one session per distinct header set.
   */
  private fun sessionKey(headers: Map<String, String>): String =
    when (connectionParams) {
      is McpConnectionParameters.Stdio -> STDIO_SESSION_KEY
      else -> if (headers.isEmpty()) NO_HEADERS_SESSION_KEY else headers.toSortedMap().toString()
    }

  companion object {
    private const val STDIO_SESSION_KEY = "stdio_session"
    private const val NO_HEADERS_SESSION_KEY = "session_no_headers"

    /**
     * Initializes an asynchronous MCP client session.
     *
     * @param connectionParams The parameters for the MCP connection.
     * @param transportBuilder The builder for the MCP transport.
     * @param progressConsumers The progress consumers for the MCP client.
     * @return An initialized McpAsyncClient.
     */
    @JvmStatic
    fun initializeAsyncSession(
      connectionParams: McpConnectionParameters,
      transportBuilder: McpTransportBuilder = DefaultMcpTransportBuilder(),
      progressConsumers: List<(McpSchema.ProgressNotification) -> Unit> = emptyList(),
    ): McpAsyncClient {
      val (initializationTimeout: Duration?, requestTimeout: Duration?) =
        when (connectionParams) {
          is McpConnectionParameters.Stdio -> null to connectionParams.timeoutDuration
          is McpConnectionParameters.Sse ->
            connectionParams.timeout to connectionParams.sseReadTimeout
          is McpConnectionParameters.StreamableHttp ->
            connectionParams.timeout to connectionParams.readTimeout
        }

      val transport = transportBuilder.build(connectionParams)
      val builder =
        McpClient.async(transport)
          .initializationTimeout(initializationTimeout ?: Duration.ofMinutes(5))
          .requestTimeout(requestTimeout ?: Duration.ofMinutes(5))
          .capabilities(ClientCapabilities.builder().build())
          .loggingConsumer { notification ->
            mono {
                val data = notification.data()
                when (notification.level()) {
                  McpSchema.LoggingLevel.DEBUG -> logger.debug { data.toString() }
                  McpSchema.LoggingLevel.INFO,
                  McpSchema.LoggingLevel.NOTICE -> logger.info { data.toString() }
                  McpSchema.LoggingLevel.WARNING -> logger.warn { data.toString() }
                  McpSchema.LoggingLevel.ERROR,
                  McpSchema.LoggingLevel.CRITICAL,
                  McpSchema.LoggingLevel.ALERT,
                  McpSchema.LoggingLevel.EMERGENCY -> logger.error { data.toString() }
                  null -> logger.info { data.toString() }
                }
                null
              }
              .then()
          }

      for (consumer in progressConsumers) {
        builder.progressConsumer { notification -> mono { consumer(notification) }.then() }
      }
      return builder.build()
    }

    private val logger = LoggerFactory.getLogger(McpSessionManager::class)

    /** Closes this client, swallowing and logging any error (close is best-effort). */
    private fun McpAsyncClient.closeQuietly() {
      try {
        close()
      } catch (e: Exception) {
        logger.warn(e) { "Failed to close McpAsyncClient session: ${e.message}" }
      }
    }
  }
}
