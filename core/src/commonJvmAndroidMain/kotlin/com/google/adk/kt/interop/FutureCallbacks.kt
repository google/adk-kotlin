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

import com.google.adk.kt.agents.CallbackContext
import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.callbacks.AfterAgentCallback
import com.google.adk.kt.callbacks.AfterModelCallback
import com.google.adk.kt.callbacks.AfterToolCallback
import com.google.adk.kt.callbacks.BeforeAgentCallback
import com.google.adk.kt.callbacks.BeforeModelCallback
import com.google.adk.kt.callbacks.BeforeToolCallback
import com.google.adk.kt.callbacks.CallbackChoice
import com.google.adk.kt.callbacks.OnModelErrorCallback
import com.google.adk.kt.callbacks.OnToolErrorCallback
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.Content
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.future.await

/**
 * Java-friendly adapters for the engine's callback interfaces.
 *
 * Every engine callback is an `interface` with a single `suspend fun call(...)`, which no Java
 * lambda or class can implement. Each factory pairs an engine callback with a [CompletableFuture]
 * -returning functional interface and awaits it -- the same async idiom as the `BaseFuture*` SPI
 * bases, with no blocking dispatcher. The sealed [CallbackChoice] return is built and read via
 * [Choices].
 */
@AdkJavaInteropApi
object FutureCallbacks {

  /** Runs before an agent; return [Choices.proceed] to continue or [Choices.breakWith] to skip. */
  @JvmStatic
  fun beforeAgent(callback: FutureBeforeAgentCallback): BeforeAgentCallback =
    object : BeforeAgentCallback {
      override suspend fun call(context: CallbackContext): CallbackChoice<EventActions, Content> =
        callback.call(context).await()
    }

  /** Runs after an agent. */
  @JvmStatic
  fun afterAgent(callback: FutureAfterAgentCallback): AfterAgentCallback =
    object : AfterAgentCallback {
      override suspend fun call(context: CallbackContext): CallbackChoice<Unit, Content> =
        callback.call(context).await()
    }

  /** Runs before each model call; may rewrite the request or short-circuit with a response. */
  @JvmStatic
  fun beforeModel(callback: FutureBeforeModelCallback): BeforeModelCallback =
    object : BeforeModelCallback {
      override suspend fun call(
        context: CallbackContext,
        request: LlmRequest,
      ): CallbackChoice<LlmRequest, LlmResponse> = callback.call(context, request).await()
    }

  /** Runs after each model call; may rewrite the response. */
  @JvmStatic
  fun afterModel(callback: FutureAfterModelCallback): AfterModelCallback =
    object : AfterModelCallback {
      override suspend fun call(context: CallbackContext, response: LlmResponse): LlmResponse =
        callback.call(context, response).await()
    }

  /** Runs before each tool call; may rewrite the arguments or short-circuit with a result. */
  @JvmStatic
  fun beforeTool(callback: FutureBeforeToolCallback): BeforeToolCallback =
    object : BeforeToolCallback {
      override suspend fun call(
        context: ToolContext,
        tool: BaseTool,
        args: Map<String, Any?>,
      ): CallbackChoice<Map<String, Any?>, Map<String, Any?>> =
        callback.call(context, tool, args).await()
    }

  /** Runs after each tool call; may rewrite the result. */
  @JvmStatic
  fun afterTool(callback: FutureAfterToolCallback): AfterToolCallback =
    object : AfterToolCallback {
      override suspend fun call(
        context: ToolContext,
        tool: BaseTool,
        args: Map<String, Any?>,
        result: Map<String, Any?>,
      ): Map<String, Any?> = callback.call(context, tool, args, result).await()
    }

  /** Runs when a model call throws; may swallow the error by returning a response. */
  @JvmStatic
  fun onModelError(callback: FutureOnModelErrorCallback): OnModelErrorCallback =
    object : OnModelErrorCallback {
      override suspend fun call(
        context: CallbackContext,
        request: LlmRequest,
        error: Throwable,
      ): CallbackChoice<Unit, LlmResponse> = callback.call(context, request, error).await()
    }

  /** Runs when a tool throws; may swallow the error by returning a result. */
  @JvmStatic
  fun onToolError(callback: FutureOnToolErrorCallback): OnToolErrorCallback =
    object : OnToolErrorCallback {
      override suspend fun call(
        context: ToolContext,
        tool: BaseTool,
        args: Map<String, Any?>,
        error: Throwable,
      ): CallbackChoice<Unit, Map<String, Any?>> = callback.call(context, tool, args, error).await()
    }
}

/**
 * Java factories for the sealed [CallbackChoice].
 *
 * `Continue` carries the (possibly rewritten) value forward; `Break` stops and uses its value as
 * the result.
 */
@AdkJavaInteropApi
object Choices {

  /** Continue, carrying [value] forward. */
  @JvmStatic fun <C, B> proceed(value: C): CallbackChoice<C, B> = CallbackChoice.Continue(value)

  /** Continue with no value; the `Unit`-shaped variant used by the `after*` callbacks. */
  @JvmStatic fun <B> proceed(): CallbackChoice<Unit, B> = CallbackChoice.Continue(Unit)

  /** Stop and use [value] as the result. */
  @JvmStatic fun <C, B> breakWith(value: B): CallbackChoice<C, B> = CallbackChoice.Break(value)
}

// ------------------------------------------------------------------------------------------------
// Java functional interfaces. Plain, CompletableFuture-returning, lambda-compatible.
// ------------------------------------------------------------------------------------------------

@AdkJavaInteropApi
@JvmSuppressWildcards
fun interface FutureBeforeAgentCallback {
  fun call(context: CallbackContext): CompletableFuture<CallbackChoice<EventActions, Content>>
}

@AdkJavaInteropApi
@JvmSuppressWildcards
fun interface FutureAfterAgentCallback {
  fun call(context: CallbackContext): CompletableFuture<CallbackChoice<Unit, Content>>
}

@AdkJavaInteropApi
@JvmSuppressWildcards
fun interface FutureBeforeModelCallback {
  fun call(
    context: CallbackContext,
    request: LlmRequest,
  ): CompletableFuture<CallbackChoice<LlmRequest, LlmResponse>>
}

@AdkJavaInteropApi
@JvmSuppressWildcards
fun interface FutureAfterModelCallback {
  fun call(context: CallbackContext, response: LlmResponse): CompletableFuture<LlmResponse>
}

@AdkJavaInteropApi
@JvmSuppressWildcards
fun interface FutureBeforeToolCallback {
  fun call(
    context: ToolContext,
    tool: BaseTool,
    args: Map<String, Any?>,
  ): CompletableFuture<CallbackChoice<Map<String, Any?>, Map<String, Any?>>>
}

@AdkJavaInteropApi
@JvmSuppressWildcards
fun interface FutureAfterToolCallback {
  fun call(
    context: ToolContext,
    tool: BaseTool,
    args: Map<String, Any?>,
    result: Map<String, Any?>,
  ): CompletableFuture<Map<String, Any?>>
}

@AdkJavaInteropApi
@JvmSuppressWildcards
fun interface FutureOnModelErrorCallback {
  fun call(
    context: CallbackContext,
    request: LlmRequest,
    error: Throwable,
  ): CompletableFuture<CallbackChoice<Unit, LlmResponse>>
}

@AdkJavaInteropApi
@JvmSuppressWildcards
fun interface FutureOnToolErrorCallback {
  fun call(
    context: ToolContext,
    tool: BaseTool,
    args: Map<String, Any?>,
    error: Throwable,
  ): CompletableFuture<CallbackChoice<Unit, Map<String, Any?>>>
}
