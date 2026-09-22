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

package com.google.adk.kt.agents

import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.models.LiveInput
import kotlin.jvm.JvmStatic

/**
 * One item sent to a live agent through a [LiveRequestQueue].
 *
 * A [close] request carries no [input]; the constructor throws [IllegalArgumentException] if it
 * does.
 *
 * @property input The turn or realtime input to send, or null to send nothing to the model.
 * @property close Closes the queue: requests sent before it are still delivered, and anything sent
 *   after it is dropped.
 * @property stateDelta State changes intended for the session, which may accompany any request and
 *   are applied by whatever consumes the queue; empty when none were set.
 */
data class LiveRequest(
  val input: LiveInput? = null,
  val close: Boolean = false,
  val stateDelta: Map<String, Any> = emptyMap(),
) {
  init {
    require(!close || input == null) {
      "A close request carries no input; send the input first, then close."
    }
  }

  /**
   * Returns a [Builder] initialized with this instance's properties, primarily for Java callers.
   * Prefer it over `copy` from Java: `copy` takes every property positionally, so its signature
   * changes whenever a property is added.
   */
  @AdkJavaInteropApi
  fun toBuilder(): Builder = Builder().input(input).close(close).stateDelta(stateDelta)

  /**
   * Fluent builder for [LiveRequest], provided primarily for Java callers. Any property left unset
   * falls back to the same default as the constructor.
   */
  @AdkJavaInteropApi
  @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
  class Builder {
    private var input: LiveInput? = null
    private var close: Boolean = false
    private var stateDelta: Map<String, Any> = emptyMap()

    fun input(input: LiveInput?): Builder = apply { this.input = input }

    fun close(close: Boolean): Builder = apply { this.close = close }

    // Copied: the request can wait in the queue after the caller's map changes.
    fun stateDelta(stateDelta: Map<String, Any>): Builder = apply {
      this.stateDelta = stateDelta.toMap()
    }

    fun build(): LiveRequest = LiveRequest(input = input, close = close, stateDelta = stateDelta)
  }

  companion object {
    @AdkJavaInteropApi @JvmStatic fun builder(): Builder = Builder()
  }
}
