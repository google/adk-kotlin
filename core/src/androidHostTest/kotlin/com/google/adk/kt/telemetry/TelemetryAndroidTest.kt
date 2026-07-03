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

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.adk.kt.telemetry.otel.OtelTracer as OtelTracerImpl
import com.google.common.truth.Truth.assertThat
import io.opentelemetry.api.OpenTelemetry
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android-specific telemetry tests verifying the OpenTelemetry-backed tracer built via [OtelTracer]
 * works on Android and that the Android-compiled span path executes end-to-end. See b/524162719.
 *
 * The full span-export round-trip is verified on the JVM (see `OtelTracerTest`) because the
 * OpenTelemetry SDK is JVM-only here; the production tracer code is shared (commonJvmAndroidMain),
 * so the JVM round-trip exercises the same code compiled into this Android target.
 */
@RunWith(AndroidJUnit4::class)
class TelemetryAndroidTest {

  private val tracer = OtelTracer(OpenTelemetry.noop())

  @Test
  fun otelTracer_onAndroid_buildsOtelTracer() {
    assertThat(tracer).isInstanceOf(OtelTracerImpl::class.java)
  }

  @Test
  fun span_whenExercisedOnAndroid_doesNotCrash() {
    val span = tracer.spanBuilder("android-span").set("key", "value").startSpan()

    span
      .set("long", 1L)
      .set("double", 2.0)
      .set("bool", true)
      .set("list", listOf("a", "b"))
      .addEvent("event")
      .recordException(RuntimeException("boom"))
      .end()

    assertThat(tracer.contextWithSpan(span)).isNotNull()
  }
}
