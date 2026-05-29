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

package com.google.adk.kt.sessions.room

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.adk.kt.events.Event
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.sessions.State
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests for [JsonConverters] in isolation — exercises the converter API directly without spinning
 * up a Room database. Behaviors reachable end-to-end via [RoomSessionService] are covered in
 * [RoomSessionServiceTest]; this file covers cases that are awkward (or unreachable) through the
 * full stack.
 */
@RunWith(AndroidJUnit4::class)
class JsonConvertersTest {

  @Test
  fun stateJson_primitives_roundTrip() {
    val original: Map<String, Any> =
      mapOf(
        "string" to "value",
        "int" to 42,
        "double" to 1.5,
        "boolean" to true,
        "list" to listOf("a", "b"),
      )

    val decoded = JsonConverters.fromStateJson(JsonConverters.toStateJson(original))

    // org.json preserves numeric types (unlike Gson, which decodes everything as Double), so assert
    // on the numeric value rather than the boxed type.
    assertThat(decoded["string"]).isEqualTo("value")
    assertThat((decoded["int"] as Number).toInt()).isEqualTo(42)
    assertThat((decoded["double"] as Number).toDouble()).isEqualTo(1.5)
    assertThat(decoded["boolean"]).isEqualTo(true)
    assertThat(decoded["list"]).isEqualTo(listOf("a", "b"))
  }

  @Test
  fun fromStateJson_explicitNulls_areDropped() {
    val decoded = JsonConverters.fromStateJson("""{"present": "v", "missing": null}""")

    assertThat(decoded.containsKey("present")).isTrue()
    assertThat(decoded["present"]).isEqualTo("v")
    assertThat(decoded.containsKey("missing")).isFalse()
  }

  @Test
  fun eventToJson_doesNotMutateCaller() {
    // eventToJson serializes a copy, so the caller's REMOVED sentinel survives unchanged.
    val event =
      Event(
        author = "agent",
        actions = EventActions(stateDelta = mutableMapOf<String, Any>("k" to State.REMOVED)),
        timestamp = 1L,
      )

    val unused = JsonConverters.eventToJson(event)

    assertThat(event.actions.stateDelta["k"]).isSameInstanceAs(State.REMOVED)
  }

  @Test
  fun eventJson_removedSentinel_roundTrips() {
    val event =
      Event(
        author = "agent",
        actions = EventActions(stateDelta = mutableMapOf<String, Any>("k" to State.REMOVED)),
        timestamp = 12345L,
      )

    val decoded = JsonConverters.eventFromJson(JsonConverters.eventToJson(event))

    assertThat(decoded.actions.stateDelta["k"]).isSameInstanceAs(State.REMOVED)
    assertThat(decoded.timestamp).isEqualTo(12345L)
  }
}
