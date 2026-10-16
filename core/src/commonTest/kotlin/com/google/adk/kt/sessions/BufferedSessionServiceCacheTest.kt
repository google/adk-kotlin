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
import com.google.adk.kt.retry.ExponentialBackoff
import com.google.adk.kt.testing.SessionServiceAssertions
import com.google.adk.kt.testing.nextEvent
import kotlin.coroutines.CoroutineContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

/**
 * Covers the read cache of [BufferedSessionService]; `SessionCacheTest` covers expiry on a test
 * clock.
 */
class BufferedSessionServiceCacheTest {

  private val parent = Job()
  private val store = GatedStore()

  @AfterTest
  fun tearDown() {
    parent.cancel()
  }

  @Test
  fun getSession_afterAppend_servesTheCachedCopyWithTheAppend() = runBlocking {
    val service = service()
    val session = service.createSession(KEY)
    val unusedWarm = service.getSession(KEY)

    val appended = service.appendEvent(session, session.nextEvent(mapOf("topic" to "tides")))
    val read = assertNotNull(service.getSession(KEY))

    assertEquals(1, store.reads.value)
    assertEquals(listOf(appended.id), read.events.map { it.id })
    assertEquals("tides", read.state["topic"])
  }

  @Test
  fun getSession_afterAppend_servesTheDeltaAndVersionOfTheAppend() = runBlocking {
    val service = service()
    val session = service.createSession(KEY, mapOf("gone" to "x"))
    val unusedWarm = service.getSession(KEY)

    val delta = mapOf("gone" to State.REMOVED, "temp:scratch" to "t", "kept" to "v")
    val appended = service.appendEvent(session, session.nextEvent(delta))
    val read = assertNotNull(service.getSession(KEY))

    assertEquals(1, store.reads.value)
    assertEquals(mapOf<String, Any>("kept" to "v"), read.state.toMap())
    assertEquals(Instant.fromEpochMilliseconds(appended.timestamp), read.lastUpdateTime)
  }

  @Test
  fun getSession_afterCreate_readsTheStore() = runBlocking {
    val service = service()
    val unused = service.createSession(KEY)

    val read = service.getSession(KEY)

    assertNotNull(read)
    assertEquals(1, store.reads.value)
  }

  @Test
  fun getSession_notCached_readsTheStoreOnceThenServesTheCopy() = runBlocking {
    val service = service()
    val unused = store.inner.createSession(KEY)

    val first = service.getSession(KEY)
    val second = service.getSession(KEY)

    assertNotNull(first)
    assertNotNull(second)
    assertEquals(1, store.reads.value)
  }

  @Test
  fun getSession_storeReturnsAnotherKey_doesNotCacheIt() = runBlocking {
    val service = service()
    val unused = store.inner.createSession(KEY)
    store.returnedKey.value = KEY.copy(id = "other")

    val unusedFirst = service.getSession(KEY)
    val unusedSecond = service.getSession(KEY)

    assertEquals(2, store.reads.value)
  }

  @Test
  fun getSession_cachedWithAWriteHeld_servesTheCopyWithoutWaiting() = runBlocking {
    val service = service()
    val session = service.createSession(KEY)
    val unusedWarm = service.getSession(KEY)
    store.appendsOpen.value = false

    val appended = service.appendEvent(session, session.nextEvent())
    val read = withTimeout(5.seconds) { assertNotNull(service.getSession(KEY)) }
    store.appendsOpen.value = true

    assertEquals(listOf(appended.id), read.events.map { it.id })
    assertEquals(1, store.reads.value)
  }

  @Test
  fun getSession_afterTtl_readsTheStoreAgain() = runBlocking {
    val service = service(SessionCacheConfig(ttl = 1.milliseconds))
    val unused = service.createSession(KEY)
    val unusedWarm = service.getSession(KEY)

    // Never flaky: delay waits at least this long on the monotonic clock the cache reads.
    delay(2.milliseconds)
    val read = service.getSession(KEY)

    assertNotNull(read)
    assertEquals(2, store.reads.value)
  }

  @Test
  fun getSession_withConfig_readsTheStoreAndLeavesTheCacheAlone() = runBlocking {
    val service = service()
    val session = service.createSession(KEY)
    val unusedWarm = service.getSession(KEY)
    service.append(session)
    service.append(session)

    val filtered = assertNotNull(service.getSession(KEY, GetSessionConfig(numRecentEvents = 1)))
    val cached = assertNotNull(service.getSession(KEY))

    assertEquals(2, store.reads.value)
    assertEquals(1, filtered.events.size)
    assertEquals(2, cached.events.size)
  }

  @Test
  fun getSession_cacheFull_dropsTheSessionReadLeastRecently() = runBlocking {
    val service = service(SessionCacheConfig(maxSessions = 2))
    val keys = listOf(KEY, KEY.copy(id = "second"), KEY.copy(id = "third"))
    for (key in keys) {
      val unused = service.createSession(key)
    }

    for (key in keys.take(2)) {
      val unusedWarm = service.getSession(key)
    }
    val unusedRead = service.getSession(KEY)
    val unusedThird = service.getSession(keys[2])
    val unusedKept = service.getSession(KEY)
    val unusedDropped = service.getSession(keys[1])

    assertEquals(4, store.reads.value)
  }

  @Test
  fun appendEvent_userStateChange_dropsTheUsersOtherSessionsOnly() = runBlocking {
    val service = service()
    val writer = service.createSession(KEY)
    val sibling = KEY.copy(id = "sibling")
    val stranger = SessionKey(APP, "other-user", "stranger")
    for (key in listOf(sibling, stranger)) {
      val unused = service.createSession(key)
    }
    for (key in listOf(KEY, sibling, stranger)) {
      val unusedWarm = service.getSession(key)
    }

    service.append(writer, mapOf("user:theme" to "dark"))
    val writerRead = assertNotNull(service.getSession(KEY))
    val unusedStrangerRead = service.getSession(stranger)
    assertEquals(3, store.reads.value)
    val siblingRead = assertNotNull(service.getSession(sibling))

    assertEquals(4, store.reads.value)
    assertEquals("dark", writerRead.state["user:theme"])
    assertEquals("dark", siblingRead.state["user:theme"])
  }

  @Test
  fun appendEvent_appStateChange_dropsEveryUsersSessionsOfTheApp() = runBlocking {
    val service = service()
    val writer = service.createSession(KEY)
    val stranger = SessionKey(APP, "other-user", "stranger")
    val otherApp = SessionKey("other-app", USER, "elsewhere")
    for (key in listOf(stranger, otherApp)) {
      val unused = service.createSession(key)
    }
    for (key in listOf(KEY, stranger, otherApp)) {
      val unusedWarm = service.getSession(key)
    }

    service.append(writer, mapOf("app:flag" to "on"))
    val unusedOtherAppRead = service.getSession(otherApp)
    assertEquals(3, store.reads.value)
    val strangerRead = assertNotNull(service.getSession(stranger))

    assertEquals(4, store.reads.value)
    assertEquals("on", strangerRead.state["app:flag"])
  }

  @Test
  fun createSession_userStateSeed_dropsTheUsersOtherCachedSessions() = runBlocking {
    val service = service()
    val unused = service.createSession(KEY)
    val unusedWarm = service.getSession(KEY)

    val unusedSeeded = service.createSession(KEY.copy(id = "seeded"), mapOf("user:pref" to "u"))
    val read = assertNotNull(service.getSession(KEY))

    assertEquals(2, store.reads.value)
    assertEquals("u", read.state["user:pref"])
  }

  @Test
  fun createSession_storeFailsAfterCreating_stillDropsTheUsersOtherCachedSessions() = runBlocking {
    val service = service()
    val unused = service.createSession(KEY)
    val unusedWarm = service.getSession(KEY)
    store.createsFailAfterward.value = true

    assertFailsWith<IllegalStateException> {
      service.createSession(KEY.copy(id = "seeded"), mapOf("user:pref" to "u"))
    }
    val read = assertNotNull(service.getSession(KEY))

    assertEquals("u", read.state["user:pref"])
  }

  @Test
  fun createSession_keyCachedButGoneFromTheStore_dropsTheOldCopy() = runBlocking {
    val service = service()
    val old = service.createSession(KEY)
    service.append(old)
    val unusedWarm = service.getSession(KEY)
    // The store drops the session on its own, as one that expires would.
    store.inner.deleteSession(KEY)

    val unusedRecreated = service.createSession(KEY)
    val read = assertNotNull(service.getSession(KEY))

    assertEquals(0, read.events.size)
  }

  @Test
  fun getSession_returnsCopiesThatDoNotChangeTheCache() = runBlocking {
    val service = service()
    val session = service.createSession(KEY)
    service.append(session)

    val filled = assertNotNull(service.getSession(KEY))
    filled.state["scratch"] = "filled"
    filled.events.clear()
    val hit = assertNotNull(service.getSession(KEY))
    hit.state["scratch"] = "hit"
    hit.events.clear()
    val read = assertNotNull(service.getSession(KEY))

    assertNull(read.state["scratch"])
    assertEquals(1, read.events.size)
  }

  @Test
  fun getSession_hitEventChanged_changesLaterHits() = runBlocking {
    // Pins the sharing that SessionCacheConfig documents; copying events would change that KDoc.
    val service = service()
    val session = service.createSession(KEY)
    val unusedWarm = service.getSession(KEY)
    service.append(session, mapOf("color" to "red"))
    val hit = assertNotNull(service.getSession(KEY))

    hit.events.single().actions.stateDelta["color"] = "blue"
    val later = assertNotNull(service.getSession(KEY))

    assertEquals("blue", later.events.single().actions.stateDelta["color"])
  }

  @Test
  fun getSession_hitEventChanged_doesNotChangeTheQueuedWrite() = runBlocking {
    val service = service()
    val session = service.createSession(KEY)
    val unusedWarm = service.getSession(KEY)
    store.appendsOpen.value = false
    service.append(session, mapOf("color" to "red"))
    val hit = assertNotNull(service.getSession(KEY))

    hit.events.single().actions.stateDelta["color"] = "blue"
    store.appendsOpen.value = true
    service.flush(KEY)

    assertEquals("red", store.inner.listEvents(KEY).events.single().actions.stateDelta["color"])
  }

  @Test
  fun getSession_appendedEventChangedAfterward_servesTheAppendedVersion() = runBlocking {
    val service = service()
    val session = service.createSession(KEY)
    val unusedWarm = service.getSession(KEY)

    val appended = service.appendEvent(session, session.nextEvent(mapOf("color" to "red")))
    appended.actions.stateDelta["color"] = "blue"
    val hit = assertNotNull(service.getSession(KEY))

    assertEquals("red", hit.events.single().actions.stateDelta["color"])
  }

  @Test
  fun appendEvent_toACachedCopy_addsTheEventOnce() = runBlocking {
    val service = service()
    val unused = service.createSession(KEY)
    val unusedWarm = service.getSession(KEY)
    val hit = assertNotNull(service.getSession(KEY))

    service.append(hit)
    val read = assertNotNull(service.getSession(KEY))

    assertEquals(1, store.reads.value)
    assertEquals(1, read.events.size)
  }

  @Test
  fun deleteSession_dropsTheCachedCopy() = runBlocking {
    val service = service()
    val unused = service.createSession(KEY)
    val unusedWarm = service.getSession(KEY)

    service.deleteSession(KEY)

    assertNull(service.getSession(KEY))
  }

  @Test
  fun deleteSession_readWhileTheStoreDeletes_leavesNothingCached() = runBlocking {
    val service = service()
    val unused = service.createSession(KEY)
    store.deletesOpen.value = false

    val deleting = async { service.deleteSession(KEY) }
    store.deletesStarted.first { it == 1 }
    val unusedStale = service.getSession(KEY)
    store.deletesOpen.value = true
    deleting.await()

    assertNull(service.getSession(KEY))
  }

  @Test
  fun deleteSession_storeFailsAfterDeleting_leavesNothingCached() = runBlocking {
    val service = service()
    val unused = service.createSession(KEY)
    store.deletesOpen.value = false
    store.deletesFailAfterward.value = true

    val deleting = async { assertFailsWith<IllegalStateException> { service.deleteSession(KEY) } }
    store.deletesStarted.first { it == 1 }
    val unusedStale = service.getSession(KEY)
    store.deletesOpen.value = true
    deleting.await()

    assertNull(service.getSession(KEY))
  }

  @Test
  fun getSession_concurrentReadCanceled_stillCachesTheOther() = runBlocking {
    val service = service()
    val unused = store.inner.createSession(KEY)
    store.readsOpen.value = false

    val kept = async { service.getSession(KEY) }
    store.readsStarted.first { it == 1 }
    val canceled = async { service.getSession(KEY) }
    store.readsStarted.first { it == 2 }
    canceled.cancelAndJoin()
    store.readsOpen.value = true
    assertNotNull(kept.await())
    val cached = service.getSession(KEY)

    assertNotNull(cached)
    assertEquals(1, store.reads.value)
  }

  @Test
  fun getSession_afterRetriesRanOut_readsTheStoreWithoutReporting() = runBlocking {
    val service = service()
    val session = service.createSession(KEY)
    val unusedWarm = service.getSession(KEY)
    store.appendsFail.value = true
    service.append(session)
    // listSessions waits for the failed write without reporting it.
    val unusedList = service.listSessions(APP, USER)

    val read = assertNotNull(service.getSession(KEY))

    assertEquals(2, store.reads.value)
    assertEquals(0, read.events.size)
    val failure = assertFailsWith<SessionException> { service.flush(KEY) }
    assertEquals(SessionException.SESSION_PERSISTENCE_FAILED, failure.message)
  }

  @Test
  fun getSession_storeThrowsAnError_readsTheStoreAgain() =
    runBlocking<Unit> {
      // Catches the Error the write rethrows, which would otherwise reach the global handler.
      val escaped = CompletableDeferred<Throwable>()
      val handler = CoroutineExceptionHandler { _, error -> escaped.complete(error) }
      val service = service(context = Dispatchers.Default + parent + handler)
      val session = service.createSession(KEY)
      val unusedWarm = service.getSession(KEY)
      store.appendFailure.value = NotImplementedError("store stub")
      store.appendsFail.value = true
      service.append(session)
      val unusedError = escaped.await()

      val read = assertNotNull(service.getSession(KEY))

      assertEquals(2, store.reads.value)
      assertEquals(0, read.events.size)
    }

  @Test
  fun getSession_appendWhileReadingTheStore_doesNotCacheTheOutdatedResult() = runBlocking {
    val service = service()
    val unusedCreated = store.inner.createSession(KEY)
    val live = assertNotNull(store.inner.getSession(KEY))
    store.readsOpen.value = false

    val read = async { service.getSession(KEY) }
    store.readsStarted.first { it == 1 }
    // Hold the write, so the read cannot see it.
    store.appendsOpen.value = false
    val appended = service.appendEvent(live, live.nextEvent())
    store.readsOpen.value = true
    val outdated = assertNotNull(read.await())
    store.appendsOpen.value = true
    val fresh = assertNotNull(service.getSession(KEY))

    assertEquals(0, outdated.events.size)
    assertEquals(listOf(appended.id), fresh.events.map { it.id })
  }

  @Test
  fun getSession_userStateChangedDuringTheRead_doesNotCacheTheOutdatedResult() = runBlocking {
    val service = service()
    val writer = service.createSession(KEY)
    val sibling = KEY.copy(id = "sibling")
    val unused = service.createSession(sibling)
    store.readsOpen.value = false

    val read = async { service.getSession(sibling) }
    store.readsStarted.first { it == 1 }
    // Hold the write, so the read cannot see it.
    store.appendsOpen.value = false
    service.append(writer, mapOf("user:theme" to "dark"))
    store.readsOpen.value = true
    val outdated = assertNotNull(read.await())
    store.appendsOpen.value = true
    val fresh = assertNotNull(service.getSession(sibling))

    assertNull(outdated.state["user:theme"])
    assertEquals("dark", fresh.state["user:theme"])
  }

  @Test
  fun getSession_writeCanceledBeforeItStarted_readsTheStore() = runBlocking {
    // Holds the write's task, so the service stops before it starts; all on this thread.
    val tasks = ArrayDeque<Runnable>()
    val holding =
      object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) {
          tasks.addLast(block)
        }
      }
    val service = service(context = holding + parent)
    val session = service.createSession(KEY)
    val unusedWarm = service.getSession(KEY)
    service.append(session)

    parent.cancel()
    while (tasks.isNotEmpty()) tasks.removeFirst().run()
    val read = assertNotNull(service.getSession(KEY))

    assertEquals(2, store.reads.value)
    assertEquals(0, read.events.size)
  }

  @Test
  fun contract_holdsWithTheCacheOn() = runBlocking {
    val assertions: List<suspend (SessionService) -> Unit> =
      listOf(
        SessionServiceAssertions::tempStateVisibleInInvocationButNotPersisted,
        SessionServiceAssertions::tempStateTrimmedFromReturnedEvent,
        SessionServiceAssertions::tempStateRemovalReflectedOnLiveSession,
        SessionServiceAssertions::createdSessionIsRetrievable,
        SessionServiceAssertions::createSessionMintsIdWhenAbsent,
        SessionServiceAssertions::createSessionRetainsInitialState,
        SessionServiceAssertions::getUnknownSessionReturnsNull,
        SessionServiceAssertions::listSessionsReturnsUsersSessions,
        SessionServiceAssertions::listSessionsIsEmptyForUnknownUser,
        SessionServiceAssertions::listEventsReturnsAppendsInOrder,
        SessionServiceAssertions::listEventsIsEmptyForUnknownSession,
        SessionServiceAssertions::deleteRemovesSession,
        SessionServiceAssertions::deleteUnknownSessionIsNoOp,
        SessionServiceAssertions::appendPersistsSessionScopedState,
        SessionServiceAssertions::appendRemovesStateWithSentinel,
        SessionServiceAssertions::appendSyncsCallerSession,
        SessionServiceAssertions::createSessionRejectsDuplicateId,
        SessionServiceAssertions::createSessionAllowsSameIdForDifferentUser,
        SessionServiceAssertions::appScopedStateSharedAcrossSessions,
        SessionServiceAssertions::userScopedStateSharedForSameUser,
        SessionServiceAssertions::createSessionScopesPrefixedInitialState,
        SessionServiceAssertions::createSessionDropsRemovalSentinelInInitialState,
        SessionServiceAssertions::createSessionMergesScopedSeedOntoExistingState,
        SessionServiceAssertions::appendPartialNotPersisted,
      )

    // A fresh store and service for each, as each backend test gives every assertion its own.
    for (assertion in assertions) {
      assertion(
        WarmingSessionService(
          BufferedSessionService(
            InMemorySessionService(),
            Dispatchers.Default + parent,
            BufferedSessionServiceConfig(cacheConfig = SessionCacheConfig()),
          )
        )
      )
    }
  }

  private fun service(
    cacheConfig: SessionCacheConfig = SessionCacheConfig(),
    context: CoroutineContext = Dispatchers.Default + parent,
  ) =
    BufferedSessionService(
      store,
      context,
      BufferedSessionServiceConfig(
        retryPolicy = ExponentialBackoff(maxAttempts = 1),
        cacheConfig = cacheConfig,
      ),
    )

  /** Appends the next event of [session], with [stateDelta], where the test ignores the result. */
  private suspend fun BufferedSessionService.append(
    session: Session,
    stateDelta: Map<String, Any> = emptyMap(),
  ) {
    val unused = appendEvent(session, session.nextEvent(stateDelta))
  }

  private companion object {
    const val APP = "cache-app"
    const val USER = "user"
    val KEY = SessionKey(APP, USER, "session")
  }

  /** Reads each session it creates, so the reads that follow hit the cache. */
  private class WarmingSessionService(private val inner: SessionService) : SessionService by inner {
    override suspend fun createSession(key: SessionKey, state: Map<String, Any>?): Session =
      inner.createSession(key, state).also {
        val unused = inner.getSession(it.key)
      }
  }

  /**
   * An [InMemorySessionService] that counts [getSession] calls in [reads] (and those begun in
   * [readsStarted]) and holds them while [readsOpen] is false, holds appends while [appendsOpen] is
   * false and deletes while [deletesOpen] is false (counting those begun in [deletesStarted]), and
   * fails every append while [appendsFail] is true. While [createsFailAfterward] or
   * [deletesFailAfterward] is true, it creates or deletes and then throws, as a store that times
   * out after applying the change would. A read returns the session under [returnedKey] if set.
   */
  private class GatedStore(val inner: InMemorySessionService = InMemorySessionService()) :
    SessionService by inner {
    val reads = MutableStateFlow(0)
    val readsStarted = MutableStateFlow(0)
    val readsOpen = MutableStateFlow(true)
    val appendsOpen = MutableStateFlow(true)
    val appendsFail = MutableStateFlow(false)
    val appendFailure = MutableStateFlow<Throwable>(IllegalStateException("store unavailable"))
    val deletesStarted = MutableStateFlow(0)
    val deletesOpen = MutableStateFlow(true)
    val createsFailAfterward = MutableStateFlow(false)
    val deletesFailAfterward = MutableStateFlow(false)
    val returnedKey = MutableStateFlow<SessionKey?>(null)

    override suspend fun createSession(key: SessionKey, state: Map<String, Any>?): Session {
      val created = inner.createSession(key, state)
      if (createsFailAfterward.value) throw IllegalStateException("store timed out")
      return created
    }

    override suspend fun getSession(key: SessionKey, config: GetSessionConfig?): Session? {
      readsStarted.update { it + 1 }
      readsOpen.first { it }
      yield()
      reads.update { it + 1 }
      val session = inner.getSession(key, config)
      return returnedKey.value?.let { session?.copy(key = it) } ?: session
    }

    override suspend fun appendEvent(session: Session, event: Event): Event {
      appendsOpen.first { it }
      if (appendsFail.value) throw appendFailure.value
      return inner.appendEvent(session, event)
    }

    override suspend fun deleteSession(key: SessionKey) {
      deletesStarted.update { it + 1 }
      deletesOpen.first { it }
      inner.deleteSession(key)
      if (deletesFailAfterward.value) throw IllegalStateException("store timed out")
    }
  }
}
