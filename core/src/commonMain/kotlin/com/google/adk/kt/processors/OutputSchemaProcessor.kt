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
package com.google.adk.kt.processors

import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.events.Event
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.canUseOutputSchemaWithTools
import com.google.adk.kt.serialization.Json
import com.google.adk.kt.tools.SetModelResponseTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role

/** Instruction added to the request when the [SetModelResponseTool] workaround is active. */
private const val SET_MODEL_RESPONSE_INSTRUCTION =
  "IMPORTANT: You have access to other tools, but you must provide your final response using the " +
    "set_model_response tool with the required structured format. After using any other tools " +
    "needed to complete the task, always call set_model_response with your final answer in the " +
    "specified schema format."

/**
 * A processor that enables structured output for agents that also have tools.
 *
 * Some models (e.g. Gemini 2.x) cannot use a response schema while tools are present. For those
 * models this processor exposes the [SetModelResponseTool] and instructs the model to call it with
 * the final structured answer. For models that support a response schema together with tools, the
 * schema is applied directly by [BasicRequestProcessor] and this processor is a no-op.
 *
 * Follows the Java ADK `OutputSchema` processor. The Python ADK's `_OutputSchemaRequestProcessor`
 * is structurally similar but gates on a different rule (see [canUseOutputSchemaWithTools]) and
 * supports non-object schemas; this implementation matches Java's object-schema behavior.
 */
internal class OutputSchemaProcessor : LlmRequestProcessor {
  override suspend fun process(
    context: InvocationContext,
    request: LlmRequest,
    emitEvent: suspend (Event) -> Unit,
  ): LlmRequest {
    val agent = context.agent as? LlmAgent ?: return request
    val outputSchema = agent.outputSchema ?: return request

    // When the schema is applied directly to the request config by BasicRequestProcessor, this
    // processor is a no-op.
    if (agent.appliesOutputSchemaDirectly) {
      return request
    }

    val toolContext = ToolContext(invocationContext = context)
    val withTool = SetModelResponseTool(outputSchema).processLlmRequest(toolContext, request)
    return withTool.appendInstructions(
      Content(parts = listOf(Part(text = SET_MODEL_RESPONSE_INSTRUCTION)))
    )
  }
}

/**
 * Whether the model will be offered any tools, for the purpose of [LlmAgent.outputSchema] gating.
 *
 * This is broader than just [LlmAgent.tools]/[LlmAgent.toolsets]: it also counts the
 * framework-injected `transfer_to_agent` tool that [AgentTransferProcessor] attaches whenever the
 * agent can transfer to a sub-agent, parent, or peer (see [findTransferTargets]). That tool is
 * subject to the same Gemini 2.x "response schema cannot be combined with tools" limitation, so it
 * must participate in the decision between applying the schema directly and falling back to the
 * [SetModelResponseTool] workaround.
 *
 * Both [BasicRequestProcessor] and [OutputSchemaProcessor] call this single predicate so their
 * decisions cannot drift apart. Note this is intentionally stricter than the Java/Python ADK, which
 * only inspect the agent's declared tools and therefore would emit a response schema alongside the
 * `transfer_to_agent` tool on Gemini 2.x.
 */
internal fun LlmAgent.hasToolsForOutputSchemaGating(): Boolean =
  tools.isNotEmpty() || toolsets.isNotEmpty() || findTransferTargets(this).isNotEmpty()

/**
 * Whether [LlmAgent.outputSchema] can be applied directly to the request config instead of via the
 * [SetModelResponseTool] workaround.
 *
 * True when the agent has no tools (see [hasToolsForOutputSchemaGating]) or runs on a model that
 * supports a response schema together with tools (see [canUseOutputSchemaWithTools]). Both
 * [BasicRequestProcessor] (which sets the response schema when this holds) and
 * [OutputSchemaProcessor] (which installs the workaround when it does not) consult this single
 * predicate so their decisions cannot drift apart.
 */
internal val LlmAgent.appliesOutputSchemaDirectly: Boolean
  get() = !hasToolsForOutputSchemaGating() || model.canUseOutputSchemaWithTools

/**
 * Extracts the structured JSON response from a [SetModelResponseTool] call, if present.
 *
 * Note: the serialized arguments come from the function-response map, which the tool-execution
 * machinery builds by dropping entries whose value is `null`. So a `set_model_response` call with
 * an explicit `null` for a nullable field is serialized without that field. This matches the Python
 * ADK (which uses `exclude_none=True`) but differs from the direct-schema path, where the model's
 * JSON text is parsed verbatim and explicit `null`s are preserved.
 *
 * @param functionResponseEvent The function-response event produced after executing tool calls.
 * @return The JSON-serialized arguments of the `set_model_response` call, or `null` if the event
 *   did not contain such a call.
 */
internal fun getStructuredModelResponse(functionResponseEvent: Event): String? {
  for (functionResponse in functionResponseEvent.functionResponses()) {
    if (functionResponse.name == SetModelResponseTool.NAME) {
      return Json.toJsonString(functionResponse.response)
    }
  }
  return null
}

/**
 * Builds a synthetic final model-response event from a [SetModelResponseTool] JSON payload so that
 * downstream logic (state saving, turn termination) treats it like a normal text response.
 *
 * @param context The invocation context.
 * @param jsonResponse The JSON response captured from the `set_model_response` call.
 * @return An event whose content is the JSON response authored by the current agent.
 */
internal fun createFinalModelResponseEvent(
  context: InvocationContext,
  jsonResponse: String,
): Event =
  Event(
    invocationId = context.invocationId,
    author = context.agent.name,
    branch = context.branch,
    content = Content(role = Role.MODEL, parts = listOf(Part(text = jsonResponse))),
  )
