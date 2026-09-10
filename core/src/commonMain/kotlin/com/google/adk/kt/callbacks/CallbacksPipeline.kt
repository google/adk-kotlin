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

package com.google.adk.kt.callbacks

import com.google.adk.kt.agents.CallbackContext
import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.events.Event
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.logging.LoggerFactory
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.Content
import kotlin.coroutines.cancellation.CancellationException

/** Represents a step in a callback pipeline execution. */
internal sealed class PipelineStep<out S, out R> {
  /** Continue execution with the next state. */
  data class Continue<S>(val state: S) : PipelineStep<S, Nothing>()

  /** Break/Short-circuit execution and return the result directly. */
  data class ShortCircuit<R>(val result: R) : PipelineStep<Nothing, R>()
}

private class CallbacksPipelineMarker

private val logger = LoggerFactory.getLogger(CallbacksPipelineMarker::class)

/**
 * Executes a sequence of callback operations over an iterable, handling Continuation and
 * Short-Circuit control flow.
 *
 * @param callbacks The callbacks to iterate over.
 * @param initialState The initial state data to feed into the pipeline.
 * @param onComplete Function mapping the final continuation state to the expected pipeline result.
 * @param executor Lambda executing a specific callback and returning an appropriate [PipelineStep].
 */
private suspend fun <T : Callback, S, R> runCallbacksPipeline(
  callbacks: Iterable<T>,
  initialState: S,
  onComplete: (S) -> R,
  executor: suspend (T, S) -> PipelineStep<S, R>,
): R {
  var currentState = initialState
  for (callback in callbacks) {
    val name = callback.name

    try {
      when (val step = executor(callback, currentState)) {
        is PipelineStep.Continue -> currentState = step.state
        is PipelineStep.ShortCircuit -> {
          logger.debug { "Callback '$name' returned a value, exiting early." }
          return step.result
        }
      }
    } catch (e: Exception) {
      logger.error(e) { "[$name] Error during callback" }
      throw e
    }
  }
  return onComplete(currentState)
}

/**
 * Executes the [BeforeAgentCallback] pipeline over the provided callbacks.
 *
 * If any callback returns [CallbackChoice.Break], the pipeline immediately terminates and
 * short-circuits execution, returning the provided content directly to bypass the agent's normal
 * logic. If a callback returns [CallbackChoice.Continue], the pipeline advances to the next
 * callback, merging any potentially modified event actions.
 */
internal suspend fun runBeforeAgentCallbacksPipeline(
  callbacks: Iterable<BeforeAgentCallback>,
  context: CallbackContext,
): CallbackChoice<EventActions, Content> =
  runCallbacksPipeline(
    callbacks = callbacks,
    initialState = EventActions(),
    onComplete = { CallbackChoice.Continue(it) },
  ) { callback, _ ->
    when (val res = callback.call(context)) {
      is CallbackChoice.Break -> PipelineStep.ShortCircuit(res)
      is CallbackChoice.Continue -> {
        context.mergeEventActions(res.value)
        PipelineStep.Continue(res.value)
      }
    }
  }

/**
 * Executes the [AfterAgentCallback] pipeline over the provided callbacks.
 *
 * If any callback returns [CallbackChoice.Break], the pipeline halts and overrides the agent's
 * original response with the returned content. If a callback returns [CallbackChoice.Continue], it
 * permits normal execution to proceed unaffected.
 */
internal suspend fun runAfterAgentCallbacksPipeline(
  callbacks: Iterable<AfterAgentCallback>,
  context: CallbackContext,
): CallbackChoice<Unit, Content> =
  runCallbacksPipeline(
    callbacks = callbacks,
    initialState = Unit,
    onComplete = { CallbackChoice.Continue(Unit) },
  ) { callback, _ ->
    when (val res = callback.call(context)) {
      is CallbackChoice.Break -> PipelineStep.ShortCircuit(res)
      is CallbackChoice.Continue -> PipelineStep.Continue(Unit)
    }
  }

/**
 * Executes the [BeforeModelCallback] pipeline over the provided callbacks.
 *
 * If any callback returns [CallbackChoice.Break], the pipeline immediately suppresses the model
 * call and yields the provided [LlmResponse] directly. If a callback returns
 * [CallbackChoice.Continue], it forwards the potentially modified [LlmRequest] to the succeeding
 * stages.
 */
internal suspend fun runBeforeModelCallbacksPipeline(
  callbacks: Iterable<BeforeModelCallback>,
  context: CallbackContext,
  request: LlmRequest,
): CallbackChoice<LlmRequest, LlmResponse> =
  runCallbacksPipeline(
    callbacks = callbacks,
    initialState = request,
    onComplete = { CallbackChoice.Continue(it) },
  ) { callback, currentState ->
    when (val res = callback.call(context, currentState)) {
      is CallbackChoice.Break -> PipelineStep.ShortCircuit(res)
      is CallbackChoice.Continue -> PipelineStep.Continue(res.value)
    }
  }

/**
 * Executes the [AfterModelCallback] pipeline over the provided callbacks.
 *
 * If any callback returns [CallbackChoice.Continue], the potentially modified [LlmResponse] is
 * propagated down the chain. A break is not permitted during this lifecycle stage.
 */
internal suspend fun runAfterModelCallbacksPipeline(
  callbacks: Iterable<AfterModelCallback>,
  context: CallbackContext,
  response: LlmResponse,
): LlmResponse =
  runCallbacksPipeline(callbacks = callbacks, initialState = response, onComplete = { it }) {
    callback,
    currentState ->
    PipelineStep.Continue(callback.call(context, currentState))
  }

/**
 * Executes the [OnModelErrorCallback] pipeline over the provided callbacks when a model exception
 * occurs.
 *
 * If any callback returns [CallbackChoice.Break], the pipeline intercepts the error and resolves
 * execution with the provided fallback [LlmResponse]. If a callback returns
 * [CallbackChoice.Continue], the pipeline continues checking subsequent callbacks before optionally
 * re-throwing the error.
 */
internal suspend fun runOnModelErrorCallbacksPipeline(
  callbacks: Iterable<OnModelErrorCallback>,
  context: CallbackContext,
  request: LlmRequest,
  error: Throwable,
): CallbackChoice<Unit, LlmResponse> =
  runCallbacksPipeline(
    callbacks = callbacks,
    initialState = Unit,
    onComplete = { CallbackChoice.Continue(Unit) },
  ) { callback, _ ->
    when (val res = callback.call(context, request, error)) {
      is CallbackChoice.Break -> PipelineStep.ShortCircuit(res)
      is CallbackChoice.Continue -> PipelineStep.Continue(Unit)
    }
  }

/**
 * Executes the [BeforeRunCallback] pipeline over the provided callbacks.
 *
 * If any callback returns [CallbackChoice.Break], the initial invocation aborts and the returned
 * content is directly handled. If a callback returns [CallbackChoice.Continue], the runner
 * commences executing the primary root agent loop as usual.
 */
internal suspend fun runBeforeRunCallbacksPipeline(
  callbacks: Iterable<BeforeRunCallback>,
  context: InvocationContext,
): CallbackChoice<Unit, Content> =
  runCallbacksPipeline(
    callbacks = callbacks,
    initialState = Unit,
    onComplete = { CallbackChoice.Continue(Unit) },
  ) { callback, _ ->
    when (val res = callback.call(context)) {
      is CallbackChoice.Break -> PipelineStep.ShortCircuit(res)
      is CallbackChoice.Continue -> PipelineStep.Continue(Unit)
    }
  }

/**
 * Executes the [AfterRunCallback] pipeline over the provided callbacks.
 *
 * This pipeline executes as a pure iteration for post-cleanup and logging operations. Breaks are
 * not supported.
 */
internal suspend fun runAfterRunCallbacksPipeline(
  callbacks: Iterable<AfterRunCallback>,
  context: InvocationContext,
) =
  runCallbacksPipeline(callbacks = callbacks, initialState = Unit, onComplete = { Unit }) {
    callback,
    _ ->
    callback.call(context)
    PipelineStep.Continue(Unit)
  }

/**
 * Executes the [OnRunErrorCallback] pipeline over the provided callbacks.
 *
 * Notification-only and best-effort: every callback is invoked in order regardless of return value,
 * and a callback that throws is logged and skipped so the remaining callbacks still run. The error
 * being reported is never suppressed here; the caller re-raises it after this pipeline returns.
 */
internal suspend fun runOnRunErrorCallbacksPipeline(
  callbacks: Iterable<OnRunErrorCallback>,
  context: InvocationContext,
  error: Throwable,
) {
  for (callback in callbacks) {
    try {
      callback.call(context, error)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      logger.error(e) { "[${callback.name}] Error during 'onRunError' callback" }
    }
  }
}

/**
 * Executes the [OnUserMessageCallback] pipeline over the provided callbacks.
 *
 * Callbacks are evaluated sequentially to optionally update or modify the user message content.
 * Each iteration receives the output of the prior phase.
 */
internal suspend fun runOnUserMessageCallbacksPipeline(
  callbacks: Iterable<OnUserMessageCallback>,
  context: InvocationContext,
  userMessage: Content,
): Content =
  runCallbacksPipeline(callbacks = callbacks, initialState = userMessage, onComplete = { it }) {
    callback,
    currentState ->
    PipelineStep.Continue(callback.call(context, currentState))
  }

/**
 * Executes the [OnEventCallback] pipeline over the provided callbacks.
 *
 * Allows plugins to dynamically inspect and amend internal events before they are dispatched down
 * the session.
 */
internal suspend fun runOnEventCallbacksPipeline(
  callbacks: Iterable<OnEventCallback>,
  context: InvocationContext,
  event: Event,
): Event =
  runCallbacksPipeline(callbacks = callbacks, initialState = event, onComplete = { it }) {
    callback,
    currentState ->
    PipelineStep.Continue(callback.call(context, currentState))
  }

/** Executes the [BeforeToolCallback] pipeline. */
internal suspend fun runBeforeToolCallbacksPipeline(
  callbacks: Iterable<BeforeToolCallback>,
  context: ToolContext,
  tool: BaseTool,
  args: Map<String, Any?>,
): CallbackChoice<Map<String, Any?>, Map<String, Any?>> =
  runCallbacksPipeline(
    callbacks = callbacks,
    initialState = args,
    onComplete = { CallbackChoice.Continue(it) },
  ) { callback, currentState ->
    when (val res = callback.call(context, tool, currentState)) {
      is CallbackChoice.Break -> PipelineStep.ShortCircuit(res)
      is CallbackChoice.Continue -> PipelineStep.Continue(res.value)
    }
  }

/** Executes the [AfterToolCallback] pipeline. */
internal suspend fun runAfterToolCallbacksPipeline(
  callbacks: Iterable<AfterToolCallback>,
  context: ToolContext,
  tool: BaseTool,
  args: Map<String, Any?>,
  result: Map<String, Any?>,
): Map<String, Any?> =
  runCallbacksPipeline(callbacks = callbacks, initialState = result, onComplete = { it }) {
    callback,
    currentState ->
    PipelineStep.Continue(callback.call(context, tool, args, currentState))
  }

/** Executes the [OnToolErrorCallback] pipeline. */
internal suspend fun runOnToolErrorCallbacksPipeline(
  callbacks: Iterable<OnToolErrorCallback>,
  context: ToolContext,
  tool: BaseTool,
  args: Map<String, Any?>,
  error: Throwable,
): CallbackChoice<Unit, Map<String, Any?>> =
  runCallbacksPipeline(
    callbacks = callbacks,
    initialState = Unit,
    onComplete = { CallbackChoice.Continue(Unit) },
  ) { callback, _ ->
    when (val res = callback.call(context, tool, args, error)) {
      is CallbackChoice.Break -> PipelineStep.ShortCircuit(res)
      is CallbackChoice.Continue -> PipelineStep.Continue(Unit)
    }
  }
