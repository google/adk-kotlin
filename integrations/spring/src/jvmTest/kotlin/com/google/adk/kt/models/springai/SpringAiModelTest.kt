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

import com.anthropic.models.messages.MessageDeltaUsage
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.RunConfig
import com.google.adk.kt.agents.StreamingMode
import com.google.adk.kt.events.Event
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FinishReason
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.common.truth.Truth.assertThat
import com.google.genai.types.GenerateContentResponseUsageMetadata
import java.util.Optional
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.metadata.ChatResponseMetadata
import org.springframework.ai.chat.metadata.DefaultUsage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.google.genai.metadata.GoogleGenAiUsage
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

  @Test
  fun run_nonStreaming_eventCarriesCachedTokensReportedByChatModel() =
    runBlocking<Unit> {
      val chatModel = mock<ChatModel>()
      whenever(chatModel.call(any<Prompt>())).thenReturn(promptCachingResponse())

      val events = runAgent(chatModel)

      val usage = events.single().usageMetadata!!
      assertThat(usage.promptTokenCount).isEqualTo(2006)
      assertThat(usage.cachedContentTokenCount).isEqualTo(1920)
    }

  @Test
  fun run_streaming_finalEventCarriesCachedTokensReportedByChatModel() =
    runBlocking<Unit> {
      val chatModel = mock<ChatModel>()
      whenever(chatModel.stream(any<Prompt>())).thenReturn(Flux.just(promptCachingResponse()))

      val events = runAgent(chatModel, RunConfig(streamingMode = StreamingMode.SSE))

      val finalEvent = events.last()
      assertThat(finalEvent.partial).isFalse()
      val usage = finalEvent.usageMetadata!!
      assertThat(usage.promptTokenCount).isEqualTo(2006)
      assertThat(usage.cachedContentTokenCount).isEqualTo(1920)
    }

  @Test
  fun run_streaming_anthropicUsage_finalEventFoldsCacheTokensIntoPrompt() =
    runBlocking<Unit> {
      // Spring AI's Anthropic provider streams text first, then a terminal chunk with the usage.
      val nativeUsage =
        MessageDeltaUsage.builder()
          .inputTokens(Optional.empty())
          .outputTokens(12)
          .cacheReadInputTokens(1500L)
          .cacheCreationInputTokens(300L)
          .outputTokensDetails(Optional.empty())
          .serverToolUse(Optional.empty())
          .build()
      val usageChunk =
        ChatResponse(
          listOf(Generation(AssistantMessage(""))),
          ChatResponseMetadata.builder()
            .usage(
              DefaultUsage(
                /* promptTokens= */ 20,
                /* completionTokens= */ 12,
                /* totalTokens= */ 32,
                /* nativeUsage= */ nativeUsage,
                /* cacheReadInputTokens= */ 1500L,
                /* cacheWriteInputTokens= */ 300L,
              )
            )
            .build(),
        )
      val chatModel = mock<ChatModel>()
      whenever(chatModel.stream(any<Prompt>()))
        .thenReturn(
          Flux.just(ChatResponse(listOf(Generation(AssistantMessage("Hi!")))), usageChunk)
        )

      val events = runAgent(chatModel, RunConfig(streamingMode = StreamingMode.SSE))

      val finalEvent = events.last()
      assertThat(finalEvent.partial).isFalse()
      val usage = finalEvent.usageMetadata!!
      assertThat(usage.promptTokenCount).isEqualTo(1820)
      assertThat(usage.candidatesTokenCount).isEqualTo(12)
      assertThat(usage.totalTokenCount).isEqualTo(1832)
      assertThat(usage.cachedContentTokenCount).isEqualTo(1500)
    }

  /** Runs one user turn through an agent backed by [chatModel] and returns its events. */
  private suspend fun runAgent(chatModel: ChatModel, runConfig: RunConfig? = null): List<Event> {
    val agent = LlmAgent(name = "agent", model = SpringAiModel("test-model", chatModel))
    return InMemoryRunner(agent).use { runner ->
      runner
        .runAsync(
          userId = "user",
          sessionId = "session",
          newMessage = Content.fromText(Role.USER, "Hi"),
          runConfig = runConfig,
        )
        .toList()
    }
  }

  /**
   * A response with usage as Spring AI's Google GenAI provider reports it: 1920 of its 2006 prompt
   * tokens were read from cache.
   */
  private fun promptCachingResponse() =
    ChatResponse(
      listOf(Generation(AssistantMessage("Hi!"))),
      ChatResponseMetadata.builder()
        .usage(
          GoogleGenAiUsage.from(
            GenerateContentResponseUsageMetadata.builder()
              .promptTokenCount(2006)
              .candidatesTokenCount(300)
              .totalTokenCount(2306)
              .cachedContentTokenCount(1920)
              .build()
          )
        )
        .build(),
    )
}
