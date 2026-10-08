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
import com.google.adk.kt.types.GenerateContentConfig
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.Tool
import com.google.adk.kt.types.ToolCodeExecution
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

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
    assertTrue(parts.none { it.inlineData != null }, "the image stayed in the event")
    assertTrue(Part(text = "Saved as artifact: chart.png. ") in parts)
    assertEquals(
      Part(inlineData = Blob(mimeType = "image/png", data = byteArrayOf(1, 2, 3))),
      artifacts.loadArtifact(SESSION, "chart.png"),
    )
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
    assertTrue(name.endsWith("_1.png"), "unexpected artifact name $name")
    assertTrue(Part(text = "Saved as artifact: $name. ") in events.last().content!!.parts)
    assertNotNull(artifacts.loadArtifact(SESSION, name))
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

    assertEquals("Saving code execution images needs an artifact service.", thrown.message)
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
    assertTrue(
      names[0].endsWith("_1.png") && names[1].endsWith("_2.png"),
      "unexpected names $names",
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
