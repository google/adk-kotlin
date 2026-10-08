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
import com.google.adk.kt.agents.RunConfig
import com.google.adk.kt.events.Event
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.types.GenerateContentConfig
import com.google.adk.kt.types.LiveConnectConfig

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
    val liveConnectConfig =
      (context.runConfig?.let { request.liveConnectConfig.applyRunConfig(it) }
          ?: request.liveConnectConfig)
        .foldSamplingFrom(config)
    return request.copy(model = agent.model, config = config, liveConnectConfig = liveConnectConfig)
  }
}

/**
 * Folds the agent's sampling settings into this live config, as ADK Python's `basic.py` does.
 *
 * Applied for every request, live or not; the live config's own value wins, otherwise the agent's
 * [config] value is used.
 */
private fun LiveConnectConfig.foldSamplingFrom(config: GenerateContentConfig): LiveConnectConfig =
  copy(
    temperature = temperature ?: config.temperature,
    topP = topP ?: config.topP,
    topK = topK ?: config.topK,
    maxOutputTokens = maxOutputTokens ?: config.maxOutputTokens,
    mediaResolution = mediaResolution ?: config.mediaResolution,
    seed = seed ?: config.seed,
  )

/**
 * Returns this config with the connect-time settings of [runConfig] applied.
 *
 * Applied for every request, live or not, as ADK Python does; a non-live request simply never reads
 * the result. Every setting passes through on every model and the server decides what it accepts.
 */
private fun LiveConnectConfig.applyRunConfig(runConfig: RunConfig): LiveConnectConfig =
  copy(
    responseModalities = runConfig.responseModalities.ifEmpty { null },
    speechConfig = runConfig.speechConfig,
    outputAudioTranscription = runConfig.outputAudioTranscription,
    inputAudioTranscription = runConfig.inputAudioTranscription,
    realtimeInputConfig = runConfig.realtimeInputConfig,
    explicitVadSignal = runConfig.explicitVadSignal,
    translationConfig = runConfig.translationConfig,
    enableAffectiveDialog = runConfig.enableAffectiveDialog,
    proactivity = runConfig.proactivity,
    sessionResumption = runConfig.sessionResumption,
    contextWindowCompression = runConfig.contextWindowCompression,
  )
