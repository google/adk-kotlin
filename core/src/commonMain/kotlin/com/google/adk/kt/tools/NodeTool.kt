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

package com.google.adk.kt.tools

import com.google.adk.kt.agents.BaseAgent
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.collections.concurrentMutableListOf
import com.google.adk.kt.events.Event
import com.google.adk.kt.sessions.State
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import com.google.adk.kt.workflow.BaseNode
import com.google.adk.kt.workflow.EventSink
import com.google.adk.kt.workflow.FunctionNode
import com.google.adk.kt.workflow.Node
import com.google.adk.kt.workflow.NodeInputValidationException
import com.google.adk.kt.workflow.NodeRunner
import com.google.adk.kt.workflow.isJsonNative
import com.google.adk.kt.workflow.validateInput
import kotlin.jvm.JvmOverloads
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A tool that runs a workflow [Node], such as a [Workflow][com.google.adk.kt.workflow.Workflow], on
 * the model's arguments, on a branch of its own for each call, and answers with the node's output.
 * The node's events are not emitted; its state and artifact changes, and a request to skip
 * summarization made by the node itself, reach the calling agent. If the node fails, [run] rethrows
 * its exception, and if it pauses for input or is a workflow or `waitForOutput` node that ends
 * without an output, [run] throws [IllegalStateException].
 *
 * @property node the node to run; it needs an [Node.inputSchema] unless it is a [FunctionNode]
 *   whose input type is `Unit`
 * @param name the tool's name; when null or empty, the node's name
 * @param description the tool's description; when null or empty, the node's description, or a
 *   generated one if that is empty too
 * @constructor throws [IllegalArgumentException] if [node] is an agent, lacks a required input
 *   schema, or is a [FunctionNode] taking a message or event
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
      response = node.outputSchema,
    )

  override suspend fun run(context: ToolContext, args: Map<String, Any?>): Any {
    val nodeInput =
      try {
        parseInput(args)
      } catch (e: IllegalArgumentException) {
        return buildValidationError(e)
      } catch (e: NodeInputValidationException) {
        return buildValidationError(e)
      }
    return toJsonNative(runNodeInToolContext(nodeInput, context))
      ?: mapOf(BaseTool.RESULT_KEY to null)
  }

  /** Reads the node's input from [args], undoing [asObjectParameters], and validates it. */
  private fun parseInput(args: Map<String, Any?>): Any? {
    val schema = node.inputSchema
    val raw =
      when {
        takesNoArguments(node) -> null
        schema != null && schema.type != Type.OBJECT -> args[REQUEST_PROPERTY]
        else -> args
      }
    val typed = if (node is FunctionNode<*, *>) node.decodeInput(raw) else raw
    return if (node is BaseNode) node.checkInput(typed) else node.validateInput(typed)
  }

  /** Answers a call whose arguments do not fit the node, so the model can correct them. */
  private fun buildValidationError(e: Exception): Map<String, String> =
    mapOf(
      FunctionTool.ERROR_KEY to
        "Invoking `$name()` failed due to argument validation errors:\n${e.message}\n" +
          "You could retry calling this tool with corrected argument types."
    )

  /**
   * Runs [node] on this call's own branch against a copy of the session, so the node's steps see
   * each other's events and state while only [context]'s actions carry its changes out. Throws the
   * node's own error if it fails, so tool error callbacks see the real cause.
   */
  private suspend fun runNodeInToolContext(nodeInput: Any?, context: ToolContext): Any? {
    val callId = context.functionCallId?.takeIf { it.isNotEmpty() }
    val segment = if (callId != null) "$name@$callId" else name
    val baseBranch = context.branch
    val toolBranch = if (baseBranch.isNullOrEmpty()) segment else "$baseBranch.$segment"

    val session = context.invocationContext.session
    val scratchSession =
      session.copy(
        state = State(context.state),
        events = concurrentMutableListOf<Event>().apply { addAll(session.events) },
      )
    val lock = Mutex()
    val sink = EventSink { event, _ ->
      if (event.partial) return@EventSink
      lock.withLock {
        scratchSession.state.applyDelta(event.actions.stateDelta)
        scratchSession.state.applyTempDelta(event.actions.stateDelta)
        scratchSession.events.add(event)
        context.actions.stateDelta.putAll(event.actions.stateDelta)
        context.actions.artifactDelta.putAll(event.actions.artifactDelta)
      }
    }
    val childContext =
      NodeRunner.runDetached(
        node = node,
        context = context.invocationContext.copy(session = scratchSession, branch = toolBranch),
        eventSink = sink,
        nodeInput = nodeInput,
      )

    if (childContext.actions.skipSummarization) context.actions.skipSummarization = true
    childContext.requireNodeState().failure?.let { throw it.cause }
    check(childContext.interruptIds.isEmpty() && !childContext.isWaitingForOutput) {
      "NodeTool '$name' cannot answer: node '${node.name}' paused for input or ended without an" +
        " output, and a tool call cannot pause yet."
    }
    return childContext.output
  }

  /** Returns [output] as JSON data, encoding a function node's typed output. */
  private fun toJsonNative(output: Any?): Any? =
    if (output != null && !isJsonNative(output) && node is FunctionNode<*, *>) {
      node.encodeOutput(output)
    } else {
      output
    }
}

private const val REQUEST_PROPERTY = "request"

/** Whether [node] is a function node with no schema whose `Unit` input needs no arguments. */
private fun takesNoArguments(node: Node): Boolean =
  node.inputSchema == null && node is FunctionNode<*, *> && node.inputType.classifier == Unit::class

/**
 * Returns [schema] unchanged when it is already an object, else wraps it under a single
 * [REQUEST_PROPERTY], because a function declaration's parameters must be an object schema.
 */
private fun asObjectParameters(schema: Schema): Schema =
  if (schema.type == Type.OBJECT) {
    schema
  } else {
    Schema(
      type = Type.OBJECT,
      properties = mapOf(REQUEST_PROPERTY to schema),
      required = listOf(REQUEST_PROPERTY),
    )
  }
