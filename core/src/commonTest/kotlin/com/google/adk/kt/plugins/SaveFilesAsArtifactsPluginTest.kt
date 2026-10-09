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

package com.google.adk.kt.plugins

import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.toCallbackContext
import com.google.adk.kt.artifacts.ArtifactService
import com.google.adk.kt.artifacts.InMemoryArtifactService
import com.google.adk.kt.callbacks.CallbackChoice
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.InMemorySessionService
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.testing.DummyArtifactService
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.testInvocationContext
import com.google.adk.kt.testing.testSession
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.FileData
import com.google.adk.kt.types.Part
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

class SaveFilesAsArtifactsPluginTest {

  private val service = FakeArtifactService()
  private val context = testInvocationContext(artifactService = service)
  private val sessionKey = testSession().key

  @Test
  fun constructor_defaultName() {
    assertEquals("save_files_as_artifacts_plugin", SaveFilesAsArtifactsPlugin().name)
  }

  @Test
  fun onUserMessage_savesTheFileAndPutsAPlaceholderAndReferenceInItsPlace() = runBlocking {
    val file = filePart("report.pdf")

    val result = SaveFilesAsArtifactsPlugin().onUserMessage(context, userMessage(file))

    assertEquals(listOf(Save("report.pdf", file)), service.saves)
    assertEquals(
      userMessage(placeholder("report.pdf"), reference("report.pdf", "gs://bucket/report.pdf/0")),
      result,
    )
  }

  @Test
  fun onUserMessage_attachFileReferenceFalse_putsOnlyAPlaceholder() = runBlocking {
    val file = filePart("report.pdf")

    val result =
      SaveFilesAsArtifactsPlugin(attachFileReference = false)
        .onUserMessage(context, userMessage(file))

    assertEquals(listOf(Save("report.pdf", file)), service.saves)
    assertEquals(userMessage(placeholder("report.pdf")), result)
  }

  @Test
  fun onUserMessage_uriTheModelCannotRead_putsOnlyAPlaceholder() = runBlocking {
    val fileService = FakeArtifactService(uriPrefix = "file:///tmp/")

    val result =
      SaveFilesAsArtifactsPlugin()
        .onUserMessage(
          testInvocationContext(artifactService = fileService),
          userMessage(filePart("report.pdf")),
        )

    assertEquals(userMessage(placeholder("report.pdf")), result)
  }

  @Test
  fun onUserMessage_upperCaseUriScheme_addsTheReference() = runBlocking {
    val upperCaseService = FakeArtifactService(uriPrefix = "GS://bucket/")

    val result =
      SaveFilesAsArtifactsPlugin()
        .onUserMessage(
          testInvocationContext(artifactService = upperCaseService),
          userMessage(filePart("report.pdf")),
        )

    assertEquals(
      userMessage(placeholder("report.pdf"), reference("report.pdf", "GS://bucket/report.pdf/0")),
      result,
    )
  }

  @Test
  fun onUserMessage_fileWithoutMimeType_takesTheReferenceTypeFromTheStoredFile() = runBlocking {
    val file =
      Part(
        inlineData = Blob(mimeType = "", displayName = "notes", data = "text".encodeToByteArray())
      )

    val result = SaveFilesAsArtifactsPlugin().onUserMessage(context, userMessage(file))

    val reference =
      Part(
        fileData =
          FileData(mimeType = "text/plain", displayName = "notes", fileUri = "gs://bucket/notes/0")
      )
    assertEquals(userMessage(placeholder("notes"), reference), result)
  }

  @Test
  fun onUserMessage_inMemoryService_putsOnlyAPlaceholder() = runBlocking {
    val inMemory = InMemoryArtifactService()
    val file = filePart("report.pdf")

    val result =
      SaveFilesAsArtifactsPlugin()
        .onUserMessage(testInvocationContext(artifactService = inMemory), userMessage(file))

    assertEquals(userMessage(placeholder("report.pdf")), result)
    assertEquals(file, inMemory.loadArtifact(sessionKey, "report.pdf"))
  }

  @Test
  fun onUserMessage_noDisplayName_savesUnderAGeneratedName() = runBlocking {
    val result =
      SaveFilesAsArtifactsPlugin(attachFileReference = false)
        .onUserMessage(context, userMessage(Part(text = "See:"), filePart(displayName = "")))

    val generatedName = "artifact_test-invocation-id_1"
    assertEquals(listOf(generatedName), service.saves.map { it.fileName })
    assertEquals(userMessage(Part(text = "See:"), placeholder(generatedName)), result)
  }

  @Test
  fun onUserMessage_severalFiles_keepsTheOtherPartsInPlace() = runBlocking {
    val result =
      SaveFilesAsArtifactsPlugin()
        .onUserMessage(
          context,
          userMessage(filePart("a.txt"), Part(text = "Some text between files"), filePart("b.jpg")),
        )

    assertEquals(
      userMessage(
        placeholder("a.txt"),
        reference("a.txt", "gs://bucket/a.txt/0"),
        Part(text = "Some text between files"),
        placeholder("b.jpg"),
        reference("b.jpg", "gs://bucket/b.jpg/0"),
      ),
      result,
    )
  }

  @Test
  fun onUserMessage_noArtifactService_returnsTheMessageUnchanged() = runBlocking {
    val message = userMessage(filePart())

    assertSame(
      message,
      SaveFilesAsArtifactsPlugin().onUserMessage(testInvocationContext(), message),
    )
  }

  @Test
  fun onUserMessage_noFiles_returnsTheMessageUnchanged() = runBlocking {
    val message = userMessage(Part(text = "Hello"), Part(text = "No files here"))

    assertSame(message, SaveFilesAsArtifactsPlugin().onUserMessage(context, message))
    assertEquals(emptyList(), service.saves)
  }

  @Test
  fun onUserMessage_saveFails_keepsTheFile() = runBlocking {
    val failing = FakeArtifactService(failingNames = setOf("report.pdf"))
    val message = userMessage(filePart("report.pdf"))

    val result =
      SaveFilesAsArtifactsPlugin()
        .onUserMessage(testInvocationContext(artifactService = failing), message)

    assertSame(message, result)
  }

  @Test
  fun onUserMessage_oneOfTwoSavesFails_keepsOnlyTheFileThatFailed() = runBlocking {
    val failing = FakeArtifactService(failingNames = setOf("failure.pdf"))
    val failed = filePart("failure.pdf")

    val result =
      SaveFilesAsArtifactsPlugin()
        .onUserMessage(
          testInvocationContext(artifactService = failing),
          userMessage(filePart("success.pdf"), failed),
        )

    assertEquals(
      userMessage(
        placeholder("success.pdf"),
        reference("success.pdf", "gs://bucket/success.pdf/0"),
        failed,
      ),
      result,
    )
  }

  @Test
  fun onUserMessage_saveCancelled_rethrows() {
    val cancelling =
      FakeArtifactService(
        failingNames = setOf("report.pdf"),
        failure = { CancellationException("Cancelled") },
      )

    assertFailsWith<CancellationException> {
      runBlocking {
        SaveFilesAsArtifactsPlugin()
          .onUserMessage(
            testInvocationContext(artifactService = cancelling),
            userMessage(filePart("report.pdf")),
          )
      }
    }
  }

  @Test
  fun onUserMessage_versionCannotBeReadBack_keepsThePlaceholderAndReportsNoVersion() = runBlocking {
    val plugin = SaveFilesAsArtifactsPlugin()
    val unreadable =
      testInvocationContext(artifactService = FakeArtifactService(versionsReadable = false))

    val result = plugin.onUserMessage(unreadable, userMessage(filePart("report.pdf")))

    assertEquals(
      userMessage(placeholder("report.pdf"), reference("report.pdf", "gs://bucket/report.pdf/0")),
      result,
    )
    assertEquals(
      CallbackChoice.Continue(EventActions()),
      plugin.beforeAgent(unreadable.toCallbackContext()),
    )
  }

  @Test
  fun onUserMessage_versionReadbackCancelled_rethrows() {
    val cancelling =
      FakeArtifactService(
        versionsReadable = false,
        failure = { CancellationException("Cancelled") },
      )

    assertFailsWith<CancellationException> {
      runBlocking {
        SaveFilesAsArtifactsPlugin()
          .onUserMessage(
            testInvocationContext(artifactService = cancelling),
            userMessage(filePart("report.pdf")),
          )
      }
    }
  }

  @Test
  fun onUserMessage_serviceListsNoVersions_reportsNoVersion() = runBlocking {
    val plugin = SaveFilesAsArtifactsPlugin()
    val dummy = testInvocationContext(artifactService = DummyArtifactService())

    val result = plugin.onUserMessage(dummy, userMessage(filePart("report.pdf")))

    assertEquals(userMessage(placeholder("report.pdf")), result)
    assertEquals(
      CallbackChoice.Continue(EventActions()),
      plugin.beforeAgent(dummy.toCallbackContext()),
    )
  }

  @Test
  fun onUserMessage_fileAtTheSizeLimit_isSaved() = runBlocking {
    val result =
      SaveFilesAsArtifactsPlugin(attachFileReference = false)
        .onUserMessage(context, userMessage(filePart("max.pdf", size = MAX_SIZE)))

    assertEquals(listOf("max.pdf"), service.saves.map { it.fileName })
    assertEquals(userMessage(placeholder("max.pdf")), result)
  }

  @Test
  fun onUserMessage_fileOverTheSizeLimit_isReplacedWithAnError() = runBlocking {
    val result =
      SaveFilesAsArtifactsPlugin()
        .onUserMessage(
          context,
          userMessage(filePart("small.pdf"), filePart("large.pdf", size = MAX_SIZE + 1)),
        )

    assertEquals(listOf("small.pdf"), service.saves.map { it.fileName })
    assertEquals(
      userMessage(
        placeholder("small.pdf"),
        reference("small.pdf", "gs://bucket/small.pdf/0"),
        Part(
          text =
            "[Upload Error: File large.pdf (20.00 MB) exceeds the maximum supported size of " +
              "20MB. Please upload a smaller file.]"
        ),
      ),
      result,
    )
  }

  @Test
  fun onUserMessage_fileSizeHalfwayBetweenHundredths_roundsHalfToEven() = runBlocking {
    // 20.125 MB, which Python's `:.2f` formats as 20.12.
    val size = MAX_SIZE + MAX_SIZE / 160

    val result =
      SaveFilesAsArtifactsPlugin()
        .onUserMessage(context, userMessage(filePart("large.pdf", size = size)))

    assertEquals(
      userMessage(
        Part(
          text =
            "[Upload Error: File large.pdf (20.12 MB) exceeds the maximum supported size of " +
              "20MB. Please upload a smaller file.]"
        )
      ),
      result,
    )
  }

  @Test
  fun beforeAgent_reportsTheSavedVersionsOnce() = runBlocking {
    val plugin = SaveFilesAsArtifactsPlugin()
    val earlierVersion = service.saveArtifact(sessionKey, "a.txt", filePart("a.txt"))
    val unused = plugin.onUserMessage(context, userMessage(filePart("a.txt"), filePart("b.txt")))

    val first = plugin.beforeAgent(context.toCallbackContext())
    val second = plugin.beforeAgent(context.toCallbackContext())

    assertEquals(
      CallbackChoice.Continue(
        EventActions(artifactDelta = mutableMapOf("a.txt" to earlierVersion + 1, "b.txt" to 0))
      ),
      first,
    )
    assertEquals(CallbackChoice.Continue(EventActions()), second)
  }

  @Test
  fun beforeAgent_nothingSaved_reportsNothing() = runBlocking {
    val result = SaveFilesAsArtifactsPlugin().beforeAgent(context.toCallbackContext())

    assertEquals(CallbackChoice.Continue(EventActions()), result)
  }

  @Test
  fun runAsync_modelGetsThePlaceholderAndTheAgentEventReportsTheVersion() = runBlocking {
    val requests = mutableListOf<LlmRequest>()
    val agent =
      LlmAgent(
        name = "root",
        model =
          DummyModel("mock-model") { request ->
            requests += request
            flowOf(LlmResponse(content = modelMessage("Got it.")))
          },
      )
    val sessionService = InMemorySessionService()
    val artifactService = InMemoryArtifactService()
    val runner =
      InMemoryRunner(
        agent = agent,
        sessionService = sessionService,
        artifactService = artifactService,
        plugins = listOf(SaveFilesAsArtifactsPlugin()),
      )

    val turns =
      listOf("first", "second").map { text ->
        runner
          .runAsync(
            userId = "user1",
            sessionId = "session1",
            newMessage = userMessage(Part(text = "Read this"), filePart("notes.txt", text)),
          )
          .toList()
      }

    val expectedUserContent = userMessage(Part(text = "Read this"), placeholder("notes.txt"))
    assertEquals(expectedUserContent, requests.last().contents.last())
    assertEquals(
      listOf(mapOf("notes.txt" to 0), mapOf("notes.txt" to 1)),
      turns.map { events -> events.first().actions.artifactDelta },
    )
    val session = sessionService.getSession(SessionKey("InMemoryRunner", "user1", "session1"))
    assertEquals(expectedUserContent, session?.events?.first()?.content)
    val stored =
      artifactService.loadArtifact(SessionKey("InMemoryRunner", "user1", "session1"), "notes.txt")
    assertEquals("second", stored?.inlineData?.data?.decodeToString())
  }

  private fun filePart(
    displayName: String? = "report.pdf",
    text: String = "file contents",
    size: Int? = null,
  ): Part =
    Part(
      inlineData =
        Blob(
          mimeType = "application/pdf",
          displayName = displayName,
          data = size?.let { ByteArray(it) } ?: text.encodeToByteArray(),
        )
    )

  private fun placeholder(fileName: String) = Part(text = "[Uploaded Artifact: \"$fileName\"]")

  private fun reference(fileName: String, uri: String) =
    Part(fileData = FileData(mimeType = "application/pdf", displayName = fileName, fileUri = uri))

  private data class Save(val fileName: String, val artifact: Part)

  /**
   * Stores artifacts in memory and reports each saved version at `<uriPrefix><filename>/<version>`.
   * Saving a name in [failingNames] throws [failure], as does listing versions unless
   * [versionsReadable].
   */
  private class FakeArtifactService(
    private val uriPrefix: String = "gs://bucket/",
    private val failingNames: Set<String> = emptySet(),
    private val versionsReadable: Boolean = true,
    private val failure: () -> Exception = { IllegalStateException("Storage error") },
    private val delegate: InMemoryArtifactService = InMemoryArtifactService(),
  ) : ArtifactService by delegate {
    val saves = mutableListOf<Save>()

    override suspend fun saveArtifact(
      sessionKey: SessionKey,
      filename: String,
      artifact: Part,
    ): Int {
      if (filename in failingNames) throw failure()
      saves += Save(filename, artifact)
      return delegate.saveArtifact(sessionKey, filename, artifact)
    }

    override suspend fun listVersions(sessionKey: SessionKey, filename: String): List<Int> {
      if (!versionsReadable) throw failure()
      return delegate.listVersions(sessionKey, filename)
    }

    override suspend fun saveAndReloadArtifact(
      sessionKey: SessionKey,
      filename: String,
      artifact: Part,
    ): Part {
      val version = saveArtifact(sessionKey, filename, artifact)
      return Part(
        fileData = FileData(mimeType = "text/plain", fileUri = "$uriPrefix$filename/$version")
      )
    }
  }

  companion object {
    private const val MAX_SIZE = 20 * 1024 * 1024
  }
}
