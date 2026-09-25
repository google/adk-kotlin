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

package com.google.adk.kt.serialization

import com.google.adk.kt.agents.TypedData
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.events.Event
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.events.EventCompaction
import com.google.adk.kt.models.CacheMetadata
import com.google.adk.kt.sessions.State
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FinishReason
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.GroundingMetadata
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.PartialArg
import com.google.adk.kt.types.PartialArgValue
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.ToolCall
import com.google.adk.kt.types.ToolResponse
import com.google.adk.kt.types.ToolType
import com.google.adk.kt.types.UsageMetadata
import com.google.adk.kt.workflow.NodeInfo
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.Test

/** Round-trip tests for the kotlinx.serialization wiring of the [Event] graph. */
@OptIn(FrameworkInternalApi::class)
class EventSerializationTest {

  private fun roundTrip(event: Event): Event =
    adkJson.decodeFromString(Event.serializer(), adkJson.encodeToString(Event.serializer(), event))

  private fun roundTrip(content: Content): Content =
    adkJson.decodeFromString(
      Content.serializer(),
      adkJson.encodeToString(Content.serializer(), content),
    )

  @Test
  fun event_fullGraph_roundTripsLosslessly() {
    val event =
      Event(
        id = "evt-1",
        invocationId = "inv-1",
        author = "agent",
        content =
          Content(
            role = Role.MODEL,
            parts =
              listOf(
                Part(text = "hello"),
                Part(inlineData = Blob(mimeType = "image/png", data = byteArrayOf(1, 2, 3))),
                Part(
                  functionCall =
                    FunctionCall(name = "f", args = mapOf("a" to "x", "n" to 1L, "b" to true))
                ),
                Part(
                  functionResponse =
                    FunctionResponse(name = "f", response = mapOf("r" to listOf("a", "b")))
                ),
              ),
          ),
        actions =
          EventActions(
            stateDelta = mutableMapOf("k" to "v", "num" to 7L),
            artifactDelta = mutableMapOf("art" to 2),
            agentState = TypedData.MapValue(mapOf("x" to TypedData.IntValue(5))),
            compaction =
              EventCompaction(
                startTimestamp = 1730874845000L,
                endTimestamp = 1730874846000L,
                compactedContent = Content(parts = listOf(Part(text = "c"))),
              ),
          ),
        finishReason = FinishReason.STOP,
        usageMetadata = UsageMetadata(promptTokenCount = 10, totalTokenCount = 20),
        customMetadata = mapOf("meta" to "data"),
        timestamp = 1730874845934L,
      )

    assertEquals(event, roundTrip(event))
  }

  // A session is persisted through this serializer, so a server-side tool call has to survive it or
  // the reloaded history is missing the call the model expects back.
  @Test
  fun event_serverSideToolParts_roundTripLosslessly() {
    val event =
      Event(
        author = "agent",
        content =
          Content(
            role = Role.MODEL,
            parts =
              listOf(
                Part(
                  toolCall =
                    ToolCall(
                      id = "tc1",
                      toolType = ToolType.URL_CONTEXT,
                      args = mapOf("url" to "https://example.com"),
                    )
                ),
                Part(
                  toolResponse =
                    ToolResponse(
                      id = "tc1",
                      toolType = ToolType("SOMETHING_NEW"),
                      response = mapOf("content" to "page text"),
                    )
                ),
              ),
          ),
      )

    assertEquals(event, roundTrip(event))
  }

  // Regression: adkJson must serialize dynamic defaults. The loop makes a same-millisecond
  // timestamp encode near-certain, so this fails reliably if default-omission returns.
  @Test
  fun event_defaultTimestampAndId_alwaysSerialized() {
    repeat(100) {
      val event = Event(author = "agent")

      val json = adkJson.encodeToString(Event.serializer(), event)

      assertTrue(json.contains("\"timestamp\""), "timestamp must always be serialized")
      assertTrue(json.contains("\"id\""), "id must always be serialized")
      assertEquals(event, roundTrip(event))
    }
  }

  @Test
  fun cacheMetadata_activeCache_roundTripsLosslessly() {
    val event =
      Event(
        author = "agent",
        cacheMetadata =
          CacheMetadata(
            fingerprint = "abc123",
            contentsCount = 4,
            cacheName = "projects/p/locations/l/cachedContents/456",
            expireTime = 1730874846000L,
            invocationsUsed = 2,
            createdAt = 1730874845000L,
          ),
        timestamp = 1730874845934L,
      )

    assertEquals(event.cacheMetadata, roundTrip(event).cacheMetadata)
  }

  @Test
  fun stateDelta_removedSentinel_roundTripsToRemoved() {
    val event =
      Event(
        author = "agent",
        actions = EventActions(stateDelta = mutableMapOf("gone" to State.REMOVED)),
        timestamp = 1L,
      )

    assertEquals(State.REMOVED, roundTrip(event).actions.stateDelta["gone"])
  }

  @Test
  fun freeFormValues_integers_decodeAsLong() {
    val serializer = MapSerializer(String.serializer(), AnySerializer)

    val decoded = adkJson.decodeFromString(serializer, """{"n": 42}""")

    assertEquals(42L, decoded["n"])
    assertTrue(decoded["n"] is Long)
  }

  @Test
  fun freeFormValues_nullMapValue_roundTrips() {
    // `FunctionCall.args` is a `Map<String, @Contextual Any?>`; null values are handled by the
    // framework's nullable wrapper around the (non-null) `AnySerializer`, so the round trip must
    // not
    // throw.
    val content =
      Content(
        parts =
          listOf(
            Part(
              functionCall =
                FunctionCall(name = "f", args = mapOf("present" to "v", "absent" to null))
            )
          )
      )

    val roundTripped = roundTrip(content).parts[0].functionCall!!

    assertEquals("v", roundTripped.args["present"])
    assertNull(roundTripped.args["absent"])
  }

  @Test
  fun agentState_typedDataSealedHierarchy_roundTrips() {
    val agentState =
      TypedData.ListValue(
        listOf(TypedData.StringValue("s"), TypedData.BooleanValue(true), TypedData.NullValue)
      )
    val event =
      Event(author = "agent", actions = EventActions(agentState = agentState), timestamp = 1L)

    assertEquals(agentState, roundTrip(event).actions.agentState)
  }

  @Test
  fun functionCall_partialArgValueSealedHierarchy_roundTrips() {
    val functionCall =
      FunctionCall(
        name = "f",
        partialArgs =
          listOf(
            PartialArg(value = PartialArgValue.StringValue("x"), jsonPath = "$.a"),
            PartialArg(value = PartialArgValue.NullValue),
          ),
      )
    val content = Content(parts = listOf(Part(functionCall = functionCall)))

    assertEquals(functionCall, roundTrip(content).parts[0].functionCall)
  }

  @Test
  fun anySerializer_unsupportedType_throws() {
    val serializer = MapSerializer(String.serializer(), AnySerializer)

    assertFailsWith<IllegalArgumentException> {
      adkJson.encodeToString(serializer, mapOf("bad" to Any()))
    }
  }

  @Test
  fun anySerializer_jsonNullForNonNull_throws() {
    assertFailsWith<IllegalStateException> { adkJson.decodeFromString(AnySerializer, "null") }
  }

  @Test
  fun anyToJsonElement_jsonElementToAny_roundTripsPlainTree() {
    // Integral numbers round-trip as Long and fractional numbers as Double.
    val tree =
      mapOf(
        "s" to "text",
        "n" to 42L,
        "d" to 1.5,
        "b" to true,
        "nested" to mapOf("list" to listOf(1L, 2L, 3L)),
      )

    assertEquals(tree, jsonElementToAny(anyToJsonElement(tree)))
  }

  @Test
  fun functionCall_throughAnyTreeBridge_roundTrips() {
    val functionCall = FunctionCall(id = "call-1", name = "lookup", args = mapOf("q" to "weather"))

    // The bridge A2A relies on: a @Serializable type <-> a plain Map/List tree (DataPart payload).
    val asMap =
      jsonElementToAny(adkJson.encodeToJsonElement(FunctionCall.serializer(), functionCall))
    assertTrue(asMap is Map<*, *>)

    val decoded = adkJson.decodeFromJsonElement(FunctionCall.serializer(), anyToJsonElement(asMap))

    assertEquals(functionCall, decoded)
  }

  @Test
  fun blobData_encodedToJson_isBase64StringNotNumberArray() {
    // A number array round-trips through this decoder just as happily as base64 does, so the wire
    // shape is the only thing that tells them apart. "AQID" is base64 for the bytes 1, 2, 3.
    val blob = Blob(mimeType = "image/png", data = byteArrayOf(1, 2, 3))

    val encoded = adkJson.encodeToString(Blob.serializer(), blob)

    assertTrue(encoded.contains("\"data\":\"AQID\""), encoded)
  }

  @Test
  fun blobData_base64RoundTrip_preservesBytes() {
    val blob = Blob(mimeType = "image/png", data = byteArrayOf(1, 2, 3))

    val decoded =
      adkJson.decodeFromString(Blob.serializer(), adkJson.encodeToString(Blob.serializer(), blob))

    assertEquals(blob, decoded)
  }

  @Test
  fun partThoughtSignature_encodedToJson_isBase64StringNotNumberArray() {
    val part = Part(text = "hello", thoughtSignature = byteArrayOf(1, 2, 3))

    val encoded = adkJson.encodeToString(Part.serializer(), part)

    assertTrue(encoded.contains("\"thoughtSignature\":\"AQID\""), encoded)
  }

  @Test
  fun partThoughtSignature_base64RoundTrip_preservesBytes() {
    val part = Part(text = "hello", thoughtSignature = byteArrayOf(1, 2, 3))

    val decoded =
      adkJson.decodeFromString(Part.serializer(), adkJson.encodeToString(Part.serializer(), part))

    assertEquals(part, decoded)
  }

  @Test
  fun timestamp_fractionalSeconds_decodesToMilliseconds() {
    // ADK Python writes the event timestamp as fractional epoch seconds (a time.time() float).
    val json = """{"id":"e1","author":"user","timestamp":1730874845.9344919}"""

    val decoded = adkJson.decodeFromString(Event.serializer(), json)

    assertEquals(1730874845934L, decoded.timestamp)
  }

  @Test
  fun timestamp_integerSeconds_decodesToMilliseconds() {
    // Chosen by magnitude, not syntax: a bare integer this small is seconds, not 1970-era millis.
    val json = """{"id":"e1","author":"user","timestamp":1730874845}"""

    val decoded = adkJson.decodeFromString(Event.serializer(), json)

    assertEquals(1730874845000L, decoded.timestamp)
  }

  @Test
  fun timestamp_millisInExponentialForm_decodesUnchanged() {
    // Exponential form is not a seconds marker; a value this large is already milliseconds.
    val json = """{"id":"e1","author":"user","timestamp":1.730874845934E12}"""

    val decoded = adkJson.decodeFromString(Event.serializer(), json)

    assertEquals(1730874845934L, decoded.timestamp)
  }

  @Test
  fun timestamp_implausiblyLarge_throws() {
    // A value that resolves past year 9999 is rejected rather than saturated to a far-future Long.
    val json = """{"id":"e1","author":"user","timestamp":1e300}"""

    assertFailsWith<SerializationException> { adkJson.decodeFromString(Event.serializer(), json) }
  }

  @Test
  fun timestamp_stringValue_throws() {
    // A quoted timestamp (even a quoted number) is malformed, not a bare number.
    val json = """{"id":"e1","author":"user","timestamp":"1730874845934"}"""

    assertFailsWith<SerializationException> { adkJson.decodeFromString(Event.serializer(), json) }
  }

  @Test
  fun timestamp_booleanValue_throws() {
    val json = """{"id":"e1","author":"user","timestamp":true}"""

    assertFailsWith<SerializationException> { adkJson.decodeFromString(Event.serializer(), json) }
  }

  @Test
  fun timestamp_nullValue_throws() {
    val json = """{"id":"e1","author":"user","timestamp":null}"""

    assertFailsWith<SerializationException> { adkJson.decodeFromString(Event.serializer(), json) }
  }

  @Test
  fun timestamp_integerMilliseconds_decodesUnchanged() {
    // This library's own output is integer milliseconds and must keep decoding as-is.
    val json = """{"id":"e1","author":"user","timestamp":1730874845934}"""

    val decoded = adkJson.decodeFromString(Event.serializer(), json)

    assertEquals(1730874845934L, decoded.timestamp)
  }

  @Test
  fun timestamp_isEncodedAsIntegerMilliseconds() {
    val encoded =
      adkJson.encodeToString(Event.serializer(), Event(author = "user", timestamp = 1730874845934L))

    assertTrue(encoded.contains("\"timestamp\":1730874845934"), encoded)
  }

  @Test
  fun compaction_fractionalSeconds_decodesToMilliseconds() {
    // ADK Python's EventCompaction start/end timestamps are fractional epoch seconds.
    val json =
      """{"start_timestamp":1730874845.5,"end_timestamp":1730874846.5,""" +
        """"compacted_content":{"parts":[{"text":"s"}]}}"""

    val decoded = adkJson.decodeFromString(EventCompaction.serializer(), json)

    assertEquals(1730874845500L, decoded.startTimestamp)
    assertEquals(1730874846500L, decoded.endTimestamp)
  }

  @Test
  fun cacheMetadata_fractionalSeconds_decodesToMilliseconds() {
    // ADK Python's CacheMetadata expire_time/created_at are fractional epoch seconds.
    val json =
      """{"fingerprint":"fp","contents_count":2,"cache_name":"c",""" +
        """"expire_time":1730874845.5,"invocations_used":1,"created_at":1730874840.25}"""

    val decoded = adkJson.decodeFromString(CacheMetadata.serializer(), json)

    assertEquals(1730874845500L, decoded.expireTime)
    assertEquals(1730874840250L, decoded.createdAt)
  }

  @Test
  fun event_fullySnakeCase_decodesEqualToCamelCaseDecode() {
    // Decodes a fully snake_case event across every aliased surface (event fields, actions'
    // state/artifact deltas + compaction, usage/grounding metadata, cache_metadata, node_info) and
    // compares to the camelCase decode; reverting the aliases drops these fields and fails this.
    val expected =
      Event(
        id = "evt-1",
        invocationId = "inv-1",
        author = "agent",
        content = Content(role = Role.MODEL, parts = listOf(Part(text = "hi"))),
        actions =
          EventActions(
            stateDelta = mutableMapOf("k" to "v"),
            artifactDelta = mutableMapOf("f" to 3),
            compaction =
              EventCompaction(
                startTimestamp = 1730874845000L,
                endTimestamp = 1730874846000L,
                compactedContent = Content(parts = listOf(Part(text = "summary"))),
              ),
          ),
        longRunningToolIds = setOf("t1"),
        turnComplete = true,
        errorCode = "E1",
        errorMessage = "boom",
        finishReason = FinishReason.STOP,
        usageMetadata =
          UsageMetadata(promptTokenCount = 5, candidatesTokenCount = 7, totalTokenCount = 12),
        groundingMetadata =
          GroundingMetadata(
            webSearchQueries = listOf("weather"),
            imageSearchQueries = listOf("cat"),
          ),
        modelVersion = "gemini-x",
        cacheMetadata =
          CacheMetadata(
            fingerprint = "fp",
            contentsCount = 2,
            cacheName = "projects/p/locations/l/cachedContents/1",
            expireTime = 1730874846000L,
            invocationsUsed = 1,
            createdAt = 1730874845000L,
          ),
        customMetadata = mapOf("k" to "v"),
        nodeInfo =
          NodeInfo(path = "wf@1/a@1", outputFor = listOf("wf@1/a@1"), messageAsOutput = true),
        timestamp = 1730874845934L,
      )

    val camel = adkJson.encodeToJsonElement(Event.serializer(), expected)
    // The camelCase form this library writes decodes back to the original.
    assertEquals(expected, adkJson.decodeFromJsonElement(Event.serializer(), camel))

    // A Python model_dump-style event renames every property to snake_case; the aliases must map it
    // back to the same object. The values in this event are all single-word, so rewriting object
    // keys does not touch free-form map contents (state_delta, custom_metadata).
    val snake = camelKeysToSnakeCase(camel)
    assertEquals(expected, adkJson.decodeFromJsonElement(Event.serializer(), snake))
  }

  /** Recursively rewrites every JSON object key from camelCase to snake_case. */
  private fun camelKeysToSnakeCase(element: JsonElement): JsonElement =
    when (element) {
      is JsonObject ->
        JsonObject(
          element.entries.associate { (key, value) ->
            camelToSnakeCase(key) to camelKeysToSnakeCase(value)
          }
        )
      is JsonArray -> JsonArray(element.map { camelKeysToSnakeCase(it) })
      else -> element
    }

  private fun camelToSnakeCase(name: String): String = buildString {
    for (char in name) {
      if (char.isUpperCase()) {
        append('_')
        append(char.lowercaseChar())
      } else {
        append(char)
      }
    }
  }
}
