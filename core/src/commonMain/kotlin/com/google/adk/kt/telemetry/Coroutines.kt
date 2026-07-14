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

package com.google.adk.kt.telemetry

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * Executes [block] within a new [Span], installing the span on the coroutine context via
 * `withContext` so nested OpenTelemetry instrumentation sees it as the active parent.
 *
 * **Do not call from inside `flow { }`.** The internal `withContext` switch violates Kotlin's flow
 * context-preservation invariant if [block] calls `emit`. For flow tracing use [Flow.trace] or
 * [tracedFlow]; for non-suspending code use [inSpan].
 */
internal suspend fun <T> withSpan(
  name: String,
  builder: SpanBuilder.() -> Unit = {},
  block: suspend CoroutineScope.(Span) -> T,
): T {
  val spanBuilder = Telemetry.tracer.spanBuilder(name)
  spanBuilder.apply(builder)
  val span = spanBuilder.startSpan()

  return try {
    withContext(Telemetry.tracer.contextWithSpan(span).asContextElement()) { block(span) }
  } catch (e: Throwable) {
    if (e !is CancellationException) {
      span.recordException(e)
    }
    throw e
  } finally {
    span.end()
  }
}

/**
 * Executes the given [block] within a new [Span] synchronously.
 *
 * This function handles span lifecycle for non-suspending code: starting the span, recording any
 * exceptions, and ensuring the span is ended.
 *
 * @param name the name of the new span.
 * @param builder a lambda to configure the [SpanBuilder] before starting the span.
 * @param block the block of code to execute within the span.
 */
internal inline fun <T> inSpan(
  name: String,
  builder: SpanBuilder.() -> Unit = {},
  block: (Span) -> T,
): T {
  val spanBuilder = Telemetry.tracer.spanBuilder(name)
  spanBuilder.apply(builder)
  val span = spanBuilder.startSpan()
  val context = Telemetry.tracer.contextWithSpan(span)
  val scope = context.attach()

  return try {
    block(span)
  } catch (e: Throwable) {
    if (e !is CancellationException) {
      span.recordException(e)
    }
    throw e
  } finally {
    context.detach(scope)
    span.end()
  }
}

/**
 * An operator that creates a new [Span] around the collection of the given [Flow].
 *
 * This function handles the span lifecycle for a flow: starting the span when collection begins,
 * applying it to the contextual execution of the upstream flow, recording any exceptions, and
 * ensuring the span is ended when collection finishes.
 *
 * @param name the name of the new span.
 * @param parent optional explicit parent context. When provided, the span is parented to it (e.g.
 *   the caller's ambient span captured at an entry point) so the resulting trace joins the caller's
 *   trace. When null, the span inherits the ambient context active at collection time.
 * @param builder a lambda to configure the [SpanBuilder] before starting the span.
 */
internal fun <T> Flow<T>.trace(
  name: String,
  parent: TelemetryContext? = null,
  builder: SpanBuilder.() -> Unit = {},
): Flow<T> = flow {
  val spanBuilder = Telemetry.tracer.spanBuilder(name)
  if (parent != null) spanBuilder.setParent(parent)
  spanBuilder.apply(builder)
  val span = spanBuilder.startSpan()

  try {
    // We use flowOn to inject the span into the upstream coroutine context safely,
    // honoring Kotlin's Flow context preservation invariants without affecting the
    // downstream emission context.
    emitAll(this@trace.flowOn(Telemetry.tracer.contextWithSpan(span).asContextElement()))
  } catch (e: Throwable) {
    if (e !is CancellationException) {
      span.recordException(e)
    }
    throw e
  } finally {
    span.end()
  }
}

/**
 * Builds a [Flow] whose emissions are traced by a new [Span], with synchronous backpressure: every
 * `emit` inside [block] suspends until the downstream collector has fully processed the value.
 *
 * [block] receives the [Span] (for attributes/events) and a [TelemetryContextElement] that can be
 * applied via `.flowOn(spanContext)` to upstream sub-flows that need the span context. Do not wrap
 * `emit` calls in `withContext(spanContext)` — that would re-introduce the flow-invariant problem
 * [withSpan] has.
 */
internal fun <T> tracedFlow(
  name: String,
  builder: SpanBuilder.() -> Unit = {},
  block: suspend FlowCollector<T>.(span: Span, spanContext: TelemetryContextElement) -> Unit,
): Flow<T> = flow {
  val spanBuilder = Telemetry.tracer.spanBuilder(name)
  spanBuilder.apply(builder)
  val span = spanBuilder.startSpan()
  val spanContext = Telemetry.tracer.contextWithSpan(span).asContextElement()

  try {
    block(span, spanContext)
  } catch (e: Throwable) {
    if (e !is CancellationException) {
      span.recordException(e)
    }
    throw e
  } finally {
    span.end()
  }
}
