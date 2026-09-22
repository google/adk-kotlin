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
import com.google.adk.kt.logging.LoggerFactory
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.Model
import com.google.adk.kt.models.StreamingResponseAggregator
import com.google.adk.kt.types.FinishReason
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.reactive.asFlow
import org.springframework.ai.chat.model.ChatModel

/**
 * A [Model] backed by a Spring AI [ChatModel], letting any Spring AI chat provider (OpenAI,
 * Anthropic, Bedrock, Ollama, ...) drive an ADK agent.
 *
 * The adapter wraps the low-level [ChatModel] rather than a `ChatClient`: ADK owns the agent loop,
 * so the model runs a single turn, returns any tool-call requests as ADK function-call parts, and
 * ADK executes the tools. Tools are handed to the model as schema only; Spring AI never executes
 * them (see `SpringAiTools.kt`).
 *
 * @property name The model name, reported to ADK and traces.
 * @param chatModel The Spring AI chat model to wrap. Streaming requires the model to support it; a
 *   model whose provider does not override streaming surfaces Spring AI's own
 *   `UnsupportedOperationException`.
 * @param coroutineContext The coroutine context the blocking Spring AI call runs on; defaults to
 *   [Dispatchers.IO] and is injectable so tests can supply a deterministic dispatcher.
 */
class SpringAiModel
@JvmOverloads
constructor(
  override val name: String,
  private val chatModel: ChatModel,
  private val coroutineContext: CoroutineContext = Dispatchers.IO,
) : Model {

  @OptIn(FrameworkInternalApi::class)
  override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> =
    flow {
        val prompt = request.toSpringAiPrompt(chatModel.options)
        logger.debug { "Spring AI '$name' generateContent (stream=$stream)" }

        if (stream) {
          val aggregator = StreamingResponseAggregator()
          chatModel.stream(prompt).asFlow().collect { chunk ->
            emit(aggregator.processResponse(chunk.toLlmResponse()))
          }
          aggregator.aggregate()?.let { emit(it.ensureFinishReason()) }
        } else {
          emit(chatModel.call(prompt).toLlmResponse().ensureFinishReason())
        }
      }
      // Spring AI's call()/stream() may block on the calling thread; run them off the collector's.
      .flowOn(coroutineContext)

  /**
   * Backfills [FinishReason.STOP] when a completed response carries content but the provider gave
   * no finish reason, so downstream ADK sees a terminal turn. Errors surface as thrown exceptions,
   * not as an empty finish reason, so this never masks one.
   */
  private fun LlmResponse.ensureFinishReason(): LlmResponse =
    if (finishReason == null && content != null) copy(finishReason = FinishReason.STOP) else this

  private companion object {
    val logger = LoggerFactory.getLogger(SpringAiModel::class)
  }
}
