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

package com.google.adk.kt.models

import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FileData
import com.google.adk.kt.types.GenerateContentConfig
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.fromGenaiSdk
import com.google.common.truth.Truth.assertThat
import com.google.genai.kotlin.types.Candidate as GenAiCandidate
import com.google.genai.kotlin.types.Content as GenAiContent
import com.google.genai.kotlin.types.FinishReason as GenAiFinishReason
import com.google.genai.kotlin.types.GenerateContentResponse as GenAiGenerateContentResponse
import com.google.genai.kotlin.types.Part as GenAiPart
import kotlin.test.Test

class GeminiTest {

  @Test
  fun ensureModelResponse_lastRoleNotUser_addsContinueOutputMessage() {
    val reqContents = listOf(userMessage("Prompt"), modelMessage("Response"))

    val ensured = reqContents.ensureModelResponse()
    assertThat(ensured).hasSize(3)
    assertThat(ensured.last().role).isEqualTo("user")
    assertThat(ensured.last().parts.first().text)
      .isEqualTo(
        "Continue processing previous requests as instructed. Exit or provide a summary if no more outputs are needed."
      )
  }

  @Test
  fun ensureModelResponse_contentsEmpty_addsContinueOutputMessage() {
    val reqContents = emptyList<Content>()

    val ensured = reqContents.ensureModelResponse()
    assertThat(ensured).hasSize(1)
    assertThat(ensured.last().role).isEqualTo("user")
    assertThat(ensured.last().parts.first().text)
      .isEqualTo("Handle the requests as specified in the System Instruction.")
  }

  @Test
  fun ensureModelResponse_lastRoleUser_doesNothing() {
    val reqContents = listOf(userMessage("Prompt"))

    val ensured = reqContents.ensureModelResponse()
    assertThat(ensured).hasSize(1)
    assertThat(ensured).isEqualTo(reqContents)
  }

  @Test
  fun sanitizeRequestForGeminiApi_withDisplayName_removesDisplayName() {
    val reqContents =
      mutableListOf(
        userMessage(
          Part(
            inlineData =
              Blob(mimeType = "image/png", data = "123".toByteArray(), displayName = "my-image.png")
          ),
          Part(
            fileData =
              FileData(
                mimeType = "application/pdf",
                fileUri = "gs://bucket/file",
                displayName = "document.pdf",
              )
          ),
        )
      )

    // Using KMP Gen AI config
    val request = LlmRequest(model = null, contents = reqContents, config = GenerateContentConfig())
    val sanitized = request.sanitizeForGeminiApi()

    val inlineData = sanitized.contents.first().parts.get(0).inlineData
    val fileData = sanitized.contents.first().parts.get(1).fileData

    assertThat(inlineData?.displayName).isNull()
    assertThat(inlineData?.mimeType).isEqualTo("image/png")
    assertThat(inlineData?.data).isEqualTo("123".toByteArray())

    assertThat(fileData?.displayName).isNull()
    assertThat(fileData?.mimeType).isEqualTo("application/pdf")
    assertThat(fileData?.fileUri).isEqualTo("gs://bucket/file")
  }

  @Test
  fun sanitizeRequestForGeminiApi_withoutData_handlesSuccessfully() {
    val reqContents = mutableListOf(userMessage("Just text"))

    val request = LlmRequest(model = null, contents = reqContents, config = GenerateContentConfig())
    val sanitized = request.sanitizeForGeminiApi()

    assertThat(sanitized.contents.first().parts.get(0).text).isEqualTo("Just text")
  }

  @Test
  fun sanitizeForGeminiApi_emptyContents_clearsLabels() {
    val config = GenerateContentConfig(labels = mapOf("test" to "label"))
    val request = LlmRequest(model = null, contents = emptyList(), config = config)

    val sanitized = request.sanitizeForGeminiApi()

    assertThat(sanitized.config.labels).isNull()
    assertThat(sanitized.contents).isEmpty()
  }

  @Test
  fun prepareGenerateContentRequest_default_appliesLogicCorrectly() {
    val reqContents =
      mutableListOf(
        modelMessage(
          Part(text = "Thought", thought = true),
          Part(
            inlineData = Blob(mimeType = "img", data = "1".toByteArray(), displayName = "name"),
            thought = false,
          ),
        )
      )
    val request = LlmRequest(model = null, contents = reqContents, config = GenerateContentConfig())

    val prepared = request.prepareGenerateContentRequest(sanitize = true)

    // Should:
    // 1. Sanitize the string display name
    // 2. Add ensureModelResponse append string at the end as "user"

    assertThat(prepared.contents).hasSize(2)
    assertThat(prepared.contents[0].role).isEqualTo("model")
    assertThat(prepared.contents[0].parts).hasSize(2)
    val part0 = prepared.contents[0].parts[0]
    assertThat(part0.thought).isTrue() // Explicit check
    assertThat(prepared.contents[0].parts[1].inlineData?.displayName).isNull()
    assertThat(prepared.contents[1].role).isEqualTo("user")
    assertThat(prepared.contents[1].parts[0].text)
      .isEqualTo(
        "Continue processing previous requests as instructed. Exit or provide a summary if no more outputs are needed."
      )
  }

  @Test
  fun from_finishReasonStop_nullsOutErrorMessage() {
    val genAiResponse =
      GenAiGenerateContentResponse(
        candidates =
          listOf(
            GenAiCandidate(
              content = GenAiContent(role = "model", parts = listOf(GenAiPart(text = "hello"))),
              finishReason = GenAiFinishReason.STOP,
              finishMessage = "Should be ignored",
            )
          )
      )

    val llmResponse = LlmResponse.from(genAiResponse.fromGenaiSdk())
    assertThat(llmResponse.finishReason?.name).isEqualTo("STOP")
    assertThat(llmResponse.errorMessage).isNull()
  }

  @Test
  fun from_finishReasonNotStop_keepsErrorMessage() {
    val genAiResponse =
      GenAiGenerateContentResponse(
        candidates =
          listOf(
            GenAiCandidate(finishReason = GenAiFinishReason.MAX_TOKENS, finishMessage = "Too long")
          )
      )

    val llmResponse = LlmResponse.from(genAiResponse.fromGenaiSdk())
    assertThat(llmResponse.finishReason?.name).isEqualTo("MAX_TOKENS")
    assertThat(llmResponse.errorMessage).isEqualTo("Too long")
  }
}
