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

@file:OptIn(ExperimentalWorkflowApi::class, FrameworkInternalApi::class)
// typeOf<T>() is a compiler intrinsic and does not require kotlin-reflect.
@file:Suppress("KotlinReflectNeeded")

package com.google.adk.kt.workflow

import com.google.adk.kt.SchemaUtils
import com.google.adk.kt.agents.Context
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.events.Event
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Schema
import kotlin.reflect.KClass
import kotlin.reflect.KClassifier
import kotlin.reflect.KType
import kotlin.reflect.typeOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.descriptors.nullable

/**
 * A workflow [Node] backed by a suspending function that may emit progress [Event]s and returns an
 * output of type [O]. Its schemas inferred from [I] and [O] only describe it; an explicit schema
 * passed to [node] is also enforced.
 *
 * Inline workflow steps use [node] or [parallelNode].
 *
 * @param I Expected type of `nodeInput` from predecessor nodes.
 * @param O Type of the value the body returns, which is the node's output. `Unit` or `null` is no
 *   output, an [Event] passes through as it is, and a [RequestInput] fails. A [Content] is output
 *   as it is, and any other value as its JSON form under [O]'s serializer.
 */
@ExperimentalWorkflowApi
class FunctionNode<I, O>
private constructor(
  name: String,
  description: String,
  rerunOnResume: Boolean,
  waitForOutput: Boolean,
  config: NodeConfig,
  private val explicitInputSchema: Schema?,
  private val explicitOutputSchema: Schema?,
  stateSchema: Schema?,
  internal val inputCodec: ValueCodec<I>,
  internal val outputCodec: ValueCodec<O>,
  internal val itemNode: FunctionNode<*, *>?,
  /** Maximum number of items a parallel node runs at a time, or `null` for no limit. */
  internal val maxParallelWorkers: Int?,
  private val body: suspend FlowCollector<Event>.(Context, I) -> O,
) :
  BaseNode(
    name = name,
    description = description,
    rerunOnResume = rerunOnResume || itemNode != null,
    waitForOutput = waitForOutput,
    config = config,
    // A parallel node declares no port schemas, as in adk-python; its item node keeps its own.
    inputSchema =
      if (itemNode != null) null else explicitInputSchema ?: inferredPortSchema(inputCodec),
    outputSchema =
      if (itemNode != null) null else explicitOutputSchema ?: inferredPortSchema(outputCodec),
    stateSchema = stateSchema,
  ) {

  @PublishedApi
  internal constructor(
    name: String,
    description: String = "",
    rerunOnResume: Boolean = false,
    waitForOutput: Boolean = false,
    config: NodeConfig = NodeConfig(),
    inputSchema: Schema? = null,
    outputSchema: Schema? = null,
    stateSchema: Schema? = null,
    inputType: KType,
    outputType: KType,
    itemNode: FunctionNode<*, *>? = null,
    maxParallelWorkers: Int? = null,
    body: suspend FlowCollector<Event>.(Context, I) -> O,
  ) : this(
    name = name,
    description = description,
    rerunOnResume = rerunOnResume,
    waitForOutput = waitForOutput,
    config = config,
    explicitInputSchema = inputSchema,
    explicitOutputSchema = outputSchema,
    stateSchema = stateSchema,
    inputCodec = ValueCodec(inputType),
    outputCodec = ValueCodec(outputType),
    itemNode = itemNode,
    maxParallelWorkers = maxParallelWorkers,
    body = body,
  )

  internal val inputType: KType
    get() = inputCodec.kType

  internal val outputType: KType
    get() = outputCodec.kType

  init {
    validateNodeName(name)
    require(maxParallelWorkers == null || itemNode != null) {
      "maxParallelWorkers requires an item node."
    }
    checkMaxParallelWorkers(maxParallelWorkers)
  }

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    if (itemNode != null) {
      emit(ParallelWorker(itemNode, maxParallelWorkers).mapOverItems(context, nodeInput))
      return@flow
    }
    // A no-op after validateInput, and it covers a direct call.
    val input = inputCodec.coerce(nodeInput, "input of node '$name'")
    // The return value is the only output, so an emitted event carries messages and actions only.
    val events =
      FlowCollector<Event> { event ->
        require(event.output == null && event.nodeInfo?.messageAsOutput != true) {
          "node '$name' emitted an Event with an output; return the value instead and put state " +
            "changes in ctx.updateState or the event's actions."
        }
        emit(event)
      }
    when (val result = events.body(context, input)) {
      is RequestInput ->
        throw IllegalArgumentException(
          "node '$name' returned a RequestInput; call requestInput(...) to ask for input."
        )
      else -> emit(result)
    }
  }

  /**
   * Makes [nodeInput] an [I], then checks its JSON form against an explicit [inputSchema]. A
   * parallel node passes it through, and its item node checks each item.
   */
  override fun validateInput(nodeInput: Any?): Any? {
    if (itemNode != null) return nodeInput
    val what = "input of node '$name'"
    val input = inputCodec.coerce(nodeInput, what)
    if (explicitInputSchema != null) {
      val checked =
        try {
          inputCodec.schemaForm(input, what)
        } catch (e: IllegalArgumentException) {
          throw NodeInputValidationException("validation error: ${e.message}", e)
        }
      val unused =
        SchemaUtils.validateValue(checked, explicitInputSchema, what).getOrElse {
          throw NodeInputValidationException(it.message.orEmpty(), it)
        }
    }
    return input
  }

  /**
   * Returns a returned value, or a returned event's output, as successors receive it:
   * - a [Content] or [Event], or any output with [O] as `Content` or `Event`, passes as it is;
   * - any other output becomes its JSON form under [O]'s serializer, so with [O] as `Any` a value
   *   that is not JSON-native fails here.
   *
   * An explicit [outputSchema] then checks the result.
   */
  override fun validateOutput(output: Any?): Any? {
    // A parallel node outputs its items' outputs, which each item node already validated.
    if (itemNode != null) return output
    // Assigning null produces no output.
    if (output == null) return null
    val what = "output of node '$name'"
    val json = outputCodec.schemaForm(output, what)
    if (explicitOutputSchema != null) {
      val unused = SchemaUtils.validateValue(json, explicitOutputSchema, what).getOrThrow()
    }
    return json
  }
}

/** Types a node handles by their meaning rather than as data. */
private val FRAMEWORK_TYPES: Set<KClass<*>> = setOf(Content::class, Event::class)

/**
 * Port types that need no serializer: a framework value passes as it is, `Any` is a JSON value, and
 * `Unit` carries nothing.
 */
private val SERIALIZER_FREE_TYPES: Set<KClass<*>> = FRAMEWORK_TYPES + setOf(Any::class, Unit::class)

/** Infers a port's schema, except for framework types. */
private fun inferredPortSchema(codec: ValueCodec<*>): Schema? =
  if (codec.kType.classifier in FRAMEWORK_TYPES) null else codec.schema

/**
 * Returns [value] as a node outputs it and a schema checks it: a framework value as it is, and
 * anything else as JSON.
 */
internal fun ValueCodec<*>.schemaForm(value: Any?, what: String): Any? =
  if (kType.classifier in FRAMEWORK_TYPES || value is Content || value is Event) {
    value
  } else {
    encode(value, what)
  }

/**
 * Creates a [FunctionNode] that runs [block] on an input of type [I] and returns an output of type
 * [O], emitting progress [Event]s through its [FlowCollector] receiver. Schemas inferred from [I]
 * and [O] describe the node, while an explicit [inputSchema] or [outputSchema] is also enforced on
 * every run. Unlike adk-python, which dumps only models, an output travels as its JSON form under
 * [O]'s serializer, so a successor typed `Any?` reads a `Long` for an `Int`, and an input decodes
 * from that form, a quoted number included.
 *
 * The block asks the user for input with [requestInput]. Its return value, a [Content] included, is
 * the node's output; emitting an event with an output or calling `Context.setOutput` fails the
 * node.
 *
 * @throws WorkflowConfigurationException if [I] or [O] has no usable serializer and is not
 *   `Content`, `Event`, `Any`, or `Unit`.
 */
@ExperimentalWorkflowApi
inline fun <reified I, reified O> node(
  name: String,
  description: String = "",
  rerunOnResume: Boolean = false,
  waitForOutput: Boolean = false,
  config: NodeConfig = NodeConfig(),
  inputSchema: Schema? = null,
  outputSchema: Schema? = null,
  stateSchema: Schema? = null,
  crossinline block: suspend FlowCollector<Event>.(context: Context, input: I) -> O,
): FunctionNode<I, O> {
  requirePortSerializer(name, "input", typeOf<I>())
  requirePortSerializer(name, "output", typeOf<O>())
  return FunctionNode(
    name = name,
    description = description,
    rerunOnResume = rerunOnResume,
    waitForOutput = waitForOutput,
    config = config,
    inputSchema = inputSchema,
    outputSchema = outputSchema,
    stateSchema = stateSchema,
    inputType = typeOf<I>(),
    outputType = typeOf<O>(),
    body = { context, input -> block(context, input) },
  )
}

/**
 * Emits [content] from a [node] body as a message, which is not the node's output. In a custom
 * [Node]'s `flow {}`, `emit(content)` emits a raw value instead, which becomes the output.
 */
@ExperimentalWorkflowApi
suspend fun FlowCollector<Event>.emit(content: Content) {
  emit(Event(content = content))
}

/**
 * Throws [WorkflowConfigurationException] when node [name]'s [port] type [type], or a type argument
 * inside it, cannot travel as JSON, so the node fails when it is built rather than on its first
 * value.
 */
@PublishedApi
internal fun requirePortSerializer(name: String, port: String, type: KType) {
  val problem = portTypeProblem(type, nested = false) ?: return
  throw WorkflowConfigurationException("node '$name' has $port type $type, $problem")
}

/** Returns why [type] or one of its type arguments cannot travel as JSON, or null if it can. */
private fun portTypeProblem(type: KType, nested: Boolean): String? {
  val classifier = type.classifier
  if (classifier in SERIALIZER_FREE_TYPES) return null
  if (nested && classifier == ByteArray::class) {
    return "which has a ByteArray type argument; only a top-level ByteArray is supported for now."
  }
  val serializer = SchemaUtils.serializerFor(type)
  if (serializer == null || isOpenPolymorphic(serializer, classifier)) {
    return "which has no usable serializer. Mark it @Serializable, or use Any for a JSON-native " +
      "value, such as Map<String, Any?> for Map<String, *>."
  }
  return type.arguments.firstNotNullOfOrNull { argument ->
    argument.type?.let { portTypeProblem(it, nested = true) }
  }
}

/**
 * Whether [serializer] is an interface's polymorphic serializer, nullable or not, which adkJson
 * cannot use because it registers no subclasses.
 */
private fun isOpenPolymorphic(serializer: KSerializer<*>, classifier: KClassifier?): Boolean {
  if (serializer is PolymorphicSerializer<*>) return true
  @Suppress("UNCHECKED_CAST") val kClass = classifier as? KClass<Any> ?: return false
  return serializer.descriptor == PolymorphicSerializer(kClass).descriptor.nullable
}

/**
 * Creates a [FunctionNode] that runs [block] concurrently on each item of a list input and returns
 * the results as a [List] of [O] in input order. A text-only [Content] input, unless [I] is
 * [Content], is read as JSON so a JSON list fans out. Any other non-list input runs as one item.
 *
 * [config], the schemas and [rerunOnResume] apply to each item's run; the fan-out node shares
 * [waitForOutput] and [stateSchema], declares no input or output schema, has no retry or timeout,
 * and always reruns on resume.
 *
 * @param maxParallelWorkers Maximum number of items run at a time, or `null` for no limit.
 * @throws WorkflowConfigurationException if [maxParallelWorkers] is less than 1, or if [I] or [O]
 *   has no serializer, as in [node].
 */
@ExperimentalWorkflowApi
inline fun <reified I, reified O> parallelNode(
  name: String,
  description: String = "",
  rerunOnResume: Boolean = false,
  waitForOutput: Boolean = false,
  maxParallelWorkers: Int? = null,
  config: NodeConfig = NodeConfig(),
  inputSchema: Schema? = null,
  outputSchema: Schema? = null,
  stateSchema: Schema? = null,
  crossinline block: suspend FlowCollector<Event>.(context: Context, input: I) -> O,
): FunctionNode<List<I>, List<O>> =
  parallelNode(
    node =
      node<I, O>(
        name = name,
        description = description,
        rerunOnResume = rerunOnResume,
        waitForOutput = waitForOutput,
        config = config,
        inputSchema = inputSchema,
        outputSchema = outputSchema,
        stateSchema = stateSchema,
        block = block,
      ),
    maxParallelWorkers = maxParallelWorkers,
  )

/**
 * Wraps [node] so it runs concurrently on each item of a list input and returns the results in
 * input order. [node]'s configuration and schemas apply to each item's run; the fan-out node
 * declares no input or output schema.
 *
 * @param maxParallelWorkers Maximum number of items run at a time, or `null` for no limit.
 * @throws WorkflowConfigurationException if [maxParallelWorkers] is less than 1.
 */
@ExperimentalWorkflowApi
inline fun <reified I, reified O> parallelNode(
  node: FunctionNode<I, O>,
  maxParallelWorkers: Int? = null,
): FunctionNode<List<I>, List<O>> =
  FunctionNode(
    name = node.name,
    description = node.description,
    rerunOnResume = true,
    waitForOutput = node.waitForOutput,
    config = NodeConfig(),
    inputSchema = null,
    outputSchema = null,
    stateSchema = node.stateSchema,
    inputType = typeOf<List<I>>(),
    outputType = typeOf<List<O>>(),
    itemNode = node,
    maxParallelWorkers = maxParallelWorkers,
    body = { _, _ -> error("parallelNode dispatches through itemNode") },
  )
