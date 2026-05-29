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

@file:OptIn(FrameworkInternalApi::class)

package com.google.adk.kt.serialization

import com.google.adk.kt.agents.TypedData
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.events.Event
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.events.EventCompaction
import com.google.adk.kt.events.ToolConfirmation
import com.google.adk.kt.sessions.State
import com.google.adk.kt.testing.modelEvent
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.userEvent
import com.google.adk.kt.testing.userFunctionResponse
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.Citation
import com.google.adk.kt.types.CitationMetadata
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FileData
import com.google.adk.kt.types.FinishReason
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.GroundingMetadata
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.PartialArg
import com.google.adk.kt.types.PartialArgValue
import com.google.adk.kt.types.UsageMetadata
import com.google.common.truth.Truth.assertThat
import kotlin.test.assertFailsWith
import org.junit.Test

/**
 * Round-trip tests for the reflection-free [Event] <-> map serialization in `EventJson.kt`.
 *
 * Each `assertRoundTrips` proves `eventFromMap(event.toMap()) == event` for one slice of the graph;
 * a few tests additionally assert the encoded map shape (Base64 bytes, the `REMOVED` marker).
 */
class EventJsonTest {

  private fun assertRoundTrips(event: Event) {
    assertThat(eventFromMap(event.toMap())).isEqualTo(event)
  }

  @Suppress("UNCHECKED_CAST")
  private fun Map<String, Any?>.obj(key: String) = this[key] as Map<String, Any?>

  @Suppress("UNCHECKED_CAST")
  private fun asObj(value: Any?): Map<String, Any?> = value as Map<String, Any?>

  // -------------------- basics / scalar fields --------------------

  @Test
  fun minimalEvent_roundTrips() {
    assertRoundTrips(Event(author = "user"))
  }

  @Test
  fun userEvent_helper_roundTrips() {
    assertRoundTrips(userEvent("a question", timestamp = 5L))
  }

  @Test
  fun modelEvent_helper_roundTrips() {
    assertRoundTrips(modelEvent("an answer", timestamp = 7L))
  }

  @Test
  fun allScalarFields_roundTrip() {
    assertRoundTrips(
      Event(
        id = "e1",
        invocationId = "inv1",
        author = "agent",
        longRunningToolIds = setOf("a", "b"),
        partial = true,
        turnComplete = true,
        errorCode = "ERR",
        errorMessage = "boom",
        finishReason = FinishReason.MAX_TOKENS,
        avgLogProbs = -0.5,
        interrupted = true,
        branch = "a.b.c",
        modelVersion = "v2",
        timestamp = 123L,
      )
    )
  }

  @Test
  fun finishReason_allValues_roundTrip() {
    for (reason in FinishReason.entries) {
      assertRoundTrips(Event(author = "model", finishReason = reason))
    }
  }

  // -------------------- content / parts --------------------

  @Test
  fun textContent_helpers_roundTrip() {
    assertRoundTrips(Event(author = "user", content = userMessage("hi")))
    assertRoundTrips(Event(author = "model", content = modelMessage("hello")))
  }

  @Test
  fun multipleParts_roundTrip() {
    assertRoundTrips(
      Event(
        author = "model",
        content = Content(role = "model", parts = listOf(Part(text = "one"), Part(text = "two"))),
      )
    )
  }

  @Test
  fun inlineDataBlob_bytesEncodedAsBase64String() {
    val event =
      Event(
        author = "user",
        content =
          Content(
            parts =
              listOf(
                Part(inlineData = Blob(mimeType = "image/png", data = byteArrayOf(0, 1, 127, -1)))
              )
          ),
      )
    assertRoundTrips(event)

    val parts = event.toMap().obj("content")["parts"] as List<*>
    val blob = asObj(parts[0]).obj("inlineData")
    assertThat(blob["data"]).isInstanceOf(String::class.java)
  }

  @Test
  fun fileData_roundTrips() {
    assertRoundTrips(
      Event(
        author = "user",
        content =
          Content(
            parts =
              listOf(
                Part(fileData = FileData(mimeType = "text/plain", displayName = "o", fileUri = "u"))
              )
          ),
      )
    )
  }

  @Test
  fun functionCall_roundTrips() {
    assertRoundTrips(
      Event(
        author = "model",
        content =
          Content(
            parts =
              listOf(
                Part(
                  functionCall =
                    FunctionCall(
                      name = "lookup",
                      args = mapOf("q" to "kotlin", "n" to 3, "nested" to mapOf("k" to true)),
                      id = "fc1",
                      willContinue = false,
                    )
                )
              )
          ),
      )
    )
  }

  @Test
  fun functionCall_partialArgs_allValueKinds_roundTrip() {
    assertRoundTrips(
      Event(
        author = "model",
        content =
          Content(
            parts =
              listOf(
                Part(
                  functionCall =
                    FunctionCall(
                      name = "stream",
                      partialArgs =
                        listOf(
                          PartialArg(value = PartialArgValue.StringValue("s"), jsonPath = "p"),
                          PartialArg(value = PartialArgValue.NumberValue(1.5)),
                          PartialArg(value = PartialArgValue.BoolValue(true)),
                          PartialArg(value = PartialArgValue.NullValue, willContinue = true),
                          PartialArg(value = null),
                        ),
                    )
                )
              )
          ),
      )
    )
  }

  @Test
  fun functionResponse_helper_roundTrips() {
    assertRoundTrips(
      Event(
        author = "user",
        content = userFunctionResponse("lookup", "fc1", mapOf("result" to listOf(1, 2, 3))),
      )
    )
  }

  @Test
  fun thoughtAndSignature_roundTrips() {
    assertRoundTrips(
      Event(
        author = "model",
        content =
          Content(parts = listOf(Part(thought = true, thoughtSignature = byteArrayOf(9, 8, 7)))),
      )
    )
  }

  @Test
  fun opaqueData_roundTrips() {
    assertRoundTrips(
      Event(
        author = "model",
        content = Content(parts = listOf(Part(opaqueData = mapOf("k" to "v")))),
      )
    )
  }

  // -------------------- EventActions --------------------

  @Test
  fun eventActions_allFields_roundTrip() {
    assertRoundTrips(
      Event(
        author = "agent",
        actions =
          EventActions(
            skipSummarization = true,
            stateDelta = mutableMapOf("k" to "v"),
            artifactDelta = mutableMapOf("f.txt" to 2),
            transferToAgent = "other",
            escalate = true,
            endOfAgent = true,
            requestedToolConfirmations =
              mutableMapOf(
                "fc1" to ToolConfirmation(confirmed = true, payload = mapOf("a" to 1), hint = "ok?")
              ),
            rewindBeforeInvocationId = "inv0",
            agentState = TypedData.StringValue("s"),
            compaction = EventCompaction(1L, 2L, modelMessage("summary")),
          ),
      )
    )
  }

  @Test
  fun stateDelta_removedSentinel_encodedAsMarkerAndRestored() {
    val event =
      Event(
        author = "agent",
        actions = EventActions(stateDelta = mutableMapOf("gone" to State.REMOVED, "kept" to "v")),
      )

    val stateDelta = event.toMap().obj("actions")["stateDelta"] as Map<*, *>
    assertThat(stateDelta["gone"]).isEqualTo(REMOVED_MARKER)
    assertThat(stateDelta["kept"]).isEqualTo("v")

    val restored = eventFromMap(event.toMap())
    assertThat(restored.actions.stateDelta["gone"]).isSameInstanceAs(State.REMOVED)
    assertThat(restored.actions.stateDelta["kept"]).isEqualTo("v")
  }

  @Test
  fun toolConfirmation_minimal_roundTrips() {
    assertRoundTrips(
      Event(
        author = "agent",
        actions =
          EventActions(
            requestedToolConfirmations = mutableMapOf("id" to ToolConfirmation(confirmed = false))
          ),
      )
    )
  }

  // -------------------- TypedData (agentState) --------------------

  @Test
  fun agentState_typedData_allVariants_roundTrip() {
    val variants =
      listOf(
        TypedData.NullValue,
        TypedData.IntValue(1),
        TypedData.LongValue(2L),
        TypedData.StringValue("s"),
        TypedData.BooleanValue(true),
        TypedData.DoubleValue(1.5),
        TypedData.ListValue(listOf(TypedData.IntValue(7), TypedData.NullValue)),
        TypedData.MapValue(
          mapOf(
            "a" to TypedData.StringValue("x"),
            "b" to TypedData.ListValue(listOf(TypedData.BooleanValue(false))),
          )
        ),
      )
    for (v in variants) {
      assertRoundTrips(Event(author = "agent", actions = EventActions(agentState = v)))
    }
  }

  // -------------------- metadata + free-form --------------------

  @Test
  fun usageMetadata_roundTrips() {
    assertRoundTrips(Event(author = "model", usageMetadata = UsageMetadata(10, 20, 30)))
  }

  @Test
  fun groundingMetadata_roundTrips() {
    assertRoundTrips(
      Event(author = "model", groundingMetadata = GroundingMetadata(listOf("q1", "q2")))
    )
  }

  @Test
  fun citationMetadata_roundTrips() {
    assertRoundTrips(
      Event(
        author = "model",
        citationMetadata = CitationMetadata(listOf(Citation("t1"), Citation())),
      )
    )
  }

  @Test
  fun customMetadata_nested_roundTrips() {
    assertRoundTrips(
      Event(
        author = "model",
        customMetadata =
          mapOf(
            "s" to "v",
            "n" to 5,
            "b" to true,
            "list" to listOf(1, "two", false),
            "map" to mapOf("x" to 1),
          ),
      )
    )
  }

  // -------------------- whole graph --------------------

  @Test
  fun fullyPopulatedEvent_roundTrips() {
    assertRoundTrips(
      Event(
        id = "event-1",
        invocationId = "inv-1",
        author = "agent",
        content =
          Content(
            role = "model",
            parts =
              listOf(
                Part(text = "hello"),
                Part(inlineData = Blob(mimeType = "image/png", data = byteArrayOf(1, 2, 3))),
                Part(
                  functionCall =
                    FunctionCall(name = "lookup", args = mapOf("q" to "k"), id = "fc-1")
                ),
                Part(functionResponse = FunctionResponse(name = "lookup", id = "fc-1")),
                Part(thought = true, thoughtSignature = byteArrayOf(4, 5)),
              ),
          ),
        actions =
          EventActions(
            skipSummarization = true,
            stateDelta = mutableMapOf("keep" to "v", "remove" to State.REMOVED),
            artifactDelta = mutableMapOf("file.txt" to 3),
            transferToAgent = "other-agent",
            agentState =
              TypedData.MapValue(mapOf("i" to TypedData.IntValue(1), "n" to TypedData.NullValue)),
            compaction = EventCompaction(100L, 200L, modelMessage("summary")),
          ),
        longRunningToolIds = setOf("fc-1"),
        turnComplete = true,
        finishReason = FinishReason.STOP,
        usageMetadata = UsageMetadata(10, 20, 30),
        avgLogProbs = 0.25,
        interrupted = true,
        branch = "a.b",
        modelVersion = "v1",
        citationMetadata = CitationMetadata(listOf(Citation("t"))),
        customMetadata = mapOf("k" to "v", "nested" to mapOf("x" to 1)),
        timestamp = 1234567890L,
      )
    )
  }

  // -------------------- negative cases --------------------

  @Test
  fun typedDataFromMap_unknownType_throws() {
    assertFailsWith<IllegalArgumentException> {
      eventFromMap(
        mapOf("author" to "a", "actions" to mapOf("agentState" to mapOf("__type" to "bogus")))
      )
    }
  }

  @Test
  fun customMetadata_unsupportedValueType_throwsOnEncode() {
    assertFailsWith<IllegalArgumentException> {
      Event(author = "a", customMetadata = mapOf("bad" to Any())).toMap()
    }
  }

  @Test
  fun finishReason_unknownName_decodesToNull() {
    assertThat(eventFromMap(mapOf("author" to "a", "finishReason" to "BOGUS_FUTURE")).finishReason)
      .isNull()
  }

  // -------------------- deserialization fallbacks (fromMap defaults) --------------------

  @Test
  fun eventFromMap_emptyMap_usesDefaults() {
    val event = eventFromMap(emptyMap())
    assertThat(event.id).isNotEmpty()
    assertThat(event.author).isEqualTo("")
    assertThat(event.timestamp).isEqualTo(0L)
    assertThat(event.actions).isEqualTo(EventActions())
    assertThat(event.content).isNull()
    assertThat(event.longRunningToolIds).isEmpty()
    assertThat(event.finishReason).isNull()
    assertThat(event.customMetadata).isNull()
  }

  @Test
  fun eventFromMap_partialNestedObjects_useDefaults() {
    val event =
      eventFromMap(
        mapOf(
          "author" to "a",
          "content" to
            mapOf(
              "parts" to
                listOf(
                  mapOf("functionCall" to emptyMap<String, Any?>()),
                  mapOf("functionResponse" to emptyMap<String, Any?>()),
                )
            ),
          "actions" to mapOf("compaction" to emptyMap<String, Any?>()),
        )
      )

    val parts = event.content!!.parts
    assertThat(parts[0].functionCall!!.name).isEqualTo("")
    assertThat(parts[0].functionCall!!.args).isEmpty()
    assertThat(parts[1].functionResponse!!.name).isEqualTo("")
    assertThat(parts[1].functionResponse!!.response).isEmpty()
    assertThat(event.actions.compaction).isEqualTo(EventCompaction(0L, 0L, Content()))
  }

  @Test
  fun customMetadata_nullValue_isDropped() {
    val event =
      eventFromMap(mapOf("author" to "a", "customMetadata" to mapOf("keep" to "v", "drop" to null)))
    assertThat(event.customMetadata).containsExactly("keep", "v")
  }

  @Test
  fun customMetadata_floatValue_roundTrips() {
    // A non-Int/Long/Double Number (Float) exercises encodeFree's generic Number branch.
    assertRoundTrips(Event(author = "a", customMetadata = mapOf("f" to 1.5f)))
  }
}
