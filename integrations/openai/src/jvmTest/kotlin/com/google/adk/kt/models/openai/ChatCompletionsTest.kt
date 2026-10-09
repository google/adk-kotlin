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
package com.google.adk.kt.models.openai

import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.CodeExecutionResult
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.ExecutableCode
import com.google.adk.kt.types.FileData
import com.google.adk.kt.types.FinishReason
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionCallingConfig
import com.google.adk.kt.types.FunctionCallingConfigMode
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.GenerateContentConfig
import com.google.adk.kt.types.HttpOptions
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Tool
import com.google.adk.kt.types.ToolCall
import com.google.adk.kt.types.ToolConfig
import com.google.adk.kt.types.ToolResponse
import com.google.adk.kt.types.Type
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ChatCompletionsTest {

  @Test
  fun apiKeyOrDefault_readsOpenAiApiKeyOnlyForOpenAisBaseUrl() {
    val env = { name: String -> if (name == "OPENAI_API_KEY") "openai-key" else null }
    val otherServer = HttpOptions(baseUrl = "http://localhost:8080/v1")

    assertThat(apiKeyOrDefault(null, null, env)).isEqualTo("openai-key")
    assertThat(apiKeyOrDefault(" ", HttpOptions(timeout = 1.seconds), env)).isEqualTo("openai-key")
    assertThat(apiKeyOrDefault(null, otherServer, env)).isNull()
    assertThat(apiKeyOrDefault("", otherServer, env)).isNull()
    assertThat(apiKeyOrDefault("own-key", otherServer, env)).isEqualTo("own-key")
  }

  @Test
  fun apiKeyOrDefault_failsForOpenAisOwnEndpointWithoutAKey() {
    // A blank key counts as unset.
    assertFailsWith<IllegalStateException> { apiKeyOrDefault(" ", null) { null } }
  }

  @Test
  fun apiKeyOrDefault_givesTheOpenAiBaseUrlTheOpenAiApiKeyOrNone() {
    // As in the OpenAI SDKs, a proxy named in `OPENAI_BASE_URL` gets `OPENAI_API_KEY`, if set.
    val proxy = mapOf("OPENAI_BASE_URL" to "http://proxy/v1", "OPENAI_API_KEY" to "openai-key")
    assertThat(apiKeyOrDefault(null, null, proxy::get)).isEqualTo("openai-key")
    assertThat(apiKeyOrDefault(null, null, (proxy - "OPENAI_API_KEY")::get)).isNull()
  }

  @Test
  fun withEnvBaseUrl_readsOpenAiBaseUrlOnlyWhenNoneIsSet() {
    val env = { name: String -> if (name == "OPENAI_BASE_URL") "http://proxy/v1" else null }
    val own = HttpOptions(baseUrl = "http://own/v1")

    assertThat(withEnvBaseUrl(null, env)).isEqualTo(HttpOptions(baseUrl = "http://proxy/v1"))
    assertThat(withEnvBaseUrl(HttpOptions(timeout = 1.seconds), env))
      .isEqualTo(HttpOptions(baseUrl = "http://proxy/v1", timeout = 1.seconds))
    assertThat(withEnvBaseUrl(own, env)).isSameInstanceAs(own)
    assertThat(withEnvBaseUrl(null) { null }).isNull()
  }

  @Test
  fun toChatCompletionRequest_mapsSystemMessagesToolsAndConfig() {
    val request =
      LlmRequest(
        contents = listOf(Content(role = Role.USER, parts = listOf(Part(text = "Hi")))),
        config =
          GenerateContentConfig(
            systemInstruction = Content(parts = listOf(Part(text = "Be brief"))),
            maxOutputTokens = 100,
            temperature = 0.5f,
            seed = 7,
            tools =
              listOf(
                Tool(
                  functionDeclarations =
                    listOf(
                      FunctionDeclaration(
                        name = "get_weather",
                        description = "Get weather",
                        parameters =
                          Schema(
                            type = Type.OBJECT,
                            properties = mapOf("city" to Schema(type = Type.STRING)),
                            required = listOf("city"),
                          ),
                      )
                    )
                )
              ),
          ),
      )

    val chat = request.toChatCompletionRequest("gpt-4o")

    assertThat(chat.model).isEqualTo("gpt-4o")
    assertThat(chat.maxTokens).isEqualTo(100)
    assertThat(chat.maxCompletionTokens).isNull()
    assertThat(chat.temperature).isEqualTo(0.5f)
    assertThat(chat.seed).isEqualTo(7)
    assertThat(chat.responseFormat).isNull()
    assertThat(chat.messages).hasSize(2)
    assertThat(chat.messages[0].role).isEqualTo("system")
    assertThat(chat.messages[0].contentText()).isEqualTo("Be brief")
    assertThat(chat.messages[1].role).isEqualTo("user")
    assertThat(chat.stream).isNull()
    assertThat(chat.tools).hasSize(1)
    assertThat(chat.tools!![0].function.name).isEqualTo("get_weather")
    assertThat(chat.toolChoice).isEqualTo("auto")

    // JSON Schema types must be lowercased for Chat Completions.
    val params = chat.tools[0].function.parameters
    assertThat(params["type"]!!.jsonPrimitive.content).isEqualTo("object")
    assertThat(
        params["properties"]!!.jsonObject["city"]!!.jsonObject["type"]!!.jsonPrimitive.content
      )
      .isEqualTo("string")
  }

  @Test
  fun toChatCompletionRequest_serializesToolTypeOnWire() {
    val request =
      LlmRequest(
        contents = listOf(Content(role = Role.USER, parts = listOf(Part(text = "Hi")))),
        config =
          GenerateContentConfig(
            tools =
              listOf(
                Tool(
                  functionDeclarations =
                    listOf(FunctionDeclaration(name = "get_weather", description = "Get weather"))
                )
              )
          ),
      )

    val json = chatCompletionsJson.encodeToString(request.toChatCompletionRequest("gpt-4o"))

    // The API rejects a tool without `type`, which encodeDefaults=false would omit.
    assertThat(json).contains("\"type\":\"function\"")
  }

  @Test
  fun toChatCompletionRequest_usesDeveloperRoleForOSeriesModels() {
    val request =
      LlmRequest(
        config =
          GenerateContentConfig(
            systemInstruction = Content(parts = listOf(Part(text = "Be brief")))
          )
      )

    for (model in listOf("o1-mini", "openai/o3-mini", "O4-mini")) {
      assertThat(request.toChatCompletionRequest(model).messages[0].role).isEqualTo("developer")
    }
    assertThat(request.toChatCompletionRequest("gpt-4o").messages[0].role).isEqualTo("system")
  }

  @Test
  fun toChatCompletionRequest_mapsAssistantToolCallAndToolResult() {
    val assistant =
      Content(
        role = Role.MODEL,
        parts =
          listOf(
            Part(functionCall = FunctionCall(name = "f", args = mapOf("x" to 1L), id = "call_1"))
          ),
      )
    val toolTurn =
      Content(
        role = Role.USER,
        parts =
          listOf(
            Part(
              functionResponse =
                FunctionResponse(name = "f", response = mapOf("result" to "ok"), id = "call_1")
            )
          ),
      )

    val assistantMessage = assistant.toMessages().single()
    assertThat(assistantMessage.role).isEqualTo("assistant")
    val toolCall = assistantMessage.toolCalls!!.single()
    assertThat(toolCall.id).isEqualTo("call_1")
    assertThat(toolCall.function!!.name).isEqualTo("f")
    assertThat(toolCall.function.arguments).contains("\"x\"")

    val toolMessage = toolTurn.toMessages().single()
    assertThat(toolMessage.role).isEqualTo("tool")
    assertThat(toolMessage.toolCallId).isEqualTo("call_1")
    // ADK's {"result": value} wrapper for a scalar tool return is unwrapped.
    assertThat(toolMessage.contentText()).isEqualTo("ok")
  }

  @Test
  fun toChatCompletionRequest_unwrapsOnlyASoleResultKey() {
    fun toolText(response: Map<String, Any?>): String? {
      val part = Part(functionResponse = FunctionResponse(name = "f", response = response))
      return Content(role = Role.USER, parts = listOf(part)).toMessages().single().contentText()
    }

    assertThat(toolText(mapOf("result" to 1))).isEqualTo("1")
    assertThat(toolText(mapOf("result" to listOf("a")))).isEqualTo("[\"a\"]")
    assertThat(toolText(mapOf("result" to 1, "x" to 2))).isEqualTo("{\"result\":1,\"x\":2}")
  }

  @Test
  fun toChatCompletionRequest_throwsOnUnsupportedPart() {
    val parts =
      listOf(
        Part(inlineData = Blob(mimeType = "audio/wav", data = byteArrayOf(1))),
        // Only a `gs://` file typed as an image is sent as one.
        Part(fileData = FileData(fileUri = "gs://bucket/report")),
      )
    for (part in parts) {
      val content = Content(role = Role.USER, parts = listOf(part))
      assertFailsWith<IllegalArgumentException> { content.toMessages() }
    }
  }

  @Test
  fun toChatCompletionRequest_dropsPartsWithNothingToSendAndSendsCodeAsText() {
    // Session history can keep these, for example after switching from Gemini.
    val model =
      Content(
        role = Role.MODEL,
        parts =
          listOf(
            Part(thoughtSignature = byteArrayOf(1)),
            Part(toolCall = ToolCall(id = "t1")),
            Part(toolResponse = ToolResponse(id = "t1")),
            Part(executableCode = ExecutableCode(code = "print(1)")),
            Part(codeExecutionResult = CodeExecutionResult(output = "1")),
          ),
      )
    val user = Content(role = Role.USER, parts = listOf(Part(thoughtSignature = byteArrayOf(1))))

    val message = model.toMessages().single()

    assertThat(message.contentText())
      .isEqualTo("Code:```python\nprint(1)\n```\nExecution Result:```code_output\n1\n```")
    assertThat(user.toMessages()).isEmpty()
  }

  @Test
  fun toChatCompletionRequest_sendsNoUserMessageForAThoughtImageAlone() {
    val image =
      Part(inlineData = Blob(mimeType = "image/png", data = byteArrayOf(1)), thought = true)

    val messages = Content(role = Role.USER, parts = listOf(image)).toMessages()

    assertThat(messages).isEmpty()
  }

  @Test
  fun toChatCompletionRequest_sendsUserImagesAsImageUrlParts() {
    val content =
      Content(
        role = Role.USER,
        parts =
          listOf(
            Part(text = "What is this?"),
            Part(inlineData = Blob(mimeType = "image/PNG", data = byteArrayOf(1, 2, 3))),
            Part(fileData = FileData(fileUri = "https://example.com/cat.jpg")),
            // Vertex AI reads images from Cloud Storage.
            Part(fileData = FileData(fileUri = "gs://bucket/dog.png", mimeType = "image/PNG")),
            Part(text = ""),
          ),
      )

    val parts = content.toMessages().single().content!!.jsonArray

    assertThat(parts.map { it.jsonObject["type"]!!.jsonPrimitive.content })
      .containsExactly("text", "image_url", "image_url", "image_url")
      .inOrder()
    assertThat(parts[0].jsonObject["text"]!!.jsonPrimitive.content).isEqualTo("What is this?")
    assertThat(parts[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
      .isEqualTo("data:image/png;base64,AQID")
    assertThat(parts[2].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
      .isEqualTo("https://example.com/cat.jpg")
    assertThat(parts[3].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
      .isEqualTo("gs://bucket/dog.png")
  }

  @Test
  fun toChatCompletionRequest_sendsOnlyTheTextOfModelTurns() {
    // An assistant message carries only text, so a generated image stays out of the history.
    val content =
      Content(
        role = Role.MODEL,
        parts =
          listOf(
            Part(text = "Planning", thought = true),
            Part(text = "Here it is"),
            Part(text = ""),
            Part(inlineData = Blob(mimeType = "image/png", data = byteArrayOf(1))),
            Part(text = "Enjoy"),
          ),
      )

    val message = content.toMessages().single()

    assertThat(message.content).isEqualTo(JsonPrimitive("Here it is\nEnjoy"))
  }

  @Test
  fun toChatCompletionRequest_sendsSystemTurnsWithTheSystemRole() {
    val request =
      LlmRequest(
        contents = listOf(Content(role = Role.SYSTEM, parts = listOf(Part(text = "Be terse")))),
        config =
          GenerateContentConfig(
            tools = listOf(Tool(functionDeclarations = listOf(FunctionDeclaration("f", "Does f"))))
          ),
      )

    val message = request.toChatCompletionRequest("gpt-4o").messages.single()
    val oSeriesMessage = request.toChatCompletionRequest("o3-mini").messages.single()

    assertThat(message.role).isEqualTo("system")
    assertThat(message.contentText()).isEqualTo("Be terse")
    assertThat(oSeriesMessage.role).isEqualTo("developer")
  }

  @Test
  fun toChatCompletionRequest_keepsToolCallsOnlyOnAssistantMessages() {
    val call = Part(functionCall = FunctionCall(name = "f", args = emptyMap(), id = "call_1"))

    val userMessage =
      Content(role = Role.USER, parts = listOf(Part(text = "Hi"), call)).toMessages().single()

    assertThat(userMessage.toolCalls).isNull()
  }

  @Test
  fun toChatCompletionRequest_mapsToolChoiceModes() {
    fun toolChoice(mode: FunctionCallingConfigMode?, withTools: Boolean = true): String? =
      LlmRequest(
          config =
            GenerateContentConfig(
              tools =
                if (withTools) {
                  listOf(Tool(functionDeclarations = listOf(FunctionDeclaration("f", "Does f"))))
                } else {
                  null
                },
              toolConfig = ToolConfig(functionCallingConfig = FunctionCallingConfig(mode = mode)),
            )
        )
        .toChatCompletionRequest("gpt-4o")
        .toolChoice

    assertThat(toolChoice(FunctionCallingConfigMode.ANY)).isEqualTo("required")
    assertThat(toolChoice(FunctionCallingConfigMode.NONE)).isEqualTo("none")
    assertThat(toolChoice(FunctionCallingConfigMode.AUTO)).isEqualTo("auto")
    assertThat(toolChoice(FunctionCallingConfigMode.VALIDATED)).isEqualTo("auto")
    assertThat(toolChoice(null)).isEqualTo("auto")
    assertThat(toolChoice(FunctionCallingConfigMode.ANY, withTools = false)).isNull()
  }

  @Test
  fun toChatCompletionRequest_reasoningModelsTakeMaxCompletionTokensAndDefaultSampling() {
    val config =
      GenerateContentConfig(
        maxOutputTokens = 100,
        temperature = 0.5f,
        topP = 1f,
        presencePenalty = 0.5f,
        frequencyPenalty = 0f,
        stopSequences = emptyList(),
      )

    for (model in listOf("o3-mini", "gpt-5.1", "openai/o4-mini")) {
      val chat = LlmRequest(config = config).toChatCompletionRequest(model)
      assertThat(chat.maxCompletionTokens).isEqualTo(100)
      assertThat(chat.maxTokens).isNull()
      // A reasoning model rejects sampling values other than the defaults.
      assertThat(chat.temperature).isNull()
      assertThat(chat.topP).isEqualTo(1f)
      assertThat(chat.presencePenalty).isNull()
      assertThat(chat.frequencyPenalty).isEqualTo(0f)
      assertThat(chat.stop).isNull()
    }
    for (model in listOf("gpt-4o", "gpt-5-chat-latest", "llama-3.3-70b")) {
      val chat = LlmRequest(config = config).toChatCompletionRequest(model)
      assertThat(chat.maxTokens).isEqualTo(100)
      assertThat(chat.maxCompletionTokens).isNull()
      assertThat(chat.temperature).isEqualTo(0.5f)
      assertThat(chat.presencePenalty).isEqualTo(0.5f)
    }
  }

  @Test
  fun toChatCompletionRequest_alwaysSendsToolProperties() {
    val declarations =
      listOf(
        FunctionDeclaration("f", "Does f", parameters = Schema(type = Type.OBJECT)),
        FunctionDeclaration("g", "Does g"),
      )
    val request =
      LlmRequest(
        config = GenerateContentConfig(tools = listOf(Tool(functionDeclarations = declarations)))
      )

    // Python sends `properties` even when a schema declares none, as an MCP tool's may.
    for (tool in request.toChatCompletionRequest("gpt-4o").tools!!) {
      assertThat(tool.function.parameters["properties"]).isEqualTo(JsonObject(emptyMap()))
    }
  }

  @Test
  fun toChatCompletionRequest_sendsAnUntypedToolSchemaAsAnObject() {
    val properties = mapOf("city" to Schema(type = Type.STRING))
    // Python sends parameters as an object whatever their declared type.
    for (type in listOf(null, Type.STRING)) {
      val parameters = Schema(type = type, properties = properties, required = listOf("city"))
      val declaration = FunctionDeclaration("f", "Does f", parameters)
      val request =
        LlmRequest(
          config =
            GenerateContentConfig(tools = listOf(Tool(functionDeclarations = listOf(declaration))))
        )

      val schema = request.toChatCompletionRequest("gpt-4o").tools!!.single().function.parameters

      assertThat(schema["type"]).isEqualTo(JsonPrimitive("object"))
      assertThat(schema["properties"]!!.jsonObject.keys).containsExactly("city")
      assertThat(schema["required"]).isEqualTo(JsonArray(listOf(JsonPrimitive("city"))))
    }
  }

  @Test
  fun toChatCompletionRequest_sendsResponseSchemaAsStrictJsonSchema() {
    val schema =
      Schema(
        type = Type.OBJECT,
        title = "Weather",
        properties =
          mapOf(
            "city" to Schema(type = Type.STRING),
            "note" to Schema(type = Type.STRING, nullable = true),
            "tags" to
              Schema(type = Type.ARRAY, items = Schema(type = Type.OBJECT, properties = mapOf())),
          ),
        required = listOf("city"),
      )
    val request = LlmRequest(config = GenerateContentConfig(responseSchema = schema))

    val format = request.toChatCompletionRequest("gpt-4o").responseFormat!!

    assertThat(format.type).isEqualTo("json_schema")
    val jsonSchema = format.jsonSchema!!
    assertThat(jsonSchema.name).isEqualTo("Weather")
    assertThat(jsonSchema.strict).isTrue()
    val root = jsonSchema.schema
    // Strict mode requires every property and forbids undeclared ones, at every level.
    assertThat(root["required"]!!.jsonArray.map { it.jsonPrimitive.content })
      .containsExactly("city", "note", "tags")
    assertThat(root["additionalProperties"]).isEqualTo(JsonPrimitive(false))
    val properties = root["properties"]!!.jsonObject
    val note = properties["note"]!!.jsonObject
    assertThat(note["type"]!!.jsonArray.map { it.jsonPrimitive.content })
      .containsExactly("string", "null")
    assertThat(note).doesNotContainKey("nullable")
    val item = properties["tags"]!!.jsonObject["items"]!!.jsonObject
    assertThat(item["additionalProperties"]).isEqualTo(JsonPrimitive(false))
  }

  @Test
  fun toChatCompletionRequest_addsNullToNullableUnionsAndEnums() {
    val member = Schema(type = Type.OBJECT, properties = mapOf("a" to Schema(type = Type.STRING)))
    val schema =
      Schema(
        type = Type.OBJECT,
        properties =
          mapOf(
            "value" to Schema(anyOf = listOf(Schema(type = Type.STRING), member), nullable = true),
            "size" to Schema(type = Type.STRING, enum = listOf("S", "L"), nullable = true),
          ),
      )
    val request = LlmRequest(config = GenerateContentConfig(responseSchema = schema))

    val properties =
      request
        .toChatCompletionRequest("gpt-4o")
        .responseFormat!!
        .jsonSchema!!
        .schema["properties"]!!
        .jsonObject
    val anyOf = properties["value"]!!.jsonObject["anyOf"]!!.jsonArray
    val size = properties["size"]!!.jsonObject

    // Union members are made strict too, and null joins the union and the enum.
    assertThat(anyOf[1].jsonObject["additionalProperties"]).isEqualTo(JsonPrimitive(false))
    assertThat(anyOf.last()).isEqualTo(JsonObject(mapOf("type" to JsonPrimitive("null"))))
    assertThat(size["enum"]!!.jsonArray)
      .containsExactly(JsonPrimitive("S"), JsonPrimitive("L"), JsonNull)
      .inOrder()
  }

  @Test
  fun toChatCompletionRequest_namesSchemaResponseWhenTitleIsNotAValidName() {
    val schema = Schema(type = Type.OBJECT, title = "Weather report", properties = mapOf())
    val request = LlmRequest(config = GenerateContentConfig(responseSchema = schema))

    assertThat(request.toChatCompletionRequest("gpt-4o").responseFormat!!.jsonSchema!!.name)
      .isEqualTo("response")
  }

  @Test
  fun toChatCompletionRequest_sendsJsonMimeTypeAsJsonObject() {
    val request = LlmRequest(config = GenerateContentConfig(responseMimeType = "application/json"))

    val format = request.toChatCompletionRequest("gpt-4o").responseFormat!!

    assertThat(format.type).isEqualTo("json_object")
    assertThat(format.jsonSchema).isNull()
  }

  @Test
  fun thoughtSignature_roundTripsOnToolCalls() =
    runBlocking<Unit> {
      // Gemini rejects a history whose tool call lost the signature it was sent with.
      val signed =
        ChatToolCall(
          index = 0,
          id = "call_1",
          type = "function",
          function = ChatFunctionCall(name = "f", arguments = "{}"),
          extraContent = ChatExtraContent(GoogleExtraContent(thoughtSignature = "AQID")),
        )
      val unary =
        ChatCompletionResponse(
            choices = listOf(ChatChoice(message = ChatMessage(toolCalls = listOf(signed))))
          )
          .toLlmResponse()
      val streamed =
        ChatCompletions(
            FakeChatCompletionsClient(
              chunks = listOf(chunk(ChatChoice(delta = ChatMessage(toolCalls = listOf(signed)))))
            ),
            "gpt-4o",
          )
          .generateContent(userRequest("Hi"), stream = true)
          .toList()
          .last()

      for (response in listOf(unary, streamed)) {
        val part = response.content!!.parts.single()
        assertThat(part.thoughtSignature).isEqualTo(byteArrayOf(1, 2, 3))
        val sent = Content(role = Role.MODEL, parts = listOf(part)).toMessages()
        assertThat(sent.single().toolCalls!!.single().extraContent!!.google!!.thoughtSignature)
          .isEqualTo("AQID")
      }
    }

  @Test
  fun toLlmResponse_reportsNoChoicesAsError() {
    val llm = ChatCompletionResponse(model = "gpt-4o").toLlmResponse()

    assertThat(llm.content).isNull()
    assertThat(llm.finishReason).isEqualTo(FinishReason.OTHER)
    assertThat(llm.errorCode).isEqualTo("OTHER")
  }

  @Test
  fun toLlmResponse_mapsContentToolCallUsageAndFinishReason() {
    val response =
      ChatCompletionResponse(
        model = "gpt-4o",
        choices =
          listOf(
            ChatChoice(
              message =
                ChatMessage(
                  role = "assistant",
                  content = JsonPrimitive("Hello"),
                  toolCalls =
                    listOf(
                      ChatToolCall(
                        id = "call_1",
                        type = "function",
                        function = ChatFunctionCall(name = "f", arguments = "{\"x\":\"y\"}"),
                      )
                    ),
                ),
              finishReason = "tool_calls",
            )
          ),
        usage =
          ChatUsage(
            promptTokens = 10,
            completionTokens = 5,
            totalTokens = 15,
            promptTokensDetails = PromptTokensDetails(cachedTokens = 4),
            completionTokensDetails = CompletionTokensDetails(reasoningTokens = 2),
          ),
      )

    val llm = response.toLlmResponse()

    val parts = llm.content!!.parts
    assertThat(parts).hasSize(2)
    assertThat(parts[0].text).isEqualTo("Hello")
    val call = parts[1].functionCall!!
    assertThat(call.name).isEqualTo("f")
    assertThat(call.id).isEqualTo("call_1")
    assertThat(call.args["x"]).isEqualTo("y")
    assertThat(llm.finishReason).isEqualTo(FinishReason.STOP)
    val usage = llm.usageMetadata!!
    assertThat(usage.promptTokenCount).isEqualTo(10)
    assertThat(usage.cachedContentTokenCount).isEqualTo(4)
    assertThat(usage.candidatesTokenCount).isEqualTo(5)
    assertThat(usage.thoughtsTokenCount).isEqualTo(2)
    assertThat(usage.totalTokenCount).isEqualTo(15)
    assertThat(llm.modelVersion).isEqualTo("gpt-4o")
  }

  @Test
  fun toFinishReason_mapsProviderReasonsToAdkReasons() {
    assertThat("stop".toFinishReason()).isEqualTo(FinishReason.STOP)
    assertThat("tool_calls".toFinishReason()).isEqualTo(FinishReason.STOP)
    assertThat("function_call".toFinishReason()).isEqualTo(FinishReason.STOP)
    assertThat("length".toFinishReason()).isEqualTo(FinishReason.MAX_TOKENS)
    assertThat("content_filter".toFinishReason()).isEqualTo(FinishReason.SAFETY)
    assertThat(null.toFinishReason()).isNull()
    assertThat("".toFinishReason()).isNull()
    assertThat("something_new".toFinishReason()).isEqualTo(FinishReason.FINISH_REASON_UNSPECIFIED)
    assertThat(null.toFinishReason(droppedMalformedCall = true))
      .isEqualTo(FinishReason.MALFORMED_FUNCTION_CALL)
    // A truncated call is reported as the truncation, not as malformed.
    assertThat("length".toFinishReason(droppedMalformedCall = true))
      .isEqualTo(FinishReason.MAX_TOKENS)
  }

  @Test
  fun generateContent_streaming_ignoresEmptyFinishReasonUntilTheRealOne() =
    runBlocking<Unit> {
      fun fragment(arguments: String, finishReason: String?) =
        chunk(
          ChatChoice(
            delta =
              ChatMessage(
                toolCalls =
                  listOf(
                    ChatToolCall(index = 0, function = ChatFunctionCall(arguments = arguments))
                  )
              ),
            finishReason = finishReason,
          )
        )
      val chunks =
        listOf(
          chunk(
            ChatChoice(
              delta =
                ChatMessage(
                  toolCalls =
                    listOf(
                      ChatToolCall(
                        index = 0,
                        id = "call_1",
                        function = ChatFunctionCall(name = "f"),
                      )
                    )
                ),
              finishReason = "",
            )
          ),
          fragment("{\"a\":", ""),
          fragment("1}", "tool_calls"),
        )

      val final =
        ChatCompletions(FakeChatCompletionsClient(chunks = chunks), "gpt-4o")
          .generateContent(userRequest("Hi"), stream = true)
          .toList()
          .last()

      assertThat(final.content!!.parts.single().functionCall!!.args).containsExactly("a", 1.0)
      assertThat(final.finishReason).isEqualTo(FinishReason.STOP)
    }

  @Test
  fun generateContent_streaming_separatesToolCallsWithoutIndex() =
    runBlocking<Unit> {
      // Without `index`, each non-empty id starts a new call and other deltas continue the last.
      fun call(id: String?, name: String?, arguments: String) =
        ChatToolCall(id = id, function = ChatFunctionCall(name = name, arguments = arguments))
      val chunks =
        listOf(
          chunk(
            ChatChoice(
              delta =
                ChatMessage(toolCalls = listOf(call("a", "f", "{}"), call("b", "g", "{\"x\":")))
            )
          ),
          // A repeated or empty id continues the call too.
          chunk(ChatChoice(delta = ChatMessage(toolCalls = listOf(call("b", null, "2"))))),
          chunk(ChatChoice(delta = ChatMessage(toolCalls = listOf(call("", null, "}"))))),
          chunk(ChatChoice(delta = ChatMessage(toolCalls = listOf(call("c", "h", "{\"y\":"))))),
          chunk(ChatChoice(delta = ChatMessage(toolCalls = listOf(call(null, null, "3}"))))),
          chunk(ChatChoice(delta = ChatMessage(), finishReason = "tool_calls")),
        )

      val calls =
        ChatCompletions(FakeChatCompletionsClient(chunks = chunks), "gpt-4o")
          .generateContent(userRequest("Hi"), stream = true)
          .toList()
          .last()
          .content!!
          .parts
          .map { it.functionCall!! }

      assertThat(calls.map { it.id }).containsExactly("a", "b", "c").inOrder()
      assertThat(calls[1].args).containsExactly("x", 2.0)
      assertThat(calls[2].args).containsExactly("y", 3.0)
    }

  @Test
  fun toLlmResponse_toleratesLiteralNullArgumentsAndAnEmptyId() {
    val response =
      ChatCompletionResponse(
        model = "gpt-4o",
        choices =
          listOf(
            ChatChoice(
              message =
                ChatMessage(
                  role = "assistant",
                  toolCalls =
                    listOf(
                      ChatToolCall(
                        id = "",
                        type = "function",
                        function = ChatFunctionCall(name = "f", arguments = "null"),
                      )
                    ),
                ),
              finishReason = "tool_calls",
            )
          ),
      )

    val call = response.toLlmResponse().content!!.parts.single().functionCall!!
    assertThat(call.name).isEqualTo("f")
    assertThat(call.args).isEmpty()
    assertThat(call.id).isNull()
  }

  @Test
  fun generateContent_dropsAToolCallWithoutAName() =
    runBlocking<Unit> {
      val nameless =
        ChatToolCall(
          index = 0,
          id = "call_1",
          type = "function",
          function = ChatFunctionCall(arguments = "{}"),
        )
      val unary =
        ChatCompletionResponse(
            choices =
              listOf(
                ChatChoice(
                  message = ChatMessage(toolCalls = listOf(nameless)),
                  finishReason = "tool_calls",
                )
              )
          )
          .toLlmResponse()
      val chunks =
        listOf(
          chunk(
            ChatChoice(
              delta = ChatMessage(toolCalls = listOf(nameless)),
              finishReason = "tool_calls",
            )
          )
        )
      val streamed =
        ChatCompletions(FakeChatCompletionsClient(chunks = chunks), "gpt-4o")
          .generateContent(userRequest("Hi"), stream = true)
          .toList()
          .last()

      for (response in listOf(unary, streamed)) {
        assertThat(response.content).isNull()
        assertThat(response.finishReason).isEqualTo(FinishReason.MALFORMED_FUNCTION_CALL)
      }
    }

  @Test
  fun toLlmResponse_joinsTheTextPartsOfArrayContent() {
    fun part(type: String, value: String) =
      JsonObject(mapOf("type" to JsonPrimitive(type), type to JsonPrimitive(value)))
    val content = JsonArray(listOf(part("text", "Hel"), part("refusal", "no"), part("text", "lo")))
    val response =
      ChatCompletionResponse(
        choices =
          listOf(ChatChoice(message = ChatMessage(content = content), finishReason = "stop"))
      )

    assertThat(response.toLlmResponse().content!!.parts.single().text).isEqualTo("Hello")
  }

  @Test
  fun toLlmResponse_dropsCallWithMalformedArgumentsAndReportsIt() {
    val response =
      ChatCompletionResponse(
        model = "gpt-4o",
        choices =
          listOf(
            ChatChoice(
              message =
                ChatMessage(
                  role = "assistant",
                  toolCalls =
                    listOf(
                      ChatToolCall(
                        id = "call_1",
                        type = "function",
                        function = ChatFunctionCall(name = "f", arguments = "{\"city\":"),
                      )
                    ),
                ),
              finishReason = "tool_calls",
            )
          ),
      )

    val llm = response.toLlmResponse()

    // The tool must not run with arguments the model never finished writing.
    assertThat(llm.content).isNull()
    assertThat(llm.finishReason).isEqualTo(FinishReason.MALFORMED_FUNCTION_CALL)
    // Unlike Python, a finish other than STOP is also reported as an error.
    assertThat(llm.errorCode).isEqualTo("MALFORMED_FUNCTION_CALL")
  }

  @Test
  fun toChatCompletionRequest_pairsToolCallIdsMissingFromHistory() {
    // Calls made by another model can reach this backend without ids, or with empty ones.
    fun call(id: String?) =
      Part(functionCall = FunctionCall(name = "f", args = emptyMap(), id = id))
    fun result(id: String?, name: String = "f") =
      Part(functionResponse = FunctionResponse(name = name, response = mapOf(), id = id))
    val request =
      LlmRequest(
        contents =
          listOf(
            Content(role = Role.MODEL, parts = listOf(call(null), call(""))),
            Content(role = Role.USER, parts = listOf(result(null), result(""), result(null, "g"))),
          )
      )

    val messages = request.toChatCompletionRequest("gpt-4o").messages

    assertThat(messages[0].toolCalls!!.map { it.id })
      .containsExactly("call_fallback_0", "call_fallback_1")
      .inOrder()
    // Results pair with calls of the same name in order; one with no waiting call uses its name.
    assertThat(messages.drop(1).map { it.toolCallId })
      .containsExactly("call_fallback_0", "call_fallback_1", "g")
      .inOrder()
  }

  @Test
  fun generateContent_streaming_reportsMalformedCallOnToolCallsFinish() =
    runBlocking<Unit> {
      val chunks =
        listOf(
          chunk(
            ChatChoice(
              delta =
                ChatMessage(
                  toolCalls =
                    listOf(
                      ChatToolCall(
                        index = 0,
                        id = "call_9",
                        type = "function",
                        function = ChatFunctionCall(name = "ping", arguments = "{not json"),
                      )
                    )
                ),
              finishReason = "tool_calls",
            )
          )
        )
      val chatCompletions = ChatCompletions(FakeChatCompletionsClient(chunks = chunks), "gpt-4o")

      val final =
        chatCompletions.generateContent(userRequest("ping"), stream = true).toList().last()

      assertThat(final.content).isNull()
      assertThat(final.finishReason).isEqualTo(FinishReason.MALFORMED_FUNCTION_CALL)
    }

  @Test
  fun generateContent_streaming_dropsTruncatedToolCall() =
    runBlocking<Unit> {
      val chunks =
        listOf(
          chunk(
            ChatChoice(
              delta =
                ChatMessage(
                  toolCalls =
                    listOf(
                      ChatToolCall(
                        index = 0,
                        id = "call_7",
                        type = "function",
                        function =
                          ChatFunctionCall(name = "get_weather", arguments = "{\"city\":\"Pa"),
                      )
                    )
                ),
              finishReason = "length",
            )
          )
        )
      val chatCompletions = ChatCompletions(FakeChatCompletionsClient(chunks = chunks), "gpt-4o")

      val final =
        chatCompletions.generateContent(userRequest("Weather?"), stream = true).toList().last()

      assertThat(final.content).isNull()
      assertThat(final.finishReason).isEqualTo(FinishReason.MAX_TOKENS)
    }

  @Test
  fun generateContent_streaming_reportsMalformedCallWhenStreamEndsWithoutFinishReason() =
    runBlocking<Unit> {
      val chunks =
        listOf(
          chunk(
            ChatChoice(
              delta =
                ChatMessage(
                  toolCalls =
                    listOf(
                      ChatToolCall(
                        index = 0,
                        id = "call_8",
                        type = "function",
                        function = ChatFunctionCall(name = "ping", arguments = "{\"a\""),
                      )
                    )
                )
            )
          )
        )
      val chatCompletions = ChatCompletions(FakeChatCompletionsClient(chunks = chunks), "gpt-4o")

      val final =
        chatCompletions.generateContent(userRequest("ping"), stream = true).toList().last()

      assertThat(final.content).isNull()
      assertThat(final.finishReason).isEqualTo(FinishReason.MALFORMED_FUNCTION_CALL)
    }

  @Test
  fun generateContent_streaming_throwsOnInStreamError() {
    val chunks = listOf(ChatCompletionResponse(error = ChatError(type = "server_error")))
    val chatCompletions = ChatCompletions(FakeChatCompletionsClient(chunks = chunks), "gpt-4o")

    val exception =
      assertFailsWith<ChatCompletionsApiException> {
        runBlocking { chatCompletions.generateContent(userRequest("Hi"), stream = true).toList() }
      }

    assertThat(exception).hasMessageThat().isEqualTo("Chat Completions stream error: server_error")
  }

  @Test
  fun generateContent_unary_returnsSingleResponse() = runBlocking {
    val client =
      FakeChatCompletionsClient(
        response =
          ChatCompletionResponse(
            model = "gpt-4o",
            choices =
              listOf(
                ChatChoice(
                  message = ChatMessage(role = "assistant", content = JsonPrimitive("Hi there")),
                  finishReason = "stop",
                )
              ),
            usage = ChatUsage(promptTokens = 3, completionTokens = 2, totalTokens = 5),
          )
      )
    val chatCompletions = ChatCompletions(client, "gpt-4o")

    val responses = chatCompletions.generateContent(userRequest("Hi"), stream = false).toList()

    assertThat(responses).hasSize(1)
    assertThat(responses[0].content!!.parts[0].text).isEqualTo("Hi there")
    assertThat(responses[0].finishReason).isEqualTo(FinishReason.STOP)
  }

  @Test
  fun generateContent_streaming_aggregatesTextAndToolCall() = runBlocking {
    val chunks =
      listOf(
        chunk(ChatChoice(delta = ChatMessage(role = "assistant", content = JsonPrimitive("Hel")))),
        chunk(ChatChoice(delta = ChatMessage(content = JsonPrimitive("lo")))),
        chunk(
          ChatChoice(
            delta =
              ChatMessage(
                toolCalls =
                  listOf(
                    ChatToolCall(
                      index = 0,
                      id = "call_9",
                      type = "function",
                      function = ChatFunctionCall(name = "get_weather", arguments = "{\"city\":"),
                    )
                  )
              )
          )
        ),
        chunk(
          ChatChoice(
            delta =
              ChatMessage(
                toolCalls =
                  listOf(
                    ChatToolCall(index = 0, function = ChatFunctionCall(arguments = "\"Paris\"}"))
                  )
              )
          )
        ),
        chunk(ChatChoice(delta = ChatMessage(), finishReason = "tool_calls")),
        ChatCompletionResponse(
          usage = ChatUsage(promptTokens = 7, completionTokens = 4, totalTokens = 11)
        ),
      )
    val chatCompletions = ChatCompletions(FakeChatCompletionsClient(chunks = chunks), "gpt-4o")

    val responses =
      chatCompletions.generateContent(userRequest("Weather in Paris?"), stream = true).toList()

    // "Hel", "lo", the tool call, then the final response; no partial for finish or usage.
    assertThat(responses.map { it.partial }).containsExactly(true, true, true, false).inOrder()
    val partialParts = responses.dropLast(1).map { it.content!!.parts.single() }
    assertThat(partialParts.map { it.text }).containsExactly("Hel", "lo", null).inOrder()
    assertThat(partialParts[2].functionCall!!.name).isEqualTo("get_weather")
    assertThat(responses.dropLast(1).map { it.finishReason }).containsExactly(null, null, null)
    val finalResponse = responses.last()
    val parts = finalResponse.content!!.parts
    assertThat(parts.mapNotNull { it.text }).contains("Hello")
    val call = parts.mapNotNull { it.functionCall }.single()
    assertThat(call.name).isEqualTo("get_weather")
    assertThat(call.id).isEqualTo("call_9")
    assertThat(call.args["city"]).isEqualTo("Paris")
    assertThat(finalResponse.finishReason).isEqualTo(FinishReason.STOP)
    val usage = finalResponse.usageMetadata!!
    assertThat(usage.promptTokenCount).isEqualTo(7)
    assertThat(usage.candidatesTokenCount).isEqualTo(4)
  }

  @Test
  fun generateContent_streaming_flushesToolCallWhenStreamEndsWithoutFinishReason() = runBlocking {
    // No finish_reason chunk follows the tool call; it must still be flushed at stream end.
    val chunks =
      listOf(
        chunk(
          ChatChoice(
            delta =
              ChatMessage(
                toolCalls =
                  listOf(
                    ChatToolCall(
                      index = 0,
                      id = "call_5",
                      type = "function",
                      function = ChatFunctionCall(name = "ping", arguments = "{}"),
                    )
                  )
              )
          )
        )
      )
    val chatCompletions = ChatCompletions(FakeChatCompletionsClient(chunks = chunks), "gpt-4o")

    val responses = chatCompletions.generateContent(userRequest("ping"), stream = true).toList()

    val call = responses.last().content!!.parts.mapNotNull { it.functionCall }.single()
    assertThat(call.name).isEqualTo("ping")
    assertThat(call.id).isEqualTo("call_5")
  }

  @Test
  fun generateContent_wrapsQuotaError() {
    val quotaError =
      ChatCompletionsApiException("Chat Completions API error: HTTP 429", statusCode = 429)
    val chatCompletions = ChatCompletions(FakeChatCompletionsClient(error = quotaError), "gpt-4o")

    val exception =
      assertFailsWith<ChatCompletionsApiException> {
        runBlocking { chatCompletions.generateContent(userRequest("Hi"), stream = false).toList() }
      }
    assertThat(exception.statusCode).isEqualTo(429)
    assertThat(exception).hasMessageThat().contains("rate limit")
    assertThat(exception).hasMessageThat().contains("HTTP 429")
    assertThat(exception).hasCauseThat().isSameInstanceAs(quotaError)
  }

  /** The messages one content becomes, converted the way a request converts its history. */
  private fun Content.toMessages(): List<ChatMessage> =
    LlmRequest(contents = listOf(this)).toChatCompletionRequest("gpt-4o").messages

  private fun userRequest(text: String): LlmRequest =
    LlmRequest(contents = listOf(Content(role = Role.USER, parts = listOf(Part(text = text)))))

  private fun chunk(choice: ChatChoice): ChatCompletionResponse =
    ChatCompletionResponse(model = "gpt-4o", choices = listOf(choice))
}

/** In-memory [ChatCompletionsClient] that returns canned responses or chunks, or throws [error]. */
private class FakeChatCompletionsClient(
  private val response: ChatCompletionResponse? = null,
  private val chunks: List<ChatCompletionResponse> = emptyList(),
  private val error: ChatCompletionsApiException? = null,
) : ChatCompletionsClient {
  override suspend fun create(request: ChatCompletionRequest): ChatCompletionResponse {
    error?.let { throw it }
    return response!!
  }

  override fun createStream(request: ChatCompletionRequest): Flow<ChatCompletionResponse> = flow {
    error?.let { throw it }
    chunks.forEach { emit(it) }
  }
}
