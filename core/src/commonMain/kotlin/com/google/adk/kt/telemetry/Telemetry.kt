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

/** Returns the default platform-specific tracer interface. */
internal expect fun defaultTracer(): Tracer

/** Internal test tracer setter for concurrent overrides on JVM. */
internal expect fun internalSetTestTracer(tracer: Tracer)

/** Internal test tracer getter for concurrent overrides on JVM. */
internal expect fun getTestTracer(): Tracer?

/** Internal test tracer resetter for concurrent overrides on JVM. */
internal expect fun internalResetTestTracer()

/**
 * Captures the caller's current ambient telemetry context synchronously (e.g. the host's active
 * OpenTelemetry span on this thread). Used to parent ADK's root span under the caller's span so
 * engine spans join the host's trace instead of starting a new one.
 */
internal expect fun currentTelemetryContext(): TelemetryContext

/** Global entry point for the Telemetry abstraction. */
object Telemetry {
  // In actual implementation, defaultTracer() provides the platform default
  private val defaultTracer: Tracer by lazy { defaultTracer() }

  /**
   * The active Tracer. Defaults to the platform implementation. On JVM tests, this can be safely
   * overridden per-thread using [setTracerForTest].
   */
  val tracer: Tracer
    get() = getTestTracer() ?: defaultTracer

  /** Safely sets a custom tracer for testing. */
  fun setTracerForTest(tracer: Tracer) {
    internalSetTestTracer(tracer)
  }

  /** Resets the test tracer to restore the default tracer. */
  fun resetTracer() {
    internalResetTestTracer()
  }

  /** Delegates entirely to configured tracer */
  suspend fun currentContext(): TelemetryContext = tracer.currentContext()
}
