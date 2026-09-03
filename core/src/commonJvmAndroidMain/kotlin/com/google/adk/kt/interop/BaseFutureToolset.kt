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

import com.google.adk.kt.agents.ReadonlyContext
import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.tools.Toolset
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.future.await

/**
 * Java-friendly base for implementing a [Toolset] -- a dynamically resolved group of tools. Both
 * engine methods are `suspend`; a Java subclass returns [CompletableFuture]s instead. Return each
 * future promptly and do any blocking work inside it, not before returning it. [processLlmRequest]
 * defaults to returning the request unchanged; override [processLlmRequestAsync] to rewrite it.
 */
@AdkJavaInteropApi
abstract class BaseFutureToolset : Toolset {

  final override suspend fun getTools(readonlyContext: ReadonlyContext?): List<BaseTool> =
    getToolsAsync(readonlyContext).await()

  final override suspend fun processLlmRequest(
    toolContext: ToolContext,
    llmRequest: LlmRequest,
  ): LlmRequest = processLlmRequestAsync(toolContext, llmRequest).await()

  /** Returns the tools visible under [readonlyContext] (null means "no filtering context"). */
  @JvmSuppressWildcards
  protected abstract fun getToolsAsync(
    readonlyContext: ReadonlyContext?
  ): CompletableFuture<List<BaseTool>>

  /** Resolve to the (possibly rewritten) request; the default returns it unchanged. */
  protected open fun processLlmRequestAsync(
    toolContext: ToolContext,
    llmRequest: LlmRequest,
  ): CompletableFuture<LlmRequest> = CompletableFuture.completedFuture(llmRequest)

  override fun close() {}
}
