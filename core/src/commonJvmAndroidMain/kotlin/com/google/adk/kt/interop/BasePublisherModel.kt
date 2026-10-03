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
import com.google.adk.kt.annotations.ExperimentalLiveApi
import com.google.adk.kt.logging.LoggerFactory
import com.google.adk.kt.models.LiveConnection
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.Model
import com.google.adk.kt.models.liveConnectionsUnsupported
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.future.asDeferred
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.asFlow
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
   * blocking work inside it; fail it rather than cancel it. If the caller of [connect] is cancelled
   * before it returns, the base closes the connection if it arrives within 10 seconds and otherwise
   * cancels the future; if your handshake opens a connection after that cancel, close it through
   * your transport, not the blocking [LiveConnection.close].
   */
  @ExperimentalLiveApi
  protected open fun connectAsync(request: LlmRequest): CompletableFuture<out LiveConnection> =
    throw liveConnectionsUnsupported()

  @OptIn(ExperimentalLiveApi::class)
  final override suspend fun connect(request: LlmRequest): LiveConnection {
    val collector = currentCoroutineContext()[ContinuationInterceptor] ?: EmptyCoroutineContext
    val future = connectAsync(request)
    val connection: LiveConnection? =
      try {
        // A plain await() would cancel the future and leak the late connection; await a Deferred.
        future.asDeferred().await().also {
          currentCoroutineContext().ensureActive() // await() skips this check for a done future.
        }
      } catch (e: CancellationException) {
        // No caller gets the connection now, so close it if it arrives.
        @Suppress("UnsafeCoroutineCrossing") // Only the Java future is shared with the closer.
        val unusedCloser =
          CoroutineScope(collector).launch(start = CoroutineStart.UNDISPATCHED) {
            closeLateConnection(future)
          }
        throw e
      }
    return checkNotNull(connection) { "connectAsync of $name completed with null" }
  }
}

/**
 * Closes what [future] delivers after its caller was cancelled, rather than leak it: cancelling a
 * [CompletableFuture] doesn't stop the connect behind it. Nothing cancels this coroutine.
 */
@OptIn(ExperimentalLiveApi::class)
private suspend fun closeLateConnection(future: CompletableFuture<out LiveConnection>) {
  // At the deadline the plain await() cancels the future, which may complete just before that.
  val awaited =
    withTimeoutOrNull(LATE_CONNECTION_CLOSE_TIMEOUT) {
      // Rethrow only the timeout: a failed or cancelled future means no connection.
      try {
        Result.success(future.await())
      } catch (e: TimeoutCancellationException) {
        throw e
      } catch (e: Exception) {
        Result.failure(e)
      }
    }
  val late = awaited?.getOrNull() ?: runCatching { future.getNow(null) }.getOrNull()
  if (late == null) {
    if (awaited == null) {
      logger.warn {
        "Gave up waiting for a late live connection after $LATE_CONNECTION_CLOSE_TIMEOUT."
      }
    }
    return
  }
  withTimeoutOrNull(LATE_CONNECTION_CLOSE_TIMEOUT) {
    try {
      late.closeSession()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      logger.warn { "Closing a late live connection failed (${e::class.simpleName})." }
    }
  }
    ?: logger.warn {
      "Gave up closing a late live connection after $LATE_CONNECTION_CLOSE_TIMEOUT."
    }
}

/**
 * Bounds both waiting for, and then closing, a connection that arrives after its caller was
 * cancelled, so the background closer can't wait forever.
 */
private val LATE_CONNECTION_CLOSE_TIMEOUT = 10.seconds

@OptIn(AdkJavaInteropApi::class)
private val logger = LoggerFactory.getLogger(BasePublisherModel::class)
