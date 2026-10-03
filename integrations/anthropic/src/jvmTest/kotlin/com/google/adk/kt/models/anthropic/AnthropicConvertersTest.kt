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

import com.anthropic.models.messages.StopReason
import com.google.adk.kt.types.FinishReason
import com.google.common.truth.Truth.assertThat
import org.junit.Test

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
}
