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

package com.google.adk.kt.testing

import com.google.adk.kt.telemetry.Scope
import com.google.adk.kt.telemetry.Span
import com.google.adk.kt.telemetry.SpanBuilder
import com.google.adk.kt.telemetry.TelemetryContext
import com.google.adk.kt.telemetry.TelemetryContextElement
import com.google.adk.kt.telemetry.Tracer

/** A dummy implementation of [Tracer] that records created spans for testing. */
class DummyTracer : Tracer {
  val recordedSpans = mutableListOf<DummySpan>()

  override fun spanBuilder(spanName: String): SpanBuilder {
    return DummySpanBuilder(spanName) { span -> recordedSpans.add(span) }
  }

  override suspend fun currentContext(): TelemetryContext {
    return DummyTelemetryContext()
  }

  override fun contextWithSpan(span: Span): TelemetryContext {
    return DummyTelemetryContext(span as? DummySpan)
  }
}

/** A dummy implementation of [SpanBuilder] that accumulates attributes. */
class DummySpanBuilder(val name: String, val onSpanEnd: (DummySpan) -> Unit) : SpanBuilder {
  val attributes = mutableMapOf<String, Any>()

  override operator fun set(key: String, value: String): SpanBuilder {
    attributes[key] = value
    return this
  }

  override operator fun set(key: String, value: Long): SpanBuilder {
    attributes[key] = value
    return this
  }

  override operator fun set(key: String, value: Double): SpanBuilder {
    attributes[key] = value
    return this
  }

  override operator fun set(key: String, value: Boolean): SpanBuilder {
    attributes[key] = value
    return this
  }

  private var parentSpan: DummySpan? = null

  override fun setParent(context: TelemetryContext): SpanBuilder {
    if (context is DummyTelemetryContext) {
      parentSpan = context.span
    }
    return this
  }

  override fun startSpan(): Span {
    return DummySpan(name, attributes, parentSpan, onSpanEnd)
  }
}

/** A dummy implementation of [Span] that records its lifecycle and events. */
class DummySpan(
  val name: String,
  initialAttributes: Map<String, Any>,
  val parentSpan: DummySpan?,
  val onSpanEnd: (DummySpan) -> Unit,
) : Span {
  var isEnded = false
  val events = mutableListOf<String>()
  val attributes = initialAttributes.toMutableMap()

  override operator fun set(key: String, value: String): Span {
    attributes[key] = value
    return this
  }

  override operator fun set(key: String, value: Long): Span {
    attributes[key] = value
    return this
  }

  override operator fun set(key: String, value: Double): Span {
    attributes[key] = value
    return this
  }

  override operator fun set(key: String, value: Boolean): Span {
    attributes[key] = value
    return this
  }

  override operator fun set(key: String, value: List<String>): Span {
    attributes[key] = value
    return this
  }

  override fun addEvent(name: String): Span {
    events.add(name)
    return this
  }

  override fun recordException(exception: Throwable): Span = this

  override fun end() {
    isEnded = true
    onSpanEnd(this)
  }
}

/** A dummy implementation of [TelemetryContext]. */
class DummyTelemetryContext(val span: DummySpan? = null) : TelemetryContext {
  override fun asContextElement(): TelemetryContextElement = DummyTelemetryContextElement

  override fun attach(): Scope = DummyScope

  override fun detach(scopeToken: Scope) {}
}

/** A no-op [Scope] for [DummyTelemetryContext]. */
private object DummyScope : Scope {
  override fun close() {}
}

/** A no-op [TelemetryContextElement] for [DummyTelemetryContext]. */
private object DummyTelemetryContextElement : TelemetryContextElement {
  override val key: kotlin.coroutines.CoroutineContext.Key<*> = TelemetryContextElement.Key
  override val context: TelemetryContext = DummyTelemetryContext()
}
