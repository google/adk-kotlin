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

import androidx.room.TypeConverter
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.events.Event
import com.google.adk.kt.serialization.AnySerializer
import com.google.adk.kt.serialization.adkJson
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer

/**
 * Room `@TypeConverter`s for state and event payloads stored in TEXT columns.
 *
 * Serialization goes through `kotlinx.serialization` (the shared [adkJson] instance). State maps
 * use a contextual [AnySerializer] for their free-form values; events use the generated [Event]
 * serializer. State columns never persist the [com.google.adk.kt.sessions.State.REMOVED] sentinel
 * (the DAO strips removed keys before a map reaches [toStateJson]); the append-only event log keeps
 * deltas verbatim, and [AnySerializer] preserves the sentinel across the event round-trip.
 */
@OptIn(FrameworkInternalApi::class)
internal object JsonConverters {

  private val stateSerializer = MapSerializer(String.serializer(), AnySerializer)
  private val nullableStateSerializer = MapSerializer(String.serializer(), AnySerializer.nullable)

  /** Reads a state map, dropping any keys whose JSON value is explicitly `null`. */
  @TypeConverter
  fun fromStateJson(json: String): Map<String, Any> =
    adkJson
      .decodeFromString(nullableStateSerializer, json)
      .filterValues { it != null }
      .mapValues { (_, v) -> v!! }

  @TypeConverter
  fun toStateJson(state: Map<String, Any>): String = adkJson.encodeToString(stateSerializer, state)

  /** Serializes a full [Event] to JSON. The caller's [event] is never mutated. */
  fun eventToJson(event: Event): String = adkJson.encodeToString(Event.serializer(), event)

  /**
   * Deserializes an [Event] from JSON, preserving the `State.REMOVED` sentinel in
   * `event.actions.stateDelta`.
   */
  fun eventFromJson(json: String): Event = adkJson.decodeFromString(Event.serializer(), json)
}
