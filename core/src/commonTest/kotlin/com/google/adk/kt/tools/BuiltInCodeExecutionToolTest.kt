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

package com.google.adk.kt.tools

import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.ReadonlyContext
import com.google.adk.kt.artifacts.InMemoryArtifactService
import com.google.adk.kt.events.Event
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.processors.CodeExecutionImageProcessor.Companion.formatCompactTimestamp
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.InMemorySessionService
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.testToolContext
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.CodeExecutionResult
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.ExecutableCode
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.GenerateContentConfig
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.Tool
import com.google.adk.kt.types.ToolCodeExecution
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone

class BuiltInCodeExecutionToolTest {

  @Test
  fun declaration_returnsNull() {
    assertNull(BuiltInCodeExecutionTool().declaration())
  }

  @Test
  fun run_throws(): Unit = runBlocking {
    val unused =
      assertFailsWith<UnsupportedOperationException> {
        BuiltInCodeExecutionTool().run(testToolContext(), emptyMap())
      }
  }

  @Test
  fun processLlmRequest_addsCodeExecutionTool(): Unit = runBlocking {
    val request = LlmRequest(model = DummyModel("gemini-flash-latest"))

    val result = BuiltInCodeExecutionTool().processLlmRequest(testToolContext(), request)

    val tools = assertNotNull(result.config.tools)
    assertEquals(1, tools.size)
    assertNotNull(tools[0].codeExecution)
  }

  @Test
  fun processLlmRequest_existingConfigFields_preserved(): Unit = runBlocking {
    val request =
      LlmRequest(
        config = GenerateContentConfig(temperature = 0.5f),
        model = DummyModel("gemini-flash-latest"),
      )

    val result = BuiltInCodeExecutionTool().processLlmRequest(testToolContext(), request)

    assertEquals(0.5f, result.config.temperature)
    assertNotNull(result.config.tools?.firstOrNull { it.codeExecution != null })
  }

  @Test
  fun processLlmRequest_withOtherTool_preservesExistingAndAppendsCodeExecution(): Unit =
    runBlocking {
      val existingTool =
        Tool(functionDeclarations = listOf(FunctionDeclaration(name = "fn", description = "d")))
      val request =
        LlmRequest(
          model = DummyModel("gemini-flash-latest"),
          config = GenerateContentConfig(tools = listOf(existingTool)),
        )

      val result = BuiltInCodeExecutionTool().processLlmRequest(testToolContext(), request)

      val tools = assertNotNull(result.config.tools)
      assertEquals(2, tools.size)
      assertEquals(existingTool, tools[0])
      assertNotNull(tools[1].codeExecution)
    }

  @Test
  fun processLlmRequest_withExistingCodeExecution_doesNotAddDuplicate(): Unit = runBlocking {
    val request =
      LlmRequest(
        model = DummyModel("gemini-flash-latest"),
        config = GenerateContentConfig(tools = listOf(Tool(codeExecution = ToolCodeExecution()))),
      )

    val result = BuiltInCodeExecutionTool().processLlmRequest(testToolContext(), request)

    val tools = assertNotNull(result.config.tools)
    assertEquals(1, tools.size)
    assertNotNull(tools[0].codeExecution)
  }

  @Test
  fun processLlmRequest_nonGeminiModel_addsCodeExecutionTool(): Unit = runBlocking {
    // The backend, not ADK, decides whether a model supports code execution.
    val request = LlmRequest(model = DummyModel("gpt-4o"))

    val result = BuiltInCodeExecutionTool().processLlmRequest(testToolContext(), request)

    assertNotNull(result.config.tools?.firstOrNull { it.codeExecution != null })
  }

  @Test
  fun processLlmRequest_noModel_addsCodeExecutionTool(): Unit = runBlocking {
    val result = BuiltInCodeExecutionTool().processLlmRequest(testToolContext(), LlmRequest())

    assertNotNull(result.config.tools?.firstOrNull { it.codeExecution != null })
  }

  @Test
  fun generatedImage_isSavedAsArtifactAndReplacedWithText(): Unit = runBlocking {
    val image = Blob(mimeType = "image/png", displayName = "chart.png", data = byteArrayOf(1, 2, 3))
    val artifacts = InMemoryArtifactService()

    val events = runAgent(artifacts, tools = listOf(BuiltInCodeExecutionTool()), image = image)

    // As in ADK Python, the artifact is recorded on its own event, ahead of the model's response.
    assertEquals(2, events.size)
    val (artifactEvent, modelEvent) = events
    assertNull(artifactEvent.content)
    assertEquals(setOf("chart.png"), artifactEvent.actions.artifactDelta.keys)
    assertTrue(modelEvent.actions.artifactDelta.isEmpty())
    val parts = modelEvent.content!!.parts
    assertEquals(
      listOf(
        Part(executableCode = ExecutableCode(code = "plot()")),
        Part(codeExecutionResult = CodeExecutionResult(output = "done")),
        Part(text = "Saved as artifact: chart.png. "),
        Part(text = "Here is the chart."),
      ),
      parts,
    )
    assertEquals(
      Part(inlineData = Blob(mimeType = "image/png", data = byteArrayOf(1, 2, 3))),
      artifacts.loadArtifact(SESSION, "chart.png"),
    )
  }

  @Test
  fun nonImageInlineData_staysInlineAlongsideSavedImage(): Unit = runBlocking {
    val pdf = Blob(mimeType = "application/pdf", displayName = "doc.pdf", data = byteArrayOf(9))
    val image = Blob(mimeType = "image/png", displayName = "chart.png", data = byteArrayOf(1))
    val model =
      DummyModel("gemini-flash-latest") {
        flowOf(
          LlmResponse(content = codeReply(listOf(Part(inlineData = pdf), Part(inlineData = image))))
        )
      }

    val events = run(model, InMemoryArtifactService(), listOf(BuiltInCodeExecutionTool()))

    assertEquals(setOf("chart.png"), events.first().actions.artifactDelta.keys)
    val parts = events.last().content!!.parts
    assertTrue(Part(inlineData = pdf) in parts)
    assertTrue(Part(text = "Saved as artifact: chart.png. ") in parts)
  }

  @Test
  fun generatedImageWithoutName_getsATimestampedFileName(): Unit = runBlocking {
    val artifacts = InMemoryArtifactService()

    val events =
      runAgent(
        artifacts,
        tools = listOf(BuiltInCodeExecutionTool()),
        image = Blob(mimeType = "image/png", data = byteArrayOf(4, 5)),
      )

    val name = events.first().actions.artifactDelta.keys.single()
    assertTrue(Regex("""\d{8}_\d{6}\.png""").matches(name), "unexpected artifact name $name")
    assertTrue(Part(text = "Saved as artifact: $name. ") in events.last().content!!.parts)
    assertNotNull(artifacts.loadArtifact(SESSION, name))
  }

  @Test
  fun formatCompactTimestamp_formatsAsYyyyMmDdHhMmSs() {
    val instant = Instant.parse("2024-12-30T15:04:05Z")

    assertEquals("20241230_150405", formatCompactTimestamp(instant, TimeZone.UTC))
  }

  @Test
  fun generatedImage_keepsThePartsOtherFields(): Unit = runBlocking {
    val image = Blob(mimeType = "image/png", displayName = "chart.png", data = byteArrayOf(1))

    val events =
      runAgent(
        InMemoryArtifactService(),
        tools = listOf(BuiltInCodeExecutionTool()),
        image = image,
        imageSignature = byteArrayOf(7, 7),
      )

    val replaced = events.last().content!!.parts.single { it.text?.startsWith("Saved") == true }
    assertTrue(byteArrayOf(7, 7).contentEquals(replaced.thoughtSignature))
  }

  @Test
  fun generatedImage_whenStreaming_isSavedOnce(): Unit = runBlocking {
    val image = Blob(mimeType = "image/png", displayName = "chart.png", data = byteArrayOf(1, 2, 3))
    val artifacts = InMemoryArtifactService()

    // The partial chunk and the final response both carry the image, as streamed Gemini replies do.
    val events =
      runAgent(artifacts, tools = listOf(BuiltInCodeExecutionTool()), image = image, partial = true)

    assertEquals(listOf(0), artifacts.listVersions(SESSION, "chart.png"))
    assertTrue(events.last().content!!.parts.none { it.inlineData != null })
  }

  @Test
  fun generatedImage_whenStreaming_keepsTheStreamedId(): Unit = runBlocking {
    val image = Blob(mimeType = "image/png", displayName = "chart.png", data = byteArrayOf(1, 2, 3))

    val events =
      runAgent(
        InMemoryArtifactService(),
        tools = listOf(BuiltInCodeExecutionTool()),
        image = image,
        partial = true,
      )

    // The chunk and the complete reply share an id, so a UI replaces the chunk and its raw image.
    assertEquals(3, events.size)
    assertTrue(events.first().partial)
    assertEquals(events.first().id, events.last().id)
  }

  @Test
  fun generatedImage_fromAToolsetTool_isSavedAsArtifact(): Unit = runBlocking {
    val image = Blob(mimeType = "image/png", displayName = "chart.png", data = byteArrayOf(1, 2, 3))
    val toolset =
      object : Toolset {
        override suspend fun getTools(readonlyContext: ReadonlyContext?): List<BaseTool> =
          listOf(BuiltInCodeExecutionTool())
      }

    val events = runAgent(InMemoryArtifactService(), toolsets = listOf(toolset), image = image)

    assertEquals(setOf("chart.png"), events.first().actions.artifactDelta.keys)
  }

  @Test
  fun generatedImage_withoutArtifactService_throwsWithoutTheImageName(): Unit = runBlocking {
    val image = Blob(mimeType = "image/png", displayName = "chart.png", data = byteArrayOf(1))

    val thrown =
      assertFailsWith<IllegalStateException> {
        runAgent(artifacts = null, tools = listOf(BuiltInCodeExecutionTool()), image = image)
      }

    assertFalse("chart.png" in assertNotNull(thrown.message))
  }

  @Test
  fun responseWithoutImage_emitsNoArtifactEvent(): Unit = runBlocking {
    val events =
      runAgent(InMemoryArtifactService(), tools = listOf(BuiltInCodeExecutionTool()), image = null)

    assertTrue(events.single().actions.artifactDelta.isEmpty())
  }

  @Test
  fun generatedImage_withoutCodeExecution_staysInline(): Unit = runBlocking {
    val image = Blob(mimeType = "image/png", displayName = "chart.png", data = byteArrayOf(1, 2, 3))
    val artifacts = InMemoryArtifactService()

    val events = runAgent(artifacts, image = image)

    assertTrue(events.single().content!!.parts.any { it.inlineData == image })
    assertTrue(events.single().actions.artifactDelta.isEmpty())
    assertEquals(emptyList(), artifacts.listArtifactKeys(SESSION))
  }

  @Test
  fun unnamedImages_getNumberedFileNames(): Unit = runBlocking {
    // An empty display name counts as none, as in ADK Python.
    val images =
      listOf(
        Part(inlineData = Blob(mimeType = "image/png", data = byteArrayOf(1))),
        Part(inlineData = Blob(mimeType = "image/png", displayName = "", data = byteArrayOf(2))),
      )
    val model =
      DummyModel("gemini-flash-latest") { flowOf(LlmResponse(content = codeReply(images))) }

    val events = run(model, InMemoryArtifactService(), listOf(BuiltInCodeExecutionTool()))

    val names = events.first().actions.artifactDelta.keys.sorted()
    assertEquals(2, names.size, "unexpected names $names")
    assertTrue(Regex("""\d{8}_\d{6}\.png""").matches(names[0]), "unexpected names $names")
    assertEquals(names[0].removeSuffix(".png") + "_2.png", names[1])
  }

  @Test
  fun mixedNamedPngAndUnnamedJpeg_keepsNameAndStartsCounterAtFirstUnnamed(): Unit = runBlocking {
    val images =
      listOf(
        Part(
          inlineData = Blob(mimeType = "image/png", displayName = "plot.png", data = byteArrayOf(1))
        ),
        Part(inlineData = Blob(mimeType = "image/jpeg", data = byteArrayOf(2))),
      )
    val model =
      DummyModel("gemini-flash-latest") { flowOf(LlmResponse(content = codeReply(images))) }

    val events = run(model, InMemoryArtifactService(), listOf(BuiltInCodeExecutionTool()))

    val names = events.first().actions.artifactDelta.keys
    assertEquals(2, names.size, "unexpected names $names")
    assertTrue("plot.png" in names)
    val unnamed = names.single { it != "plot.png" }
    assertTrue(Regex("""\d{8}_\d{6}\.jpeg""").matches(unnamed), "unexpected name $unnamed")
  }

  @Test
  fun generatedImage_withPathOrUserPrefix_stripsDirectoryAndUserScope(): Unit = runBlocking {
    val images =
      listOf(
        Part(
          inlineData =
            Blob(mimeType = "image/png", displayName = "../plots/chart.png", data = byteArrayOf(1))
        ),
        Part(
          inlineData =
            Blob(mimeType = "image/png", displayName = "user:scoped.png", data = byteArrayOf(2))
        ),
        Part(
          inlineData =
            Blob(mimeType = "image/png", displayName = "../USER:nested.png", data = byteArrayOf(3))
        ),
      )
    val artifacts = InMemoryArtifactService()
    val model =
      DummyModel("gemini-flash-latest") { flowOf(LlmResponse(content = codeReply(images))) }

    val events = run(model, artifacts, listOf(BuiltInCodeExecutionTool()))

    assertEquals(
      setOf("chart.png", "scoped.png", "nested.png"),
      events.first().actions.artifactDelta.keys,
    )
    assertTrue(artifacts.listArtifactKeys(SESSION).none { it.startsWith("user:") })
  }

  @Test
  fun generatedImage_withUnusableOrReservedDisplayName_fallsBackToTimestamp(): Unit = runBlocking {
    val images =
      listOf(
          "..",
          "user:",
          "../metadata.json",
          "versions",
          "C:chart.png",
          "plot_12:30.png",
          "a?.png",
        )
        .map { name ->
          Part(inlineData = Blob(mimeType = "image/png", displayName = name, data = byteArrayOf(1)))
        }
    val model =
      DummyModel("gemini-flash-latest") { flowOf(LlmResponse(content = codeReply(images))) }

    val events = run(model, InMemoryArtifactService(), listOf(BuiltInCodeExecutionTool()))

    val names = events.first().actions.artifactDelta.keys.sorted()
    assertEquals(7, names.size, "unexpected names $names")
    val base = names[0].removeSuffix(".png")
    assertTrue(Regex("""\d{8}_\d{6}""").matches(base), "unexpected names $names")
    assertEquals(
      listOf(
        "$base.png",
        "${base}_2.png",
        "${base}_3.png",
        "${base}_4.png",
        "${base}_5.png",
        "${base}_6.png",
        "${base}_7.png",
      ),
      names,
    )
  }

  @Test
  fun codeOnlyReply_isInTheNextRequest(): Unit = runBlocking {
    // A trailing code result makes the agent call the model again, which must still see both parts.
    val codeOnly =
      Content(
        role = Role.MODEL,
        parts =
          listOf(
            Part(executableCode = ExecutableCode(code = "print(2 + 2)")),
            Part(codeExecutionResult = CodeExecutionResult(output = "4")),
          ),
      )
    val answer = Content(role = Role.MODEL, parts = listOf(Part(text = "2 + 2 is 4.")))
    val requests = mutableListOf<LlmRequest>()
    val model =
      DummyModel("gemini-flash-latest") { request ->
        requests += request
        flowOf(LlmResponse(content = if (requests.size == 1) codeOnly else answer))
      }

    val unused = run(model, InMemoryArtifactService(), listOf(BuiltInCodeExecutionTool()))

    assertEquals(2, requests.size)
    val parts = requests[1].contents.flatMap { it.parts }
    assertTrue(parts.any { it.executableCode?.code == "print(2 + 2)" }, "the code is missing")
    assertTrue(parts.any { it.codeExecutionResult?.output == "4" }, "the result is missing")
  }

  @Test
  fun generatedImage_modelEventIsNotOlderThanTheArtifactEvent(): Unit = runBlocking {
    // The model event is created before the slow call but must not predate the artifact event.
    val image = Blob(mimeType = "image/png", displayName = "chart.png", data = byteArrayOf(1))
    val model =
      DummyModel("gemini-flash-latest") {
        flow {
          delay(50.milliseconds)
          emit(LlmResponse(content = codeReply(listOf(Part(inlineData = image)))))
        }
      }

    val events = run(model, InMemoryArtifactService(), listOf(BuiltInCodeExecutionTool()))

    assertEquals(2, events.size)
    val (artifactEvent, modelEvent) = events
    assertTrue(
      artifactEvent.timestamp <= modelEvent.timestamp,
      "the model event is older than the artifact event",
    )
  }

  /**
   * Runs an agent whose model replies with code, its result, [image] if any and a text part, first
   * as a partial chunk if [partial], and returns the agent's events.
   */
  private suspend fun runAgent(
    artifacts: InMemoryArtifactService?,
    tools: List<BaseTool> = emptyList(),
    toolsets: List<Toolset> = emptyList(),
    image: Blob?,
    imageSignature: ByteArray? = null,
    partial: Boolean = false,
  ): List<Event> {
    val reply =
      codeReply(
        listOfNotNull(image?.let { Part(inlineData = it, thoughtSignature = imageSignature) })
      )
    val responses =
      listOfNotNull(
        LlmResponse(content = reply, partial = true).takeIf { partial },
        LlmResponse(content = reply),
      )
    return run(DummyModel("gemini-flash-latest") { responses.asFlow() }, artifacts, tools, toolsets)
  }

  /** A model reply with code, its result, [imageParts] and a text part. */
  private fun codeReply(imageParts: List<Part>): Content =
    Content(
      role = Role.MODEL,
      parts =
        listOf(
          Part(executableCode = ExecutableCode(code = "plot()")),
          Part(codeExecutionResult = CodeExecutionResult(output = "done")),
        ) + imageParts + Part(text = "Here is the chart."),
    )

  /** Runs an agent on [model] for one user message and returns the events the agent authored. */
  private suspend fun run(
    model: DummyModel,
    artifacts: InMemoryArtifactService?,
    tools: List<BaseTool>,
    toolsets: List<Toolset> = emptyList(),
  ): List<Event> {
    val agent = LlmAgent(name = "coder", model = model, tools = tools, toolsets = toolsets)
    val sessions = InMemorySessionService()
    val unused = sessions.createSession(key = SESSION, state = null)
    return InMemoryRunner(
        agent = agent,
        appName = SESSION.appName,
        sessionService = sessions,
        artifactService = artifacts,
      )
      .runAsync(userId = SESSION.userId, sessionId = SESSION.id!!, newMessage = userMessage("plot"))
      .toList()
      .filter { it.author == "coder" }
  }

  companion object {
    private val SESSION = SessionKey("app", "user", "session")
  }
}
