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

import com.google.errorprone.annotations.CanIgnoreReturnValue

/** A Span represents a single operation within a trace. */
interface Span {
  /**
   * Sets a string attribute on the span.
   *
   * @param key the attribute key.
   * @param value the attribute value.
   */
  @CanIgnoreReturnValue operator fun set(key: String, value: String): Span

  /**
   * Sets a long attribute on the span.
   *
   * @param key the attribute key.
   * @param value the attribute value.
   */
  @CanIgnoreReturnValue operator fun set(key: String, value: Long): Span

  /**
   * Sets a double attribute on the span.
   *
   * @param key the attribute key.
   * @param value the attribute value.
   */
  @CanIgnoreReturnValue operator fun set(key: String, value: Double): Span

  /**
   * Sets a boolean attribute on the span.
   *
   * @param key the attribute key.
   * @param value the attribute value.
   */
  @CanIgnoreReturnValue operator fun set(key: String, value: Boolean): Span

  /**
   * Sets a string-array attribute on the span (e.g. OTEL `gen_ai.response.finish_reasons`).
   *
   * @param key the attribute key.
   * @param value the attribute value.
   */
  @CanIgnoreReturnValue operator fun set(key: String, value: List<String>): Span

  /**
   * Adds an event to the span.
   *
   * @param name the name of the event.
   */
  @CanIgnoreReturnValue fun addEvent(name: String): Span

  /**
   * Records an exception on the span.
   *
   * @param exception the exception to record.
   */
  @CanIgnoreReturnValue fun recordException(exception: Throwable): Span

  /** Ends the span. */
  fun end()
}
