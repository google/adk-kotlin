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

package com.google.adk.kt.telemetry

import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.toTracePayload
import com.google.adk.kt.serialization.anyToJsonElement
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FinishReason
import com.google.adk.kt.types.GenerateContentConfig
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.UsageMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Serialization of the four content payloads recorded on `call_llm`/`execute_tool` spans
 * (`llm_request`, `llm_response`, `tool_call_args`, `tool_response`).
 *
 * These assertions run on every KMP target from the shared `commonTest` source. Because the
 * payloads are built by the shared `adkJson` serializer, the exact JSON asserted here is identical
 * on JVM and Android, which is what this suite guards.
 */
class TracePayloadSerializationTest {

  @Test
  fun llmResponse_serializesStructuredJson_excludingNulls() {
    val response =
      LlmResponse(
        content = Content(role = "model", parts = listOf(Part(text = "Hi"))),
        usageMetadata = UsageMetadata(promptTokenCount = 5, candidatesTokenCount = 7),
        finishReason = FinishReason.STOP,
        modelVersion = "gemini",
      )

    assertEquals(
      "{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"Hi\"}]}," +
        "\"usageMetadata\":{\"promptTokenCount\":5,\"candidatesTokenCount\":7}," +
        "\"finishReason\":\"STOP\",\"modelVersion\":\"gemini\"}",
      response.toTracePayload().toString(),
    )
  }

  @Test
  fun llmResponse_doesNotDegradeDomainObjectsToToString() {
    val json =
      LlmResponse(content = Content(role = "model", parts = listOf(Part(text = "Hi"))))
        .toTracePayload()
        .toString()

    // The old Android formatter wrapped non-primitive values as {"value": obj.toString()}; verify
    // the domain graph is real JSON instead.
    assertFalse(json.contains("\"value\""), "unexpected degraded payload: $json")
    assertFalse(json.contains("Content("), "unexpected toString payload: $json")
  }

  @Test
  fun llmRequest_dropsInlineDataParts_andExcludesResponseSchema() {
    val request =
      LlmRequest(
        contents =
          listOf(
            Content(
              role = "user",
              parts =
                listOf(
                  Part(text = "hello"),
                  Part(inlineData = Blob(mimeType = "image/png", data = byteArrayOf(1, 2, 3))),
                ),
            )
          ),
        config =
          GenerateContentConfig(responseSchema = Schema(description = "x"), temperature = 0.5f),
      )

    assertEquals(
      "{\"config\":{\"temperature\":0.5}," +
        "\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":\"hello\"}]}]}",
      request.toTracePayload().toString(),
    )
  }

  @OptIn(FrameworkInternalApi::class)
  @Test
  fun toolCallArgs_serializeAsStructuredJson() {
    assertEquals(
      "{\"city\":\"Paris\",\"days\":3}",
      anyToJsonElement(mapOf("city" to "Paris", "days" to 3)).toString(),
    )
  }

  @OptIn(FrameworkInternalApi::class)
  @Test
  fun toolResponse_serializesNestedMapsAndLists() {
    val response = mapOf("result" to mapOf("temp" to 20, "unit" to "C"), "tags" to listOf("a", "b"))

    assertEquals(
      "{\"result\":{\"temp\":20,\"unit\":\"C\"},\"tags\":[\"a\",\"b\"]}",
      anyToJsonElement(response).toString(),
    )
  }
}
