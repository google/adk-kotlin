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
import com.google.adk.kt.types.FinishReason
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.GenerateContentConfig
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.common.truth.Truth.assertThat
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
  fun response_textGeneration_mapsToModelContent() {
    val response = ChatResponse(listOf(Generation(AssistantMessage("Hello there"))))

    val llmResponse = response.toLlmResponse()

    assertThat(llmResponse.content?.role).isEqualTo(Role.MODEL)
    assertThat(llmResponse.content?.text()).isEqualTo("Hello there")
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
}
