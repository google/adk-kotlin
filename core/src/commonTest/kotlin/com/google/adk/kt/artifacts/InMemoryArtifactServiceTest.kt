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

package com.google.adk.kt.artifacts

import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.Part
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest

/** Unit tests for [InMemoryArtifactService]. */
class InMemoryArtifactServiceTest {

  private lateinit var service: InMemoryArtifactService

  @BeforeTest
  fun setUp() {
    service = InMemoryArtifactService()
  }

  @Test
  fun saveArtifact_savesAndReturnsVersion() = runTest {
    val artifact =
      Part(inlineData = Blob(data = "test content".toByteArray(), mimeType = "text/plain"))
    val version = service.saveArtifact(SESSION_KEY, FILENAME, artifact)
    assertEquals(0, version)
  }

  @Test
  fun loadArtifact_loadsLatest() = runTest {
    val artifact1 =
      Part(inlineData = Blob(data = "content 1".toByteArray(), mimeType = "text/plain"))
    val artifact2 =
      Part(inlineData = Blob(data = "content 2".toByteArray(), mimeType = "text/plain"))
    val unused1 = service.saveArtifact(SESSION_KEY, FILENAME, artifact1)
    val unused2 = service.saveArtifact(SESSION_KEY, FILENAME, artifact2)

    val result = service.loadArtifact(SESSION_KEY, FILENAME)
    assertEquals(artifact2, result)
  }

  @Test
  fun loadArtifact_loadsByVersion() = runTest {
    val artifact1 =
      Part(inlineData = Blob(data = "content 1".toByteArray(), mimeType = "text/plain"))
    val artifact2 =
      Part(inlineData = Blob(data = "content 2".toByteArray(), mimeType = "text/plain"))
    val unused1 = service.saveArtifact(SESSION_KEY, FILENAME, artifact1)
    val unused2 = service.saveArtifact(SESSION_KEY, FILENAME, artifact2)

    val result = service.loadArtifact(SESSION_KEY, FILENAME, 0)
    assertEquals(artifact1, result)
  }

  @Test
  fun saveAndReloadArtifact_reloadsArtifact() = runTest {
    val artifact =
      Part(inlineData = Blob(data = "test content".toByteArray(), mimeType = "text/plain"))
    val result = service.saveAndReloadArtifact(SESSION_KEY, FILENAME, artifact)
    assertEquals(artifact, result)
  }

  @Test
  fun listArtifactKeys_returnsFilenames() = runTest {
    val artifact = Part(inlineData = Blob(data = "content".toByteArray(), mimeType = "text/plain"))
    val unused1 = service.saveArtifact(SESSION_KEY, "file1.txt", artifact)
    val unused2 = service.saveArtifact(SESSION_KEY, "file2.txt", artifact)

    val response = service.listArtifactKeys(SESSION_KEY)
    assertContentEquals(listOf("file1.txt", "file2.txt"), response)
  }

  @Test
  fun deleteArtifact_removesArtifact() = runTest {
    val artifact = Part(inlineData = Blob(data = "content".toByteArray(), mimeType = "text/plain"))
    val unused = service.saveArtifact(SESSION_KEY, FILENAME, artifact)

    service.deleteArtifact(SESSION_KEY, FILENAME)

    val result = service.loadArtifact(SESSION_KEY, FILENAME)
    assertNull(result)
  }

  @Test
  fun userNamespacedArtifact_isVisibleAcrossSessionsForSameUser() = runBlocking {
    val unused = service.saveArtifact(SESSION_KEY, USER_FILENAME, part("shared"))

    val loaded = service.loadArtifact(OTHER_SESSION_KEY, USER_FILENAME)

    assertEquals(part("shared"), loaded)
  }

  @Test
  fun listArtifactKeys_includesUserKeysSavedFromAnotherSession() = runBlocking {
    val unused0 = service.saveArtifact(SESSION_KEY, "session.txt", part("x"))
    val unused1 = service.saveArtifact(OTHER_SESSION_KEY, "user:global.txt", part("x"))

    assertContentEquals(
      listOf("session.txt", "user:global.txt"),
      service.listArtifactKeys(SESSION_KEY),
    )
    // The saving session has nothing of its own, so this also covers the empty-session-store case.
    assertContentEquals(listOf("user:global.txt"), service.listArtifactKeys(OTHER_SESSION_KEY))
  }

  @Test
  fun listVersions_userScoped_accumulatesAcrossSessions() = runBlocking {
    val unused0 = service.saveArtifact(SESSION_KEY, USER_FILENAME, part("first"))
    val unused1 = service.saveArtifact(OTHER_SESSION_KEY, USER_FILENAME, part("second"))

    assertContentEquals(listOf(0, 1), service.listVersions(THIRD_SESSION_KEY, USER_FILENAME))
  }

  @Test
  fun saveArtifact_userScoped_versionsAccumulateAcrossSessions() = runBlocking {
    assertEquals(0, service.saveArtifact(SESSION_KEY, USER_FILENAME, part("first")))
    assertEquals(1, service.saveArtifact(OTHER_SESSION_KEY, USER_FILENAME, part("second")))
  }

  @Test
  fun loadArtifact_userScoped_byExplicitVersionFromAnotherSession() = runBlocking {
    val unused0 = service.saveArtifact(SESSION_KEY, USER_FILENAME, part("first"))
    val unused1 = service.saveArtifact(SESSION_KEY, USER_FILENAME, part("second"))

    assertEquals(part("first"), service.loadArtifact(OTHER_SESSION_KEY, USER_FILENAME, 0))
  }

  @Test
  fun saveAndReloadArtifact_userScoped_isVisibleAcrossSessions() = runBlocking {
    val unused = service.saveAndReloadArtifact(SESSION_KEY, USER_FILENAME, part("shared"))

    assertEquals(part("shared"), service.loadArtifact(OTHER_SESSION_KEY, USER_FILENAME))
  }

  @Test
  fun deleteArtifact_userScoped_fromAnotherSession() = runBlocking {
    val unused = service.saveArtifact(SESSION_KEY, USER_FILENAME, part("shared"))

    service.deleteArtifact(OTHER_SESSION_KEY, USER_FILENAME)

    assertNull(service.loadArtifact(SESSION_KEY, USER_FILENAME))
    assertContentEquals(emptyList(), service.listArtifactKeys(SESSION_KEY))
  }

  @Test
  fun nullSessionId_userScopedArtifact_reachesSharedUserScope() = runBlocking {
    val unused = service.saveArtifact(SESSION_KEY.copy(id = null), USER_FILENAME, part("shared"))

    assertEquals(part("shared"), service.loadArtifact(SESSION_KEY, USER_FILENAME))
  }

  @Test
  fun nullSessionId_sessionScopedName_isRejected() = runBlocking {
    val noSession = SESSION_KEY.copy(id = null)

    assertFailsWith<IllegalArgumentException> {
      service.saveArtifact(noSession, FILENAME, part("x"))
    }
    assertFailsWith<IllegalArgumentException> { service.loadArtifact(noSession, FILENAME) }
    assertFailsWith<IllegalArgumentException> { service.deleteArtifact(noSession, FILENAME) }
    val unused =
      assertFailsWith<IllegalArgumentException> { service.listVersions(noSession, FILENAME) }
  }

  @Test
  fun nullSessionId_listArtifactKeys_returnsUserScopeOnly() = runBlocking {
    val unused0 = service.saveArtifact(SESSION_KEY, FILENAME, part("session"))
    val unused1 = service.saveArtifact(SESSION_KEY, USER_FILENAME, part("user"))

    assertContentEquals(
      listOf(USER_FILENAME),
      service.listArtifactKeys(SESSION_KEY.copy(id = null)),
    )
  }

  @Test
  fun listArtifactKeys_isSorted() = runBlocking {
    val unused0 = service.saveArtifact(SESSION_KEY, "b.txt", part("x"))
    val unused1 = service.saveArtifact(SESSION_KEY, "a.txt", part("x"))

    assertContentEquals(listOf("a.txt", "b.txt"), service.listArtifactKeys(SESSION_KEY))
  }

  @Test
  fun userScopedArtifact_isScopedToAppAndUser() = runBlocking {
    val unused = service.saveArtifact(SESSION_KEY, USER_FILENAME, part("shared"))
    val otherUser = SESSION_KEY.copy(userId = "other-user")
    val otherApp = SESSION_KEY.copy(appName = "other-app")

    assertNull(service.loadArtifact(otherUser, USER_FILENAME))
    assertNull(service.loadArtifact(otherApp, USER_FILENAME))
    assertContentEquals(emptyList(), service.listArtifactKeys(otherUser))
    assertContentEquals(emptyList(), service.listArtifactKeys(otherApp))
  }

  @Test
  fun sessionNamedUser_artifactsStayPrivateToThatSession() = runBlocking {
    // A session may legitimately be named "user"; its artifacts must not reach the user scope.
    val sessionNamedUser = SESSION_KEY.copy(id = "user")
    val unused = service.saveArtifact(sessionNamedUser, "report.txt", part("private"))

    assertNull(service.loadArtifact(OTHER_SESSION_KEY, "report.txt"))
    assertContentEquals(emptyList(), service.listArtifactKeys(OTHER_SESSION_KEY))
  }

  @Test
  fun deleteArtifact_userScoped_leavesSessionScopedNameIntact() = runBlocking {
    val unused0 = service.saveArtifact(SESSION_KEY, "x.txt", part("session"))
    val unused1 = service.saveArtifact(SESSION_KEY, "user:x.txt", part("user"))

    service.deleteArtifact(SESSION_KEY, "user:x.txt")

    assertEquals(part("session"), service.loadArtifact(SESSION_KEY, "x.txt"))
    assertNull(service.loadArtifact(SESSION_KEY, "user:x.txt"))
  }

  @Test
  fun listArtifactKeys_failedUserScopedReads_doNotCreatePhantomKeys() = runBlocking {
    // Reads must not materialize the store they miss in, or the miss shows up as a key.
    assertNull(service.loadArtifact(SESSION_KEY, USER_FILENAME))
    assertContentEquals(emptyList(), service.listVersions(SESSION_KEY, USER_FILENAME))
    service.deleteArtifact(SESSION_KEY, USER_FILENAME)

    assertContentEquals(emptyList(), service.listArtifactKeys(OTHER_SESSION_KEY))
  }

  @Test
  fun sessionScopedArtifact_staysPrivateToItsSession() = runBlocking {
    val unused = service.saveArtifact(SESSION_KEY, FILENAME, part("private"))

    assertNull(service.loadArtifact(OTHER_SESSION_KEY, FILENAME))
  }

  private fun part(content: String): Part =
    Part(inlineData = Blob(data = content.toByteArray(), mimeType = "text/plain"))

  companion object {
    private const val APP_NAME = "test-app"
    private const val USER_ID = "test-user"
    private const val SESSION_ID = "test-session"
    private const val FILENAME = "test-file.txt"
    private const val USER_FILENAME = "user:profile.json"
    private val SESSION_KEY = SessionKey(appName = APP_NAME, userId = USER_ID, id = SESSION_ID)
    private val OTHER_SESSION_KEY = SESSION_KEY.copy(id = "another-session")
    private val THIRD_SESSION_KEY = SESSION_KEY.copy(id = "third-session")
  }
}
