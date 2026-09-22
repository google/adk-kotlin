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
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FinishReason
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.Prompt
import reactor.core.publisher.Flux

class SpringAiModelTest {

  @Test
  fun generateContent_nonStreaming_emitsModelContentWithStop() =
    runBlocking<Unit> {
      val chatModel = mock<ChatModel>()
      whenever(chatModel.call(any<Prompt>()))
        .thenReturn(ChatResponse(listOf(Generation(AssistantMessage("Hi!")))))
      val llm = SpringAiModel("test-model", chatModel)
      val request =
        LlmRequest(contents = listOf(Content(role = Role.USER, parts = listOf(Part(text = "Hi")))))

      val responses = llm.generateContent(request, stream = false).toList()

      assertThat(responses).hasSize(1)
      assertThat(responses.single().content?.text()).isEqualTo("Hi!")
      assertThat(responses.single().finishReason).isEqualTo(FinishReason.STOP)
    }

  @Test
  fun generateContent_streaming_aggregatesChunksWithStop() =
    runBlocking<Unit> {
      val chatModel = mock<ChatModel>()
      whenever(chatModel.stream(any<Prompt>()))
        .thenReturn(
          Flux.just(
            ChatResponse(listOf(Generation(AssistantMessage("Hel")))),
            ChatResponse(listOf(Generation(AssistantMessage("lo")))),
          )
        )
      val llm = SpringAiModel("test-model", chatModel)
      val request =
        LlmRequest(contents = listOf(Content(role = Role.USER, parts = listOf(Part(text = "Hi")))))

      val responses = llm.generateContent(request, stream = true).toList()

      // The Flux chunks are bridged to a Flow, aggregated into one final non-partial response, and
      // the missing finish reason is backfilled to STOP.
      val last = responses.last()
      assertThat(last.content?.text()).isEqualTo("Hello")
      assertThat(last.finishReason).isEqualTo(FinishReason.STOP)
      assertThat(last.partial).isFalse()
    }

  @Test
  fun name_isReportedAsProvided() {
    val llm = SpringAiModel("my-model", mock<ChatModel>())

    assertThat(llm.name).isEqualTo("my-model")
  }

  @Test
  fun generateContent_runsBlockingCallOnInjectedContext() =
    runBlocking<Unit> {
      val dispatcher =
        Executors.newSingleThreadExecutor { Thread(it, "spring-ai-test-io") }
          .asCoroutineDispatcher()
      var callThreadName: String? = null
      val chatModel = mock<ChatModel>()
      whenever(chatModel.call(any<Prompt>())).thenAnswer {
        callThreadName = Thread.currentThread().name
        ChatResponse(listOf(Generation(AssistantMessage("Hi!"))))
      }
      val llm = SpringAiModel("test-model", chatModel, dispatcher)
      val request =
        LlmRequest(contents = listOf(Content(role = Role.USER, parts = listOf(Part(text = "Hi")))))

      try {
        llm.generateContent(request, stream = false).toList()
      } finally {
        dispatcher.close()
      }

      // The injected context, not the hardcoded Dispatchers.IO, runs the blocking Spring AI call.
      // The coroutine debug agent may append " @coroutine#N", so match the thread name prefix.
      assertThat(callThreadName).startsWith("spring-ai-test-io")
    }
}
