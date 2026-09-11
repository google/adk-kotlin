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

import com.google.adk.kt.logging.LoggerFactory
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.context.propagation.TextMapPropagator
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.logs.LogRecordProcessor
import io.opentelemetry.sdk.logs.SdkLoggerProvider
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.export.MetricReader
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.SpanProcessor
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * A bundle of OpenTelemetry plumbing an application asks ADK to install, grouped by signal.
 *
 * A caller fills in only the signals it exports, and a signal left empty gets no provider. A caller
 * that wants OTLP builds the exporter itself and passes it here like any other.
 */
class OtelHooks(
  val spanProcessors: List<SpanProcessor> = emptyList(),
  val metricReaders: List<MetricReader> = emptyList(),
  val logRecordProcessors: List<LogRecordProcessor> = emptyList(),
)

/** Private marker used only to name this file's logger. */
private object OtelSetup

private val logger = LoggerFactory.getLogger(OtelSetup::class)

private const val OTEL_RESOURCE_ATTRIBUTES = "OTEL_RESOURCE_ATTRIBUTES"
private const val OTEL_SERVICE_NAME = "OTEL_SERVICE_NAME"
private const val SERVICE_NAME = "service.name"

/**
 * Builds OpenTelemetry providers from [otelHooksToSetup] and registers them as this process's
 * global OpenTelemetry.
 *
 * Every bundle contributes, and one provider is built per signal that ends up with at least one
 * processor or reader. Nothing is registered when no bundle carries anything, and a host that
 * configured OpenTelemetry first keeps its own providers.
 *
 * @param otelHooksToSetup per-signal processors and readers to add to the providers ADK builds.
 * @param otelResource resource stamped on everything those providers export; when null, one is read
 *   from the OpenTelemetry environment variables.
 */
fun maybeSetOtelProviders(
  otelHooksToSetup: List<OtelHooks> = emptyList(),
  otelResource: Resource? = null,
) {
  val spanProcessors = otelHooksToSetup.flatMap { it.spanProcessors }
  val metricReaders = otelHooksToSetup.flatMap { it.metricReaders }
  val logRecordProcessors = otelHooksToSetup.flatMap { it.logRecordProcessors }

  // An empty SDK would spend the one global registration and hand the host nothing for it.
  if (spanProcessors.isEmpty() && metricReaders.isEmpty() && logRecordProcessors.isEmpty()) {
    return
  }

  val resource = otelResource ?: defaultOtelResource()
  val sdkBuilder = OpenTelemetrySdk.builder()

  // The builder defaults to no propagators and the registration is one-shot, so set the W3C pair.
  sdkBuilder.setPropagators(
    ContextPropagators.create(
      TextMapPropagator.composite(
        W3CTraceContextPropagator.getInstance(),
        W3CBaggagePropagator.getInstance(),
      )
    )
  )

  if (spanProcessors.isNotEmpty()) {
    val tracerProviderBuilder = SdkTracerProvider.builder().setResource(resource)
    spanProcessors.forEach { tracerProviderBuilder.addSpanProcessor(it) }
    sdkBuilder.setTracerProvider(tracerProviderBuilder.build())
  }

  if (metricReaders.isNotEmpty()) {
    val meterProviderBuilder = SdkMeterProvider.builder().setResource(resource)
    metricReaders.forEach { meterProviderBuilder.registerMetricReader(it) }
    sdkBuilder.setMeterProvider(meterProviderBuilder.build())
  }

  if (logRecordProcessors.isNotEmpty()) {
    val loggerProviderBuilder = SdkLoggerProvider.builder().setResource(resource)
    logRecordProcessors.forEach { loggerProviderBuilder.addLogRecordProcessor(it) }
    sdkBuilder.setLoggerProvider(loggerProviderBuilder.build())
  }

  try {
    sdkBuilder.buildAndRegisterGlobal()
  } catch (e: IllegalStateException) {
    logger.warn(e) {
      "OpenTelemetry is already configured globally; discarding the providers ADK built."
    }
  }
}

/**
 * The resource used when the caller supplies none.
 *
 * The Java SDK reads `OTEL_SERVICE_NAME` and `OTEL_RESOURCE_ATTRIBUTES` only through its
 * autoconfigure module, which ADK does not depend on, so they are read here and layered over the
 * SDK's default resource. Anything the environment names wins.
 */
private fun defaultOtelResource(): Resource =
  Resource.getDefault()
    .merge(
      Resource.create(
        otelEnvironmentAttributes(
          resourceAttributes = System.getenv(OTEL_RESOURCE_ATTRIBUTES),
          serviceName = System.getenv(OTEL_SERVICE_NAME),
        )
      )
    )

/**
 * The resource attributes the two OpenTelemetry environment variables name, given their values.
 *
 * [resourceAttributes] is a comma-separated list of `key=value` pairs whose values are
 * percent-encoded, and an entry with no `=` is dropped while the rest of the list still counts.
 * [serviceName], when set, wins for `service.name`.
 */
internal fun otelEnvironmentAttributes(
  resourceAttributes: String?,
  serviceName: String?,
): Attributes {
  val attributes = Attributes.builder()
  resourceAttributes.orEmpty().split(",").forEachIndexed { index, pair ->
    if (pair.isBlank()) return@forEachIndexed
    val separator = pair.indexOf('=')
    if (separator < 0) {
      // Naming the position keeps a credential pasted into the variable out of the log.
      logger.warn {
        "Dropping entry ${index + 1} of $OTEL_RESOURCE_ATTRIBUTES: it is not a key=value pair."
      }
      return@forEachIndexed
    }
    val key = pair.substring(0, separator).trim()
    attributes.put(key, percentDecode(key, pair.substring(separator + 1).trim()))
  }
  if (!serviceName.isNullOrEmpty()) {
    attributes.put(SERVICE_NAME, serviceName)
  }
  return attributes.build()
}

/**
 * Decodes the `%XX` escapes in the value [key] was given.
 *
 * A literal `+` is escaped first, because `URLDecoder` reads it as a space and this syntax does
 * not. A value whose escapes are malformed is returned unchanged rather than failing setup.
 */
private fun percentDecode(key: String, value: String): String =
  try {
    URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8)
  } catch (e: IllegalArgumentException) {
    logger.warn(e) { "Leaving the value of '$key' in $OTEL_RESOURCE_ATTRIBUTES undecoded." }
    value
  }
