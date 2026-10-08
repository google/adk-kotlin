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

import com.google.adk.kt.agents.TypedData
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.events.Event
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.events.EventCompaction
import com.google.adk.kt.events.ToolConfirmation
import com.google.adk.kt.models.CacheMetadata
import com.google.adk.kt.serialization.adkJson
import com.google.adk.kt.sessions.State
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.Citation
import com.google.adk.kt.types.CitationMetadata
import com.google.adk.kt.types.FinishReason
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.UsageMetadata
import com.google.adk.kt.workflow.Route
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
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
 * read, the session name/fallback-id and timestamp handling, and where fields the API drops are
 * stored.
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
        content = userMessage("round-trip"),
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
      modelMessage(
        Part(
          functionCall = FunctionCall(name = "get_weather", args = mapOf("city" to "SF")),
          thoughtSignature = signature,
        ),
        Part(inlineData = Blob(mimeType = "image/png", data = byteArrayOf(1, 2, 3))),
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
    val content = userMessage(Part(text = "hi", partMetadata = mapOf("k" to "v")))
    val event = Event(author = "user", timestamp = 1000L, content = content)

    val dto = event.toDto()

    val part = dto.content!!.jsonObject["parts"]!!.jsonArray.single().jsonObject
    assertThat(part.keys).doesNotContain("partMetadata")
    // The rest of the part is untouched.
    assertThat(part["text"]!!.jsonPrimitive.content).isEqualTo("hi")
    // ADK Python strips partMetadata from rawEvent too.
    val rawPart = dto.rawEvent!!["content"]!!.jsonObject["parts"]!!.jsonArray.single().jsonObject
    assertThat(rawPart.keys).containsExactly("text")
  }

  @Test
  fun eventToDto_customMetadata_isWrittenUnderEventMetadata() {
    // custom_metadata is field 7 of EventMetadata in session.proto, not a top-level SessionEvent
    // field, so it must be nested under eventMetadata on the wire.
    val event =
      Event(
        author = "user",
        timestamp = 1000L,
        customMetadata = mapOf("trace_id" to "abc123", "attempt" to 2),
      )

    val struct = event.toDto().eventMetadata!!.customMetadata!!.jsonObject

    // Write side only: `attempt` is written as the Int it was given, but comes back widened to a
    // Long. See event_roundTrip_widensCustomMetadataIntegersToLong.
    assertThat(struct["trace_id"]!!.jsonPrimitive.content).isEqualTo("abc123")
    assertThat(struct["attempt"]!!.jsonPrimitive.int).isEqualTo(2)
  }

  @Test
  fun event_roundTrip_preservesCustomMetadata() {
    val original =
      Event(
        author = "user",
        timestamp = 1000L,
        customMetadata = mapOf("trace_id" to "abc123", "nested" to mapOf("k" to "v")),
      )

    val restored = original.toDto().toAdk()

    assertThat(restored.customMetadata)
      .containsExactlyEntriesIn(mapOf("trace_id" to "abc123", "nested" to mapOf("k" to "v")))
  }

  @Test
  fun eventToDto_noCustomMetadata_omitsFieldFromWire() {
    val event = Event(author = "user", timestamp = 1000L)

    assertThat(event.toDto().eventMetadata!!.customMetadata).isNull()
  }

  @Test
  fun event_roundTrip_widensCustomMetadataIntegersToLong() {
    // `custom_metadata` is a google.protobuf.Struct, so numbers cross the wire as JSON numbers with
    // no integer width. `jsonElementToAny` tries `longOrNull` before `doubleOrNull`, so an Int
    // always comes back as a Long and a fractional value as a Double; an Int never round-trips as
    // an Int. Callers that compare against the map they wrote must expect the widened types. This
    // is pre-existing behaviour shared with `stateDelta`, documented here so it is not rediscovered
    // as a bug.
    val original =
      Event(author = "user", timestamp = 1000L, customMetadata = mapOf("int" to 2, "double" to 2.5))

    val restored = original.toDto().toAdk()

    assertThat(restored.customMetadata)
      .containsExactlyEntriesIn(mapOf("int" to 2L, "double" to 2.5))
  }

  @Test
  fun sessionEventDtoToAdk_nullValuedCustomMetadata_preservesNullEntries() {
    // Struct permits null values and Event.customMetadata is Map<String, Any?>, so a null read off
    // the wire round-trips as a null-valued entry rather than being dropped. This distinguishes
    // "key absent" from "key present with no value", matching the Python ADK.
    val dto =
      SessionEventDto(
        author = "user",
        timestamp = TimestampDto.fromEpochMillis(1000L),
        eventMetadata =
          EventMetadataDto(
            customMetadata =
              JsonObject(mapOf("present" to JsonPrimitive("v"), "absent" to JsonNull))
          ),
      )

    val event = dto.toAdk()

    assertThat(event.customMetadata).containsExactly("present", "v", "absent", null)
  }

  @Test
  fun eventToDto_fieldsTheApiDrops_areStoredInRawEventAndCustomMetadata() {
    val event =
      Event(
        author = "agent",
        timestamp = 1_700_000_000_123L,
        usageMetadata = UsageMetadata(totalTokenCount = 12),
        actions =
          EventActions(
            endOfAgent = true,
            agentState = TypedData.MapValue(mapOf("step" to TypedData.IntValue(2))),
            requestedToolConfirmations =
              mutableMapOf("call-1" to ToolConfirmation(confirmed = true, hint = "ok?")),
            compaction = COMPACTION,
          ),
      )

    val dto = event.toDto()

    val customMetadata = dto.eventMetadata!!.customMetadata!!.jsonObject
    assertThat(
        customMetadata["_usage_metadata"]!!.jsonObject["totalTokenCount"]!!.jsonPrimitive.int
      )
      .isEqualTo(12)
    assertThat(customMetadata["_compaction"]!!.jsonObject["startTimestamp"]!!.jsonPrimitive.double)
      .isWithin(1e-6)
      .of(1_700_000_000.1)
    val rawActions = dto.rawEvent!!["actions"]!!.jsonObject
    assertThat(rawActions["endOfAgent"]!!.jsonPrimitive.boolean).isTrue()
    assertThat(rawActions.keys).containsAtLeast("agentState", "requestedToolConfirmations")
  }

  @Test
  fun eventToDto_rawEvent_hasShapePythonAdkReads() {
    val event =
      Event(
        id = "event-1",
        author = "agent",
        timestamp = 1_700_000_000_123L,
        citationMetadata = CitationMetadata(listOf(Citation(uri = "https://example.com"))),
        cacheMetadata = CACHE_METADATA,
        actions =
          EventActions(route = listOf(Route.Tag("next")), compaction = COMPACTION).apply {
            stateDelta["kept"] = "v"
            stateDelta["removed"] = State.REMOVED
          },
      )

    val rawEvent = event.toDto().rawEvent!!

    assertThat(rawEvent["id"]!!.jsonPrimitive.content).isEqualTo("event-1")
    assertThat(rawEvent["timestamp"]!!.jsonPrimitive.double).isWithin(1e-6).of(1_700_000_000.123)
    // ADK Python names the citation list `citations`.
    assertThat(rawEvent["citationMetadata"]!!.jsonObject.keys).containsExactly("citations")
    // ADK Python's CacheMetadata rejects camelCase keys and takes epoch seconds.
    val cacheMetadata = rawEvent["cacheMetadata"]!!.jsonObject
    assertThat(cacheMetadata.keys)
      .containsExactly(
        "fingerprint",
        "contents_count",
        "cache_name",
        "expire_time",
        "invocations_used",
        "created_at",
      )
    assertThat(cacheMetadata["expire_time"]!!.jsonPrimitive.double)
      .isWithin(1e-6)
      .of(1_700_000_600.5)
    val rawActions = rawEvent["actions"]!!.jsonObject
    assertThat(rawActions["stateDelta"]!!.jsonObject["removed"]).isEqualTo(JsonNull)
    assertThat(rawActions["compaction"]!!.jsonObject["startTimestamp"]!!.jsonPrimitive.double)
      .isWithin(1e-6)
      .of(1_700_000_000.1)
    // ADK Python 1.x rejects unknown EventActions keys, which would fail its whole session load.
    assertThat(rawActions.keys).doesNotContain("route")
  }

  @Test
  fun eventToDto_outputNotJsonNative_isLeftOutOfRawEvent() {
    data class NodeResult(val n: Int)
    val event = Event(author = "node", timestamp = 1000L, output = NodeResult(3))

    val rawEvent = event.toDto().rawEvent!!

    assertThat(rawEvent.keys).doesNotContain("output")
    assertThat(rawEvent["author"]!!.jsonPrimitive.content).isEqualTo("node")
  }

  @Test
  fun event_roundTripThroughRawEvent_restoresWholeEvent() {
    val original =
      Event(
        id = "event-1",
        invocationId = "inv-1",
        author = "agent",
        timestamp = 1_700_000_000_123L,
        content =
          modelMessage(
            Part(text = "hi", thoughtSignature = byteArrayOf(-5, -1, 1)),
            Part(inlineData = Blob(mimeType = "image/png", data = byteArrayOf(1, 2, 3))),
          ),
        turnComplete = true,
        finishReason = FinishReason.STOP,
        usageMetadata = UsageMetadata(promptTokenCount = 10, totalTokenCount = 12),
        modelVersion = "model-1",
        citationMetadata = CitationMetadata(listOf(Citation(uri = "https://example.com"))),
        cacheMetadata = CACHE_METADATA,
        customMetadata = mapOf("k" to "v"),
        output = mapOf("n" to 1L),
        actions =
          EventActions(
              transferToAgent = "other",
              endOfAgent = true,
              requestedToolConfirmations =
                mutableMapOf("call-1" to ToolConfirmation(confirmed = true, hint = "ok?")),
              agentState = TypedData.MapValue(mapOf("step" to TypedData.IntValue(2))),
              compaction = COMPACTION,
            )
            .apply {
              stateDelta["k"] = "v"
              stateDelta["gone"] = State.REMOVED
            },
      )

    val restored = original.toDto().copy(name = "sessions/s1/events/server-id").toAdk()

    assertThat(restored).isEqualTo(original)
  }

  @Test
  fun event_roundTripThroughRawEvent_keepsSmallCompactionTimestamps() {
    val compaction =
      EventCompaction(
        startTimestamp = 1000L,
        endTimestamp = 2000L,
        compactedContent = modelMessage("summary"),
      )
    val event =
      Event(author = "agent", timestamp = 3000L, actions = EventActions(compaction = compaction))

    val restored = event.toDto().copy(name = "sessions/s1/events/e1").toAdk()

    assertThat(restored.actions.compaction).isEqualTo(compaction)
  }

  @Test
  fun sessionEventDtoToAdk_pythonWrittenRawEvent_readsFieldsKotlinModels() {
    // As ADK Python dumps it: float seconds, URL-safe base64 and keys Kotlin does not model.
    val rawEvent =
      Json.parseToJsonElement(
          """
          {
            "id": "python-event-id",
            "timestamp": 1700000000.9,
            "nodeInfo": {"path": ""},
            "content": {"role": "model", "parts": [{"text": "hi", "thoughtSignature": "-_8B"}]},
            "usageMetadata": {"promptTokenCount": 10, "totalTokenCount": 12},
            "customMetadata": {"k": "v"},
            "citationMetadata": {"citations": [{"uri": "https://example.com"}]},
            "actions": {
              "stateDelta": {"gone": null},
              "agentState": {"current_sub_agent": "b"},
              "requestedToolConfirmations": {"call-1": {"hint": "ok?", "confirmed": false}},
              "compaction": {
                "startTimestamp": 1700000000.1236,
                "endTimestamp": 1700000000.2,
                "compactedContent": {"role": "model", "parts": [{"text": "sum"}]}
              }
            }
          }
          """
        )
        .jsonObject
    val dto =
      SessionEventDto(
        name = "sessions/s1/events/server-id",
        author = "agent",
        invocationId = "inv-1",
        timestamp = TimestampDto.fromEpochMillis(1_700_000_000_500L),
        rawEvent = rawEvent,
      )

    val event = dto.toAdk()

    assertThat(event.id).isEqualTo("python-event-id")
    // As in ADK Python, the envelope timestamp wins over rawEvent's.
    assertThat(event.timestamp).isEqualTo(1_700_000_000_500L)
    assertThat(event.content!!.parts.single().thoughtSignature!!.toList())
      .isEqualTo(byteArrayOf(-5, -1, 1).toList())
    assertThat(event.usageMetadata!!.totalTokenCount).isEqualTo(12)
    assertThat(event.customMetadata).containsExactly("k", "v")
    assertThat(event.citationMetadata!!.citationSources.single().uri)
      .isEqualTo("https://example.com")
    assertThat(event.actions.stateDelta["gone"]).isEqualTo(State.REMOVED)
    assertThat(event.actions.requestedToolConfirmations["call-1"]!!.hint).isEqualTo("ok?")
    // Floored like the event timestamps, so the first compacted event stays in range.
    assertThat(event.actions.compaction!!.startTimestamp).isEqualTo(1_700_000_000_123L)
    // ADK Python's agentState is a plain object, which Kotlin's TypedData cannot hold.
    assertThat(event.actions.agentState).isNull()
  }

  @Test
  fun sessionEventDtoToAdk_rawEventWithoutId_usesResourceNameId() {
    val dto =
      SessionEventDto(
        name = "sessions/s1/events/server-id",
        author = "agent",
        timestamp = TimestampDto.fromEpochMillis(1000L),
        rawEvent =
          JsonObject(mapOf("customMetadata" to JsonObject(mapOf("k" to JsonPrimitive("v"))))),
      )

    val event = dto.toAdk()

    assertThat(event.id).isEqualTo("server-id")
    // The customMetadata shows that rawEvent was read.
    assertThat(event.customMetadata).containsExactly("k", "v")
  }

  @Test
  fun sessionEventDtoToAdk_rawEventEmptyId_usesResourceNameId() {
    // As in ADK Python, an empty stored id falls back to the resource name.
    val dto =
      SessionEventDto(
        name = "sessions/s1/events/server-id",
        author = "agent",
        timestamp = TimestampDto.fromEpochMillis(1000L),
        rawEvent =
          JsonObject(
            mapOf(
              "id" to JsonPrimitive(""),
              "customMetadata" to JsonObject(mapOf("k" to JsonPrimitive("v"))),
            )
          ),
      )

    val event = dto.toAdk()

    assertThat(event.id).isEqualTo("server-id")
    // The customMetadata shows that rawEvent was read.
    assertThat(event.customMetadata).containsExactly("k", "v")
  }

  @Test
  fun sessionEventDtoToAdk_emptyRawEvent_usesTypedFields() {
    // As in ADK Python, an empty rawEvent counts as missing.
    val dto =
      SessionEventDto(
        name = "sessions/s1/events/server-id",
        author = "agent",
        timestamp = TimestampDto.fromEpochMillis(1000L),
        content = Json.parseToJsonElement("""{"parts": [{"text": "typed"}]}"""),
        actions = EventActionsDto(transferAgent = "typed-agent"),
        rawEvent = JsonObject(emptyMap()),
      )

    val event = dto.toAdk()

    assertThat(event.content!!.parts.single().text).isEqualTo("typed")
    assertThat(event.actions.transferToAgent).isEqualTo("typed-agent")
  }

  @Test
  fun sessionEventDtoToAdk_pythonEventWithoutRawEvent_readsCustomMetadataKeys() {
    // ADK Python stores an event without raw_event when its Vertex AI SDK rejects that field.
    val customMetadata =
      Json.parseToJsonElement(
        """
        {
          "k": "v",
          "_usage_metadata": {
            "prompt_token_count": 10,
            "total_token_count": 12,
            "prompt_tokens_details": [{"modality": "TEXT", "token_count": 10}]
          },
          "_compaction": {
            "start_timestamp": 1718648987.6949995,
            "end_timestamp": 1718648987.8,
            "compacted_content": {"role": "model", "parts": [{"text": "summary"}]}
          }
        }
        """
      )
    val dto =
      SessionEventDto(
        name = "sessions/s1/events/e9",
        author = "agent",
        timestamp = TimestampDto.fromEpochMillis(1000L),
        eventMetadata = EventMetadataDto(customMetadata = customMetadata),
      )

    val event = dto.toAdk()

    assertThat(event.id).isEqualTo("e9")
    assertThat(event.usageMetadata!!.promptTokenCount).isEqualTo(10)
    assertThat(event.usageMetadata.promptTokensDetails!!.single().tokenCount).isEqualTo(10)
    // CPython's datetime.fromtimestamp puts an event at this float in ms 694, not 695.
    assertThat(event.actions.compaction!!.startTimestamp).isEqualTo(1_718_648_987_694L)
    assertThat(event.actions.compaction!!.compactedContent.parts.single().text).isEqualTo("summary")
    assertThat(event.customMetadata).containsExactly("k", "v")
  }

  @Test
  fun sessionEventDtoToAdk_pythonCompactionWithUserData_keepsItsKeys() {
    // ADK Python writes _compaction with snake_case schema keys; user data keeps its own keys.
    val customMetadata =
      Json.parseToJsonElement(
        """
        {
          "_compaction": {
            "start_timestamp": 1700000000.1,
            "end_timestamp": 1700000000.2,
            "compacted_content": {
              "role": "model",
              "parts": [
                {"function_call": {"name": "lookup", "args": {"user_id": "u1"}}},
                {"function_response": {"name": "lookup", "response": {"user_name": "Ann"}}},
                {"text": "summary", "part_metadata": {"user_tag": 1}}
              ]
            }
          }
        }
        """
      )
    val dto =
      SessionEventDto(
        name = "sessions/s1/events/e9",
        author = "agent",
        timestamp = TimestampDto.fromEpochMillis(1000L),
        eventMetadata = EventMetadataDto(customMetadata = customMetadata),
      )

    val parts = dto.toAdk().actions.compaction!!.compactedContent.parts

    assertThat(parts[0].functionCall!!.args).containsExactly("user_id", "u1")
    assertThat(parts[1].functionResponse!!.response).containsExactly("user_name", "Ann")
    assertThat(parts[2].partMetadata).containsExactly("user_tag", 1L)
  }

  @OptIn(FrameworkInternalApi::class)
  @Test
  fun sessionEventDtoToAdk_unreadableRawEvent_keepsFieldsTheApiDropsFromRawActions() {
    val agentState = TypedData.MapValue(mapOf("step" to TypedData.IntValue(2)))
    val rawActions =
      mapOf(
        "endOfAgent" to JsonPrimitive(true),
        "agentState" to adkJson.encodeToJsonElement(TypedData.serializer(), agentState),
        "requestedToolConfirmations" to
          Json.parseToJsonElement("""{"call-1": {"hint": "ok?", "confirmed": false}}"""),
      )
    val rawEvent =
      JsonObject(
        mapOf(
          "id" to JsonPrimitive("raw-id"),
          "content" to Json.parseToJsonElement("""{"parts": [{"inlineData": {"data": "!!!!"}}]}"""),
          "actions" to JsonObject(rawActions),
        )
      )
    val dto =
      SessionEventDto(
        name = "sessions/s1/events/e9",
        author = "agent",
        timestamp = TimestampDto.fromEpochMillis(1000L),
        rawEvent = rawEvent,
      )

    val event = dto.toAdk()

    // The resource-name id shows that the typed-field fallback ran.
    assertThat(event.id).isEqualTo("e9")
    assertThat(event.actions.endOfAgent).isTrue()
    assertThat(event.actions.agentState).isEqualTo(agentState)
    assertThat(event.actions.requestedToolConfirmations["call-1"]!!.hint).isEqualTo("ok?")
  }

  @Test
  fun sessionEventDtoToAdk_nullToolConfirmation_isSkipped() {
    val rawEvent =
      Json.parseToJsonElement(
          """
          {
            "content": {"parts": [{"inlineData": {"data": "!!!!"}}]},
            "actions": {
              "requestedToolConfirmations": {
                "call-1": null,
                "call-2": {"hint": "ok?", "confirmed": false}
              }
            }
          }
          """
        )
        .jsonObject
    val dto =
      SessionEventDto(
        name = "sessions/s1/events/e9",
        author = "agent",
        timestamp = TimestampDto.fromEpochMillis(1000L),
        rawEvent = rawEvent,
      )

    val event = dto.toAdk()

    assertThat(event.actions.requestedToolConfirmations.keys).containsExactly("call-2")
  }

  private companion object {
    val COMPACTION =
      EventCompaction(
        startTimestamp = 1_700_000_000_100L,
        endTimestamp = 1_700_000_000_200L,
        compactedContent = modelMessage("summary"),
      )

    val CACHE_METADATA =
      CacheMetadata(
        fingerprint = "fp",
        contentsCount = 2,
        cacheName = "projects/p/locations/l/cachedContents/c",
        expireTime = 1_700_000_600_500L,
        invocationsUsed = 1,
        createdAt = 1_700_000_000_250L,
      )
  }
}
