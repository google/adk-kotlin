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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** Tests for artifact names that collide with the metadata document. */
class FileArtifactReservedNameRegressionTest {
  private lateinit var root: File
  private lateinit var service: FileArtifactService
  private val sessionKey = SessionKey("app", "user", "session")
  private val payload = "user payload".toByteArray()
  private val artifact = Part(inlineData = Blob(data = payload, mimeType = "text/plain"))

  @BeforeTest
  fun setUp() {
    root = Files.createTempDirectory("adk-artifact-reserved-name").toFile()
    service = FileArtifactService(root.path)
  }

  @AfterTest
  fun tearDown() {
    root.deleteRecursively()
  }

  @Test
  fun saveArtifact_reservedBasenames_areRejectedBeforeWriting() = runBlocking {
    val filenames =
      listOf(
        "metadata.json",
        "Metadata.Json",
        "METADATA.JSON",
        "nested/metadata.json",
        "nested\\metadata.json",
        "nested/../metadata.json",
        "metadata.json/child/..",
        "user:metadata.json",
        "user:nested/Metadata.Json",
        "metadata.json.",
        "metadata.json...",
        "metadata.json . . ",
      )

    for (key in listOf(sessionKey, sessionKey.copy(id = null))) {
      for (filename in filenames) {
        val failure =
          assertFailsWith<IllegalArgumentException>("filename=$filename, session=${key.id}") {
            service.saveArtifact(key, filename, artifact)
          }
        assertTrue(failure.message.orEmpty().contains("reserved"))
        assertTrue(root.listFiles().orEmpty().isEmpty())
      }
    }
  }

  @Test
  fun saveAndReloadArtifact_reservedBasename_isRejectedBeforeWriting() = runBlocking {
    assertFailsWith<IllegalArgumentException> {
      service.saveAndReloadArtifact(sessionKey, "metadata.json", artifact)
    }
    assertTrue(root.listFiles().orEmpty().isEmpty())
  }

  @Test
  fun saveArtifact_similarNamesAndMetadataParentDirectory_roundTrip() = runBlocking {
    for (filename in listOf("report.json", "metadata.json.bak", "metadata.json/report.txt")) {
      assertEquals(0, service.saveArtifact(sessionKey, filename, artifact))
      assertContentEquals(payload, service.loadArtifact(sessionKey, filename)?.inlineData?.data)
    }
  }

  @Test
  fun existingReservedArtifact_remainsReadableAndDeletable() = runBlocking {
    val artifactDir = File(root, "users/user/sessions/session/artifacts/metadata.json")
    val versionDir = File(artifactDir, "versions/0")
    assertTrue(versionDir.mkdirs())
    val metadata =
      """
      {"version":0,"fileName":"metadata.json","canonicalUri":"file:///legacy",
       "createTime":0.0,"mimeType":"application/json"}
      """
        .trimIndent()
        .toByteArray()
    File(versionDir, "metadata.json").writeBytes(metadata)

    assertFailsWith<IllegalArgumentException> {
      service.saveArtifact(sessionKey, "metadata.json", artifact)
    }
    assertContentEquals(
      metadata,
      service.loadArtifact(sessionKey, "metadata.json")?.inlineData?.data,
    )
    assertEquals(listOf(0), service.listVersions(sessionKey, "metadata.json"))
    assertEquals(listOf("metadata.json"), service.listArtifactKeys(sessionKey))

    service.deleteArtifact(sessionKey, "metadata.json")

    assertFalse(artifactDir.exists())
    assertNull(service.loadArtifact(sessionKey, "metadata.json"))
  }
}
