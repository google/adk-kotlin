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

package com.google.adk.kt.examples.springai;

import com.google.adk.kt.agents.BaseAgent;
import com.google.adk.kt.agents.LlmAgent;
import com.google.adk.kt.models.springai.SpringAiModel;
import com.google.genai.Client;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;

/**
 * Java port of the Spring AI model demo. Wraps a Spring AI {@code ChatModel} with {@link
 * SpringAiModel}; swap the {@code ChatModel} to use any other Spring AI provider.
 */
public final class SpringAiModelDemoJava {

  private static final String MODEL = "gemini-flash-latest";

  private static final GoogleGenAiChatModel CHAT_MODEL =
      GoogleGenAiChatModel.builder()
          .genAiClient(
              Client.builder().apiKey(System.getenv("GOOGLE_API_KEY")).vertexAI(false).build())
          .options(GoogleGenAiChatOptions.builder().model(MODEL).build())
          .build();

  public static final BaseAgent rootAgent =
      LlmAgent.builder()
          .name("spring_ai_model_demo")
          .model(new SpringAiModel(MODEL, CHAT_MODEL))
          .instruction("You are a helpful assistant. Keep answers concise.")
          .build();

  private SpringAiModelDemoJava() {}
}
