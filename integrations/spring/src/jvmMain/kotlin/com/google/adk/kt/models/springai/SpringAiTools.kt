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
package com.google.adk.kt.models.springai

import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.tools.toJsonSchema
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.GenerateContentConfig
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import java.util.function.Function
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.model.tool.StructuredOutputChatOptions
import org.springframework.ai.model.tool.ToolCallingChatOptions
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.function.FunctionToolCallback

/** Converts each ADK tool declaration on the request to a schema-only Spring AI tool callback. */
internal fun LlmRequest.toolCallbacks(): List<ToolCallback> =
  config.tools?.flatMap { it.functionDeclarations ?: emptyList() }?.map { it.toToolCallback() }
    ?: emptyList()

/**
 * Builds a Spring AI [ToolCallback] that carries only the tool's schema. ADK owns the agent loop
 * and executes tools itself, and Spring AI 2.0's [org.springframework.ai.chat.model.ChatModel]
 * never runs tools, so the callback body must never be invoked; it throws if it ever is, surfacing
 * the mistake loudly rather than double-executing a tool.
 */
private fun FunctionDeclaration.toToolCallback(): ToolCallback {
  val schema = parameters ?: Schema(type = Type.OBJECT, properties = emptyMap())
  val neverCalled =
    Function<Map<String, Any?>, String> {
      throw UnsupportedOperationException(
        "ADK executes tools; Spring AI must not invoke tool '$name' directly."
      )
    }
  return FunctionToolCallback.builder(name, neverCalled)
    .description(description)
    .inputType(Map::class.java)
    .inputSchema(schema.toJsonSchemaString())
    .build()
}

/**
 * Serializes an ADK [Schema] to a JSON Schema string. Delegates to ADK core's converter so a tool's
 * schema does not depend on which backend renders it.
 */
@OptIn(FrameworkInternalApi::class)
internal fun Schema.toJsonSchemaString(): String = toJsonSchema().toString()

/**
 * Builds the prompt's [ChatOptions] by overlaying ADK tools and generation config onto the model's
 * own default options.
 *
 * Starting from [defaultOptions] (the model's own [ChatOptions]) preserves the concrete
 * provider-specific option type and its settings, avoiding the ClassCastException some providers
 * throw when they cast `Prompt.getOptions()` to their own type. Returns null when there is nothing
 * ADK-specific to set, letting the model apply its own defaults.
 */
internal fun buildChatOptions(
  config: GenerateContentConfig,
  toolCallbacks: List<ToolCallback>,
  defaultOptions: ChatOptions?,
): ChatOptions? {
  val hasTools = toolCallbacks.isNotEmpty()
  val hasConfig = config.hasGenerationParams()
  // Return null only when the model's own defaults carry no tool callbacks. When they do, fall
  // through so the branches below clear them; otherwise default tools leak into a turn that
  // declares none (Spring substitutes the model's default options for a null-options prompt).
  val hasDefaultTools =
    (defaultOptions as? ToolCallingChatOptions)?.toolCallbacks?.isNotEmpty() == true
  if (!hasTools && !hasConfig && !hasDefaultTools) return null

  return when {
    defaultOptions is ToolCallingChatOptions -> {
      val builder = defaultOptions.mutate()
      // Always set toolCallbacks, even to empty, so callbacks from the model's own defaults do not
      // leak into a request that declares no tools.
      builder.toolCallbacks(if (hasTools) toolCallbacks else emptyList())
      builder.applyGenerationConfig(config)
      builder.build()
    }
    hasTools -> {
      // defaultOptions is null or not tool-calling; start fresh but carry over its model so the
      // request still targets the model the caller configured on defaultOptions.
      val builder = ToolCallingChatOptions.builder()
      builder.toolCallbacks(toolCallbacks)
      defaultOptions?.model?.let { builder.model(it) }
      builder.applyGenerationConfig(config)
      builder.build()
    }
    // Only generation config to apply: mutate defaultOptions to keep its provider type and model,
    // falling back to a plain ChatOptions when there is no defaultOptions.
    defaultOptions != null ->
      defaultOptions.mutate().apply { applyGenerationConfig(config) }.build()
    else -> ChatOptions.builder().apply { applyGenerationConfig(config) }.build()
  }
}

// Keep the parameter set here in sync with hasGenerationParams below: a param applied here but not
// checked there is silently dropped when it is the only thing set (buildChatOptions returns null).
private fun ChatOptions.Builder<*>.applyGenerationConfig(config: GenerateContentConfig) {
  config.temperature?.let { temperature(it.toDouble()) }
  config.maxOutputTokens?.let { maxTokens(it) }
  config.topP?.let { topP(it.toDouble()) }
  config.topK?.let { topK(it) }
  config.stopSequences?.let { stopSequences(it) }
  config.frequencyPenalty?.let { frequencyPenalty(it.toDouble()) }
  config.presencePenalty?.let { presencePenalty(it.toDouble()) }
  // Map an ADK responseSchema to Spring AI's portable outputSchema, honored by providers whose
  // options implement StructuredOutputChatOptions (others cannot carry it).
  config.responseSchema?.let { schema ->
    (this as? StructuredOutputChatOptions.Builder<*>)?.outputSchema(schema.toJsonSchemaString())
  }
}

private fun GenerateContentConfig.hasGenerationParams(): Boolean =
  temperature != null ||
    maxOutputTokens != null ||
    topP != null ||
    topK != null ||
    stopSequences != null ||
    frequencyPenalty != null ||
    presencePenalty != null ||
    responseSchema != null
