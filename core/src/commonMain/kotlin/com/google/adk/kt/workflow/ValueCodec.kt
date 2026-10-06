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
import com.google.adk.kt.serialization.adkJson
import com.google.adk.kt.serialization.jsonElementToAny
import com.google.adk.kt.types.Schema
import com.google.genai.kotlin.types.ByteArrayAsBase64Serializer
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull

/**
 * Converts values to [kType] where they enter typed code, such as a node input. A value that is
 * already a [T] passes through, checked element by element for a collection or map.
 */
@OptIn(ExperimentalWorkflowApi::class, FrameworkInternalApi::class)
internal class ValueCodec<T>(val kType: KType) {
  private val classifier = kType.classifier as? KClass<*>

  @Suppress("UNCHECKED_CAST")
  private val serializer: KSerializer<T>? = SchemaUtils.serializerFor(kType) as KSerializer<T>?

  /** The serializer for [kType]'s JSON form, which writes a `ByteArray` as base64. */
  @Suppress("UNCHECKED_CAST")
  private val jsonSerializer: KSerializer<Any?>? =
    (if (classifier == ByteArray::class) ByteArrayAsBase64Serializer else serializer)
      as KSerializer<Any?>?

  /** The schema of [kType]'s JSON form, which describes a port without being enforced. */
  val schema: Schema? by lazy { SchemaUtils.inferSchema(kType) }

  private val elementCodec: ValueCodec<Any?>? =
    kType.arguments.singleOrNull()?.type?.let { ValueCodec(it) }

  private val mapCodecs = MapCodecs(kType)

  /**
   * Returns the JSON form of [value] as a [T] under [kType]'s serializer: plain maps, lists, and
   * scalars, with a `ByteArray` as base64 and an `Any` value only if it is JSON-native. Throws
   * [IllegalArgumentException], naming [what] and not [value], when there is no JSON form.
   */
  fun encode(value: Any?, what: String): Any? {
    if (value == null) return null
    val typed = coerce(value, what)
    val serializer =
      jsonSerializer
        ?: throw IllegalArgumentException(
          "$what has type $kType, which has no serializer. Mark it @Serializable."
        )
    val element =
      try {
        explicitNullsJson.encodeToJsonElement(serializer, typed)
      } catch (e: IllegalArgumentException) {
        // Also catches SerializationException; its message can quote the value, so no cause.
        throw IllegalArgumentException("$what has no JSON form as $kType.")
      }
    if (element is JsonNull) throw IllegalArgumentException("$what encodes to null as $kType.")
    return jsonElementToAny(element)
  }

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
          val newKey = mapCodecs.keyCodec?.coerceAny(key, "key of $what") ?: key
          val newValue =
            mapCodecs.entryValueCodec?.coerceAny(entryValue, "value of $what") ?: entryValue
          if (newKey !== key || newValue !== entryValue) changed = true
          newKey to newValue
        }
      return if (changed) entries else value
    }
    // Erasure leaves the type arguments of any other generic class to the static edge check.
    return value
  }
}

/** [adkJson] that writes a required `null` property, so a class keeps every field it requires. */
@OptIn(FrameworkInternalApi::class)
private val explicitNullsJson: Json = Json(adkJson) { explicitNulls = true }

/** The codecs of a map type's key and value, or null when [kType] has no such argument. */
@OptIn(ExperimentalWorkflowApi::class)
private class MapCodecs(kType: KType) {
  private val arguments = kType.arguments.takeIf { it.size == 2 }

  val keyCodec: ValueCodec<Any?>? = arguments?.get(0)?.type?.let { ValueCodec(it) }

  val entryValueCodec: ValueCodec<Any?>? = arguments?.get(1)?.type?.let { ValueCodec(it) }
}
