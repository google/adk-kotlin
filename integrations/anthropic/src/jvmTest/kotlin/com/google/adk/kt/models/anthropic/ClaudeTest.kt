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
package com.google.adk.kt.models.anthropic

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.RateLimitException
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.VertexCredentials
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.CodeExecutionResult
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.ExecutableCode
import com.google.adk.kt.types.FinishReason
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.GenerateContentConfig
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Tool
import com.google.adk.kt.types.Type
import com.google.auth.oauth2.AccessToken
import com.google.auth.oauth2.GoogleCredentials
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.util.Date
import kotlin.test.assertFailsWith
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Drives [Claude] through the real Anthropic SDK client against canned HTTP responses. */
class ClaudeTest {

  @get:Rule val temporaryFolder = TemporaryFolder()

  private lateinit var server: MockWebServer
  private lateinit var client: AnthropicClient
  private lateinit var claude: Claude
  private val vertexClients = mutableListOf<AnthropicClient>()

  @Before
  fun setUp() {
    server = MockWebServer()
    server.start()
    client =
      AnthropicOkHttpClient.builder()
        .apiKey("test-key")
        .baseUrl(server.url("/").toString())
        .maxRetries(0)
        .build()
    claude = Claude("claude-x", client)
  }

  @After
  fun tearDown() {
    client.close()
    vertexClients.forEach { it.close() }
    server.close()
  }

  @Test
  fun generateContent_unary_sendsRequestAndMapsResponse() =
    runBlocking<Unit> {
      server.enqueue(jsonResponse(message(content = """[{"type":"text","text":"Hi there"}]""")))

      val response = claude.generateContent(userRequest("Hi"), stream = false).toList().single()

      assertThat(response.content!!.parts.single().text).isEqualTo("Hi there")
      assertThat(response.finishReason).isEqualTo(FinishReason.STOP)
      assertThat(response.modelVersion).isEqualTo("claude-x")
      assertThat(response.usageMetadata!!.promptTokenCount).isEqualTo(3)
      assertThat(response.usageMetadata!!.candidatesTokenCount).isEqualTo(2)
      assertThat(response.usageMetadata!!.totalTokenCount).isEqualTo(5)
      val recorded = server.takeRequest()
      assertThat(recorded.target).isEqualTo("/v1/messages")
      assertThat(recorded.headers["x-api-key"]).isEqualTo("test-key")
      val body = Json.parseToJsonElement(recorded.body!!.utf8()).jsonObject
      assertThat(body["model"]!!.jsonPrimitive.content).isEqualTo("claude-x")
      assertThat(body["max_tokens"]!!.jsonPrimitive.int).isEqualTo(8192)
    }

  @Test
  fun generateContent_mapsSystemToolsAndConfigOnTheWire() =
    runBlocking<Unit> {
      server.enqueue(jsonResponse(message(content = """[{"type":"text","text":"ok"}]""")))
      val request =
        LlmRequest(
          contents = listOf(userText("Hi")),
          config =
            GenerateContentConfig(
              systemInstruction = Content(parts = listOf(Part(text = "Be brief"))),
              maxOutputTokens = 100,
              temperature = 0.5f,
              tools = listOf(Tool(functionDeclarations = listOf(weatherDeclaration()))),
            ),
        )

      claude.generateContent(request, stream = false).toList()

      val body = recordedBody()
      assertThat(body["system"]!!.jsonPrimitive.content).isEqualTo("Be brief")
      assertThat(body["max_tokens"]!!.jsonPrimitive.int).isEqualTo(100)
      assertThat(body["temperature"]!!.jsonPrimitive.content).isEqualTo("0.5")
      assertThat(body["tool_choice"]!!.jsonObject["type"]!!.jsonPrimitive.content).isEqualTo("auto")
      val tool = body["tools"]!!.jsonArray.single().jsonObject
      assertThat(tool["name"]!!.jsonPrimitive.content).isEqualTo("get_weather")
      val schema = tool["input_schema"]!!.jsonObject
      assertThat(schema["type"]!!.jsonPrimitive.content).isEqualTo("object")
      // JSON Schema type names must be lowercase for Anthropic.
      val city = schema["properties"]!!.jsonObject["city"]!!.jsonObject
      assertThat(city["type"]!!.jsonPrimitive.content).isEqualTo("string")
      assertThat(schema["required"]!!.jsonArray.map { it.jsonPrimitive.content })
        .containsExactly("city")
      // Keys beyond type/properties/required pass through unchanged.
      assertThat(schema["description"]!!.jsonPrimitive.content)
        .isEqualTo("Weather lookup arguments")
    }

  @Test
  fun generateContent_mapsToolHistoryAndDropsThoughtsAndEmptyTurns() =
    runBlocking<Unit> {
      server.enqueue(jsonResponse(message(content = """[{"type":"text","text":"Sunny"}]""")))
      val call = FunctionCall(name = "get_weather", args = mapOf("city" to "Paris"), id = "call-1")
      val request =
        LlmRequest(
          contents =
            listOf(
              userText("Weather in Paris?"),
              Content(
                role = Role.MODEL,
                parts = listOf(Part(text = "thinking", thought = true), Part(functionCall = call)),
              ),
              Content(
                role = Role.USER,
                parts =
                  listOf(
                    Part(
                      functionResponse =
                        FunctionResponse(
                          name = "get_weather",
                          response = mapOf("result" to "sunny"),
                          id = "call-1",
                        )
                    )
                  ),
              ),
              Content(role = Role.USER, parts = listOf(Part(text = ""))),
            )
        )

      claude.generateContent(request, stream = false).toList()

      val messages = recordedBody()["messages"]!!.jsonArray.map { it.jsonObject }
      assertThat(messages.map { it["role"]!!.jsonPrimitive.content })
        .containsExactly("user", "assistant", "user")
        .inOrder()
      val toolUse = messages[1]["content"]!!.jsonArray.single().jsonObject
      assertThat(toolUse["type"]!!.jsonPrimitive.content).isEqualTo("tool_use")
      assertThat(toolUse["id"]!!.jsonPrimitive.content).isEqualTo("call-1")
      assertThat(toolUse["input"]!!.jsonObject["city"]!!.jsonPrimitive.content).isEqualTo("Paris")
      val toolResult = messages[2]["content"]!!.jsonArray.single().jsonObject
      assertThat(toolResult["type"]!!.jsonPrimitive.content).isEqualTo("tool_result")
      assertThat(toolResult["tool_use_id"]!!.jsonPrimitive.content).isEqualTo("call-1")
      // ADK's {"result": value} wrapper is unwrapped for Anthropic.
      assertThat(toolResult["content"]!!.jsonPrimitive.content).isEqualTo("sunny")
    }

  @Test
  fun generateContent_unary_mapsToolUseResponse() =
    runBlocking<Unit> {
      server.enqueue(
        jsonResponse(
          message(
            content =
              """[{"type":"tool_use","id":"tu_1","name":"get_weather","input":{"city":"Paris"}}]""",
            stopReason = "tool_use",
          )
        )
      )

      val response =
        claude.generateContent(userRequest("Weather?"), stream = false).toList().single()

      val call = response.content!!.parts.single().functionCall!!
      assertThat(call.name).isEqualTo("get_weather")
      assertThat(call.id).isEqualTo("tu_1")
      assertThat(call.args).containsExactly("city", "Paris")
      assertThat(response.finishReason).isEqualTo(FinishReason.STOP)
    }

  @Test
  fun generateContent_unary_toleratesLiteralNullToolInput() =
    runBlocking<Unit> {
      server.enqueue(
        jsonResponse(
          message(
            content = """[{"type":"tool_use","id":"tu_1","name":"ping","input":null}]""",
            stopReason = "tool_use",
          )
        )
      )

      val response = claude.generateContent(userRequest("Ping"), stream = false).toList().single()

      assertThat(response.content!!.parts.single().functionCall!!.args).isEmpty()
    }

  @Test
  fun generateContent_streaming_aggregatesTextAndToolCall() =
    runBlocking<Unit> {
      server.enqueue(
        MockResponse(
          headers = Headers.headersOf("content-type", "text/event-stream"),
          body =
            sse(
              "message_start" to
                """{"type":"message_start","message":${message(content = "[]", stopReason = null, outputTokens = 1)}}""",
              "content_block_start" to
                """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
              "content_block_delta" to
                """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hel"}}""",
              "content_block_delta" to
                """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"lo"}}""",
              "content_block_stop" to """{"type":"content_block_stop","index":0}""",
              "content_block_start" to
                """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"tu_9","name":"get_weather","input":{}}}""",
              "content_block_delta" to
                """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"city\":"}}""",
              "content_block_delta" to
                """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"\"Paris\"}"}}""",
              "content_block_stop" to """{"type":"content_block_stop","index":1}""",
              "message_delta" to
                """{"type":"message_delta","delta":{"stop_reason":"tool_use","stop_sequence":null},"usage":{"output_tokens":4}}""",
              "message_stop" to """{"type":"message_stop"}""",
            ),
        )
      )

      val responses = claude.generateContent(userRequest("Weather?"), stream = true).toList()

      assertThat(responses.first().partial).isTrue()
      assertThat(responses.first().content!!.parts.single().text).isEqualTo("Hel")
      val final = responses.last()
      assertThat(final.partial).isFalse()
      // Exactly one complete response, so the tool call runs once.
      assertThat(responses.count { !it.partial }).isEqualTo(1)
      assertThat(final.content!!.parts.mapNotNull { it.text }).containsExactly("Hello")
      val call = final.content!!.parts.mapNotNull { it.functionCall }.single()
      assertThat(call.name).isEqualTo("get_weather")
      assertThat(call.id).isEqualTo("tu_9")
      assertThat(call.args).containsExactly("city", "Paris")
      assertThat(final.finishReason).isEqualTo(FinishReason.STOP)
      assertThat(final.usageMetadata!!.promptTokenCount).isEqualTo(3)
      assertThat(final.usageMetadata!!.candidatesTokenCount).isEqualTo(4)
      assertThat(recordedBody()["stream"]!!.jsonPrimitive.content).isEqualTo("true")
    }

  @Test
  fun generateContent_propagatesRateLimitError() {
    server.enqueue(rateLimitResponse())

    assertFailsWith<RateLimitException> {
      runBlocking { claude.generateContent(userRequest("Hi"), stream = false).toList() }
    }
  }

  @Test
  fun generateContent_streaming_propagatesTypedRateLimitError() {
    server.enqueue(rateLimitResponse())

    assertFailsWith<RateLimitException> {
      runBlocking { claude.generateContent(userRequest("Hi"), stream = true).toList() }
    }
  }

  @Test
  fun generateContent_pairsToolIdsMissingFromHistory() =
    runBlocking<Unit> {
      server.enqueue(jsonResponse(message(content = """[{"type":"text","text":"ok"}]""")))
      // Calls from another model may lack ids; invalid ones must be replaced consistently.
      val request =
        LlmRequest(
          contents =
            listOf(
              userText("Weather?"),
              Content(
                role = "assistant",
                parts =
                  listOf(
                    Part(functionCall = FunctionCall(name = "get_weather", args = emptyMap())),
                    Part(
                      functionCall = FunctionCall(name = "get_time", args = emptyMap(), id = "a.b")
                    ),
                  ),
              ),
              Content(
                role = Role.USER,
                parts =
                  listOf(
                    Part(
                      functionResponse =
                        FunctionResponse(name = "get_time", response = mapOf(), id = "a.b")
                    ),
                    Part(
                      functionResponse = FunctionResponse(name = "get_weather", response = mapOf())
                    ),
                  ),
              ),
            )
        )

      claude.generateContent(request, stream = false).toList()

      val messages = recordedBody()["messages"]!!.jsonArray.map { it.jsonObject }
      assertThat(messages[1]["role"]!!.jsonPrimitive.content).isEqualTo("assistant")
      val callIds =
        messages[1]["content"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
      val resultIds =
        messages[2]["content"]!!.jsonArray.map {
          it.jsonObject["tool_use_id"]!!.jsonPrimitive.content
        }
      assertThat(callIds).containsExactly("toolu_fallback_0", "toolu_fallback_1").inOrder()
      assertThat(resultIds).containsExactly("toolu_fallback_1", "toolu_fallback_0").inOrder()
    }

  @Test
  fun generateContent_pairsIdlessResultsByNameAndTreatsEmptyIdsAsMissing() =
    runBlocking<Unit> {
      server.enqueue(jsonResponse(message(content = """[{"type":"text","text":"ok"}]""")))
      val request =
        LlmRequest(
          contents =
            listOf(
              userText("Weather and time?"),
              Content(
                role = Role.MODEL,
                parts =
                  listOf(
                    Part(
                      functionCall = FunctionCall(name = "get_weather", args = emptyMap(), id = "")
                    ),
                    Part(functionCall = FunctionCall(name = "get_time", args = emptyMap(), id = "")),
                  ),
              ),
              Content(
                role = Role.USER,
                parts =
                  listOf(
                    Part(
                      functionResponse = FunctionResponse(name = "get_time", response = mapOf())
                    ),
                    Part(
                      functionResponse = FunctionResponse(name = "get_weather", response = mapOf())
                    ),
                  ),
              ),
            )
        )

      claude.generateContent(request, stream = false).toList()

      val messages = recordedBody()["messages"]!!.jsonArray.map { it.jsonObject }
      val callIds =
        messages[1]["content"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
      val resultIds =
        messages[2]["content"]!!.jsonArray.map {
          it.jsonObject["tool_use_id"]!!.jsonPrimitive.content
        }
      // Empty ids get their own fallbacks, and results pair by name even out of order.
      assertThat(callIds).containsExactly("toolu_fallback_0", "toolu_fallback_1").inOrder()
      assertThat(resultIds).containsExactly("toolu_fallback_1", "toolu_fallback_0").inOrder()
    }

  @Test
  fun generateContent_foldsCacheTokensIntoPromptCount() =
    runBlocking<Unit> {
      server.enqueue(
        jsonResponse(
          message(content = """[{"type":"text","text":"ok"}]""")
            .replace(
              """"input_tokens":3""",
              """"input_tokens":3,"cache_read_input_tokens":10,"cache_creation_input_tokens":5""",
            )
        )
      )

      val usage =
        claude.generateContent(userRequest("Hi"), stream = false).toList().single().usageMetadata!!

      assertThat(usage.promptTokenCount).isEqualTo(18)
      assertThat(usage.cachedContentTokenCount).isEqualTo(10)
      assertThat(usage.totalTokenCount).isEqualTo(20)
    }

  @Test
  fun apiKeyConstructor_sendsKeyInApiKeyHeader() =
    runBlocking<Unit> {
      server.enqueue(jsonResponse(message(content = """[{"type":"text","text":"ok"}]""")))
      // The SDK reads this property before ANTHROPIC_BASE_URL, so the client targets the server.
      System.setProperty("anthropic.baseUrl", server.url("/").toString())
      try {
        Claude("claude-x", apiKey = "explicit-key")
          .generateContent(userRequest("Hi"), stream = false)
          .toList()
      } finally {
        System.clearProperty("anthropic.baseUrl")
      }

      assertThat(server.takeRequest().headers["x-api-key"]).isEqualTo("explicit-key")
    }

  @Test
  fun generateContent_dropsThoughtSignatureWithoutContent() =
    runBlocking<Unit> {
      server.enqueue(jsonResponse(message(content = """[{"type":"text","text":"ok"}]""")))
      val request =
        LlmRequest(
          contents =
            listOf(
              userText("Hi"),
              Content(
                role = Role.MODEL,
                parts = listOf(Part(thoughtSignature = byteArrayOf(1, 2)), Part(text = "Hello")),
              ),
            )
        )

      claude.generateContent(request, stream = false).toList()

      val modelTurn = recordedBody()["messages"]!!.jsonArray[1].jsonObject
      assertThat(
          modelTurn["content"]!!.jsonArray.map { it.jsonObject["text"]!!.jsonPrimitive.content }
        )
        .containsExactly("Hello")
    }

  @Test
  fun generateContent_sendsCodeExecutionPartsAsText() =
    runBlocking<Unit> {
      server.enqueue(jsonResponse(message(content = """[{"type":"text","text":"ok"}]""")))
      val request =
        LlmRequest(
          contents =
            listOf(
              userText("Run it"),
              Content(
                role = Role.MODEL,
                parts =
                  listOf(
                    Part(executableCode = ExecutableCode(code = "print(1)")),
                    Part(codeExecutionResult = CodeExecutionResult(output = "1")),
                  ),
              ),
            )
        )

      claude.generateContent(request, stream = false).toList()

      val modelTurn = recordedBody()["messages"]!!.jsonArray[1].jsonObject
      assertThat(
          modelTurn["content"]!!.jsonArray.map { it.jsonObject["text"]!!.jsonPrimitive.content }
        )
        .containsExactly("Code:```python\nprint(1)\n```", "Execution Result:```code_output\n1\n```")
        .inOrder()
    }

  @Test
  fun generateContent_rejectsUnsupportedPart() {
    val audio = Part(inlineData = Blob(mimeType = "audio/wav", data = byteArrayOf(1, 2, 3)))
    val request = LlmRequest(contents = listOf(Content(role = Role.USER, parts = listOf(audio))))

    assertFailsWith<IllegalArgumentException> {
      runBlocking { claude.generateContent(request, stream = false).toList() }
    }
  }

  @Test
  fun generateContent_dropsImagesAndPdfsFromModelTurns() =
    runBlocking<Unit> {
      server.enqueue(jsonResponse(message(content = """[{"type":"text","text":"ok"}]""")))
      val image = Part(inlineData = Blob(mimeType = "image/png", data = byteArrayOf(1, 2, 3)))
      val pdf = Part(inlineData = Blob(mimeType = "application/PDF", data = byteArrayOf(4)))
      val request =
        LlmRequest(
          contents =
            listOf(
              userText("Draw a cat"),
              Content(role = Role.MODEL, parts = listOf(image, pdf, Part(text = "Here it is"))),
            )
        )

      claude.generateContent(request, stream = false).toList()

      val modelTurn = recordedBody()["messages"]!!.jsonArray[1].jsonObject
      assertThat(
          modelTurn["content"]!!.jsonArray.map { it.jsonObject["text"]!!.jsonPrimitive.content }
        )
        .containsExactly("Here it is")
    }

  @Test
  fun generateContent_rejectsMediaWithoutInlineData() {
    val image = Part(inlineData = Blob(mimeType = "image/png"))
    val request = LlmRequest(contents = listOf(Content(role = Role.USER, parts = listOf(image))))

    assertFailsWith<IllegalArgumentException> {
      runBlocking { claude.generateContent(request, stream = false).toList() }
    }
  }

  @Test
  fun generateContent_pairsIdlessResultWithOldestCallWhenNoNameMatches() =
    runBlocking<Unit> {
      server.enqueue(jsonResponse(message(content = """[{"type":"text","text":"ok"}]""")))
      val request =
        LlmRequest(
          contents =
            listOf(
              userText("Weather?"),
              Content(
                role = Role.MODEL,
                parts =
                  listOf(Part(functionCall = FunctionCall(name = "get_weather", args = emptyMap()))),
              ),
              Content(
                role = Role.USER,
                parts =
                  listOf(
                    Part(functionResponse = FunctionResponse(name = "weather", response = mapOf()))
                  ),
              ),
            )
        )

      claude.generateContent(request, stream = false).toList()

      val result =
        recordedBody()["messages"]!!.jsonArray[2].jsonObject["content"]!!.jsonArray.single()
      assertThat(result.jsonObject["tool_use_id"]!!.jsonPrimitive.content)
        .isEqualTo("toolu_fallback_0")
    }

  @Test
  fun generateContent_setsErrorCodeWhenStopReasonIsNotAStop() =
    runBlocking<Unit> {
      server.enqueue(
        jsonResponse(
          message(content = """[{"type":"text","text":"Once upon"}]""", stopReason = "max_tokens")
        )
      )

      val response =
        claude.generateContent(userRequest("Tell a story"), stream = false).toList().single()

      assertThat(response.finishReason).isEqualTo(FinishReason.MAX_TOKENS)
      assertThat(response.errorCode).isEqualTo("MAX_TOKENS")
    }

  @Test
  fun generateContent_sendsImagesAndPdfsAsBase64Blocks() =
    runBlocking<Unit> {
      server.enqueue(jsonResponse(message(content = """[{"type":"text","text":"ok"}]""")))
      val parts =
        listOf(
          Part(inlineData = Blob(mimeType = "image/PNG; charset=binary", data = byteArrayOf(1, 2))),
          Part(inlineData = Blob(mimeType = "application/pdf", data = byteArrayOf(3, 4))),
          Part(text = "Describe these"),
        )

      claude
        .generateContent(
          LlmRequest(contents = listOf(Content(role = Role.USER, parts = parts))),
          stream = false,
        )
        .toList()

      val blocks =
        recordedBody()["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray.map {
          it.jsonObject
        }
      val image = blocks[0]["source"]!!.jsonObject
      assertThat(blocks[0]["type"]!!.jsonPrimitive.content).isEqualTo("image")
      assertThat(image["type"]!!.jsonPrimitive.content).isEqualTo("base64")
      assertThat(image["media_type"]!!.jsonPrimitive.content).isEqualTo("image/png")
      assertThat(image["data"]!!.jsonPrimitive.content).isEqualTo("AQI=")
      val pdf = blocks[1]["source"]!!.jsonObject
      assertThat(blocks[1]["type"]!!.jsonPrimitive.content).isEqualTo("document")
      assertThat(pdf["media_type"]!!.jsonPrimitive.content).isEqualTo("application/pdf")
      assertThat(pdf["data"]!!.jsonPrimitive.content).isEqualTo("AwQ=")
    }

  @Test
  fun generateContent_mapsThinkingBlocksToSignedThoughts() =
    runBlocking<Unit> {
      server.enqueue(
        jsonResponse(
          message(
              content =
                """[{"type":"thinking","thinking":"Let me think","signature":"sig-1"},""" +
                  """{"type":"redacted_thinking","data":"encrypted"},{"type":"text","text":"42"}]"""
            )
            .replace(
              """"output_tokens":2}""",
              """"output_tokens":10,"output_tokens_details":{"thinking_tokens":6}}""",
            )
        )
      )

      val response = claude.generateContent(userRequest("Hi"), stream = false).toList().single()

      assertThat(response.content!!.parts).hasSize(3)
      val (thinking, redacted, answer) = response.content!!.parts
      assertThat(thinking.thought).isTrue()
      assertThat(thinking.text).isEqualTo("Let me think")
      assertThat(thinking.thoughtSignature!!.decodeToString()).isEqualTo("sig-1")
      assertThat(redacted.thought).isTrue()
      assertThat(redacted.thoughtSignature!!.decodeToString()).isEqualTo("encrypted")
      assertThat(answer.text).isEqualTo("42")
      assertThat(response.usageMetadata!!.thoughtsTokenCount).isEqualTo(6)
      assertThat(response.usageMetadata!!.candidatesTokenCount).isEqualTo(4)
      assertThat(response.usageMetadata!!.totalTokenCount).isEqualTo(13)
    }

  @Test
  fun generateContent_streaming_emitsThoughtPartialsAndSignedFinalThought() =
    runBlocking<Unit> {
      server.enqueue(
        MockResponse(
          headers = Headers.headersOf("content-type", "text/event-stream"),
          body =
            sse(
              "message_start" to
                """{"type":"message_start","message":${message(content = "[]", stopReason = null)}}""",
              "content_block_start" to
                """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"","signature":""}}""",
              "content_block_delta" to
                """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"Hmm"}}""",
              "content_block_delta" to
                """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"sig-2"}}""",
              "content_block_stop" to """{"type":"content_block_stop","index":0}""",
              "content_block_start" to
                """{"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}""",
              "content_block_delta" to
                """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"Done"}}""",
              "content_block_stop" to """{"type":"content_block_stop","index":1}""",
              "message_delta" to
                """{"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":3}}""",
              "message_stop" to """{"type":"message_stop"}""",
            ),
        )
      )

      val responses = claude.generateContent(userRequest("Hi"), stream = true).toList()

      val partials = responses.filter { it.partial == true }.map { it.content!!.parts.single() }
      assertThat(partials.map { it.text to it.thought })
        .containsExactly("Hmm" to true, "Done" to null)
        .inOrder()
      assertThat(responses.last().content!!.parts).hasSize(2)
      val (thought, text) = responses.last().content!!.parts
      assertThat(thought.text).isEqualTo("Hmm")
      assertThat(thought.thoughtSignature!!.decodeToString()).isEqualTo("sig-2")
      assertThat(text.text).isEqualTo("Done")
    }

  @Test
  fun hasAnthropicCredentialSource_isFalseWithoutAnySource() {
    assertThat(hasAnthropicCredentialSource(env = { null }, property = linuxProperties()::get))
      .isFalse()
  }

  @Test
  fun hasAnthropicCredentialSource_findsKeysTokensAndProfiles() {
    val properties = linuxProperties()
    for (name in listOf("ANTHROPIC_API_KEY", "ANTHROPIC_CUSTOM_HEADERS", "ANTHROPIC_PROFILE")) {
      assertThat(
          hasAnthropicCredentialSource(env = mapOf(name to "x")::get, property = properties::get)
        )
        .isTrue()
    }
    assertThat(
        hasAnthropicCredentialSource(
          env = { null },
          property = (properties + ("anthropic.authToken" to "t"))::get,
        )
      )
      .isTrue()
  }

  @Test
  fun hasAnthropicCredentialSource_findsTheDefaultConfigDirectory() {
    temporaryFolder.newFolder(".config", "anthropic")

    assertThat(hasAnthropicCredentialSource(env = { null }, property = linuxProperties()::get))
      .isTrue()
  }

  @Test
  fun vertexClient_sendsRawPredictWithBearerTokenAndVertexBody() =
    runBlocking<Unit> {
      server.enqueue(jsonResponse(message(content = """[{"type":"text","text":"pong"}]""")))
      val vertex = vertexClaude()

      val response = vertex.generateContent(userRequest("Ping"), stream = false).toList().single()

      assertThat(response.content!!.parts.single().text).isEqualTo("pong")
      val recorded = server.takeRequest()
      assertThat(recorded.target)
        .isEqualTo(
          "/v1/projects/test-project/locations/europe-west1/publishers/anthropic/models/" +
            "claude-haiku-4-5:rawPredict"
        )
      assertThat(recorded.headers["Authorization"]).isEqualTo("Bearer fake-token")
      assertThat(recorded.headers["x-goog-api-client"]).startsWith("google-adk/")
      assertThat(recorded.headers["user-agent"]).startsWith("google-adk/")
      val body = Json.parseToJsonElement(recorded.body!!.utf8()).jsonObject
      assertThat(body["anthropic_version"]!!.jsonPrimitive.content).isEqualTo("vertex-2023-10-16")
      // The model id travels in the URL, not the body.
      assertThat(body).doesNotContainKey("model")
    }

  @Test
  fun vertexClient_streamsThroughStreamRawPredict() =
    runBlocking<Unit> {
      server.enqueue(
        MockResponse(
          headers = Headers.headersOf("content-type", "text/event-stream"),
          body =
            sse(
              "message_start" to
                """{"type":"message_start","message":${message(content = "[]", stopReason = null)}}""",
              "message_delta" to
                """{"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":1}}""",
              "message_stop" to """{"type":"message_stop"}""",
            ),
        )
      )
      val vertex = vertexClaude()

      vertex.generateContent(userRequest("Ping"), stream = true).toList()

      assertThat(server.takeRequest().target).endsWith("/claude-haiku-4-5:streamRawPredict")
    }

  @Test
  fun vertexClient_requiresProject() {
    val error =
      assertFailsWith<IllegalArgumentException> {
        vertexClient(vertexCredentials().copy(project = null), env = { " " })
      }

    assertThat(error).hasMessageThat().contains("GOOGLE_CLOUD_PROJECT")
  }

  @Test
  fun vertexClient_requiresLocation() {
    val error =
      assertFailsWith<IllegalArgumentException> {
        vertexClient(vertexCredentials().copy(location = null), env = { " " })
      }

    assertThat(error).hasMessageThat().contains("GOOGLE_CLOUD_LOCATION")
  }

  @Test
  fun vertexClient_treatsBlankProjectAndLocationAsUnset() =
    runBlocking<Unit> {
      server.enqueue(jsonResponse(message(content = """[{"type":"text","text":"pong"}]""")))
      val env =
        mapOf("GOOGLE_CLOUD_PROJECT" to "env-project", "GOOGLE_CLOUD_LOCATION" to "us-east5")
      val credentials = vertexCredentials().copy(project = " ", location = "")
      val vertex = vertexClaude(credentials, env::get)

      vertex.generateContent(userRequest("Ping"), stream = false).toList()

      assertThat(server.takeRequest().target)
        .startsWith("/v1/projects/env-project/locations/us-east5/")
    }

  @Test
  fun vertexClient_fallsBackToEnvironmentForProjectAndLocation() =
    runBlocking<Unit> {
      server.enqueue(jsonResponse(message(content = """[{"type":"text","text":"pong"}]""")))
      val env =
        mapOf("GOOGLE_CLOUD_PROJECT" to "env-project", "GOOGLE_CLOUD_LOCATION" to "us-east5")
      val credentials = vertexCredentials().copy(project = null, location = null)
      val vertex = vertexClaude(credentials, env::get)

      vertex.generateContent(userRequest("Ping"), stream = false).toList()

      assertThat(server.takeRequest().target)
        .startsWith("/v1/projects/env-project/locations/us-east5/")
    }

  @Test
  fun vertexClient_takesProjectAndLocationFromResourceName() =
    runBlocking<Unit> {
      server.enqueue(jsonResponse(message(content = """[{"type":"text","text":"pong"}]""")))
      val resource = "projects/res-project/locations/us-east5/publishers/anthropic/models/claude-x"
      val env = mapOf("GOOGLE_CLOUD_PROJECT" to "env-project", "GOOGLE_CLOUD_LOCATION" to "global")
      val credentials = vertexCredentials().copy(project = null, location = null)
      val client =
        vertexClient(credentials, server.url("/").toString(), env::get, modelName = resource)
      vertexClients += client

      Claude(resource, client).generateContent(userRequest("Ping"), stream = false).toList()

      assertThat(server.takeRequest().target)
        .isEqualTo(
          "/v1/projects/res-project/locations/us-east5/publishers/anthropic/models/claude-x:rawPredict"
        )
    }

  @Test
  fun vertexClient_scopesCredentialsThatRequireIt() =
    runBlocking<Unit> {
      server.enqueue(jsonResponse(message(content = """[{"type":"text","text":"pong"}]""")))
      val credentials = ScopeRequiringCredentials()
      val vertex = vertexClaude(vertexCredentials().copy(credentials = credentials))

      vertex.generateContent(userRequest("Ping"), stream = false).toList()

      assertThat(credentials.requestedScopes)
        .containsExactly("https://www.googleapis.com/auth/cloud-platform")
      assertThat(server.takeRequest().headers["Authorization"]).isEqualTo("Bearer scoped-token")
    }

  /** A Vertex AI [Claude] that targets the mock server; its client is closed in [tearDown]. */
  private fun vertexClaude(
    credentials: VertexCredentials = vertexCredentials(),
    env: (String) -> String? = System::getenv,
  ): Claude =
    Claude(
      "claude-haiku-4-5",
      vertexClient(credentials, server.url("/").toString(), env).also { vertexClients += it },
    )

  private fun vertexCredentials(): VertexCredentials =
    VertexCredentials(
      project = "test-project",
      location = "europe-west1",
      credentials =
        GoogleCredentials.newBuilder()
          .setAccessToken(AccessToken("fake-token", Date.from(Instant.now().plusSeconds(3600))))
          .build(),
    )

  private fun linuxProperties() =
    mapOf("user.home" to temporaryFolder.root.path, "os.name" to "Linux")

  private fun recordedBody(): JsonObject =
    Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject

  private fun userText(text: String): Content =
    Content(role = Role.USER, parts = listOf(Part(text = text)))

  private fun userRequest(text: String): LlmRequest = LlmRequest(contents = listOf(userText(text)))

  private fun weatherDeclaration(): FunctionDeclaration =
    FunctionDeclaration(
      name = "get_weather",
      description = "Get weather",
      parameters =
        Schema(
          type = Type.OBJECT,
          description = "Weather lookup arguments",
          properties = mapOf("city" to Schema(type = Type.STRING)),
          required = listOf("city"),
        ),
    )

  private fun rateLimitResponse(): MockResponse =
    MockResponse(
      code = 429,
      headers = Headers.headersOf("content-type", "application/json"),
      body = """{"type":"error","error":{"type":"rate_limit_error","message":"slow down"}}""",
    )

  private fun jsonResponse(body: String): MockResponse =
    MockResponse(headers = Headers.headersOf("content-type", "application/json"), body = body)

  private fun message(
    content: String,
    stopReason: String? = "end_turn",
    outputTokens: Int = 2,
  ): String {
    val reason = stopReason?.let { "\"$it\"" } ?: "null"
    return """{"id":"msg_1","type":"message","role":"assistant","model":"claude-x",""" +
      """"content":$content,"stop_reason":$reason,"stop_sequence":null,""" +
      """"usage":{"input_tokens":3,"output_tokens":$outputTokens}}"""
  }

  private fun sse(vararg events: Pair<String, String>): String =
    events.joinToString(separator = "") { (event, data) -> "event: $event\ndata: $data\n\n" }
}

/** Credentials that, like a service account, must be scoped before they can mint a token. */
private class ScopeRequiringCredentials : GoogleCredentials() {
  var requestedScopes: Collection<String>? = null

  override fun createScopedRequired(): Boolean = true

  override fun createScoped(scopes: Collection<String>): GoogleCredentials {
    requestedScopes = scopes
    return create(AccessToken("scoped-token", Date.from(Instant.now().plusSeconds(3600))))
  }
}
