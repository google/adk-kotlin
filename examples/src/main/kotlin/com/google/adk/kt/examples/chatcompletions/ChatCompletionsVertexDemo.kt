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
import com.google.adk.kt.models.openai.ChatCompletions
import com.google.adk.kt.types.HttpOptions
import com.google.auth.oauth2.GoogleCredentials

/**
 * Example travel agent on Gemini through Vertex AI's OpenAI-compatible endpoint, authenticated with
 * Application Default Credentials.
 *
 * The project comes from `GOOGLE_CLOUD_PROJECT`. Access tokens expire, so the model asks for a
 * fresh one before each request.
 */
object ChatCompletionsVertexDemo {
  private const val MODEL = "google/gemini-3.1-flash-lite"

  private val project: String? = System.getenv("GOOGLE_CLOUD_PROJECT")

  private val credentials by lazy {
    GoogleCredentials.getApplicationDefault()
      .createScoped("https://www.googleapis.com/auth/cloud-platform")
  }

  @JvmField
  val rootAgent: LlmAgent =
    travelAgent(
      name = "chat_completions_vertex_demo",
      model =
        ChatCompletions(
          name = MODEL,
          httpOptions =
            HttpOptions(
              baseUrl =
                "https://aiplatform.googleapis.com/v1/projects/$project/locations/global/" +
                  "endpoints/openapi"
            ),
        ) {
          // Checked on use so the agent loads without it.
          checkNotNull(project) { "Set GOOGLE_CLOUD_PROJECT." }
          credentials.refreshIfExpired()
          credentials.accessToken.tokenValue
        },
    )
}
