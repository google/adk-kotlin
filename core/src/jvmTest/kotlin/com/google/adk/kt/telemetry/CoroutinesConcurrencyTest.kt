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

import com.google.adk.kt.telemetry.otel.OtelSpan
import com.google.adk.kt.telemetry.otel.OtelTracer
import com.google.common.truth.Truth.assertThat
import io.opentelemetry.api.trace.Span as OpenTelemetrySpan
import io.opentelemetry.sdk.trace.SdkTracerProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class CoroutinesConcurrencyTest {

  @Test
  fun withSpan_withDispatcherSwitch_maintainsContext() {
    val provider = SdkTracerProvider.builder().build()
    try {
      // Provide a real tracer via the coroutine context (as the runner does from App.tracer).
      runBlocking(TracerElement(OtelTracer(provider.get("test")))) {
        withSpan("concurrent_span") { span ->
          val nativeSpan = (span as OtelSpan).otelSpan

          // Before thread switch
          assertThat(OpenTelemetrySpan.current()).isSameInstanceAs(nativeSpan)

          withContext(Dispatchers.Default) {
            // hopped to background thread dispatcher
            assertThat(OpenTelemetrySpan.current()).isSameInstanceAs(nativeSpan)

            // Spawn multiple child coroutines
            val def1 = async {
              delay(10) // Force suspension and potential thread switch
              assertThat(OpenTelemetrySpan.current()).isSameInstanceAs(nativeSpan)
            }
            val def2 = async {
              delay(10)
              assertThat(OpenTelemetrySpan.current()).isSameInstanceAs(nativeSpan)
            }
            awaitAll(def1, def2)
          }

          // Back to original dispatcher
          assertThat(OpenTelemetrySpan.current()).isSameInstanceAs(nativeSpan)
        }
      }
    } finally {
      provider.close()
    }
  }
}
