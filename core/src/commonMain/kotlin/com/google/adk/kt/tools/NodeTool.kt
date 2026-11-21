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

@file:OptIn(ExperimentalWorkflowApi::class)

package com.google.adk.kt.tools

import com.google.adk.kt.acceptsNull
import com.google.adk.kt.agents.BaseAgent
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.ids.Uuid
import com.google.adk.kt.serialization.anyToJsonElement
import com.google.adk.kt.serialization.jsonElementToAny
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import com.google.adk.kt.workflow.BranchPath
import com.google.adk.kt.workflow.DynamicNodeFailedException
import com.google.adk.kt.workflow.FunctionNode
import com.google.adk.kt.workflow.Node
import com.google.adk.kt.workflow.NodeInputValidationException
import com.google.adk.kt.workflow.ParallelWorkerNode
import com.google.adk.kt.workflow.START_NODE_NAME
import com.google.adk.kt.workflow.Workflow
import com.google.adk.kt.workflow.validateInput
import com.google.adk.kt.workflow.validateNodeName
import kotlin.jvm.JvmOverloads

/**
 * A tool that runs a workflow [Node], such as a [Workflow], on the model's arguments and answers
 * with the node's output. It works only for an agent on the node path, such as an app's root node
 * or a workflow step, and not in a live run. Each call runs the node under that agent's path, on a
 * `<tool>@<callId>` branch that keeps the node's messages out of the model's history.
 *
 * @property node the node to run, which needs an input schema unless it is a [FunctionNode] whose
 *   input type is `Unit`
 * @param name the tool's name; when null or empty, the node's name
 * @param description the tool's description; when null or empty, the node's description, or a
 *   generated one if that is empty too
 * @constructor throws [IllegalArgumentException] if [node] is an agent or a parallel node, has a
 *   name with '/', '@' or '.', lacks a required input schema, or is a [FunctionNode] taking a
 *   message or event
 */
@ExperimentalWorkflowApi
class NodeTool
@JvmOverloads
constructor(val node: Node, name: String? = null, description: String? = null) :
  BaseTool(
    name = name?.ifEmpty { null } ?: node.name,
    description =
      description?.ifEmpty { null }
        ?: node.description.ifEmpty { "Executes the node: ${node.name}" },
  ) {

  init {
    require(node !is BaseAgent) {
      "Agent '${node.name}' cannot be wrapped as a NodeTool. Agents should be invoked as" +
        " sub-agents instead."
    }
    validateNodeName(node.name)
    require(itemNodeOf(node) == null) {
      "NodeTool cannot wrap node '${node.name}': it is a parallel node, which declares no input" +
        " schema of its own. Wrap it in a Workflow with an inputSchema instead."
    }
    require(node.inputSchema != null || takesNoArguments(node)) {
      "Node '${node.name}' does not have an inputSchema defined. NodeTool requires an explicit" +
        " inputSchema on the wrapped node."
    }
    require(node !is FunctionNode<*, *> || !node.takesFrameworkInput) {
      "NodeTool cannot wrap node '${node.name}': its input type is Content or Event, and tool" +
        " arguments are JSON data that cannot become a message or an event. Give the node a data" +
        " input type instead."
    }
  }

  override fun declaration(): FunctionDeclaration =
    FunctionDeclaration(
      name = name,
      description = description,
      parameters = node.inputSchema?.let(::asObjectParameters),
      // Vertex rejects a response schema holding anyOf, failing every request, so it is left out.
      response = node.outputSchema?.takeUnless { it.containsAnyOf() },
    )

  /**
   * Runs [node] on [args] and answers with its output, or with an `error` the model can retry on if
   * the node rejects them. A request to skip summarization, from the node or one of its workflow
   * steps, reaches the calling agent. If the node fails, this rethrows its exception.
   *
   * @throws IllegalStateException if the calling agent is not on the node path or runs live, if the
   *   node pauses for input, or if a workflow or `waitForOutput` node ends without an output.
   */
  @OptIn(FrameworkInternalApi::class)
  override suspend fun run(context: ToolContext, args: Map<String, Any?>): Any {
    // The node decodes the arguments itself; this check only turns bad ones into an error answer.
    val nodeInput =
      try {
        readInput(args).also { checkInput(node, it) }
      } catch (e: IllegalArgumentException) {
        return InternalToolHelpers.argumentValidationError(name, e.message)
      } catch (e: NodeInputValidationException) {
        return InternalToolHelpers.argumentValidationError(name, e.message)
      }
    // A tool result must be plain JSON, so any Content or JsonElement in the output is converted.
    val output = jsonElementToAny(anyToJsonElement(runNodeInToolContext(nodeInput, context)))
    return output ?: mapOf(RESULT_KEY to null)
  }

  /** Reads the node's input from [args], undoing [asObjectParameters]. */
  private fun readInput(args: Map<String, Any?>): Any? {
    val schema = node.inputSchema
    return when {
      // init leaves a node without a schema only when it takes no arguments.
      schema == null -> null
      schema.type != Type.OBJECT -> {
        require(AgentTool.REQUEST_KEY in args) {
          "Missing required argument \"${AgentTool.REQUEST_KEY}\"."
        }
        args[AgentTool.REQUEST_KEY]
      }
      else -> args
    }
  }

  /**
   * Runs [node] on this call's own branch and returns its output, throwing the node's own error if
   * it fails so tool error callbacks see the real cause.
   */
  private suspend fun runNodeInToolContext(nodeInput: Any?, context: ToolContext): Any? {
    val callId = context.functionCallId?.takeIf { it.isNotEmpty() } ?: Uuid.random()
    // The branch keeps the raw call ID, which the history filter matches to the call.
    val toolBranch = BranchPath.subBranch(context.branch, name, callId)
    // A call ID that isn't path-safe gets a fresh run ID, so calls never share one.
    val runId = callId.takeIf { '/' !in it && '@' !in it && '.' !in it } ?: Uuid.random()

    return try {
      context.runNode(
        node,
        nodeInput,
        runId = runId,
        overrideBranch = toolBranch,
        raiseOnWait = true,
      )
    } catch (e: DynamicNodeFailedException) {
      throw e.error
    }
  }
}

/** Whether [node] is a function node whose `Unit` input needs no arguments. */
private fun takesNoArguments(node: Node): Boolean =
  node is FunctionNode<*, *> && node.inputType?.classifier == Unit::class

/** The node [node] runs on each item of its input if it is a parallel node, else null. */
private fun itemNodeOf(node: Node): Node? =
  when (node) {
    is ParallelWorkerNode -> node.node
    is FunctionNode<*, *> -> node.itemNode
    else -> null
  }

/**
 * Validates [input] for [node] and for each node it passes the input to: a workflow's entry nodes
 * (START successors that don't wait for all predecessors), and a parallel node's item node, once
 * per item. Throws the first validation error.
 */
private fun checkInput(node: Node, input: Any?) {
  // The schema check lets null through, so a schema that rejects null is checked here.
  if (input == null) {
    node.inputSchema?.let {
      require(it.acceptsNull()) { "Node '${node.name}' takes no null input." }
    }
  }
  val checked = node.validateInput(input)
  if (node is Workflow) {
    for (edge in node.edges) {
      if (edge.from.name == START_NODE_NAME && !edge.to.requiresAllPredecessors) {
        checkInput(edge.to, checked)
      }
    }
  }
  itemNodeOf(node)?.let { itemNode ->
    // A parallel node runs a non-list input as one item.
    val items = checked as? List<*> ?: listOf(checked)
    for (item in items) checkInput(itemNode, item)
  }
}

/**
 * Returns [schema] unchanged when it is already an object, else wraps it under a single
 * [AgentTool.REQUEST_KEY], because a function declaration's parameters must be an object schema.
 */
private fun asObjectParameters(schema: Schema): Schema =
  if (schema.type == Type.OBJECT) {
    schema
  } else {
    Schema(
      type = Type.OBJECT,
      properties = mapOf(AgentTool.REQUEST_KEY to schema),
      required = listOf(AgentTool.REQUEST_KEY),
    )
  }

/** Whether this schema or one nested in its properties or items has an `anyOf`. */
private fun Schema.containsAnyOf(): Boolean =
  anyOf != null ||
    items?.containsAnyOf() == true ||
    properties?.values?.any { it.containsAnyOf() } == true
