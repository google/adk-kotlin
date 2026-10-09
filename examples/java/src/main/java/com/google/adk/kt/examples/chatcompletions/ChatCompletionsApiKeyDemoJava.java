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

package com.google.adk.kt.examples.chatcompletions;

import com.google.adk.kt.agents.BaseAgent;
import com.google.adk.kt.models.openai.ChatCompletions;
import com.google.adk.kt.types.HttpOptions;

/**
 * Java port of the Chat Completions API key demo. Calls Gemini through the Gemini API's
 * OpenAI-compatible endpoint with {@code GEMINI_API_KEY}, or another provider when {@code
 * CHAT_COMPLETIONS_BASE_URL}, {@code CHAT_COMPLETIONS_MODEL}, and {@code CHAT_COMPLETIONS_API_KEY}
 * are set.
 */
public final class ChatCompletionsApiKeyDemoJava {

  private static final String GEMINI_BASE_URL =
      "https://generativelanguage.googleapis.com/v1beta/openai";
  private static final String GEMINI_MODEL = "gemini-3.1-flash-lite";

  public static final BaseAgent rootAgent =
      TravelToolsJava.travelAgent("chat_completions_api_key_demo", model());

  private static ChatCompletions model() {
    String baseUrl = System.getenv("CHAT_COMPLETIONS_BASE_URL");
    String model = System.getenv("CHAT_COMPLETIONS_MODEL");
    // The Gemini key goes only to Gemini.
    String apiKey = System.getenv(baseUrl == null ? "GEMINI_API_KEY" : "CHAT_COMPLETIONS_API_KEY");
    return new ChatCompletions(
        model == null ? GEMINI_MODEL : model,
        apiKey,
        HttpOptions.builder().baseUrl(baseUrl == null ? GEMINI_BASE_URL : baseUrl).build());
  }

  private ChatCompletionsApiKeyDemoJava() {}
}
