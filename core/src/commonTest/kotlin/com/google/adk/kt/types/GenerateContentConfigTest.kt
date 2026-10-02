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

package com.google.adk.kt.types

import com.google.adk.kt.annotations.AdkJavaInteropApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class GenerateContentConfigTest {
  @OptIn(AdkJavaInteropApi::class)
  @Test
  fun toBuilder_matchesCopy() {
    val config =
      GenerateContentConfig(
        tools = emptyList(),
        labels = mapOf("team" to "adk"),
        systemInstruction = Content(role = Role.USER),
        temperature = 0.1f,
        topP = 0.2f,
        topK = 1,
        candidateCount = 2,
        maxOutputTokens = 3,
        stopSequences = listOf("stop"),
        responseMimeType = "text/plain",
        responseSchema = Schema(),
        thinkingConfig = ThinkingConfig(),
        toolConfig = ToolConfig(),
        safetySettings = emptyList(),
        mediaResolution = MediaResolution.MEDIA_RESOLUTION_LOW,
        serviceTier = ServiceTier.FLEX,
        presencePenalty = 0.3f,
        frequencyPenalty = 0.4f,
        responseLogprobs = true,
        routingConfig = GenerationConfigRoutingConfig(),
        cachedContent = "cache",
        responseModalities = listOf("TEXT"),
        seed = 4,
        continuationToken = byteArrayOf(1, 2, 3),
      )

    assertEquals(config.copy(), config.toBuilder().build())
    assertEquals(config.copy(seed = 5), config.toBuilder().seed(5).build())
  }

  @OptIn(AdkJavaInteropApi::class)
  @Test
  fun builder_noPropertySet_matchesConstructorDefaults() {
    assertEquals(GenerateContentConfig(), GenerateContentConfig.builder().build())
  }

  @Test
  fun equals_sameContinuationToken_returnsTrue() {
    assertEquals(config(byteArrayOf(1, 2, 3)), config(byteArrayOf(1, 2, 3)))
  }

  @Test
  fun equals_differentContinuationToken_returnsFalse() {
    assertNotEquals(config(byteArrayOf(1, 2, 3)), config(byteArrayOf(1, 2, 4)))
  }

  @Test
  fun equals_differentTemperature_returnsFalse() {
    assertNotEquals(config(token = null), config(token = null).copy(temperature = 0.7f))
  }

  @Test
  fun equals_nanTemperature_returnsTrue() {
    val config = config(byteArrayOf(1, 2, 3)).copy(temperature = Float.NaN)

    assertEquals(config, config.copy())
    assertEquals(config.hashCode(), config.copy().hashCode())
  }

  @Test
  fun equals_anyPropertyDiffers_returnsFalse() {
    val base = GenerateContentConfig()
    val changed =
      listOf(
        base.copy(tools = emptyList()),
        base.copy(labels = emptyMap()),
        base.copy(systemInstruction = Content()),
        base.copy(temperature = 0.5f),
        base.copy(topP = 0.5f),
        base.copy(topK = 1),
        base.copy(candidateCount = 1),
        base.copy(maxOutputTokens = 1),
        base.copy(stopSequences = emptyList()),
        base.copy(responseMimeType = "text/plain"),
        base.copy(responseSchema = Schema()),
        base.copy(thinkingConfig = ThinkingConfig()),
        base.copy(toolConfig = ToolConfig()),
        base.copy(safetySettings = emptyList()),
        base.copy(mediaResolution = MediaResolution.MEDIA_RESOLUTION_LOW),
        base.copy(serviceTier = ServiceTier.FLEX),
        base.copy(presencePenalty = 0.5f),
        base.copy(frequencyPenalty = 0.5f),
        base.copy(responseLogprobs = true),
        base.copy(routingConfig = GenerationConfigRoutingConfig()),
        base.copy(cachedContent = "cache"),
        base.copy(responseModalities = emptyList()),
        base.copy(seed = 1),
        base.copy(continuationToken = byteArrayOf(1)),
      )

    for (config in changed) {
      assertNotEquals(base, config)
    }
  }

  @Test
  fun hashCode_sameContinuationToken_returnsSameHashCode() {
    assertEquals(config(byteArrayOf(1, 2, 3)).hashCode(), config(byteArrayOf(1, 2, 3)).hashCode())
  }

  private fun config(token: ByteArray?) =
    GenerateContentConfig(temperature = 0.5f, maxOutputTokens = 100, continuationToken = token)
}
