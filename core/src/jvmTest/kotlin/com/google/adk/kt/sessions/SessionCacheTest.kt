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

import com.google.adk.kt.events.Event
import com.google.adk.kt.events.EventActions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TestTimeSource

class SessionCacheTest {

  private val time = TestTimeSource()
  private var storeReads = 0

  @Test
  fun get_afterARead_servesACopy() {
    val cache = cache()
    val stored = session(KEY, mapOf("k" to "v"))
    val unusedRead = cache.read(stored)

    val hit = assertNotNull(cache.get(KEY))

    assertEquals("v", hit.state["k"])
    assertNotSame(stored, hit)
  }

  @Test
  fun get_hitChanged_doesNotChangeTheCache() {
    val cache = cache()
    val unusedRead = cache.read(session(KEY))

    assertNotNull(cache.get(KEY)).state["k"] = "changed"

    assertNull(assertNotNull(cache.get(KEY)).state["k"])
  }

  @Test
  fun finishRead_storeReturnsNothingOrAnotherKey_cachesNothing() {
    val cache = cache()

    cache.finishRead(KEY, cache.startRead(KEY), session = null)
    cache.finishRead(KEY, cache.startRead(KEY), session(OTHER_SESSION))

    assertNull(cache.get(KEY))
    assertNull(cache.get(OTHER_SESSION))
  }

  @Test
  fun finishRead_sessionAppendedDuringTheRead_cachesNothing() {
    val cache = cache()
    val read = cache.startRead(KEY)
    cache.append(KEY, event(), changed = emptySet())

    cache.finishRead(KEY, read, session(KEY))

    assertNull(cache.get(KEY))
  }

  @Test
  fun finishRead_sharedScopeChangedElsewhereDuringTheRead_cachesNothing() {
    val cache = cache()
    val read = cache.startRead(KEY)
    cache.append(OTHER_SESSION, event(), changed = setOf(SharedStateScope.User(APP, USER)))

    cache.finishRead(KEY, read, session(KEY))

    assertNull(cache.get(KEY))
  }

  @Test
  fun finishRead_readVoidedThenStartedAgain_onlyTheNewReadCaches() {
    val cache = cache()
    val voided = cache.startRead(KEY)
    cache.drop(KEY)
    val restarted = cache.startRead(KEY)

    cache.finishRead(KEY, voided, session(KEY))
    assertNull(cache.get(KEY))
    cache.finishRead(KEY, restarted, session(KEY))
    assertNotNull(cache.get(KEY))
  }

  @Test
  fun finishRead_laterReadOfTheSessionAbandoned_stillCaches() {
    val cache = cache()
    val read = cache.startRead(KEY)
    val unusedAbandoned = cache.startRead(KEY)

    cache.finishRead(KEY, read, session(KEY))

    assertNotNull(cache.get(KEY))
  }

  @Test
  fun get_afterTtl_readsTheStoreAgain() {
    val cache = cache(ttl = 1.minutes)
    val unusedFirst = cache.read(session(KEY))

    time += 1.minutes
    val unusedSecond = cache.read(session(KEY))

    assertEquals(2, storeReads)
  }

  @Test
  fun get_infiniteTtl_neverExpires() {
    val cache = cache(ttl = Duration.INFINITE)
    val unusedFirst = cache.read(session(KEY))

    time += (365 * 24 * 60).minutes
    val unusedSecond = cache.read(session(KEY))

    assertEquals(1, storeReads)
  }

  @Test
  fun get_hit_marksTheSessionReadMostRecently() {
    val cache = cache(maxSessions = 2)
    val unusedFirst = cache.read(session(KEY))
    val unusedOther = cache.read(session(OTHER_SESSION))
    val unusedHit = cache.read(session(KEY))

    val unusedThird = cache.read(session(THIRD_SESSION))
    storeReads = 0
    val unusedKeptKey = cache.read(session(KEY))
    val unusedDroppedOther = cache.read(session(OTHER_SESSION))

    assertEquals(1, storeReads)
  }

  @Test
  fun startRead_cacheFull_dropsExpiredSessionsFirst() {
    val cache = cache(ttl = 1.minutes, maxSessions = 2)
    val unusedFirst = cache.read(session(KEY))
    time += 30.seconds
    val unusedOther = cache.read(session(OTHER_SESSION))
    time += 10.seconds
    val unusedHit = cache.read(session(KEY))
    time += 20.seconds

    // KEY was read last but has expired, so it goes before OTHER_SESSION, read least recently.
    val unusedThird = cache.read(session(THIRD_SESSION))
    storeReads = 0
    val unusedKeptOther = cache.read(session(OTHER_SESSION))

    assertEquals(0, storeReads)
  }

  @Test
  fun startRead_cacheFull_dropsTheSessionReadLeastRecently() {
    val cache = cache(maxSessions = 2)
    val unusedFirst = cache.read(session(KEY))
    val unusedOther = cache.read(session(OTHER_SESSION))

    val unusedRead = cache.startRead(THIRD_SESSION)

    assertNull(cache.get(KEY))
    assertNotNull(cache.get(OTHER_SESSION))
  }

  @Test
  fun startRead_readInProgress_marksItReadMostRecently() {
    val cache = cache(maxSessions = 2)
    val read = cache.startRead(KEY)
    val unusedOther = cache.read(session(OTHER_SESSION))
    val unusedShared = cache.startRead(KEY)

    val unusedThird = cache.read(session(THIRD_SESSION))
    cache.finishRead(KEY, read, session(KEY))

    assertNotNull(cache.get(KEY))
  }

  @Test
  fun finishRead_nothingToCache_freesItsSlot() {
    val cache = cache(maxSessions = 2)
    val unusedFirst = cache.read(session(KEY))
    cache.finishRead(OTHER_SESSION, cache.startRead(OTHER_SESSION), session = null)

    val unusedThird = cache.read(session(THIRD_SESSION))

    assertNotNull(cache.get(KEY))
  }

  @Test
  fun append_cachedSession_appliesTheEventLikeTheStore() {
    val cache = cache()
    val unusedRead = cache.read(session(KEY))
    val event = event(mapOf("k" to "v"), timestamp = 42)

    cache.append(KEY, event, changed = emptySet())

    val hit = assertNotNull(cache.get(KEY))
    assertEquals("v", hit.state["k"])
    assertEquals(listOf(event.id), hit.events.map { it.id })
    assertEquals(Instant.fromEpochMilliseconds(42), hit.lastUpdateTime)
  }

  @Test
  fun append_cachedSession_doesNotExtendTheTtl() {
    val cache = cache(ttl = 1.minutes)
    val unusedFirst = cache.read(session(KEY))
    time += 30.seconds

    cache.append(KEY, event(), changed = emptySet())
    time += 30.seconds
    val unusedSecond = cache.read(session(KEY))

    assertEquals(2, storeReads)
  }

  @Test
  fun append_userScopeChange_dropsOnlyThatUsersOtherSessions() {
    val cache = cache(maxSessions = 3)
    val otherUser = SessionKey(APP, "other-user", "theirs")
    for (key in listOf(KEY, OTHER_SESSION, otherUser)) {
      val unused = cache.read(session(key))
    }

    cache.append(KEY, event(), changed = setOf(SharedStateScope.User(APP, USER)))

    assertNotNull(cache.get(KEY))
    assertNull(cache.get(OTHER_SESSION))
    assertNotNull(cache.get(otherUser))
  }

  @Test
  fun append_appScopeChange_dropsEveryOtherSessionOfTheApp() {
    val cache = cache(maxSessions = 3)
    val otherUser = SessionKey(APP, "other-user", "theirs")
    val otherApp = SessionKey("other-app", USER, "session")
    for (key in listOf(KEY, otherUser, otherApp)) {
      val unused = cache.read(session(key))
    }

    cache.append(KEY, event(), changed = setOf(SharedStateScope.App(APP)))

    assertNotNull(cache.get(KEY))
    assertNull(cache.get(otherUser))
    assertNotNull(cache.get(otherApp))
  }

  @Test
  fun drop_cachedSession_readsTheStoreAgain() {
    val cache = cache()
    val unusedFirst = cache.read(session(KEY))

    cache.drop(KEY)
    val unusedSecond = cache.read(session(KEY))

    assertEquals(2, storeReads)
  }

  @Test
  fun drop_duringARead_cachesNothing() {
    val cache = cache()
    val read = cache.startRead(KEY)
    cache.drop(KEY)

    cache.finishRead(KEY, read, session(KEY))

    assertNull(cache.get(KEY))
  }

  @Test
  fun drop_sharedScopeChanged_dropsOnlyTheSessionsThatSeeIt() {
    val cache = cache(maxSessions = 3)
    val otherUser = SessionKey(APP, "other-user", "theirs")
    for (key in listOf(KEY, OTHER_SESSION, otherUser)) {
      val unused = cache.read(session(key))
    }

    // An uncached key, as when a new session seeds user: state.
    cache.drop(THIRD_SESSION, changed = setOf(SharedStateScope.User(APP, USER)))

    assertNull(cache.get(KEY))
    assertNull(cache.get(OTHER_SESSION))
    assertNotNull(cache.get(otherUser))
  }

  private fun cache(ttl: Duration = 1.minutes, maxSessions: Int = 2) =
    SessionCache(SessionCacheConfig(ttl = ttl, maxSessions = maxSessions), time)

  /** Reads [stored] through the cache as its owner does, counting the store reads it takes. */
  private fun SessionCache.read(stored: Session): Session? {
    val hit = get(stored.key)
    if (hit != null) return hit
    val read = startRead(stored.key)
    storeReads++
    finishRead(stored.key, read, stored)
    return stored
  }

  private companion object {
    const val APP = "app"
    const val USER = "user"
    val KEY = SessionKey(APP, USER, "session")
    val OTHER_SESSION = SessionKey(APP, USER, "other")
    val THIRD_SESSION = SessionKey(APP, USER, "third")

    fun session(key: SessionKey, state: Map<String, Any> = emptyMap()): Session =
      Session(key, state = State(initialState = state))

    fun event(stateDelta: Map<String, Any> = emptyMap(), timestamp: Long = 1): Event =
      Event(
        author = "agent",
        invocationId = "inv",
        actions = EventActions(stateDelta = stateDelta.toMutableMap()),
        timestamp = timestamp,
      )
  }
}
