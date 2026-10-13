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

package com.google.adk.kt.agents

import com.google.adk.kt.SchemaUtils
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.events.Event
import com.google.adk.kt.events.applyRewinds
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.tools.AgentTool
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.tools.inputSchemaOf
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import com.google.adk.kt.workflow.DynamicNodeFailedException
import com.google.adk.kt.workflow.ToolParameters
import kotlinx.coroutines.CancellationException

/**
 * Ends a [AgentMode.TASK] agent's task. A call whose arguments match the agent's output schema is
 * answered with [SUCCESS_RESULT]; any other call gets a validation error so the model can retry.
 */
internal class FinishTaskTool(taskAgent: LlmAgent) :
  BaseTool(
    name = NAME,
    description =
      if (taskAgent.outputSchema != null)
        "$DESCRIPTION Pass the required output data in the parameters."
      else DESCRIPTION,
  ) {

  private val parameters =
    ToolParameters(taskAgent.outputSchema ?: DEFAULT_OUTPUT_SCHEMA, BaseTool.RESULT_KEY)

  /** Names the checked value in a validation error. */
  private val argumentsLabel =
    parameters.wrapperKey?.let { "$NAME argument '$it'" } ?: "$NAME arguments"

  override fun declaration(): FunctionDeclaration =
    FunctionDeclaration(name = name, description = description, parameters = parameters.declaration)

  override suspend fun processLlmRequest(
    toolContext: ToolContext,
    llmRequest: LlmRequest,
  ): LlmRequest =
    super.processLlmRequest(toolContext, llmRequest)
      .appendInstructions(Content(parts = listOf(Part(text = INSTRUCTION))))

  override suspend fun run(context: ToolContext, args: Map<String, Any?>): Any {
    // validateValue accepts null, so require a wrapped value unless its schema is nullable.
    val key = parameters.wrapperKey
    if (key != null && args[key] == null && parameters.schema.nullable != true) {
      return validationError("$NAME args does not contain required $key")
    }
    return SchemaUtils.validateValue(parameters.valueOf(args), parameters.schema, argumentsLabel)
      .fold(onSuccess = { SUCCESS_RESULT }, onFailure = { validationError(it.message) })
  }

  private fun validationError(message: String?): Map<String, String> =
    mapOf("error" to "Invoking `$name()` failed due to validation errors:\n$message\n$RETRY_HINT")

  /** Returns the task output a call's [args] carry, as the model sent it. */
  fun outputOf(args: Map<String, Any?>): Any? = parameters.valueOf(args)

  companion object {
    const val NAME = "finish_task"
    const val SUCCESS_RESULT = "Task completed."
    const val ERROR_RESULT = "Task failed."
    private const val DESCRIPTION =
      "Signal that this agent has completed its delegated task. Call this when you have finished" +
        " your delegated task."
    private const val RETRY_HINT =
      "You could retry calling this tool, but it is IMPORTANT for you to provide all the" +
        " mandatory parameters with correct types."
    private const val INSTRUCTION =
      "Do NOT call `finish_task` prematurely. Use your available tools to\n" +
        "fully complete every aspect of the delegated task first. If the\n" +
        "task is unclear, ask the user for clarification before proceeding.\n" +
        "Once the task is fully complete, call `finish_task` by itself with\n" +
        "no accompanying text output."
    private val DEFAULT_OUTPUT_SCHEMA =
      Schema(
        type = Type.OBJECT,
        properties =
          mapOf(
            "result" to
              Schema(
                type = Type.STRING,
                description = "A brief summary of what the agent accomplished.",
              )
          ),
        required = listOf("result"),
      )

    /** Whether [event] answers `finish_task` with a result that ends the task. */
    fun isTerminalResponse(event: Event): Boolean {
      val response = event.functionResponses().firstOrNull { it.name == NAME } ?: return false
      return response.response[BaseTool.RESULT_KEY] in TERMINAL_RESULTS
    }

    private val TERMINAL_RESULTS = setOf(SUCCESS_RESULT, ERROR_RESULT)
  }
}

/**
 * Offers a [AgentMode.TASK] sub-agent to its parent as a tool. The call marks the delegation so the
 * parent runs the task agent and answers the call with the task's output.
 */
internal class TaskAgentTool(private val agent: BaseAgent) :
  BaseTool(agent.name, agent.description) {

  override val defersResponse: Boolean
    get() = true

  override fun declaration(): FunctionDeclaration =
    FunctionDeclaration(
      name = name,
      description = "$description$TASK_TOOL_SUFFIX".trim(),
      parameters = inputSchemaOf(agent) ?: DEFAULT_TASK_INPUT_SCHEMA,
    )

  override suspend fun run(context: ToolContext, args: Map<String, Any?>): Any = Unit

  private companion object {
    const val TASK_TOOL_SUFFIX =
      "\nIMPORTANT: This tool delegates execution to a specialized agent. Do NOT call this tool in" +
        " parallel with any other tools."
    val DEFAULT_TASK_INPUT_SCHEMA =
      Schema(
        type = Type.OBJECT,
        properties =
          mapOf(
            AgentTool.REQUEST_KEY to
              Schema(
                type = Type.STRING,
                description = "Detailed instructions or context for the task sub-agent.",
              )
          ),
        required = listOf(AgentTool.REQUEST_KEY),
      )
  }
}

/**
 * Offers a [AgentMode.SINGLE_TURN] sub-agent to its parent as a tool: the call runs the sub-agent
 * as a child node of the parent's node on its own sub-branch and answers with its output.
 */
internal class SingleTurnAgentTool(private val agent: BaseAgent) :
  BaseTool(agent.name, agent.description) {

  override fun declaration(): FunctionDeclaration =
    FunctionDeclaration(
      name = name,
      description = description,
      parameters =
        inputSchemaOf(agent)
          ?: Schema(
            type = Type.OBJECT,
            properties = mapOf(AgentTool.REQUEST_KEY to Schema(type = Type.STRING)),
            required = listOf(AgentTool.REQUEST_KEY),
          ),
    )

  override suspend fun run(context: ToolContext, args: Map<String, Any?>): Any {
    val schema = inputSchemaOf(agent)
    val nodeInput =
      if (schema == null) {
        args[AgentTool.REQUEST_KEY]
      } else {
        SchemaUtils.validateValue(args, schema).getOrElse { e ->
          return "Error validating input: ${e.message}"
        }
      }
    // The tool runs inside its parent's node, which is where the sub-agent runs as a child.
    val parentNode =
      checkNotNull(context.invocationContext.frameworkData.nodeContext) {
        "Single-turn agent '${agent.name}' can only run as a tool of an agent in a workflow node."
      }
    return try {
      parentNode.runNodeUnchecked(
        agent,
        nodeInput = nodeInput,
        runId = context.functionCallId,
        useSubBranch = true,
      ) ?: mapOf(BaseTool.RESULT_KEY to null)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      val cause = (e as? DynamicNodeFailedException)?.error ?: e
      "Error running sub-agent: ${cause.message}"
    }
  }
}

/**
 * Returns the task delegations in [event]: its calls to a [TaskAgentTool]. A partial event yields
 * none, since its arguments may still be arriving.
 */
internal fun taskDelegationsIn(event: Event, tools: Map<String, BaseTool>): List<FunctionCall> {
  if (event.partial) return emptyList()
  return event.functionCalls().filter { !it.id.isNullOrEmpty() && tools[it.name] is TaskAgentTool }
}

/**
 * Whether [event] calls a known tool that answers in the same model step, which is any tool but a
 * [TaskAgentTool].
 */
internal fun hasEagerToolCalls(event: Event, tools: Map<String, BaseTool>): Boolean =
  event.functionCalls().any { call ->
    val tool = tools[call.name]
    tool != null && tool !is TaskAgentTool
  }

/**
 * Returns the task delegations [owner] made that have no function response yet, across the live
 * [events] left after rewinds. The scan ignores isolation scopes, because a coordinator's
 * delegation from an earlier turn sits in that turn's scope.
 */
internal fun unresolvedTaskDelegations(
  events: List<Event>,
  owner: String,
  tools: Map<String, BaseTool>,
): List<FunctionCall> {
  val callsById = linkedMapOf<String, FunctionCall>()
  val answeredIds = mutableSetOf<String>()
  for (event in applyRewinds(events)) {
    if (event.author != owner && event.author != Role.USER) continue
    for (part in event.content?.parts.orEmpty()) {
      val call = part.functionCall
      val callId = call?.id
      if (call != null && !callId.isNullOrEmpty() && tools[call.name] is TaskAgentTool) {
        callsById[callId] = call
      }
      part.functionResponse?.id?.let(answeredIds::add)
    }
  }
  return callsById.filterKeys { it !in answeredIds }.values.toList()
}

/**
 * Builds the user-authored function response that answers a task delegation [call] with the task's
 * [output]: a map output is the response itself, anything else is wrapped under `output`.
 */
internal fun taskResponseEvent(call: FunctionCall, output: Any?): Event {
  val response = (output as? Map<*, *>)?.mapKeys { it.key.toString() } ?: mapOf("output" to output)
  return Event(
    author = Role.USER,
    content =
      Content(
        role = Role.USER,
        parts =
          listOf(
            Part(
              functionResponse =
                FunctionResponse(id = call.id, name = call.name, response = response)
            )
          ),
      ),
  )
}
