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

import com.google.common.truth.Truth.assertThat
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import java.util.concurrent.CyclicBarrier
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** How [ServerTelemetry.install] treats the process-wide OpenTelemetry. */
@RunWith(JUnit4::class)
class ServerTelemetryInstallTest {
  private val log = RecordingLog()

  @Before
  fun setUp() {
    ServerTelemetry.resetForTest()
  }

  @After
  fun tearDown() {
    ServerTelemetry.resetForTest()
  }

  @Test
  fun install_registersTheGlobal_feedingTheDevUi() {
    val devUi = ApiServerSpanExporter()

    ServerTelemetry.install(devUi, log)
    emitGlobalSpan("global-span")

    assertThat(GlobalOpenTelemetry.isSet()).isTrue()
    assertThat(devUi.spanNames()).containsExactly("global-span")
    assertThat(log.entries).isEmpty()
  }

  @Test
  fun installTwice_keepsTheFirstSdk() {
    val first = ApiServerSpanExporter()
    val second = ApiServerSpanExporter()

    ServerTelemetry.install(first, log)
    ServerTelemetry.install(second, log)
    emitGlobalSpan("global-span")

    assertThat(first.spanNames()).containsExactly("global-span")
    assertThat(second.spanNames()).isEmpty()
    assertThat(log.entries).isEmpty()
  }

  @Test
  fun install_afterAHostSdk_leavesItInPlace_andWarnsOnce() {
    val host = ApiServerSpanExporter()
    val hostSdk =
      OpenTelemetrySdk.builder()
        .setTracerProvider(
          SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(host)).build()
        )
        .buildAndRegisterGlobal()
    val devUi = ApiServerSpanExporter()

    hostSdk.use {
      ServerTelemetry.install(devUi, log)
      ServerTelemetry.install(devUi, log)
      emitGlobalSpan("global-span")
    }

    assertThat(host.spanNames()).containsExactly("global-span")
    assertThat(devUi.spanNames()).isEmpty()
    assertThat(log.warnings).hasSize(1)
    assertThat(log.warnings.single()).contains("registered before the server started")
  }

  @Test
  fun install_afterAnEarlyGet_namesTheNoOp() {
    val unused = GlobalOpenTelemetry.get()

    ServerTelemetry.install(ApiServerSpanExporter(), log)

    assertThat(log.warnings).hasSize(1)
    assertThat(log.warnings.single()).contains("no-op")
  }

  @Test
  fun installConcurrently_buildsOneSdk() {
    val exporters = List(RACING_THREADS) { ApiServerSpanExporter() }
    val barrier = CyclicBarrier(RACING_THREADS)

    exporters
      .map { exporter ->
        thread {
          barrier.await()
          ServerTelemetry.install(exporter, log)
        }
      }
      .forEach { it.join() }
    emitGlobalSpan("global-span")

    assertThat(exporters.count { it.spanNames().isNotEmpty() }).isEqualTo(1)
    assertThat(log.entries).isEmpty()
  }

  @Test
  fun forceFlush_withNothingInstalled_completesAtOnce() {
    assertThat(ServerTelemetry.forceFlush().isSuccess).isTrue()
  }

  private fun emitGlobalSpan(name: String) {
    GlobalOpenTelemetry.getTracer("test").spanBuilder(name).startSpan().end()
  }

  private fun ApiServerSpanExporter.spanNames(): List<String> =
    getAllExportedSpans().map { it.name }

  private companion object {
    const val RACING_THREADS = 8
  }
}
