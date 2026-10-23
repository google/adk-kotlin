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
import com.google.adk.kt.ValueCoercer
import com.google.adk.kt.agents.Context
import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.events.Event
import com.google.adk.kt.events.ToolConfirmation
import com.google.adk.kt.events.parseToolConfirmation
import com.google.adk.kt.ids.Uuid
import com.google.adk.kt.serialization.adkJson
import com.google.adk.kt.serialization.jsonElementToAny
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Schema
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic
import kotlin.jvm.JvmSynthetic
import kotlin.reflect.KClass
import kotlin.reflect.KClassifier
import kotlin.reflect.KType
import kotlin.reflect.typeOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.nullable

/**
 * A workflow [Node] backed by a suspending function that returns an [O] and may emit [Event]s. An
 * input that does not decode as [I] throws [NodeInputValidationException]; an output that is not an
 * [O] or a `Content`, has no JSON form, or fails an explicit schema throws
 * [IllegalArgumentException].
 *
 * Inline workflow steps use [node] or [parallelNode], and Java callers use `FunctionNodes`,
 * [parallelOf] and [toBuilder]; a schema set on a [Builder] is explicit.
 *
 * @param I Expected type of `nodeInput` from predecessor nodes. A message reaches a `Content` or
 *   `Any` port as is, a `String` as its text, a `ByteArray` as base64 text, and another typed port
 *   as JSON, converting only a quoted literal of the port's type and a whole number for an integer.
 * @param O Type of the value the body returns, which is the node's output. `Unit` or `null` is no
 *   output, an [Event] passes through as it is, and a [RequestInput] fails. Any other value, a
 *   [Content] included, is output as its JSON form under [O]'s serializer.
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
  internal val inputCodec: PortCodec<I>,
  internal val outputCodec: PortCodec<O>,
  internal val itemNode: FunctionNode<*, *>?,
  /** Maximum number of items a parallel node runs at a time, or `null` for no limit. */
  internal val maxParallelWorkers: Int?,
  /** Whether each run asks the user to approve it before the body runs. */
  internal val requireConfirmation: Boolean,
  /** Decides per input whether a run asks the user to approve it, or `null` to not decide. */
  private val confirmationPredicate: (suspend (Context, I) -> Boolean)?,
  private val body: suspend FlowCollector<Event>.(Context, I) -> O,
) :
  BaseNode(
    name = name,
    description = description,
    rerunOnResume = rerunOnResume || itemNode != null,
    waitForOutput = waitForOutput,
    config = config,
    // A parallel node declares no port schemas; its item node keeps its own.
    inputSchema = if (itemNode != null) null else explicitInputSchema ?: inputCodec.schema,
    outputSchema =
      if (itemNode != null) null else explicitOutputSchema ?: inferredOutputSchema(outputCodec),
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
    requireConfirmation: Boolean = false,
    confirmationPredicate: (suspend (Context, I) -> Boolean)? = null,
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
    requireConfirmation = requireConfirmation,
    confirmationPredicate = confirmationPredicate,
    body = body,
  )

  internal val inputType: KType?
    get() = (inputCodec as? ValueCodec<*>)?.kType

  /** Whether runs may ask the user to approve them, always or per [confirmationPredicate]. */
  private val asksForConfirmation: Boolean
    get() = requireConfirmation || confirmationPredicate != null

  internal val outputType: KType?
    get() = (outputCodec as? ValueCodec<*>)?.kType

  init {
    validateNodeName(name)
    require(maxParallelWorkers == null || itemNode != null) {
      "maxParallelWorkers requires an item node."
    }
    checkMaxParallelWorkers(maxParallelWorkers)
    if (asksForConfirmation && !this.rerunOnResume) {
      throw WorkflowConfigurationException(
        "node '$name' requires confirmation, so it needs rerunOnResume = true to run again once " +
          "the user answers."
      )
    }
  }

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    if (itemNode != null) {
      emit(ParallelWorker(itemNode, maxParallelWorkers).mapOverItems(context, nodeInput))
      return@flow
    }
    // A no-op after validateInput, and it covers a direct call.
    val input = inputCodec.coerce(nodeInput, "input of node '$name'")
    val confirmation = if (asksForConfirmation) confirmation(context, input) else null
    if (confirmation?.confirmed == false) {
      // The rejection is the run's output.
      emit(REJECTED_OUTPUT)
      return@flow
    }
    // An emitted event carries messages and actions only; the output is returned or delegated.
    val events =
      FlowCollector<Event> { event ->
        require(event.output == null) {
          "node '$name' emitted an Event with an output; return the value instead."
        }
        require(event.nodeInfo?.messageAsOutput != true) {
          "node '$name' emitted an Event marked messageAsOutput; return the value instead."
        }
        emit(event)
      }
    val result = events.body(context, input)
    val delegated = context.output
    val encoded = delegated?.let { encodeDelegatedOutput(it) }
    when (result) {
      is RequestInput ->
        throw IllegalArgumentException(
          "node '$name' returned a RequestInput; call requestInput(...) to ask for input."
        )
      else -> emit(result)
    }
    // A delegated output that encoding changed goes out again as this node's own output event.
    if (encoded != null && encoded != delegated) {
      context.requireNodeState().replaceDelegatedOutput(encoded)
    }
    // An event marks a confirmed run that output nothing as finished, so a later resume skips it.
    if (confirmation != null && delegated == null && (result == null || result == Unit))
      emit(Event())
  }

  /**
   * Returns the user's answer to this run's confirmation request and sets it as the context's
   * [Context.toolConfirmation], or null when [confirmationPredicate] decides not to ask. Without an
   * answer yet, asks for one and ends the run.
   */
  private suspend fun FlowCollector<Nothing>.confirmation(
    context: Context,
    input: I,
  ): ToolConfirmation? {
    val interruptId = "$CONFIRMATION_INTERRUPT_PREFIX${context.nodePath}"
    val answer = context.resumeInputs[interruptId]
    if (answer == null) {
      if (!requireConfirmation && confirmationPredicate?.invoke(context, input) != true) return null
      requestInput(
        interruptId = interruptId,
        message = "Please approve or reject the tool call $name().",
        payload =
          mapOf("tool_name" to name, "args" to inputCodec.encode(input, "input of node '$name'")),
        responseSchema = TOOL_CONFIRMATION_SCHEMA,
      )
    }
    val confirmation =
      (answer as? Map<*, *>)?.let(::parseToolConfirmation)
        ?: throw IllegalArgumentException(
          "node '$name' got an answer to its confirmation request that is not a ToolConfirmation."
        )
    context.toolConfirmation = confirmation
    return confirmation
  }

  /**
   * Checks [nodeInput] against an explicit [inputSchema] as it arrives, then makes the checked
   * value an [I]; a `Content` port receives the message itself. A parallel node passes it through,
   * and its item node checks each item.
   */
  override fun validateInput(nodeInput: Any?): Any? {
    if (itemNode != null) return nodeInput
    val what = "input of node '$name'"
    if (explicitInputSchema == null || nodeInput == null) return inputCodec.coerce(nodeInput, what)
    val takesContent = inputCodec.classifier == Content::class
    // A Content port checks the message, which may arrive as its JSON form.
    val json =
      when {
        takesContent -> inputCodec.coerce(nodeInput, what)
        nodeInput.isJsonForm() -> nodeInput
        else ->
          try {
            inputCodec.schemaForm(nodeInput, what)
          } catch (e: IllegalArgumentException) {
            throw NodeInputValidationException("validation error: ${e.message}", e)
          }
      }
    val checked =
      ValueCoercer.coerceValue(json, explicitInputSchema, what).getOrElse {
        throw NodeInputValidationException(it.message.orEmpty(), it)
      }
    return if (takesContent) json else inputCodec.coerce(checked, what)
  }

  /**
   * Returns [value], the output of a child this node gave its output to with `useAsOutput`, as if
   * this node had returned it. Throws [IllegalArgumentException] if it is not an [O].
   */
  private fun encodeDelegatedOutput(value: Any): Any? {
    val classifier = outputCodec.classifier
    require(classifier != Unit::class) {
      "node '$name' declares no output (Unit) but gives a child its output; declare its output type."
    }
    // A node typed Event passes the child's value on as it is, since a value is not an Event.
    if (classifier == Event::class) return validateOutput(value)
    val output =
      try {
        outputCodec.coerce(value, "output of node '$name'")
      } catch (e: NodeInputValidationException) {
        throw IllegalArgumentException(e.message, e)
      }
    return validateOutput(output)
  }

  /**
   * Returns a returned value, or a returned event's output, as successors receive it: a [Content]
   * as its JSON form, an [Event] as it is, and any other value as its JSON form under [O]'s
   * serializer. An explicit [outputSchema] then checks it, and the checked value is the output.
   */
  override fun validateOutput(output: Any?): Any? {
    // A parallel node outputs its items' outputs, which each item node already validated.
    if (itemNode != null) return output
    if (asksForConfirmation && output == REJECTED_OUTPUT) return output
    // Assigning null produces no output.
    if (output == null) return null
    val what = "output of node '$name'"
    // Only a node whose O is Content, Event or Any returns a Content, which has one JSON form.
    val json = if (output is Content) contentJson(output) else outputCodec.schemaForm(output, what)
    return if (explicitOutputSchema == null) json
    else SchemaUtils.checkedValue(json, explicitOutputSchema, what)
  }

  /**
   * Returns a [Builder] initialized with this instance's properties, primarily for Java callers.
   * Only explicitly set schemas are copied, not inferred ones, so the copy enforces the same
   * schemas as this node.
   */
  @AdkJavaInteropApi
  @ExperimentalWorkflowApi
  fun toBuilder(): Builder<I, O> =
    Builder.create<I, O>()
      .name(name)
      .description(description)
      .rerunOnResume(rerunOnResume)
      .waitForOutput(waitForOutput)
      .config(config)
      .inputSchema(explicitInputSchema)
      .outputSchema(explicitOutputSchema)
      .stateSchema(stateSchema)
      .inputCodec(inputCodec)
      .outputCodec(outputCodec)
      .itemNode(itemNode)
      .maxParallelWorkers(maxParallelWorkers)
      .confirmation(requireConfirmation, confirmationPredicate)
      .flowBody(body)

  /**
   * Fluent builder for [FunctionNode], provided primarily for Java callers. Any property left unset
   * falls back to the same default as the constructor. Java gets one from `FunctionNodes.builder`
   * or [toBuilder], which keep the port types and parallel-worker settings, because a port cannot
   * decode without a type.
   */
  @AdkJavaInteropApi
  @ExperimentalWorkflowApi
  @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
  class Builder<I, O> private constructor() {
    private var name: String? = null
    private var description: String = ""
    private var rerunOnResume: Boolean = false
    private var waitForOutput: Boolean = false
    private var config: NodeConfig = NodeConfig()
    private var inputSchema: Schema? = null
    private var outputSchema: Schema? = null
    private var stateSchema: Schema? = null
    private var inputCodec: PortCodec<*> = ValueCodec<Any?>(typeOf<Any?>())
    private var outputCodec: PortCodec<*> = ValueCodec<Any?>(typeOf<Any?>())
    private var itemNode: FunctionNode<*, *>? = null
    private var maxParallelWorkers: Int? = null
    private var requireConfirmation: Boolean = false
    private var confirmationPredicate: (suspend (Context, I) -> Boolean)? = null
    private var flowBody: (suspend FlowCollector<Event>.(Context, I) -> O)? = null
    private var bodySetByCaller = false

    internal companion object {
      // Synthetic, so Java cannot build a node whose port types it has no codec for.
      @JvmSynthetic fun <I, O> create(): Builder<I, O> = Builder()
    }

    /** Sets the node's name, unique within its graph. Required. */
    fun name(name: String): Builder<I, O> = apply { this.name = name }

    /** Sets what the node does, for humans and for a model that may call it. */
    fun description(description: String): Builder<I, O> = apply { this.description = description }

    /**
     * Sets whether a resume runs the node again rather than using the resuming answer. A parallel
     * node always reruns, so [build] rejects `false` for one.
     */
    fun rerunOnResume(rerunOnResume: Boolean): Builder<I, O> = apply {
      this.rerunOnResume = rerunOnResume
    }

    /** Sets whether the node stays re-triggerable until it produces an output or a route. */
    fun waitForOutput(waitForOutput: Boolean): Builder<I, O> = apply {
      this.waitForOutput = waitForOutput
    }

    /**
     * Sets the node's retry policy and execution timeout. A parallel node runs its items with the
     * item node's config, so [build] rejects a non-default config for one.
     */
    fun config(config: NodeConfig): Builder<I, O> = apply { this.config = config }

    /** Sets an explicit schema that each input must match before the body runs. */
    fun inputSchema(inputSchema: Schema?): Builder<I, O> = apply { this.inputSchema = inputSchema }

    /** Sets an explicit schema that each output value must match. */
    fun outputSchema(outputSchema: Schema?): Builder<I, O> = apply {
      this.outputSchema = outputSchema
    }

    /**
     * Sets the state keys the node uses, which child nodes inherit unless they declare their own.
     */
    fun stateSchema(stateSchema: Schema?): Builder<I, O> = apply { this.stateSchema = stateSchema }

    internal fun inputCodec(inputCodec: PortCodec<*>): Builder<I, O> = apply {
      this.inputCodec = inputCodec
    }

    internal fun outputCodec(outputCodec: PortCodec<*>): Builder<I, O> = apply {
      this.outputCodec = outputCodec
    }

    internal fun confirmation(
      requireConfirmation: Boolean,
      confirmationPredicate: (suspend (Context, I) -> Boolean)?,
    ): Builder<I, O> = apply {
      this.requireConfirmation = requireConfirmation
      this.confirmationPredicate = confirmationPredicate
    }

    internal fun itemNode(itemNode: FunctionNode<*, *>?): Builder<I, O> = apply {
      this.itemNode = itemNode
    }

    internal fun maxParallelWorkers(maxParallelWorkers: Int?): Builder<I, O> = apply {
      this.maxParallelWorkers = maxParallelWorkers
    }

    /**
     * Sets the function the node runs on each input. Required, except on a parallel node, which
     * runs its item node and so [build] rejects a body for one.
     */
    fun body(body: NodeBody<I, O>): Builder<I, O> = apply {
      this.flowBody = { context, input -> body.apply(context, input) }
      this.bodySetByCaller = true
    }

    internal fun flowBody(flowBody: suspend FlowCollector<Event>.(Context, I) -> O): Builder<I, O> =
      apply {
        this.flowBody = flowBody
        this.bodySetByCaller = false
      }

    /**
     * Builds the [FunctionNode].
     *
     * @throws IllegalStateException if [name] or [body] is not set.
     * @throws WorkflowConfigurationException if a parallel node, such as one from [parallelOf], has
     *   an input or output schema, `rerunOnResume(false)`, a non-default [config], or a body set
     *   with [body].
     */
    fun build(): FunctionNode<I, O> {
      val resolvedName = checkNotNull(name) { "FunctionNode.Builder requires name to be set." }
      val resolvedBody =
        checkNotNull(flowBody) {
          "FunctionNode.Builder for '$resolvedName' requires body to be set."
        }
      if (itemNode != null) {
        val rejected = buildList {
          if (inputSchema != null) add("inputSchema")
          if (outputSchema != null) add("outputSchema")
          if (!rerunOnResume) add("rerunOnResume(false)")
          if (config != NodeConfig()) add("config")
          if (bodySetByCaller) add("body")
        }
        if (rejected.isNotEmpty()) {
          throw WorkflowConfigurationException(
            "${rejected.joinToString()} cannot be set on parallel node '$resolvedName'; " +
              "configure its item node instead."
          )
        }
      }
      @Suppress("UNCHECKED_CAST")
      return FunctionNode(
        name = resolvedName,
        description = description,
        rerunOnResume = rerunOnResume,
        waitForOutput = waitForOutput,
        config = config,
        explicitInputSchema = inputSchema,
        explicitOutputSchema = outputSchema,
        stateSchema = stateSchema,
        inputCodec = inputCodec as PortCodec<I>,
        outputCodec = outputCodec as PortCodec<O>,
        itemNode = itemNode,
        maxParallelWorkers = maxParallelWorkers,
        requireConfirmation = requireConfirmation,
        confirmationPredicate = confirmationPredicate,
        body = resolvedBody,
      )
    }
  }

  companion object {
    /**
     * Wraps [node] in a parallel-worker [FunctionNode] that runs it on each item of a list input
     * concurrently (optionally bounded by [maxParallelWorkers]) and outputs the results in input
     * order, primarily for Java callers. A non-list input runs as one item, and [node] checks each
     * item against its own schemas.
     *
     * @throws WorkflowConfigurationException if [maxParallelWorkers] is less than 1.
     */
    @AdkJavaInteropApi
    @ExperimentalWorkflowApi
    @JvmStatic
    @JvmOverloads
    fun <I, O> parallelOf(
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
        inputType = typeOf<List<Any?>>(),
        outputType = typeOf<List<Any?>>(),
        itemNode = node,
        maxParallelWorkers = maxParallelWorkers,
        body = { _, _ -> error("parallelOf dispatches through itemNode") },
      )
  }
}

/**
 * The body of a [FunctionNode] set through [FunctionNode.Builder.body]; it runs on the workflow's
 * coroutine, so it must not block, and asynchronous work belongs in a `BasePublisherNode`. `input`
 * arrives as the node's input type, in JSON form on an untyped port (a whole number as a `Long`, a
 * fraction as a `Double`, an object as a `Map`), or as a [Content] message, and may be a
 * predecessor's own collection, so the body must not mutate it. Return `null` for no output, a
 * value of the output type, or an [Event] to emit as it is (an `Event` with content sends a
 * message, and an output goes in the return value, not in the `Event`), and ask the user with
 * [requestInput].
 */
@AdkJavaInteropApi
@ExperimentalWorkflowApi
fun interface NodeBody<I, O> {
  @Throws(Exception::class) fun apply(context: Context, input: I): O

  companion object {
    /**
     * Asks the user for input and ends the body's run, so it never returns; the answer becomes the
     * node's output unless the node reruns on resume. Parameters are as in [RequestInput] (a single
     * argument is the interrupt id, not the message), and a `catch (Throwable t)` around the call
     * cancels the request. [payload] must be JSON-native (maps, lists, strings, numbers, booleans);
     * JVM code uses `FunctionNodes.requestInput`, which also takes a record or POJO.
     */
    @JvmStatic
    @JvmOverloads
    fun <T> requestInput(
      interruptId: String = Uuid.random(),
      message: String? = null,
      payload: Any? = null,
      responseSchema: Schema? = null,
    ): T =
      throw NodeInterruptedException(RequestInput(interruptId, message, payload, responseSchema))
  }
}

/** The JSON form of [content], as a session saves it. */
private fun contentJson(content: Content): Any? =
  jsonElementToAny(adkJson.encodeToJsonElement(Content.serializer(), content))

/** The interrupt id prefix of a node run's confirmation request, followed by the node path. */
private const val CONFIRMATION_INTERRUPT_PREFIX = "wf_tool_confirmation:"

/** The output of a run the user rejected. */
private val REJECTED_OUTPUT: Map<String, Any?> = mapOf("error" to "This tool call is rejected.")

/** The schema of a confirmation answer. */
private val TOOL_CONFIRMATION_SCHEMA: Schema? by lazy {
  SchemaUtils.inferSchema(typeOf<ToolConfirmation>())
}

/** Whether this value is already in the form a schema checks: JSON-native, or a message. */
private fun Any?.isJsonForm(): Boolean =
  when (this) {
    null,
    is String,
    is Number,
    is Boolean,
    is Content -> true
    is Map<*, *> -> entries.all { (key, value) -> key is String && value.isJsonForm() }
    is Collection<*> -> all { it.isJsonForm() }
    else -> false
  }

/** Types a node handles by their meaning rather than as data. */
private val FRAMEWORK_TYPES: Set<KClass<*>> = setOf(Content::class, Event::class)

/**
 * Port types that need no serializer: a framework value passes as it is, `Any` is a JSON value, and
 * `Unit` carries nothing.
 */
private val SERIALIZER_FREE_TYPES: Set<KClass<*>> = FRAMEWORK_TYPES + setOf(Any::class, Unit::class)

/** Infers an output port's schema, except for framework types, which pass through as they are. */
private fun inferredOutputSchema(codec: PortCodec<*>): Schema? =
  if (codec.classifier in FRAMEWORK_TYPES) null else codec.outputSchema

/**
 * Returns [value] as a node outputs it and a schema checks it: a framework value as it is, and
 * anything else as JSON.
 */
internal fun PortCodec<*>.schemaForm(value: Any?, what: String): Any? =
  if (classifier in FRAMEWORK_TYPES || value is Content || value is Event) {
    value
  } else {
    encode(value, what)
  }

/**
 * Creates a [FunctionNode] that runs [block], which uses `context.state`, `context.updateState` and
 * [requestInput]. An edge into it builds only if the predecessor's output schema fits its input
 * schema, except a rejected confirmation's output; inferred output schemas require every field,
 * inferred input schemas only fields without defaults. Explicit schemas apply each run.
 *
 * For example, an output `DefOrder(id, total, qty = 1)` feeds an input `ReqOrder(id, total, qty)`:
 * the output always carries `qty`, which the input requires.
 *
 * The block's return value, a [Content] included, is the node's output; emitting an event with an
 * output or calling `Context.setOutput` fails the node. A node that talks to an agent node sends
 * the message with `emit(content)`. A node has no auth configuration.
 *
 * @param name The node's name, required: non-empty and without '/', '@' or '.'.
 * @param inputSchema Checks the input as it arrives, before [I]'s defaults apply; an `Any?` port
 *   then gets the checked value.
 * @param rerunOnResume Whether a resume runs the node again rather than using the resuming answer;
 *   unset, it is true when [requireConfirmation] is true.
 * @param requireConfirmation Whether each run first asks the user to approve it: pass `true`, or a
 *   predicate that decides for each input. The request's payload holds the input's JSON form as
 *   `args`; a rejected run skips the block and outputs `{"error": "This tool call is rejected."}`
 *   unchecked; the answer is in [Context.toolConfirmation].
 * @throws IllegalArgumentException if [name] is empty or contains '/', '@', or '.'.
 * @throws WorkflowConfigurationException if [I] or [O], or a type argument inside it, is a sealed
 *   type, a nested `ByteArray`, or has no usable serializer and is not `Content`, `Event`, `Any`,
 *   or `Unit`, or if [requireConfirmation] is true with `rerunOnResume = false`.
 */
@ExperimentalWorkflowApi
inline fun <reified I, reified O> node(
  name: String,
  description: String = "",
  rerunOnResume: Boolean? = null,
  waitForOutput: Boolean = false,
  config: NodeConfig = NodeConfig(),
  inputSchema: Schema? = null,
  outputSchema: Schema? = null,
  stateSchema: Schema? = null,
  requireConfirmation: Boolean = false,
  crossinline block: suspend FlowCollector<Event>.(context: Context, input: I) -> O,
): FunctionNode<I, O> =
  buildFunctionNode(
    name = name,
    description = description,
    rerunOnResume = rerunOnResume,
    waitForOutput = waitForOutput,
    config = config,
    inputSchema = inputSchema,
    outputSchema = outputSchema,
    stateSchema = stateSchema,
    requireConfirmation = requireConfirmation,
    confirmationPredicate = null,
    block = block,
  )

/**
 * Creates a [FunctionNode] as the other [node] overload does, asking the user to approve a run only
 * when [requireConfirmation] returns true for its decoded input.
 *
 * @param rerunOnResume Whether a resume runs the node again rather than using the resuming answer;
 *   unset, it is true.
 * @param requireConfirmation Decides for each input whether a run asks the user to approve it; it
 *   runs on each run that has no answer yet, and once the node has asked, the answer decides. A
 *   rejected run outputs as the other [node] overload describes.
 * @throws IllegalArgumentException if [name] is invalid, as in the other [node] overload.
 * @throws WorkflowConfigurationException if [I] or [O] is not a valid port type, as in the other
 *   [node] overload, or with `rerunOnResume = false`.
 */
@ExperimentalWorkflowApi
inline fun <reified I, reified O> node(
  name: String,
  description: String = "",
  rerunOnResume: Boolean? = null,
  waitForOutput: Boolean = false,
  config: NodeConfig = NodeConfig(),
  inputSchema: Schema? = null,
  outputSchema: Schema? = null,
  stateSchema: Schema? = null,
  noinline requireConfirmation: suspend (context: Context, input: I) -> Boolean,
  crossinline block: suspend FlowCollector<Event>.(context: Context, input: I) -> O,
): FunctionNode<I, O> =
  buildFunctionNode(
    name = name,
    description = description,
    rerunOnResume = rerunOnResume,
    waitForOutput = waitForOutput,
    config = config,
    inputSchema = inputSchema,
    outputSchema = outputSchema,
    stateSchema = stateSchema,
    requireConfirmation = false,
    confirmationPredicate = requireConfirmation,
    block = block,
  )

/** Builds the [FunctionNode] that both [node] overloads create. */
@PublishedApi
internal inline fun <reified I, reified O> buildFunctionNode(
  name: String,
  description: String,
  rerunOnResume: Boolean?,
  waitForOutput: Boolean,
  config: NodeConfig,
  inputSchema: Schema?,
  outputSchema: Schema?,
  stateSchema: Schema?,
  requireConfirmation: Boolean,
  noinline confirmationPredicate: (suspend (Context, I) -> Boolean)?,
  crossinline block: suspend FlowCollector<Event>.(context: Context, input: I) -> O,
): FunctionNode<I, O> {
  requirePortSerializer(name, "input", typeOf<I>())
  requirePortSerializer(name, "output", typeOf<O>())
  return FunctionNode(
    name = name,
    description = description,
    rerunOnResume = rerunOnResume ?: (requireConfirmation || confirmationPredicate != null),
    waitForOutput = waitForOutput,
    config = config,
    inputSchema = inputSchema,
    outputSchema = outputSchema,
    stateSchema = stateSchema,
    inputType = typeOf<I>(),
    outputType = typeOf<O>(),
    requireConfirmation = requireConfirmation,
    confirmationPredicate = confirmationPredicate,
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
 * inside it, is not a valid port type, so the node fails when it is built rather than on its first
 * value.
 */
@PublishedApi
internal fun requirePortSerializer(name: String, port: String, type: KType) {
  val problem = portTypeProblem(type, nested = false) ?: return
  throw WorkflowConfigurationException("node '$name' has $port type $type, $problem")
}

/** Returns why [type] or one of its type arguments is not a valid port type, or null if it is. */
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
  if (serializer.descriptor.isSealedForm()) {
    val name = (classifier as? KClass<*>)?.simpleName ?: "it"
    val what = if (nested) "which has a sealed type argument $name" else "which is a sealed type"
    return "$what; declare the port as a concrete @Serializable class, wrap the hierarchy in " +
      "one, or use Any for a JSON-native value."
  }
  return type.arguments.firstNotNullOfOrNull { argument ->
    argument.type?.let { portTypeProblem(it, nested = true) }
  }
}

/** Whether this is a sealed type's descriptor: a class discriminator and a value, not a class. */
private fun SerialDescriptor.isSealedForm(): Boolean =
  kind != StructureKind.CLASS &&
    elementsCount == 2 &&
    getElementName(0) == "type" &&
    getElementName(1) == "value"

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
 * [config], the schemas, [rerunOnResume] and [requireConfirmation] apply to each item's run, so
 * each item asks for its own approval; the fan-out node shares [waitForOutput] and [stateSchema],
 * declares no input or output schema, has no retry or timeout, and always reruns on resume.
 *
 * @param maxParallelWorkers Maximum number of items run at a time, or `null` for no limit.
 * @param requireConfirmation Whether each item's run first asks the user to approve it: pass
 *   `true`, or a predicate that decides for each item, as in [node], which describes a rejected
 *   run's output.
 * @throws IllegalArgumentException if [name] is invalid, as in [node].
 * @throws WorkflowConfigurationException if [maxParallelWorkers] is less than 1, or as in [node].
 */
@ExperimentalWorkflowApi
inline fun <reified I, reified O> parallelNode(
  name: String,
  description: String = "",
  rerunOnResume: Boolean? = null,
  waitForOutput: Boolean = false,
  maxParallelWorkers: Int? = null,
  config: NodeConfig = NodeConfig(),
  inputSchema: Schema? = null,
  outputSchema: Schema? = null,
  stateSchema: Schema? = null,
  requireConfirmation: Boolean = false,
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
        requireConfirmation = requireConfirmation,
        block = block,
      ),
    maxParallelWorkers = maxParallelWorkers,
  )

/**
 * Creates a fan-out [FunctionNode] as the other block-form [parallelNode] does, asking to approve
 * an item's run only when [requireConfirmation] returns true for it. The predicate runs on each
 * item's run with no answer yet; once the item has asked, the answer decides. A rejected item
 * outputs as in [node].
 *
 * @param maxParallelWorkers Maximum number of items run at a time, or `null` for no limit.
 * @throws IllegalArgumentException if [name] is invalid, as in [node].
 * @throws WorkflowConfigurationException if [maxParallelWorkers] is less than 1, or as in [node].
 */
@ExperimentalWorkflowApi
inline fun <reified I, reified O> parallelNode(
  name: String,
  description: String = "",
  rerunOnResume: Boolean? = null,
  waitForOutput: Boolean = false,
  maxParallelWorkers: Int? = null,
  config: NodeConfig = NodeConfig(),
  inputSchema: Schema? = null,
  outputSchema: Schema? = null,
  stateSchema: Schema? = null,
  noinline requireConfirmation: suspend (context: Context, input: I) -> Boolean,
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
        requireConfirmation = requireConfirmation,
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
