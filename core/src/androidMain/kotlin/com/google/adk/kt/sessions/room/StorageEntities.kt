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

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room `@Entity` row types backing [RoomSessionService].
 *
 * Four tables map to the four buckets of session-related state:
 *
 * * [StorageSession] — per-session metadata + session-scoped state.
 * * [StorageEvent] — append-only event log, FK-cascade-deleted with its session.
 * * [StorageAppState] — app-scoped state (the `app:` prefix), one row per app.
 * * [StorageUserState] — user-scoped state (the `user:` prefix), one row per `(app, user)` pair.
 *
 * Kept in a single file because all four are `internal`, share the same `@Database`, and evolve
 * together when the schema version bumps. Column and table names follow the Kotlin property/class
 * names (Room defaults), so there are no `@ColumnInfo`/`tableName` overrides.
 */

/** A persisted session: session-scoped state plus create/update timestamps. */
@Entity(
  primaryKeys = ["appName", "userId", "id"],
  // Backs SessionsDao.listSessions which filters by (appName, userId) and orders by updateTime;
  // without this index SQLite has to filesort the matching rows on every list call.
  indices = [Index(value = ["appName", "userId", "updateTime"])],
)
internal data class StorageSession(
  val appName: String,
  val userId: String,
  val id: String,
  /** Session-scoped state (no `app:` / `user:` keys). Persisted as JSON via [JsonConverters]. */
  val state: Map<String, Any>,
  val createTime: Long,
  val updateTime: Long,
)

/**
 * One event row per [com.google.adk.kt.events.Event] appended to a session, stored as a
 * JSON-encoded payload in [eventData] plus a few promoted columns for indexing.
 */
@Entity(
  primaryKeys = ["id", "appName", "userId", "sessionId"],
  foreignKeys =
    [
      ForeignKey(
        entity = StorageSession::class,
        parentColumns = ["appName", "userId", "id"],
        childColumns = ["appName", "userId", "sessionId"],
        onDelete = ForeignKey.CASCADE,
      )
    ],
  // timestamp DESC mirrors ADK Python
  indices =
    [
      Index(
        value = ["appName", "userId", "sessionId", "timestamp"],
        orders = [Index.Order.ASC, Index.Order.ASC, Index.Order.ASC, Index.Order.DESC],
      )
    ],
)
internal data class StorageEvent(
  val id: String,
  val appName: String,
  val userId: String,
  val sessionId: String,
  val invocationId: String?,
  val timestamp: Long,
  /** JSON-encoded full [com.google.adk.kt.events.Event] payload. */
  val eventData: String,
)

/** App-scoped state (the `app:` prefix) for one app. */
@Entity
internal data class StorageAppState(
  @PrimaryKey val appName: String,
  /**
   * App-scoped state with `app:` prefix stripped from keys. Persisted as JSON via [JsonConverters].
   */
  val state: Map<String, Any>,
  val updateTime: Long,
)

/** User-scoped state (the `user:` prefix) for one user. */
@Entity(primaryKeys = ["appName", "userId"])
internal data class StorageUserState(
  val appName: String,
  val userId: String,
  /**
   * User-scoped state with `user:` prefix stripped from keys. Persisted as JSON via
   * [JsonConverters].
   */
  val state: Map<String, Any>,
  val updateTime: Long,
)
