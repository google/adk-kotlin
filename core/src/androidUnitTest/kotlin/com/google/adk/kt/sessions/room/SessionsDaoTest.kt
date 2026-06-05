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

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.adk.kt.sessions.State
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests [SessionsDao] in isolation against an in-memory Room database. Covers basic CRUD on each
 * table plus the `appendEventAtomic` transaction (success, missing-session, and stale-write paths).
 */
@RunWith(AndroidJUnit4::class)
class SessionsDaoTest {

  private lateinit var database: AdkSessionsDatabase
  private lateinit var dao: SessionsDao

  @Before
  fun setUp() {
    database =
      Room.inMemoryDatabaseBuilder(
          ApplicationProvider.getApplicationContext(),
          AdkSessionsDatabase::class.java,
        )
        .allowMainThreadQueries()
        .build()
    dao = database.sessionsDao()
  }

  @After
  fun tearDown() {
    database.close()
  }

  @Test
  fun eachTable_insertAndRead_roundTrips() = runTest {
    val sessionRow =
      StorageSession(
        appName = "app",
        userId = "user",
        id = "session1",
        state = emptyMap(),
        createTime = 1L,
        updateTime = 1L,
      )
    val appStateRow = StorageAppState(appName = "app", state = emptyMap(), updateTime = 1L)
    val userStateRow =
      StorageUserState(appName = "app", userId = "user", state = emptyMap(), updateTime = 1L)
    val eventRow =
      StorageEvent(
        id = "event1",
        appName = "app",
        userId = "user",
        sessionId = "session1",
        invocationId = null,
        timestamp = 2L,
        eventData = "{}",
      )

    dao.insertSession(sessionRow)
    dao.insertAppStateIfAbsent(appStateRow)
    dao.insertUserStateIfAbsent(userStateRow)
    dao.insertEvent(eventRow)

    assertThat(dao.getSession("app", "user", "session1")).isEqualTo(sessionRow)
    assertThat(dao.getAppState("app")).isEqualTo(appStateRow)
    assertThat(dao.getUserState("app", "user")).isEqualTo(userStateRow)
    assertThat(dao.listEvents("app", "user", "session1")).containsExactly(eventRow)
  }

  @Test
  fun appendEventAtomic_existingSession_persistsAndMerges() = runTest {
    val initialTs = 100L
    dao.insertSession(
      StorageSession(
        appName = "app",
        userId = "user",
        id = "session1",
        state = mapOf("existing" to "keep"),
        createTime = initialTs,
        updateTime = initialTs,
      )
    )
    dao.insertAppStateIfAbsent(
      StorageAppState(appName = "app", state = emptyMap(), updateTime = initialTs)
    )
    dao.insertUserStateIfAbsent(
      StorageUserState(appName = "app", userId = "user", state = emptyMap(), updateTime = initialTs)
    )

    val eventTs = 200L
    dao.appendEventAtomic(
      appName = "app",
      userId = "user",
      sessionId = "session1",
      expectedUpdateTime = initialTs,
      appDelta = mapOf("appKey" to "appValue"),
      userDelta = mapOf("userKey" to "userValue"),
      sessionDelta = mapOf("newKey" to "newValue"),
      eventRow =
        StorageEvent(
          id = "event1",
          appName = "app",
          userId = "user",
          sessionId = "session1",
          invocationId = null,
          timestamp = eventTs,
          eventData = "{}",
        ),
    )

    assertThat(dao.getAppState("app")?.state).containsExactly("appKey", "appValue")
    assertThat(dao.getUserState("app", "user")?.state).containsExactly("userKey", "userValue")
    val updatedSession = dao.getSession("app", "user", "session1")!!
    assertThat(updatedSession.state).containsExactly("existing", "keep", "newKey", "newValue")
    assertThat(updatedSession.updateTime).isEqualTo(eventTs)
    assertThat(dao.listEvents("app", "user", "session1")).hasSize(1)
  }

  @Test
  fun appendEventAtomic_noSessionDelta_touchesUpdateTimeAndKeepsState() = runTest {
    val initialTs = 100L
    dao.insertSession(
      StorageSession(
        appName = "app",
        userId = "user",
        id = "session1",
        state = mapOf("existing" to "keep"),
        createTime = initialTs,
        updateTime = initialTs,
      )
    )

    val eventTs = 200L
    dao.appendEventAtomic(
      appName = "app",
      userId = "user",
      sessionId = "session1",
      expectedUpdateTime = initialTs,
      appDelta = emptyMap(),
      userDelta = emptyMap(),
      sessionDelta = emptyMap(),
      eventRow =
        StorageEvent(
          id = "event1",
          appName = "app",
          userId = "user",
          sessionId = "session1",
          invocationId = null,
          timestamp = eventTs,
          eventData = "{}",
        ),
    )

    // With no session delta the state is unchanged, but updateTime is bumped (touchSession
    // branch).
    val updatedSession = dao.getSession("app", "user", "session1")!!
    assertThat(updatedSession.state).containsExactly("existing", "keep")
    assertThat(updatedSession.updateTime).isEqualTo(eventTs)
    assertThat(dao.listEvents("app", "user", "session1")).hasSize(1)
  }

  @Test
  fun appendEventAtomic_missingSession_throws() = runTest {
    val result = runCatching {
      dao.appendEventAtomic(
        appName = "app",
        userId = "user",
        sessionId = "ghost",
        expectedUpdateTime = 0L,
        appDelta = emptyMap(),
        userDelta = emptyMap(),
        sessionDelta = emptyMap(),
        eventRow =
          StorageEvent(
            id = "e",
            appName = "app",
            userId = "user",
            sessionId = "ghost",
            invocationId = null,
            timestamp = 1L,
            eventData = "{}",
          ),
      )
    }

    val exception = result.exceptionOrNull()
    assertThat(exception).isNotNull()
    assertThat(exception).isInstanceOf(IllegalStateException::class.java)
    assertThat(exception!!.message).contains("Session not found")
  }

  @Test
  fun appendEventAtomic_staleUpdateTime_throws() = runTest {
    val storedTs = 100L
    dao.insertSession(
      StorageSession(
        appName = "app",
        userId = "user",
        id = "session1",
        state = emptyMap(),
        createTime = storedTs,
        updateTime = storedTs,
      )
    )

    val result = runCatching {
      dao.appendEventAtomic(
        appName = "app",
        userId = "user",
        sessionId = "session1",
        expectedUpdateTime = storedTs - 1,
        appDelta = emptyMap(),
        userDelta = emptyMap(),
        sessionDelta = emptyMap(),
        eventRow =
          StorageEvent(
            id = "e",
            appName = "app",
            userId = "user",
            sessionId = "session1",
            invocationId = null,
            timestamp = 200L,
            eventData = "{}",
          ),
      )
    }

    val exception = result.exceptionOrNull()
    assertThat(exception).isNotNull()
    assertThat(exception).isInstanceOf(IllegalStateException::class.java)
    assertThat(exception!!.message).contains("Stale session")
  }

  @Test
  fun appendEventAtomic_withStateRemove_removesKeys() = runTest {
    val initialTs = 100L
    dao.insertSession(
      StorageSession(
        appName = "app",
        userId = "user",
        id = "session1",
        state = mapOf("key1" to "value1", "key2" to "value2"),
        createTime = initialTs,
        updateTime = initialTs,
      )
    )
    dao.insertAppStateIfAbsent(
      StorageAppState(
        appName = "app",
        state = mapOf("appKey1" to "appValue1", "appKey2" to "appValue2"),
        updateTime = initialTs,
      )
    )
    dao.insertUserStateIfAbsent(
      StorageUserState(
        appName = "app",
        userId = "user",
        state = mapOf("userKey1" to "userValue1", "userKey2" to "userValue2"),
        updateTime = initialTs,
      )
    )

    dao.appendEventAtomic(
      appName = "app",
      userId = "user",
      sessionId = "session1",
      expectedUpdateTime = initialTs,
      appDelta = mapOf("appKey1" to State.REMOVED),
      userDelta = mapOf("userKey1" to State.REMOVED),
      sessionDelta = mapOf("key1" to State.REMOVED),
      eventRow =
        StorageEvent(
          id = "event1",
          appName = "app",
          userId = "user",
          sessionId = "session1",
          invocationId = null,
          timestamp = 200L,
          eventData = "{}",
        ),
    )

    assertThat(dao.getSession("app", "user", "session1")!!.state).containsExactly("key2", "value2")
    assertThat(dao.getAppState("app")?.state).containsExactly("appKey2", "appValue2")
    assertThat(dao.getUserState("app", "user")?.state).containsExactly("userKey2", "userValue2")
  }

  // ---------------- mutation return values ----------------

  @Test
  fun insertSession_newRow_returnsRowId() = runTest {
    val rowId =
      dao.insertSession(
        StorageSession(
          appName = "app",
          userId = "user",
          id = "session1",
          state = emptyMap(),
          createTime = 1L,
          updateTime = 1L,
        )
      )

    assertThat(rowId).isNotEqualTo(-1L)
  }

  @Test
  fun deleteSession_existingThenMissing_returnsOneThenZero() = runTest {
    dao.insertSession(
      StorageSession(
        appName = "app",
        userId = "user",
        id = "session1",
        state = emptyMap(),
        createTime = 1L,
        updateTime = 1L,
      )
    )

    assertThat(dao.deleteSession(StorageSessionKey("app", "user", "session1"))).isEqualTo(1)
    assertThat(dao.deleteSession(StorageSessionKey("app", "user", "session1"))).isEqualTo(0)
  }

  @Test
  fun updateSessionState_existingThenMissing_returnsOneThenZero() = runTest {
    dao.insertSession(
      StorageSession(
        appName = "app",
        userId = "user",
        id = "session1",
        state = emptyMap(),
        createTime = 1L,
        updateTime = 1L,
      )
    )

    assertThat(dao.updateSessionState("app", "user", "session1", mapOf("k" to "v"), 2L))
      .isEqualTo(1)
    assertThat(dao.updateSessionState("app", "user", "ghost", mapOf("k" to "v"), 2L)).isEqualTo(0)
  }

  @Test
  fun touchSession_existingThenMissing_returnsOneThenZero() = runTest {
    dao.insertSession(
      StorageSession(
        appName = "app",
        userId = "user",
        id = "session1",
        state = emptyMap(),
        createTime = 1L,
        updateTime = 1L,
      )
    )

    assertThat(dao.touchSession("app", "user", "session1", 2L)).isEqualTo(1)
    assertThat(dao.touchSession("app", "user", "ghost", 2L)).isEqualTo(0)
  }

  @Test
  fun insertEvent_newRow_returnsRowId() = runTest {
    dao.insertSession(
      StorageSession(
        appName = "app",
        userId = "user",
        id = "session1",
        state = emptyMap(),
        createTime = 1L,
        updateTime = 1L,
      )
    )

    val rowId =
      dao.insertEvent(
        StorageEvent(
          id = "event1",
          appName = "app",
          userId = "user",
          sessionId = "session1",
          invocationId = null,
          timestamp = 2L,
          eventData = "{}",
        )
      )

    assertThat(rowId).isNotEqualTo(-1L)
  }

  @Test
  fun insertAppStateIfAbsent_secondInsert_returnsMinusOne() = runTest {
    val first =
      dao.insertAppStateIfAbsent(
        StorageAppState(appName = "app", state = emptyMap(), updateTime = 1L)
      )
    val second =
      dao.insertAppStateIfAbsent(
        StorageAppState(appName = "app", state = mapOf("k" to "v"), updateTime = 2L)
      )

    assertThat(first).isNotEqualTo(-1L)
    assertThat(second).isEqualTo(-1L)
  }

  @Test
  fun insertUserStateIfAbsent_secondInsert_returnsMinusOne() = runTest {
    val first =
      dao.insertUserStateIfAbsent(
        StorageUserState(appName = "app", userId = "user", state = emptyMap(), updateTime = 1L)
      )
    val second =
      dao.insertUserStateIfAbsent(
        StorageUserState(
          appName = "app",
          userId = "user",
          state = mapOf("k" to "v"),
          updateTime = 2L,
        )
      )

    assertThat(first).isNotEqualTo(-1L)
    assertThat(second).isEqualTo(-1L)
  }

  @Test
  fun upsertAppState_insertThenUpdate_returnsRowIdThenMinusOne() = runTest {
    val inserted =
      dao.upsertAppState(StorageAppState(appName = "app", state = emptyMap(), updateTime = 1L))
    val updated =
      dao.upsertAppState(
        StorageAppState(appName = "app", state = mapOf("k" to "v"), updateTime = 2L)
      )

    assertThat(inserted).isNotEqualTo(-1L)
    assertThat(updated).isEqualTo(-1L)
    assertThat(dao.getAppState("app")?.state).containsExactly("k", "v")
  }

  @Test
  fun upsertUserState_insertThenUpdate_returnsRowIdThenMinusOne() = runTest {
    val inserted =
      dao.upsertUserState(
        StorageUserState(appName = "app", userId = "user", state = emptyMap(), updateTime = 1L)
      )
    val updated =
      dao.upsertUserState(
        StorageUserState(
          appName = "app",
          userId = "user",
          state = mapOf("k" to "v"),
          updateTime = 2L,
        )
      )

    assertThat(inserted).isNotEqualTo(-1L)
    assertThat(updated).isEqualTo(-1L)
    assertThat(dao.getUserState("app", "user")?.state).containsExactly("k", "v")
  }
}
