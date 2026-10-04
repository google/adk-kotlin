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
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.CodeExecutionResult
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.ExecutableCode
import com.google.adk.kt.types.FileData
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
import com.google.common.truth.Truth.assertThat
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
import org.junit.Test

/** Drives [Claude] through the real Anthropic SDK client against canned HTTP responses. */
class ClaudeTest {

  private lateinit var server: MockWebServer
  private lateinit var client: AnthropicClient
  private lateinit var claude: Claude

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
                parts =
                  listOf(
                    Part(text = "thinking", thought = true),
                    // A thought-flagged call keeps its tool_use and drops its thought text.
                    Part(text = "plan", thought = true, functionCall = call),
                  ),
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
      // Missing, empty, or invalid ids are replaced; id-less results pair with calls by name.
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
                    Part(functionCall = FunctionCall(name = "get_date", args = emptyMap(), id = "")),
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
                      functionResponse = FunctionResponse(name = "get_date", response = mapOf())
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
      assertThat(callIds)
        .containsExactly("toolu_fallback_0", "toolu_fallback_1", "toolu_fallback_2")
        .inOrder()
      assertThat(resultIds)
        .containsExactly("toolu_fallback_1", "toolu_fallback_2", "toolu_fallback_0")
        .inOrder()
    }

  @Test
  fun generateContent_sendsAToolWithoutParametersAsAnEmptyObjectSchema() =
    runBlocking<Unit> {
      server.enqueue(jsonResponse(message(content = """[{"type":"text","text":"ok"}]""")))
      val ping = FunctionDeclaration(name = "ping", description = "Ping")
      val request =
        LlmRequest(
          contents = listOf(userText("Ping")),
          config = GenerateContentConfig(tools = listOf(Tool(functionDeclarations = listOf(ping)))),
        )

      claude.generateContent(request, stream = false).toList()

      val schema = recordedBody()["tools"]!!.jsonArray.single().jsonObject["input_schema"]!!
      assertThat(schema).isEqualTo(Json.parseToJsonElement("""{"properties":{},"type":"object"}"""))
    }

  @Test
  fun generateContent_sendsTheCallOfAPartWithEmptyText() =
    runBlocking<Unit> {
      server.enqueue(jsonResponse(message(content = """[{"type":"text","text":"ok"}]""")))
      val call = FunctionCall(name = "get_weather", args = emptyMap(), id = "call-1")
      val request =
        LlmRequest(
          contents =
            listOf(
              userText("Weather?"),
              Content(role = Role.MODEL, parts = listOf(Part(text = "", functionCall = call))),
            )
        )

      claude.generateContent(request, stream = false).toList()

      val block =
        recordedBody()["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonArray.single()
      assertThat(block.jsonObject["type"]!!.jsonPrimitive.content).isEqualTo("tool_use")
      assertThat(block.jsonObject["id"]!!.jsonPrimitive.content).isEqualTo("call-1")
    }

  @Test
  fun generateContent_rejectsAThoughtSignatureOnAFilePart() {
    val part =
      Part(
        thoughtSignature = byteArrayOf(1, 2),
        fileData = FileData(fileUri = "gs://bucket/report.pdf", mimeType = "application/pdf"),
      )
    val request = LlmRequest(contents = listOf(Content(role = Role.USER, parts = listOf(part))))

    assertFailsWith<IllegalArgumentException> {
      runBlocking { claude.generateContent(request, stream = false).toList() }
    }
  }

  @Test
  fun generateContent_streaming_readsAToolUseWithoutArgumentDeltasAsEmpty() =
    runBlocking<Unit> {
      server.enqueue(
        MockResponse(
          headers = Headers.headersOf("content-type", "text/event-stream"),
          body =
            sse(
              "message_start" to
                """{"type":"message_start","message":${message(content = "[]", stopReason = null, outputTokens = 1)}}""",
              "content_block_start" to
                """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"tu_1","name":"ping","input":{}}}""",
              "content_block_stop" to """{"type":"content_block_stop","index":0}""",
              "message_delta" to
                """{"type":"message_delta","delta":{"stop_reason":"tool_use","stop_sequence":null},"usage":{"output_tokens":3}}""",
              "message_stop" to """{"type":"message_stop"}""",
            ),
        )
      )

      val final = claude.generateContent(userRequest("Ping"), stream = true).toList().last()

      val call = final.content!!.parts.single().functionCall!!
      assertThat(call.name).isEqualTo("ping")
      assertThat(call.args).isEmpty()
    }

  @Test
  fun generateContent_dropsAToolUseCutOffByMaxTokens() =
    runBlocking<Unit> {
      // The SDK accumulates the unparsable input fragment of a cut-off stream as {}.
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
                """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Checking"}}""",
              "content_block_stop" to """{"type":"content_block_stop","index":0}""",
              "content_block_start" to
                """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"tu_1","name":"get_weather","input":{}}}""",
              "content_block_delta" to
                """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"city\": \"Pa"}}""",
              "content_block_stop" to """{"type":"content_block_stop","index":1}""",
              "message_delta" to
                """{"type":"message_delta","delta":{"stop_reason":"max_tokens","stop_sequence":null},"usage":{"output_tokens":3}}""",
              "message_stop" to """{"type":"message_stop"}""",
            ),
        )
      )
      server.enqueue(
        jsonResponse(
          message(
            content =
              """[{"type":"text","text":"Checking"},""" +
                """{"type":"tool_use","id":"tu_1","name":"get_weather","input":{}}]""",
            stopReason = "max_tokens",
          )
        )
      )

      val streamed = claude.generateContent(userRequest("Weather?"), stream = true).toList().last()
      val unary = claude.generateContent(userRequest("Weather?"), stream = false).toList().single()

      for (response in listOf(streamed, unary)) {
        assertThat(response.content!!.parts.map { it.text }).containsExactly("Checking")
        assertThat(response.finishReason).isEqualTo(FinishReason.MAX_TOKENS)
      }
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
    val image = Part(inlineData = Blob(mimeType = "image/png", data = byteArrayOf(1, 2, 3)))
    val request = LlmRequest(contents = listOf(Content(role = Role.USER, parts = listOf(image))))

    assertFailsWith<IllegalArgumentException> {
      runBlocking { claude.generateContent(request, stream = false).toList() }
    }
  }

  @Test
  fun generateContent_dropsImagesFromModelTurns() =
    runBlocking<Unit> {
      server.enqueue(jsonResponse(message(content = """[{"type":"text","text":"ok"}]""")))
      val image = Part(inlineData = Blob(mimeType = "image/png", data = byteArrayOf(1, 2, 3)))
      val request =
        LlmRequest(
          contents =
            listOf(
              userText("Draw a cat"),
              Content(role = Role.MODEL, parts = listOf(image, Part(text = "Here it is"))),
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

  private fun recordedBody(): JsonObject =
    Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject

  private fun userText(text: String): Content = Content.fromText(Role.USER, text)

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
