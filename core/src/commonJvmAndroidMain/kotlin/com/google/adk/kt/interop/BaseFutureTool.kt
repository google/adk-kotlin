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
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.ToolContext
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.future.await
import kotlinx.coroutines.reactive.asFlow
import org.reactivestreams.Publisher

/**
 * Java-friendly base for implementing a [BaseTool] asynchronously. A Java subclass overrides
 * [declaration] and [runAsync], which returns a [CompletableFuture]; this base awaits it to satisfy
 * the `suspend` [run] contract that Java cannot express.
 *
 * Cancellation composes: cancelling the invocation cancels the `await`, and
 * `CompletableFuture.cancel()` completes the stage exceptionally, which the coroutine observes.
 */
@AdkJavaInteropApi
abstract class BaseFutureTool
@JvmOverloads
constructor(
  name: String,
  description: String,
  isLongRunning: Boolean = false,
  customMetadata: Map<String, Any> = emptyMap(),
) : BaseTool(name, description, isLongRunning, customMetadata) {

  /**
   * Asynchronous counterpart of [run]; return the future promptly and do any blocking work inside
   * it, since this runs on the engine's coroutine thread. The result must be JSON-native (a
   * non-[Map] is wrapped under [BaseTool.RESULT_KEY]), or a Reactive Streams [Publisher] that
   * streams as [BaseTool.run] describes for a Flow. A live tool can read the experimental
   * `context.getLiveRequests()` through `AsyncJavaHelpers.asPublisher`, whose signals can arrive on
   * the engine's send thread, so do slow work on another thread.
   */
  @JvmSuppressWildcards
  protected abstract fun runAsync(
    context: ToolContext,
    args: Map<String, Any?>,
  ): CompletableFuture<Any>

  final override suspend fun run(context: ToolContext, args: Map<String, Any?>): Any =
    when (val result = runAsync(context, args).await()) {
      // A Reactive Streams Publisher streams like a Kotlin Flow; a JDK Flow.Publisher is not one.
      is Publisher<*> -> @Suppress("UNCHECKED_CAST") (result as Publisher<Any>).asFlow()
      else -> result
    }

  /**
   * Asynchronous counterpart of [BaseTool.processLlmRequest]; override to attach instructions,
   * artifacts, or other data to the request. Return the future promptly and do any blocking work
   * inside it, not before returning it; the default appends this tool to the request, like the
   * engine.
   */
  protected open fun processLlmRequestAsync(
    toolContext: ToolContext,
    llmRequest: LlmRequest,
  ): CompletableFuture<LlmRequest> =
    CompletableFuture.completedFuture(llmRequest.appendTools(listOf(this)))

  final override suspend fun processLlmRequest(
    toolContext: ToolContext,
    llmRequest: LlmRequest,
  ): LlmRequest = processLlmRequestAsync(toolContext, llmRequest).await()
}
