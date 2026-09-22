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
@file:JvmName("ToolNodes")

package com.google.adk.kt.workflow

import com.google.adk.kt.agents.Context
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.ids.Uuid
import com.google.adk.kt.serialization.adkJson
import com.google.adk.kt.serialization.jsonElementToAny
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.Content
import kotlin.jvm.JvmName
import kotlin.jvm.JvmOverloads
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerializationException

/**
 * Returns a workflow node that runs this tool as a fixed step: the predecessor's output becomes the
 * tool's arguments, and the tool's result becomes the node's output.
 *
 * Use it to put an existing tool into a graph, such as a built-in tool, an MCP tool, or an agent
 * wrapped as a tool.
 *
 * **Input.** The node accepts:
 * - a map of arguments;
 * - a string, or a [Content], whose text is a JSON object;
 * - `null`, or a string or [Content] whose text is blank or the JSON literal `null`, which calls
 *   the tool with no arguments.
 *
 * Anything else fails the node, including plain text that is not JSON, a JSON array, and a number.
 * A required parameter that the input lacks is read from session state when state holds that key,
 * as in Python ADK.
 *
 * **Validation.** The node declares no input or output schema and checks the arguments against
 * none. The tool validates its own arguments in [BaseTool.run], as it does when a model calls it.
 *
 * **Output.** The tool's return value, unchanged, is the node's output; a tool that returns
 * nothing, including a long-running tool that defers its result, gives the node no output. The
 * tool's state and artifact changes travel on the node's output event. Actions that only a model
 * call can honor are dropped: a confirmation request, an agent transfer, and the skip-summarization
 * flag.
 *
 * **Errors.** An exception the tool throws fails the node, and the retry policy in `config`
 * applies. A retry runs the tool again, so a tool with side effects should be safe to repeat. A
 * tool may instead report a problem, such as a missing required parameter, by returning an error
 * map for a model to read. In a graph no model reads it: the error map becomes the node's output
 * and reaches the next node like any other value. A tool that requires confirmation returns its
 * placeholder error the same way, and its confirmation request is dropped.
 *
 * **Identity.** Each call returns a new node. To use one tool at several places in a graph, convert
 * it once and reuse the returned node: two conversions share the tool's name, and a graph rejects
 * two distinct nodes with the same name.
 *
 * The tool receives a function call id generated for each run, because no model call produced one.
 *
 * @param name the node's name. Defaults to the tool's name, which must then be a valid node name:
 *   no `/`, `@` or `.`.
 * @param config the node's retry policy and execution timeout.
 */
@ExperimentalWorkflowApi
@JvmOverloads
fun BaseTool.asNode(name: String = this.name, config: NodeConfig = NodeConfig()): Node =
  ToolNode(this, name, config)

/** Runs a [BaseTool] as a workflow node. Created by [asNode], which documents its behavior. */
internal class ToolNode(private val tool: BaseTool, name: String, config: NodeConfig) :
  BaseNode(name = name, description = tool.description, config = config) {

  init {
    validateNodeName(name)
  }

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    val args = argumentsFrom(nodeInput).toMutableMap()
    val state = context.state
    for (param in tool.declaration()?.parameters?.required.orEmpty()) {
      if (param !in args && param in state) args[param] = state[param]
    }
    // No model issued this call, so the id is generated; a tool may still key on it.
    val toolContext = ToolContext(context.invocationContext, functionCallId = Uuid.random())
    val result = tool.run(toolContext, args)
    // Only the tool's deltas carry over, onto the node's output event. Other actions, such as a
    // confirmation request or an agent transfer, have no meaning for a node.
    context.mergeEventActions(
      EventActions(
        stateDelta = toolContext.actions.stateDelta,
        artifactDelta = toolContext.actions.artifactDelta,
      )
    )
    emit(result)
  }

  /** Reads the tool's arguments from [nodeInput]; [asNode] lists the accepted shapes. */
  private fun argumentsFrom(nodeInput: Any?): Map<String, Any?> =
    when (nodeInput) {
      null -> emptyMap()
      is Map<*, *> -> nodeInput.toArguments()
      is String -> argumentsFromText(nodeInput)
      is Content -> argumentsFromText(nodeInput.text())
      else -> throw notArguments(nodeInput::class.simpleName.toString())
    }

  /** Reads arguments from [inputText]: none for blank text or JSON `null`, else a JSON object. */
  private fun argumentsFromText(inputText: String): Map<String, Any?> {
    if (inputText.isBlank()) return emptyMap()
    val element =
      try {
        adkJson.parseToJsonElement(inputText)
      } catch (e: SerializationException) {
        throw notArguments(NOT_A_JSON_OBJECT)
      }
    return when (val json = jsonElementToAny(element)) {
      null -> emptyMap()
      is Map<*, *> -> json.toArguments()
      else -> throw notArguments(NOT_A_JSON_OBJECT)
    }
  }

  private fun Map<*, *>.toArguments(): Map<String, Any?> = entries.associate { (key, value) ->
    key.toString() to value
  }

  private fun notArguments(got: String) =
    IllegalArgumentException(
      "The input to tool node '$name' must be a dictionary of tool arguments, a JSON object as" +
        " text, or null, but got $got."
    )

  private companion object {
    const val NOT_A_JSON_OBJECT = "text that is not a JSON object"
  }
}
