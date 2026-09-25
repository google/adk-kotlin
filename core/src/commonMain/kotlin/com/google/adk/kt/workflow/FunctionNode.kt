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
import com.google.adk.kt.agents.Context
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.events.Event
import com.google.adk.kt.logging.LoggerFactory
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Schema
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow

/** Where a [FunctionNode] takes its parameter values from. */
@ExperimentalWorkflowApi
enum class ParameterBinding {
  /** From session state, each parameter looked up by name. */
  STATE,

  /** From the upstream node's output, which must be a map keyed by parameter name. */
  NODE_INPUT,
}

/**
 * One parameter a [FunctionNode] takes.
 *
 * Kotlin has no runtime view of a lambda's parameters, so a function node declares them. That is
 * what lets the runtime bind by name, validate against the declared type, and fall back to a
 * default the way the other ADK implementations do.
 *
 * @property name The key looked up in state or in the node input.
 * @property schema The declared type, used to validate the bound value. Null accepts it as-is.
 * @property hasDefault Whether [defaultValue] applies when the value is absent. Without it, an
 *   absent value fails the node.
 * @property defaultValue The value used when absent. A non-null default must match [schema].
 */
@ExperimentalWorkflowApi
class NodeParam(
  val name: String,
  val schema: Schema? = null,
  val hasDefault: Boolean = false,
  val defaultValue: Any? = null,
) {
  init {
    require(
      !hasDefault || schema == null || SchemaUtils.validateValue(defaultValue, schema).isSuccess
    ) {
      "The default value of parameter \"$name\" does not match its schema."
    }
  }

  companion object {
    /** A parameter that must be present. */
    fun required(name: String, schema: Schema? = null): NodeParam = NodeParam(name, schema)

    /** A parameter that falls back to [defaultValue] when absent. */
    fun optional(name: String, defaultValue: Any?, schema: Schema? = null): NodeParam =
      NodeParam(name, schema, hasDefault = true, defaultValue = defaultValue)
  }
}

/**
 * A node whose behavior is a Kotlin function.
 *
 * The body runs with a [FlowCollector] receiver, so it can emit values as it goes, like a
 * generator, and return a final value; emitted and returned values are treated alike. Its
 * parameters are resolved before it runs, per [parameterBinding], and handed to it as a map keyed
 * by [NodeParam.name].
 */
@ExperimentalWorkflowApi
class FunctionNode(
  override val name: String,
  override val description: String = "",
  val params: List<NodeParam> = emptyList(),
  val parameterBinding: ParameterBinding = ParameterBinding.STATE,
  override val rerunOnResume: Boolean = false,
  override val waitForOutput: Boolean = false,
  override val config: NodeConfig = NodeConfig(),
  override val inputSchema: Schema? = null,
  override val outputSchema: Schema? = null,
  override val stateSchema: Schema? = null,
  private val body: suspend FlowCollector<Any?>.(Context, Map<String, Any?>) -> Any?,
) : Node {

  init {
    validateNodeName(name)
    val duplicates = params.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys
    require(duplicates.isEmpty()) {
      "FunctionNode '$name' declares these parameters more than once: $duplicates"
    }
  }

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    val arguments = bindParameters(context, nodeInput)
    val collector = FlowCollector<Any?> { value -> this@flow.emit(toEmission(value)) }
    emit(toEmission(collector.body(context, arguments)))
  }

  /**
   * Sends a [Content] the body emits or returns as an event's content rather than as the node's
   * output. The engine handles every other value, skipping `null` and `Unit`.
   */
  private fun toEmission(value: Any?): Any? {
    if (value !is Content) return value
    // The author is left empty here and stamped later by the node runner.
    return Event(author = "", content = value)
  }

  private fun bindParameters(context: Context, nodeInput: Any?): Map<String, Any?> {
    val fromNodeInput = parameterBinding == ParameterBinding.NODE_INPUT
    val source: Map<String, Any?> =
      if (fromNodeInput) {
        (nodeInput as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to v } ?: emptyMap()
      } else {
        context.state
      }

    return params.associate { param ->
      // Under state binding the reserved name takes the upstream output itself, not a state value.
      if (!fromNodeInput && param.name == NODE_INPUT_PARAM) {
        return@associate param.name to bindValue(param, nodeInput)
      }
      when {
        source.containsKey(param.name) -> param.name to bindValue(param, source[param.name])
        param.hasDefault -> param.name to param.defaultValue
        else ->
          throw IllegalArgumentException(
            "Missing value for parameter \"${param.name}\" of function \"$name\". It was not" +
              " found in ${if (fromNodeInput) "node_input" else "state"} and has no default value."
          )
      }
    }
  }

  private fun bindValue(param: NodeParam, value: Any?): Any? {
    val schema = param.schema ?: return value
    // A parameter that takes a string, directly or through anyOf, receives the Content's text.
    if (value is Content && SchemaUtils.acceptsString(schema)) {
      return contentToStr(value, param.name)
    }
    // As in Python, a Content's text is read as JSON here, while a String is never parsed.
    return SchemaUtils.validateValue(value, schema, argsName = param.name).getOrElse { e ->
      throw IllegalArgumentException(
        "Cannot bind parameter \"${param.name}\" of function \"$name\": ${e.message}",
        e,
      )
    }
  }

  private fun contentToStr(content: Content, paramName: String): String {
    val hasNonTextParts =
      content.parts.any {
        it.text == null &&
          (it.inlineData != null || it.fileData != null || it.executableCode != null)
      }
    if (hasNonTextParts) {
      logger.warn {
        "Parameter \"$paramName\" of function \"$name\" takes a string, so the non-text parts" +
          " (inline data, file data, or executable code) of its Content are dropped."
      }
    }
    return content.text(includeThoughts = true)
  }

  companion object {
    /**
     * The reserved parameter name that receives the upstream output verbatim under STATE binding.
     */
    @ExperimentalWorkflowApi const val NODE_INPUT_PARAM: String = "node_input"

    private val logger = LoggerFactory.getLogger(FunctionNode::class)
  }
}
