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

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.BeanDescription
import com.fasterxml.jackson.databind.BeanProperty
import com.fasterxml.jackson.databind.DeserializationConfig
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JavaType
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.KeyDeserializer
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import com.fasterxml.jackson.databind.cfg.CoercionAction
import com.fasterxml.jackson.databind.cfg.CoercionInputShape
import com.fasterxml.jackson.databind.deser.AbstractDeserializer
import com.fasterxml.jackson.databind.deser.BeanDeserializerBase
import com.fasterxml.jackson.databind.deser.BeanDeserializerModifier
import com.fasterxml.jackson.databind.deser.ContextualDeserializer
import com.fasterxml.jackson.databind.deser.DefaultDeserializationContext
import com.fasterxml.jackson.databind.deser.ValueInstantiator
import com.fasterxml.jackson.databind.deser.impl.UnsupportedTypeDeserializer
import com.fasterxml.jackson.databind.deser.std.DelegatingDeserializer
import com.fasterxml.jackson.databind.deser.std.EnumDeserializer
import com.fasterxml.jackson.databind.deser.std.ObjectArrayDeserializer
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.databind.deser.std.StdScalarDeserializer
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.databind.ser.impl.UnknownSerializer
import com.fasterxml.jackson.databind.ser.impl.UnsupportedTypeSerializer
import com.fasterxml.jackson.databind.ser.std.BeanSerializerBase
import com.fasterxml.jackson.databind.type.LogicalType
import com.fasterxml.jackson.databind.util.TokenBuffer
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.google.adk.kt.ValueCoercer
import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.events.Event
import com.google.adk.kt.fitsInto
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
import com.google.adk.kt.workflow.defaultBodyDispatcher
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.typeOf
import kotlinx.coroutines.CoroutineDispatcher

/**
 * Creates [FunctionNode]s from Java with a type per port. Jackson reads each input into its
 * declared type and writes each output as JSON, so records and POJOs work as ports, and a port
 * typed this way infers the schema of that JSON form, or none for a type with no fixed shape or an
 * input Jackson reads as another class through `@JsonDeserialize(as = ...)` or a builder;
 * `Object.class` leaves a port untyped, a `Content` or `Event` port passes values unchanged, and a
 * `String` port reads a message as its text, passes null through, rejects any other non-text value,
 * and infers a string schema. An edge into a typed port must fit its schema as between Kotlin
 * ports: the source may write only keys the port type reads, or keys the port type itself writes,
 * with a JSON type that fits (an integer also fits a number), and null only where the port type
 * takes it, such as into a `String` port or a field marked `@Nullable`. So a bean fits its own
 * input, even one whose getter and setter names differ by a typo, which then reads null; a creator
 * property that a type never writes fails its own edge, as its reader would get null. A port writes
 * a value as its own class, so a subclass value may write keys its declared type does not; a typed
 * successor skips them, but an explicit input schema with `additionalProperties: false` rejects
 * them at run time. To run a node on each item of a list, wrap it with [FunctionNode.parallelOf].
 *
 * Numbers, booleans, chars, enums, and text inside a record, also as primitive array elements and
 * map keys, follow the coercion rules of Kotlin ports: a value of the JSON type, the same literal
 * quoted, a whole number into an integer, and one character of text into a `char`. `BigInteger`,
 * `BigDecimal`, the `Atomic*` types, `byte[]` (base64) and `char[]` (text) keep Jackson's readers.
 * A primitive field rejects an explicit null. An enum that Jackson writes as its ordinal, or a
 * boolean it writes as 1 or 0, such as under a numeric `@JsonFormat` shape, reads back from that
 * number.
 */
@AdkJavaInteropApi
@ExperimentalWorkflowApi
object FunctionNodes {
  /**
   * Returns a node named [name] that runs [body] on each input read as [inputType] and writes its
   * [outputType] output as JSON. [body] runs on a thread of [dispatcher], by default of
   * `Dispatchers.IO` without its thread limit, and may send messages before its output with
   * [InteropContextView.emit]; see [NodeBody]:
   * ```java
   * FunctionNodes.of("report", String.class, String.class, myDispatcher, (ctx, city) -> ...);
   * ```
   *
   * Do not use this method if [body] runs child nodes with [InteropContextView.runNode] or
   * [InteropContextView.runNodeBlocking]; build the node with [builder] and `rerunOnResume(true)`
   * instead.
   *
   * @throws WorkflowConfigurationException if Jackson cannot read and write [inputType] or write
   *   [outputType], or if a port type has no single concrete JSON shape; a field type Jackson
   *   cannot handle fails on the first value instead.
   */
  @JvmStatic
  @JvmOverloads
  fun <I, O> of(
    name: String,
    inputType: Class<I & Any>,
    outputType: Class<O & Any>,
    dispatcher: CoroutineDispatcher = defaultBodyDispatcher,
    body: NodeBody<I, O>,
  ): FunctionNode<I, O> = node(name, javaType(inputType), javaType(outputType), body, dispatcher)

  /** Like [of] with classes, for generic port types such as `List<String>`. */
  @JvmStatic
  @JvmOverloads
  fun <I, O> of(
    name: String,
    inputType: TypeReference<I>,
    outputType: TypeReference<O>,
    dispatcher: CoroutineDispatcher = defaultBodyDispatcher,
    body: NodeBody<I, O>,
  ): FunctionNode<I, O> = node(name, javaType(inputType), javaType(outputType), body, dispatcher)

  /** Like [of] with classes, for a generic output type. */
  @JvmStatic
  @JvmOverloads
  fun <I, O> of(
    name: String,
    inputType: Class<I & Any>,
    outputType: TypeReference<O>,
    dispatcher: CoroutineDispatcher = defaultBodyDispatcher,
    body: NodeBody<I, O>,
  ): FunctionNode<I, O> = node(name, javaType(inputType), javaType(outputType), body, dispatcher)

  /** Like [of] with classes, for a generic input type. */
  @JvmStatic
  @JvmOverloads
  fun <I, O> of(
    name: String,
    inputType: TypeReference<I>,
    outputType: Class<O & Any>,
    dispatcher: CoroutineDispatcher = defaultBodyDispatcher,
    body: NodeBody<I, O>,
  ): FunctionNode<I, O> = node(name, javaType(inputType), javaType(outputType), body, dispatcher)

  /**
   * Returns a [FunctionNode.Builder] whose node reads inputs as [inputType] and writes [outputType]
   * outputs as JSON, for setting properties that [of] does not take. Its body runs on [dispatcher],
   * as in [of].
   *
   * @throws WorkflowConfigurationException if Jackson cannot read and write [inputType] or write
   *   [outputType], or if a port type has no single concrete JSON shape.
   */
  @JvmStatic
  @JvmOverloads
  fun <I, O> builder(
    inputType: Class<I & Any>,
    outputType: Class<O & Any>,
    dispatcher: CoroutineDispatcher = defaultBodyDispatcher,
  ): FunctionNode.Builder<I, O> =
    typedBuilder(javaType(inputType), javaType(outputType), BUILDER, dispatcher)

  /** Like [builder] with classes, for generic port types such as `List<String>`. */
  @JvmStatic
  @JvmOverloads
  fun <I, O> builder(
    inputType: TypeReference<I>,
    outputType: TypeReference<O>,
    dispatcher: CoroutineDispatcher = defaultBodyDispatcher,
  ): FunctionNode.Builder<I, O> =
    typedBuilder(javaType(inputType), javaType(outputType), BUILDER, dispatcher)

  /** Like [builder] with classes, for a generic output type. */
  @JvmStatic
  @JvmOverloads
  fun <I, O> builder(
    inputType: Class<I & Any>,
    outputType: TypeReference<O>,
    dispatcher: CoroutineDispatcher = defaultBodyDispatcher,
  ): FunctionNode.Builder<I, O> =
    typedBuilder(javaType(inputType), javaType(outputType), BUILDER, dispatcher)

  /** Like [builder] with classes, for a generic input type. */
  @JvmStatic
  @JvmOverloads
  fun <I, O> builder(
    inputType: TypeReference<I>,
    outputType: Class<O & Any>,
    dispatcher: CoroutineDispatcher = defaultBodyDispatcher,
  ): FunctionNode.Builder<I, O> =
    typedBuilder(javaType(inputType), javaType(outputType), BUILDER, dispatcher)

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
    dispatcher: CoroutineDispatcher,
  ): FunctionNode<I, O> =
    typedBuilder<I, O>(inputType, outputType, "node '$name'", dispatcher)
      .name(name)
      .body(body)
      .build()

  private fun <I, O> typedBuilder(
    inputType: JavaType,
    outputType: JavaType,
    node: String,
    dispatcher: CoroutineDispatcher,
  ): FunctionNode.Builder<I, O> =
    FunctionNode.Builder.create<I, O>()
      .bodyDispatcher(dispatcher)
      // An input port also writes: an explicit input schema checks the input in its JSON form.
      .inputCodec(
        portCodec<I>(inputType, "the input of $node", KOTLIN_CODEC_TYPES, ::requireRoundTrip)
      )
      .outputCodec(
        portCodec<O>(
          outputType,
          "the output of $node",
          KOTLIN_OUTPUT_CODEC_TYPES,
          ::requireWritable,
        )
      )
}

private const val BUILDER = "a FunctionNodes.builder node"

/**
 * One mapper for every Java port: unknown properties are ignored, null fields are written,
 * `java.time` values travel as ISO-8601 strings, and scalars follow the typed-port coercion table
 * with the exceptions listed on [FunctionNodes].
 */
internal val MAPPER: ObjectMapper =
  JsonMapper.builder()
    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
    .addModule(JavaTimeModule())
    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    .disable(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS)
    .withCoercionConfig(LogicalType.Textual) {
      it.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
      it.setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
      it.setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail)
    }
    .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
    .addModule(tableScalars())
    .build()

/**
 * Number, boolean and char readers for the table, also for primitive arrays and map keys, which
 * read a scalar as the Kotlin port of that type does. Jackson's coercion configs cannot limit text
 * to JSON literals or floats to whole numbers, so these replace its readers.
 */
private fun tableScalars(): SimpleModule =
  SimpleModule("AdkPortScalars").apply {
    scalar(0L) { wholeLong(it) }
    scalar(0) { wholeLong(it)?.takeIf { n -> n.toInt().toLong() == n }?.toInt() }
    scalar(0.toShort()) { wholeLong(it)?.takeIf { n -> n.toShort().toLong() == n }?.toShort() }
    scalar(0.toByte()) { wholeLong(it)?.takeIf { n -> n.toByte().toLong() == n }?.toByte() }
    scalar(0.0) { ValueCoercer.numberLiteral(it)?.toDouble() }
    scalar(0f) { ValueCoercer.numberLiteral(it)?.toFloat()?.takeIf { f -> f.isFinite() } }
    scalar(false) { ValueCoercer.booleanLiteral(it) }
    scalar('\u0000') { (it as? String)?.singleOrNull() }
    // byte[] reads base64 and char[] text, as Jackson writes them.
    primitiveArray(LongArray::class.java) { LongArray(it.size) { i -> it[i] as Long } }
    primitiveArray(IntArray::class.java) { IntArray(it.size) { i -> it[i] as Int } }
    primitiveArray(ShortArray::class.java) { ShortArray(it.size) { i -> it[i] as Short } }
    primitiveArray(DoubleArray::class.java) { DoubleArray(it.size) { i -> it[i] as Double } }
    primitiveArray(FloatArray::class.java) { FloatArray(it.size) { i -> it[i] as Float } }
    primitiveArray(BooleanArray::class.java) { BooleanArray(it.size) { i -> it[i] as Boolean } }
    setDeserializerModifier(WrittenNumbers)
  }

/** Registers [read], which takes [scalarValue] or a map key, for a boxed and a primitive [T]. */
private inline fun <reified T : Any> SimpleModule.scalar(
  defaultValue: T,
  noinline read: (Any?) -> T?,
) {
  addDeserializer(
    T::class.javaObjectType,
    TableScalarDeserializer(T::class.javaObjectType, defaultValue, isPrimitive = false, read),
  )
  addDeserializer(
    T::class.javaPrimitiveType!!,
    TableScalarDeserializer(T::class.javaObjectType, defaultValue, isPrimitive = true, read),
  )
  addKeyDeserializer(
    T::class.javaObjectType,
    TableScalarKeyDeserializer(T::class.javaObjectType, read),
  )
}

private fun <A : Any> SimpleModule.primitiveArray(type: Class<A>, build: (Array<*>) -> A) {
  addDeserializer(type, PrimitiveArrayDeserializer(type, build))
}

private fun wholeLong(value: Any?): Long? = ValueCoercer.integerLiteral(value)?.toLong()

/** The current scalar token as the value a Kotlin port reads from the same JSON, else null. */
private fun scalarValue(p: JsonParser): Any? =
  when (p.currentToken()) {
    JsonToken.VALUE_NUMBER_INT ->
      if (p.numberType == JsonParser.NumberType.BIG_INTEGER) p.doubleValue else p.longValue
    JsonToken.VALUE_NUMBER_FLOAT -> p.doubleValue
    JsonToken.VALUE_STRING -> p.text
    JsonToken.VALUE_TRUE -> true
    JsonToken.VALUE_FALSE -> false
    else -> null
  }

/**
 * Reads a [type] with [read]; a primitive field takes no null and keeps its default when absent.
 */
private class TableScalarDeserializer<T : Any>(
  private val type: Class<T>,
  private val defaultValue: T,
  private val isPrimitive: Boolean,
  private val read: (Any?) -> T?,
) : StdScalarDeserializer<T>(type) {
  @Suppress("UNCHECKED_CAST")
  override fun deserialize(p: JsonParser, ctxt: DeserializationContext): T =
    read(scalarValue(p)) ?: ctxt.handleUnexpectedToken(type, p) as T

  override fun getNullValue(ctxt: DeserializationContext): T? {
    if (isPrimitive) ctxt.reportInputMismatch<Any>(this, "null for a primitive")
    return null
  }

  override fun getAbsentValue(ctxt: DeserializationContext): Any? = defaultValue.takeIf {
    isPrimitive
  }

  // Nulls.AS_EMPTY reads zero or false, as Jackson's own number and boolean readers do.
  override fun getEmptyValue(ctxt: DeserializationContext): Any = defaultValue
}

/** Reads a map key, which JSON writes as text, as [read] reads a value of [type]. */
private class TableScalarKeyDeserializer(
  private val type: Class<*>,
  private val read: (Any?) -> Any?,
) : KeyDeserializer() {
  override fun deserializeKey(key: String, ctxt: DeserializationContext): Any? =
    read(key) ?: ctxt.handleWeirdKey(type, key, "does not fit the key type")
}

/**
 * Reads a primitive array with Jackson's array reader, which honors the field's content-null
 * setting and `ACCEPT_SINGLE_VALUE_AS_ARRAY`, each element read as its type is in [tableScalars].
 */
private class PrimitiveArrayDeserializer<A : Any>(
  private val type: Class<A>,
  private val build: (Array<*>) -> A,
  private val boxed: JsonDeserializer<*>? = null,
) : StdDeserializer<A>(type), ContextualDeserializer {
  override fun createContextual(
    ctxt: DeserializationContext,
    property: BeanProperty?,
  ): JsonDeserializer<*> {
    val element = type.componentType
    // The primitive's own reader, so a null element fails.
    @Suppress("UNCHECKED_CAST") // A value reader reads Objects.
    val boxed =
      ObjectArrayDeserializer(
        ctxt.typeFactory.constructArrayType(element.kotlin.javaObjectType),
        ctxt.findRootValueDeserializer(ctxt.constructType(element)) as JsonDeserializer<Any>,
        null,
      )
    return PrimitiveArrayDeserializer(type, build, boxed.createContextual(ctxt, property))
  }

  override fun deserialize(p: JsonParser, ctxt: DeserializationContext): A {
    val values = checkNotNull(boxed) { "set by createContextual" }.deserialize(p, ctxt) as Array<*>
    if (values.any { it == null }) {
      return ctxt.reportInputMismatch(this, "null in a primitive array")
    }
    return build(values)
  }

  override fun getEmptyValue(ctxt: DeserializationContext): Any = build(emptyArray<Any>())
}

/**
 * Wraps the enum and boolean readers so that each also reads an enum's ordinal, or 1 and 0 for a
 * boolean, where Jackson writes those, and an enum rejects a padded name it does not write. An enum
 * with a `@JsonCreator` keeps its own reader.
 */
private object WrittenNumbers : BeanDeserializerModifier() {
  override fun modifyEnumDeserializer(
    config: DeserializationConfig,
    type: JavaType,
    beanDesc: BeanDescription,
    deserializer: JsonDeserializer<*>,
  ): JsonDeserializer<*> =
    if (deserializer is EnumDeserializer) {
      IndexedValueDeserializer(
        deserializer,
        type.rawClass.enumConstants.orEmpty().asList(),
        readsIndex = false,
      )
    } else {
      deserializer
    }

  override fun modifyDeserializer(
    config: DeserializationConfig,
    beanDesc: BeanDescription,
    deserializer: JsonDeserializer<*>,
  ): JsonDeserializer<*> =
    if (beanDesc.beanClass.kotlin.javaObjectType == Boolean::class.javaObjectType) {
      IndexedValueDeserializer(deserializer, listOf(false, true), readsIndex = false)
    } else {
      deserializer
    }
}

/**
 * Reads like its delegate but rejects padded text unless Jackson writes that exact text. Where
 * Jackson writes each of [values] as its own index, it also reads an index n as `values[n]`.
 * Jackson reads only the instance [createContextual] returns, which decides [readsIndex].
 */
private class IndexedValueDeserializer(
  delegate: JsonDeserializer<*>,
  private val values: List<Any>,
  private val readsIndex: Boolean,
) : DelegatingDeserializer(delegate) {
  override fun newDelegatingInstance(newDelegatee: JsonDeserializer<*>): JsonDeserializer<*> =
    IndexedValueDeserializer(newDelegatee, values, readsIndex)

  override fun createContextual(
    ctxt: DeserializationContext,
    property: BeanProperty?,
  ): JsonDeserializer<*> {
    val delegate =
      ctxt.handleSecondaryContextualization(_delegatee, property, ctxt.constructType(handledType()))
    return IndexedValueDeserializer(delegate, values, writesIndexes(property))
  }

  override fun deserialize(p: JsonParser, ctxt: DeserializationContext): Any? {
    val index = if (readsIndex) scalarValue(p) as? Long else null
    if (index != null && index in values.indices) return values[index.toInt()]
    val text = if (p.hasToken(JsonToken.VALUE_STRING)) p.text else null
    val value = super.deserialize(p, ctxt)
    // Jackson's enum reader trims a name it does not find; keep only names the type writes.
    if (
      text != null &&
        value != null &&
        text != text.trim { it <= ' ' } &&
        text != MAPPER.valueToTree<JsonNode>(value).asText()
    ) {
      return ctxt.handleWeirdStringValue(handledType(), text, "not a name it writes")
    }
    return value
  }

  /**
   * Whether Jackson writes each of [values] as its own index in [property], or as a top-level value
   * when [property] is null. A field with its own `@JsonSerialize`, or a serializer that fails
   * here, counts as not writing them.
   */
  private fun writesIndexes(property: BeanProperty?): Boolean {
    if (values.isEmpty() || property?.getAnnotation(JsonSerialize::class.java) != null) {
      return false
    }
    val provider = MAPPER.serializerProviderInstance
    return runCatching {
        val serializer = provider.findValueSerializer(handledType(), property)
        values.withIndex().all { (i, value) ->
          TokenBuffer(MAPPER, false).use { tokens ->
            serializer.serialize(value, tokens, provider)
            tokens.asParser().use { parser ->
              parser.nextToken() == JsonToken.VALUE_NUMBER_INT && parser.longValue == i.toLong()
            }
          }
        }
      }
      .getOrDefault(false)
  }
}

/** Port classes that keep the codec of the matching Kotlin port type. */
private val KOTLIN_CODEC_TYPES: Map<Class<*>, KType> =
  mapOf(
    Any::class.java to typeOf<Any?>(),
    Content::class.java to typeOf<Content?>(),
    Event::class.java to typeOf<Event?>(),
    String::class.java to typeOf<String?>(),
  )

/** [KOTLIN_CODEC_TYPES] for outputs; a String output is non-null since null means no output. */
private val KOTLIN_OUTPUT_CODEC_TYPES: Map<Class<*>, KType> =
  KOTLIN_CODEC_TYPES + (String::class.java to typeOf<String>())

private fun javaType(type: Class<*>): JavaType =
  MAPPER.typeFactory.constructType(type.kotlin.javaObjectType)

private fun javaType(type: TypeReference<*>): JavaType = MAPPER.typeFactory.constructType(type)

private fun <T> portCodec(
  type: JavaType,
  what: String,
  kotlinTypes: Map<Class<*>, KType>,
  check: (JavaType, String) -> Unit,
): PortCodec<T> {
  kotlinTypes[type.rawClass]?.let {
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
      isPolymorphic(type) -> "is polymorphic (@JsonTypeInfo)"
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

/** Whether Jackson writes a type id for [type] (`@JsonTypeInfo`). */
internal fun isPolymorphic(type: JavaType): Boolean =
  runCatching { MAPPER.serializerProviderInstance.findTypeSerializer(type) }.getOrNull() != null

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

internal fun deserializationContext(): DefaultDeserializationContext =
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

  // Only a Jackson reference type (`AtomicReference`) takes a top-level null; coerce passes any.
  override val schema: Schema? by lazy { inferJavaPortSchema(type, forOutput = false) }

  override val outputSchema: Schema? by lazy { inferJavaPortSchema(type, forOutput = true) }

  override fun mismatchHint(source: Schema): String? {
    val input = schema ?: return null
    // Hint only when the closed input alone rejects the edge.
    if (!source.fitsInto(input.copy(additionalProperties = null))) return null
    val unread = source.properties?.keys.orEmpty() - input.properties.orEmpty().keys
    if (unread.isEmpty()) return null
    // Jackson reads a delegating type as its delegate, which then needs the keys.
    val read =
      generateSequence(if (type.isReferenceType) type.contentType else type) {
          delegateType(it)?.let { d -> if (d.isReferenceType) d.contentType else d }
        }
        .last()
    val name = read.rawClass.simpleName
    return "$name does not read $unread: read each under that name, such as with @JsonProperty on" +
      " a field, setter or creator parameter, mark a derived getter that writes one @JsonIgnore," +
      " set the node's input schema, or use a Map or Object.class port."
  }

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
