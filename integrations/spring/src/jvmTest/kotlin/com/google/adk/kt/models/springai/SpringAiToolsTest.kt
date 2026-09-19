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
import org.springframework.ai.google.genai.GoogleGenAiChatOptions
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
}
