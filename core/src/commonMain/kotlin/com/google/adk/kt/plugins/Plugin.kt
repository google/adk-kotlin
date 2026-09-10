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

package com.google.adk.kt.plugins

import com.google.adk.kt.agents.CallbackContext
import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.callbacks.CallbackChoice
import com.google.adk.kt.events.Event
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.Content

/**
 * Interface for creating plugins.
 *
 * Plugins provide a structured way to intercept and modify agent, tool, and LLM behaviors at
 * critical execution points in a callback manner.
 */
interface Plugin : AutoCloseable {
  /** The unique name of the plugin. */
  val name: String

  // Runner-level callbacks

  /**
   * Callback executed when a user message is received before an invocation starts.
   *
   * Helps log and modify/replace the user message before the runner starts the invocation.
   *
   * @param invocationContext The context for the entire invocation.
   * @param userMessage The message content input by the user.
   * @return The potentially modified [Content] to propagate down the chain.
   */
  suspend fun onUserMessage(invocationContext: InvocationContext, userMessage: Content): Content =
    userMessage

  /**
   * Callback executed before the ADK runner starts the main execution loop.
   *
   * This is the first callback called in the lifecycle, ideal for global setup or initialization
   * tasks. It provides an opportunity to inspect or log the invocation setup, or to short-circuit
   * the run before it begins.
   *
   * @param invocationContext The context for the entire invocation, containing session information,
   *   the root agent, etc.
   * @return A [CallbackChoice] where returning [CallbackChoice.Break] with custom [Content] halts
   *   execution of the runner and resolves the invocation with that content directly. Returning
   *   [CallbackChoice.Continue] with [Unit] allows execution to proceed normally.
   */
  suspend fun beforeRun(invocationContext: InvocationContext): CallbackChoice<Unit, Content> =
    CallbackChoice.Continue(Unit)

  /**
   * Callback executed when an event is yielded by an agent during execution.
   *
   * This is the ideal place to modify the event before it is persisted to the session service and
   * yielded to the caller. Useful for logging events or transforming them before they reach the
   * final consumer.
   *
   * @param invocationContext The context for the entire invocation.
   * @param event The event raised by the runner.
   * @return The potentially modified [Event] to propagate downstream.
   */
  suspend fun onEvent(invocationContext: InvocationContext, event: Event): Event = event

  /**
   * Callback executed after the ADK runner completes its execution.
   *
   * Ideal for final logging, telemetry reporting, or cleanup after a successful or failed run.
   *
   * @param invocationContext The context for the entire invocation.
   */
  suspend fun afterRun(invocationContext: InvocationContext) {}

  /**
   * Callback executed when an unhandled error escapes the ADK runner run.
   *
   * Notification-only: the error is always re-raised to the caller after every plugin has been
   * notified, so this hook is for logging, telemetry, or cleanup, not recovery. It must not attempt
   * to suppress the error, and a failure thrown from it is logged and does not stop the remaining
   * plugins from being notified.
   *
   * @param invocationContext The context for the entire invocation.
   * @param error The error that escaped the run.
   */
  suspend fun onRunError(invocationContext: InvocationContext, error: Throwable) {}

  // Agent-level callbacks

  /**
   * Callback executed before a specific agent starts processing.
   *
   * This callback can be used for logging, setup, or short-circuiting the agent's execution.
   *
   * @param context The context of the current agent call.
   * @return A [CallbackChoice] where returning [CallbackChoice.Break] with custom [Content]
   *   bypasses the agent's regular execution entirely and directly yields the provided content.
   *   Returning [CallbackChoice.Continue] with [EventActions] allows normal execution to proceed,
   *   merging any actions into the running context.
   */
  suspend fun beforeAgent(context: CallbackContext): CallbackChoice<EventActions, Content> =
    CallbackChoice.Continue(EventActions())

  /**
   * Callback executed after a specific agent finishes its processing.
   *
   * Allows plugins/callbacks to inspect invocation state or override the agent's final response.
   *
   * @param context The context of the current agent call.
   * @return A [CallbackChoice] where returning [CallbackChoice.Break] with a custom [Content]
   *   overrides the agent's original response and appends it to the event history, mimicking Python
   *   ADK's behavior when a truthy content is returned. Returning [CallbackChoice.Continue] with
   *   [Unit] allows execution to proceed utilizing the original response naturally.
   */
  suspend fun afterAgent(context: CallbackContext): CallbackChoice<Unit, Content> =
    CallbackChoice.Continue(Unit)

  // Model-level callbacks

  /**
   * Callback executed before an LLM request is sent.
   *
   * Provides an opportunity to inspect, log, or modify the [LlmRequest] object. It can also be used
   * to implement caching by returning a cached [LlmResponse], which skips the actual model call.
   *
   * @param context The context of the current agent call.
   * @param request The prepared request object to be sent to the model.
   * @return A [CallbackChoice] where returning [CallbackChoice.Break] with a custom [LlmResponse]
   *   triggers an early exit, returning the response immediately and bypassing the model call.
   *   Returning [CallbackChoice.Continue] with a potentially modified [LlmRequest] propagates the
   *   request to the model normally.
   */
  suspend fun beforeModel(
    context: CallbackContext,
    request: LlmRequest,
  ): CallbackChoice<LlmRequest, LlmResponse> = CallbackChoice.Continue(request)

  /**
   * Callback executed after an LLM response is received.
   *
   * This is the ideal place to log model responses, collect metrics on token usage, or perform
   * post-processing on the raw [LlmResponse].
   *
   * @param context The context of the current agent call.
   * @param response The response object received from the model.
   * @return The potentially modified [LlmResponse] to propagate down the chain.
   */
  suspend fun afterModel(context: CallbackContext, response: LlmResponse): LlmResponse = response

  /**
   * Callback executed when an error occurs during an LLM interaction.
   *
   * Provides an opportunity to handle model errors gracefully, potentially providing alternative
   * responses or recovery mechanisms.
   *
   * @param context The context of the current agent call.
   * @param request The request that was sent to the model when the error occurred.
   * @param error The exception that was raised during model execution.
   * @return A [CallbackChoice] where returning [CallbackChoice.Break] using a fallback
   *   [LlmResponse] intercepts the error and resolves execution utilizing that fallback response.
   *   Returning [CallbackChoice.Continue] with [Unit] permits the original error to be propagated.
   */
  suspend fun onModelError(
    context: CallbackContext,
    request: LlmRequest,
    error: Throwable,
  ): CallbackChoice<Unit, LlmResponse> = CallbackChoice.Continue(Unit)

  // BaseTool-level callbacks

  /**
   * Callback executed before a tool is invoked.
   *
   * Provides a way to audit, log, or modify the arguments being passed to a tool, or to
   * short-circuit the tool call.
   *
   * @param context The context of the current tool execution.
   * @param tool The tool about to be executed.
   * @param args The arguments to be passed to the tool.
   * @return A [CallbackChoice] representing the tool response/arguments. When
   *   [CallbackChoice.Break] is returned, the value will be used as the tool response and the
   *   framework will skip calling the actual tool. When [CallbackChoice.Continue] is returned, the
   *   value will be used as the arguments to be passed to the tool.
   */
  suspend fun beforeTool(
    context: ToolContext,
    tool: BaseTool,
    args: Map<String, Any?>,
  ): CallbackChoice<Map<String, Any?>, Map<String, Any?>> = CallbackChoice.Continue(args)

  /**
   * Callback executed after a tool finishes its execution.
   *
   * This callback allows for inspecting, logging, or modifying the result returned by a tool before
   * it is returned to the agent.
   *
   * @param context The context specific to the tool execution.
   * @param tool The tool instance that has just been executed.
   * @param args The original arguments that were passed to the tool.
   * @param result The dictionary / map returned by the tool invocation. Values may be `null`.
   * @return The potentially modified result map to propagate downstream.
   */
  suspend fun afterTool(
    context: ToolContext,
    tool: BaseTool,
    args: Map<String, Any?>,
    result: Map<String, Any?>,
  ): Map<String, Any?> = result

  /**
   * Callback executed when an error occurs during a tool invocation.
   *
   * Provides an opportunity to handle tool errors gracefully, potentially providing alternative
   * responses or recovery mechanisms.
   *
   * @param context The context for the current tool execution.
   * @param tool The tool instance that encountered an error.
   * @param args The arguments that were passed to the tool.
   * @param error The exception that was raised.
   * @return A [CallbackChoice] where returning [CallbackChoice.Break] with a custom map intercepts
   *   the error and resolves execution using that fallback value; its values may be `null`.
   *   Returning [CallbackChoice.Continue] with [Unit] permits the error to be propagated naturally.
   */
  suspend fun onToolError(
    context: ToolContext,
    tool: BaseTool,
    args: Map<String, Any?>,
    error: Throwable,
  ): CallbackChoice<Unit, Map<String, Any?>> = CallbackChoice.Continue(Unit)

  /**
   * Method executed when the runner is closed.
   *
   * This method is used for cleanup tasks such as closing network connections or releasing
   * resources.
   */
  override fun close() {}
}
