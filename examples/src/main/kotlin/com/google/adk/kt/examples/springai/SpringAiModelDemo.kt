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

package com.google.adk.kt.examples.springai

import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.models.springai.SpringAiModel
import com.google.genai.Client
import org.springframework.ai.google.genai.GoogleGenAiChatModel
import org.springframework.ai.google.genai.GoogleGenAiChatOptions

/**
 * Example agent driven by a Spring AI `ChatModel` through [SpringAiModel].
 *
 * Any Spring AI chat provider works; this wraps the Google GenAI provider using a Gemini API key
 * from the `GOOGLE_API_KEY` environment variable. To use OpenAI, Anthropic, Ollama, or Vertex,
 * build that provider's `ChatModel` instead and pass it to [SpringAiModel].
 */
object SpringAiModelDemo {
  private const val MODEL = "gemini-flash-latest"

  private val chatModel: GoogleGenAiChatModel =
    GoogleGenAiChatModel.builder()
      .genAiClient(Client.builder().apiKey(System.getenv("GOOGLE_API_KEY")).vertexAI(false).build())
      .options(GoogleGenAiChatOptions.builder().model(MODEL).build())
      .build()

  @JvmField
  val rootAgent =
    LlmAgent(
      name = "spring_ai_model_demo",
      model = SpringAiModel(name = MODEL, chatModel = chatModel),
      instruction = Instruction("You are a helpful assistant. Keep answers concise."),
    )
}
