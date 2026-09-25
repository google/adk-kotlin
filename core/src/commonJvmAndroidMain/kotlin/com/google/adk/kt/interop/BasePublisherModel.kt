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
import com.google.adk.kt.logging.LoggerFactory
import com.google.adk.kt.models.LiveConnection
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.Model
import com.google.adk.kt.models.liveConnectionsUnsupported
import java.util.concurrent.CompletableFuture
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.future.await
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.reactivestreams.Publisher

/**
 * Java-friendly base for implementing a [Model]. A Java subclass overrides [generateContentJava],
 * which returns a Reactive Streams [Publisher]; this base adapts it to the `Flow`-returning
 * [generateContent] contract that Java cannot express. Both the unary and streaming cases flow
 * through that one method, exactly as they do for a Kotlin [Model]; a subclass that supports live
 * connections also overrides [connectAsync].
 */
@AdkJavaInteropApi
abstract class BasePublisherModel(final override val name: String) : Model {

  /**
   * Generates content for [request]. When [stream] is false the publisher emits exactly one
   * aggregated response; when true it may emit any number of `partial = true` responses followed by
   * one aggregated `partial = false` response.
   */
  @JvmSuppressWildcards
  protected abstract fun generateContentJava(
    request: LlmRequest,
    stream: Boolean,
  ): Publisher<LlmResponse>

  final override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> =
    generateContentJava(request, stream).asFlow()

  /**
   * Opens a live connection for [request], completing once it is open and failing if it cannot. The
   * default throws [UnsupportedOperationException], as a [Model] does; override it to support
   * [connect], typically with a [BaseFutureLiveConnection], and return the future promptly with any
   * blocking work inside it. If the caller is cancelled, the base gives the future up to 10
   * seconds, cancels it if still pending, and spends up to 10 more closing any connection it
   * delivered, so the cancelled caller waits up to 20 seconds; shut down a connection you open
   * after the cancel through your transport rather than the blocking [LiveConnection.close].
   */
  protected open fun connectAsync(request: LlmRequest): CompletableFuture<out LiveConnection> =
    throw liveConnectionsUnsupported()

  final override suspend fun connect(request: LlmRequest): LiveConnection {
    var future: CompletableFuture<out LiveConnection>? = null
    val connection: LiveConnection? =
      try {
        awaitHook("connectAsync") {
          val started = connectAsync(request)
          future = started
          // Awaits a copy, so cancelling the caller does not cancel the hook's future.
          started.thenApply { it }
        }
      } catch (e: CancellationException) {
        future?.let { closeLateConnection(it) }
        throw e
      }
    return checkNotNull(connection) { "connectAsync of $name completed with null" }
  }
}

/** Closes what [future] delivers after its caller was cancelled, rather than leak it. */
private suspend fun closeLateConnection(future: CompletableFuture<out LiveConnection>) {
  withContext(NonCancellable) {
    // On timeout the await cancels the future; its outcome is read from the future below.
    val unused = withTimeoutOrNull(LATE_CONNECTION_CLOSE_TIMEOUT) { runCatching { future.await() } }
    // Checked after the wait, because the future can complete between the timeout and the cancel.
    if (future.isDone && !future.isCompletedExceptionally) {
      withTimeoutOrNull(LATE_CONNECTION_CLOSE_TIMEOUT) {
        try {
          val late: LiveConnection? = future.join()
          late?.closeSession()
        } catch (e: Exception) {
          // The caller's cancellation takes precedence; only the failure's type is logged.
          if (e !is CancellationException) {
            logger.debug { "Closing a late live connection failed: ${e::class.simpleName}" }
          }
        }
      }
    }
  }
}

/**
 * Bounds both waiting for, and then closing, a connection that arrives after its caller was
 * cancelled, so neither can hang the caller.
 */
private val LATE_CONNECTION_CLOSE_TIMEOUT = 10.seconds

private val logger = LoggerFactory.getLogger(BasePublisherModel::class)
