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

import com.anthropic.core.JsonValue
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.ThinkingConfigAdaptive
import com.google.adk.kt.agents.ContextCacheConfig
import com.google.adk.kt.annotations.ExperimentalContextCachingFeature
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FinishReason
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.GenerateContentConfig
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.ThinkingConfig
import com.google.adk.kt.types.Tool
import com.google.adk.kt.types.Type
import com.google.common.truth.Truth.assertThat
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.hours
import org.junit.Test

@OptIn(ExperimentalContextCachingFeature::class)
class AnthropicConvertersTest {

  @Test
  fun toToolResultText_followsPythonAdkRendering() {
    assertThat(mapOf("result" to "sunny").toToolResultText()).isEqualTo("sunny")
    assertThat(mapOf("result" to 21).toToolResultText()).isEqualTo("21")
    assertThat(mapOf("result" to mapOf("temp" to 21)).toToolResultText())
      .isEqualTo("""{"temp":21}""")
    assertThat(mapOf("content" to "done").toToolResultText()).isEqualTo("done")
    assertThat(
        mapOf("content" to listOf(mapOf("type" to "text", "text" to "a"), "b")).toToolResultText()
      )
      .isEqualTo("a\nb")
    assertThat(mapOf("temp" to 21, "unit" to "C").toToolResultText())
      .isEqualTo("""{"temp":21,"unit":"C"}""")
    assertThat(emptyMap<String, Any?>().toToolResultText()).isEmpty()
  }

  @Test
  fun toFinishReason_matchesPythonAdkMapping() {
    assertThat(StopReason.END_TURN.toFinishReason()).isEqualTo(FinishReason.STOP)
    assertThat(StopReason.TOOL_USE.toFinishReason()).isEqualTo(FinishReason.STOP)
    assertThat(StopReason.MAX_TOKENS.toFinishReason()).isEqualTo(FinishReason.MAX_TOKENS)
    assertThat(StopReason.REFUSAL.toFinishReason()).isEqualTo(FinishReason.SAFETY)
    assertThat(null.toFinishReason()).isNull()
    assertThat(StopReason.MODEL_CONTEXT_WINDOW_EXCEEDED.toFinishReason())
      .isEqualTo(FinishReason.FINISH_REASON_UNSPECIFIED)
    assertThat(StopReason.of("new_reason").toFinishReason())
      .isEqualTo(FinishReason.FINISH_REASON_UNSPECIFIED)
  }

  @Test
  fun toMessageCreateParams_mapsThinkingBudgetsAsPythonAdkDoes() {
    assertThat(params(thinkingBudget = 0).thinking().get().isDisabled()).isTrue()
    val adaptive = params(thinkingBudget = -1).thinking().get().asAdaptive()
    assertThat(adaptive.display().get()).isEqualTo(ThinkingConfigAdaptive.Display.SUMMARIZED)
    assertThat(params(thinkingBudget = 2048).thinking().get().asEnabled().budgetTokens())
      .isEqualTo(2048)
    assertThat(params().thinking()).isEmpty()
  }

  @Test
  fun toMessageCreateParams_requiresThinkingBudgetWhenThinkingIsConfigured() {
    val request =
      LlmRequest(
        contents = listOf(userText("Hi")),
        config = GenerateContentConfig(thinkingConfig = ThinkingConfig(includeThoughts = true)),
      )

    assertFailsWith<IllegalArgumentException> { request.toParams() }
  }

  @Suppress("DEPRECATION")
  @Test
  fun toMessageCreateParams_dropsSamplingWhileThinkingOrEffortIsOn() {
    assertThat(params(temperature = 0.5f).temperature()).isPresent()
    assertThat(params(temperature = 0.5f, thinkingBudget = 2048).temperature()).isEmpty()
    assertThat(params(temperature = 0.5f, effort = OutputConfig.Effort.LOW).temperature()).isEmpty()
    assertThat(params(temperature = 0.5f, thinkingBudget = 0).temperature()).isPresent()
  }

  @Test
  fun toMessageCreateParams_sendsEffortAndResponseSchemaInOutputConfig() {
    val schema =
      Schema(type = Type.OBJECT, properties = mapOf("answer" to Schema(type = Type.STRING)))

    val outputConfig =
      params(effort = OutputConfig.Effort.HIGH, responseSchema = schema).outputConfig().get()

    assertThat(outputConfig.effort().get()).isEqualTo(OutputConfig.Effort.HIGH)
    val jsonSchema = outputConfig.format().get().schema()._additionalProperties()
    assertThat(jsonSchema["type"]!!.asString().get()).isEqualTo("object")
    assertThat(jsonSchema["properties"]!!.asObject().get().keys).containsExactly("answer")
    assertThat(params().outputConfig()).isEmpty()
  }

  @Test
  fun toMessageCreateParams_forbidsUndeclaredPropertiesOnEveryOutputObject() {
    val schema =
      Schema(
        type = Type.OBJECT,
        properties =
          mapOf(
            "items" to
              Schema(
                type = Type.ARRAY,
                items =
                  Schema(
                    type = Type.OBJECT,
                    properties = mapOf("name" to Schema(type = Type.STRING)),
                  ),
              )
          ),
      )

    val jsonSchema = outputSchema(schema)

    assertThat(jsonSchema["additionalProperties"]!!.asBoolean().get()).isFalse()
    val item =
      jsonSchema["properties"]!!
        .asObject()
        .get()["items"]!!
        .asObject()
        .get()["items"]!!
        .asObject()
        .get()
    assertThat(item["additionalProperties"]!!.asBoolean().get()).isFalse()
  }

  @Test
  fun toMessageCreateParams_marksToolsSystemAndLastCacheableBlockForCaching() {
    val request =
      LlmRequest(
        contents =
          listOf(
            userText("Hi"),
            Content(
              role = Role.MODEL,
              parts =
                listOf(
                  Part(text = "Hello"),
                  Part(text = "reasoning", thought = true, thoughtSignature = byteArrayOf(65)),
                ),
            ),
          ),
        config =
          GenerateContentConfig(
            systemInstruction = Content(parts = listOf(Part(text = "Be brief"))),
            tools =
              listOf(
                Tool(
                  functionDeclarations =
                    listOf(
                      FunctionDeclaration(name = "a", description = "A"),
                      FunctionDeclaration(name = "b", description = "B"),
                    )
                )
              ),
          ),
        cacheConfig = ContextCacheConfig(),
      )

    val params = request.toParams()

    val tools = params.tools().get().map { it.asTool() }
    assertThat(tools[0].cacheControl()).isEmpty()
    assertThat(tools[1].cacheControl().get().ttl()).isEmpty()
    assertThat(params.system().get().asTextBlockParams().single().cacheControl()).isPresent()
    val modelTurn = params.messages()[1].content().asBlockParams()
    // The trailing thinking block cannot take a breakpoint, so it goes on the text before it.
    assertThat(modelTurn[0].asText().cacheControl()).isPresent()
    assertThat(modelTurn[1].isThinking()).isTrue()
    assertThat(params.messages()[0].content().asBlockParams()[0].asText().cacheControl()).isEmpty()
  }

  @Test
  fun toMessageCreateParams_asksForTheHourLongCacheForLongTtls() {
    val request =
      LlmRequest(contents = listOf(userText("Hi")), cacheConfig = ContextCacheConfig(ttl = 2.hours))

    val block = request.toParams().messages()[0].content().asBlockParams()[0]

    assertThat(block.asText().cacheControl().get().ttl().get())
      .isEqualTo(CacheControlEphemeral.Ttl.TTL_1H)
  }

  @Test
  fun toMessageCreateParams_skipsCachingBelowMinTokens() {
    val request =
      LlmRequest(
        contents = listOf(userText("Hi")),
        config =
          GenerateContentConfig(
            systemInstruction = Content(parts = listOf(Part(text = "Be brief")))
          ),
        cacheConfig = ContextCacheConfig(minTokens = 1000),
        cacheableContentsTokenCount = 500,
      )

    val params = request.toParams()

    assertThat(params.system().get().isString()).isTrue()
    assertThat(params.messages()[0].content().asBlockParams()[0].asText().cacheControl()).isEmpty()
  }

  @Test
  fun toMessageCreateParams_sendsSignedThoughtsBackAndDropsUnsignedOnes() {
    val request =
      LlmRequest(
        contents =
          listOf(
            userText("Hi"),
            Content(
              role = Role.MODEL,
              parts =
                listOf(
                  Part(text = "plan", thought = true, thoughtSignature = "sig".toByteArray()),
                  Part(thought = true, thoughtSignature = "encrypted".toByteArray()),
                  Part(text = "unsigned", thought = true),
                  Part(text = "Hello"),
                ),
            ),
          )
      )

    val blocks = request.toParams().messages()[1].content().asBlockParams()

    assertThat(blocks).hasSize(3)
    assertThat(blocks[0].asThinking().thinking()).isEqualTo("plan")
    assertThat(blocks[0].asThinking().signature()).isEqualTo("sig")
    assertThat(blocks[1].asRedactedThinking().data()).isEqualTo("encrypted")
    assertThat(blocks[2].asText().text()).isEqualTo("Hello")
  }

  @Test
  fun toMessageCreateParams_readsTheModelIdFromAVertexResourceName() {
    fun modelFor(name: String) =
      LlmRequest(contents = listOf(userText("Hi")))
        .toMessageCreateParams(modelName = name, defaultMaxTokens = 8192, effort = null)
        .model()
        .asString()

    assertThat(modelFor("projects/p/locations/l/publishers/anthropic/models/claude-x"))
      .isEqualTo("claude-x")
    assertThat(modelFor("projects/p/locations/l/endpoints/123")).isEqualTo("123")
    assertThat(modelFor("claude-x")).isEqualTo("claude-x")
  }

  @Test
  fun toMessageCreateParams_movesBoundsClaudeRejectsIntoTheDescription() {
    val schema =
      Schema(
        type = Type.OBJECT,
        properties =
          mapOf(
            "count" to Schema(type = Type.INTEGER, description = "How many", minimum = 1.0),
            "tags" to Schema(type = Type.ARRAY, items = Schema(type = Type.STRING), minItems = 2),
            "notes" to Schema(type = Type.ARRAY, items = Schema(type = Type.STRING), minItems = 1),
          ),
      )

    val properties = outputSchema(schema)["properties"]!!.asObject().get()
    val count = properties["count"]!!.asObject().get()
    val tags = properties["tags"]!!.asObject().get()

    assertThat(count).doesNotContainKey("minimum")
    assertThat(count["description"]!!.asString().get()).isEqualTo("How many (minimum: 1.0)")
    assertThat(tags).doesNotContainKey("minItems")
    assertThat(tags["description"]!!.asString().get()).isEqualTo("(minItems: 2)")
    assertThat(properties["notes"]!!.asObject().get()).containsKey("minItems")
  }

  @Test
  fun toMessageCreateParams_sendsAThoughtFlaggedFunctionCallAsToolUse() {
    val call = FunctionCall(name = "get_weather", args = emptyMap(), id = "call-1")
    val request =
      LlmRequest(
        contents =
          listOf(
            userText("Hi"),
            Content(role = Role.MODEL, parts = listOf(Part(functionCall = call, thought = true))),
          )
      )

    val block = request.toParams().messages()[1].content().asBlockParams().single()

    assertThat(block.asToolUse().id()).isEqualTo("call-1")
  }

  private fun params(
    thinkingBudget: Int? = null,
    temperature: Float? = null,
    responseSchema: Schema? = null,
    effort: OutputConfig.Effort? = null,
  ): MessageCreateParams =
    LlmRequest(
        contents = listOf(userText("Hi")),
        config =
          GenerateContentConfig(
            thinkingConfig = thinkingBudget?.let { ThinkingConfig(thinkingBudget = it) },
            temperature = temperature,
            responseSchema = responseSchema,
          ),
      )
      .toMessageCreateParams(modelName = "claude-x", defaultMaxTokens = 8192, effort = effort)

  private fun outputSchema(schema: Schema): Map<String, JsonValue> =
    params(responseSchema = schema)
      .outputConfig()
      .get()
      .format()
      .get()
      .schema()
      ._additionalProperties()

  private fun LlmRequest.toParams(): MessageCreateParams =
    toMessageCreateParams(modelName = "claude-x", defaultMaxTokens = 8192, effort = null)

  private fun userText(text: String) = Content(role = Role.USER, parts = listOf(Part(text = text)))
}
