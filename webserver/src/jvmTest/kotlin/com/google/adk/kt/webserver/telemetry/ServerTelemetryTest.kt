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

import com.google.adk.kt.webserver.telemetry.ServerTelemetry.DEFAULT_SERVICE_NAME
import com.google.adk.kt.webserver.telemetry.ServerTelemetry.LOGS_EXPORTER
import com.google.adk.kt.webserver.telemetry.ServerTelemetry.OTLP_ENDPOINT
import com.google.adk.kt.webserver.telemetry.ServerTelemetry.OTLP_TRACES_ENDPOINT
import com.google.common.truth.Truth.assertThat
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * What [ServerTelemetry.buildSdk] exports and where, read from `otel.*` system properties.
 *
 * The SDKs built here are never registered globally, so these tests share a JVM safely.
 */
@RunWith(JUnit4::class)
class ServerTelemetryTest {
  private val devUi = ApiServerSpanExporter()
  private val log = RecordingLog()

  @Test
  fun tracesEndpoint_exportsOverHttpProtobuf_withTheConfiguredHeaders() {
    OtlpReceiver().use { receiver ->
      val exports =
        withSystemProperties(
          OTLP_TRACES_ENDPOINT to "${receiver.endpoint}/v1/traces",
          "otel.exporter.otlp.headers" to "x-adk-test=present",
        ) {
          // The batch processor would wait 5 s, so only the flush delivers this span in time.
          emitSpan("unit-span")
        }

      assertThat(exports).containsExactly("traces (otlp)")
      val request = receiver.requests.single()
      assertThat(request.path).isEqualTo("/v1/traces")
      assertThat(request.header("Content-Type")).isEqualTo("application/x-protobuf")
      assertThat(request.header("x-adk-test")).isEqualTo("present")
      assertThat(receiver.received("unit-span")).isTrue()
      assertThat(devUiSpanNames()).containsExactly("unit-span")
      assertThat(log.entries).isEmpty()
    }
  }

  @Test
  fun genericEndpoint_postsToTheTracesPath() {
    OtlpReceiver().use { receiver ->
      withSystemProperties(OTLP_ENDPOINT to receiver.endpoint) { emit("unit-span") }

      assertThat(receiver.requests.single().path).isEqualTo("/v1/traces")
    }
  }

  @Test
  fun noEndpoint_exportsNothing_butFeedsTheDevUi() {
    val exports = emitSpan("unit-span")

    assertThat(exports).isEmpty()
    assertThat(devUiSpanNames()).containsExactly("unit-span")
  }

  @Test
  fun resource_namesTheServiceByDefault() {
    emit("unit-span")

    assertThat(devUiResourceAttribute("service.name")).isEqualTo(DEFAULT_SERVICE_NAME)
  }

  @Test
  fun resource_takesTheServiceNameSetting() {
    withSystemProperties("otel.service.name" to "from-setting") { emit("unit-span") }

    assertThat(devUiResourceAttribute("service.name")).isEqualTo("from-setting")
  }

  @Test
  fun resource_takesResourceAttributes_lessDisabledKeys() {
    withSystemProperties(
      "otel.resource.attributes" to "service.name=from-attributes,team=adk,region=eu",
      "otel.resource.disabled.keys" to "region",
    ) {
      emit("unit-span")
    }

    assertThat(devUiResourceAttribute("service.name")).isEqualTo("from-attributes")
    assertThat(devUiResourceAttribute("team")).isEqualTo("adk")
    assertThat(devUiResourceAttribute("region")).isNull()
  }

  @Test
  fun sdkDisabled_exportsNothing_andFeedsNothing() {
    OtlpReceiver().use { receiver ->
      val exports =
        withSystemProperties(
          "otel.sdk.disabled" to "true",
          OTLP_TRACES_ENDPOINT to "${receiver.endpoint}/v1/traces",
        ) {
          emitSpan("unit-span")
        }

      assertThat(exports).isEmpty()
      assertThat(receiver.requests).isEmpty()
      assertThat(devUiSpanNames()).isEmpty()
    }
  }

  @Test
  fun malformedHeader_isIgnoredWithoutEchoingIt() {
    // The decoder's own error quotes the two characters after the stray `%`.
    assertFallsBackWithoutLeaking(
      leak = "zz",
      OTLP_TRACES_ENDPOINT to "http://127.0.0.1:9/v1/traces",
      "otel.exporter.otlp.headers" to "authorization=Bearer%20$SECRET%zz",
    )
  }

  @Test
  fun endpointWithAQuery_isIgnoredWithoutEchoingIt() {
    assertFallsBackWithoutLeaking(
      leak = SECRET,
      OTLP_TRACES_ENDPOINT to "http://127.0.0.1:9/v1/traces?k=$SECRET",
    )
  }

  @Test
  fun failureAfterTheTracerProviderWasBuilt_stillFeedsTheDevUi() {
    // Logs come last, so autoconfigure has already built, and on failure closed, the processors.
    assertFallsBackWithoutLeaking(leak = SECRET, LOGS_EXPORTER to "unknown-$SECRET")
  }

  @Test
  fun failureOutsideAutoconfiguresOwnErrorHandling_isIgnored() {
    // Read before autoconfigure's own error handling; OpenTelemetry logs the path, so no secret.
    assertFallsBackWithoutLeaking(leak = SECRET, "otel.config.file" to "/nonexistent/adk.yaml")
  }

  /**
   * Builds with [settings], which must fail to apply, and checks the fallback still feeds the
   * Development UI and nothing logged quotes [leak].
   */
  private fun assertFallsBackWithoutLeaking(leak: String, vararg settings: Pair<String, String>) {
    lateinit var exports: List<String>
    val otelLogs = captureOpenTelemetryLogs {
      exports = withSystemProperties(*settings) { emitSpan("unit-span") }
    }

    assertThat(exports).isEmpty()
    assertThat(devUiSpanNames()).containsExactly("unit-span")
    assertThat(log.warnings).hasSize(1)
    assertThat(log.warnings.single()).startsWith("Exporting no telemetry")
    assertThat(log.warnings.single()).doesNotContain(leak)
    assertThat(otelLogs).doesNotContain(leak)
  }

  /** Builds an SDK from the current settings, ends one span on it, and returns what it exports. */
  private fun emitSpan(name: String): List<String> {
    val built = ServerTelemetry.buildSdk(log) { listOf(SimpleSpanProcessor.create(devUi)) }
    try {
      built.sdk.getTracer("test").spanBuilder(name).startSpan().end()
      built.sdk.sdkTracerProvider
        .forceFlush()
        .join(FLUSH_TIMEOUT.inWholeMilliseconds, TimeUnit.MILLISECONDS)
    } finally {
      built.sdk.shutdown().join(FLUSH_TIMEOUT.inWholeMilliseconds, TimeUnit.MILLISECONDS)
    }
    return built.exports
  }

  /** [emitSpan] for a test that does not check what the SDK exports. */
  private fun emit(name: String) {
    val unused = emitSpan(name)
  }

  private fun devUiSpanNames(): List<String> = devUi.getAllExportedSpans().map { it.name }

  private fun devUiResourceAttribute(key: String): String? =
    devUi.getAllExportedSpans().single().resource.getAttribute(AttributeKey.stringKey(key))

  private companion object {
    const val SECRET = "s3cret"
    val FLUSH_TIMEOUT = 10.seconds
  }
}
