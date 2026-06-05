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

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.google.adk.kt.sessions.State
import com.google.errorprone.annotations.CanIgnoreReturnValue

/** Room DAO for [AdkSessionsDatabase]. */
@Dao
internal interface SessionsDao {

  // ---------------- sessions ----------------

  @Query(
    "SELECT * FROM StorageSession WHERE appName = :appName AND userId = :userId AND id = :id LIMIT 1"
  )
  suspend fun getSession(appName: String, userId: String, id: String): StorageSession?

  @Query(
    """
    SELECT * FROM StorageSession
     WHERE appName = :appName AND userId = :userId
     ORDER BY updateTime DESC
  """
  )
  suspend fun listSessions(appName: String, userId: String): List<StorageSession>

  /** @return the inserted row's SQLite rowid. A duplicate primary key aborts with an exception. */
  @CanIgnoreReturnValue
  @Insert(onConflict = OnConflictStrategy.ABORT)
  suspend fun insertSession(row: StorageSession): Long

  /** @return the number of rows updated (1 if the session exists, 0 otherwise). */
  @CanIgnoreReturnValue
  @Query(
    """
    UPDATE StorageSession
       SET state = :state, updateTime = :updateTime
     WHERE appName = :appName AND userId = :userId AND id = :id
  """
  )
  suspend fun updateSessionState(
    appName: String,
    userId: String,
    id: String,
    state: Map<String, Any>,
    updateTime: Long,
  ): Int

  /** @return the number of rows updated (1 if the session exists, 0 otherwise). */
  @CanIgnoreReturnValue
  @Query(
    """
    UPDATE StorageSession
       SET updateTime = :updateTime
     WHERE appName = :appName AND userId = :userId AND id = :id
  """
  )
  suspend fun touchSession(appName: String, userId: String, id: String, updateTime: Long): Int

  /**
   * Deletes the session identified by [key] (cascade-deletes its events via the FK).
   *
   * @return the number of session rows deleted (1 if it existed, 0 otherwise).
   */
  @CanIgnoreReturnValue
  @Delete(entity = StorageSession::class)
  suspend fun deleteSession(key: StorageSessionKey): Int

  // ---------------- events ----------------

  // Event queries tie-break on `id` so same-millisecond appends return in a stable order across
  // calls. Without the tie-breaker SQLite is free to return ties in any order.
  @Query(
    """
    SELECT * FROM StorageEvent
     WHERE appName = :appName AND userId = :userId AND sessionId = :sessionId
     ORDER BY timestamp ASC, id ASC
  """
  )
  suspend fun listEvents(appName: String, userId: String, sessionId: String): List<StorageEvent>

  @Query(
    """
    SELECT * FROM StorageEvent
     WHERE appName = :appName AND userId = :userId AND sessionId = :sessionId
       AND timestamp >= :sinceTimestampMs
     ORDER BY timestamp ASC, id ASC
  """
  )
  suspend fun listEventsAfter(
    appName: String,
    userId: String,
    sessionId: String,
    sinceTimestampMs: Long,
  ): List<StorageEvent>

  @Query(
    """
    SELECT * FROM (
      SELECT * FROM StorageEvent
       WHERE appName = :appName AND userId = :userId AND sessionId = :sessionId
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
  ): List<StorageEvent>

  /** @return the inserted row's SQLite rowid. A duplicate primary key aborts with an exception. */
  @CanIgnoreReturnValue @Insert suspend fun insertEvent(row: StorageEvent): Long

  // ---------------- app state ----------------

  @Query("SELECT * FROM StorageAppState WHERE appName = :appName LIMIT 1")
  suspend fun getAppState(appName: String): StorageAppState?

  /**
   * Inserts a row only if `appName` is not already present. Used by `createSession` seeding.
   *
   * @return the inserted row's rowid, or -1 if a row was already present (insert ignored).
   */
  @CanIgnoreReturnValue
  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insertAppStateIfAbsent(row: StorageAppState): Long

  /**
   * Inserts or replaces an app-state row. Used by `appendEvent` so a missing row is created.
   *
   * @return the inserted row's rowid, or -1 if an existing row was updated instead.
   */
  @CanIgnoreReturnValue @Upsert suspend fun upsertAppState(row: StorageAppState): Long

  // ---------------- user state ----------------

  @Query("SELECT * FROM StorageUserState WHERE appName = :appName AND userId = :userId LIMIT 1")
  suspend fun getUserState(appName: String, userId: String): StorageUserState?

  /**
   * Inserts a row only if the `(appName, userId)` pair is not already present.
   *
   * @return the inserted row's rowid, or -1 if a row was already present (insert ignored).
   */
  @CanIgnoreReturnValue
  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insertUserStateIfAbsent(row: StorageUserState): Long

  /**
   * Inserts or replaces a user-state row. Used by `appendEvent` so a missing row is created.
   *
   * @return the inserted row's rowid, or -1 if an existing row was updated instead.
   */
  @CanIgnoreReturnValue @Upsert suspend fun upsertUserState(row: StorageUserState): Long

  // ---------------- appendEvent transaction ----------------

  /**
   * Persists an event atomically:
   * 1. Verify the session still has the timestamp the caller saw last (stale-write check).
   * 2. For each non-empty per-scope delta, read the current state row inside the transaction, apply
   *    the delta honoring [State.REMOVED], and write the merged JSON back.
   * 3. Insert the event row.
   * 4. Bump the session's `updateTime` to the event's timestamp.
   *
   * The read-merge-write is performed entirely inside the `@Transaction` so concurrent appends
   * across sessions of the same `appName` (or same `appName` + `userId`) cannot lose updates to the
   * global app or user state tables. Room's convenience `@Insert`/`@Update`/`@Upsert` operate on a
   * single entity and can't express this multi-table read-merge-write, so it is hand-written. This
   * mirrors the source of truth — Python's `append_event` and Go's `applyEvent` do the same
   * read-merge-write inside one transaction.
   *
   * If a per-scope state row is missing — e.g. a caller passes a
   * [com.google.adk.kt.sessions.Session] for a previously deleted record — the corresponding empty
   * `{}` baseline is used for merging and the row is then created via [upsertAppState] /
   * [upsertUserState]. The caller is responsible for prefix-stripping keys in the deltas.
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
    eventRow: StorageEvent,
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
    check(current.updateTime == expectedUpdateTime) {
      "Stale session: storage updateTime=${current.updateTime}, " +
        "caller lastUpdateTime=$expectedUpdateTime"
    }

    val eventTs = eventRow.timestamp
    if (appDelta.isNotEmpty()) {
      val existing = getAppState(appName)?.state ?: emptyMap()
      upsertAppState(
        StorageAppState(
          appName = appName,
          state = mergeStateDelta(existing, appDelta),
          updateTime = eventTs,
        )
      )
    }
    if (userDelta.isNotEmpty()) {
      val existing = getUserState(appName, userId)?.state ?: emptyMap()
      upsertUserState(
        StorageUserState(
          appName = appName,
          userId = userId,
          state = mergeStateDelta(existing, userDelta),
          updateTime = eventTs,
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

/** Primary-key projection of [StorageSession], used as the target for the `@Delete` shortcut. */
internal data class StorageSessionKey(val appName: String, val userId: String, val id: String)

/** Applies [delta] onto a copy of [existing], honoring [State.REMOVED] semantics. */
private fun mergeStateDelta(existing: Map<String, Any>, delta: Map<String, Any>): Map<String, Any> {
  val merged = existing.toMutableMap()
  for ((k, v) in delta) {
    if (v === State.REMOVED) merged.remove(k) else merged[k] = v
  }
  return merged
}
