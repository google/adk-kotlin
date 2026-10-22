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
 * Any Spring AI chat provider works; this wraps the Google GenAI provider on Vertex AI, using
 * Application Default Credentials and the project named by `GOOGLE_CLOUD_PROJECT`. To use OpenAI,
 * Anthropic, or Ollama, build that provider's `ChatModel` instead and pass it to [SpringAiModel].
 */
object SpringAiModelDemo {
  private const val MODEL = "gemini-3.8-flash"

  private val chatModel: GoogleGenAiChatModel =
    GoogleGenAiChatModel.builder()
      .genAiClient(Client.builder().vertexAI(true).location("global").build())
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
