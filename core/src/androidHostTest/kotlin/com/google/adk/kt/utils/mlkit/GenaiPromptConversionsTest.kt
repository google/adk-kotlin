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
package com.google.adk.kt.utils.mlkit

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FileData
import com.google.adk.kt.types.FinishReason
import com.google.adk.kt.types.GenerateContentConfig
import com.google.adk.kt.types.Part
import com.google.adk.kt.utils.mlkit.GenaiPromptConversions.toGenerateContentRequest
import com.google.adk.kt.utils.mlkit.GenaiPromptConversions.toLlmResponse
import com.google.common.truth.Truth.assertThat
import com.google.mlkit.common.sdkinternal.MlKitContext
import com.google.mlkit.genai.prompt.Candidate.FinishReason as MlKitFinishReason
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GenaiPromptConversionsTest {
  private lateinit var imageUri: Uri

  private val imageBytes: ByteArray = run {
    val testBitmap = Bitmap.createBitmap(intArrayOf(1, 2, 3, 4), 1, 1, Bitmap.Config.ARGB_8888)
    val bytes = ByteArrayOutputStream()
    testBitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)
    bytes.toByteArray()
  }

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    MlKitContext.initializeIfNeeded(context)
    val file = File(context.cacheDir, "test-image.png")
    context.assets.open("test-image.png").use { input ->
      file.outputStream().use { output -> input.copyTo(output) }
    }
    imageUri = file.toUri()
  }

  @Test
  fun toGenerateContentRequest_textOnly_success() {
    val request =
      LlmRequest(
        contents =
          listOf(
            Content(
              role = "user",
              parts = listOf(Part(text = "Hello World"), Part(text = "Another text")),
            )
          )
      )

    val generateContentRequest = request.toGenerateContentRequest()
    assertThat(generateContentRequest.text.textString).isEqualTo("Hello World\n\nAnother text")
    assertThat(generateContentRequest.image).isNull()
    assertThat(generateContentRequest.promptPrefix).isNull()
  }

  @Ignore("throws java.lang.VerifyError")
  fun toGenerateContentRequest_textAndImage_success() {
    val request =
      LlmRequest(
        contents =
          listOf(
            Content(
              role = "user",
              parts =
                listOf(
                  Part(text = "Hello World"),
                  Part(text = "Another text"),
                  Part(inlineData = Blob(mimeType = "image/png", data = imageBytes)),
                ),
            )
          )
      )
    val generateContentRequest = request.toGenerateContentRequest()
    assertThat(generateContentRequest.text.textString).isEqualTo("Hello World\n\nAnother text")
    assertThat(generateContentRequest.image?.bitmap).isNotNull()
    assertThat(generateContentRequest.promptPrefix).isNull()
  }

  @Ignore("throws java.lang.VerifyError")
  fun toGenerateContentRequest_textAndFileUriImage_success() {
    val request =
      LlmRequest(
        contents =
          listOf(
            Content(
              role = "user",
              parts =
                listOf(
                  Part(text = "Hello World"),
                  Part(text = "Another text"),
                  Part(fileData = FileData(mimeType = "image/png", fileUri = imageUri.toString())),
                ),
            )
          )
      )
    val generateContentRequest = request.toGenerateContentRequest()
    assertThat(generateContentRequest.text.textString).isEqualTo("Hello World\n\nAnother text")
    assertThat(generateContentRequest.image?.bitmap).isNotNull()
    assertThat(generateContentRequest.promptPrefix).isNull()
  }

  @Ignore("throws java.lang.VerifyError")
  fun toGenerateContentRequest_imageOnly_success() {
    val request =
      LlmRequest(
        contents =
          listOf(
            Content(
              role = "user",
              parts = listOf(Part(inlineData = Blob(mimeType = "image/png", data = imageBytes))),
            )
          )
      )
    val generateContentRequest = request.toGenerateContentRequest()
    assertThat(generateContentRequest.text.textString).isEmpty()
    assertThat(generateContentRequest.image?.bitmap).isNotNull()
    assertThat(generateContentRequest.promptPrefix).isNull()
  }

  @Ignore("throws java.lang.VerifyError")
  fun toGenerateContentRequest_fileUriImageOnly_success() {
    val request =
      LlmRequest(
        contents =
          listOf(
            Content(
              role = "user",
              parts =
                listOf(
                  Part(fileData = FileData(mimeType = "image/png", fileUri = imageUri.toString()))
                ),
            )
          )
      )
    val generateContentRequest = request.toGenerateContentRequest()
    assertThat(generateContentRequest.text.textString).isEmpty()
    assertThat(generateContentRequest.image?.bitmap).isNotNull()
    assertThat(generateContentRequest.promptPrefix).isNull()
  }

  @Ignore("throws java.lang.VerifyError")
  fun toGenerateContentRequest_multipleImages_success() {
    val request =
      LlmRequest(
        contents =
          listOf(
            Content(
              role = "user",
              parts =
                listOf(
                  Part(inlineData = Blob(mimeType = "image/png", data = imageBytes)),
                  Part(fileData = FileData(mimeType = "image/png", fileUri = imageUri.toString())),
                ),
            )
          )
      )
    val generateContentRequest = request.toGenerateContentRequest()
    assertThat(generateContentRequest.image?.bitmap).isNotNull()
    // Only the first image is used. It has a size of 1x1.
    assertThat(generateContentRequest.image?.width).isEqualTo(1)
    assertThat(generateContentRequest.image?.height).isEqualTo(1)
  }

  @Test
  fun toGenerateContentRequest_textAndSystemInstruction_successWithPromptPrefix() {
    val request =
      LlmRequest(
        contents = listOf(Content(role = "user", parts = listOf(Part(text = "Hello World")))),
        config =
          GenerateContentConfig(
            systemInstruction =
              Content(
                parts =
                  listOf(
                    Part(text = "Test prompt prefix"),
                    Part(text = "Another system instruction"),
                  )
              )
          ),
      )
    val generateContentRequest = request.toGenerateContentRequest()
    assertThat(generateContentRequest.text.textString).isEqualTo("Hello World")
    assertThat(generateContentRequest.image).isNull()
    assertThat(generateContentRequest.promptPrefix?.textString)
      .isEqualTo("Test prompt prefix\n\nAnother system instruction")
  }

  @Ignore("throws java.lang.VerifyError")
  fun toGenerateContentRequest_textAndSystemInstructionAndImage_successNoPromptPrefix() {
    val request =
      LlmRequest(
        contents =
          listOf(
            Content(
              role = "user",
              parts =
                listOf(
                  Part(text = "Hello World"),
                  Part(text = "Another text"),
                  Part(inlineData = Blob(mimeType = "image/png", data = imageBytes)),
                ),
            )
          ),
        config =
          GenerateContentConfig(
            systemInstruction =
              Content(
                parts =
                  listOf(
                    Part(text = "Test prompt prefix"),
                    Part(text = "Another system instruction"),
                  )
              )
          ),
      )
    val generateContentRequest = request.toGenerateContentRequest()
    assertThat(generateContentRequest.text.textString)
      .isEqualTo("Test prompt prefix\n\nAnother system instruction\n\nHello World\n\nAnother text")
    assertThat(generateContentRequest.image?.bitmap).isNotNull()
    assertThat(generateContentRequest.promptPrefix).isNull()
  }

  @Test
  fun toGenerateContentRequest_configValues_success() {
    val request =
      LlmRequest(
        contents = listOf(Content(role = "user", parts = listOf(Part(text = "Hello World")))),
        config =
          GenerateContentConfig(
            temperature = 0.5f,
            topK = 40,
            candidateCount = 1,
            maxOutputTokens = 100,
          ),
      )

    val generateContentRequest = request.toGenerateContentRequest()

    assertThat(generateContentRequest.temperature).isEqualTo(0.5f)
    assertThat(generateContentRequest.topK).isEqualTo(40)
    assertThat(generateContentRequest.candidateCount).isEqualTo(1)
    assertThat(generateContentRequest.maxOutputTokens).isEqualTo(100)
  }

  @Ignore("throws java.lang.VerifyError")
  fun toGenerateContentRequest_nonImageMimeType_isIgnored() {
    val request =
      LlmRequest(
        contents =
          listOf(
            Content(
              role = "user",
              parts =
                listOf(
                  Part(text = "Hello World"),
                  Part(
                    inlineData =
                      Blob(mimeType = "example/data", data = "example data".toByteArray())
                  ),
                  Part(
                    fileData = FileData(mimeType = "example/data", fileUri = "file://example/data")
                  ),
                  Part(inlineData = Blob(mimeType = "image/png", data = imageBytes)),
                ),
            )
          )
      )
    val generateContentRequest = request.toGenerateContentRequest()
    assertThat(generateContentRequest.text.textString).isEqualTo("Hello World")
    assertThat(generateContentRequest.image?.bitmap).isNotNull()
    assertThat(generateContentRequest.promptPrefix).isNull()
  }

  @Test
  fun toLlmResponse_candidateWithStopReason_success() {
    val response =
      AggregatedResponse(
        candidates =
          listOf(AggregatedCandidate(text = "Hello World", finishReason = MlKitFinishReason.STOP))
      )
    val llmResponse = response.toLlmResponse()
    assertThat(llmResponse.content?.parts?.firstOrNull()?.text).isEqualTo("Hello World")
    assertThat(llmResponse.finishReason).isEqualTo(FinishReason.STOP)
    assertThat(llmResponse.errorMessage).isNull()
  }

  @Test
  fun toLlmResponse_candidateWithNonStopReason_hasErrorMessage() {
    val response =
      AggregatedResponse(
        candidates =
          listOf(
            AggregatedCandidate(text = "Hello World", finishReason = MlKitFinishReason.MAX_TOKENS)
          )
      )
    val llmResponse = response.toLlmResponse()
    assertThat(llmResponse.content?.parts?.firstOrNull()?.text).isEqualTo("Hello World")
    assertThat(llmResponse.finishReason).isEqualTo(FinishReason.MAX_TOKENS)
    assertThat(llmResponse.errorMessage).contains("MAX_TOKENS")
  }

  @Test
  fun toLlmResponse_noFinishReason_success() {
    val response =
      AggregatedResponse(
        candidates = listOf(AggregatedCandidate(text = "Hello World", finishReason = null))
      )

    val llmResponse = response.toLlmResponse()
    assertThat(llmResponse.content?.parts?.firstOrNull()?.text).isEqualTo("Hello World")
    assertThat(llmResponse.finishReason).isNull()
    assertThat(llmResponse.errorMessage).isNull()
  }

  @Test
  fun toLlmResponse_otherFinishReason_hasErrorMessage() {
    val response =
      AggregatedResponse(
        candidates =
          listOf(AggregatedCandidate(text = "Hello World", finishReason = MlKitFinishReason.OTHER))
      )
    val llmResponse = response.toLlmResponse()
    assertThat(llmResponse.content?.parts?.firstOrNull()?.text).isEqualTo("Hello World")
    assertThat(llmResponse.finishReason).isEqualTo(FinishReason.OTHER)
    assertThat(llmResponse.errorMessage).contains("OTHER")
  }

  @Test
  fun toLlmResponse_roleIsModel() {
    val response =
      AggregatedResponse(
        candidates = listOf(AggregatedCandidate(text = "Hello World", finishReason = null))
      )
    val llmResponse = response.toLlmResponse()
    assertThat(llmResponse.content?.role).isEqualTo("model")
  }

  @Test
  fun toLlmResponse_noCandidates_hasErrorMessage() {
    val response = AggregatedResponse(candidates = emptyList())
    val llmResponse = response.toLlmResponse()
    assertThat(llmResponse.content).isNull()
    assertThat(llmResponse.finishReason).isNull()
    assertThat(llmResponse.errorMessage).isEqualTo("No candidates returned.")
  }
}
