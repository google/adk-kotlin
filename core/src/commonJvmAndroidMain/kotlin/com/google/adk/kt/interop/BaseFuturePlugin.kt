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
import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.callbacks.CallbackChoice
import com.google.adk.kt.events.Event
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.plugins.Plugin
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.Content
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.future.await

/**
 * Java-friendly base for implementing a [Plugin] -- the cross-cutting interception point. The
 * engine's `Plugin` has 12 `suspend` callbacks plus `close()`, every one with a default body. This
 * base final-overrides all 12 and re-exposes each as a [CompletableFuture]-returning hook whose
 * *result* is the outcome; each default hook completes with the value that leaves execution
 * unchanged. Return the future promptly and do any blocking work inside it, not before returning
 * it.
 *
 * Each hook mirrors its engine callback's shape: those whose callback returns a [CallbackChoice]
 * resolve to one too (build it with [Choices] -- [Choices.proceed] to continue, [Choices.breakWith]
 * to short-circuit), and the rest resolve to a replacement value. To leave execution unchanged,
 * resolve to the original value, exactly as the default hooks do.
 */
@AdkJavaInteropApi
abstract class BaseFuturePlugin(private val pluginName: String) : Plugin {

  override val name: String
    get() = pluginName

  // ---- Runner-level ----------------------------------------------------------------------------

  final override suspend fun onUserMessage(
    invocationContext: InvocationContext,
    userMessage: Content,
  ): Content = onUserMessageAsync(invocationContext, userMessage).await()

  final override suspend fun beforeRun(
    invocationContext: InvocationContext
  ): CallbackChoice<Unit, Content> = beforeRunAsync(invocationContext).await()

  final override suspend fun onEvent(invocationContext: InvocationContext, event: Event): Event =
    onEventAsync(invocationContext, event).await()

  final override suspend fun afterRun(invocationContext: InvocationContext) {
    afterRunAsync(invocationContext).await()
  }

  // ---- Agent-level -----------------------------------------------------------------------------

  final override suspend fun beforeAgent(
    context: CallbackContext
  ): CallbackChoice<EventActions, Content> = beforeAgentAsync(context).await()

  final override suspend fun afterAgent(context: CallbackContext): CallbackChoice<Unit, Content> =
    afterAgentAsync(context).await()

  // ---- Model-level -----------------------------------------------------------------------------

  final override suspend fun beforeModel(
    context: CallbackContext,
    request: LlmRequest,
  ): CallbackChoice<LlmRequest, LlmResponse> = beforeModelAsync(context, request).await()

  final override suspend fun afterModel(
    context: CallbackContext,
    response: LlmResponse,
  ): LlmResponse = afterModelAsync(context, response).await()

  final override suspend fun onModelError(
    context: CallbackContext,
    request: LlmRequest,
    error: Throwable,
  ): CallbackChoice<Unit, LlmResponse> = onModelErrorAsync(context, request, error).await()

  // ---- Tool-level ------------------------------------------------------------------------------

  final override suspend fun beforeTool(
    context: ToolContext,
    tool: BaseTool,
    args: Map<String, Any?>,
  ): CallbackChoice<Map<String, Any?>, Map<String, Any?>> =
    beforeToolAsync(context, tool, args).await()

  final override suspend fun afterTool(
    context: ToolContext,
    tool: BaseTool,
    args: Map<String, Any?>,
    result: Map<String, Any?>,
  ): Map<String, Any?> = afterToolAsync(context, tool, args, result).await()

  final override suspend fun onToolError(
    context: ToolContext,
    tool: BaseTool,
    args: Map<String, Any?>,
    error: Throwable,
  ): CallbackChoice<Unit, Map<String, Any?>> = onToolErrorAsync(context, tool, args, error).await()

  // ---- Java hooks. Override the ones you need; every default leaves execution unchanged. --------

  /** Resolve to a replacement user message; the default keeps the original. */
  protected open fun onUserMessageAsync(
    invocationContext: InvocationContext,
    userMessage: Content,
  ): CompletableFuture<Content> = CompletableFuture.completedFuture(userMessage)

  /**
   * Resolve to [Choices.breakWith] to skip the whole invocation with content, or [Choices.proceed]
   * to continue; the default continues.
   */
  protected open fun beforeRunAsync(
    invocationContext: InvocationContext
  ): CompletableFuture<CallbackChoice<Unit, Content>> =
    CompletableFuture.completedFuture(Choices.proceed())

  /** Resolve to a replacement event before it is persisted and yielded; the default keeps it. */
  protected open fun onEventAsync(
    invocationContext: InvocationContext,
    event: Event,
  ): CompletableFuture<Event> = CompletableFuture.completedFuture(event)

  /** Observe the end of a run. */
  protected open fun afterRunAsync(invocationContext: InvocationContext): CompletableFuture<Void?> =
    CompletableFuture.completedFuture(null)

  /**
   * Resolve to [Choices.proceed] to rewrite the event actions and continue, or [Choices.breakWith]
   * to skip the agent with replacement content; the default continues.
   */
  protected open fun beforeAgentAsync(
    context: CallbackContext
  ): CompletableFuture<CallbackChoice<EventActions, Content>> =
    CompletableFuture.completedFuture(Choices.proceed(EventActions()))

  /**
   * Resolve to [Choices.breakWith] to replace the agent output, or [Choices.proceed] to keep it;
   * the default keeps it.
   */
  protected open fun afterAgentAsync(
    context: CallbackContext
  ): CompletableFuture<CallbackChoice<Unit, Content>> =
    CompletableFuture.completedFuture(Choices.proceed())

  /**
   * Resolve to [Choices.proceed] to rewrite the request and continue (e.g. add a system instruction
   * or tools), or [Choices.breakWith] to skip the model call with a response; the default
   * continues.
   */
  protected open fun beforeModelAsync(
    context: CallbackContext,
    request: LlmRequest,
  ): CompletableFuture<CallbackChoice<LlmRequest, LlmResponse>> =
    CompletableFuture.completedFuture(Choices.proceed(request))

  /** Resolve to a replacement model response; the default keeps it. */
  protected open fun afterModelAsync(
    context: CallbackContext,
    response: LlmResponse,
  ): CompletableFuture<LlmResponse> = CompletableFuture.completedFuture(response)

  /**
   * Resolve to [Choices.breakWith] to swallow a model error with a response, or [Choices.proceed]
   * to let it propagate; the default lets it propagate.
   */
  protected open fun onModelErrorAsync(
    context: CallbackContext,
    request: LlmRequest,
    error: Throwable,
  ): CompletableFuture<CallbackChoice<Unit, LlmResponse>> =
    CompletableFuture.completedFuture(Choices.proceed())

  /**
   * Resolve to [Choices.proceed] to rewrite the arguments and continue, or [Choices.breakWith] to
   * skip the tool with a result; the default continues.
   */
  @JvmSuppressWildcards
  protected open fun beforeToolAsync(
    context: ToolContext,
    tool: BaseTool,
    args: Map<String, Any?>,
  ): CompletableFuture<CallbackChoice<Map<String, Any?>, Map<String, Any?>>> =
    CompletableFuture.completedFuture(Choices.proceed(args))

  /** Resolve to a replacement tool result; the default keeps it. */
  @JvmSuppressWildcards
  protected open fun afterToolAsync(
    context: ToolContext,
    tool: BaseTool,
    args: Map<String, Any?>,
    result: Map<String, Any?>,
  ): CompletableFuture<Map<String, Any?>> = CompletableFuture.completedFuture(result)

  /**
   * Resolve to [Choices.breakWith] to swallow a tool error with a result, or [Choices.proceed] to
   * let it propagate; the default lets it propagate.
   */
  @JvmSuppressWildcards
  protected open fun onToolErrorAsync(
    context: ToolContext,
    tool: BaseTool,
    args: Map<String, Any?>,
    error: Throwable,
  ): CompletableFuture<CallbackChoice<Unit, Map<String, Any?>>> =
    CompletableFuture.completedFuture(Choices.proceed())

  override fun close() {}
}
