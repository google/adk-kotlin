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

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * The Room database host for [RoomSessionService].
 *
 * Holds four tables ([StorageSession], [StorageEvent], [StorageAppState], [StorageUserState]). JSON
 * columns are converted via [JsonConverters].
 *
 * Internal: instances are constructed via [RoomSessionService.fromContext], not directly.
 */
@Database(
  entities =
    [StorageSession::class, StorageEvent::class, StorageAppState::class, StorageUserState::class],
  version = 1,
  exportSchema = false,
)
@TypeConverters(JsonConverters::class)
internal abstract class AdkSessionsDatabase : RoomDatabase() {
  abstract fun sessionsDao(): SessionsDao
}
