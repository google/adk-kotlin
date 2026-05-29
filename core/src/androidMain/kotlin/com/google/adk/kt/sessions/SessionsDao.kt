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

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

/** Room DAO for [AdkSessionsDatabase]. */
@Dao
internal interface SessionsDao {

  // ---------------- sessions ----------------

  @Query(
    "SELECT * FROM sessions WHERE app_name = :appName AND user_id = :userId AND id = :id LIMIT 1"
  )
  suspend fun getSession(appName: String, userId: String, id: String): SessionRow?

  @Query(
    """
    SELECT * FROM sessions
     WHERE app_name = :appName AND user_id = :userId
     ORDER BY update_time DESC
  """
  )
  suspend fun listSessions(appName: String, userId: String): List<SessionRow>

  @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertSession(row: SessionRow)

  @Query(
    """
    UPDATE sessions
       SET state = :state, update_time = :updateTime
     WHERE app_name = :appName AND user_id = :userId AND id = :id
  """
  )
  suspend fun updateSessionState(
    appName: String,
    userId: String,
    id: String,
    state: Map<String, Any>,
    updateTime: Long,
  )

  @Query(
    """
    UPDATE sessions
       SET update_time = :updateTime
     WHERE app_name = :appName AND user_id = :userId AND id = :id
  """
  )
  suspend fun touchSession(appName: String, userId: String, id: String, updateTime: Long)

  @Query("DELETE FROM sessions WHERE app_name = :appName AND user_id = :userId AND id = :id")
  suspend fun deleteSession(appName: String, userId: String, id: String)

  // ---------------- events ----------------

  // Event queries tie-break on `id` so same-millisecond appends return in a stable order across
  // calls. Without the tie-breaker SQLite is free to return ties in any order.
  @Query(
    """
    SELECT * FROM events
     WHERE app_name = :appName AND user_id = :userId AND session_id = :sessionId
     ORDER BY timestamp ASC, id ASC
  """
  )
  suspend fun listEvents(appName: String, userId: String, sessionId: String): List<EventRow>

  @Query(
    """
    SELECT * FROM events
     WHERE app_name = :appName AND user_id = :userId AND session_id = :sessionId
       AND timestamp >= :afterMs
     ORDER BY timestamp ASC, id ASC
  """
  )
  suspend fun listEventsAfter(
    appName: String,
    userId: String,
    sessionId: String,
    afterMs: Long,
  ): List<EventRow>

  @Query(
    """
    SELECT * FROM (
      SELECT * FROM events
       WHERE app_name = :appName AND user_id = :userId AND session_id = :sessionId
       ORDER BY timestamp DESC, id DESC
       LIMIT :limit
    ) ORDER BY timestamp ASC, id ASC
  """
  )
  suspend fun listRecentEvents(
    appName: String,
    userId: String,
    sessionId: String,
    limit: Int,
  ): List<EventRow>

  @Insert suspend fun insertEvent(row: EventRow)

  // ---------------- app_states ----------------

  @Query("SELECT * FROM app_states WHERE app_name = :appName LIMIT 1")
  suspend fun getAppState(appName: String): AppStateRow?

  /** Inserts a row only if `app_name` is not already present. Used by `createSession` seeding. */
  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insertAppStateIfAbsent(row: AppStateRow)

  /** Inserts or replaces an app-state row. Used by `appendEvent` so a missing row is created. */
  @Upsert suspend fun upsertAppState(row: AppStateRow)

  // ---------------- user_states ----------------

  @Query("SELECT * FROM user_states WHERE app_name = :appName AND user_id = :userId LIMIT 1")
  suspend fun getUserState(appName: String, userId: String): UserStateRow?

  /** Inserts a row only if the `(app_name, user_id)` pair is not already present. */
  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insertUserStateIfAbsent(row: UserStateRow)

  /** Inserts or replaces a user-state row. Used by `appendEvent` so a missing row is created. */
  @Upsert suspend fun upsertUserState(row: UserStateRow)

  // ---------------- appendEvent transaction ----------------

  /**
   * Persists an event atomically:
   * 1. Verify the session still has the timestamp the caller saw last (stale-write check).
   * 2. For each non-empty per-scope delta, read the current state row inside the transaction, apply
   *    the delta honoring [State.REMOVED], and write the merged JSON back.
   * 3. Insert the event row.
   * 4. Bump `sessions.update_time` to the event's timestamp.
   *
   * The read-merge-write is performed entirely inside the `@Transaction` so concurrent appends
   * across sessions of the same `appName` (or same `appName` + `userId`) cannot lose updates to the
   * global app or user state tables.
   *
   * If a per-scope state row is missing — e.g. a caller passes a [Session] for a previously deleted
   * record — the corresponding empty `{}` baseline is used for merging and the row is then created
   * via [upsertAppState] / [upsertUserState]. The caller is responsible for prefix-stripping keys
   * in the deltas.
   */
  @Transaction
  suspend fun appendEventAtomic(
    appName: String,
    userId: String,
    sessionId: String,
    expectedUpdateTime: Long,
    appDelta: Map<String, Any>,
    userDelta: Map<String, Any>,
    sessionDelta: Map<String, Any>,
    eventRow: EventRow,
  ) {
    val current =
      getSession(appName, userId, sessionId)
        ?: throw IllegalStateException(
          "Session not found: appName=$appName, userId=$userId, sessionId=$sessionId"
        )
    // Exact-equality stale-write check (matches Python's storage_update_marker semantics):
    // a mismatch in EITHER direction is rejected. `>` means another writer raced us; `<` means
    // the caller's in-memory Session is "ahead" of storage (e.g. fabricated timestamp, backup
    // restore, or a previous appendEvent with a non-monotonic event.timestamp).
    check(current.updateTimeEpochMs == expectedUpdateTime) {
      "Stale session: storage update_time=${current.updateTimeEpochMs}, " +
        "caller lastUpdateTime=$expectedUpdateTime"
    }

    val eventTs = eventRow.timestamp
    if (appDelta.isNotEmpty()) {
      val existing = getAppState(appName)?.state ?: emptyMap()
      upsertAppState(
        AppStateRow(
          appName = appName,
          state = mergeStateDelta(existing, appDelta),
          updateTimeEpochMs = eventTs,
        )
      )
    }
    if (userDelta.isNotEmpty()) {
      val existing = getUserState(appName, userId)?.state ?: emptyMap()
      upsertUserState(
        UserStateRow(
          appName = appName,
          userId = userId,
          state = mergeStateDelta(existing, userDelta),
          updateTimeEpochMs = eventTs,
        )
      )
    }
    if (sessionDelta.isNotEmpty()) {
      updateSessionState(
        appName,
        userId,
        sessionId,
        mergeStateDelta(current.state, sessionDelta),
        eventTs,
      )
    } else {
      touchSession(appName, userId, sessionId, eventTs)
    }

    insertEvent(eventRow)
  }
}

/** Applies [delta] onto a copy of [existing], honoring [State.REMOVED] semantics. */
private fun mergeStateDelta(existing: Map<String, Any>, delta: Map<String, Any>): Map<String, Any> {
  val merged = existing.toMutableMap()
  for ((k, v) in delta) {
    if (v === State.REMOVED) merged.remove(k) else merged[k] = v
  }
  return merged
}
