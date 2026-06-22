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
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** Unit tests for [FileArtifactService]. */
class FileArtifactServiceTest {

  private lateinit var baseDir: File
  private lateinit var service: FileArtifactService

  @BeforeTest
  fun setUp() {
    baseDir = Files.createTempDirectory("file-artifacts").toFile()
    service = FileArtifactService(baseDir.path)
  }

  @AfterTest
  fun tearDown() {
    baseDir.deleteRecursively()
  }

  @Test
  fun saveArtifact_firstVersion_returnsZero() = runTest {
    val version = service.saveArtifact(SESSION_KEY, FILENAME, part("hello"))
    assertEquals(0, version)
  }

  @Test
  fun saveArtifact_successiveVersions_increment() = runTest {
    val v0 = service.saveArtifact(SESSION_KEY, FILENAME, part("a"))
    val v1 = service.saveArtifact(SESSION_KEY, FILENAME, part("b"))
    assertEquals(0, v0)
    assertEquals(1, v1)
  }

  @Test
  fun loadArtifact_noVersion_loadsLatest() = runTest {
    val unused0 = service.saveArtifact(SESSION_KEY, FILENAME, part("content 1"))
    val unused1 = service.saveArtifact(SESSION_KEY, FILENAME, part("content 2"))

    val loaded = service.loadArtifact(SESSION_KEY, FILENAME)

    assertEquals(part("content 2"), loaded)
  }

  @Test
  fun loadArtifact_explicitVersion_loadsThatVersion() = runTest {
    val unused0 = service.saveArtifact(SESSION_KEY, FILENAME, part("content 1"))
    val unused1 = service.saveArtifact(SESSION_KEY, FILENAME, part("content 2"))

    val loaded = service.loadArtifact(SESSION_KEY, FILENAME, version = 0)

    assertEquals(part("content 1"), loaded)
  }

  @Test
  fun loadArtifact_preservesMimeType() = runTest {
    val unused =
      service.saveArtifact(
        SESSION_KEY,
        FILENAME,
        Part(inlineData = Blob(data = "x".toByteArray(), mimeType = "image/png")),
      )

    val loaded = service.loadArtifact(SESSION_KEY, FILENAME)

    assertEquals("image/png", loaded?.inlineData?.mimeType)
  }

  @Test
  fun loadArtifact_nullMimeType_roundTripsToNull() = runTest {
    val unused =
      service.saveArtifact(
        SESSION_KEY,
        FILENAME,
        Part(inlineData = Blob(data = "x".toByteArray(), mimeType = null)),
      )

    val loaded = service.loadArtifact(SESSION_KEY, FILENAME)

    assertNull(loaded?.inlineData?.mimeType)
    assertContentEquals("x".toByteArray(), loaded?.inlineData?.data)
  }

  @Test
  fun loadArtifact_preservesDisplayName() = runTest {
    val unused =
      service.saveArtifact(
        SESSION_KEY,
        FILENAME,
        Part(
          inlineData =
            Blob(data = "x".toByteArray(), mimeType = "text/plain", displayName = "My Report")
        ),
      )

    val loaded = service.loadArtifact(SESSION_KEY, FILENAME)

    assertEquals("My Report", loaded?.inlineData?.displayName)
  }

  @Test
  fun loadArtifact_unknownArtifact_returnsNull() = runTest {
    assertNull(service.loadArtifact(SESSION_KEY, "missing.txt"))
  }

  @Test
  fun loadArtifact_unknownVersion_returnsNull() = runTest {
    val unused = service.saveArtifact(SESSION_KEY, FILENAME, part("a"))
    assertNull(service.loadArtifact(SESSION_KEY, FILENAME, version = 99))
  }

  @Test
  fun loadArtifact_payloadFileMissing_returnsNull() = runTest {
    val unused = service.saveArtifact(SESSION_KEY, FILENAME, part("a"))
    val payload =
      File(baseDir, "users/$USER_ID/sessions/$SESSION_ID/artifacts/$FILENAME/versions/0/$FILENAME")
    assertTrue(payload.delete())

    assertNull(service.loadArtifact(SESSION_KEY, FILENAME))
  }

  @Test
  fun saveArtifact_withoutInlineData_throws() = runTest {
    assertFailsWith<IllegalArgumentException> {
      service.saveArtifact(SESSION_KEY, FILENAME, Part(text = "not an artifact"))
    }
  }

  @Test
  fun saveArtifact_emptyData_throws() = runTest {
    assertFailsWith<IllegalArgumentException> {
      service.saveArtifact(SESSION_KEY, FILENAME, Part(inlineData = Blob(data = ByteArray(0))))
    }
  }

  @Test
  fun saveArtifact_traversalFilename_throws() = runTest {
    assertFailsWith<IllegalArgumentException> {
      service.saveArtifact(SESSION_KEY, "../../escape.txt", part("x"))
    }
  }

  @Test
  fun saveArtifact_absoluteFilename_throws() = runTest {
    assertFailsWith<IllegalArgumentException> {
      service.saveArtifact(SESSION_KEY, "/etc/passwd", part("x"))
    }
  }

  @Test
  fun saveArtifact_emptyUserId_throws() = runTest {
    val key = SESSION_KEY.copy(userId = "")
    assertFailsWith<IllegalArgumentException> { service.saveArtifact(key, FILENAME, part("x")) }
  }

  @Test
  fun saveArtifact_userIdWithSeparator_throws() = runTest {
    val key = SESSION_KEY.copy(userId = "bad/user")
    assertFailsWith<IllegalArgumentException> { service.saveArtifact(key, FILENAME, part("x")) }
  }

  @Test
  fun saveArtifact_sessionIdTraversalSegment_throws() = runTest {
    val key = SESSION_KEY.copy(id = "..")
    assertFailsWith<IllegalArgumentException> { service.saveArtifact(key, FILENAME, part("x")) }
  }

  @Test
  fun saveArtifact_userIdWithNullByte_throws() = runTest {
    val key = SESSION_KEY.copy(userId = "bad\u0000user")
    assertFailsWith<IllegalArgumentException> { service.saveArtifact(key, FILENAME, part("x")) }
  }

  @Test
  fun saveAndReloadArtifact_returnsFileUriAndMimeType() = runTest {
    val reloaded =
      service.saveAndReloadArtifact(
        SESSION_KEY,
        FILENAME,
        Part(inlineData = Blob(data = "bytes".toByteArray(), mimeType = "text/plain")),
      )

    assertEquals("text/plain", reloaded.fileData?.mimeType)
    assertTrue(reloaded.fileData?.fileUri?.startsWith("file:") == true)
  }

  @Test
  fun saveAndReloadArtifact_nullMimeType_defaultsToOctetStream() = runTest {
    val reloaded =
      service.saveAndReloadArtifact(
        SESSION_KEY,
        FILENAME,
        Part(inlineData = Blob(data = "bytes".toByteArray(), mimeType = null)),
      )

    assertEquals("application/octet-stream", reloaded.fileData?.mimeType)
  }

  @Test
  fun listArtifactKeys_returnsSortedSessionFilenames() = runTest {
    val unused0 = service.saveArtifact(SESSION_KEY, "b.txt", part("x"))
    val unused1 = service.saveArtifact(SESSION_KEY, "a.txt", part("x"))

    assertContentEquals(listOf("a.txt", "b.txt"), service.listArtifactKeys(SESSION_KEY))
  }

  @Test
  fun listVersions_returnsAllVersionsSorted() = runTest {
    val unused0 = service.saveArtifact(SESSION_KEY, FILENAME, part("a"))
    val unused1 = service.saveArtifact(SESSION_KEY, FILENAME, part("b"))
    val unused2 = service.saveArtifact(SESSION_KEY, FILENAME, part("c"))

    assertContentEquals(listOf(0, 1, 2), service.listVersions(SESSION_KEY, FILENAME))
  }

  @Test
  fun deleteArtifact_removesArtifact() = runTest {
    val unused = service.saveArtifact(SESSION_KEY, FILENAME, part("a"))

    service.deleteArtifact(SESSION_KEY, FILENAME)

    assertNull(service.loadArtifact(SESSION_KEY, FILENAME))
    assertContentEquals(emptyList(), service.listVersions(SESSION_KEY, FILENAME))
  }

  @Test
  fun deleteArtifact_missingArtifact_isNoOp() = runTest {
    service.deleteArtifact(SESSION_KEY, "never-saved.txt")

    assertNull(service.loadArtifact(SESSION_KEY, "never-saved.txt"))
  }

  @Test
  fun userNamespacedArtifact_isVisibleAcrossSessionsForSameUser() = runTest {
    val userFile = "user:profile.json"
    val unused = service.saveArtifact(SESSION_KEY, userFile, part("shared"))

    val otherSession = SESSION_KEY.copy(id = "another-session")
    val loaded = service.loadArtifact(otherSession, userFile)

    assertEquals(part("shared"), loaded)
  }

  @Test
  fun nullSessionId_isTreatedAsUserScoped() = runTest {
    val key = SESSION_KEY.copy(id = null)
    val unused = service.saveArtifact(key, "doc.txt", part("scoped-to-user"))

    val loaded = service.loadArtifact(key, "doc.txt")

    assertEquals(part("scoped-to-user"), loaded)
  }

  @Test
  fun sessionIdEqualsUser_doesNotCollideWithUserScope() = runTest {
    val key = SESSION_KEY.copy(id = "user")
    val unused0 = service.saveArtifact(key, "report.txt", part("session-data"))
    val unused1 = service.saveArtifact(key, "user:report.txt", part("user-data"))

    assertEquals(part("session-data"), service.loadArtifact(key, "report.txt"))
    assertEquals(part("user-data"), service.loadArtifact(key, "user:report.txt"))
  }

  @Test
  fun nestedFilename_roundTrips() = runTest {
    val unused = service.saveArtifact(SESSION_KEY, "images/photo.png", part("pixels"))

    assertEquals(part("pixels"), service.loadArtifact(SESSION_KEY, "images/photo.png"))
  }

  @Test
  fun listArtifactKeys_includesUserNamespacedKeysWithPrefix() = runTest {
    val unused0 = service.saveArtifact(SESSION_KEY, "session.txt", part("x"))
    val unused1 = service.saveArtifact(SESSION_KEY, "user:global.txt", part("x"))

    assertContentEquals(
      listOf("session.txt", "user:global.txt"),
      service.listArtifactKeys(SESSION_KEY),
    )
  }

  @Test
  fun listArtifactKeys_returnsOriginalFilenameFromMetadata() = runTest {
    // The stored fileName (the caller's original) differs from the normalized on-disk path
    // ("report.txt"), so this pins that listing reports metadata.fileName, not the reconstructed
    // path.
    val unused = service.saveArtifact(SESSION_KEY, "sub/../report.txt", part("x"))

    assertContentEquals(listOf("sub/../report.txt"), service.listArtifactKeys(SESSION_KEY))
  }

  @Test
  fun saveArtifact_sessionScoped_usesCrossLanguageLayout() = runTest {
    val unused = service.saveArtifact(SESSION_KEY, FILENAME, part("a"))

    val payload =
      File(baseDir, "users/$USER_ID/sessions/$SESSION_ID/artifacts/$FILENAME/versions/0/$FILENAME")
    val metadata =
      File(
        baseDir,
        "users/$USER_ID/sessions/$SESSION_ID/artifacts/$FILENAME/versions/0/metadata.json",
      )
    assertTrue(payload.exists())
    assertTrue(metadata.exists())
  }

  @Test
  fun saveArtifact_userScoped_stripsPrefixAndOmitsSessionFromLayout() = runTest {
    val unused = service.saveArtifact(SESSION_KEY, "user:global.txt", part("a"))

    val payload = File(baseDir, "users/$USER_ID/artifacts/global.txt/versions/0/global.txt")
    assertTrue(payload.exists())
  }

  @Test
  fun artifacts_persistAcrossServiceInstancesOnSameDir() = runTest {
    val unused = service.saveArtifact(SESSION_KEY, FILENAME, part("durable"))

    val reopened = FileArtifactService(baseDir.path)

    assertEquals(part("durable"), reopened.loadArtifact(SESSION_KEY, FILENAME))
  }

  private fun part(content: String): Part =
    Part(inlineData = Blob(data = content.toByteArray(), mimeType = "text/plain"))

  companion object {
    private const val APP_NAME = "test-app"
    private const val USER_ID = "test-user"
    private const val SESSION_ID = "test-session"
    private const val FILENAME = "test-file.txt"
    private val SESSION_KEY = SessionKey(appName = APP_NAME, userId = USER_ID, id = SESSION_ID)
  }
}
