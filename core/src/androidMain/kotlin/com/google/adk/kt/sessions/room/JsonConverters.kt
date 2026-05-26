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
import com.google.adk.kt.sessions.State
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Gson-backed Room `@TypeConverter`s for state and event payloads stored in TEXT columns.
 *
 * State columns never persist the [State.REMOVED] sentinel: every write goes through the DAO's
 * state merge, which strips removed keys before the map reaches [toStateJson]. Only the append-only
 * event log stores deltas verbatim, so [eventToJson] / [eventFromJson] encode the sentinel as a
 * literal marker string on write and re-hydrate it on read.
 */
internal object JsonConverters {

  private const val REMOVED_MARKER: String = "__ADK_SENTINEL_REMOVED__"

  private val gson: Gson = Gson()

  private val stateMapType = object : TypeToken<Map<String, Any?>>() {}

  @TypeConverter
  fun fromStateJson(json: String): Map<String, Any> {
    val parsed: Map<String, Any?> = gson.fromJson(json, stateMapType) ?: return emptyMap()
    return parsed.filterValues { it != null }.mapValues { (_, v) -> v!! }
  }

  @TypeConverter fun toStateJson(state: Map<String, Any>): String = gson.toJson(state)

  /**
   * Serializes a full [Event] to JSON. Applies the [State.REMOVED] sentinel marker substitution to
   * `event.actions.stateDelta` so a delete entry survives the JSON round-trip — without this, Gson
   * would emit the sentinel singleton as `{}` and a replay would see an empty map instead of a
   * removal.
   *
   * The caller's [event] is never mutated; the substitution happens on a defensive copy of the
   * state delta to avoid races with concurrent readers of `event.actions.stateDelta`.
   */
  fun eventToJson(event: Event): String {
    val safeDelta =
      event.actions.stateDelta.mapValuesTo(mutableMapOf<String, Any>()) { (_, v) ->
        if (v === State.REMOVED) REMOVED_MARKER else v
      }
    val safeEvent = event.copy(actions = event.actions.copy(stateDelta = safeDelta))
    return gson.toJson(safeEvent)
  }

  /**
   * Deserializes an [Event] from JSON, re-hydrating the [State.REMOVED] sentinel in
   * `event.actions.stateDelta`. The returned Event is freshly allocated, so in-place mutation is
   * safe here.
   */
  fun eventFromJson(json: String): Event {
    val event = gson.fromJson(json, Event::class.java)
    val stateDelta = event.actions.stateDelta
    for ((k, v) in stateDelta.toMap()) {
      if (v == REMOVED_MARKER) stateDelta[k] = State.REMOVED
    }
    return event
  }
}
