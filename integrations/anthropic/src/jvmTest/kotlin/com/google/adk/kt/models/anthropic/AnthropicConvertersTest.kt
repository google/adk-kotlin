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

import com.anthropic.core.jsonMapper
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FinishReason
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.GenerateContentConfig
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import com.google.common.truth.Truth.assertThat
import kotlin.test.assertFailsWith
import org.junit.Test

class AnthropicConvertersTest {

  @Test
  fun toMessageCreateParams_rendersToolResultsAsPythonAdkDoes() {
    fun resultText(response: Map<String, Any?>): String =
      LlmRequest(
          contents =
            listOf(
              Content(
                role = Role.USER,
                parts =
                  listOf(
                    Part(
                      functionResponse =
                        FunctionResponse(name = "f", response = response, id = "call-1")
                    )
                  ),
              )
            )
        )
        .toParams()
        .messages()
        .single()
        .content()
        .asBlockParams()
        .single()
        .asToolResult()
        .content()
        .get()
        .asString()

    assertThat(resultText(mapOf("result" to "sunny"))).isEqualTo("sunny")
    assertThat(resultText(mapOf("result" to 21))).isEqualTo("21")
    assertThat(resultText(mapOf("result" to mapOf("temp" to 21)))).isEqualTo("""{"temp":21}""")
    assertThat(resultText(mapOf("content" to "done"))).isEqualTo("done")
    assertThat(resultText(mapOf("content" to listOf(mapOf("type" to "text", "text" to "a"), "b"))))
      .isEqualTo("a\nb")
    assertThat(resultText(mapOf("temp" to 21, "unit" to "C")))
      .isEqualTo("""{"temp":21,"unit":"C"}""")
    assertThat(resultText(emptyMap())).isEmpty()
  }

  @Test
  fun toLlmResponse_mapsStopReasonsAsPythonAdkDoes() {
    fun finishReason(stopReason: String?): FinishReason? =
      message(stopReason).toLlmResponse().finishReason

    assertThat(finishReason("end_turn")).isEqualTo(FinishReason.STOP)
    assertThat(finishReason("tool_use")).isEqualTo(FinishReason.STOP)
    assertThat(finishReason("max_tokens")).isEqualTo(FinishReason.MAX_TOKENS)
    assertThat(finishReason("refusal")).isEqualTo(FinishReason.SAFETY)
    assertThat(finishReason(null)).isNull()
    assertThat(finishReason("model_context_window_exceeded"))
      .isEqualTo(FinishReason.FINISH_REASON_UNSPECIFIED)
    assertThat(finishReason("new_reason")).isEqualTo(FinishReason.FINISH_REASON_UNSPECIFIED)
  }

  @Test
  fun toMessageCreateParams_rejectsAnOutputSchema() {
    val request =
      LlmRequest(
        contents = listOf(Content.fromText(Role.USER, "Hi")),
        config = GenerateContentConfig(responseSchema = Schema(type = Type.OBJECT)),
      )

    assertFailsWith<IllegalArgumentException> { request.toParams() }
  }

  private fun LlmRequest.toParams(): MessageCreateParams =
    toMessageCreateParams(modelName = "claude-x", defaultMaxTokens = 8192)

  private fun message(stopReason: String?): Message =
    jsonMapper()
      .readValue(
        """{"id":"msg_1","type":"message","role":"assistant","model":"claude-x",""" +
          """"content":[{"type":"text","text":"ok"}],""" +
          """"stop_reason":${stopReason?.let { "\"$it\"" } ?: "null"},"stop_sequence":null,""" +
          """"usage":{"input_tokens":3,"output_tokens":2}}""",
        Message::class.java,
      )
}
