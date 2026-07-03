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

import com.google.adk.kt.telemetry.noop.NoOpTracer
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class TelemetryTest {

  @Test
  fun currentTracer_withoutAppTracer_isNoOp() = runBlocking {
    // No TracerElement in the coroutine context (no App.tracer): telemetry is off by default, with
    // no global fallback.
    assertThat(currentTracer()).isSameInstanceAs(NoOpTracer)
  }

  @Test
  fun noOpTracer_whenMethodsCalled_doesNotCrash() = runBlocking {
    val tracer = NoOpTracer
    assertThat(tracer).isInstanceOf(NoOpTracer::class.java)

    // Verify we can call all methods without crashing.
    val span =
      tracer
        .spanBuilder("test-span")
        .set("key", "value")
        .set("key", 1L)
        .set("key", 2.0)
        .set("key", true)
        .startSpan()

    span.addEvent("event")
    span.recordException(RuntimeException("test"))

    tracer.spanBuilder("child").setParent(tracer.currentContext()).startSpan().end()

    span.end()
  }
}
