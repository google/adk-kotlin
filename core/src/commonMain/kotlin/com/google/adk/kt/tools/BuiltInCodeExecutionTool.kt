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
import com.google.adk.kt.models.Model
import com.google.adk.kt.models.shortName
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.Tool
import com.google.adk.kt.types.ToolCodeExecution

/**
 * A built-in tool that lets Gemini models write and run code on the model's serving side while
 * generating a response. Like [GoogleSearchTool], nothing runs locally: [processLlmRequest] adds
 * the model's code-execution tool to the request, and images the code produces are saved as
 * artifacts. Only Gemini models support it, so a request for any other model fails.
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
    require(isGeminiModel(llmRequest.model)) {
      "Gemini code execution tool is not supported for model ${llmRequest.model?.name}."
    }
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

/**
 * Whether [model] is a Gemini model, accepting the name forms ADK Python accepts: a bare name, a
 * Vertex AI, Apigee or `models/` path, or a provider prefix such as `openrouter/google/`.
 */
private fun isGeminiModel(model: Model?): Boolean {
  val name = model?.name ?: return false
  val shortName = model.shortName
  // Only a name with no known path prefix may carry a provider prefix, as in ADK Python.
  if (shortName != name || name.startsWith("projects/")) return shortName.startsWith("gemini-")
  return name.substringAfterLast('/').startsWith("gemini-")
}
