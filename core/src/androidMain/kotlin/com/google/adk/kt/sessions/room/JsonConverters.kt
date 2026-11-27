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
import com.google.adk.kt.annotations.ExperimentalAuthApi
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.annotations.SecretAccess
import com.google.adk.kt.auth.adkSecretRevealingJson
import com.google.adk.kt.events.Event
import com.google.adk.kt.serialization.AnySerializer
import com.google.adk.kt.serialization.adkJson
import com.google.adk.kt.sessions.NullableStateMapSerializer
import com.google.adk.kt.sessions.forStorage
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Room `@TypeConverter`s for state and event payloads stored in TEXT columns.
 *
 * Serialization goes through `kotlinx.serialization`: reads use the shared [adkJson] instance, and
 * writes keep credential secrets where ADK reads them back. State maps read the credentials under
 * ADK credential keys typed and use a contextual [AnySerializer] for other free-form values; events
 * use the generated [Event] serializer. State columns never persist the
 * [com.google.adk.kt.sessions.State.REMOVED] sentinel (the DAO strips removed keys before a map
 * reaches [toStateJson]); the append-only event log keeps deltas verbatim, and [AnySerializer]
 * preserves the sentinel across the event round-trip.
 */
@OptIn(FrameworkInternalApi::class)
internal object JsonConverters {

  private val stateSerializer = MapSerializer(String.serializer(), AnySerializer)

  /**
   * Reads a state map, dropping any keys whose JSON value is explicitly `null`. A credential under
   * an ADK credential key is read typed, and a malformed one is dropped.
   */
  @TypeConverter
  fun fromStateJson(json: String): Map<String, Any> =
    adkJson
      .decodeFromString(NullableStateMapSerializer, json)
      .filterValues { it != null }
      .mapValues { (_, v) -> v!! }

  @TypeConverter
  @OptIn(ExperimentalAuthApi::class, SecretAccess::class)
  fun toStateJson(state: Map<String, Any>): String =
    adkSecretRevealingJson.encodeToString(stateSerializer, state)

  /**
   * Serializes a full [Event] to JSON, with the secrets of typed credentials in its state delta,
   * output and requested auth configs only. The caller's [event] is never mutated.
   */
  @OptIn(ExperimentalAuthApi::class, SecretAccess::class)
  fun eventToJson(event: Event): String =
    adkSecretRevealingJson.encodeToString(Event.serializer(), event.forStorage())

  /**
   * Deserializes an [Event] from JSON, preserving the `State.REMOVED` sentinel in
   * `event.actions.stateDelta`.
   */
  fun eventFromJson(json: String): Event = adkJson.decodeFromString(Event.serializer(), json)
}
