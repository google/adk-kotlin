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
package com.google.adk.kt.telemetry.otel

import com.google.adk.kt.telemetry.Span
import com.google.errorprone.annotations.CanIgnoreReturnValue
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Span as OpenTelemetrySpan

/** OpenTelemetry implementation of [Span]. */
internal class OtelSpan(val otelSpan: OpenTelemetrySpan) : Span {
  @CanIgnoreReturnValue
  override fun set(key: String, value: String): Span {
    otelSpan.setAttribute(key, value)
    return this
  }

  @CanIgnoreReturnValue
  override fun set(key: String, value: Long): Span {
    otelSpan.setAttribute(key, value)
    return this
  }

  @CanIgnoreReturnValue
  override fun set(key: String, value: Double): Span {
    otelSpan.setAttribute(key, value)
    return this
  }

  @CanIgnoreReturnValue
  override fun set(key: String, value: Boolean): Span {
    otelSpan.setAttribute(key, value)
    return this
  }

  @CanIgnoreReturnValue
  override fun set(key: String, value: List<String>): Span {
    otelSpan.setAttribute(AttributeKey.stringArrayKey(key), value)
    return this
  }

  @CanIgnoreReturnValue
  override fun addEvent(name: String): Span {
    otelSpan.addEvent(name)
    return this
  }

  @CanIgnoreReturnValue
  override fun recordException(exception: Throwable): Span {
    otelSpan.recordException(exception)
    return this
  }

  override fun end() {
    otelSpan.end()
  }
}
