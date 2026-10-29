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
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.ToolContext
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.future.await
import kotlinx.coroutines.reactive.asFlow
import org.reactivestreams.Publisher

/**
 * Java-friendly base for a [BaseTool] whose result is a Reactive Streams [Publisher]. A Java
 * subclass overrides [declaration] and [runPublisher], whose values a connected live run streams;
 * anywhere else the call fails, as [BaseTool.run] describes for a Flow. A live run declares the
 * tool non-blocking on its own, so the model can keep the conversation going while results arrive.
 *
 * A live tool can read the experimental `context.getLiveRequests()` through
 * [AsyncJavaHelpers.asPublisher].
 */
@AdkJavaInteropApi
abstract class BasePublisherTool
@JvmOverloads
constructor(name: String, description: String, customMetadata: Map<String, Any> = emptyMap()) :
  BaseTool(name, description, isLongRunning = false, customMetadata) {

  // Every result comes from a publisher, so a live run can mark the tool before calling it.
  @OptIn(FrameworkInternalApi::class) final override val isStreaming: Boolean = true

  /**
   * Publisher counterpart of [run]; implement the tool's logic here. Return the publisher promptly
   * and do any blocking work inside it, not before returning it -- this runs on the engine's
   * coroutine thread.
   */
  @JvmSuppressWildcards
  protected abstract fun runPublisher(
    context: ToolContext,
    args: Map<String, Any?>,
  ): Publisher<out @JvmWildcard Any>

  final override suspend fun run(context: ToolContext, args: Map<String, Any?>): Any =
    runPublisher(context, args).asFlow()

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
