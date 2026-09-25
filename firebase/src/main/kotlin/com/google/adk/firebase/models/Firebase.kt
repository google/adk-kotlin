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

package com.google.adk.firebase.models

import com.google.adk.firebase.utils.Conversions
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.Model
import com.google.adk.kt.models.StreamingResponseAggregator
import com.google.firebase.ai.FirebaseAI
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.GenerateContentResponse
import com.google.firebase.ai.type.PromptBlockedException
import com.google.firebase.ai.type.ResponseStoppedException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach

/** Implementation of the Model interface using Firebase AI. */
class Firebase private constructor(override val name: String, val firebaseAI: FirebaseAI) : Model {

  private val conversions = Conversions()

  companion object {
    /**
     * Creates a new Firebase model.
     *
     * Note the session persistence limitation with thinking models documented on [Firebase].
     */
    fun create(
      /** The name of the underlying model, e.g. "gemini-3.1-flash-lite" */
      name: String,
      /** The FirebaseAI instance */
      firebaseAI: FirebaseAI,
    ) = Firebase(name, firebaseAI)

    // No-op until tracing can avoid recording sensitive request and response data.
    private fun trace(response: GenerateContentResponse) {}

    private fun trace(request: List<Content>) {}

    private fun trace(request: LlmRequest) {}

    private fun trace(response: LlmResponse) {}
  }

  override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> {
    if (stream) {
      return generateContentStreaming(request)
    } else {
      return flow { emit(generateContentNonStreaming(request)) }
    }
  }

  private suspend fun generateContentNonStreaming(request: LlmRequest): LlmResponse {
    trace(request)
    val requestConverter = conversions.forRequest(request)
    val model = buildModel(requestConverter)

    val response =
      model.generateContent(requestConverter.contents().also { trace(it) }).also { trace(it) }
    return conversions.convertResponse(response).also { trace(it) }
  }

  /**
   * Emits every chunk as a partial [LlmResponse], then the aggregated final one.
   *
   * If the model finishes abnormally (a non-`STOP` finish or a prompt block) the Firebase SDK
   * throws mid-stream instead of emitting; [streamToLlmResponses] catches that and still emits a
   * terminal error response, so partials already delivered are not lost.
   */
  private fun generateContentStreaming(request: LlmRequest): Flow<LlmResponse> = flow {
    trace(request)
    val requestConverter = conversions.forRequest(request)
    val model = buildModel(requestConverter)
    val chunks =
      model.generateContentStream(requestConverter.contents().also { trace(it) }).onEach {
        trace(it)
      }
    emitAll(streamToLlmResponses(chunks).onEach { trace(it) })
  }

  private fun buildModel(requestConverter: Conversions.RequestConverter): GenerativeModel =
    requestConverter.convert {
      firebaseAI.generativeModel(
        name,
        generationConfig(),
        safetySettings(),
        tools(),
        toolConfig(),
        systemInstruction(),
        requestOptions(),
      )
    }
}

/**
 * Converts a raw Firebase chunk stream into ADK [LlmResponse]s: each chunk as a partial, then one
 * aggregated terminal response. On an abnormal finish the SDK throws mid-stream; that is recovered
 * into a terminal error response so partials already delivered aren't lost.
 */
@OptIn(FrameworkInternalApi::class)
internal fun streamToLlmResponses(chunks: Flow<GenerateContentResponse>): Flow<LlmResponse> = flow {
  val conversions = Conversions()
  val aggregator = StreamingResponseAggregator()
  try {
    chunks.collect { chunk -> emit(aggregator.processResponse(conversions.convertResponse(chunk))) }
  } catch (e: ResponseStoppedException) {
    // Feed the terminating chunk into the aggregator for its finish reason / error; the returned
    // partial is intentionally discarded so only the aggregated terminal response is emitted.
    val unused = aggregator.processResponse(conversions.convertResponse(e.response))
  } catch (e: PromptBlockedException) {
    val response = e.response
    if (response == null) {
      emit(LlmResponse(errorMessage = e.message ?: "Unknown error."))
      return@flow
    }
    val unused = aggregator.processResponse(conversions.convertResponse(response))
  }
  aggregator.aggregate()?.let { emit(it) }
}
