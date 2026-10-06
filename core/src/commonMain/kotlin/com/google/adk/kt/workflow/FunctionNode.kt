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

package com.google.adk.kt.workflow

import com.google.adk.kt.SchemaUtils
import com.google.adk.kt.agents.Context
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.events.Event
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Schema
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.typeOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow

/**
 * A workflow [Node] backed by a suspending function that may emit progress [Event]s and returns an
 * output of type [O]. Its schemas inferred from [I] and [O] only describe it; an explicit schema
 * passed to [node] is also enforced.
 *
 * Inline workflow steps use [node].
 *
 * @param I Expected type of `nodeInput` from predecessor nodes.
 * @param O Type of output value this node returns (`Unit`/`null` emits no output event; [Event]
 *   passes through directly; a [Content] becomes a message event with no node output; any other
 *   value becomes the node's output on an [Event]).
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
  private val body: suspend FlowCollector<Event>.(Context, I) -> O,
) :
  BaseNode(
    name = name,
    description = description,
    rerunOnResume = rerunOnResume,
    waitForOutput = waitForOutput,
    config = config,
    inputSchema = explicitInputSchema ?: inferredPortSchema(inputCodec),
    outputSchema = explicitOutputSchema ?: inferredPortSchema(outputCodec),
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
    body = body,
  )

  internal val inputType: KType
    get() = inputCodec.kType

  internal val outputType: KType
    get() = outputCodec.kType

  init {
    validateNodeName(name)
  }

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    // A no-op after validateInput, and it covers a direct call.
    val input = inputCodec.coerce(nodeInput, "input of node '$name'")
    when (val result = body(context, input)) {
      // As in adk-python and Go, a returned Content is a message and not the node's output.
      is Content -> emit(Event(content = result))
      else -> emit(result)
    }
  }

  /** Makes [nodeInput] an [I], then checks its JSON form against an explicit [inputSchema]. */
  override fun validateInput(nodeInput: Any?): Any? {
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

  /** Checks the JSON form of [output] against an explicit [outputSchema] and returns [output]. */
  override fun validateOutput(output: Any?): Any? {
    if (explicitOutputSchema != null) {
      val what = "output of node '$name'"
      val unused =
        SchemaUtils.validateValue(outputCodec.schemaForm(output, what), explicitOutputSchema, what)
          .getOrThrow()
    }
    return output
  }
}

/** Types a node handles by their meaning rather than as data. */
private val FRAMEWORK_TYPES: Set<KClass<*>> = setOf(Content::class, Event::class)

/** Infers a port's schema, except for framework types. */
private fun inferredPortSchema(codec: ValueCodec<*>): Schema? =
  if (codec.kType.classifier in FRAMEWORK_TYPES) null else codec.schema

/** Returns [value] as a schema checks it: a framework value as it is, and anything else as JSON. */
private fun ValueCodec<*>.schemaForm(value: Any?, what: String): Any? =
  if (kType.classifier in FRAMEWORK_TYPES || value is Content || value is Event) {
    value
  } else {
    encode(value, what)
  }

/**
 * Creates a [FunctionNode] that runs [block] on an input of type [I] and returns an output of type
 * [O], emitting progress [Event]s through its [FlowCollector] receiver. Schemas inferred from [I]
 * and [O] describe the node, while an explicit [inputSchema] or [outputSchema] is also enforced on
 * every run.
 */
@ExperimentalWorkflowApi
// typeOf<T>() is a compiler intrinsic and does not require kotlin-reflect.
@Suppress("KotlinReflectNeeded")
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
): FunctionNode<I, O> =
  FunctionNode(
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
