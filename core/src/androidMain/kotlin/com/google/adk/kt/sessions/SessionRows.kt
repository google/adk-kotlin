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

package com.google.adk.kt.sessions

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room `@Entity` row types backing [RoomSessionService].
 *
 * Four tables map to the four buckets of session-related state:
 *
 * * [SessionRow] — per-session metadata + session-scoped state.
 * * [EventRow] — append-only event log, FK-cascade-deleted with its session.
 * * [AppStateRow] — app-scoped state (the `app:` prefix), one row per app.
 * * [UserStateRow] — user-scoped state (the `user:` prefix), one row per `(app, user)` pair.
 *
 * Kept in a single file because all four are `internal`, share the same `@Database`, and evolve
 * together when the schema version bumps.
 */

/** Row in the `sessions` table. Stores session-scoped state plus create/update timestamps. */
@Entity(
  tableName = "sessions",
  primaryKeys = ["app_name", "user_id", "id"],
  // Backs SessionsDao.listSessions which filters by (app_name, user_id) and orders by update_time;
  // without this index SQLite has to filesort the matching rows on every list call.
  indices = [Index(value = ["app_name", "user_id", "update_time"])],
)
internal data class SessionRow(
  @ColumnInfo(name = "app_name") val appName: String,
  @ColumnInfo(name = "user_id") val userId: String,
  val id: String,
  /** Session-scoped state (no `app:` / `user:` keys). Persisted as JSON via [JsonConverters]. */
  val state: Map<String, Any>,
  @ColumnInfo(name = "create_time") val createTimeEpochMs: Long,
  @ColumnInfo(name = "update_time") val updateTimeEpochMs: Long,
)

/**
 * Row in the `events` table. One event row per [com.google.adk.kt.events.Event] appended to a
 * session, stored as a JSON-encoded payload in [eventData] plus a few promoted columns for
 * indexing.
 */
@Entity(
  tableName = "events",
  primaryKeys = ["app_name", "user_id", "session_id", "id"],
  foreignKeys =
    [
      ForeignKey(
        entity = SessionRow::class,
        parentColumns = ["app_name", "user_id", "id"],
        childColumns = ["app_name", "user_id", "session_id"],
        onDelete = ForeignKey.CASCADE,
      )
    ],
  indices = [Index(value = ["app_name", "user_id", "session_id", "timestamp"])],
)
internal data class EventRow(
  val id: String,
  @ColumnInfo(name = "app_name") val appName: String,
  @ColumnInfo(name = "user_id") val userId: String,
  @ColumnInfo(name = "session_id") val sessionId: String,
  @ColumnInfo(name = "invocation_id") val invocationId: String?,
  val timestamp: Long,
  /** JSON-encoded full [com.google.adk.kt.events.Event] payload. */
  @ColumnInfo(name = "event_data") val eventData: String,
)

/** Row in the `app_states` table. Holds app-scoped state (the `app:` prefix) for one app. */
@Entity(tableName = "app_states")
internal data class AppStateRow(
  @PrimaryKey @ColumnInfo(name = "app_name") val appName: String,
  /**
   * App-scoped state with `app:` prefix stripped from keys. Persisted as JSON via [JsonConverters].
   */
  val state: Map<String, Any>,
  @ColumnInfo(name = "update_time") val updateTimeEpochMs: Long,
)

/** Row in the `user_states` table. Holds user-scoped state (the `user:` prefix) for one user. */
@Entity(tableName = "user_states", primaryKeys = ["app_name", "user_id"])
internal data class UserStateRow(
  @ColumnInfo(name = "app_name") val appName: String,
  @ColumnInfo(name = "user_id") val userId: String,
  /**
   * User-scoped state with `user:` prefix stripped from keys. Persisted as JSON via
   * [JsonConverters].
   */
  val state: Map<String, Any>,
  @ColumnInfo(name = "update_time") val updateTimeEpochMs: Long,
)
