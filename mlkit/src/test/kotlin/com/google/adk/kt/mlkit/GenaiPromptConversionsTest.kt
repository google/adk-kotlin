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
package com.google.adk.kt.mlkit

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.adk.kt.mlkit.GenaiPromptConversions.buildLlmResponse
import com.google.adk.kt.mlkit.GenaiPromptConversions.includeThoughts
import com.google.adk.kt.mlkit.GenaiPromptConversions.selectThoughtText
import com.google.adk.kt.mlkit.GenaiPromptConversions.toGenerateContentRequest
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FileData
import com.google.adk.kt.types.FinishReason
import com.google.adk.kt.types.GenerateContentConfig
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.ThinkingConfig
import com.google.adk.kt.types.ThinkingLevel
import com.google.common.truth.Truth.assertThat
import com.google.mlkit.common.sdkinternal.MlKitContext
import com.google.mlkit.genai.prompt.Candidate.FinishReason as MlKitFinishReason
import com.google.mlkit.genai.prompt.TextPart
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests for the ML Kit request/response conversions: text, images, config, and response mapping.
 */
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

  @Test
  fun toGenerateContentRequest_singleTurn_noRoleMarker() {
    val request =
      LlmRequest(contents = listOf(Content(role = "user", parts = listOf(Part(text = "Hello")))))

    val generateContentRequest = request.toGenerateContentRequest()

    val texts =
      generateContentRequest.contents
        .flatMap { it.parts }
        .filterIsInstance<TextPart>()
        .map { it.textString }
    assertThat(texts).containsExactly("Hello")
    // Single-turn requests get no default multi-turn system instruction.
    assertThat(generateContentRequest.systemInstruction).isNull()
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
    // All images are sent; the deprecated `image` getter still returns the first one (1x1).
    assertThat(generateContentRequest.image?.width).isEqualTo(1)
    assertThat(generateContentRequest.image?.height).isEqualTo(1)
  }

  @Test
  fun toGenerateContentRequest_textAndSystemInstruction_usesSystemInstructionField() {
    val request =
      LlmRequest(
        contents = listOf(Content(role = "user", parts = listOf(Part(text = "Hello World")))),
        config =
          GenerateContentConfig(
            systemInstruction =
              Content(
                parts =
                  listOf(
                    Part(text = "Test system instruction"),
                    Part(text = "Another system instruction"),
                  )
              )
          ),
      )
    val generateContentRequest = request.toGenerateContentRequest()
    assertThat(generateContentRequest.text.textString).isEqualTo("Hello World")
    assertThat(generateContentRequest.image).isNull()
    assertThat(generateContentRequest.systemInstruction?.textString)
      .isEqualTo("Test system instruction\n\nAnother system instruction")
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

  /** Builds a minimal request carrying [thinkingConfig] and converts it. */
  private fun thinkingRequest(thinkingConfig: ThinkingConfig?) =
    LlmRequest(
        contents = listOf(Content(role = "user", parts = listOf(Part(text = "Hello World")))),
        config = GenerateContentConfig(thinkingConfig = thinkingConfig),
      )
      .toGenerateContentRequest()

  /** Without a thinking config the request explicitly disables thinking. */
  @Test
  fun toGenerateContentRequest_noThinkingConfig_disablesThinking() {
    assertThat(thinkingRequest(null).enableThinking).isFalse()
  }

  /** Asking for a thinking config at all is enough to turn thinking on. */
  @Test
  fun toGenerateContentRequest_emptyThinkingConfig_enablesThinking() {
    assertThat(thinkingRequest(ThinkingConfig()).enableThinking).isTrue()
  }

  @Test
  fun toGenerateContentRequest_includeThoughts_enablesThinking() {
    assertThat(thinkingRequest(ThinkingConfig(includeThoughts = true)).enableThinking).isTrue()
  }

  /** A zero budget is genai's encoding of DISABLED. */
  @Test
  fun toGenerateContentRequest_zeroThinkingBudget_disablesThinking() {
    assertThat(thinkingRequest(ThinkingConfig(thinkingBudget = 0)).enableThinking).isFalse()
  }

  /** A zero budget wins over a request for thoughts: there is nothing to think about. */
  @Test
  fun toGenerateContentRequest_zeroThinkingBudgetWithIncludeThoughts_disablesThinking() {
    val config = ThinkingConfig(thinkingBudget = 0, includeThoughts = true)

    assertThat(thinkingRequest(config).enableThinking).isFalse()
  }

  /** -1 is genai's AUTOMATIC, which ML Kit expresses as simply on. */
  @Test
  fun toGenerateContentRequest_automaticThinkingBudget_enablesThinking() {
    assertThat(thinkingRequest(ThinkingConfig(thinkingBudget = -1)).enableThinking).isTrue()
  }

  /** ML Kit has no token budget, so a positive one still just turns thinking on. */
  @Test
  fun toGenerateContentRequest_positiveThinkingBudget_enablesThinking() {
    assertThat(thinkingRequest(ThinkingConfig(thinkingBudget = 2048)).enableThinking).isTrue()
  }

  /** ML Kit has no thinking level, so any level still just turns thinking on. */
  @Test
  fun toGenerateContentRequest_thinkingLevel_enablesThinking() {
    val config = ThinkingConfig(thinkingLevel = ThinkingLevel.HIGH)

    assertThat(thinkingRequest(config).enableThinking).isTrue()
  }

  /**
   * The gate itself, not just the flag that feeds it: with the thoughts declined nothing is
   * surfaced however many the model returned. This is the mirror's only coverage of that branch,
   * since Gradle cannot build a thought-bearing ML Kit response.
   */
  @Test
  fun selectThoughtText_thoughtsDeclined_returnsNull() {
    assertThat(selectThoughtText(listOf("counting things"), includeThoughts = false)).isNull()
  }

  @Test
  fun selectThoughtText_thoughtsRequested_returnsTheThought() {
    assertThat(selectThoughtText(listOf("counting things"), includeThoughts = true))
      .isEqualTo("counting things")
  }

  /** ML Kit does not pair thoughts with candidates, so only the first is used. */
  @Test
  fun selectThoughtText_severalThoughts_returnsTheFirst() {
    val thoughts = listOf("first thought", "second thought")

    assertThat(selectThoughtText(thoughts, includeThoughts = true)).isEqualTo("first thought")
  }

  /** An empty thought is nothing to show. */
  @Test
  fun selectThoughtText_emptyThought_returnsNull() {
    assertThat(selectThoughtText(listOf(""), includeThoughts = true)).isNull()
  }

  @Test
  fun selectThoughtText_noThoughts_returnsNull() {
    assertThat(selectThoughtText(emptyList(), includeThoughts = true)).isNull()
  }

  /**
   * Whether the caller wants the thoughts back is read straight off the config. The response side
   * of the gate is covered end to end in internal/mlkit, which can fabricate ML Kit responses.
   */
  @Test
  fun includeThoughts_requestedOnThinkingConfig_isTrue() {
    val request =
      LlmRequest(
        config = GenerateContentConfig(thinkingConfig = ThinkingConfig(includeThoughts = true))
      )

    assertThat(request.includeThoughts()).isTrue()
  }

  @Test
  fun includeThoughts_declinedOnThinkingConfig_isFalse() {
    val request =
      LlmRequest(
        config = GenerateContentConfig(thinkingConfig = ThinkingConfig(includeThoughts = false))
      )

    assertThat(request.includeThoughts()).isFalse()
  }

  /** Thinking may still be on via the budget; that alone does not ask for the thoughts. */
  @Test
  fun includeThoughts_thinkingConfigWithoutTheFlag_isFalse() {
    val request =
      LlmRequest(
        config = GenerateContentConfig(thinkingConfig = ThinkingConfig(thinkingBudget = -1))
      )

    assertThat(request.includeThoughts()).isFalse()
  }

  @Test
  fun includeThoughts_noThinkingConfig_isFalse() {
    assertThat(LlmRequest().includeThoughts()).isFalse()
  }

  /** An unspecified level is not a level, so it is neither honored nor reported as unsupported. */
  @Test
  fun toGenerateContentRequest_unspecifiedThinkingLevel_enablesThinking() {
    val config = ThinkingConfig(thinkingLevel = ThinkingLevel.THINKING_LEVEL_UNSPECIFIED)

    assertThat(thinkingRequest(config).enableThinking).isTrue()
  }

  /**
   * A thought from an earlier turn is the model's private reasoning, so it never goes back to the
   * model - it would otherwise be replayed inside the `[model]:` turn.
   */
  @Test
  fun toGenerateContentRequest_historyWithThought_dropsTheThought() {
    val request =
      LlmRequest(
        contents =
          listOf(
            Content(role = "user", parts = listOf(Part(text = "How much?"))),
            Content(
              role = "model",
              parts =
                listOf(
                  Part(text = "the shop charges 5 for 3", thought = true),
                  Part(text = "20 dollars"),
                ),
            ),
            Content(role = "user", parts = listOf(Part(text = "And for six?"))),
          )
      )

    val texts =
      request
        .toGenerateContentRequest()
        .contents
        .flatMap { it.parts }
        .filterIsInstance<TextPart>()
        .map { it.textString }

    assertThat(texts)
      .containsExactly("[user]: How much?", "[model]: 20 dollars", "[user]: And for six?")
      .inOrder()
  }

  /** A turn that is nothing but a thought contributes no content at all. */
  @Test
  fun toGenerateContentRequest_historyWithThoughtOnlyTurn_dropsTheTurn() {
    val request =
      LlmRequest(
        contents =
          listOf(
            Content(role = "user", parts = listOf(Part(text = "How much?"))),
            Content(role = "model", parts = listOf(Part(text = "reasoning", thought = true))),
          )
      )

    val texts =
      request
        .toGenerateContentRequest()
        .contents
        .flatMap { it.parts }
        .filterIsInstance<TextPart>()
        .map { it.textString }

    assertThat(texts).containsExactly("[user]: How much?")
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

  /** STOP is the normal completion, so the text passes through unflagged. */
  @Test
  fun buildLlmResponse_stopFinishReason_hasTextAndNoError() {
    val response =
      buildLlmResponse(
        text = "Hello World",
        mlKitFinishReason = MlKitFinishReason.STOP,
        hasThoughtProcess = false,
      )

    assertThat(response.content?.parts?.firstOrNull()?.text).isEqualTo("Hello World")
    assertThat(response.finishReason).isEqualTo(FinishReason.STOP)
    assertThat(response.errorMessage).isNull()
  }

  /** Generated content is attributed to the model role, not the user. */
  @Test
  fun buildLlmResponse_contentRoleIsModel() {
    val response =
      buildLlmResponse(
        text = "Hello World",
        mlKitFinishReason = MlKitFinishReason.STOP,
        hasThoughtProcess = false,
      )

    assertThat(response.content?.role).isEqualTo("model")
  }

  /** Truncated output keeps its text but is still flagged. */
  @Test
  fun buildLlmResponse_maxTokensFinishReason_hasErrorMessage() {
    val response =
      buildLlmResponse(
        text = "Hello World",
        mlKitFinishReason = MlKitFinishReason.MAX_TOKENS,
        hasThoughtProcess = false,
      )

    assertThat(response.content?.parts?.firstOrNull()?.text).isEqualTo("Hello World")
    assertThat(response.finishReason).isEqualTo(FinishReason.MAX_TOKENS)
    assertThat(response.errorMessage).contains("MAX_TOKENS")
  }

  /** Any finish reason other than STOP is surfaced as an error. */
  @Test
  fun buildLlmResponse_otherFinishReason_hasErrorMessage() {
    val response =
      buildLlmResponse(
        text = "Hello World",
        mlKitFinishReason = MlKitFinishReason.OTHER,
        hasThoughtProcess = false,
      )

    assertThat(response.finishReason).isEqualTo(FinishReason.OTHER)
    assertThat(response.errorMessage).contains("OTHER")
  }

  /** Streaming chunks arrive without a finish reason and must not be flagged. */
  @Test
  fun buildLlmResponse_noFinishReason_hasNoErrorMessage() {
    val response =
      buildLlmResponse(text = "Hello World", mlKitFinishReason = null, hasThoughtProcess = false)

    assertThat(response.content?.parts?.firstOrNull()?.text).isEqualTo("Hello World")
    assertThat(response.finishReason).isNull()
    assertThat(response.errorMessage).isNull()
  }

  /** A response with neither text nor thoughts is an error. */
  @Test
  fun buildLlmResponse_noCandidate_hasErrorMessage() {
    val response =
      buildLlmResponse(text = null, mlKitFinishReason = null, hasThoughtProcess = false)

    assertThat(response.content).isNull()
    assertThat(response.finishReason).isNull()
    assertThat(response.errorMessage).isEqualTo("No candidates returned.")
  }

  /**
   * ML Kit's thought chunks carry no candidate. With the thought suppressed - the caller did not
   * set `includeThoughts` - the chunk is empty, but it is still not an error.
   */
  @Test
  fun buildLlmResponse_noCandidateButThoughtProcess_hasNoErrorMessage() {
    val response = buildLlmResponse(text = null, mlKitFinishReason = null, hasThoughtProcess = true)

    assertThat(response.content).isNull()
    assertThat(response.errorMessage).isNull()
  }

  @Test
  fun toGenerateContentRequest_multiTurn_addsRoleMarkers() {
    val request =
      LlmRequest(
        contents =
          listOf(
            Content(role = "user", parts = listOf(Part(text = "Hello"))),
            Content(role = "model", parts = listOf(Part(text = "Hi there"))),
            Content(role = "user", parts = listOf(Part(text = "How are you?"))),
          )
      )

    val generateContentRequest = request.toGenerateContentRequest()

    val texts =
      generateContentRequest.contents
        .flatMap { it.parts }
        .filterIsInstance<TextPart>()
        .map { it.textString }
    assertThat(texts)
      .containsExactly("[user]: Hello", "[model]: Hi there", "[user]: How are you?")
      .inOrder()
  }

  @Test
  fun toGenerateContentRequest_multiTurn_groupsTextPartsUnderSingleRoleMarker() {
    val request =
      LlmRequest(
        contents =
          listOf(
            Content(role = "user", parts = listOf(Part(text = "Hi"))),
            Content(
              role = "model",
              parts = listOf(Part(text = "First line"), Part(text = "Second line")),
            ),
          )
      )

    val texts =
      request
        .toGenerateContentRequest()
        .contents
        .flatMap { it.parts }
        .filterIsInstance<TextPart>()
        .map { it.textString }
    // A turn's text parts are joined with "\n\n" and carry a single leading role marker.
    assertThat(texts).containsExactly("[user]: Hi", "[model]: First line\n\nSecond line").inOrder()
  }

  @Test
  fun toGenerateContentRequest_multiTurn_nullRole_defaultsToUserMarker() {
    val request =
      LlmRequest(
        contents =
          listOf(
            Content(parts = listOf(Part(text = "First"))),
            Content(role = "model", parts = listOf(Part(text = "Second"))),
          )
      )

    val texts =
      request
        .toGenerateContentRequest()
        .contents
        .flatMap { it.parts }
        .filterIsInstance<TextPart>()
        .map { it.textString }
    assertThat(texts).containsExactly("[user]: First", "[model]: Second").inOrder()
  }

  @Test
  fun toGenerateContentRequest_multiTurn_addsDefaultSystemInstruction() {
    val request =
      LlmRequest(
        contents =
          listOf(
            Content(role = "user", parts = listOf(Part(text = "Hello"))),
            Content(role = "model", parts = listOf(Part(text = "Hi there"))),
          )
      )

    val systemInstruction = request.toGenerateContentRequest().systemInstruction?.textString

    assertThat(systemInstruction).contains("Never write a marker yourself")
    assertThat(systemInstruction).contains("Write only your own reply")
  }

  @Test
  fun toGenerateContentRequest_multiTurn_combinesWithUserSystemInstruction() {
    val request =
      LlmRequest(
        contents =
          listOf(
            Content(role = "user", parts = listOf(Part(text = "Hello"))),
            Content(role = "model", parts = listOf(Part(text = "Hi there"))),
          ),
        config =
          GenerateContentConfig(
            systemInstruction = Content(parts = listOf(Part(text = "Be concise.")))
          ),
      )

    val systemInstruction = request.toGenerateContentRequest().systemInstruction?.textString

    assertThat(systemInstruction).contains("Never write a marker yourself")
    // The caller's instruction comes last, so it is not overridden by the default guidance.
    assertThat(systemInstruction).endsWith("\n\nBe concise.")
  }
}
