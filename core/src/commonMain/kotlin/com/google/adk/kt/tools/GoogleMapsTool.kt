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
import com.google.adk.kt.types.GenerateContentConfig
import com.google.adk.kt.types.GoogleMaps
import com.google.adk.kt.types.Tool

/**
 * A built-in tool that is automatically invoked by Gemini 2 models to retrieve search results from
 * Google Maps.
 *
 * This tool operates internally within the model and does not require or perform local code
 * execution.
 */
class GoogleMapsTool(val model: String? = null) :
  BaseTool(name = "google_maps", description = "google_maps") {

  override fun declaration(): FunctionDeclaration? = null

  override suspend fun run(context: ToolContext, args: Map<String, Any>): Any {
    throw UnsupportedOperationException("GoogleMapsTool does not support local execution")
  }

  override suspend fun processLlmRequest(
    toolContext: ToolContext,
    llmRequest: LlmRequest,
  ): LlmRequest {
    val effectiveModelName = model ?: llmRequest.model?.name
    if (effectiveModelName == null) {
      throw IllegalArgumentException("Model name was not defined.")
    }

    val config = llmRequest.config
    val existingTools = config.tools?.toMutableList() ?: mutableListOf()

    // Check if a Google Maps tool is already present in the existing tools.
    val hasMapsTool = existingTools.any { it.googleMaps != null }
    if (hasMapsTool) {
      return llmRequest
    }

    if (!isGeminiModel(effectiveModelName)) {
      throw IllegalArgumentException(
        "Google maps tool is not supported for model $effectiveModelName"
      )
    }

    existingTools.add(Tool(googleMaps = GoogleMaps()))
    return llmRequest.copy(config = GenerateContentConfig(tools = existingTools))
  }

  private fun isGeminiModel(modelName: String): Boolean {
    return modelName.startsWith("gemini")
  }
}
