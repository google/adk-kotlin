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

import com.google.adk.kt.SchemaUtils
import com.google.adk.kt.annotations.AdkJavaInteropApi
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
   * Returns a [Builder] initialized with this instance's properties, primarily for Java callers.
   * Prefer it over `copy` from Java: `copy` takes every property positionally, so its signature
   * changes whenever a property is added.
   */
  @AdkJavaInteropApi
  fun toBuilder(): Builder = Builder().confirmed(confirmed).payload(payload).hint(hint)

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
    @AdkJavaInteropApi @JvmStatic fun builder(): Builder = Builder()

    /** Key for the 'confirmed' field in the serialized map. */
    const val CONFIRMED_KEY = "confirmed"

    /** Key for the 'payload' field in the serialized map. */
    const val PAYLOAD_KEY = "payload"

    /** Key for the 'hint' field in the serialized map. */
    const val HINT_KEY = "hint"
  }
}

/**
 * The single key under which the ADK client nests a [ToolConfirmation], as a JSON string or map.
 */
private const val WRAPPED_RESPONSE_KEY = "response"

/** The keys a [ToolConfirmation] answer may hold. */
private val CONFIRMATION_KEYS =
  setOf(ToolConfirmation.CONFIRMED_KEY, ToolConfirmation.PAYLOAD_KEY, ToolConfirmation.HINT_KEY)

/**
 * Parses a user's answer to a confirmation request, given either as the [ToolConfirmation] fields
 * directly or inside the ADK client's `{"response": ...}` wrapper, as a JSON string or a map.
 * Returns null if [response] is neither. As in Python's `ToolConfirmation` model, an answer without
 * `confirmed` parses as `confirmed = false`, and an answer with any key other than `confirmed`,
 * `hint`, or `payload` is not a confirmation. Besides a Boolean, `confirmed` may be the string
 * `"true"` or `"false"` (in any case) or the number 0 or 1, a subset of what Python's model
 * accepts.
 */
internal fun parseToolConfirmation(response: Map<*, *>?): ToolConfirmation? {
  if (response == null) return null
  if (response.size != 1 || WRAPPED_RESPONSE_KEY !in response) return confirmationFrom(response)
  val wrapped = response[WRAPPED_RESPONSE_KEY]
  val fields = if (wrapped is String) SchemaUtils.readJson(wrapped).getOrNull() else wrapped
  return (fields as? Map<*, *>)?.let(::confirmationFrom)
}

/** Returns the confirmation that [fields] hold, or null if they are not a confirmation's fields. */
private fun confirmationFrom(fields: Map<*, *>): ToolConfirmation? {
  if (!CONFIRMATION_KEYS.containsAll(fields.keys)) return null
  val confirmed = booleanFrom(fields[ToolConfirmation.CONFIRMED_KEY] ?: false)
  val hint = fields[ToolConfirmation.HINT_KEY]
  if (confirmed == null || hint !is String?) return null
  return ToolConfirmation(
    confirmed = confirmed,
    payload = fields[ToolConfirmation.PAYLOAD_KEY],
    hint = hint,
  )
}

/**
 * Returns [value] as a Boolean, reading `"true"` and `"false"` case-insensitively and the numbers 0
 * and 1, or null if [value] cannot be converted.
 */
private fun booleanFrom(value: Any): Boolean? =
  when (value) {
    is Boolean -> value
    is String -> value.lowercase().toBooleanStrictOrNull()
    is Number ->
      when (value.toDouble()) {
        0.0 -> false
        1.0 -> true
        else -> null
      }
    else -> null
  }
