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
import com.google.adk.kt.types.GenerateContentConfig

/**
 * A processor that handles basic information to build the LLM request.
 *
 * It sets the model and configuration from the agent to the request. When the agent has an
 * [LlmAgent.outputSchema] that [appliesOutputSchemaDirectly] (no tools, or a model that supports a
 * response schema together with tools), the schema is applied as the request's response schema
 * (with a JSON response MIME type). Otherwise the schema is handled via the [OutputSchemaProcessor]
 * workaround.
 */
internal class BasicRequestProcessor : LlmRequestProcessor {
  override suspend fun process(
    context: InvocationContext,
    request: LlmRequest,
    emitEvent: suspend (Event) -> Unit,
  ): LlmRequest {
    require(context.agent is LlmAgent) { "BasicRequestProcessor requires an LlmAgent." }
    val agent = context.agent
    val baseConfig = agent.generateContentConfig ?: GenerateContentConfig()
    val config =
      agent.outputSchema
        ?.takeIf { agent.appliesOutputSchemaDirectly }
        ?.let { outputSchema ->
          baseConfig.copy(responseSchema = outputSchema, responseMimeType = "application/json")
        } ?: baseConfig
    return request.copy(model = agent.model, config = config)
  }
}
