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

import com.google.adk.kt.webserver.telemetry.ServerTelemetry.DISABLED_RESOURCE_PROVIDERS
import com.google.adk.kt.webserver.telemetry.ServerTelemetry.ENABLED_RESOURCE_PROVIDERS
import com.google.adk.kt.webserver.telemetry.ServerTelemetry.LOGS_EXPORTER
import com.google.adk.kt.webserver.telemetry.ServerTelemetry.METRICS_EXPORTER
import com.google.adk.kt.webserver.telemetry.ServerTelemetry.OTLP_ENDPOINT
import com.google.adk.kt.webserver.telemetry.ServerTelemetry.OTLP_PROTOCOL
import com.google.adk.kt.webserver.telemetry.ServerTelemetry.OTLP_TRACES_ENDPOINT
import com.google.adk.kt.webserver.telemetry.ServerTelemetry.TRACES_EXPORTER
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class ExporterOverridesTest {

  @Test
  fun nothingSet_exportsNothing_overHttpProtobuf_withoutGcpResourceDetection() {
    assertThat(overrides())
      .containsExactly(
        TRACES_EXPORTER,
        "none",
        METRICS_EXPORTER,
        "none",
        LOGS_EXPORTER,
        "none",
        OTLP_PROTOCOL,
        "http/protobuf",
        DISABLED_RESOURCE_PROVIDERS,
        GCP_PROVIDER,
      )
  }

  @Test
  fun enabledResourceProviders_addNoDisabledProviders() {
    assertThat(overrides(ENABLED_RESOURCE_PROVIDERS to "com.example.Provider"))
      .doesNotContainKey(DISABLED_RESOURCE_PROVIDERS)
  }

  @Test
  fun disabledResourceProviders_keepGcpResourceDetectionOff() {
    assertThat(overrides(DISABLED_RESOURCE_PROVIDERS to "com.example.Provider"))
      .containsEntry(DISABLED_RESOURCE_PROVIDERS, "com.example.Provider,$GCP_PROVIDER")
  }

  @Test
  fun disabledResourceProvidersNamingGcp_areLeftAlone() {
    assertThat(overrides(DISABLED_RESOURCE_PROVIDERS to "com.example.Provider, $GCP_PROVIDER"))
      .doesNotContainKey(DISABLED_RESOURCE_PROVIDERS)
  }

  @Test
  fun genericEndpoint_exportsTracesOnly() {
    val overrides = overrides(OTLP_ENDPOINT to "http://collector:4318")

    assertThat(overrides).containsEntry(TRACES_EXPORTER, "otlp")
    assertThat(overrides).containsEntry(METRICS_EXPORTER, "none")
    assertThat(overrides).containsEntry(LOGS_EXPORTER, "none")
  }

  @Test
  fun tracesEndpoint_exportsTraces() {
    assertThat(overrides(OTLP_TRACES_ENDPOINT to "http://collector:4318/v1/traces"))
      .containsEntry(TRACES_EXPORTER, "otlp")
  }

  @Test
  fun metricsOrLogsEndpointAlone_exportsNothing() {
    val overrides =
      overrides(
        "otel.exporter.otlp.metrics.endpoint" to "http://collector:4318/v1/metrics",
        "otel.exporter.otlp.logs.endpoint" to "http://collector:4318/v1/logs",
      )

    assertThat(overrides).containsEntry(TRACES_EXPORTER, "none")
    assertThat(overrides).containsEntry(METRICS_EXPORTER, "none")
    assertThat(overrides).containsEntry(LOGS_EXPORTER, "none")
  }

  @Test
  fun explicitExporters_areLeftAlone() {
    val overrides =
      overrides(TRACES_EXPORTER to "none", METRICS_EXPORTER to "otlp", LOGS_EXPORTER to "otlp")

    assertThat(overrides).doesNotContainKey(TRACES_EXPORTER)
    assertThat(overrides).doesNotContainKey(METRICS_EXPORTER)
    assertThat(overrides).doesNotContainKey(LOGS_EXPORTER)
  }

  @Test
  fun explicitTracesExporter_winsOverAnEndpoint() {
    assertThat(overrides(TRACES_EXPORTER to "none", OTLP_ENDPOINT to "http://collector:4318"))
      .doesNotContainKey(TRACES_EXPORTER)
  }

  @Test
  fun explicitProtocol_isLeftAlone() {
    assertThat(overrides(OTLP_PROTOCOL to "grpc")).doesNotContainKey(OTLP_PROTOCOL)
  }

  @Test
  fun blankValues_countAsUnset() {
    val overrides =
      overrides(
        TRACES_EXPORTER to " ",
        OTLP_ENDPOINT to "",
        OTLP_PROTOCOL to "  ",
        ENABLED_RESOURCE_PROVIDERS to "",
        DISABLED_RESOURCE_PROVIDERS to " ",
      )

    assertThat(overrides).containsEntry(TRACES_EXPORTER, "none")
    assertThat(overrides).containsEntry(OTLP_PROTOCOL, "http/protobuf")
    assertThat(overrides).containsEntry(DISABLED_RESOURCE_PROVIDERS, GCP_PROVIDER)
  }

  private fun overrides(vararg settings: Pair<String, String>): Map<String, String> {
    val values = settings.toMap()
    return ServerTelemetry.exporterOverrides { values[it] }
  }

  companion object {
    private const val GCP_PROVIDER = "io.opentelemetry.contrib.gcp.resource.GCPResourceProvider"
  }
}
