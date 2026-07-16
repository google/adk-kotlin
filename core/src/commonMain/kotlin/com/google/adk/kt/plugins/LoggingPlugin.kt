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

package com.google.adk.kt.plugins

import com.google.adk.kt.agents.CallbackContext
import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.callbacks.CallbackChoice
import com.google.adk.kt.events.Event
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.logging.LoggerFactory
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.Content

/**
 * A plugin that logs a high volume of requests and responses handled by the agent at each callback
 * point.
 *
 * This plugin is primarily intended for ADK development and debugging purposes, helping to print
 * all critical events in the console.
 *
 * **CAUTION**: The plugin logs raw requests / responses, including user prompts. Be mindful of
 * sensitive data disclosure.
 */
class LoggingPlugin(override val name: String = "logging_plugin") : Plugin {

  private fun log(message: String) {
    logger.info { "[$name] $message" }
  }

  override suspend fun onUserMessage(
    invocationContext: InvocationContext,
    userMessage: Content,
  ): Content {
    log("🚀 USER MESSAGE RECEIVED")
    log("   Invocation ID: ${invocationContext.invocationId}")
    log("   Session ID: ${invocationContext.session.key.id}")
    log("   User ID: ${invocationContext.session.key.userId}")
    log("   App Name: ${invocationContext.session.key.appName}")
    log("   Root Agent: ${invocationContext.agent.name}")
    log("   User Content: ${formatContent(userMessage)}")
    invocationContext.branch?.let { log("   Branch: $it") }
    return userMessage
  }

  override suspend fun beforeRun(
    invocationContext: InvocationContext
  ): CallbackChoice<Unit, Content> {
    log("🏃 INVOCATION STARTING")
    log("   Invocation ID: ${invocationContext.invocationId}")
    log("   Starting Agent: ${invocationContext.agent.name}")
    return CallbackChoice.Continue(Unit)
  }

  override suspend fun onEvent(invocationContext: InvocationContext, event: Event): Event {
    log("📢 EVENT YIELDED")
    log("   Event ID: ${event.id}")
    log("   Author: ${event.author}")
    log("   Content: ${formatContent(event.content)}")
    log("   Final Response: ${event.isFinalResponse}")

    val functionCalls = event.functionCalls()
    if (functionCalls.isNotEmpty()) {
      val funcCallsStr = functionCalls.joinToString(", ") { it.name }
      log("   Function Calls: [$funcCallsStr]")
    }

    val functionResponses = event.functionResponses()
    if (functionResponses.isNotEmpty()) {
      val funcResponsesStr = functionResponses.joinToString(", ") { it.name }
      log("   Function Responses: [$funcResponsesStr]")
    }

    if (event.longRunningToolIds.isNotEmpty()) {
      log("   Long Running Tools: ${event.longRunningToolIds}")
    }

    return event
  }

  override suspend fun afterRun(invocationContext: InvocationContext) {
    log("✅ INVOCATION COMPLETED")
    log("   Invocation ID: ${invocationContext.invocationId}")
    log("   Final Agent: ${invocationContext.agent.name}")
  }

  override suspend fun beforeAgent(
    context: CallbackContext
  ): CallbackChoice<EventActions, Content> {
    log("🤖 AGENT STARTING")
    log("   Agent Name: ${context.agentName}")
    log("   Invocation ID: ${context.invocationId}")
    context.branch?.let { log("   Branch: $it") }
    return CallbackChoice.Continue(EventActions())
  }

  override suspend fun afterAgent(context: CallbackContext): CallbackChoice<Unit, Content> {
    log("🤖 AGENT COMPLETED")
    log("   Agent Name: ${context.agentName}")
    log("   Invocation ID: ${context.invocationId}")
    return CallbackChoice.Continue(Unit)
  }

  override suspend fun beforeModel(
    context: CallbackContext,
    request: LlmRequest,
  ): CallbackChoice<LlmRequest, LlmResponse> {
    log("🧠 LLM REQUEST")
    log("   Model: ${request.model?.name ?: "default"}")
    log("   Agent: ${context.agentName}")

    request.config.systemInstruction?.let { sysInstruction ->
      val textParts = sysInstruction.parts.mapNotNull { it.text }
      if (textParts.isNotEmpty()) {
        var truncatedInstruction = textParts.joinToString("")
        if (truncatedInstruction.length > MAX_CONTENT_LENGTH) {
          truncatedInstruction = truncatedInstruction.substring(0, MAX_CONTENT_LENGTH) + "..."
        }
        log("   System Instruction: '$truncatedInstruction'")
      }
    }

    val tools = request.config.tools
    if (!tools.isNullOrEmpty()) {
      val toolNames = tools.flatMap { it.functionDeclarations ?: emptyList() }.map { it.name }
      log("   Available Tools: [${toolNames.joinToString(", ")}]")
    }

    return CallbackChoice.Continue(request)
  }

  override suspend fun afterModel(context: CallbackContext, response: LlmResponse): LlmResponse {
    log("🧠 LLM RESPONSE")
    log("   Agent: ${context.agentName}")

    if (response.errorCode != null) {
      log("   ❌ ERROR - Code: ${response.errorCode}")
      log("   Error Message: ${response.errorMessage ?: "None"}")
    } else {
      log("   Content: ${formatContent(response.content)}")
      if (response.partial) {
        log("   Partial: ${response.partial}")
      }
      response.finishReason?.let { log("   Finish Reason: $it") }
    }

    response.usageMetadata?.let { usage ->
      log(
        "   Token Usage - Input: ${usage.promptTokenCount}, Output: ${usage.candidatesTokenCount}"
      )
    }

    return response
  }

  override suspend fun onModelError(
    context: CallbackContext,
    request: LlmRequest,
    error: Throwable,
  ): CallbackChoice<Unit, LlmResponse> {
    log("🧠 LLM ERROR")
    log("   Agent: ${context.agentName}")
    log("   Error: ${error.message}")
    logger.error(error) { "[$name] LLM Error" }
    return CallbackChoice.Continue(Unit)
  }

  override suspend fun beforeTool(
    context: ToolContext,
    tool: BaseTool,
    args: Map<String, Any>,
  ): CallbackChoice<Map<String, Any>, Map<String, Any>> {
    log("🔧 TOOL STARTING")
    log("   BaseTool Name: ${tool.name}")
    log("   Agent: ${context.invocationContext.agent.name}")
    log("   Function Call ID: ${context.functionCallId ?: "None"}")
    log("   Arguments: ${formatArgs(args)}")
    return CallbackChoice.Continue(args)
  }

  override suspend fun afterTool(
    context: ToolContext,
    tool: BaseTool,
    args: Map<String, Any>,
    result: Map<String, Any>,
  ): Map<String, Any> {
    log("🔧 TOOL COMPLETED")
    log("   BaseTool Name: ${tool.name}")
    log("   Agent: ${context.invocationContext.agent.name}")
    log("   Function Call ID: ${context.functionCallId ?: "None"}")
    log("   Result: ${formatArgs(result)}")
    return result
  }

  override suspend fun onToolError(
    context: ToolContext,
    tool: BaseTool,
    args: Map<String, Any>,
    error: Throwable,
  ): CallbackChoice<Unit, Map<String, Any>> {
    log("🔧 TOOL ERROR")
    log("   BaseTool Name: ${tool.name}")
    log("   Agent: ${context.invocationContext.agent.name}")
    log("   Function Call ID: ${context.functionCallId ?: "None"}")
    log("   Arguments: ${formatArgs(args)}")
    log("   Error: ${error.message}")
    return CallbackChoice.Continue(Unit)
  }

  fun formatContent(content: Content?): String {
    if (content == null || content.parts.isEmpty()) {
      return "None"
    }

    return content.parts
      .map { part ->
        val text = part.text
        val functionCall = part.functionCall
        val functionResponse = part.functionResponse
        when {
          text != null -> {
            val trimmed = text.trim()
            val truncated =
              if (trimmed.length > MAX_CONTENT_LENGTH) {
                trimmed.substring(0, MAX_CONTENT_LENGTH) + "..."
              } else {
                trimmed
              }
            "text: '$truncated'"
          }
          functionCall != null -> "function_call: ${functionCall.name}"
          functionResponse != null -> "function_response: ${functionResponse.name}"

          else -> "other_part"
        }
      }
      .joinToString(" | ")
  }

  fun formatArgs(args: Map<String, Any>?): String {
    if (args.isNullOrEmpty()) {
      return "{}"
    }

    val formatted = args.toString()
    return if (formatted.length > MAX_ARGS_LENGTH) {
      formatted.substring(0, MAX_ARGS_LENGTH) + "...}"
    } else {
      formatted
    }
  }

  companion object {
    private val logger = LoggerFactory.getLogger(LoggingPlugin::class)

    const val MAX_CONTENT_LENGTH = 200
    const val MAX_ARGS_LENGTH = 300
  }
}
