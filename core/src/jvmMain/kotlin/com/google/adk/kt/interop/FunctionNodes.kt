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

@file:OptIn(AdkJavaInteropApi::class, ExperimentalWorkflowApi::class)

package com.google.adk.kt.interop

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JavaType
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.deser.AbstractDeserializer
import com.fasterxml.jackson.databind.deser.BeanDeserializerBase
import com.fasterxml.jackson.databind.deser.DefaultDeserializationContext
import com.fasterxml.jackson.databind.deser.ValueInstantiator
import com.fasterxml.jackson.databind.deser.impl.UnsupportedTypeDeserializer
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.databind.ser.impl.UnknownSerializer
import com.fasterxml.jackson.databind.ser.impl.UnsupportedTypeSerializer
import com.fasterxml.jackson.databind.ser.std.BeanSerializerBase
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.events.Event
import com.google.adk.kt.ids.Uuid
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Schema
import com.google.adk.kt.workflow.FunctionNode
import com.google.adk.kt.workflow.NodeBody
import com.google.adk.kt.workflow.NodeInputValidationException
import com.google.adk.kt.workflow.NodeInterruptedException
import com.google.adk.kt.workflow.PortCodec
import com.google.adk.kt.workflow.RequestInput
import com.google.adk.kt.workflow.ValueCodec
import com.google.adk.kt.workflow.WorkflowConfigurationException
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.typeOf

/**
 * Creates [FunctionNode]s from Java with a type per port. Jackson reads each input into the input
 * type and writes each output as JSON, so records and POJOs work as ports; `Object.class` leaves a
 * port untyped, a `Content` or `Event` port passes values as they are, and a port typed this way
 * infers no schema. To run a node on each item of a list, wrap it with [FunctionNode.parallelOf].
 */
@AdkJavaInteropApi
@ExperimentalWorkflowApi
object FunctionNodes {
  /**
   * Returns a node named [name] that runs [body] on each input read as [inputType] and writes its
   * [outputType] output as JSON.
   *
   * @throws WorkflowConfigurationException if Jackson cannot read and write [inputType] or write
   *   [outputType], or if a port type has no single concrete JSON shape; a field type Jackson
   *   cannot handle fails on the first value instead.
   */
  @JvmStatic
  fun <I, O> of(
    name: String,
    inputType: Class<I & Any>,
    outputType: Class<O & Any>,
    body: NodeBody<I, O>,
  ): FunctionNode<I, O> = node(name, javaType(inputType), javaType(outputType), body)

  /** Like [of] with classes, for generic port types such as `List<String>`. */
  @JvmStatic
  fun <I, O> of(
    name: String,
    inputType: TypeReference<I>,
    outputType: TypeReference<O>,
    body: NodeBody<I, O>,
  ): FunctionNode<I, O> = node(name, javaType(inputType), javaType(outputType), body)

  /** Like [of] with classes, for a generic output type. */
  @JvmStatic
  fun <I, O> of(
    name: String,
    inputType: Class<I & Any>,
    outputType: TypeReference<O>,
    body: NodeBody<I, O>,
  ): FunctionNode<I, O> = node(name, javaType(inputType), javaType(outputType), body)

  /** Like [of] with classes, for a generic input type. */
  @JvmStatic
  fun <I, O> of(
    name: String,
    inputType: TypeReference<I>,
    outputType: Class<O & Any>,
    body: NodeBody<I, O>,
  ): FunctionNode<I, O> = node(name, javaType(inputType), javaType(outputType), body)

  /**
   * Returns a [FunctionNode.Builder] whose node reads inputs as [inputType] and writes [outputType]
   * outputs as JSON, for setting properties that [of] does not take.
   *
   * @throws WorkflowConfigurationException if Jackson cannot read and write [inputType] or write
   *   [outputType], or if a port type has no single concrete JSON shape.
   */
  @JvmStatic
  fun <I, O> builder(
    inputType: Class<I & Any>,
    outputType: Class<O & Any>,
  ): FunctionNode.Builder<I, O> = typedBuilder(javaType(inputType), javaType(outputType), BUILDER)

  /** Like [builder] with classes, for generic port types such as `List<String>`. */
  @JvmStatic
  fun <I, O> builder(
    inputType: TypeReference<I>,
    outputType: TypeReference<O>,
  ): FunctionNode.Builder<I, O> = typedBuilder(javaType(inputType), javaType(outputType), BUILDER)

  /** Like [builder] with classes, for a generic output type. */
  @JvmStatic
  fun <I, O> builder(
    inputType: Class<I & Any>,
    outputType: TypeReference<O>,
  ): FunctionNode.Builder<I, O> = typedBuilder(javaType(inputType), javaType(outputType), BUILDER)

  /** Like [builder] with classes, for a generic input type. */
  @JvmStatic
  fun <I, O> builder(
    inputType: TypeReference<I>,
    outputType: Class<O & Any>,
  ): FunctionNode.Builder<I, O> = typedBuilder(javaType(inputType), javaType(outputType), BUILDER)

  /**
   * Asks the user for input like [NodeBody.requestInput], and sends [payload] as JSON through the
   * same mapper as typed ports, so a record or POJO reaches the client in its JSON form.
   *
   * @throws IllegalArgumentException if Jackson cannot write [payload].
   */
  @JvmStatic
  @JvmOverloads
  fun <T> requestInput(
    interruptId: String = Uuid.random(),
    message: String? = null,
    payload: Any? = null,
    responseSchema: Schema? = null,
  ): T {
    val json = payload?.let {
      val tree: JsonNode =
        try {
          MAPPER.valueToTree(it)
        } catch (e: IllegalArgumentException) {
          // Jackson's message can quote the value, so no cause.
          throw IllegalArgumentException(
            "payload of requestInput has no JSON form as ${it.javaClass.simpleName}."
          )
        }
      jsonNative(tree)
    }
    throw NodeInterruptedException(RequestInput(interruptId, message, json, responseSchema))
  }

  /**
   * Asks the user for input with [request] and sends its payload as JSON, like the other
   * `requestInput` overload. For example, build the request with [RequestInput.builder] to set only
   * a message.
   *
   * @throws IllegalArgumentException if Jackson cannot write the payload of [request].
   */
  @JvmStatic
  fun <T> requestInput(request: RequestInput): T =
    requestInput(request.interruptId, request.message, request.payload, request.responseSchema)

  private fun <I, O> node(
    name: String,
    inputType: JavaType,
    outputType: JavaType,
    body: NodeBody<I, O>,
  ): FunctionNode<I, O> =
    typedBuilder<I, O>(inputType, outputType, "node '$name'").name(name).body(body).build()

  private fun <I, O> typedBuilder(
    inputType: JavaType,
    outputType: JavaType,
    node: String,
  ): FunctionNode.Builder<I, O> =
    FunctionNode.Builder.create<I, O>()
      // An input port also writes: an explicit input schema checks the input in its JSON form.
      .inputCodec(portCodec<I>(inputType, "the input of $node", ::requireRoundTrip))
      .outputCodec(portCodec<O>(outputType, "the output of $node", ::requireWritable))
}

private const val BUILDER = "a FunctionNodes.builder node"

/**
 * One mapper for every Java port: unknown properties are ignored, null fields are written, and
 * `java.time` values are read and written as ISO-8601 strings.
 */
private val MAPPER: ObjectMapper =
  JsonMapper.builder()
    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
    .addModule(JavaTimeModule())
    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    .disable(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS)
    .build()

/** Port classes that keep the codec of the matching Kotlin port type. */
private val KOTLIN_CODEC_TYPES: Map<Class<*>, KType> =
  mapOf(
    Any::class.java to typeOf<Any?>(),
    Content::class.java to typeOf<Content?>(),
    Event::class.java to typeOf<Event?>(),
  )

private fun javaType(type: Class<*>): JavaType =
  MAPPER.typeFactory.constructType(type.kotlin.javaObjectType)

private fun javaType(type: TypeReference<*>): JavaType = MAPPER.typeFactory.constructType(type)

private fun <T> portCodec(
  type: JavaType,
  what: String,
  check: (JavaType, String) -> Unit,
): PortCodec<T> {
  KOTLIN_CODEC_TYPES[type.rawClass]?.let {
    return ValueCodec(it)
  }
  check(type, what)
  return JacksonCodec(type)
}

/** Throws [WorkflowConfigurationException] unless Jackson can both read and write a [type]. */
private fun requireRoundTrip(type: JavaType, what: String) {
  requireReadable(type, what)
  requireWritable(type, what)
}

/** Throws [WorkflowConfigurationException] unless Jackson can create a [type] from JSON. */
private fun requireReadable(type: JavaType, what: String) {
  if (type.rawClass == Any::class.java) return
  if (type.isContainerType) {
    type.keyType?.let { requireKeyReadable(it, "a key of $what") }
    requireReadable(type.contentType, "an element of $what")
  } else {
    requireConcrete(type, what)
  }
  val lookup = runCatching { deserializationContext().findRootValueDeserializer(type) }
  val deserializer = lookup.getOrNull()
  val readable =
    deserializer != null &&
      deserializer !is AbstractDeserializer &&
      deserializer !is UnsupportedTypeDeserializer &&
      (deserializer as? ValueInstantiator.Gettable)?.valueInstantiator?.canInstantiate() != false
  if (!readable) {
    throw WorkflowConfigurationException(
      "${what.replaceFirstChar { it.uppercase() }} has type ${type.toCanonical()}, which " +
        "Jackson cannot create from JSON${reason(lookup)}. Use a record, a class with a " +
        "constructor Jackson can call, or Object.class."
    )
  }
  // A type Jackson writes as an object must also be creatable from one; a @JsonValue type writes
  // a single value and is read back through its delegate.
  val instantiator = (deserializer as? BeanDeserializerBase)?.valueInstantiator
  if (instantiator != null && writesObject(type) && !createsFromObject(instantiator)) {
    throw WorkflowConfigurationException(
      "${what.replaceFirstChar { it.uppercase() }} has type ${type.toCanonical()}, which " +
        "Jackson can create only from a string or number, not from the JSON object it writes. " +
        "Use a record, a class with a no-argument constructor, or a @JsonCreator constructor " +
        "with @JsonProperty parameters."
    )
  }
}

private fun writesObject(type: JavaType): Boolean =
  runCatching { MAPPER.serializerProviderInstance.findValueSerializer(type) }.getOrNull() is
    BeanSerializerBase

/** Whether [instantiator] builds a bean from a JSON object, the form a port writes. */
private fun createsFromObject(instantiator: ValueInstantiator): Boolean =
  instantiator.canCreateUsingDefault() ||
    instantiator.canCreateFromObjectWith() ||
    instantiator.canCreateUsingDelegate()

/**
 * Throws [WorkflowConfigurationException] unless [type] has one concrete JSON shape: a polymorphic
 * type is rejected, and so is an interface or abstract class without a creator Jackson can call.
 */
private fun requireConcrete(type: JavaType, what: String) {
  val problem =
    when {
      runCatching { MAPPER.serializerProviderInstance.findTypeSerializer(type) }.getOrNull() !=
        null -> "is polymorphic (@JsonTypeInfo)"
      type.isAbstract && !type.isEnumType && !type.isPrimitive && !hasBeanCreator(type) ->
        "is an interface or abstract class without a Jackson creator"
      else -> return
    }
  val fix =
    when {
      Number::class.java.isAssignableFrom(type.rawClass) ->
        "Declare the port as Long, Double or BigDecimal, or use Object.class."
      CharSequence::class.java.isAssignableFrom(type.rawClass) -> "Declare the port as String."
      else ->
        "Declare the port as a concrete record, wrap the hierarchy in one, or use Object.class."
    }
  throw WorkflowConfigurationException(
    "${what.replaceFirstChar { it.uppercase() }} has type ${type.toCanonical()}, which " +
      "$problem. $fix"
  )
}

/** Whether Jackson creates [type] as a bean through a creator, such as an AutoValue factory. */
private fun hasBeanCreator(type: JavaType): Boolean {
  val deserializer = runCatching { deserializationContext().findRootValueDeserializer(type) }
  return (deserializer.getOrNull() as? BeanDeserializerBase)?.valueInstantiator?.canInstantiate() ==
    true
}

/** Throws [WorkflowConfigurationException] unless Jackson can read a map key as a [type]. */
private fun requireKeyReadable(type: JavaType, what: String) {
  val lookup = runCatching { deserializationContext().findKeyDeserializer(type, null) }
  if (lookup.getOrNull() == null) {
    throw WorkflowConfigurationException(
      "${what.replaceFirstChar { it.uppercase() }} has type ${type.toCanonical()}, which " +
        "Jackson cannot read as a map key${reason(lookup)}. Use a String, a number, an enum, " +
        "or Object.class."
    )
  }
}

private fun deserializationContext(): DefaultDeserializationContext =
  (MAPPER.deserializationContext as DefaultDeserializationContext).createDummyInstance(
    MAPPER.deserializationConfig
  )

/** Throws [WorkflowConfigurationException] unless Jackson can write a [type] as JSON. */
private fun requireWritable(type: JavaType, what: String) {
  if (type.rawClass == Any::class.java) return
  if (type.isContainerType) {
    requireWritable(type.contentType, "an element of $what")
    return
  }
  requireConcrete(type, what)
  val lookup = runCatching { MAPPER.serializerProviderInstance.findValueSerializer(type) }
  val serializer = lookup.getOrNull()
  // An UnknownSerializer stands for a class with no properties, which fails when written.
  if (
    serializer == null || serializer is UnsupportedTypeSerializer || serializer is UnknownSerializer
  ) {
    throw WorkflowConfigurationException(
      "${what.replaceFirstChar { it.uppercase() }} has type ${type.toCanonical()}, which " +
        "Jackson cannot write as JSON${reason(lookup)}. Use a record, a class with getters, or " +
        "Object.class."
    )
  }
}

private fun reason(lookup: Result<*>): String =
  lookup.exceptionOrNull()?.message?.lineSequence()?.firstOrNull()?.let { " ($it)" }.orEmpty()

/** Reads a Java port's values into [type] and writes them as JSON with Jackson. */
private class JacksonCodec<T>(private val type: JavaType) : PortCodec<T> {
  override val classifier: KClass<*> = type.rawClass.kotlin

  // Schemas are not inferred from Java types yet.
  override val schema: Schema? = null

  @Suppress("UNCHECKED_CAST")
  override fun coerce(value: Any?, what: String): T {
    if (value == null) return null as T
    if (value is Content || value is Event) {
      val got = value.javaClass.simpleName
      throw NodeInputValidationException(
        "validation error: $what expected ${type.toCanonical()}, but got $got."
      )
    }
    if (hasType(value, type)) return value as T
    return try {
      MAPPER.convertValue(value, type)
    } catch (e: IllegalArgumentException) {
      // Jackson's message can quote the value, so no cause.
      throw NodeInputValidationException(
        "validation error: $what does not match ${type.toCanonical()}."
      )
    }
  }

  override fun encode(value: Any?, what: String): Any? {
    if (value == null) return null
    require(type.rawClass.isInstance(value)) {
      "$what expected ${type.toCanonical()}, but got ${value.javaClass.simpleName}."
    }
    val tree: JsonNode =
      try {
        MAPPER.valueToTree(value)
      } catch (e: IllegalArgumentException) {
        // Jackson's message can quote the value, so no cause.
        throw IllegalArgumentException("$what has no JSON form as ${type.toCanonical()}.")
      }
    return jsonNative(tree)
  }
}

/**
 * Whether [value] already has [type], down to each element and map entry, so it needs no Jackson
 * pass.
 */
private fun hasType(value: Any, type: JavaType): Boolean =
  when {
    !type.rawClass.isInstance(value) -> false
    type.isCollectionLikeType ->
      (value as Collection<*>).all { it == null || hasType(it, type.contentType) }
    type.isMapLikeType ->
      (value as Map<*, *>).all { (key, item) ->
        (key == null || hasType(key, type.keyType)) &&
          (item == null || hasType(item, type.contentType))
      }
    type.isArrayType ->
      type.contentType.isPrimitive ||
        (value as Array<*>).all { it == null || hasType(it, type.contentType) }
    else -> !type.isContainerType
  }

/** The value of [node] in the forms nodes output: maps, lists, `Long`, `Double`, and scalars. */
private fun jsonNative(node: JsonNode): Any? =
  when {
    node.isObject ->
      node.properties().associateTo(LinkedHashMap()) { (key, value) -> key to jsonNative(value) }
    node.isArray -> node.map { jsonNative(it) }
    node.isIntegralNumber && node.canConvertToLong() -> node.longValue()
    // Widening a float adds digits it never had.
    node.isFloat -> node.floatValue().toString().toDouble()
    node.isNumber -> node.doubleValue()
    node.isBoolean -> node.booleanValue()
    node.isNull || node.isMissingNode -> null
    else -> node.asText()
  }
