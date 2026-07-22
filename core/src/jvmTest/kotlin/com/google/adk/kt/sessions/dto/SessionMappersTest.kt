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

package com.google.adk.kt.sessions.dto

import com.google.adk.kt.events.Event
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.sessions.State
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.Part
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Unit tests for the wire-DTO <-> ADK-domain mappers in [SessionMappers].
 *
 * These pin down the flattening of streaming/turn signaling onto [Event], the [State.REMOVED]
 * sentinel encoding (JSON `null` on the wire), the `transferAgent`/`transferToAgent` compatibility
 * read, and the session name/fallback-id and timestamp handling.
 */
@RunWith(JUnit4::class)
class SessionMappersTest {

  @Test
  fun eventToDto_mapsCoreFieldsAndMetadata() {
    val event =
      Event(
        id = "e1",
        invocationId = "inv1",
        author = "user",
        timestamp = 1_734_005_532_123L,
        turnComplete = true,
        longRunningToolIds = setOf("tool1"),
        actions = EventActions(transferToAgent = "agent").apply { stateDelta["k"] = "v" },
      )

    val dto = event.toDto()

    assertThat(dto.author).isEqualTo("user")
    assertThat(dto.invocationId).isEqualTo("inv1")
    assertThat(dto.timestamp!!.toEpochMillis()).isEqualTo(1_734_005_532_123L)
    assertThat(dto.eventMetadata!!.turnComplete).isTrue()
    assertThat(dto.eventMetadata.longRunningToolIds).containsExactly("tool1")
    assertThat(dto.actions!!.transferAgent).isEqualTo("agent")
    assertThat((dto.actions.stateDelta as JsonObject)["k"]).isEqualTo(JsonPrimitive("v"))
  }

  @Test
  fun eventToDto_stateRemoved_isEncodedAsJsonNull() {
    val event =
      Event(author = "user", actions = EventActions().apply { stateDelta["gone"] = State.REMOVED })

    val delta = event.toDto().actions!!.stateDelta as JsonObject

    assertThat(delta["gone"]).isEqualTo(JsonNull)
  }

  @Test
  fun sessionEventDtoToAdk_mapsFieldsAndCompatTransferAgent() {
    val dto =
      SessionEventDto(
        name = "reasoningEngines/123/sessions/s1/events/e9",
        author = "agent",
        invocationId = "inv9",
        timestamp = TimestampDto.fromEpochMillis(2000L),
        // Only the compatibility field `transferToAgent` is set; the mapper must still read it.
        actions =
          EventActionsDto(
            transferToAgent = "compatAgent",
            stateDelta = JsonObject(mapOf("a" to JsonNull)),
          ),
        eventMetadata = EventMetadataDto(partial = true, branch = "b1"),
      )

    val event = dto.toAdk()

    assertThat(event.id).isEqualTo("e9")
    assertThat(event.author).isEqualTo("agent")
    assertThat(event.invocationId).isEqualTo("inv9")
    assertThat(event.timestamp).isEqualTo(2000L)
    assertThat(event.partial).isTrue()
    assertThat(event.branch).isEqualTo("b1")
    assertThat(event.actions.transferToAgent).isEqualTo("compatAgent")
    // JSON null in the wire state delta decodes back to the REMOVED sentinel.
    assertThat(event.actions.stateDelta["a"]).isEqualTo(State.REMOVED)
  }

  @Test
  fun event_roundTrip_preservesContentAndFields() {
    val original =
      Event(
        author = "user",
        invocationId = "inv-rt",
        timestamp = 1000L,
        content = Content(role = "user", parts = listOf(Part(text = "round-trip"))),
      )

    val restored = original.toDto().toAdk()

    assertThat(restored.author).isEqualTo("user")
    assertThat(restored.invocationId).isEqualTo("inv-rt")
    assertThat(restored.timestamp).isEqualTo(1000L)
    assertThat(restored.content?.parts?.single()?.text).isEqualTo("round-trip")
  }

  @Test
  fun sessionDtoToAdk_mapsNameStateAndUpdateTime() {
    val dto =
      SessionDto(
        name = "reasoningEngines/123/sessions/sX",
        updateTime = "2024-12-12T12:12:12Z",
        sessionState = JsonObject(mapOf("k" to JsonPrimitive("v"))),
      )

    val session = dto.toAdk(appName = "123", userId = "user", fallbackId = null)

    assertThat(session.key.id).isEqualTo("sX")
    assertThat(session.key.appName).isEqualTo("123")
    assertThat(session.key.userId).isEqualTo("user")
    assertThat(session.state["k"]).isEqualTo("v")
    assertThat(session.lastUpdateTime.toEpochMilliseconds())
      .isEqualTo(java.time.Instant.parse("2024-12-12T12:12:12Z").toEpochMilli())
  }

  @Test
  fun sessionDtoToAdk_missingName_usesFallbackIdAndEpochTime() {
    val dto = SessionDto(name = null, updateTime = null, sessionState = null)

    val session = dto.toAdk(appName = "123", userId = "user", fallbackId = "fallback-1")

    assertThat(session.key.id).isEqualTo("fallback-1")
    assertThat(session.state).isEmpty()
    assertThat(session.lastUpdateTime.toEpochMilliseconds()).isEqualTo(0L)
  }

  @Test
  fun eventToDto_richContent_serializesBytesAsBase64AndRoundTrips() {
    // The event content on the wire is a google.genai Content. Parts must use the SDK's camelCase
    // field names, and bytes fields (thoughtSignature, inlineData.data) must be base64 STRINGS, not
    // JSON int arrays - Vertex rejects int arrays for `bytes` proto fields ("Proto field is not
    // repeating, cannot start list."), which silently dropped every model (thinking) event.
    val signature = byteArrayOf(1, -113, 61, 107)
    val content =
      Content(
        role = "model",
        parts =
          listOf(
            Part(
              functionCall = FunctionCall(name = "get_weather", args = mapOf("city" to "SF")),
              thoughtSignature = signature,
            ),
            Part(inlineData = Blob(mimeType = "image/png", data = byteArrayOf(1, 2, 3))),
          ),
      )
    val event = Event(author = "model", timestamp = 1000L, content = content)

    val contentJson = event.toDto().content!!.jsonObject

    assertThat(contentJson["role"]!!.jsonPrimitive.content).isEqualTo("model")
    val parts = contentJson["parts"]!!.jsonArray
    assertThat(parts).hasSize(2)
    val functionCallPart = parts[0].jsonObject
    assertThat(functionCallPart["functionCall"]!!.jsonObject["name"]!!.jsonPrimitive.content)
      .isEqualTo("get_weather")
    assertThat(
        functionCallPart["functionCall"]!!
          .jsonObject["args"]!!
          .jsonObject["city"]!!
          .jsonPrimitive
          .content
      )
      .isEqualTo("SF")
    // Bytes must be base64 strings. If serialized as an int array these `.jsonPrimitive.isString`
    // accesses fail, which is the exact defect that made Vertex 400 and drop model events.
    assertThat(functionCallPart["thoughtSignature"]!!.jsonPrimitive.isString).isTrue()
    val inlineData = parts[1].jsonObject["inlineData"]!!.jsonObject
    assertThat(inlineData["mimeType"]!!.jsonPrimitive.content).isEqualTo("image/png")
    assertThat(inlineData["data"]!!.jsonPrimitive.isString).isTrue()

    val restored = event.toDto().toAdk().content!!.parts
    assertThat(restored[0].functionCall?.name).isEqualTo("get_weather")
    assertThat(restored[0].thoughtSignature?.toList()).isEqualTo(signature.toList())
    assertThat(restored[1].inlineData?.data?.toList()).isEqualTo(byteArrayOf(1, 2, 3).toList())
  }

  @Test
  fun eventToDto_stripsPartMetadataFromWire() {
    // partMetadata is an ADK-only Part field with no counterpart in the Vertex Content proto; the
    // service rejects it (400 INVALID_ARGUMENT), so it must not be sent on the wire.
    val content =
      Content(role = "user", parts = listOf(Part(text = "hi", partMetadata = mapOf("k" to "v"))))
    val event = Event(author = "user", timestamp = 1000L, content = content)

    val part = event.toDto().content!!.jsonObject["parts"]!!.jsonArray.single().jsonObject

    assertThat(part.keys).doesNotContain("partMetadata")
    // The rest of the part is untouched.
    assertThat(part["text"]!!.jsonPrimitive.content).isEqualTo("hi")
  }
}
