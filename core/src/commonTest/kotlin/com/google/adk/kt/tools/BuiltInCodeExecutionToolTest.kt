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
import kotlinx.coroutines.flow.asFlow
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
  fun processLlmRequest_acceptsGeminiModelNameForms(): Unit = runBlocking {
    val names =
      listOf(
        "gemini-3.1-flash-lite",
        "models/gemini-2.5-pro",
        "projects/p/locations/global/publishers/google/models/gemini-2.5-flash",
        "apigee/gemini-2.5-flash",
        "openrouter/google/gemini-2.5-pro",
      )
    for (name in names) {
      val result =
        BuiltInCodeExecutionTool()
          .processLlmRequest(testToolContext(), LlmRequest(model = DummyModel(name)))

      assertNotNull(result.config.tools?.firstOrNull { it.codeExecution != null }, name)
    }
  }

  @Test
  fun processLlmRequest_nonGeminiModel_throws(): Unit = runBlocking {
    // Other models do not run code server-side, so the tool must fail rather than be dropped.
    val names =
      listOf(
        "gpt-4o",
        "gemma-3n-e4b",
        "projects/p/models/gemini-2.5-flash",
        "my-proxy",
        "models/tunedModels/gemini-x",
        "apigee/a/b/c/gemini-2.5",
        "projects/p/locations/l/publishers/g/models/foo/gemini-2.5",
      )
    for (name in names) {
      val thrown =
        assertFailsWith<IllegalArgumentException>(name) {
          BuiltInCodeExecutionTool()
            .processLlmRequest(testToolContext(), LlmRequest(model = DummyModel(name)))
        }
      assertEquals("Gemini code execution tool is not supported for model $name.", thrown.message)
    }
  }

  @Test
  fun processLlmRequest_noModel_throws(): Unit = runBlocking {
    val unused =
      assertFailsWith<IllegalArgumentException> {
        BuiltInCodeExecutionTool().processLlmRequest(testToolContext(), LlmRequest())
      }
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
      Content(
        role = Role.MODEL,
        parts =
          listOfNotNull(
            Part(executableCode = ExecutableCode(code = "plot()")),
            Part(codeExecutionResult = CodeExecutionResult(output = "done")),
            image?.let { Part(inlineData = it, thoughtSignature = imageSignature) },
            Part(text = "Here is the chart."),
          ),
      )
    val responses =
      listOfNotNull(
        LlmResponse(content = reply, partial = true).takeIf { partial },
        LlmResponse(content = reply),
      )
    val agent =
      LlmAgent(
        name = "coder",
        model = DummyModel("gemini-flash-latest") { responses.asFlow() },
        tools = tools,
        toolsets = toolsets,
      )
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
