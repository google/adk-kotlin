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

package com.google.adk.kt.models.mlkit

import com.google.adk.kt.logging.LoggerFactory
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.Model
import com.google.adk.kt.models.mlkit.GenaiPromptTracing.format
import com.google.adk.kt.utils.mlkit.GenaiPromptConversions.toGenerateContentRequest
import com.google.adk.kt.utils.mlkit.GenaiPromptConversions.toLlmResponse
import com.google.adk.kt.utils.mlkit.GenerateContentResponseAggregator
import com.google.adk.kt.utils.mlkit.toAggregatedResponse
import com.google.mlkit.genai.prompt.GenerativeModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * A [Model] implementation that uses the ML Kit GenAI API to generate content.
 *
 * The underlying ML Kit GenAI `GenerateContentRequest` is a single-prompt API, so this
 * implementation currently has the following limitations:
 * - Multi-turn conversations are not truly supported. The whole [LlmRequest.contents] history is
 *   flattened into a single prompt: all text parts (across every turn) are concatenated with "\n\n"
 *   and the role of each turn is discarded, so the model cannot distinguish user turns from model
 *   turns.
 * - Only text and a single image are sent. Text parts are concatenated; if multiple images are
 *   present, only the first is used and the rest are ignored.
 * - Tool use is not supported. Only text parts are read; `functionCall` and `functionResponse`
 *   parts are dropped.
 * - Only the first response candidate is used.
 *
 * @param generativeModel The [GenerativeModel] to use for generation.
 * @param name The name of the model.
 */
class GenaiPrompt
private constructor(val generativeModel: GenerativeModel, override val name: String) : Model {

  companion object {
    val logger = LoggerFactory.getLogger(GenaiPrompt::class)

    /**
     * Creates a [GenaiPrompt] instance with the given [generativeModel] and [name].
     *
     * @param generativeModel The [GenerativeModel] to use for generation.
     * @param name The name of the model.
     */
    fun create(generativeModel: GenerativeModel, name: String = "GenaiPrompt") =
      GenaiPrompt(generativeModel, name)
  }

  private suspend fun generateContentNonStreaming(request: LlmRequest): LlmResponse {
    return generativeModel
      .generateContent(request.toGenerateContentRequest().also { logger.trace { format(it) } })
      .also { logger.trace { format(it) } }
      .toLlmResponse()
      .also { logger.trace { format(it) } }
  }

  private fun generateContentStreaming(request: LlmRequest): Flow<LlmResponse> = flow {
    val responseAggregator = GenerateContentResponseAggregator()
    generativeModel
      .generateContentStream(
        request.toGenerateContentRequest().also { logger.trace { format(it) } }
      )
      .collect {
        responseAggregator.processResponse(
          it.toAggregatedResponse().also { aggregatedResponse ->
            logger.trace { "partial response: ${format(aggregatedResponse)}" }
          }
        )
        emit(
          it
            .also { response -> logger.trace { "partial response: ${format(response)}" } }
            .toLlmResponse()
            .copy(partial = true)
            .also { response -> logger.trace { "partial response: ${format(response)}" } }
        )
      }

    emit(
      responseAggregator
        .aggregate()
        .also { response -> logger.trace { "final response: ${format(response)}" } }
        .toLlmResponse()
        .copy(partial = false)
        .also { response -> logger.trace { "final response: ${format(response)}" } }
    )
  }

  override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> {
    logger.trace { "request: ${format(request)}, stream: $stream" }
    return if (stream) {
      generateContentStreaming(request)
    } else {
      flow { emit(generateContentNonStreaming(request)) }
    }
  }
}
