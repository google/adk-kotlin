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
package com.google.adk.kt.models.mlkit

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.Model
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FinishReason
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.GenerateContentConfig
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GenaiPromptTracingTest {

  private val fakeModel =
    object : Model {
      override val name = "test-model"

      override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> =
        emptyFlow()
    }

  @Test
  fun formatLlmRequest_redactsTextParts() {
    val request =
      LlmRequest(
        model = fakeModel,
        contents = listOf(Content(role = Role.USER, parts = listOf(Part(text = "secret hello")))),
      )

    val trace = GenaiPromptTracing.format(request)

    assertThat(trace).doesNotContain("secret hello")
    assertThat(trace).contains("[REDACTED - length 12 chars]")
  }

  @Test
  fun formatLlmRequest_redactsSystemInstruction() {
    val request =
      LlmRequest(
        model = fakeModel,
        config =
          GenerateContentConfig(
            systemInstruction = Content(parts = listOf(Part(text = "be helpful"))),
            temperature = 0.5f,
          ),
      )

    val trace = GenaiPromptTracing.format(request)

    assertThat(trace).doesNotContain("be helpful")
    assertThat(trace).contains("[REDACTED - length 10 chars]")
    // Non-sensitive config metadata is preserved.
    assertThat(trace).contains("temperature=0.5")
  }

  @Test
  fun formatLlmRequest_redactsFunctionCallArgValuesButKeepsKeys() {
    val request =
      LlmRequest(
        model = fakeModel,
        contents =
          listOf(
            Content(
              role = Role.USER,
              parts =
                listOf(
                  Part(
                    functionCall =
                      FunctionCall(
                        name = "get_weather",
                        args = mapOf("location" to "Zurich", "days" to 3),
                      )
                  )
                ),
            )
          ),
      )

    val trace = GenaiPromptTracing.format(request)

    assertThat(trace).doesNotContain("Zurich")
    // Function name and argument keys are kept; only the values are redacted.
    assertThat(trace).contains("get_weather")
    assertThat(trace).contains("location=[REDACTED - length 6 chars]")
    assertThat(trace).contains("days=[REDACTED - length 1 chars]")
  }

  @Test
  fun formatLlmRequest_redactsFunctionResponseValuesButKeepsKeys() {
    val request =
      LlmRequest(
        model = fakeModel,
        contents =
          listOf(
            Content(
              role = Role.USER,
              parts =
                listOf(
                  Part(
                    functionResponse =
                      FunctionResponse(
                        name = "get_weather",
                        response = mapOf("temperature" to "20C"),
                      )
                  )
                ),
            )
          ),
      )

    val trace = GenaiPromptTracing.format(request)

    assertThat(trace).doesNotContain("20C")
    assertThat(trace).contains("temperature=[REDACTED - length 3 chars]")
  }

  @Test
  fun formatLlmRequest_keepsModelName() {
    val request = LlmRequest(model = fakeModel)

    val trace = GenaiPromptTracing.format(request)

    assertThat(trace).contains("model=test-model")
  }

  @Test
  fun formatLlmResponse_redactsContentText() {
    val response =
      LlmResponse(
        content = Content(role = Role.MODEL, parts = listOf(Part(text = "response text"))),
        finishReason = FinishReason.STOP,
      )

    val trace = GenaiPromptTracing.format(response)

    assertThat(trace).doesNotContain("response text")
    assertThat(trace).contains("[REDACTED - length 13 chars]")
    // Non-sensitive metadata is preserved.
    assertThat(trace).contains("finishReason=STOP")
  }
}
