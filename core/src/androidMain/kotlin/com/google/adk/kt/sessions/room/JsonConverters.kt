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
import com.google.adk.kt.events.Event
import com.google.adk.kt.serialization.Json
import com.google.adk.kt.serialization.eventFromMap
import com.google.adk.kt.serialization.toMap

/**
 * Room `@TypeConverter`s for state and event payloads stored in TEXT columns.
 *
 * Serialization goes through the platform [Json] serializer (`org.json` on Android — no reflection,
 * R8-friendly) plus the hand-written, reflection-free [Event] <-> map mapping in `EventJson.kt`.
 * State columns never persist the [com.google.adk.kt.sessions.State.REMOVED] sentinel (the DAO
 * strips removed keys before a map reaches [toStateJson]); the append-only event log keeps deltas
 * verbatim, and the mapper preserves the sentinel across the event round-trip.
 */
internal object JsonConverters {

  @TypeConverter
  fun fromStateJson(json: String): Map<String, Any> =
    Json.fromJsonToMap(json).filterValues { it != null }.mapValues { (_, v) -> v!! }

  @TypeConverter fun toStateJson(state: Map<String, Any>): String = Json.toJsonString(state)

  /**
   * Serializes a full [Event] to JSON via its reflection-free map form. The caller's [event] is
   * never mutated: [toMap] reads the event into a fresh map (encoding the `State.REMOVED`
   * sentinel).
   */
  fun eventToJson(event: Event): String = Json.toJsonString(event.toMap())

  /**
   * Deserializes an [Event] from JSON, re-hydrating the `State.REMOVED` sentinel in
   * `event.actions.stateDelta`.
   */
  fun eventFromJson(json: String): Event = eventFromMap(Json.fromJsonToMap(json))
}
