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

package com.google.adk.firebase.utils

import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FileData
import com.google.adk.kt.types.FinishReason
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.GenerateContentConfig
import com.google.adk.kt.types.GoogleMaps
import com.google.adk.kt.types.GoogleSearch
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.ThinkingConfig
import com.google.adk.kt.types.ThinkingLevel
import com.google.adk.kt.types.ToolCall
import com.google.adk.kt.types.ToolType
import com.google.adk.kt.types.Type
import com.google.common.truth.Truth.assertThat
import com.google.firebase.ai.type.BlockReason
import com.google.firebase.ai.type.Content as FirebaseContent
import com.google.firebase.ai.type.FileDataPart
import com.google.firebase.ai.type.FinishReason as FirebaseFinishReason
import com.google.firebase.ai.type.FunctionCallPart
import com.google.firebase.ai.type.FunctionResponsePart
import com.google.firebase.ai.type.GenerateContentResponse
import com.google.firebase.ai.type.InlineDataPart
import com.google.firebase.ai.type.Part as FirebasePart
import com.google.firebase.ai.type.PromptFeedback
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.TextPart
import com.google.firebase.ai.type.ThinkingLevel as FirebaseThinkingLevel
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class ConversionsTest {

  // TODO: Use proper subjects for truth assertions

  private fun assertContentEquals(expected: FirebaseContent, actual: FirebaseContent) {
    assertThat(actual.role).isEqualTo(expected.role)
    assertThat(actual.parts).hasSize(expected.parts.size)
    expected.parts.zip(actual.parts).forEach { (expectedPart, actualPart) ->
      assertPartEquals(expectedPart, actualPart)
    }
  }

  private fun assertPartEquals(expected: FirebasePart, actual: FirebasePart) {
    assertThat(actual).isInstanceOf(expected::class.java)
    when (expected) {
      is TextPart -> assertTextPartEquals(expected, actual as TextPart)
      is InlineDataPart -> assertInlineDataPartEquals(expected, actual as InlineDataPart)
      is FileDataPart -> assertFileDataPartEquals(expected, actual as FileDataPart)
      is FunctionCallPart -> assertFunctionCallPartEquals(expected, actual as FunctionCallPart)
      is FunctionResponsePart ->
        assertFunctionResponsePartEquals(expected, actual as FunctionResponsePart)
      else -> throw IllegalArgumentException("Unsupported part type: $expected")
    }
  }

  private fun assertTextPartEquals(expected: TextPart, actual: TextPart) {
    assertThat(actual.text).isEqualTo(expected.text)
  }

  private fun assertInlineDataPartEquals(expected: InlineDataPart, actual: InlineDataPart) {
    assertThat(actual.inlineData).isEqualTo(expected.inlineData)
    assertThat(actual.mimeType).isEqualTo(expected.mimeType)
    assertThat(actual.displayName).isEqualTo(expected.displayName)
  }

  private fun assertFileDataPartEquals(expected: FileDataPart, actual: FileDataPart) {
    assertThat(actual.uri).isEqualTo(expected.uri)
    assertThat(actual.mimeType).isEqualTo(expected.mimeType)
  }

  private fun assertFunctionCallPartEquals(expected: FunctionCallPart, actual: FunctionCallPart) {
    assertThat(actual.name).isEqualTo(expected.name)
    assertThat(actual.args).isEqualTo(expected.args)
    assertThat(actual.id).isEqualTo(expected.id)
  }

  private fun assertFunctionResponsePartEquals(
    expected: FunctionResponsePart,
    actual: FunctionResponsePart,
  ) {
    assertThat(actual.name).isEqualTo(expected.name)
    assertThat(actual.response).isEqualTo(expected.response)
    assertThat(actual.id).isEqualTo(expected.id)
  }

  @Test
  fun convertRequest_returnsFirebaseRequest() {
    // Arrange
    val conversions = Conversions()

    val request =
      LlmRequest(
        contents =
          listOf(
            Content(role = Role.USER, parts = listOf(Part(text = "Hello"))),
            Content(
              role = Role.MODEL,
              parts =
                listOf(
                  Part(text = "World"),
                  Part(
                    functionCall =
                      FunctionCall(
                        name = "test_fun",
                        args = mapOf("arg" to "value", "intArg" to 42),
                        id = "abc",
                      )
                  ),
                ),
            ),
          )
      )

    val expectedFirebaseContents =
      listOf(
        FirebaseContent(role = "user", parts = listOf(TextPart(text = "Hello"))),
        FirebaseContent(
          role = "model",
          parts =
            listOf(
              TextPart(text = "World"),
              FunctionCallPart(
                name = "test_fun",
                args = mapOf("arg" to JsonPrimitive("value"), "intArg" to JsonPrimitive(42)),
                id = "abc",
              ),
            ),
        ),
      )

    // Act
    val actualFirebaseRequestContents = conversions.convertRequest(request) { contents() }

    // Assert
    assertThat(expectedFirebaseContents.size).isEqualTo(actualFirebaseRequestContents.size)
    expectedFirebaseContents.zip(actualFirebaseRequestContents).forEach { (expected, actual) ->
      assertContentEquals(expected, actual)
    }
  }

  @Test
  fun toFirebaseText_returnsTextPart() {
    // Arrange
    val conversions = Conversions()

    val text = "Hello"

    val expectedFirebasePart = TextPart(text = "Hello")

    // Act
    val actualFirebasePart = conversions.toFirebaseText(text)

    // Assert
    assertTextPartEquals(expectedFirebasePart, actualFirebasePart)
  }

  @Test
  fun toInlineData_returnsInlineDataPart() {
    // Arrange
    val conversions = Conversions()

    val inlineData =
      Blob(data = byteArrayOf(1, 2, 3), mimeType = "image/png", displayName = "image")

    val expectedFirebasePart =
      InlineDataPart(
        inlineData = byteArrayOf(1, 2, 3),
        mimeType = "image/png",
        displayName = "image",
      )

    // Act
    val actualFirebasePart = conversions.toFirebaseInlineData(inlineData)

    // Assert
    assertInlineDataPartEquals(expectedFirebasePart, actualFirebasePart)
  }

  @Test
  fun toFirebaseFileData_returnsFileDataPart() {
    // Arrange
    val conversions = Conversions()

    val fileData =
      FileData(fileUri = "file://test.txt", mimeType = "text/plain", displayName = "test.txt")

    val expectedFirebasePart = FileDataPart(uri = "file://test.txt", mimeType = "text/plain")

    // Act
    val actualFirebasePart = conversions.toFirebaseFileData(fileData)

    // Assert
    assertFileDataPartEquals(expectedFirebasePart, actualFirebasePart)
  }

  @Test
  fun toFirebaseFunctionCall_returnsFunctionCallPart() {
    // Arrange
    val conversions = Conversions()

    val functionCall =
      FunctionCall(
        name = "testFunction",
        args = mapOf("stringArg" to "argValue", "intArg" to 5),
        id = "testId",
      )

    val expectedFirebasePart =
      FunctionCallPart(
        name = "testFunction",
        args = mapOf("stringArg" to JsonPrimitive("argValue"), "intArg" to JsonPrimitive(5)),
        id = "testId",
      )

    // Act
    val actualFirebasePart = conversions.toFirebaseFunctionCall(functionCall)

    // Assert
    assertFunctionCallPartEquals(expectedFirebasePart, actualFirebasePart)
  }

  @Test
  fun toFirebaseFunctionResponse_returnsFunctionResponsePart() {
    // Arrange
    val conversions = Conversions()

    val functionResponse =
      FunctionResponse(name = "testFunction", response = mapOf("result" to 42), id = "testId")

    val expectedFirebasePart =
      FunctionResponsePart(
        name = "testFunction",
        response =
          buildJsonObject {
            val unused = put("result", JsonPrimitive(42))
          },
        id = "testId",
      )

    // Act
    val actualFirebasePart = conversions.toFirebaseFunctionResponse(functionResponse)

    // Assert
    assertFunctionResponsePartEquals(expectedFirebasePart, actualFirebasePart)
  }

  @Test
  fun toAdkFinishReason_returnsAdkFinishReason() {
    val conversions = Conversions()

    assertThat(conversions.toAdkFinishReason(FirebaseFinishReason.STOP))
      .isEqualTo(FinishReason.STOP)
    assertThat(conversions.toAdkFinishReason(FirebaseFinishReason.PROHIBITED_CONTENT))
      .isEqualTo(FinishReason.PROHIBITED_CONTENT)
    assertThat(conversions.toAdkFinishReason(FirebaseFinishReason.SAFETY))
      .isEqualTo(FinishReason.SAFETY)
    assertThat(conversions.toAdkFinishReason(FirebaseFinishReason.MAX_TOKENS))
      .isEqualTo(FinishReason.MAX_TOKENS)
    assertThat(conversions.toAdkFinishReason(FirebaseFinishReason.RECITATION))
      .isEqualTo(FinishReason.RECITATION)
    assertThat(conversions.toAdkFinishReason(FirebaseFinishReason.SPII))
      .isEqualTo(FinishReason.SPII)
    assertThat(conversions.toAdkFinishReason(FirebaseFinishReason.MALFORMED_FUNCTION_CALL))
      .isEqualTo(FinishReason.MALFORMED_FUNCTION_CALL)
    assertThat(conversions.toAdkFinishReason(FirebaseFinishReason.UNEXPECTED_TOOL_CALL))
      .isEqualTo(FinishReason.UNEXPECTED_TOOL_CALL)
    assertThat(conversions.toAdkFinishReason(FirebaseFinishReason.BLOCKLIST))
      .isEqualTo(FinishReason.BLOCKLIST)
    assertThat(conversions.toAdkFinishReason(FirebaseFinishReason.OTHER))
      .isEqualTo(FinishReason.OTHER)
    assertThat(conversions.toAdkFinishReason(FirebaseFinishReason.UNKNOWN))
      .isEqualTo(FinishReason.FINISH_REASON_UNSPECIFIED)
  }

  @Test
  fun toAdkContent_returnsAdkContent() {
    val conversions = Conversions()
    val firebaseContent = FirebaseContent(role = "user", parts = listOf(TextPart(text = "Hello")))

    val adkContent = conversions.toAdkContent(firebaseContent)

    assertThat(adkContent.role).isEqualTo(Role.USER)
    assertThat(adkContent.parts).hasSize(1)
    assertThat(adkContent.parts[0].text).isEqualTo("Hello")
  }

  /** A non-STOP finish with no block feedback falls back to the "Unknown error." message. */
  @Test
  fun toErrorMessage_nonStopFinish_returnsUnknownError() {
    assertThat(Conversions().toErrorMessage(FinishReason.MAX_TOKENS, message = null))
      .isEqualTo("Unknown error.")
  }

  /** A successful (STOP) finish is not flagged as an error. */
  @Test
  fun toErrorMessage_stopFinish_returnsNull() {
    assertThat(Conversions().toErrorMessage(FinishReason.STOP, message = null)).isNull()
  }

  /** No finish reason is not flagged as an error. */
  @Test
  fun toErrorMessage_noFinish_returnsNull() {
    assertThat(Conversions().toErrorMessage(finishReason = null, message = null)).isNull()
  }

  /** A STOP finish is never an error, even if a message is present. */
  @Test
  fun toErrorMessage_stopFinishWithMessage_returnsNull() {
    assertThat(Conversions().toErrorMessage(FinishReason.STOP, message = "ignored")).isNull()
  }

  /** A non-STOP finish uses the supplied message when one is present. */
  @Test
  fun toErrorMessage_nonStopWithMessage_returnsMessage() {
    assertThat(Conversions().toErrorMessage(FinishReason.MAX_TOKENS, message = "boom"))
      .isEqualTo("boom")
  }

  /** An empty response (no candidate) is not flagged as an error. */
  @OptIn(PublicPreviewAPI::class)
  @Test
  fun convertResponse_noCandidate_hasNoError() {
    val conversions = Conversions()
    val response =
      GenerateContentResponse(candidates = emptyList(), promptFeedback = null, usageMetadata = null)

    val llmResponse = conversions.convertResponse(response)

    assertThat(llmResponse.errorCode).isNull()
    assertThat(llmResponse.errorMessage).isNull()
  }

  /**
   * A blocked response with no candidate derives a finish reason and error from the block reason.
   */
  @OptIn(PublicPreviewAPI::class)
  @Test
  fun convertResponse_blockedNoCandidate_hasError() {
    val conversions = Conversions()
    val response =
      GenerateContentResponse(
        candidates = emptyList(),
        promptFeedback = PromptFeedback(BlockReason.SAFETY, emptyList(), "blocked"),
        usageMetadata = null,
      )

    val llmResponse = conversions.convertResponse(response)

    assertThat(llmResponse.finishReason).isEqualTo(FinishReason.SAFETY)
    assertThat(llmResponse.errorCode).isEqualTo(FinishReason.SAFETY.name)
    assertThat(llmResponse.errorMessage).isEqualTo("blocked")
  }

  // History keeps parts carrying only a thought signature. Firebase carries one as an empty text
  // part, so the model still gets its own state back.
  @Test
  fun toFirebaseContent_keepsThoughtSignatureAsEmptyTextPart() {
    val conversions = Conversions()
    val adkContent =
      Content(
        role = Role.MODEL,
        parts = listOf(Part(text = "Hello"), Part(thoughtSignature = byteArrayOf(1, 2, 3))),
      )

    val firebaseContent = conversions.toFirebaseContent(adkContent)

    assertThat(firebaseContent.parts).hasSize(2)
    assertThat((firebaseContent.parts[0] as TextPart).text).isEqualTo("Hello")
    assertThat((firebaseContent.parts[1] as TextPart).text).isEmpty()
    // The empty text is only the vehicle; the signature is the payload that has to survive.
    assertThat((firebaseContent.parts[1] as TextPart).thoughtSignature).isEqualTo("AQID")
  }

  // Only the tool fields are unexpressible: a signature riding on a server-side tool part is state
  // the model has to get back, so the part is stripped rather than discarded.
  @Test
  fun toFirebaseContent_keepsSignatureRidingOnAServerSideToolPart() {
    val conversions = Conversions()
    val adkContent =
      Content(
        role = Role.MODEL,
        parts =
          listOf(
            Part(
              toolCall = ToolCall(id = "tc1", toolType = ToolType.URL_CONTEXT),
              thoughtSignature = byteArrayOf(1, 2, 3),
            )
          ),
      )

    val firebaseContent = conversions.toFirebaseContent(adkContent)

    assertThat(firebaseContent.parts).hasSize(1)
    assertThat((firebaseContent.parts[0] as TextPart).thoughtSignature).isEqualTo("AQID")
  }

  // A content whose parts are all unexpressible must not reach Firebase as an empty content.
  @Test
  fun requestContents_dropsContentLeftEmptyByStripping() {
    val conversions = Conversions()
    val request =
      LlmRequest(
        contents =
          listOf(
            Content(role = Role.USER, parts = listOf(Part(text = "Hi"))),
            Content(
              role = Role.MODEL,
              parts = listOf(Part(toolCall = ToolCall(id = "tc1", toolType = ToolType.URL_CONTEXT))),
            ),
          )
      )

    val contents = conversions.RequestConverter(request).contents()

    assertThat(contents).hasSize(1)
    assertThat((contents[0].parts[0] as TextPart).text).isEqualTo("Hi")
  }

  // A server-side tool call belongs to the model instance that made it and Firebase cannot express
  // it, so it is dropped rather than failing the whole request.
  @Test
  fun toFirebaseContent_dropsServerSideToolParts() {
    val conversions = Conversions()
    val adkContent =
      Content(
        role = Role.MODEL,
        parts =
          listOf(
            Part(text = "Hello"),
            Part(toolCall = ToolCall(id = "tc1", toolType = ToolType.URL_CONTEXT)),
          ),
      )

    val firebaseContent = conversions.toFirebaseContent(adkContent)

    assertThat(firebaseContent.parts).hasSize(1)
    assertThat((firebaseContent.parts[0] as TextPart).text).isEqualTo("Hello")
  }

  @Test
  fun toFirebaseContent_returnsFirebaseContent() {
    val conversions = Conversions()
    val adkContent = Content(role = Role.USER, parts = listOf(Part(text = "Hello")))

    val firebaseContent = conversions.toFirebaseContent(adkContent)

    assertThat(firebaseContent.role).isEqualTo("user")
    assertThat(firebaseContent.parts).hasSize(1)
    assertThat((firebaseContent.parts[0] as TextPart).text).isEqualTo("Hello")
  }

  @Test
  fun toFirebaseThinkingLevel_returnsFirebaseThinkingLevel() {
    val conversions = Conversions()

    assertThat(conversions.toFirebaseThinkingLevel(ThinkingLevel.MINIMAL))
      .isEqualTo(FirebaseThinkingLevel.MINIMAL)

    assertThat(conversions.toFirebaseThinkingLevel(ThinkingLevel.MEDIUM))
      .isEqualTo(FirebaseThinkingLevel.MEDIUM)

    assertThat(conversions.toFirebaseThinkingLevel(ThinkingLevel.HIGH))
      .isEqualTo(FirebaseThinkingLevel.HIGH)

    assertThat(conversions.toFirebaseThinkingLevel(ThinkingLevel.LOW))
      .isEqualTo(FirebaseThinkingLevel.LOW)

    assertThat(conversions.toFirebaseThinkingLevel(ThinkingLevel.THINKING_LEVEL_UNSPECIFIED))
      .isNull()
  }

  @Test
  fun toAdkPart_functionCall_returnsPart() {
    val conversions = Conversions()
    val firebaseFunctionCall =
      FunctionCallPart(
        name = "testFunction",
        args = mapOf("stringArg" to JsonPrimitive("argValue"), "intArg" to JsonPrimitive(5)),
        id = "testId",
      )

    val adkPart = conversions.toAdkPart(firebaseFunctionCall)

    assertThat(adkPart)
      .isEqualTo(
        Part(
          functionCall =
            FunctionCall(
              name = "testFunction",
              args = mapOf("stringArg" to "argValue", "intArg" to 5),
              id = "testId",
            )
        )
      )
  }

  @OptIn(PublicPreviewAPI::class)
  @Test
  fun toAdkPart_thoughtSignature_isDecodedToBytes() {
    val conversions = Conversions()
    // "AQID" is the standard base64 of bytes [1, 2, 3].
    val firebasePart =
      TextPart.createWithThinking("thinking", isThought = true, thoughtSignature = "AQID")

    val adkPart = conversions.toAdkPart(firebasePart)

    assertThat(adkPart.text).isEqualTo("thinking")
    assertThat(adkPart.thought).isTrue()
    assertThat(adkPart.thoughtSignature).isEqualTo(byteArrayOf(1, 2, 3))
  }

  @Test
  fun toFirebasePart_thoughtSignature_isEncodedToBase64() {
    val conversions = Conversions()
    val adkPart = Part(text = "thinking", thought = true, thoughtSignature = byteArrayOf(1, 2, 3))

    val firebasePart = conversions.toFirebasePart(adkPart)

    assertThat(firebasePart).isInstanceOf(TextPart::class.java)
    val textPart = firebasePart as TextPart
    assertThat(textPart.text).isEqualTo("thinking")
    assertThat(textPart.isThought).isTrue()
    // "AQID" is the standard base64 of bytes [1, 2, 3].
    assertThat(textPart.thoughtSignature).isEqualTo("AQID")
  }

  @Test
  fun functionCallThoughtSignature_roundTrips() {
    val conversions = Conversions()
    // A Gemini-3 style function call: not a "thought" itself, but carries a signature.
    val adkPart =
      Part(
        functionCall = FunctionCall(name = "f", args = mapOf("a" to 1), id = "id-1"),
        thoughtSignature = byteArrayOf(9, 8, 7),
      )

    val firebasePart = conversions.toFirebasePart(adkPart) as FunctionCallPart
    // "CQgH" is the standard base64 of bytes [9, 8, 7].
    assertThat(firebasePart.thoughtSignature).isEqualTo("CQgH")

    val roundTripped = conversions.toAdkPart(firebasePart)
    assertThat(roundTripped.thoughtSignature).isEqualTo(byteArrayOf(9, 8, 7))
    assertThat(roundTripped.functionCall?.name).isEqualTo("f")
  }

  @Test
  fun toAdkPart_functionResponse_returnsPart() {
    val conversions = Conversions()
    val firebaseFunctionResponse =
      FunctionResponsePart(
        name = "testFunction",
        response =
          buildJsonObject {
            val unused = put("result", JsonPrimitive(42))
          },
        id = "testId",
      )

    val actualAdkPart = conversions.toAdkPart(firebaseFunctionResponse)

    assertThat(actualAdkPart)
      .isEqualTo(
        Part(
          functionResponse =
            FunctionResponse(name = "testFunction", response = mapOf("result" to 42), id = "testId")
        )
      )
  }

  @Test
  fun toFirebaseGoogleSearch_returnsFirebaseGoogleSearch() {
    val conversions = Conversions()
    val googleSearch = GoogleSearch()

    val firebaseGoogleSearch = conversions.toFirebaseGoogleSearch(googleSearch)

    assertThat(firebaseGoogleSearch).isNotNull()
  }

  @Test
  fun toFirebaseGoogleMaps_returnsFirebaseGoogleMaps() {
    val conversions = Conversions()
    val googleMaps = GoogleMaps()

    val firebaseGoogleMaps = conversions.toFirebaseGoogleMaps(googleMaps)

    assertThat(firebaseGoogleMaps).isNotNull()
  }

  @Test
  fun toFirebaseThinkingConfigBuilder_returnsThinkingConfigBuilder() {
    val conversions = Conversions()

    val actualThinkingConfigBuilder =
      conversions.toFirebaseThinkingConfigBuilder(
        ThinkingConfig(includeThoughts = true, thinkingLevel = ThinkingLevel.MEDIUM)
      )

    assertThat(actualThinkingConfigBuilder.thinkingLevel).isEqualTo(FirebaseThinkingLevel.MEDIUM)
    assertThat(actualThinkingConfigBuilder.includeThoughts).isTrue()
    assertThat(actualThinkingConfigBuilder.thinkingBudget).isNull()
  }

  @Test
  fun toAdkPart_textPart_returnsTextPart() {
    val conversions = Conversions()

    val actualTextPart = conversions.toAdkPart(TextPart(text = "test"))

    assertThat(actualTextPart).isEqualTo(Part(text = "test"))
  }

  @Test
  fun toAdkPart_inlineDataPart_returnsPart() {
    val conversions = Conversions()

    val actualPart =
      conversions.toAdkPart(
        InlineDataPart(
          inlineData = byteArrayOf(1, 2, 3),
          mimeType = "example/image",
          displayName = "displayName",
        )
      )

    assertThat(actualPart)
      .isEqualTo(
        Part(
          inlineData =
            Blob(
              mimeType = "example/image",
              displayName = "displayName",
              data = byteArrayOf(1, 2, 3),
            )
        )
      )
  }

  @Test
  fun toAdkPart_fileDataPart_returnsPart() {
    val conversions = Conversions()

    val actualPart =
      conversions.toAdkPart(FileDataPart(uri = "file://test_file", mimeType = "example/image"))

    assertThat(actualPart)
      .isEqualTo(
        Part(fileData = FileData(mimeType = "example/image", fileUri = "file://test_file"))
      )
  }

  @Test
  fun toAdkPart_unrecognizedPart_throws() {
    val conversions = Conversions()
    assertFailsWith<IllegalArgumentException> {
      conversions.toAdkPart(
        object : FirebasePart {
          override val isThought: Boolean = false
        }
      )
    }
  }

  @Test
  fun requestConverter_generationConfigBuilder_returnsBuilder() {
    val request =
      LlmRequest(
        config =
          GenerateContentConfig(
            temperature = 0.5f,
            maxOutputTokens = 123,
            topP = 23.4f,
            topK = 45,
            stopSequences = listOf("foo", "bar"),
            candidateCount = 42,
            responseMimeType = "example/response",
          )
      )

    val requestConverter = Conversions().forRequest(request)
    val actualBuilder = requestConverter.generationConfigBuilder()
    with(actualBuilder) {
      assertThat(temperature).isEqualTo(0.5f)
      assertThat(maxOutputTokens).isEqualTo(123)
      assertThat(topP).isEqualTo(23.4f)
      assertThat(topK).isEqualTo(45)
      assertThat(stopSequences).containsExactly("foo", "bar")
      assertThat(candidateCount).isEqualTo(42)
      assertThat(responseMimeType).isEqualTo("example/response")
    }
  }

  @Test
  fun toFirebaseSchema_stringSchema_returnsSchema() {
    val conversions = Conversions()

    val actualSchema =
      conversions.toFirebaseSchema(Schema(type = Type.STRING, description = "a string"))

    assertThat(actualSchema.type).isEqualTo("STRING")
    assertThat(actualSchema.description).isEqualTo("a string")
  }

  @Test
  fun toFirebaseSchema_nullSchema_returnsNullableString() {
    val conversions = Conversions()

    val actualSchema =
      conversions.toFirebaseSchema(Schema(type = Type.NULL, description = "always null"))

    assertThat(actualSchema.type).isEqualTo("STRING")
    assertThat(actualSchema.nullable).isTrue()
  }

  @Test
  fun unsupportedConstraints_formatOnANumber_isReported() {
    // Firebase takes a `format` on a string but has no parameter for one on a number, so `int32`
    // -- the only numeric format the backend accepts -- would otherwise vanish silently.
    val conversions = Conversions()

    val dropped = conversions.unsupportedConstraints(Schema(type = Type.INTEGER, format = "int32"))

    assertThat(dropped).contains("format")
  }

  @Test
  fun unsupportedConstraints_formatOnAString_isNotReported() {
    val conversions = Conversions()

    val dropped =
      conversions.unsupportedConstraints(Schema(type = Type.STRING, format = "date-time"))

    assertThat(dropped).isEmpty()
  }

  @Test
  fun unsupportedConstraints_boundsOnTheWrongType_areReported() {
    val conversions = Conversions()

    val dropped =
      conversions.unsupportedConstraints(
        Schema(type = Type.STRING, minimum = 1.0, maxItems = 3, pattern = "^a$")
      )

    assertThat(dropped).containsAtLeast("minimum", "maxItems", "pattern")
  }

  @Test
  fun unsupportedConstraints_objectPropertyBounds_areReported() {
    // Firebase's object schema has no property-count bounds, so they are always dropped.
    val conversions = Conversions()

    val dropped =
      conversions.unsupportedConstraints(
        Schema(type = Type.OBJECT, minProperties = 1, maxProperties = 5)
      )

    assertThat(dropped).containsAtLeast("minProperties", "maxProperties")
  }

  @Test
  fun unsupportedConstraints_boundsOnTheirOwnType_areNotReported() {
    val conversions = Conversions()

    val dropped =
      conversions.unsupportedConstraints(Schema(type = Type.INTEGER, minimum = 1.0, maximum = 9.0))

    assertThat(dropped).isEmpty()
  }

  @Test
  fun unsupportedConstraints_untypedUnion_reportsWhatTheUnionCannotCarry() {
    // Firebase builds a union from its alternatives alone, so anything it declares about itself
    // has nowhere to go.
    val conversions = Conversions()

    val dropped =
      conversions.unsupportedConstraints(
        Schema(
          anyOf = listOf(Schema(type = Type.STRING)),
          description = "an id",
          title = "Id",
          nullable = true,
        )
      )

    assertThat(dropped).containsAtLeast("description", "title", "nullable")
  }

  @Test
  fun toFirebaseSchema_arrayBoundsPastIntRange_areClamped() {
    val conversions = Conversions()

    val actual =
      conversions.toFirebaseSchema(
        Schema(type = Type.ARRAY, items = Schema(type = Type.STRING), maxItems = 5_000_000_000L)
      )

    assertThat(actual.maxItems).isEqualTo(Int.MAX_VALUE)
  }

  @Test
  fun toFirebaseSchema_objectWithoutProperties_becomesAJsonCarryingString() {
    // `{"type": "object"}` with no properties is how a free-form argument is declared. It used to
    // throw; it must not become `{"type":"OBJECT","properties":{}}` either, because the backend
    // rejects that with "should be non-empty for OBJECT type" and fails the entire request.
    val conversions = Conversions()

    val actualSchema = conversions.toFirebaseSchema(Schema(type = Type.OBJECT, description = "Any"))

    assertThat(actualSchema.type).isEqualTo("STRING")
    assertThat(actualSchema.description).isEqualTo("Any A JSON object, serialized as a string.")
  }

  @Test
  fun toFirebaseSchema_arrayWithoutItems_defaultsItemsToString() {
    val conversions = Conversions()

    val actualSchema = conversions.toFirebaseSchema(Schema(type = Type.ARRAY))

    assertThat(actualSchema.type).isEqualTo("ARRAY")
    assertThat(actualSchema.items?.type).isEqualTo("STRING")
  }

  @Test
  fun toFirebaseSchema_anyOfWithoutType_returnsUnion() {
    // A union carries no type of its own, which used to reach the unsupported-type branch and
    // throw.
    val conversions = Conversions()

    val actualSchema =
      conversions.toFirebaseSchema(
        Schema(anyOf = listOf(Schema(type = Type.STRING), Schema(type = Type.INTEGER)))
      )

    assertThat(actualSchema.anyOf).hasSize(2)
    assertThat(actualSchema.anyOf?.map { it.type }).containsExactly("STRING", "INTEGER")
  }

  @Test
  fun toFirebaseSchema_untypedSchema_becomesAJsonCarryingString() {
    // Nothing describes this schema, which is the same open shape as an object with no properties,
    // so it takes the same escape route rather than an empty OBJECT the backend would reject.
    val conversions = Conversions()

    val actualSchema = conversions.toFirebaseSchema(Schema(type = Type.TYPE_UNSPECIFIED))

    assertThat(actualSchema.type).isEqualTo("STRING")
  }

  @Test
  fun toFirebaseSchema_untypedSchemaWithProperties_staysAnObject() {
    // The properties are the only description this schema has, so they are worth keeping -- and a
    // non-empty OBJECT is a shape the backend accepts.
    val conversions = Conversions()

    val actualSchema =
      conversions.toFirebaseSchema(Schema(properties = mapOf("name" to Schema(type = Type.STRING))))

    assertThat(actualSchema.type).isEqualTo("OBJECT")
    assertThat(actualSchema.properties).containsKey("name")
  }

  @Test
  fun unsupportedConstraints_structureFieldsOnAUnion_areReported() {
    // A union is built from its alternatives alone, so anything describing a structure beside it
    // is dropped. Nothing said so, which is the one branch the report was missing.
    val conversions = Conversions()

    val unsupported =
      conversions.unsupportedConstraints(
        Schema(
          anyOf = listOf(Schema(type = Type.STRING)),
          properties = mapOf("a" to Schema(type = Type.STRING)),
          items = Schema(type = Type.STRING),
          required = listOf("a"),
        )
      )

    assertThat(unsupported).containsAtLeast("properties", "items", "required")
  }

  @Test
  fun unsupportedConstraints_boundsOnAnEnumeratedInteger_areReported() {
    // `FirebaseSchema.enumeration` takes only values, description, nullability and title, so a
    // bound declared beside an enum is lost however well the type would have carried it. This is
    // the shape GenAI's own `Schema.enum` KDoc uses: apartment numbers as enumerated integers.
    val conversions = Conversions()

    val unsupported =
      conversions.unsupportedConstraints(
        Schema(type = Type.INTEGER, enum = listOf("101", "201"), minimum = 100.0)
      )

    assertThat(unsupported).contains("minimum")
  }

  @Test
  fun unsupportedConstraints_enumFormat_isNotReported() {
    // `enumeration` sets `format = "enum"` itself, so that one spelling survives and reporting it
    // would be a false alarm.
    val conversions = Conversions()

    val unsupported =
      conversions.unsupportedConstraints(
        Schema(type = Type.STRING, format = "enum", enum = listOf("east", "west"))
      )

    assertThat(unsupported).doesNotContain("format")
  }

  @Test
  fun unsupportedConstraints_anotherFormatOnAnEnum_isReported() {
    val conversions = Conversions()

    val unsupported =
      conversions.unsupportedConstraints(
        Schema(type = Type.STRING, format = "date-time", enum = listOf("east", "west"))
      )

    assertThat(unsupported).contains("format")
  }

  @Test
  fun substitutesStringForEnumType_enumOnAnInteger_reportsTheSubstitution() {
    // The bounds are not the only loss: the declared INTEGER reaches the model as a string.
    val conversions = Conversions()

    val substitutes =
      conversions.substitutesStringForEnumType(
        Schema(type = Type.INTEGER, enum = listOf("101", "201"))
      )

    assertThat(substitutes).isTrue()
  }

  @Test
  fun substitutesStringForEnumType_enumOnAString_reportsNothing() {
    val conversions = Conversions()

    val substitutes =
      conversions.substitutesStringForEnumType(
        Schema(type = Type.STRING, enum = listOf("east", "west"))
      )

    assertThat(substitutes).isFalse()
  }

  @Test
  fun dropsUnionForItsType_typeDeclaredBesideAnyOf_dropsTheUnion() {
    // Firebase holds a type or a union, never both, so a schema declaring both resolves to the
    // type. Asserted on the decision rather than on the conversion, because the conversion warns
    // and the Android logging sink is a stub here.
    val conversions = Conversions()

    val drops =
      conversions.dropsUnionForItsType(
        Schema(type = Type.STRING, anyOf = listOf(Schema(type = Type.INTEGER)))
      )

    assertThat(drops).isTrue()
  }

  @Test
  fun dropsUnionForItsType_untypedUnion_keepsTheUnion() {
    val conversions = Conversions()

    val drops =
      conversions.dropsUnionForItsType(Schema(anyOf = listOf(Schema(type = Type.INTEGER))))

    assertThat(drops).isFalse()
  }

  @Test
  fun toFirebaseSchema_constraintFields_reachTheFirebaseSchema() {
    val conversions = Conversions()

    val actualSchema =
      conversions.toFirebaseSchema(
        Schema(
          type = Type.INTEGER,
          description = "a bounded integer",
          nullable = true,
          title = "Count",
          minimum = 1.0,
          maximum = 10.0,
        )
      )

    assertThat(actualSchema.type).isEqualTo("INTEGER")
    assertThat(actualSchema.nullable).isTrue()
    assertThat(actualSchema.title).isEqualTo("Count")
    assertThat(actualSchema.minimum).isEqualTo(1.0)
    assertThat(actualSchema.maximum).isEqualTo(10.0)
  }

  @Test
  fun toFirebaseSchema_arrayBounds_reachTheFirebaseSchema() {
    val conversions = Conversions()

    val actualSchema =
      conversions.toFirebaseSchema(
        Schema(
          type = Type.ARRAY,
          items = Schema(type = Type.STRING),
          title = "Tags",
          minItems = 1,
          maxItems = 5,
        )
      )

    assertThat(actualSchema.type).isEqualTo("ARRAY")
    assertThat(actualSchema.title).isEqualTo("Tags")
    assertThat(actualSchema.minItems).isEqualTo(1)
    assertThat(actualSchema.maxItems).isEqualTo(5)
  }

  @Test
  fun toFirebaseSchema_stringFormat_reachesTheFirebaseSchema() {
    val conversions = Conversions()

    val actualSchema =
      conversions.toFirebaseSchema(Schema(type = Type.STRING, format = "date-time"))

    assertThat(actualSchema.format).isEqualTo("date-time")
  }

  @Test
  fun toFirebaseSchema_numberSchema_returnsSchema() {
    val conversions = Conversions()

    val actualSchema =
      conversions.toFirebaseSchema(Schema(type = Type.NUMBER, description = "a number"))

    assertThat(actualSchema.type).isEqualTo("NUMBER")
    assertThat(actualSchema.description).isEqualTo("a number")
  }

  @Test
  fun toFirebaseSchema_integerSchema_returnsSchema() {
    val conversions = Conversions()

    val actualSchema =
      conversions.toFirebaseSchema(Schema(type = Type.INTEGER, description = "an integer"))

    assertThat(actualSchema.type).isEqualTo("INTEGER")
    assertThat(actualSchema.description).isEqualTo("an integer")
  }

  @Test
  fun toFirebaseSchema_booleanSchema_returnsSchema() {
    val conversions = Conversions()

    val actualSchema =
      conversions.toFirebaseSchema(Schema(type = Type.BOOLEAN, description = "a boolean"))

    assertThat(actualSchema.type).isEqualTo("BOOLEAN")
    assertThat(actualSchema.description).isEqualTo("a boolean")
  }

  @Test
  fun toFirebaseSchema_enumSchema_returnsSchema() {
    val conversions = Conversions()

    val actualSchema =
      conversions.toFirebaseSchema(
        Schema(type = Type.STRING, enum = listOf("A", "B", "C"), description = "an enum")
      )

    assertThat(actualSchema.type).isEqualTo("STRING")
    assertThat(actualSchema.description).isEqualTo("an enum")
    assertThat(actualSchema.enum).containsExactly("A", "B", "C")
  }

  @Test
  fun toFirebaseSchema_objectSchema_returnsSchema() {
    val conversions = Conversions()

    val actualSchema =
      conversions.toFirebaseSchema(
        Schema(
          type = Type.OBJECT,
          properties =
            mapOf(
              "name" to Schema(type = Type.STRING),
              "age" to Schema(type = Type.NUMBER),
              "isStudent" to Schema(type = Type.BOOLEAN),
              "dept" to Schema(enum = listOf("A", "B", "C")),
            ),
          required = listOf("name", "age"),
          description = "an object",
        )
      )

    assertThat(actualSchema.type).isEqualTo("OBJECT")
    assertThat(actualSchema.description).isEqualTo("an object")
    assertThat(actualSchema.properties?.size).isEqualTo(4)
    assertThat(actualSchema.properties?.get("name")?.type).isEqualTo("STRING")
    assertThat(actualSchema.properties?.get("age")?.type).isEqualTo("NUMBER")
    assertThat(actualSchema.properties?.get("isStudent")?.type).isEqualTo("BOOLEAN")
    assertThat(actualSchema.properties?.get("dept")?.enum).containsExactly("A", "B", "C")
    assertThat(actualSchema.required).containsExactly("name", "age")
  }

  @Test
  fun toFirebaseSchema_arraySchema_returnsSchema() {
    val conversions = Conversions()

    val actualSchema =
      conversions.toFirebaseSchema(
        Schema(type = Type.ARRAY, items = Schema(type = Type.STRING), description = "an array")
      )

    assertThat(actualSchema.type).isEqualTo("ARRAY")
    assertThat(actualSchema.description).isEqualTo("an array")
    assertThat(actualSchema.items?.type).isEqualTo("STRING")
  }

  @Test
  fun optionalParameters_emptyRequired_returnsOriginalList() {
    val conversions = Conversions()
    val actual =
      conversions.optionalParameters(
        Schema(properties = mapOf("key" to Schema(type = Type.STRING)))
      )

    assertThat(actual).containsExactly("key")
  }

  @Test
  fun optionalParameters_nonEmptyRequired_performsSubtraction() {
    val conversions = Conversions()
    val actual =
      conversions.optionalParameters(
        Schema(
          properties =
            mapOf("key" to Schema(type = Type.STRING), "key2" to Schema(type = Type.STRING)),
          required = listOf("key"),
        )
      )

    assertThat(actual).containsExactly("key2")
  }

  @Test
  fun optionalParameters_nullSchema_returnsEmptyList() {
    val conversions = Conversions()

    val actual = conversions.optionalParameters(null)

    assertThat(actual).isEmpty()
  }

  @Test
  fun optionalParameters_nullProperties_returnsEmptyList() {
    val conversions = Conversions()

    val actual = conversions.optionalParameters(Schema(type = Type.OBJECT))

    assertThat(actual).isEmpty()
  }

  @Test
  fun optionalParameters_nullRequired_returnsOriginalList() {
    val conversions = Conversions()
    val actual =
      conversions.optionalParameters(
        Schema(properties = mapOf("key" to Schema(type = Type.STRING)))
      )

    assertThat(actual).containsExactly("key")
  }
}
