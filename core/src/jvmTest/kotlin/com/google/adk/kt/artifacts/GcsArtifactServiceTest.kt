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

import com.google.adk.kt.agents.BaseAgent
import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.events.Event
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.api.gax.paging.Page
import com.google.cloud.storage.Blob as GcsBlob
import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.Storage
import com.google.cloud.storage.Storage.BlobListOption
import com.google.common.truth.Truth.assertThat
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import org.mockito.kotlin.verify

class GcsArtifactServiceTest {

  private lateinit var service: GcsArtifactService
  private lateinit var storage: Storage

  private val bucketName = "test-bucket"

  @BeforeTest
  fun setUp() {
    storage = mock()
    service = GcsArtifactService(bucketName, storage)
  }

  @Test
  fun saveArtifact_assignsInitialVersion_returnsVersionZero() = runTest {
    val artifact =
      Part(inlineData = Blob(data = "test content".toByteArray(), mimeType = "text/plain"))

    val mockPage = mock<Page<GcsBlob>> { on { iterateAll() } doReturn emptyList() }
    val mockBlob = mock<GcsBlob>()

    storage.stub {
      on { list(eq(bucketName), any<BlobListOption>()) } doReturn mockPage
      on { create(any<BlobInfo>(), any<ByteArray>(), any<Storage.BlobTargetOption>()) } doReturn
        mockBlob
    }

    val version = service.saveArtifact(SESSION_KEY, FILENAME, artifact)

    assertThat(version).isEqualTo(0)
  }

  @Test
  fun saveArtifact_withExistingVersions_incrementsVersion() = runTest {
    val artifact =
      Part(inlineData = Blob(data = "test content".toByteArray(), mimeType = "text/plain"))

    val prefix = "$APP_NAME/$USER_ID/$SESSION_ID/$FILENAME"
    val existingBlob = mock<GcsBlob> { on { name } doReturn "$prefix/4" }
    val mockPage = mock<Page<GcsBlob>> { on { iterateAll() } doReturn listOf(existingBlob) }
    val mockBlob = mock<GcsBlob>()

    storage.stub {
      on { list(eq(bucketName), any<BlobListOption>()) } doReturn mockPage
      on { create(any<BlobInfo>(), any<ByteArray>(), any<Storage.BlobTargetOption>()) } doReturn
        mockBlob
    }

    val version = service.saveArtifact(SESSION_KEY, FILENAME, artifact)

    assertThat(version).isEqualTo(5)
  }

  @Test
  fun listArtifactKeys_returnsOnlyUniqueFilenames() = runTest {
    val blob1 = mock<GcsBlob> { on { name } doReturn "$APP_NAME/$USER_ID/$SESSION_ID/file1.txt/0" }
    val blob2 = mock<GcsBlob> { on { name } doReturn "$APP_NAME/$USER_ID/$SESSION_ID/file1.txt/1" }
    val blob3 = mock<GcsBlob> { on { name } doReturn "$APP_NAME/$USER_ID/$SESSION_ID/file2.txt/0" }
    val mockPage = mock<Page<GcsBlob>> { on { iterateAll() } doReturn listOf(blob1, blob2, blob3) }

    storage.stub { on { list(eq(bucketName), any<BlobListOption>()) } doReturn mockPage }

    val filenames = service.listArtifactKeys(SESSION_KEY)

    assertThat(filenames).containsExactly("file1.txt", "file2.txt")
  }

  @Test
  fun agentContext_withGcsArtifactService_savesArtifact() = runTest {
    val artifact =
      Part(inlineData = Blob(data = "agent dataset".toByteArray(), mimeType = "text/plain"))

    val mockBlob =
      mock<GcsBlob> {
        on { bucket } doReturn bucketName
        on { name } doReturn "$APP_NAME/$USER_ID/$SESSION_ID/$FILENAME/0"
        on { contentType } doReturn "text/plain"
      }

    val mockPage = mock<Page<GcsBlob>> { on { iterateAll() } doReturn emptyList() }

    storage.stub {
      on { list(eq(bucketName), any<BlobListOption>()) } doReturn mockPage
      on { create(any<BlobInfo>(), any<ByteArray>(), any<Storage.BlobTargetOption>()) } doReturn
        mockBlob
    }

    val agent =
      object : BaseAgent(name = "artifact-agent") {
        override fun runAsyncImpl(context: InvocationContext): Flow<Event> = flow {
          val artifactService = context.artifactService
          assertThat(artifactService).isNotNull()
          val unused = artifactService!!.saveArtifact(SESSION_KEY, FILENAME, artifact)
          emit(Event(author = Role.MODEL, content = modelMessage("Saved tracking output")))
        }
      }

    val runner = InMemoryRunner(agent = agent, artifactService = service)
    val events =
      runner
        .runAsync(userId = USER_ID, sessionId = SESSION_ID, newMessage = userMessage("Execute run"))
        .toList()

    assertThat(events).hasSize(1)
    assertThat(events[0].content?.parts?.get(0)?.text).isEqualTo("Saved tracking output")
    verify(storage).create(any<BlobInfo>(), any<ByteArray>(), any<Storage.BlobTargetOption>())
  }

  @Test
  fun saveArtifact_concurrentWrite_throwsException() = runTest {
    val artifact =
      Part(inlineData = Blob(data = "test content".toByteArray(), mimeType = "text/plain"))

    val mockPage = mock<Page<GcsBlob>> { on { iterateAll() } doReturn emptyList() }

    val storageException = com.google.cloud.storage.StorageException(412, "Precondition Failed")

    storage.stub {
      on { list(eq(bucketName), any<BlobListOption>()) } doReturn mockPage
      on { create(any<BlobInfo>(), any<ByteArray>(), any<Storage.BlobTargetOption>()) } doThrow
        storageException
    }

    assertFailsWith<IllegalStateException> { service.saveArtifact(SESSION_KEY, FILENAME, artifact) }
  }

  private companion object {
    const val APP_NAME = "test-app"
    const val USER_ID = "test-user"
    const val SESSION_ID = "test-session"
    const val FILENAME = "test-file.txt"
    val SESSION_KEY = SessionKey(appName = APP_NAME, userId = USER_ID, id = SESSION_ID)
  }
}
