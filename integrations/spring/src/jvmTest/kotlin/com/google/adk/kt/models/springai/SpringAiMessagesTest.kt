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
package com.google.adk.kt.models.springai

import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FileData
import com.google.adk.kt.types.FinishReason
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.GenerateContentConfig
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.common.truth.Truth.assertThat
import java.net.URI
import kotlin.test.Test
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.metadata.ChatGenerationMetadata
import org.springframework.ai.chat.metadata.ChatResponseMetadata
import org.springframework.ai.chat.metadata.DefaultUsage
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.content.Media
import org.springframework.core.io.ByteArrayResource
import org.springframework.util.MimeType

class SpringAiMessagesTest {

  @Test
  fun request_buildsSystemUserAndAssistantMessages() {
    val request =
      LlmRequest(
        config =
          GenerateContentConfig(
            systemInstruction = Content(parts = listOf(Part(text = "Be concise")))
          ),
        contents =
          listOf(
            Content(role = Role.USER, parts = listOf(Part(text = "Hi"))),
            Content(role = Role.MODEL, parts = listOf(Part(text = "Hello"))),
          ),
      )

    val messages = request.toSpringAiPrompt(null).instructions

    assertThat(messages).hasSize(3)
    assertThat(messages[0]).isInstanceOf(SystemMessage::class.java)
    assertThat(messages[0].text).isEqualTo("Be concise")
    assertThat(messages[1]).isInstanceOf(UserMessage::class.java)
    assertThat(messages[1].text).isEqualTo("Hi")
    assertThat(messages[2]).isInstanceOf(AssistantMessage::class.java)
    assertThat(messages[2].text).isEqualTo("Hello")
  }

  @Test
  fun request_functionResponseTurn_emitsOnlyToolResponseMessage() {
    val request =
      LlmRequest(
        contents =
          listOf(
            Content(
              role = Role.USER,
              parts =
                listOf(
                  Part(
                    functionResponse =
                      FunctionResponse(
                        id = "call-1",
                        name = "getWeather",
                        response = mapOf("temp" to 20),
                      )
                  )
                ),
            )
          )
      )

    val messages = request.toSpringAiPrompt(null).instructions

    assertThat(messages).hasSize(1)
    assertThat(messages[0]).isInstanceOf(ToolResponseMessage::class.java)
    val toolResponse = (messages[0] as ToolResponseMessage).responses.single()
    assertThat(toolResponse.id()).isEqualTo("call-1")
    assertThat(toolResponse.name()).isEqualTo("getWeather")
  }

  @Test
  fun request_assistantFunctionCall_becomesToolCall() {
    val request =
      LlmRequest(
        contents =
          listOf(
            Content(
              role = Role.MODEL,
              parts =
                listOf(
                  Part(
                    functionCall =
                      FunctionCall(
                        id = "call-1",
                        name = "getWeather",
                        args = mapOf("city" to "Paris"),
                      )
                  )
                ),
            )
          )
      )

    val messages = request.toSpringAiPrompt(null).instructions

    val assistant = messages.single() as AssistantMessage
    val toolCall = assistant.toolCalls.single()
    assertThat(toolCall.id()).isEqualTo("call-1")
    assertThat(toolCall.name()).isEqualTo("getWeather")
    assertThat(toolCall.arguments()).contains("Paris")
  }

  @Test
  fun request_userInlineData_becomesUserMessageMedia() {
    val request =
      LlmRequest(
        contents =
          listOf(
            Content(
              role = Role.USER,
              parts =
                listOf(Part(inlineData = Blob(mimeType = "image/png", data = byteArrayOf(1, 2, 3)))),
            )
          )
      )

    val user = request.toSpringAiPrompt(null).instructions.single() as UserMessage

    assertThat(user.media).hasSize(1)
    assertThat(user.media.single().mimeType.toString()).isEqualTo("image/png")
  }

  @Test
  fun response_emptyResults_returnsEmptyResponse() {
    val response = ChatResponse(emptyList())

    val llmResponse = response.toLlmResponse()

    assertThat(llmResponse.content).isNull()
  }

  @Test
  fun response_emptyResultsWithUsage_preservesUsage() {
    // A streaming terminal chunk carries usage with empty results; conversion must keep the usage
    // rather than dropping it on an early return.
    val metadata = ChatResponseMetadata.builder().usage(DefaultUsage(10, 5, 15)).build()

    val response = ChatResponse(emptyList(), metadata).toLlmResponse()

    assertThat(response.content).isNull()
    assertThat(response.usageMetadata?.totalTokenCount).isEqualTo(15)
  }

  @Test
  fun response_textGeneration_mapsToModelContent() {
    val response = ChatResponse(listOf(Generation(AssistantMessage("Hello there"))))

    val llmResponse = response.toLlmResponse()

    assertThat(llmResponse.content?.role).isEqualTo(Role.MODEL)
    assertThat(llmResponse.content?.text()).isEqualTo("Hello there")
  }

  @Test
  fun response_thoughtGeneration_setsThoughtFlagOnPart() {
    // The provider flags a thinking generation via isThought metadata; conversion must carry it as
    // thought=true so the reasoning text is not replayed as content on the next turn.
    val assistant =
      AssistantMessage.builder()
        .content("reasoning...")
        .properties(mapOf("isThought" to true))
        .build()

    val part = ChatResponse(listOf(Generation(assistant))).toLlmResponse().content?.parts?.single()

    assertThat(part?.text).isEqualTo("reasoning...")
    assertThat(part?.thought).isTrue()
  }

  @Test
  fun response_toolCall_mapsToFunctionCallPart() {
    val toolCall =
      AssistantMessage.ToolCall("call-1", "function", "getWeather", "{\"city\":\"Paris\"}")
    val assistant = AssistantMessage.builder().content("").toolCalls(listOf(toolCall)).build()
    val response = ChatResponse(listOf(Generation(assistant)))

    val functionCall = response.toLlmResponse().content?.parts?.single()?.functionCall

    assertThat(functionCall?.name).isEqualTo("getWeather")
    assertThat(functionCall?.id).isEqualTo("call-1")
    assertThat(functionCall?.args).containsEntry("city", "Paris")
  }

  @Test
  fun finishReason_unset_isNullFromConversion() {
    val response = ChatResponse(listOf(Generation(AssistantMessage("hi"))))

    // The provider set no finish reason, so conversion leaves it null; SpringAiModel backfills
    // STOP separately.
    assertThat(response.toLlmResponse().finishReason).isNull()
  }

  @Test
  fun finishReason_nonStop_mapsAndSetsErrorCode() {
    val generation =
      Generation(
        AssistantMessage("blocked"),
        ChatGenerationMetadata.builder().finishReason("SAFETY").build(),
      )

    val response = ChatResponse(listOf(generation)).toLlmResponse()

    assertThat(response.finishReason).isEqualTo(FinishReason.SAFETY)
    assertThat(response.errorCode).isEqualTo("SAFETY")
  }

  @Test
  fun finishReason_length_mapsToMaxTokens() {
    val generation =
      Generation(
        AssistantMessage("cut"),
        ChatGenerationMetadata.builder().finishReason("length").build(),
      )

    val response = ChatResponse(listOf(generation)).toLlmResponse()

    assertThat(response.finishReason).isEqualTo(FinishReason.MAX_TOKENS)
    assertThat(response.errorCode).isEqualTo("MAX_TOKENS")
  }

  @Test
  fun finishReason_stop_hasNoErrorCode() {
    val generation =
      Generation(
        AssistantMessage("done"),
        ChatGenerationMetadata.builder().finishReason("STOP").build(),
      )

    val response = ChatResponse(listOf(generation)).toLlmResponse()

    assertThat(response.finishReason).isEqualTo(FinishReason.STOP)
    assertThat(response.errorCode).isNull()
  }

  @Test
  fun usage_mapsPromptCompletionAndTotalTokens() {
    val metadata = ChatResponseMetadata.builder().usage(DefaultUsage(10, 5, 15)).build()
    val response =
      ChatResponse(listOf(Generation(AssistantMessage("hi"))), metadata).toLlmResponse()

    val usage = response.usageMetadata!!
    assertThat(usage.promptTokenCount).isEqualTo(10)
    assertThat(usage.candidatesTokenCount).isEqualTo(5)
    assertThat(usage.totalTokenCount).isEqualTo(15)
  }

  @Test
  fun response_multipleGenerations_mergeIntoOneModelContent() {
    // The Google GenAI provider returns one Generation per response part, so text and the tool call
    // arrive as separate Generations that must merge into a single model Content.
    val textGeneration = Generation(AssistantMessage("Calling the tool."))
    val toolCall =
      AssistantMessage.ToolCall("call-1", "function", "getWeather", "{\"city\":\"Paris\"}")
    val toolGeneration =
      Generation(AssistantMessage.builder().content("").toolCalls(listOf(toolCall)).build())

    val parts = ChatResponse(listOf(textGeneration, toolGeneration)).toLlmResponse().content?.parts

    assertThat(parts).hasSize(2)
    assertThat(parts?.get(0)?.text).isEqualTo("Calling the tool.")
    assertThat(parts?.get(1)?.functionCall?.name).isEqualTo("getWeather")
  }

  @Test
  fun finishReason_geminiName_mapsViaValueOfAndSetsErrorMessage() {
    val generation =
      Generation(
        AssistantMessage("blocked"),
        ChatGenerationMetadata.builder().finishReason("BLOCKLIST").build(),
      )

    val response = ChatResponse(listOf(generation)).toLlmResponse()

    assertThat(response.finishReason).isEqualTo(FinishReason.BLOCKLIST)
    assertThat(response.errorCode).isEqualTo("BLOCKLIST")
    assertThat(response.errorMessage).isEqualTo("BLOCKLIST")
  }

  @Test
  fun finishReason_stopSequence_mapsToStop() {
    val generation =
      Generation(
        AssistantMessage("done"),
        ChatGenerationMetadata.builder().finishReason("stop_sequence").build(),
      )

    val response = ChatResponse(listOf(generation)).toLlmResponse()

    assertThat(response.finishReason).isEqualTo(FinishReason.STOP)
    assertThat(response.errorCode).isNull()
  }

  @Test
  fun finishReason_unknown_fallsBackToOther() {
    val generation =
      Generation(
        AssistantMessage("x"),
        ChatGenerationMetadata.builder().finishReason("brand_new_reason").build(),
      )

    val response = ChatResponse(listOf(generation)).toLlmResponse()

    assertThat(response.finishReason).isEqualTo(FinishReason.OTHER)
  }

  @Test
  fun request_assistantThoughtPart_isNotReplayedAsText() {
    val request =
      LlmRequest(
        contents =
          listOf(
            Content(
              role = Role.MODEL,
              parts =
                listOf(Part(text = "reasoning...", thought = true), Part(text = "Final answer")),
            )
          )
      )

    val assistant = request.toSpringAiPrompt(null).instructions.single() as AssistantMessage

    assertThat(assistant.text).isEqualTo("Final answer")
  }

  @Test
  fun request_functionResponseWithText_emitsToolResponseBeforeUserMessage() {
    val request =
      LlmRequest(
        contents =
          listOf(
            Content(
              role = Role.USER,
              parts =
                listOf(
                  Part(
                    functionResponse =
                      FunctionResponse(
                        id = "call-1",
                        name = "getWeather",
                        response = mapOf("temp" to 20),
                      )
                  ),
                  Part(text = "thanks"),
                ),
            )
          )
      )

    val messages = request.toSpringAiPrompt(null).instructions

    assertThat(messages).hasSize(2)
    assertThat(messages[0]).isInstanceOf(ToolResponseMessage::class.java)
    assertThat(messages[1]).isInstanceOf(UserMessage::class.java)
    assertThat((messages[1] as UserMessage).text).isEqualTo("thanks")
  }

  @Test
  fun toolCall_malformedArgumentsJson_throwsWithoutLeakingArguments() {
    val toolCall = AssistantMessage.ToolCall("call-1", "function", "getWeather", "{\"city\"")
    val assistant = AssistantMessage.builder().content("").toolCalls(listOf(toolCall)).build()

    val error = runCatching { ChatResponse(listOf(Generation(assistant))).toLlmResponse() }

    assertThat(error.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
    // Hygiene: the raw arguments must not appear in the surfaced error.
    assertThat(error.exceptionOrNull()).hasMessageThat().doesNotContain("city")
  }

  @Test
  fun toolCall_nullArgumentsLiteral_yieldsEmptyArgs() {
    val toolCall = AssistantMessage.ToolCall("call-1", "function", "noArgs", "null")
    val assistant = AssistantMessage.builder().content("").toolCalls(listOf(toolCall)).build()

    val functionCall =
      ChatResponse(listOf(Generation(assistant)))
        .toLlmResponse()
        .content
        ?.parts
        ?.single()
        ?.functionCall

    assertThat(functionCall?.name).isEqualTo("noArgs")
    assertThat(functionCall?.args).isEmpty()
  }

  @Test
  fun request_userInlineData_unparseableMime_isSkipped() {
    val request =
      LlmRequest(
        contents =
          listOf(
            Content(
              role = Role.USER,
              parts =
                listOf(
                  Part(text = "hi"),
                  Part(inlineData = Blob(mimeType = "not a mime", data = byteArrayOf(1))),
                ),
            )
          )
      )

    val user = request.toSpringAiPrompt(null).instructions.single() as UserMessage

    assertThat(user.media).isEmpty()
    assertThat(user.text).isEqualTo("hi")
  }

  @Test
  fun request_userFileData_becomesUserMessageMedia() {
    val request =
      LlmRequest(
        contents =
          listOf(
            Content(
              role = Role.USER,
              parts =
                listOf(
                  Part(fileData = FileData(mimeType = "image/png", fileUri = "gs://b/img.png"))
                ),
            )
          )
      )

    val user = request.toSpringAiPrompt(null).instructions.single() as UserMessage

    assertThat(user.media).hasSize(1)
    assertThat(user.media.single().mimeType.toString()).isEqualTo("image/png")
  }

  @Test
  fun request_systemRoleContent_mergesIntoSystemMessage() {
    val request =
      LlmRequest(
        config =
          GenerateContentConfig(systemInstruction = Content(parts = listOf(Part(text = "Base")))),
        contents =
          listOf(
            Content(role = Role.SYSTEM, parts = listOf(Part(text = "More rules"))),
            Content(role = Role.USER, parts = listOf(Part(text = "Hi"))),
          ),
      )

    val messages = request.toSpringAiPrompt(null).instructions

    assertThat(messages[0]).isInstanceOf(SystemMessage::class.java)
    assertThat((messages[0] as SystemMessage).text).isEqualTo("Base\n\nMore rules")
  }

  @Test
  fun response_modelMedia_mapsToInlineDataPart() {
    val media = Media(MimeType.valueOf("image/png"), ByteArrayResource(byteArrayOf(1, 2, 3)))
    val assistant = AssistantMessage.builder().content("").media(listOf(media)).build()

    val parts = ChatResponse(listOf(Generation(assistant))).toLlmResponse().content?.parts
    val blob = parts?.singleOrNull { it.inlineData != null }?.inlineData

    assertThat(blob?.mimeType).isEqualTo("image/png")
    assertThat(blob?.data?.toList()).isEqualTo(byteArrayOf(1, 2, 3).toList())
  }

  @Test
  fun response_modelMediaUri_mapsToFileDataPart() {
    // Spring stores a URI-constructed medium's data as its String form, so it must map to fileData.
    val media =
      Media.builder()
        .mimeType(MimeType.valueOf("image/png"))
        .data(URI.create("gs://b/x.png"))
        .build()
    val assistant = AssistantMessage.builder().content("").media(listOf(media)).build()

    val part =
      ChatResponse(listOf(Generation(assistant))).toLlmResponse().content?.parts?.singleOrNull {
        it.fileData != null
      }

    assertThat(part?.fileData?.fileUri).isEqualTo("gs://b/x.png")
    assertThat(part?.fileData?.mimeType).isEqualTo("image/png")
  }

  @Test
  fun request_modelInlineData_replayedAsAssistantMedia() {
    val request =
      LlmRequest(
        contents =
          listOf(
            Content(
              role = Role.MODEL,
              parts =
                listOf(
                  Part(text = "here"),
                  Part(inlineData = Blob(mimeType = "image/png", data = byteArrayOf(9))),
                ),
            )
          )
      )

    val assistant = request.toSpringAiPrompt(null).instructions.single() as AssistantMessage

    assertThat(assistant.text).isEqualTo("here")
    assertThat(assistant.media).hasSize(1)
    assertThat(assistant.media.single().mimeType.toString()).isEqualTo("image/png")
  }
}
