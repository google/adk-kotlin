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
package com.google.adk.kt.events

import kotlin.jvm.JvmStatic
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/** Represents a tool confirmation configuration. */
@Serializable
data class ToolConfirmation(
  /** Whether the tool execution is confirmed. */
  val confirmed: Boolean,
  /** The confirmation payload. */
  @Contextual val payload: Any? = null,
  /** The hint for the confirmation. */
  val hint: String? = null,
) {
  /**
   * Fluent builder for [ToolConfirmation], provided primarily for Java callers. Any property left
   * unset falls back to the same default as the constructor.
   */
  @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
  class Builder {
    private var confirmed: Boolean? = null
    private var payload: Any? = null
    private var hint: String? = null

    fun confirmed(confirmed: Boolean): Builder = apply { this.confirmed = confirmed }

    fun payload(payload: Any?): Builder = apply { this.payload = payload }

    fun hint(hint: String?): Builder = apply { this.hint = hint }

    fun build(): ToolConfirmation =
      ToolConfirmation(
        confirmed =
          checkNotNull(confirmed) { "ToolConfirmation.Builder requires confirmed to be set." },
        payload = payload,
        hint = hint,
      )
  }

  companion object {
    @JvmStatic fun builder(): Builder = Builder()

    /** Key for the 'confirmed' field in the serialized map. */
    const val CONFIRMED_KEY = "confirmed"

    /** Key for the 'payload' field in the serialized map. */
    const val PAYLOAD_KEY = "payload"

    /** Key for the 'hint' field in the serialized map. */
    const val HINT_KEY = "hint"
  }
}
