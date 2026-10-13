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
import com.google.adk.kt.types.GroundingMetadata
import com.google.adk.kt.types.InteractionStatus
import com.google.adk.kt.types.LiveServerSessionResumptionUpdate
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Transcription
import com.google.adk.kt.types.TurnCompleteReason
import com.google.adk.kt.types.UsageMetadata
import com.google.adk.kt.types.VoiceActivity
import com.google.adk.kt.types.VoiceActivityType
import com.google.adk.kt.types.WordInfo
import com.google.adk.kt.workflow.NodeInfo
import com.google.adk.kt.workflow.Route
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

    val part = event.toDto().content!!.jsonObject["parts"]!!.jsonArray.single().jsonObject

    assertThat(part.keys).doesNotContain("partMetadata")
    // The rest of the part is untouched.
    assertThat(part["text"]!!.jsonPrimitive.content).isEqualTo("hi")
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
  fun eventToDto_withoutRawEvent_keepsOnlyTranscriptions() {
    // Transcriptions are proto fields; the other live signals are only in rawEvent.
    val event =
      Event(
        author = "agent",
        turnCompleteReason = TurnCompleteReason.NEED_MORE_INPUT,
        interactionStatus = InteractionStatus.IN_PROGRESS,
        inputTranscription = Transcription(text = "what is the weather", finished = true),
        outputTranscription = Transcription(text = "it is sunny", finished = true),
        liveSessionId = "live-1",
        voiceActivity = VoiceActivity(voiceActivityType = VoiceActivityType.ACTIVITY_START),
        liveSessionResumptionUpdate = LiveServerSessionResumptionUpdate(newHandle = "handle-1"),
      )

    val restored = event.toDto().copy(rawEvent = null).toAdk()

    assertThat(restored.inputTranscription).isEqualTo(event.inputTranscription)
    assertThat(restored.outputTranscription).isEqualTo(event.outputTranscription)
    assertThat(restored.voiceActivity).isNull()
    assertThat(restored.liveSessionResumptionUpdate).isNull()
    assertThat(restored.turnCompleteReason).isNull()
    assertThat(restored.interactionStatus).isNull()
    assertThat(restored.liveSessionId).isNull()
  }

  @OptIn(FrameworkInternalApi::class)
  @Test
  fun sessionEventDtoToAdk_transcriptionsOnlyInRawEvent_areRead() {
    // ADK Python stores transcriptions only in rawEvent, so its events carry no typed fields.
    val dto =
      adkJson.decodeFromString(
        SessionEventDto.serializer(),
        """
        {"name": "projects/p/locations/l/reasoningEngines/r/sessions/s/events/e1",
         "author": "agent",
         "rawEvent": {"author": "agent",
           "inputTranscription": {"text": "is the room free", "finished": true},
           "outputTranscription": {"text": "it is", "finished": false}}}
        """,
      )

    val event = dto.toAdk()

    assertThat(event.inputTranscription)
      .isEqualTo(Transcription(text = "is the room free", finished = true))
    assertThat(event.outputTranscription).isEqualTo(Transcription(text = "it is", finished = false))
  }

  @OptIn(FrameworkInternalApi::class)
  @Test
  fun sessionEventDtoToAdk_typedAndRawTranscriptions_rawWins() {
    // A readable rawEvent holds the whole event, so it wins over the typed fields.
    val dto =
      adkJson.decodeFromString(
        SessionEventDto.serializer(),
        """
        {"author": "agent",
         "eventMetadata": {"inputTranscription": {"text": "typed", "finished": true}},
         "rawEvent": {"inputTranscription": {"text": "raw", "finished": true}}}
        """,
      )

    assertThat(dto.toAdk().inputTranscription)
      .isEqualTo(Transcription(text = "raw", finished = true))
  }

  @OptIn(FrameworkInternalApi::class)
  @Test
  fun sessionEventDtoToAdk_unreadableRawEventWithTranscription_readsTranscription() {
    // The timestamp makes the rawEvent unreadable as an Event, so the typed-field read runs.
    val dto =
      adkJson.decodeFromString(
        SessionEventDto.serializer(),
        """
        {"author": "agent",
         "rawEvent": {"timestamp": "x",
           "inputTranscription": {"text": "is the room free", "finished": true}}}
        """,
      )

    assertThat(dto.toAdk().inputTranscription)
      .isEqualTo(Transcription(text = "is the room free", finished = true))
  }

  @OptIn(FrameworkInternalApi::class)
  @Test
  fun sessionEventDtoToAdk_malformedRawEventTranscription_isSkipped() {
    val dto =
      adkJson.decodeFromString(
        SessionEventDto.serializer(),
        """
        {"author": "agent",
         "rawEvent": {"inputTranscription": {"finished": "maybe"},
           "outputTranscription": "not an object"}}
        """,
      )

    val event = dto.toAdk()

    assertThat(event.inputTranscription).isNull()
    assertThat(event.outputTranscription).isNull()
  }

  @Test
  fun eventToDto_transcription_sendsOnlyTheKeysTheProtoHas() {
    // `session.proto`'s Transcription has only text and finished; any other key is rejected.
    val event =
      Event(
        author = "agent",
        inputTranscription =
          Transcription(
            text = "hola",
            finished = true,
            languageCode = "es-ES",
            speakerLabel = "spk_1",
            words = listOf(WordInfo(word = "hola", startOffset = "0s", endOffset = "0.4s")),
          ),
      )

    val keys = event.toDto().eventMetadata?.inputTranscription?.jsonObject?.keys

    assertThat(keys).containsExactly("text", "finished")
  }

  @OptIn(FrameworkInternalApi::class)
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
            Part(
              text = "hi",
              thoughtSignature = byteArrayOf(-5, -1, 1),
              partMetadata = mapOf("k" to "v"),
            ),
            Part(inlineData = Blob(mimeType = "image/png", data = byteArrayOf(1, 2, 3))),
          ),
        longRunningToolIds = setOf("call-1"),
        partial = true,
        turnComplete = true,
        errorCode = "ERROR",
        errorMessage = "message",
        finishReason = FinishReason.STOP,
        usageMetadata = UsageMetadata(promptTokenCount = 10, totalTokenCount = 12),
        avgLogProbs = -0.5,
        interrupted = true,
        branch = "root.child",
        groundingMetadata = GroundingMetadata(webSearchQueries = listOf("query")),
        modelVersion = "model-1",
        citationMetadata = CitationMetadata(listOf(Citation(uri = "https://example.com"))),
        cacheMetadata =
          CacheMetadata(
            fingerprint = "fp",
            contentsCount = 2,
            cacheName = "projects/p/locations/l/cachedContents/c",
            expireTime = 1_700_000_600_500L,
            invocationsUsed = 1,
            createdAt = 1_700_000_000_250L,
          ),
        customMetadata = mapOf("k" to "v"),
        output = mapOf("n" to 1L),
        nodeInfo = NodeInfo(path = "wf@1/a@1"),
        turnCompleteReason = TurnCompleteReason.NEED_MORE_INPUT,
        interactionStatus = InteractionStatus.IN_PROGRESS,
        inputTranscription = Transcription(text = "hola", finished = true, languageCode = "es-ES"),
        outputTranscription = Transcription(text = "hi", finished = false),
        liveSessionId = "live-1",
        liveSessionResumptionUpdate =
          LiveServerSessionResumptionUpdate(newHandle = "handle-1", resumable = true),
        voiceActivity = VoiceActivity(voiceActivityType = VoiceActivityType.ACTIVITY_START),
        isolationScope = "wf@1/task@1",
        actions =
          EventActions(
              skipSummarization = true,
              artifactDelta = mutableMapOf("file.txt" to 1),
              transferToAgent = "other",
              escalate = true,
              endOfAgent = true,
              requestedToolConfirmations =
                mutableMapOf("call-1" to ToolConfirmation(confirmed = true, hint = "ok?")),
              rewindBeforeInvocationId = "inv-0",
              route = listOf(Route.Tag("next")),
              agentState = TypedData.MapValue(mapOf("step" to TypedData.IntValue(2))),
              compaction =
                EventCompaction(
                  startTimestamp = 1_700_000_000_100L,
                  endTimestamp = 1_700_000_000_200L,
                  compactedContent = modelMessage("summary"),
                ),
            )
            .apply {
              stateDelta["k"] = "v"
              stateDelta["gone"] = State.REMOVED
            },
      )
    // Every field is set, so a field the round trip loses fails the equality below.
    assertThat(adkJson.encodeToJsonElement(Event.serializer(), original).jsonObject.keys)
      .containsExactlyElementsIn(Event.serializer().descriptor.elementNames)
    assertThat(
        adkJson.encodeToJsonElement(EventActions.serializer(), original.actions).jsonObject.keys
      )
      .containsExactlyElementsIn(EventActions.serializer().descriptor.elementNames)

    val restored = original.toDto().copy(name = "sessions/s1/events/server-id").toAdk()

    assertThat(restored).isEqualTo(original)
  }

  @Test
  fun eventToDto_outputNotJsonNative_isLeftOutOfRawEvent() {
    data class NodeResult(val n: Int)
    val event = Event(author = "node", timestamp = 1000L, output = NodeResult(3))

    val rawEvent = event.toDto().rawEvent!!.jsonObject

    assertThat(rawEvent.keys).doesNotContain("output")
    assertThat(rawEvent["author"]!!.jsonPrimitive.content).isEqualTo("node")
  }

  @Test
  fun eventToDto_rawEventCannotBeEncoded_isLeftOut() {
    data class Payload(val n: Int)
    val event =
      Event(
        author = "agent",
        timestamp = 1000L,
        content = modelMessage("kept"),
        actions =
          EventActions(
            requestedToolConfirmations =
              mutableMapOf("call-1" to ToolConfirmation(confirmed = false, payload = Payload(1)))
          ),
      )

    val dto = event.toDto()

    // The typed fields still store what they can.
    assertThat(dto.rawEvent).isNull()
    assertThat(dto.toAdk().content!!.parts.single().text).isEqualTo("kept")
  }

  @Test
  fun sessionEventDtoToAdk_emptyRawEvent_usesTypedFields() {
    val dto =
      SessionEventDto(
        name = "sessions/s1/events/server-id",
        author = "agent",
        timestamp = TimestampDto.fromEpochMillis(1000L),
        content = Json.parseToJsonElement("""{"parts": [{"text": "typed"}]}"""),
        rawEvent = JsonObject(emptyMap()),
      )

    val event = dto.toAdk()

    assertThat(event.id).isEqualTo("server-id")
    assertThat(event.content!!.parts.single().text).isEqualTo("typed")
  }

  @Test
  fun sessionEventDtoToAdk_unreadableRawEvent_usesTypedFields() {
    val dto =
      SessionEventDto(
        name = "sessions/s1/events/server-id",
        author = "agent",
        timestamp = TimestampDto.fromEpochMillis(1000L),
        content = Json.parseToJsonElement("""{"parts": [{"text": "typed"}]}"""),
        rawEvent = Json.parseToJsonElement("""{"id": "raw-id", "timestamp": "not a number"}"""),
      )

    val event = dto.toAdk()

    assertThat(event.id).isEqualTo("server-id")
    assertThat(event.content!!.parts.single().text).isEqualTo("typed")
  }
}
