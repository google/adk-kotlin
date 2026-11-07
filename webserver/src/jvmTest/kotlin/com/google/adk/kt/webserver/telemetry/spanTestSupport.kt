/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.adk.kt.webserver.telemetry

import com.google.errorprone.annotations.CanIgnoreReturnValue
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import java.util.concurrent.TimeUnit

/** Returns an exporter holding the spans [record] ends, exported through the real SDK. */
internal fun exporterWith(record: Tracer.() -> Unit): ApiServerSpanExporter {
  val exporter = ApiServerSpanExporter()
  SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build().use {
    provider ->
    provider.get("test").record()
    provider.forceFlush().join(5, TimeUnit.SECONDS)
  }
  return exporter
}

/** Ends a span [name] carrying [attributes] under [parent], and returns it. */
@CanIgnoreReturnValue
internal fun Tracer.endSpan(
  name: String,
  vararg attributes: Pair<String, String>,
  parent: Span? = null,
): Span {
  val builder = spanBuilder(name)
  if (parent != null) builder.setParent(Context.root().with(parent))
  for ((key, value) in attributes) builder.setAttribute(key, value)
  return builder.startSpan().also { it.end() }
}
