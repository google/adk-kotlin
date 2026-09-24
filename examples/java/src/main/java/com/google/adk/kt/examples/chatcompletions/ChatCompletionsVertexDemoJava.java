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
import com.google.adk.kt.models.chatcompletions.ChatCompletions;
import com.google.adk.kt.types.HttpOptions;
import com.google.auth.oauth2.GoogleCredentials;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Java port of the Chat Completions on Vertex AI demo. Calls Gemini through Vertex AI's
 * OpenAI-compatible endpoint in the project named by {@code GOOGLE_CLOUD_PROJECT}, with a fresh
 * Application Default Credentials token before each request.
 */
public final class ChatCompletionsVertexDemoJava {

  private static final String MODEL = "google/gemini-3.1-flash-lite";
  private static final String PROJECT = System.getenv("GOOGLE_CLOUD_PROJECT");

  private static final AtomicReference<GoogleCredentials> credentials = new AtomicReference<>();

  public static final BaseAgent rootAgent =
      TravelToolsJava.travelAgent(
          "chat_completions_vertex_demo",
          new ChatCompletions(
              MODEL,
              HttpOptions.builder()
                  .baseUrl(
                      "https://aiplatform.googleapis.com/v1/projects/"
                          + PROJECT
                          + "/locations/global/endpoints/openapi")
                  .build(),
              ChatCompletionsVertexDemoJava::accessToken));

  private static String accessToken() {
    // Checked on use so the agent loads without it.
    if (PROJECT == null) {
      throw new IllegalStateException("Set GOOGLE_CLOUD_PROJECT.");
    }
    try {
      GoogleCredentials adc = credentials.get();
      if (adc == null) {
        GoogleCredentials loaded =
            GoogleCredentials.getApplicationDefault()
                .createScoped("https://www.googleapis.com/auth/cloud-platform");
        // If first calls race, the first to store its credentials wins.
        adc = credentials.compareAndSet(null, loaded) ? loaded : credentials.get();
      }
      adc.refreshIfExpired();
      return adc.getAccessToken().getTokenValue();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private ChatCompletionsVertexDemoJava() {}
}
