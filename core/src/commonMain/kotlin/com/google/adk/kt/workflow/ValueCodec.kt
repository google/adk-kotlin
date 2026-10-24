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
import com.google.adk.kt.ValueCoercer
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.events.Event
import com.google.adk.kt.serialization.LenientByteArraySerializer
import com.google.adk.kt.serialization.LenientEpochMillisSerializer
import com.google.adk.kt.serialization.adkJson
import com.google.adk.kt.serialization.anyToJsonElement
import com.google.adk.kt.serialization.jsonElementToAny
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import com.google.genai.kotlin.types.ByteArrayAsBase64Serializer
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Reads a port's values as its type [T] and writes their JSON form. */
internal interface PortCodec<T> {
  /** The class of the port's type, or null when the type has none. */
  val classifier: KClass<*>?

  /** The schema the port's type infers, which describes the port without being enforced. */
  val schema: Schema?

  /** The schema of the port's output JSON form, when it differs from [schema]. */
  val outputSchema: Schema?
    get() = schema

  /**
   * Returns [value] as a [T], or throws [NodeInputValidationException] that names [what], not
   * [value].
   */
  fun coerce(value: Any?, what: String): T

  /**
   * Returns the JSON form of [value] as a [T], or throws [IllegalArgumentException] that names
   * [what], not [value].
   */
  fun encode(value: Any?, what: String): Any?
}

/**
 * Reads a value as a [T] where it enters typed code and writes a [T] as its JSON form. Another
 * value decodes from its JSON form, converting only a quoted literal of a scalar's type and a whole
 * number for an integer; a message is read as text or JSON.
 */
@OptIn(ExperimentalWorkflowApi::class, FrameworkInternalApi::class)
internal class ValueCodec<T>(val kType: KType) : PortCodec<T> {
  override val classifier = kType.classifier as? KClass<*>

  @Suppress("UNCHECKED_CAST")
  private val serializer: KSerializer<T>? = SchemaUtils.serializerFor(kType) as KSerializer<T>?

  /** The serializer for [kType]'s JSON form, which writes a `ByteArray` as base64. */
  @Suppress("UNCHECKED_CAST")
  private val jsonSerializer: KSerializer<Any?>? =
    (if (classifier == ByteArray::class) ByteArrayAsBase64Serializer else serializer)
      as KSerializer<Any?>?

  /**
   * The schema of [kType]'s JSON form for an input: defaulted fields are optional, while a nullable
   * field without a default stays required although [coerce] reads its absence as null.
   */
  override val schema: Schema? by lazy { SchemaUtils.inferSchema(kType) }

  /** The schema of [kType]'s JSON form as [encode] writes it, with every field present. */
  override val outputSchema: Schema? by lazy { SchemaUtils.inferSchema(kType, forOutput = true) }

  /**
   * ADK model types and [Event] reject unknown keys, so a value of another class fails to decode.
   */
  private val decodeJson: Json =
    if (classifier == Event::class || serializer?.descriptor?.serialName?.isAdkTypeName() == true) {
      strictJson
    } else {
      adkJson
    }

  private val elementCodec: ValueCodec<Any?>? =
    kType.arguments.singleOrNull()?.type?.let { ValueCodec(it) }

  private val mapCodecs = MapCodecs(kType)

  /**
   * Returns the JSON form of [value] as a [T] under [kType]'s serializer: plain maps, lists, and
   * scalars, with a `ByteArray` as base64 and an `Any` value only if it is JSON-native. Throws
   * [IllegalArgumentException], naming [what] and not [value], when [value] is not a [T] or has no
   * JSON form.
   */
  override fun encode(value: Any?, what: String): Any? {
    if (value == null) return null
    val typed =
      try {
        coerce(value, what)
      } catch (e: NodeInputValidationException) {
        // A value of another type is a bad output, not a bad node input.
        throw IllegalArgumentException(e.message, e)
      }
    val serializer =
      jsonSerializer
        ?: throw IllegalArgumentException(
          "$what has type $kType, which has no serializer. Mark it @Serializable."
        )
    val element =
      try {
        outputJson.encodeToJsonElement(serializer, typed)
      } catch (e: IllegalArgumentException) {
        // Also catches SerializationException; its message can quote the value, so no cause.
        throw IllegalArgumentException("$what has no JSON form as $kType.")
      }
    if (element is JsonNull) throw IllegalArgumentException("$what encodes to null as $kType.")
    return jsonElementToAny(element)
  }

  @Suppress("UNCHECKED_CAST")
  override fun coerce(value: Any?, what: String): T = coerceAny(value, what) as T

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
      // A rebuilt collection of another class falls through to decoding.
      if (coerced === value || classifier.isInstance(coerced)) return coerced
    }
    if (value is Content) {
      val takesString =
        classifier == String::class ||
          classifier == CharSequence::class ||
          classifier == ByteArray::class
      val contentSchema = if (takesString) Schema(type = Type.STRING) else Schema()
      return coerceAny(ValueCoercer.coerceContent(value, contentSchema), what)
    }
    val serializer = jsonSerializer
    if (serializer != null) {
      return try {
        decodeJson.decodeFromJsonElement(
          serializer,
          readLiterals(jsonOf(value), serializer.descriptor),
        )
      } catch (e: IllegalArgumentException) {
        // Also catches SerializationException; its message can quote the value, so no cause.
        throw NodeInputValidationException("validation error: $what does not match $kType.")
      }
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

/** The JSON element of a JSON-shaped value, reading any collection as an array. */
@OptIn(FrameworkInternalApi::class)
private fun jsonOf(value: Any?): JsonElement =
  when (value) {
    is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to jsonOf(v) })
    is Collection<*> -> JsonArray(value.map { jsonOf(it) })
    else -> anyToJsonElement(value)
  }

/**
 * Serializers that declare a scalar kind but read other JSON too, so the table leaves them alone.
 */
@OptIn(FrameworkInternalApi::class)
private val OWN_READERS: Set<String> =
  setOf(
    JsonPrimitive.serializer().descriptor.serialName,
    LenientByteArraySerializer.descriptor.serialName,
    LenientEpochMillisSerializer.descriptor.serialName,
  )

/**
 * Applies the port coercion table to [element] as [descriptor] reads it: a quoted JSON literal of a
 * scalar's type becomes that literal and a whole number becomes an integer. Throws
 * [IllegalArgumentException] for any other scalar of the wrong JSON type, such as `"TRUE"`.
 */
@OptIn(FrameworkInternalApi::class)
private fun readLiterals(element: JsonElement, descriptor: SerialDescriptor): JsonElement {
  if (element is JsonNull || descriptor.serialName.removeSuffix("?") in OWN_READERS) return element
  if (descriptor.isInline) return readLiterals(element, descriptor.getElementDescriptor(0))
  fun scalar(read: (Any?) -> Any?): JsonElement {
    // Anything but a primitive is left to the serializer, which rejects it or reads it its way.
    if (element !is JsonPrimitive) return element
    return anyToJsonElement(
      read(jsonElementToAny(element)) ?: throw IllegalArgumentException("not a ${descriptor.kind}")
    )
  }
  return when (descriptor.kind) {
    PrimitiveKind.INT,
    PrimitiveKind.LONG,
    PrimitiveKind.SHORT,
    PrimitiveKind.BYTE -> scalar(ValueCoercer::integerLiteral)
    PrimitiveKind.FLOAT,
    PrimitiveKind.DOUBLE -> scalar(ValueCoercer::numberLiteral)
    PrimitiveKind.BOOLEAN -> scalar(ValueCoercer::booleanLiteral)
    PrimitiveKind.STRING,
    PrimitiveKind.CHAR,
    SerialKind.ENUM -> scalar { it as? String }
    StructureKind.CLASS ->
      if (element !is JsonObject) element
      else
        JsonObject(
          element.mapValues { (key, value) ->
            val index = descriptor.getElementIndex(key)
            if (index == CompositeDecoder.UNKNOWN_NAME) value
            else readLiterals(value, descriptor.getElementDescriptor(index))
          }
        )
    StructureKind.LIST ->
      if (element !is JsonArray) element
      else JsonArray(element.map { readLiterals(it, descriptor.getElementDescriptor(0)) })
    StructureKind.MAP ->
      if (element !is JsonObject) element
      else
        JsonObject(element.mapValues { readLiterals(it.value, descriptor.getElementDescriptor(1)) })
    // Polymorphic and contextual values decode by their own rules.
    else -> element
  }
}

/**
 * [adkJson] that writes every property, `null` and default values included, so a value on either
 * port keeps its class's shape, also when an explicit schema checks it.
 */
@OptIn(FrameworkInternalApi::class)
private val outputJson: Json =
  Json(adkJson) {
    explicitNulls = true
    encodeDefaults = true
  }

/** The package prefix of ADK's model types, such as [Content] and `Part`. */
private val ADK_TYPES_PREFIX =
  Content.serializer().descriptor.serialName.substringBeforeLast('.') + "."

private fun String.isAdkTypeName(): Boolean = startsWith(ADK_TYPES_PREFIX)

/** [adkJson] that fails on a key the decoded class does not declare. */
@OptIn(FrameworkInternalApi::class)
private val strictJson: Json = Json(adkJson) { ignoreUnknownKeys = false }

/** The codecs of a map type's key and value, or null when [kType] has no such argument. */
@OptIn(ExperimentalWorkflowApi::class)
private class MapCodecs(kType: KType) {
  private val arguments = kType.arguments.takeIf { it.size == 2 }

  val keyCodec: ValueCodec<Any?>? = arguments?.get(0)?.type?.let { ValueCodec(it) }

  val entryValueCodec: ValueCodec<Any?>? = arguments?.get(1)?.type?.let { ValueCodec(it) }
}
