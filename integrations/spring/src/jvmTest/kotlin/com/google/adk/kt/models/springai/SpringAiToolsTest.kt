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
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.GenerateContentConfig
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Tool
import com.google.adk.kt.types.Type
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.google.genai.GoogleGenAiChatOptions
import org.springframework.ai.model.tool.StructuredOutputChatOptions
import org.springframework.ai.model.tool.ToolCallingChatOptions

class SpringAiToolsTest {

  @Test
  fun objectSchema_usesLowercaseJsonSchemaTypes() {
    val schema =
      Schema(
        type = Type.OBJECT,
        description = "A weather query",
        properties =
          mapOf(
            "city" to Schema(type = Type.STRING, description = "City name"),
            "days" to Schema(type = Type.INTEGER),
          ),
        required = listOf("city"),
      )

    val json = schema.toJsonSchemaString()

    assertThat(json).contains("\"type\":\"object\"")
    assertThat(json).contains("\"type\":\"string\"")
    assertThat(json).contains("\"type\":\"integer\"")
    assertThat(json).contains("\"description\":\"City name\"")
    assertThat(json).contains("\"required\":[\"city\"]")
  }

  @Test
  fun arraySchema_emitsItems() {
    val schema = Schema(type = Type.ARRAY, items = Schema(type = Type.STRING))

    val json = schema.toJsonSchemaString()

    assertThat(json).contains("\"type\":\"array\"")
    assertThat(json).contains("\"items\":{\"type\":\"string\"}")
  }

  @Test
  fun schema_emitsAnyOfSubschemas() {
    val schema = Schema(anyOf = listOf(Schema(type = Type.STRING), Schema(type = Type.INTEGER)))

    val json = schema.toJsonSchemaString()

    assertThat(json).contains("\"anyOf\":[{\"type\":\"string\"},{\"type\":\"integer\"}]")
  }

  @Test
  fun schema_emitsNumericConstraints() {
    val json = Schema(type = Type.INTEGER, minimum = 1.0, maximum = 10.0).toJsonSchemaString()

    assertThat(json).contains("\"minimum\":1.0")
    assertThat(json).contains("\"maximum\":10.0")
  }

  @Test
  fun toolCallbacks_carrySchemaOnly_andThrowIfSpringInvokesThem() {
    val request =
      LlmRequest(
        config =
          GenerateContentConfig(
            tools =
              listOf(
                Tool(
                  functionDeclarations =
                    listOf(
                      FunctionDeclaration(
                        name = "get_weather",
                        description = "Get weather.",
                        parameters =
                          Schema(
                            type = Type.OBJECT,
                            properties = mapOf("city" to Schema(type = Type.STRING)),
                            required = listOf("city"),
                          ),
                      )
                    )
                )
              )
          )
      )

    val callbacks = request.toolCallbacks()

    assertThat(callbacks).hasSize(1)
    assertThat(callbacks[0].toolDefinition.name()).isEqualTo("get_weather")
    assertThat(callbacks[0].toolDefinition.inputSchema()).contains("\"city\"")
    // ADK owns execution, so Spring AI must never invoke the callback body: it throws if it does.
    val error = runCatching { callbacks[0].call("{\"city\":\"Paris\"}") }.exceptionOrNull()
    assertThat(error).isNotNull()
  }

  @Test
  fun buildChatOptions_returnsNull_whenNoToolsOrGenerationConfig() {
    assertThat(buildChatOptions(GenerateContentConfig(), emptyList(), defaultOptions = null))
      .isNull()
  }

  @Test
  fun buildChatOptions_appliesToolsAndGenerationConfig() {
    val callbacks =
      LlmRequest(
          config =
            GenerateContentConfig(
              tools =
                listOf(
                  Tool(
                    functionDeclarations =
                      listOf(FunctionDeclaration(name = "t", description = "d"))
                  )
                )
            )
        )
        .toolCallbacks()

    val options =
      buildChatOptions(
        GenerateContentConfig(temperature = 0.5f),
        callbacks,
        defaultOptions = null,
      )!!

    assertThat(options.temperature).isEqualTo(0.5)
    assertThat((options as ToolCallingChatOptions).toolCallbacks).hasSize(1)
  }

  @Test
  fun buildChatOptions_preservesProviderOptionTypeViaMutate() {
    val callbacks =
      LlmRequest(
          config =
            GenerateContentConfig(
              tools =
                listOf(
                  Tool(
                    functionDeclarations =
                      listOf(FunctionDeclaration(name = "t", description = "d"))
                  )
                )
            )
        )
        .toolCallbacks()
    val providerDefaults = GoogleGenAiChatOptions.builder().model("gemini-flash-latest").build()

    val options =
      buildChatOptions(
        GenerateContentConfig(temperature = 0.25f),
        callbacks,
        defaultOptions = providerDefaults,
      )!!

    // The concrete provider option type survives (the mutate() branch), rather than being replaced
    // by a generic ToolCallingChatOptions, and the ADK tools + config are overlaid onto it.
    assertThat(options).isInstanceOf(GoogleGenAiChatOptions::class.java)
    assertThat(options.model).isEqualTo("gemini-flash-latest")
    assertThat(options.temperature).isEqualTo(0.25)
    assertThat((options as ToolCallingChatOptions).toolCallbacks).hasSize(1)
  }

  @Test
  fun buildChatOptions_generationConfigOnly_nonToolCallingDefaults_preservesModel() {
    // A plain (non-tool-calling) ChatOptions carrying a model must not be discarded when only
    // generation config is applied.
    val defaults = ChatOptions.builder().model("gemini-flash-latest").build()

    val options =
      buildChatOptions(
        GenerateContentConfig(temperature = 0.75f),
        emptyList(),
        defaultOptions = defaults,
      )!!

    assertThat(options.model).isEqualTo("gemini-flash-latest")
    assertThat(options.temperature).isEqualTo(0.75)
  }

  @Test
  fun buildChatOptions_toolsWithNonToolCallingDefaults_carriesModel() {
    // Tools force a fresh ToolCallingChatOptions, but the model from a non-tool-calling default
    // must
    // still carry over so the request targets the configured model.
    val defaults = ChatOptions.builder().model("gemini-flash-latest").build()

    val options =
      buildChatOptions(GenerateContentConfig(), toolCallbacksFor("t"), defaultOptions = defaults)!!

    assertThat(options.model).isEqualTo("gemini-flash-latest")
    assertThat((options as ToolCallingChatOptions).toolCallbacks).hasSize(1)
  }

  @Test
  fun buildChatOptions_noTools_clearsToolCallbacksFromDefaults() {
    // Callbacks carried by the model's own defaults must not leak into a request that declares
    // none.
    val defaults =
      GoogleGenAiChatOptions.builder()
        .model("gemini-flash-latest")
        .toolCallbacks(toolCallbacksFor("stale"))
        .build()

    val options =
      buildChatOptions(
        GenerateContentConfig(temperature = 0.125f),
        emptyList(),
        defaultOptions = defaults,
      )!!

    assertThat((options as ToolCallingChatOptions).toolCallbacks).isEmpty()
    assertThat(options.temperature).isEqualTo(0.125)
  }

  @Test
  fun buildChatOptions_noToolsNoConfig_clearsStaleDefaultToolCallbacks() {
    // With neither ADK tools nor generation config, stale callbacks on the model's own defaults
    // must still be cleared rather than left for the early null-return to leak into the request.
    val defaults =
      GoogleGenAiChatOptions.builder()
        .model("gemini-flash-latest")
        .toolCallbacks(toolCallbacksFor("stale"))
        .build()

    val options =
      buildChatOptions(GenerateContentConfig(), emptyList(), defaultOptions = defaults)!!

    assertThat((options as ToolCallingChatOptions).toolCallbacks).isEmpty()
  }

  @Test
  fun schema_emitsDefaultValue() {
    // Reuse of ADK core's converter also brings `default`, which the previous fork dropped.
    val json = Schema(type = Type.STRING, default = "hello").toJsonSchemaString()

    assertThat(json).contains("\"default\":\"hello\"")
  }

  @Test
  fun buildChatOptions_responseSchema_mapsToOutputSchemaOnStructuredProvider() {
    val defaults = GoogleGenAiChatOptions.builder().model("gemini-flash-latest").build()
    val responseSchema =
      Schema(
        type = Type.OBJECT,
        properties = mapOf("answer" to Schema(type = Type.STRING)),
        required = listOf("answer"),
      )

    val options =
      buildChatOptions(
        GenerateContentConfig(responseSchema = responseSchema),
        emptyList(),
        defaultOptions = defaults,
      )!!

    // The ADK response schema reaches the provider through Spring AI's portable outputSchema.
    assertThat((options as StructuredOutputChatOptions).outputSchema).contains("\"answer\"")
  }

  @Test
  fun buildChatOptions_responseSchemaOnly_isNotNull() {
    // responseSchema alone must keep buildChatOptions from returning null (hasGenerationParams).
    val responseSchema =
      Schema(type = Type.OBJECT, properties = mapOf("a" to Schema(type = Type.STRING)))

    assertThat(
        buildChatOptions(
          GenerateContentConfig(responseSchema = responseSchema),
          emptyList(),
          defaultOptions = null,
        )
      )
      .isNotNull()
  }

  private fun toolCallbacksFor(vararg names: String) =
    LlmRequest(
        config =
          GenerateContentConfig(
            tools =
              listOf(
                Tool(
                  functionDeclarations =
                    names.map { FunctionDeclaration(name = it, description = "d") }
                )
              )
          )
      )
      .toolCallbacks()
}
