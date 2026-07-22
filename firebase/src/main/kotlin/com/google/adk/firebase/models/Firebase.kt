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
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.Model
import com.google.firebase.ai.FirebaseAI
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.GenerateContentResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

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

    // TODO: b/514250362 - tracing requests and responses while making sure no sensitive information
    // is leaked
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
    val model = requestConverter.convert {
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

    val response =
      model.generateContent(requestConverter.contents().also { trace(it) }).also { trace(it) }
    return conversions.convertResponse(response).also { trace(it) }
  }

  private fun generateContentStreaming(request: LlmRequest): Flow<LlmResponse> = flow {
    throw UnsupportedOperationException("Streaming is not supported yet.")
  }
}
