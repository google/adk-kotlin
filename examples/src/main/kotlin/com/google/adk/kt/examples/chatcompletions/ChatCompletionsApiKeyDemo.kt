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

package com.google.adk.kt.examples.chatcompletions

import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.models.chatcompletions.ChatCompletions
import com.google.adk.kt.types.HttpOptions

/**
 * Example travel agent on Gemini through the Gemini API's OpenAI-compatible endpoint, authenticated
 * with an API key from `GEMINI_API_KEY`.
 *
 * Any other Chat Completions provider, such as OpenAI or a local server, works the same way: set
 * `CHAT_COMPLETIONS_BASE_URL`, `CHAT_COMPLETIONS_MODEL`, and `CHAT_COMPLETIONS_API_KEY`.
 */
object ChatCompletionsApiKeyDemo {
  private const val GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/openai"
  private const val GEMINI_MODEL = "gemini-3.1-flash-lite"

  private val baseUrl: String? = System.getenv("CHAT_COMPLETIONS_BASE_URL")

  @JvmField
  val rootAgent: LlmAgent =
    travelAgent(
      name = "chat_completions_api_key_demo",
      model =
        ChatCompletions(
          name = System.getenv("CHAT_COMPLETIONS_MODEL") ?: GEMINI_MODEL,
          // The Gemini key goes only to Gemini.
          apiKey =
            System.getenv(if (baseUrl == null) "GEMINI_API_KEY" else "CHAT_COMPLETIONS_API_KEY"),
          httpOptions = HttpOptions(baseUrl = baseUrl ?: GEMINI_BASE_URL),
        ),
    )
}
