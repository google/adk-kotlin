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

package com.google.adk.kt.workflow

import com.google.adk.kt.SchemaUtils
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Schema
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlinx.serialization.KSerializer

/**
 * Converts values to [kType] where they enter typed code, such as a node input. A value that is
 * already a [T] passes through, checked element by element for a collection or map, and a text
 * [Content] reads as a `String`.
 */
@OptIn(ExperimentalWorkflowApi::class, FrameworkInternalApi::class)
internal class ValueCodec<T>(val kType: KType) {
  private val classifier = kType.classifier as? KClass<*>

  @Suppress("UNCHECKED_CAST")
  val serializer: KSerializer<T>? = SchemaUtils.serializerFor(kType) as KSerializer<T>?

  /** The schema of [kType]'s JSON form, which describes a port without being enforced. */
  val schema: Schema? by lazy { SchemaUtils.inferSchema(kType) }

  private val elementCodec: ValueCodec<Any?>? =
    kType.arguments.singleOrNull()?.type?.let { ValueCodec(it) }

  private val keyCodec: ValueCodec<Any?>? = mapArgumentCodec(0)

  private val entryValueCodec: ValueCodec<Any?>? = mapArgumentCodec(1)

  private fun mapArgumentCodec(index: Int): ValueCodec<Any?>? =
    if (kType.arguments.size == 2) kType.arguments[index].type?.let { ValueCodec(it) } else null

  /**
   * Returns [value] as a [T], or throws [NodeInputValidationException] that names [what], not
   * [value].
   */
  @Suppress("UNCHECKED_CAST") fun coerce(value: Any?, what: String): T = coerceAny(value, what) as T

  private fun coerceAny(value: Any?, what: String): Any? {
    if (value == null) {
      if (kType.isMarkedNullable) return null
      // A predecessor with no output feeds a Unit input.
      if (classifier == Unit::class) return Unit
      throw NodeInputValidationException(
        "validation error: $what does not accept null (expected $kType)."
      )
    }
    if (classifier == null) return value
    if (value is Content && classifier == String::class) return value.text()
    if (classifier.isInstance(value)) {
      val coerced = coerceElements(value, what)
      // A collection rebuilt around converted elements must still be a [T].
      if (coerced === value || classifier.isInstance(coerced)) return coerced
    }
    val actualTypeName = value::class.simpleName ?: "unknown"
    throw NodeInputValidationException(
      "validation error: $what expected $kType, but got $actualTypeName."
    )
  }

  /** Coerces the elements of a collection or map, returning [value] itself when none change. */
  private fun coerceElements(value: Any, what: String): Any {
    if (value is Collection<*> && elementCodec != null) {
      val elements = value.mapIndexed { i, element -> elementCodec.coerceAny(element, "$what[$i]") }
      if (elements.zip(value).all { (new, old) -> new === old }) return value
      return if (value is Set<*>) elements.toSet() else elements
    }
    if (value is Map<*, *> && kType.arguments.size == 2) {
      var changed = false
      val entries =
        value.entries.associateTo(LinkedHashMap()) { (key, entryValue) ->
          val newKey = keyCodec?.coerceAny(key, "key of $what") ?: key
          val newValue =
            if (entryValueCodec == null) entryValue
            else entryValueCodec.coerceAny(entryValue, "value of $what")
          if (newKey !== key || newValue !== entryValue) changed = true
          newKey to newValue
        }
      return if (changed) entries else value
    }
    // Erasure leaves the type arguments of any other generic class to the static edge check.
    return value
  }
}
