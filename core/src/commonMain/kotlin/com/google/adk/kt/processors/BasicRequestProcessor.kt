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
import com.google.adk.kt.logging.LoggerFactory
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.isGemini3XLive
import com.google.adk.kt.types.GenerateContentConfig
import com.google.adk.kt.types.LiveConnectConfig
import com.google.adk.kt.types.Modality

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
      (context.runConfig?.let { request.liveConnectConfig.applyRunConfig(it, agent.model.name) }
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
 * the result.
 */
private fun LiveConnectConfig.applyRunConfig(
  runConfig: RunConfig,
  modelName: String,
): LiveConnectConfig {
  // Gemini 3.x live rejects non-audio output, affective dialog and proactivity, so they're dropped.
  val gemini3XLive = isGemini3XLive(modelName)
  return copy(
    responseModalities =
      runConfig.responseModalities
        .let { if (gemini3XLive) answerableModalities(it) else it }
        .ifEmpty { null },
    speechConfig = runConfig.speechConfig,
    outputAudioTranscription = runConfig.outputAudioTranscription,
    inputAudioTranscription = runConfig.inputAudioTranscription,
    realtimeInputConfig = runConfig.realtimeInputConfig,
    explicitVadSignal = runConfig.explicitVadSignal,
    translationConfig = runConfig.translationConfig,
    enableAffectiveDialog = if (gemini3XLive) null else runConfig.enableAffectiveDialog,
    proactivity = if (gemini3XLive) null else runConfig.proactivity,
    sessionResumption = runConfig.sessionResumption,
    contextWindowCompression = runConfig.contextWindowCompression,
  )
}

/**
 * Returns the [requested] response modalities a Gemini 3.x live model can actually answer in.
 *
 * Audio is the only one (it is a native-audio model), while video and text remain *input*
 * modalities, so dropping them from the response side costs a caller nothing it could have had.
 *
 * An empty list passes through as no preference, and a list whose modalities are all dropped names
 * audio explicitly so a caller who asked only for video still gets a connection that speaks.
 */
private fun answerableModalities(requested: List<Modality>): List<Modality> {
  if (requested.isEmpty()) return requested
  val (answerable, dropped) = requested.partition { it == Modality.AUDIO }
  if (dropped.isNotEmpty()) {
    // Logged so a caller who asked for text can see why the answer is audio.
    logger.info { "Gemini 3.x live answers in audio only; dropped $dropped." }
  }
  return answerable.ifEmpty { listOf(Modality.AUDIO) }
}

private val logger = LoggerFactory.getLogger(BasicRequestProcessor::class)
