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

import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.TracerProvider
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.context.propagation.TextMapPropagator
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk
import io.opentelemetry.sdk.autoconfigure.ResourceConfiguration
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.SpanProcessor
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import java.util.ServiceConfigurationError
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

/** Where telemetry setup reports; tests pass their own to read what it said. */
internal typealias TelemetryLog = (Level, String) -> Unit

/** An SDK and what it exports, as `traces (otlp)`; empty when it exports nothing. */
internal data class BuiltSdk(val sdk: OpenTelemetrySdk, val exports: List<String>)

/**
 * The process-wide OpenTelemetry SDK the server's spans go through.
 *
 * The first server to start installs it and later ones reuse it; an OpenTelemetry registered before
 * that is left in place. It exports what the standard `OTEL_*` settings ask for and also feeds the
 * Development UI, unless those settings disable the SDK.
 */
internal object ServerTelemetry {
  const val TRACES_EXPORTER = "otel.traces.exporter"
  const val METRICS_EXPORTER = "otel.metrics.exporter"
  const val LOGS_EXPORTER = "otel.logs.exporter"
  const val OTLP_ENDPOINT = "otel.exporter.otlp.endpoint"
  const val OTLP_TRACES_ENDPOINT = "otel.exporter.otlp.traces.endpoint"
  const val OTLP_PROTOCOL = "otel.exporter.otlp.protocol"
  const val ENABLED_RESOURCE_PROVIDERS = "otel.java.enabled.resource.providers"
  const val DISABLED_RESOURCE_PROVIDERS = "otel.java.disabled.resource.providers"

  /** The service name when neither `OTEL_SERVICE_NAME` nor `OTEL_RESOURCE_ATTRIBUTES` names one. */
  const val DEFAULT_SERVICE_NAME = "adk-web-server"

  private const val SDK_DISABLED = "otel.sdk.disabled"
  private const val RESOURCE_DISABLED_KEYS = "otel.resource.disabled.keys"
  private const val NONE = "none"
  private const val OTLP = "otlp"
  private const val HTTP_PROTOBUF = "http/protobuf"
  // Queries the GCP metadata server while the SDK is built, which can block startup off GCP.
  private const val GCP_RESOURCE_PROVIDER =
    "io.opentelemetry.contrib.gcp.resource.GCPResourceProvider"
  private const val MAX_CAUSES = 16

  private const val NOOP_GLOBAL_WARNING =
    "OpenTelemetry was set to a no-op tracer before the server started, typically by an early " +
      "GlobalOpenTelemetry.get(): ADK spans are dropped, OTEL_* settings are not applied, and the " +
      "Development UI shows no traces."

  private const val HOST_GLOBAL_WARNING =
    "OpenTelemetry was registered before the server started, so ADK uses it as it is: ADK does " +
      "not apply OTEL_* settings, and the Development UI shows no traces."

  private val SERVICE_NAME: AttributeKey<String> = AttributeKey.stringKey("service.name")
  private val RESET_TIMEOUT = 5.seconds

  private val logger = LoggerFactory.getLogger(ServerTelemetry::class.java)
  private val defaultLog: TelemetryLog = { level, message -> logger.atLevel(level).log(message) }

  private val lock = Any()
  private var installed: OpenTelemetrySdk? = null
  private var warnedHostOwned = false

  /**
   * Installs the SDK with [devUiExporter] as a span destination, unless one is installed already.
   *
   * Never throws for a telemetry failure: the server runs without ADK telemetry instead.
   */
  fun install(devUiExporter: SpanExporter, log: TelemetryLog = defaultLog) {
    synchronized(lock) {
      if (installed != null) return
      try {
        installed = register(log) { listOf(SimpleSpanProcessor.create(devUiExporter)) }
      } catch (e: Throwable) {
        if (!isRecoverable(e)) throw e
        log(Level.WARN, "Running without ADK telemetry, which failed to start (${describe(e)}).")
      }
    }
  }

  /** Flushes what the installed SDK has buffered; completes at once when none is installed. */
  fun forceFlush(): CompletableResultCode {
    val sdk = synchronized(lock) { installed } ?: return CompletableResultCode.ofSuccess()
    return try {
      CompletableResultCode.ofAll(
        listOf(
          sdk.sdkTracerProvider.forceFlush(),
          sdk.sdkMeterProvider.forceFlush(),
          sdk.sdkLoggerProvider.forceFlush(),
        )
      )
    } catch (e: Exception) {
      // A processor from an extension may throw, and a stop hook must not.
      logger.warn("Failed to flush telemetry: {}", e.javaClass.name)
      CompletableResultCode.ofFailure()
    }
  }

  /** Shuts down the installed SDK and clears the global, so the next [install] starts afresh. */
  fun resetForTest() {
    synchronized(lock) {
      installed?.shutdown()?.join(RESET_TIMEOUT.inWholeMilliseconds, TimeUnit.MILLISECONDS)
      installed = null
      warnedHostOwned = false
    }
    GlobalOpenTelemetry.resetForTest()
  }

  /**
   * The settings ADK puts over OpenTelemetry autoconfigure's defaults, given the effective ones.
   *
   * Traces go out over OTLP only when an endpoint is named, metrics and logs only when their
   * exporter is named, and OTLP defaults to `http/protobuf` as in the other ADKs. The GCP resource
   * detector stays off unless the enabled resource providers name it.
   */
  fun exporterOverrides(get: (String) -> String?): Map<String, String> {
    fun isSet(key: String) = !get(key).isNullOrBlank()
    return buildMap {
      if (!isSet(TRACES_EXPORTER)) {
        put(
          TRACES_EXPORTER,
          if (isSet(OTLP_ENDPOINT) || isSet(OTLP_TRACES_ENDPOINT)) OTLP else NONE,
        )
      }
      if (!isSet(METRICS_EXPORTER)) put(METRICS_EXPORTER, NONE)
      if (!isSet(LOGS_EXPORTER)) put(LOGS_EXPORTER, NONE)
      if (!isSet(OTLP_PROTOCOL)) put(OTLP_PROTOCOL, HTTP_PROTOBUF)
      if (!isSet(ENABLED_RESOURCE_PROVIDERS)) {
        val disabled = get(DISABLED_RESOURCE_PROVIDERS)?.takeIf { it.isNotBlank() }
        when {
          disabled == null -> put(DISABLED_RESOURCE_PROVIDERS, GCP_RESOURCE_PROVIDER)
          GCP_RESOURCE_PROVIDER !in disabled.split(',').map { it.trim() } ->
            put(DISABLED_RESOURCE_PROVIDERS, "$disabled,$GCP_RESOURCE_PROVIDER")
        }
      }
    }
  }

  /**
   * Builds an SDK from the standard `OTEL_*` settings with ADK's [exporterOverrides], adding the
   * span processors [processors] makes.
   *
   * If the settings fail to apply, it warns and exports nothing, feeding a fresh set of
   * [processors] only: autoconfigure shuts down the ones it was given when it fails.
   */
  fun buildSdk(log: TelemetryLog, processors: () -> List<SpanProcessor>): BuiltSdk {
    var exports = emptyList<String>()
    return try {
      val sdk =
        AutoConfiguredOpenTelemetrySdk.builder()
          .addPropertiesCustomizer { config ->
            val overrides = exporterOverrides(config::getString)
            exports =
              if (config.getBoolean(SDK_DISABLED, false)) emptyList()
              else exportedSignals { key -> overrides[key] ?: config.getString(key) }
            overrides
          }
          .addResourceCustomizer(::adkResource)
          .addTracerProviderCustomizer { builder, _ ->
            for (processor in processors()) builder.addSpanProcessor(processor)
            builder
          }
          // Ktor's own shutdown hook would race it, closing the SDK while requests still drain.
          .disableShutdownHook()
          .build()
          .openTelemetrySdk
      BuiltSdk(sdk, exports)
    } catch (e: Throwable) {
      if (!isRecoverable(e)) throw e
      log(
        Level.WARN,
        "Exporting no telemetry: the OpenTelemetry settings failed to apply (${describe(e)}). " +
          "Check the OTEL_* environment variables and otel.* system properties.",
      )
      BuiltSdk(fallbackSdk(processors()), emptyList())
    }
  }

  /** Builds and registers the SDK, or returns null when OpenTelemetry was registered first. */
  private fun register(
    log: TelemetryLog,
    processors: () -> List<SpanProcessor>,
  ): OpenTelemetrySdk? {
    var built: BuiltSdk? = null
    try {
      GlobalOpenTelemetry.set {
        // Checked under the global lock, so nothing is built when a global already exists.
        check(!GlobalOpenTelemetry.isSet())
        val result = buildSdk(log, processors)
        built = result
        result.sdk
      }
    } catch (_: IllegalStateException) {
      // Thrown, too, when something the build ran called GlobalOpenTelemetry.get() first.
      built?.sdk?.close()
      warnHostOwned(log)
      return null
    }
    val result = checkNotNull(built)
    if (result.exports.isNotEmpty()) {
      log(Level.INFO, "Exporting ADK telemetry: ${result.exports.joinToString()}")
    }
    return result.sdk
  }

  private fun exportedSignals(get: (String) -> String?): List<String> =
    listOf("traces" to TRACES_EXPORTER, "metrics" to METRICS_EXPORTER, "logs" to LOGS_EXPORTER)
      .mapNotNull { (signal, key) ->
        val exporter = get(key)?.trim()
        if (exporter.isNullOrEmpty() || exporter == NONE) null else "$signal ($exporter)"
      }

  /** An SDK feeding only [processors], with autoconfigure's default propagators. */
  private fun fallbackSdk(processors: List<SpanProcessor>): OpenTelemetrySdk {
    val tracerProvider =
      SdkTracerProvider.builder().setResource(withDefaultServiceName(Resource.getDefault()))
    for (processor in processors) tracerProvider.addSpanProcessor(processor)
    return OpenTelemetrySdk.builder()
      .setTracerProvider(tracerProvider.build())
      .setPropagators(
        ContextPropagators.create(
          TextMapPropagator.composite(
            W3CTraceContextPropagator.getInstance(),
            W3CBaggagePropagator.getInstance(),
          )
        )
      )
      .build()
  }

  /**
   * Applies `OTEL_SERVICE_NAME` and `OTEL_RESOURCE_ATTRIBUTES`, then names the service
   * [DEFAULT_SERVICE_NAME] when nothing else did.
   *
   * Not every build of autoconfigure registers the provider that applies those settings; where it
   * does, this merge repeats them, so disabled keys are filtered again.
   */
  private fun adkResource(resource: Resource, config: ConfigProperties): Resource {
    val disabledKeys = config.getList(RESOURCE_DISABLED_KEYS).toSet()
    val merged =
      resource
        .merge(ResourceConfiguration.createEnvironmentResource(config))
        .toBuilder()
        .removeIf { it.key in disabledKeys }
        .build()
    return withDefaultServiceName(merged)
  }

  private fun withDefaultServiceName(resource: Resource): Resource {
    val name = resource.getAttribute(SERVICE_NAME)
    if (name != null && !name.startsWith("unknown_service")) return resource
    return resource.toBuilder().put(SERVICE_NAME, DEFAULT_SERVICE_NAME).build()
  }

  private fun warnHostOwned(log: TelemetryLog) {
    if (warnedHostOwned) return
    warnedHostOwned = true
    // Read only once a global exists: on an unset one, get() would install the no-op itself.
    val noop = GlobalOpenTelemetry.getTracerProvider() === TracerProvider.noop()
    log(Level.WARN, if (noop) NOOP_GLOBAL_WARNING else HOST_GLOBAL_WARNING)
  }

  /** Whether a telemetry setup failure should be reported and survived rather than rethrown. */
  private fun isRecoverable(e: Throwable): Boolean =
    e is Exception || e is LinkageError || e is ServiceConfigurationError

  /** Names [e] and its root cause by type only, since OTel messages can quote secrets. */
  private fun describe(e: Throwable): String {
    // Bounded, since a cause chain can loop back on itself.
    val root = generateSequence(e) { it.cause }.take(MAX_CAUSES).last()
    return if (root === e) e.javaClass.name
    else "${e.javaClass.name}, caused by ${root.javaClass.name}"
  }
}
