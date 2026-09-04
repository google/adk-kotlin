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

import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json
import org.junit.Test

/**
 * Pins the emitted JSON for the live types whose wire form is not what the Kotlin default produces.
 *
 * A duration is `"10s"` on this API, but kotlinx writes ISO-8601 (`"PT10S"`) unless the field names
 * a serializer, and an enum constant is whatever the declaration is called. Both are silent: each
 * type still round-trips through itself, so only an assertion on the wire form catches a change.
 */
class LiveTypeWireFormatTest {

  private val json = Json

  @Test
  fun liveServerGoAway_encoded_writesTimeLeftAsSecondsString() {
    val encoded = json.encodeToString(LiveServerGoAway.serializer(), LiveServerGoAway(10.seconds))

    assertEquals("""{"timeLeft":"10s"}""", encoded)
  }

  @Test
  fun voiceActivity_encoded_writesAudioOffsetAsSecondsString() {
    val activity = VoiceActivity(audioOffset = 1500.milliseconds)

    val encoded = json.encodeToString(VoiceActivity.serializer(), activity)

    assertEquals("""{"audioOffset":"1.5s"}""", encoded)
  }

  @Test
  fun voiceActivity_encoded_writesTheTypeNameTheApiUses() {
    val activity = VoiceActivity(voiceActivityType = VoiceActivityType.ACTIVITY_START)

    val encoded = json.encodeToString(VoiceActivity.serializer(), activity)

    assertEquals("""{"voiceActivityType":"ACTIVITY_START"}""", encoded)
  }

  @Test
  fun voiceActivityType_unspecified_isNamedTypeUnspecified() {
    val activity = VoiceActivity(voiceActivityType = VoiceActivityType.TYPE_UNSPECIFIED)

    val encoded = json.encodeToString(VoiceActivity.serializer(), activity)

    assertEquals("""{"voiceActivityType":"TYPE_UNSPECIFIED"}""", encoded)
  }

  @Test
  fun liveServerGoAway_decodedFromSecondsString_readsTheDuration() {
    // Decode is the direction that runs: these are server-sent frames.
    val decoded = json.decodeFromString(LiveServerGoAway.serializer(), """{"timeLeft":"1.5s"}""")

    assertEquals(1500.milliseconds, decoded.timeLeft)
  }

  @Test
  fun voiceActivity_decodedFromSecondsString_readsTheOffset() {
    val decoded = json.decodeFromString(VoiceActivity.serializer(), """{"audioOffset":"10s"}""")

    assertEquals(10.seconds, decoded.audioOffset)
  }
}
