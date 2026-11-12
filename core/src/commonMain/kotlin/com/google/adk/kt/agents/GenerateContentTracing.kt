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

package com.google.adk.kt.agents

import com.google.adk.kt.models.Gemini
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.Model
import com.google.adk.kt.models.shortName
import com.google.adk.kt.telemetry.Span
import com.google.adk.kt.telemetry.Telemetry
import com.google.adk.kt.telemetry.TelemetryAttributes
import com.google.adk.kt.telemetry.isFailure
import com.google.adk.kt.telemetry.recordFailure
import com.google.adk.kt.types.FinishReason
import com.google.adk.kt.types.UsageMetadata
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach

/**
 * Runs this stream of [model]'s responses in a `generate_content <model>` span under [callLlmSpan],
 * as Python ADK does for telemetry schema v1. The span ends at the first complete response that
 * reports a finish reason, before the caller handles it, so a tool the model asked for does not run
 * inside it. The span also records a failure of the stream, even one an onModelError callback
 * recovers from.
 */
internal fun Flow<LlmResponse>.traceGenerateContent(
  callLlmSpan: Span,
  model: Model,
  request: LlmRequest,
  agentName: String,
  context: InvocationContext,
  eventId: String,
): Flow<LlmResponse> = flow {
  val tracer = Telemetry.tracer
  val builder =
    tracer
      .spanBuilder("${TelemetryAttributes.OPERATION_GENERATE_CONTENT} ${model.name}".trim())
      .setParent(tracer.contextWithSpan(callLlmSpan))
  builder[TelemetryAttributes.GEN_AI_SYSTEM] = genAiSystemName(model)
  builder[TelemetryAttributes.GEN_AI_OPERATION_NAME] =
    TelemetryAttributes.OPERATION_GENERATE_CONTENT
  builder[TelemetryAttributes.GEN_AI_REQUEST_MODEL] = model.name
  request.config.topP?.let { builder[TelemetryAttributes.GEN_AI_REQUEST_TOP_P] = it.toDouble() }
  request.config.maxOutputTokens?.let {
    builder[TelemetryAttributes.GEN_AI_REQUEST_MAX_TOKENS] = it.toLong()
  }
  builder[TelemetryAttributes.GEN_AI_AGENT_NAME] = agentName
  context.session.key.id?.let { builder[TelemetryAttributes.GEN_AI_CONVERSATION_ID] = it }
  builder[TelemetryAttributes.GCP_VERTEX_AGENT_EVENT_ID] = eventId
  builder[TelemetryAttributes.GCP_VERTEX_AGENT_INVOCATION_ID] = context.invocationId
  val span = builder.startSpan()
  var ended = false
  fun end() {
    if (!ended) {
      ended = true
      span.end()
    }
  }
  try {
    emitAll(
      this@traceGenerateContent.flowOn(tracer.contextWithSpan(span).asContextElement())
        // catch sees only the model's own failures, not those of the caller handling a response.
        .catch { e ->
          if (!ended && isFailure(e)) {
            span.recordFailure(e)
          }
          throw e
        }
        .onEach { response ->
          // A streamed chunk is not recorded: the complete response that follows it is.
          if (ended || response.partial) return@onEach
          val finishReason =
            response.finishReason?.takeIf { it != FinishReason.FINISH_REASON_UNSPECIFIED }
          response.usageMetadata?.let { span.recordTokenUsage(it) }
          if (finishReason != null) {
            span[TelemetryAttributes.GEN_AI_RESPONSE_FINISH_REASONS] =
              listOf(finishReason.name.lowercase())
            end()
          }
        }
    )
  } finally {
    end()
  }
}

/**
 * Records OTel token-usage attributes from [usage], as Python's `TokenUsage.to_attributes` does.
 *
 * Per the OTel GenAI semantic conventions, `input_tokens` sums prompt and tool-use tokens, and
 * `output_tokens` sums candidate and reasoning ("thoughts") tokens. Cache-read and reasoning counts
 * are recorded separately when present.
 */
internal fun Span.recordTokenUsage(usage: UsageMetadata) {
  aggregateTokens(usage.promptTokenCount, usage.toolUsePromptTokenCount)?.let {
    this[TelemetryAttributes.GEN_AI_USAGE_INPUT_TOKENS] = it.toLong()
  }
  aggregateTokens(usage.candidatesTokenCount, usage.thoughtsTokenCount)?.let {
    this[TelemetryAttributes.GEN_AI_USAGE_OUTPUT_TOKENS] = it.toLong()
  }
  usage.cachedContentTokenCount?.let {
    this[TelemetryAttributes.GEN_AI_USAGE_CACHE_READ_INPUT_TOKENS] = it.toLong()
  }
  usage.thoughtsTokenCount?.let {
    this[TelemetryAttributes.GEN_AI_USAGE_REASONING_OUTPUT_TOKENS] = it.toLong()
  }
}

/**
 * Sums two optional token counts, returning null only when both are absent, as Python's
 * `TokenUsage` treats a missing pair as no value rather than 0.
 */
private fun aggregateTokens(first: Int?, second: Int?): Int? =
  if (first == null && second == null) null else (first ?: 0) + (second ?: 0)

/**
 * Returns the `gen_ai.system` value for [model], after Python ADK's `_resolve_gen_ai_system_name`:
 * a [Gemini] reports its client's backend, any other Gemini id reports `gemini`, a Claude model
 * reports `anthropic`, a `<provider>/<model>` id reports its provider, and anything else reports
 * `gemini`. Python instead reads the backend from the environment (`vertex_ai` in enterprise mode)
 * for every Gemini id and for that last case.
 */
internal fun genAiSystemName(model: Model): String {
  val name = model.shortName
  val provider = name.substringBefore('/', missingDelimiterValue = "").lowercase()
  return when {
    model is Gemini ->
      if (model.client.enterprise) TelemetryAttributes.SYSTEM_VERTEX_AI
      else TelemetryAttributes.SYSTEM_GEMINI
    // Like Python's `extract_model_name`, reads `openrouter/google/gemini-2.5-pro` as Gemini.
    name.substringAfterLast('/').startsWith("gemini-") -> TelemetryAttributes.SYSTEM_GEMINI
    CLAUDE_MODEL.containsMatchIn(name) -> TelemetryAttributes.SYSTEM_ANTHROPIC
    // A resource path names where the model lives, not who serves it.
    provider.isNotEmpty() && provider !in RESOURCE_COLLECTION_SEGMENTS -> provider
    else -> TelemetryAttributes.SYSTEM_GEMINI
  }
}

private val CLAUDE_MODEL = Regex("^claude[-.]", RegexOption.IGNORE_CASE)

private val RESOURCE_COLLECTION_SEGMENTS =
  setOf("endpoints", "locations", "models", "projects", "publishers", "tunedmodels")
