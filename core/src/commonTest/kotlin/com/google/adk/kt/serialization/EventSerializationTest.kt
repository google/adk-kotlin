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
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.PartialArg
import com.google.adk.kt.types.PartialArgValue
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.UsageMetadata
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
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
                startTimestamp = 1L,
                endTimestamp = 2L,
                compactedContent = Content(parts = listOf(Part(text = "c"))),
              ),
          ),
        finishReason = FinishReason.STOP,
        usageMetadata = UsageMetadata(promptTokenCount = 10, totalTokenCount = 20),
        customMetadata = mapOf("meta" to "data"),
        timestamp = 1234L,
      )

    assertEquals(event, roundTrip(event))
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
            expireTime = 1_800_000L,
            invocationsUsed = 2,
            createdAt = 1_000L,
          ),
        timestamp = 1L,
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
}
