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

package com.google.adk.kt.tools

import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.Tool
import com.google.adk.kt.types.ToolCodeExecution

/**
 * A built-in tool that lets Gemini models write and run code on the model's serving side while
 * generating a response. As with [GoogleSearchTool], nothing runs locally and ADK does not check
 * the model; the backend decides whether it supports code execution. Images the code produces are
 * saved as artifacts, so the runner needs an artifact service.
 */
class BuiltInCodeExecutionTool : BaseTool(name = "code_execution", description = "code_execution") {

  override fun declaration(): FunctionDeclaration? = null

  override suspend fun run(context: ToolContext, args: Map<String, Any?>): Any {
    throw UnsupportedOperationException(
      "BuiltInCodeExecutionTool runs inside the model and does not support local execution"
    )
  }

  override suspend fun processLlmRequest(
    toolContext: ToolContext,
    llmRequest: LlmRequest,
  ): LlmRequest {
    val config = llmRequest.config
    val existingTools = config.tools?.toMutableList() ?: mutableListOf()

    // Idempotent: don't add a second code-execution tool if one is already present.
    if (existingTools.any { it.codeExecution != null }) {
      return llmRequest
    }

    existingTools.add(Tool(codeExecution = ToolCodeExecution()))
    return llmRequest.copy(config = config.copy(tools = existingTools))
  }
}
