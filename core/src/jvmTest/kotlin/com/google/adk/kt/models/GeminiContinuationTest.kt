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

import com.google.adk.kt.models.Continuation.Companion.MAX_RESUMES
import com.google.adk.kt.types.Candidate
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FinishReason
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.GenerateContentConfig
import com.google.adk.kt.types.GenerateContentResponse
import com.google.adk.kt.types.MediaModality
import com.google.adk.kt.types.ModalityTokenCount
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.UsageMetadata
import com.google.common.truth.Truth.assertThat
import com.google.genai.kotlin.Client
import com.google.genai.kotlin.ClientException
import com.google.genai.kotlin.GenAiApiException
import com.google.genai.kotlin.types.HttpOptions as GenAiHttpOptions
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers

/**
 * Tests that [Gemini] resumes generations the model pauses. A fake [Gemini.GeminiModels] drives the
 * resume loop, and a local HTTP server checks how the GenAI SDK carries the continuation token.
 */
class GeminiContinuationTest {

  private val models = FakeGeminiModels()

  @Test
  fun generateContent_pausedGeneration_resumesAndReturnsWholeGeneration(): Unit = runBlocking {
    models.respond(
      paused(
        "\u0000state",
        Part(text = "The answer is"),
        usage =
          UsageMetadata(
            promptTokenCount = 10,
            candidatesTokenCount = 5,
            totalTokenCount = 15,
            thoughtsTokenCount = 2,
            toolUsePromptTokenCount = 1,
            cachedContentTokenCount = 4,
            promptTokensDetails =
              listOf(tokens(MediaModality.TEXT, 8), tokens(MediaModality.IMAGE, 2)),
            candidatesTokensDetails = listOf(tokens(MediaModality.TEXT, 5)),
            toolUsePromptTokensDetails = listOf(tokens(modality = null, 1)),
          ),
      ),
      finished(
        Part(text = " 42."),
        usage =
          UsageMetadata(
            promptTokenCount = 15,
            candidatesTokenCount = 3,
            totalTokenCount = 18,
            thoughtsTokenCount = 3,
            toolUsePromptTokenCount = 2,
            promptTokensDetails =
              listOf(tokens(MediaModality.IMAGE, 1), tokens(MediaModality.TEXT, 14)),
            candidatesTokensDetails = listOf(tokens(MediaModality.TEXT, 3)),
            toolUsePromptTokensDetails = listOf(tokens(modality = null, 2)),
          ),
      ),
    )

    val response = generate(stream = false).single()

    assertThat(response.content).isEqualTo(Content.fromText(Role.MODEL, "The answer is 42."))
    assertThat(response.finishReason).isEqualTo(FinishReason.STOP)
    assertThat(response.usageMetadata)
      .isEqualTo(
        UsageMetadata(
          promptTokenCount = 25,
          candidatesTokenCount = 8,
          totalTokenCount = 33,
          thoughtsTokenCount = 5,
          toolUsePromptTokenCount = 3,
          cachedContentTokenCount = 4,
          promptTokensDetails =
            listOf(tokens(MediaModality.TEXT, 22), tokens(MediaModality.IMAGE, 3)),
          candidatesTokensDetails = listOf(tokens(MediaModality.TEXT, 8)),
          toolUsePromptTokensDetails = listOf(tokens(modality = null, 3)),
        )
      )
    assertThat(models.requests).hasSize(2)
    val first = models.requests[0]
    val resumed = models.requests[1]
    assertThat(first.token).isNull()
    assertThat(resumed.contents)
      .containsExactly(QUESTION, Content.fromText(Role.MODEL, "The answer is"))
      .inOrder()
    assertThat(resumed.token).isEqualTo("\u0000state".encodeToByteArray())
  }

  @Test
  fun generateContent_streamPausedGeneration_resumesInOneAggregatedStream(): Unit = runBlocking {
    models.respondStreams(
      listOf(
        chunk(Part(text = "The answer")),
        chunk(Part(text = " is")),
        paused("state", usage = UsageMetadata(candidatesTokenCount = 5)),
      ),
      listOf(finished(Part(text = " 42."), usage = UsageMetadata(candidatesTokenCount = 3))),
    )

    val responses = generate(stream = true)

    assertThat(responses.map { it.partial })
      .containsExactly(true, true, true, true, false)
      .inOrder()
    assertThat(responses.mapNotNull { it.errorCode }).isEmpty()
    val last = responses.last()
    assertThat(last.content?.parts?.single()?.text).isEqualTo("The answer is 42.")
    assertThat(last.finishReason).isEqualTo(FinishReason.STOP)
    assertThat(last.usageMetadata?.candidatesTokenCount).isEqualTo(8)
    assertThat(models.requests).hasSize(2)
    assertThat(models.requests[1].contents.last())
      .isEqualTo(Content.fromText(Role.MODEL, "The answer is"))
    assertThat(models.requests[1].token).isEqualTo("state".encodeToByteArray())
  }

  @Test
  fun generateContent_pausedTwice_resumesUntilComplete(): Unit = runBlocking {
    models.respond(
      paused("first", Part(text = "a")),
      paused("second", Part(text = "b")),
      finished(Part(text = "c")),
    )

    val response = generate(stream = false).single()

    assertThat(response.content?.parts?.single()?.text).isEqualTo("abc")
    assertThat(models.requests).hasSize(3)
    val secondResume = models.requests[2]
    assertThat(secondResume.contents)
      .containsExactly(QUESTION, Content.fromText(Role.MODEL, "ab"))
      .inOrder()
    assertThat(secondResume.token).isEqualTo("second".encodeToByteArray())
  }

  @Test
  fun generateContent_streamPausedTwice_resumesUntilComplete(): Unit = runBlocking {
    models.respondStreams(
      listOf(paused("first", Part(text = "a"))),
      listOf(paused("second", Part(text = "b"))),
      listOf(finished(Part(text = "c"))),
    )

    val responses = generate(stream = true)

    assertThat(responses.last().content?.parts?.single()?.text).isEqualTo("abc")
    assertThat(models.requests).hasSize(3)
    val secondResume = models.requests[2]
    assertThat(secondResume.contents)
      .containsExactly(QUESTION, Content.fromText(Role.MODEL, "ab"))
      .inOrder()
    assertThat(secondResume.token).isEqualTo("second".encodeToByteArray())
  }

  @Test
  fun generateContent_streamPauseWithOnlyStreamTerminator_resumesWithOriginalContents(): Unit =
    runBlocking {
      models.respondStreams(
        listOf(paused("state", Part(text = ""))),
        listOf(finished(Part(text = "Done."))),
      )

      val responses = generate(stream = true)

      assertThat(responses.last().content?.parts?.single()?.text).isEqualTo("Done.")
      assertThat(models.requests).hasSize(2)
      assertThat(models.requests[1].contents).containsExactly(QUESTION)
    }

  @Test
  fun generateContent_pauseWithoutOutput_resumesWithOriginalContents(): Unit = runBlocking {
    models.respond(paused("state"), finished(Part(text = "Done.")))

    val response = generate(stream = false).single()

    assertThat(response.content?.parts?.single()?.text).isEqualTo("Done.")
    assertThat(models.requests).hasSize(2)
    assertThat(models.requests[1].contents).containsExactly(QUESTION)
  }

  @Test
  fun generateContent_resumed_keepsRequestConfig(): Unit = runBlocking {
    models.respond(paused("state", Part(text = "a")), finished(Part(text = "b")))

    val unused = generate(stream = false)

    assertThat(models.requests.map { it.config })
      .containsExactly(CONFIG, CONFIG.copy(continuationToken = "state".encodeToByteArray()))
      .inOrder()
  }

  @Test
  fun generateContent_configWithToken_sendsItFirstAndReplacesItOnResume(): Unit = runBlocking {
    models.respond(paused("next", Part(text = "a")), finished(Part(text = "b")))

    val unused =
      generate(
        stream = false,
        config = CONFIG.copy(continuationToken = "caller".encodeToByteArray()),
      )

    assertThat(models.requests.map { it.token?.decodeToString() })
      .containsExactly("caller", "next")
      .inOrder()
  }

  @Test
  fun generateContent_resumed_joinsSignedTextKeepingFirstSignature(): Unit = runBlocking {
    val first = "first".encodeToByteArray()
    models.respond(
      paused("state", Part(text = "The answer is", thoughtSignature = first)),
      finished(Part(text = " 42.", thoughtSignature = "second".encodeToByteArray())),
    )

    val response = generate(stream = false).single()

    assertThat(response.content?.parts)
      .containsExactly(Part(text = "The answer is 42.", thoughtSignature = first))
  }

  @Test
  fun generateContent_resumed_keepsThoughtApartFromAnswer(): Unit = runBlocking {
    val thought = Part(text = "Thinking.", thought = true)
    models.respond(paused("state", thought), finished(Part(text = "The answer is 42.")))

    val response = generate(stream = false).single()

    assertThat(response.content?.parts)
      .containsExactly(thought, Part(text = "The answer is 42."))
      .inOrder()
    assertThat(models.requests).hasSize(2)
    assertThat(models.requests[1].contents.last())
      .isEqualTo(Content(role = Role.MODEL, parts = listOf(thought)))
  }

  @Test
  fun generateContent_resumed_keepsFunctionCallApartFromText(): Unit = runBlocking {
    val call = Part(functionCall = FunctionCall(name = "lookup", args = mapOf("query" to "answer")))
    models.respond(
      paused("state", Part(text = "Let me check."), call),
      finished(Part(text = "42.")),
    )

    val response = generate(stream = false).single()

    assertThat(response.content?.parts)
      .containsExactly(Part(text = "Let me check."), call, Part(text = "42."))
      .inOrder()
    assertThat(models.requests).hasSize(2)
    assertThat(models.requests[1].contents.last())
      .isEqualTo(Content(role = Role.MODEL, parts = listOf(Part(text = "Let me check."), call)))
  }

  @Test
  fun generateContent_streamResumed_resendsFunctionCallWithoutClientId(): Unit = runBlocking {
    val call = Part(functionCall = FunctionCall(name = "lookup", args = mapOf("q" to "x")))
    models.respondStreams(
      listOf(chunk(call), paused("state")),
      listOf(finished(Part(text = "Done."))),
    )

    val responses = generate(stream = true)

    val finalCall = responses.last().content?.parts?.firstNotNullOfOrNull { it.functionCall }
    assertThat(finalCall?.id).startsWith("adk-")
    assertThat(models.requests).hasSize(2)
    assertThat(models.requests[1].contents.last())
      .isEqualTo(Content(role = Role.MODEL, parts = listOf(call)))
  }

  @Test
  fun generateContent_resumed_joinsThoughtSplitByPause(): Unit = runBlocking {
    models.respond(
      paused("state", Part(text = "Think", thought = true)),
      finished(Part(text = "ing.", thought = true), Part(text = "42.")),
    )

    val response = generate(stream = false).single()

    assertThat(response.content?.parts)
      .containsExactly(Part(text = "Thinking.", thought = true), Part(text = "42."))
      .inOrder()
  }

  @Test
  fun generateContent_resumed_joinsTextKeepingResumedSignature(): Unit = runBlocking {
    val signature = "sig".encodeToByteArray()
    models.respond(
      paused("state", Part(text = "The answer is")),
      finished(Part(text = " 42.", thoughtSignature = signature)),
    )

    val response = generate(stream = false).single()

    assertThat(response.content?.parts)
      .containsExactly(Part(text = "The answer is 42.", thoughtSignature = signature))
  }

  @Test
  fun generateContent_resumed_keepsEmptyTextApart(): Unit = runBlocking {
    models.respond(paused("state", Part(text = "a"), Part(text = "")), finished(Part(text = "b")))

    val response = generate(stream = false).single()

    assertThat(response.content?.parts)
      .containsExactly(Part(text = "a"), Part(text = ""), Part(text = "b"))
      .inOrder()
  }

  @Test
  fun generateContent_streamRepeatedToken_stopsResuming(): Unit = runBlocking {
    models.respondStreams(
      listOf(paused("state", Part(text = "a"))),
      listOf(paused("state", Part(text = "b"))),
    )

    val responses = generate(stream = true)

    assertThat(models.requests).hasSize(2)
    val last = responses.last()
    assertThat(last.content?.parts?.single()?.text).isEqualTo("ab")
    // The pause that is not resumed still ends the generation.
    assertThat(last.finishReason).isEqualTo(FinishReason.CONTINUATION)
  }

  @Test
  fun generateContent_pausedOnEveryRequest_stopsAfterMaxResumes(): Unit = runBlocking {
    models.respond(*Array(MAX_RESUMES + 1) { paused("token$it", Part(text = "a")) })

    val response = generate(stream = false).single()

    assertThat(models.requests).hasSize(MAX_RESUMES + 1)
    assertThat(response.content?.parts?.single()?.text).isEqualTo("a".repeat(MAX_RESUMES + 1))
    assertThat(response.finishReason).isEqualTo(FinishReason.CONTINUATION)
  }

  @Test
  fun generateContent_streamPausedOnEveryRequest_stopsAfterMaxResumes(): Unit = runBlocking {
    models.respondStreams(*Array(MAX_RESUMES + 1) { listOf(paused("token$it", Part(text = "a"))) })

    val responses = generate(stream = true)

    assertThat(models.requests).hasSize(MAX_RESUMES + 1)
    val last = responses.last()
    assertThat(last.content?.parts?.single()?.text).isEqualTo("a".repeat(MAX_RESUMES + 1))
    assertThat(last.finishReason).isEqualTo(FinishReason.CONTINUATION)
  }

  @Test
  fun generateContent_resumeHitsQuota_throwsResourceExhausted(): Unit = runBlocking {
    val quotaError = ClientException(429, "RESOURCE_EXHAUSTED", "Quota exceeded for model")
    models.respond(paused("state", Part(text = "a")))
    models.failWhenResponsesRunOut(quotaError)

    val thrown = assertFailsWith<GenAiApiException> { generate(stream = false) }

    assertThat(models.requests).hasSize(2)
    assertThat(thrown).isNotInstanceOf(ClientException::class.java)
    assertThat(thrown).hasCauseThat().isSameInstanceAs(quotaError)
  }

  @Test
  fun generateContent_streamCollectorStopsAfterPause_sendsNoResume(): Unit = runBlocking {
    models.respondStreams(
      listOf(paused("state", Part(text = "a"))),
      listOf(finished(Part(text = "b"))),
    )

    val first =
      Client(apiKey = "fake").use { client ->
        Gemini(client, "gemini-test-model", models)
          .generateContent(LlmRequest(contents = listOf(QUESTION), config = CONFIG), stream = true)
          .take(1)
          .toList()
      }

    assertThat(first).hasSize(1)
    assertThat(models.requests).hasSize(1)
  }

  @Test
  fun generateContent_repeatedToken_stopsResuming(): Unit = runBlocking {
    models.respond(paused("state", Part(text = "a")), paused("state", Part(text = "b")))

    val response = generate(stream = false).single()

    assertThat(models.requests).hasSize(2)
    assertThat(response.content?.parts?.single()?.text).isEqualTo("ab")
  }

  @Test
  fun generateContent_streamCompleteGeneration_sendsOneRequest(): Unit = runBlocking {
    val usage = UsageMetadata(totalTokenCount = 7)
    models.respondStreams(
      listOf(chunk(Part(text = "Hello")), finished(Part(text = " world"), usage = usage))
    )

    val responses = generate(stream = true)

    assertThat(models.requests.single().token).isNull()
    assertThat(responses.last().content?.parts?.single()?.text).isEqualTo("Hello world")
    assertThat(responses.last().usageMetadata).isEqualTo(usage)
  }

  @Test
  fun resumeToken_pausedWithToken_returnsToken() {
    val token = "state".encodeToByteArray()

    assertThat(continuation().resumeToken(response(FinishReason.CONTINUATION, token = token)))
      .isEqualTo(token)
  }

  @Test
  fun resumeToken_pausedWithoutToken_returnsNull() {
    assertThat(continuation().resumeToken(response(FinishReason.CONTINUATION, token = null)))
      .isNull()
    assertThat(
        continuation().resumeToken(response(FinishReason.CONTINUATION, token = ByteArray(0)))
      )
      .isNull()
  }

  @Test
  fun resumeToken_notPaused_ignoresToken() {
    val token = "state".encodeToByteArray()

    assertThat(continuation().resumeToken(response(FinishReason.STOP, token = token))).isNull()
  }

  @Test
  fun resumeToken_noCandidates_returnsNull() {
    assertThat(continuation().resumeToken(GenerateContentResponse())).isNull()
  }

  @Test
  fun resumeToken_maxTokensWithoutOutputLimit_returnsToken() {
    val token = "state".encodeToByteArray()

    assertThat(continuation().resumeToken(response(FinishReason.MAX_TOKENS, token = token)))
      .isEqualTo(token)
  }

  @Test
  fun resumeToken_maxTokensAtOutputLimit_returnsNull() {
    val token = "state".encodeToByteArray()

    assertThat(
        continuation(LIMITED_CONFIG).resumeToken(response(FinishReason.MAX_TOKENS, token = token))
      )
      .isNull()
  }

  @Test
  fun resumeToken_maxTokensWithoutToken_returnsNull() {
    assertThat(continuation().resumeToken(response(FinishReason.MAX_TOKENS, token = null))).isNull()
  }

  @Test
  fun generateContent_maxTokensWithoutOutputLimit_resumesUntilComplete(): Unit = runBlocking {
    models.respond(maxTokens("state", Part(text = "The answer is")), finished(Part(text = " 42.")))

    val response = generate(stream = false).single()

    assertThat(response.content).isEqualTo(Content.fromText(Role.MODEL, "The answer is 42."))
    assertThat(response.finishReason).isEqualTo(FinishReason.STOP)
    assertThat(models.requests).hasSize(2)
    assertThat(models.requests[1].contents)
      .containsExactly(QUESTION, Content.fromText(Role.MODEL, "The answer is"))
      .inOrder()
    assertThat(models.requests[1].token).isEqualTo("state".encodeToByteArray())
  }

  @Test
  fun generateContent_streamMaxTokensWithoutOutputLimit_resumesInOneAggregatedStream(): Unit =
    runBlocking {
      models.respondStreams(
        listOf(chunk(Part(text = "The answer")), maxTokens("state", Part(text = " is"))),
        listOf(finished(Part(text = " 42."))),
      )

      val responses = generate(stream = true)

      // The capped request does not end the generation, so no response reports MAX_TOKENS.
      assertThat(responses.map { it.finishReason }).doesNotContain(FinishReason.MAX_TOKENS)
      assertThat(responses.mapNotNull { it.errorCode }).isEmpty()
      val last = responses.last()
      assertThat(last.content?.parts?.single()?.text).isEqualTo("The answer is 42.")
      assertThat(last.finishReason).isEqualTo(FinishReason.STOP)
      assertThat(models.requests).hasSize(2)
      assertThat(models.requests[1].token).isEqualTo("state".encodeToByteArray())
    }

  @Test
  fun generateContent_maxTokensAtOutputLimit_returnsOutput(): Unit = runBlocking {
    models.respond(maxTokens("state", Part(text = "a")))

    val response = generate(stream = false, config = LIMITED_CONFIG).single()

    assertThat(models.requests).hasSize(1)
    assertThat(response.content?.parts?.single()?.text).isEqualTo("a")
    assertThat(response.finishReason).isEqualTo(FinishReason.MAX_TOKENS)
  }

  @Test
  fun generateContent_streamMaxTokensAtOutputLimit_endsWithMaxTokens(): Unit = runBlocking {
    models.respondStreams(listOf(maxTokens("state", Part(text = "a"))))

    val responses = generate(stream = true, config = LIMITED_CONFIG)

    assertThat(models.requests).hasSize(1)
    assertThat(responses.last().content?.parts?.single()?.text).isEqualTo("a")
    assertThat(responses.last().finishReason).isEqualTo(FinishReason.MAX_TOKENS)
  }

  @Test
  fun generateContent_pausedThenMaxTokensAtOutputLimit_returnsWholeOutput(): Unit = runBlocking {
    models.respond(paused("first", Part(text = "a")), maxTokens("second", Part(text = "b")))

    val response = generate(stream = false, config = LIMITED_CONFIG).single()

    assertThat(models.requests).hasSize(2)
    assertThat(response.content?.parts?.single()?.text).isEqualTo("ab")
    assertThat(response.finishReason).isEqualTo(FinishReason.MAX_TOKENS)
  }

  @Test
  fun generateContent_maxTokensWithoutToken_returnsOutput(): Unit = runBlocking {
    models.respond(response(FinishReason.MAX_TOKENS, Part(text = "a")))

    val response = generate(stream = false).single()

    assertThat(models.requests).hasSize(1)
    assertThat(response.content?.parts?.single()?.text).isEqualTo("a")
    assertThat(response.finishReason).isEqualTo(FinishReason.MAX_TOKENS)
  }

  @Test
  fun generateContent_throughSdk_sendsTokenAtTopLevelOfResumedRequest(): Unit = runBlocking {
    MockWebServer().use { server ->
      server.start()
      server.enqueue(json(pausedJson("state", "The answer is")))
      server.enqueue(json(finishedJson(" 42.")))

      val response = generateThroughSdk(server, stream = false).single()

      assertThat(response.content).isEqualTo(Content.fromText(Role.MODEL, "The answer is 42."))
      val first = server.takeRequestJson()
      assertThat(first).doesNotContainKey("continuationToken")
      val resumed = server.takeRequestJson()
      assertThat(resumed["continuationToken"]?.jsonPrimitive?.content).isEqualTo(base64("state"))
      assertThat(
          resumed["generationConfig"]?.jsonObject?.get("temperature")?.jsonPrimitive?.content
        )
        .isEqualTo("0.5")
      assertThat(resumed["generationConfig"]).isEqualTo(first["generationConfig"])
      assertThat(
          resumed["contents"]?.jsonArray?.last()?.jsonObject?.get("role")?.jsonPrimitive?.content
        )
        .isEqualTo("model")
    }
  }

  @Test
  fun generateContent_throughSdkStream_sendsTokenAtTopLevelOfResumedRequest(): Unit = runBlocking {
    MockWebServer().use { server ->
      server.start()
      server.enqueue(sse(chunkJson("The answer"), pausedJson("state", " is")))
      server.enqueue(sse(finishedJson(" 42.")))

      val responses = generateThroughSdk(server, stream = true)

      assertThat(responses.last().content?.parts?.single()?.text).isEqualTo("The answer is 42.")
      assertThat(responses.last().finishReason).isEqualTo(FinishReason.STOP)
      assertThat(server.takeRequestJson()).doesNotContainKey("continuationToken")
      assertThat(server.takeRequestJson()["continuationToken"]?.jsonPrimitive?.content)
        .isEqualTo(base64("state"))
    }
  }

  @Test
  fun generateContent_throughSdk_retriesFailedResend(): Unit = runBlocking {
    MockWebServer().use { server ->
      server.start()
      server.enqueue(json(pausedJson("state", "The answer is")))
      server.enqueue(unavailable())
      server.enqueue(json(finishedJson(" 42.")))

      val response = generateThroughSdk(server, stream = false).single()

      assertThat(server.requestCount).isEqualTo(3)
      assertThat(response.content).isEqualTo(Content.fromText(Role.MODEL, "The answer is 42."))
    }
  }

  @Test
  fun generateContent_throughCallerClient_leavesResendRetriesToTheClient(): Unit = runBlocking {
    MockWebServer().use { server ->
      server.start()
      server.enqueue(json(pausedJson("state", "The answer is")))
      server.enqueue(unavailable())
      val client =
        Client(
          apiKey = "fake-key",
          httpOptions = GenAiHttpOptions(baseUrl = server.url("/").toString()),
        )

      client.use {
        assertFailsWith<GenAiApiException> {
          Gemini(client, "gemini-test-model")
            .generateContent(
              LlmRequest(contents = listOf(QUESTION), config = CONFIG),
              stream = false,
            )
            .toList()
        }
      }
      assertThat(server.requestCount).isEqualTo(2)
    }
  }

  @Test
  fun generateContent_throughSdkPauseWithoutToken_returnsPartialOutput(): Unit = runBlocking {
    MockWebServer().use { server ->
      server.start()
      server.enqueue(json(candidateJson("The answer is", ""","finishReason":"CONTINUATION"""")))

      val response = generateThroughSdk(server, stream = false).single()

      assertThat(server.requestCount).isEqualTo(1)
      assertThat(response.content).isEqualTo(Content.fromText(Role.MODEL, "The answer is"))
      assertThat(response.finishReason).isEqualTo(FinishReason.CONTINUATION)
    }
  }

  @Test
  fun generateContent_throughSdkStreamPauseWithoutToken_returnsPartialOutput(): Unit = runBlocking {
    MockWebServer().use { server ->
      server.start()
      server.enqueue(sse(candidateJson("The answer is", ""","finishReason":"CONTINUATION"""")))

      val responses = generateThroughSdk(server, stream = true)

      assertThat(server.requestCount).isEqualTo(1)
      assertThat(responses.last().content?.parts?.single()?.text).isEqualTo("The answer is")
      assertThat(responses.last().finishReason).isEqualTo(FinishReason.CONTINUATION)
    }
  }

  private suspend fun generate(
    stream: Boolean,
    config: GenerateContentConfig = CONFIG,
  ): List<LlmResponse> =
    Client(apiKey = "fake").use { client ->
      Gemini(client, "gemini-test-model", models)
        .generateContent(LlmRequest(contents = listOf(QUESTION), config = config), stream)
        .toList()
    }

  private suspend fun generateThroughSdk(
    server: MockWebServer,
    stream: Boolean,
  ): List<LlmResponse> {
    val gemini =
      Gemini.withBaseUrl(
        "gemini-test-model",
        apiKey = "fake-key",
        baseUrl = server.url("/").toString(),
      )
    return gemini.client.use {
      gemini
        .generateContent(LlmRequest(contents = listOf(QUESTION), config = CONFIG), stream)
        .toList()
    }
  }
}

private val QUESTION = Content.fromText(Role.USER, "What is the answer?")
private val CONFIG = GenerateContentConfig(temperature = 0.5f)
private val LIMITED_CONFIG = CONFIG.copy(maxOutputTokens = 100)

private fun continuation(config: GenerateContentConfig = CONFIG) =
  Continuation(listOf(QUESTION), config)

private fun response(
  finishReason: FinishReason?,
  vararg parts: Part,
  usage: UsageMetadata? = null,
  token: ByteArray? = null,
): GenerateContentResponse =
  GenerateContentResponse(
    candidates =
      listOf(
        Candidate(
          content = Content(role = Role.MODEL, parts = parts.toList()),
          finishReason = finishReason,
          continuationToken = token,
        )
      ),
    usageMetadata = usage,
  )

/** A response that pauses generation, which [token] resumes. */
private fun paused(token: String, vararg parts: Part, usage: UsageMetadata? = null) =
  response(FinishReason.CONTINUATION, *parts, usage = usage, token = token.encodeToByteArray())

/** A response from a request that reached its output cap, carrying [token]. */
private fun maxTokens(token: String, vararg parts: Part) =
  response(FinishReason.MAX_TOKENS, *parts, token = token.encodeToByteArray())

private fun finished(vararg parts: Part, usage: UsageMetadata? = null) =
  response(FinishReason.STOP, *parts, usage = usage)

private fun chunk(vararg parts: Part) = response(null, *parts)

private fun tokens(modality: MediaModality?, tokenCount: Int) =
  ModalityTokenCount(modality = modality, tokenCount = tokenCount)

private fun base64(token: String): String =
  Base64.getEncoder().encodeToString(token.encodeToByteArray())

private fun candidateJson(text: String, extra: String) =
  """{"candidates":[{"content":{"role":"model","parts":[{"text":"$text"}]}$extra}]}"""

private fun pausedJson(token: String, text: String) =
  candidateJson(text, ""","finishReason":"CONTINUATION","continuationToken":"${base64(token)}"""")

private fun finishedJson(text: String) = candidateJson(text, ""","finishReason":"STOP"""")

private fun chunkJson(text: String) = candidateJson(text, "")

private fun json(body: String) =
  MockResponse(headers = Headers.headersOf("Content-Type", "application/json"), body = body)

/** A transient server error that the SDK retries when retries are configured. */
private fun unavailable() =
  MockResponse(
    code = 503,
    headers = Headers.headersOf("Content-Type", "application/json"),
    body = """{"error":{"code":503,"message":"fake","status":"UNAVAILABLE"}}""",
  )

/** A server-sent event stream with one `data:` event per chunk. */
private fun sse(vararg chunks: String) =
  MockResponse(
    headers = Headers.headersOf("Content-Type", "text/event-stream"),
    body = chunks.joinToString("") { "data: $it\n\n" },
  )

private fun MockWebServer.takeRequestJson(): JsonObject =
  Json.parseToJsonElement(checkNotNull(takeRequest().body?.utf8())).jsonObject

/** A request the fake received. */
private class SentRequest(val contents: List<Content>, val config: GenerateContentConfig) {
  val token: ByteArray?
    get() = config.continuationToken
}

/** Replays canned responses and records each request and the token it resumes with. */
private class FakeGeminiModels : Gemini.GeminiModels {
  val requests = mutableListOf<SentRequest>()
  private val responses = ArrayDeque<GenerateContentResponse>()
  private val streams = ArrayDeque<List<GenerateContentResponse>>()
  private var failure: Throwable? = null

  fun respond(vararg responses: GenerateContentResponse) {
    this.responses.addAll(responses)
  }

  /** Makes the first unstreamed request after the canned responses run out throw [error]. */
  fun failWhenResponsesRunOut(error: Throwable) {
    failure = error
  }

  fun respondStreams(vararg streams: List<GenerateContentResponse>) {
    this.streams.addAll(streams)
  }

  override fun generateContentStream(
    model: String,
    contents: List<Content>,
    config: GenerateContentConfig,
  ): Flow<GenerateContentResponse> {
    requests += SentRequest(contents, config)
    return streams.removeFirst().asFlow()
  }

  override suspend fun generateContent(
    model: String,
    contents: List<Content>,
    config: GenerateContentConfig,
  ): GenerateContentResponse {
    requests += SentRequest(contents, config)
    return responses.removeFirstOrNull() ?: throw checkNotNull(failure)
  }
}
