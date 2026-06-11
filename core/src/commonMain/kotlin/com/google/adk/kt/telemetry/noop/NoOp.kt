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

package com.google.adk.kt.telemetry.noop

import com.google.adk.kt.telemetry.Scope
import com.google.adk.kt.telemetry.Span
import com.google.adk.kt.telemetry.SpanBuilder
import com.google.adk.kt.telemetry.TelemetryContext
import com.google.adk.kt.telemetry.TelemetryContextElement
import com.google.adk.kt.telemetry.Tracer

/** A no-operation implementation of the [Span] interface. */
internal object NoOpSpan : Span {
  override operator fun set(key: String, value: String): Span = this

  override operator fun set(key: String, value: Long): Span = this

  override operator fun set(key: String, value: Double): Span = this

  override operator fun set(key: String, value: Boolean): Span = this

  override operator fun set(key: String, value: List<String>): Span = this

  override fun addEvent(name: String): Span = this

  override fun recordException(exception: Throwable): Span = this

  override fun end() {}
}

/** A no-operation implementation of the [Scope] interface. */
internal object NoOpScope : Scope {
  override fun close() {}
}

/** A no-operation implementation of the [SpanBuilder] interface. */
internal object NoOpSpanBuilder : SpanBuilder {
  override operator fun set(key: String, value: String): SpanBuilder = this

  override operator fun set(key: String, value: Long): SpanBuilder = this

  override operator fun set(key: String, value: Double): SpanBuilder = this

  override operator fun set(key: String, value: Boolean): SpanBuilder = this

  override fun setParent(context: TelemetryContext): SpanBuilder = this

  override fun startSpan(): Span = NoOpSpan
}

/** A no-operation implementation of the [TelemetryContextElement] interface. */
internal object NoOpTelemetryContextElement : TelemetryContextElement {
  override val key: kotlin.coroutines.CoroutineContext.Key<*> = TelemetryContextElement.Key
  override val context: TelemetryContext = NoOpTelemetryContext
}

/** A no-operation implementation of the [TelemetryContext] interface. */
internal object NoOpTelemetryContext : TelemetryContext {
  override fun asContextElement(): TelemetryContextElement = NoOpTelemetryContextElement

  override fun attach(): Scope = NoOpScope

  override fun detach(scopeToken: Scope) {}
}

/** A no-operation implementation of the [Tracer] interface. */
internal object NoOpTracer : Tracer {
  override fun spanBuilder(spanName: String): SpanBuilder = NoOpSpanBuilder

  override suspend fun currentContext(): TelemetryContext = NoOpTelemetryContext

  override fun contextWithSpan(span: Span): TelemetryContext = NoOpTelemetryContext
}
