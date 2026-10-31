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
import com.google.adk.kt.artifacts.InMemoryArtifactService
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.testing.DummyArtifactService
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.DummyTool
import com.google.adk.kt.testing.modelFunctionCall
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.testInvocationContext
import com.google.adk.kt.testing.testToolContext
import com.google.adk.kt.testing.userFunctionResponse
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.Type
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest

class LoadArtifactsToolTest {

  private fun mockArtifactService(
    keysToReturn: List<String> = emptyList(),
    loadToReturn: Map<String, Part> = emptyMap(),
  ) =
    DummyArtifactService(
      onListArtifactKeys = { keysToReturn },
      onLoadArtifact = { _, filename, _ -> loadToReturn[filename] },
    )

  @Test
  fun declaration_returnsSchema() {
    val tool = LoadArtifactsTool()

    val declaration = tool.declaration()

    assertNotNull(declaration)
    assertEquals("load_artifacts", declaration.name)
    val parameters = declaration.parameters
    assertNotNull(parameters)
    assertEquals(Type.OBJECT, parameters.type)
    val properties = parameters.properties
    assertNotNull(properties)
    val artifactNames = properties["artifact_names"]
    assertNotNull(artifactNames)
    assertEquals(Type.ARRAY, artifactNames.type)
    assertEquals(Type.STRING, artifactNames.items?.type)
  }

  @Test
  fun run_returnsExpectedMap() = runTest {
    val tool = LoadArtifactsTool()
    val context = testToolContext()
    val args = mapOf("artifact_names" to listOf("file1.txt", "file2.jpg"))
    val result = tool.run(context, args) as Map<*, *>

    assertEquals(listOf("file1.txt", "file2.jpg"), result["artifact_names"])
    assertTrue((result["status"] as String).contains("temporarily inserted and removed"))
  }

  @Test
  fun processLlmRequest_emptyArtifacts_noOp() = runTest {
    val tool = LoadArtifactsTool()
    val context =
      testToolContext(
        testInvocationContext(artifactService = mockArtifactService(keysToReturn = emptyList())),
      )
    var request = LlmRequest()

    request = tool.processLlmRequest(context, request)

    assertNotNull(request)
    assertNotNull(request.contents)
    assertTrue(request.contents.isEmpty())
    assertNull(request.config.systemInstruction)
  }

  @Test
  fun processLlmRequest_appendsInstructions() = runTest {
    val tool = LoadArtifactsTool()
    val context =
      testToolContext(
        testInvocationContext(
          artifactService = mockArtifactService(keysToReturn = listOf("file1.txt")),
        ),
      )
    var request = LlmRequest()

    request = tool.processLlmRequest(context, request)

    val systemInstruction = request.config.systemInstruction
    assertNotNull(systemInstruction)
    assertEquals(1, systemInstruction.parts.size)
    val text = systemInstruction.parts[0].text
    assertNotNull(text)
    assertTrue(text.contains("\"file1.txt\""))
    assertTrue(text.contains("load_artifacts"))
  }

  @Test
  fun processLlmRequest_loadsRequestedArtifacts() = runTest {
    val tool = LoadArtifactsTool()
    val expectedPart = Part(text = "file 1 content")
    val context =
      testToolContext(
        testInvocationContext(
          artifactService =
            mockArtifactService(
              keysToReturn = listOf("file1.txt"),
              loadToReturn = mapOf("file1.txt" to expectedPart),
            ),
        ),
      )

    var request = LlmRequest()
    request = request.appendContent(modelFunctionCall(name = "some_other_function"))
    request =
      request.appendContent(
        userFunctionResponse(
          name = "load_artifacts",
          id = null,
          response = mapOf("artifact_names" to listOf("file1.txt")),
        ),
      )

    request = tool.processLlmRequest(context, request)

    assertEquals(3, request.contents.size)
    val addedContent = request.contents[2]
    assertEquals(Role.USER, addedContent.role)
    assertEquals(2, addedContent.parts.size)
    assertTrue(addedContent.parts[0].text?.contains("Artifact file1.txt is:") == true)
    assertEquals(expectedPart.text, addedContent.parts[1].text)
  }

  @Test
  fun run_withoutArtifactNames_returnsEmptyList() = runTest {
    val tool = LoadArtifactsTool()
    val context = testToolContext()
    val args = emptyMap<String, Any>()
    val result = tool.run(context, args) as Map<*, *>

    val names = result["artifact_names"] as List<*>
    assertTrue(names.isEmpty())
  }

  @Test
  fun processLlmRequest_artifactsInContext_withOtherFunctionCall_doesNotLoad() = runTest {
    val tool = LoadArtifactsTool()
    val context =
      testToolContext(
        testInvocationContext(
          artifactService = mockArtifactService(keysToReturn = listOf("file1.txt")),
        ),
      )
    var request = LlmRequest()

    request = request.appendContent(modelFunctionCall(name = "other_function"))
    request =
      request.appendContent(
        userFunctionResponse(name = "other_function", id = null, response = mapOf("foo" to "bar")),
      )

    request = tool.processLlmRequest(context, request)

    assertNotNull(request.config.systemInstruction)
    assertEquals(2, request.contents.size)
  }

  @Test
  fun processLlmRequest_loadsRequestedArtifacts_withUserPrefix() = runTest {
    val tool = LoadArtifactsTool()
    val expectedPart = Part(text = "user file content")
    val context =
      testToolContext(
        testInvocationContext(
          artifactService =
            mockArtifactService(
              keysToReturn = listOf("user:file1.txt"),
              loadToReturn = mapOf("user:file1.txt" to expectedPart),
            ),
        ),
      )
    var request = LlmRequest()
    request =
      request.appendContent(
        modelFunctionCall(
          name = "load_artifacts",
          args = mapOf("artifact_names" to listOf("file1.txt")),
        ),
      )
    request =
      request.appendContent(
        userFunctionResponse(
          name = "load_artifacts",
          id = null,
          response = mapOf("artifact_names" to listOf("file1.txt")),
        ),
      )

    request = tool.processLlmRequest(context, request)

    assertEquals(3, request.contents.size)
    val addedContent = request.contents[2]
    assertEquals(Role.USER, addedContent.role)
    assertTrue(addedContent.parts[0].text?.contains("Artifact file1.txt is:") == true)
    assertEquals(expectedPart.text, addedContent.parts[1].text)
  }

  @Test
  fun processLlmRequest_supportedMimeType_keptAsBlob() = runTest {
    val tool = LoadArtifactsTool()
    val pdfBytes = "%PDF-1.4".encodeToByteArray()
    val artifactName = "test.pdf"
    val artifactPart = Part(inlineData = Blob(mimeType = "application/pdf", data = pdfBytes))
    val context =
      testToolContext(
        testInvocationContext(
          artifactService =
            mockArtifactService(
              keysToReturn = listOf(artifactName),
              loadToReturn = mapOf(artifactName to artifactPart),
            ),
        ),
      )
    var request = LlmRequest()
    request =
      request.appendContent(
        modelFunctionCall(
          name = "load_artifacts",
          args = mapOf("artifact_names" to listOf(artifactName)),
        ),
      )
    request =
      request.appendContent(
        userFunctionResponse(
          name = "load_artifacts",
          id = null,
          response = mapOf("artifact_names" to listOf(artifactName)),
        ),
      )

    request = tool.processLlmRequest(context, request)

    assertEquals(3, request.contents.size)
    val loadedPart = request.contents[2].parts[1]
    assertEquals(null, loadedPart.text)
    val inlineData = loadedPart.inlineData
    assertNotNull(inlineData)
    assertEquals("application/pdf", inlineData.mimeType)
  }

  @Test
  fun processLlmRequest_appendsInstructions_multipleArtifacts() = runTest {
    val tool = LoadArtifactsTool()
    val context =
      testToolContext(
        testInvocationContext(
          artifactService = mockArtifactService(keysToReturn = listOf("file1.txt", "file2.png")),
        ),
      )
    var request = LlmRequest()

    request = tool.processLlmRequest(context, request)

    val systemInstruction = request.config.systemInstruction
    assertNotNull(systemInstruction)
    assertEquals(1, systemInstruction.parts.size)
    val text = systemInstruction.parts[0].text
    assertNotNull(text)
    assertTrue(text.contains("\"file1.txt\""))
    assertTrue(text.contains("\"file2.png\""))
    assertTrue(text.contains("load_artifacts"))
  }

  @Test
  fun processLlmRequest_noFunctionResponse_noOp() = runTest {
    val tool = LoadArtifactsTool()
    val context =
      testToolContext(
        testInvocationContext(
          artifactService = mockArtifactService(keysToReturn = listOf("file1.txt")),
        ),
      )
    var request = LlmRequest()
    request = request.appendContent(userMessage("some text"))

    request = tool.processLlmRequest(context, request)

    assertNotNull(request.config.systemInstruction) // Instructions are still added
    assertEquals(1, request.contents.size) // No new content added
  }

  @Test
  fun processLlmRequest_emptyPartsInLastContent_noOp() = runTest {
    val tool = LoadArtifactsTool()
    val context =
      testToolContext(
        testInvocationContext(
          artifactService = mockArtifactService(keysToReturn = listOf("file1.txt")),
        ),
      )
    var request = LlmRequest()
    request = request.appendContent(Content(role = Role.USER, parts = emptyList()))

    request = tool.processLlmRequest(context, request)

    assertNotNull(request.config.systemInstruction) // Instructions are still added
    assertEquals(1, request.contents.size) // No new content added
  }

  @Test
  fun processLlmRequest_notLoadArtifactsFunctionCall_noOp() = runTest {
    val tool = LoadArtifactsTool()
    val context =
      testToolContext(
        testInvocationContext(
          artifactService = mockArtifactService(keysToReturn = listOf("file1.txt")),
        ),
      )
    var request = LlmRequest()
    request =
      request.appendContent(
        userFunctionResponse(name = "other_function", id = null, response = mapOf("foo" to "bar")),
      )

    request = tool.processLlmRequest(context, request)

    assertNotNull(request.config.systemInstruction) // Instructions are still added
    assertEquals(1, request.contents.size) // No new content added
  }

  @Test
  fun processLlmRequest_nonExistentArtifact_doesNotLoad() = runTest {
    val tool = LoadArtifactsTool()
    val context =
      testToolContext(
        testInvocationContext(
          artifactService =
            mockArtifactService(keysToReturn = listOf("file1.txt"), loadToReturn = emptyMap()),
        ),
      )

    var request = LlmRequest()
    request = request.appendContent(modelFunctionCall(name = "some_other_function"))
    request =
      request.appendContent(
        userFunctionResponse(
          name = "load_artifacts",
          id = null,
          response = mapOf("artifact_names" to listOf("file1.txt")),
        ),
      )

    request = tool.processLlmRequest(context, request)

    // Original contents (model func call, user func response). No new content should be added.
    assertEquals(2, request.contents.size)
  }

  @Test
  fun processLlmRequest_multipleLoadResponses_loadsExistingArtifactsInResponseOrder() =
    runBlocking {
      val firstArtifact = Part(text = "First artifact contents")
      val secondArtifact = Part(text = "Second artifact contents")
      val context =
        testToolContext(
          testInvocationContext(
            artifactService =
              mockArtifactService(
                keysToReturn = listOf("first.txt", "second.txt"),
                loadToReturn = mapOf("first.txt" to firstArtifact, "second.txt" to secondArtifact),
              ),
          ),
        )
      val responses =
        Content(
          role = Role.USER,
          parts =
            listOf(
              Part(text = "Tool responses follow"),
              Part(
                functionResponse =
                  FunctionResponse(
                    "load_artifacts",
                    response = mapOf("artifact_names" to listOf("missing.txt", "first.txt")),
                  ),
              ),
              Part(
                functionResponse = FunctionResponse("other_tool", response = mapOf("ok" to true)),
              ),
              Part(text = "More tool responses"),
              Part(
                functionResponse =
                  FunctionResponse(
                    "load_artifacts",
                    response = mapOf("artifact_names" to listOf("second.txt")),
                  ),
              ),
            ),
        )
      val request = LlmRequest(contents = listOf(responses))

      val result = LoadArtifactsTool().processLlmRequest(context, request)

      assertEquals(
        listOf(
          responses,
          Content(
            role = Role.USER,
            parts = listOf(Part(text = "Artifact first.txt is:"), firstArtifact),
          ),
          Content(
            role = Role.USER,
            parts = listOf(Part(text = "Artifact second.txt is:"), secondArtifact),
          ),
        ),
        result.contents,
      )
      assertEquals(listOf(responses), request.contents)
    }

  @Test
  fun run_loadArtifactsAfterAnotherTool_includesRequestedArtifactInNextModelRequest() =
    runBlocking {
      assertArtifactReachesAnsweringModel(loadArtifactsFirst = false)
    }

  @Test
  fun run_loadArtifactsBeforeAnotherTool_includesRequestedArtifactInNextModelRequest() =
    runBlocking {
      assertArtifactReachesAnsweringModel(loadArtifactsFirst = true)
    }

  private suspend fun assertArtifactReachesAnsweringModel(loadArtifactsFirst: Boolean) {
    val artifacts = InMemoryArtifactService()
    val filename = "invoice.txt"
    val payload = "Invoice total is 1234.56 USD"
    artifacts.saveArtifact(
      SessionKey("artifact_app", "user1", "session1"),
      filename,
      Part(text = payload),
    )
    val currencyCall = Part(functionCall = FunctionCall("get_currency", id = "currency-1"))
    val artifactCall =
      Part(
        functionCall =
          FunctionCall(
            "load_artifacts",
            args = mapOf("artifact_names" to listOf(filename)),
            id = "artifact-1",
          ),
      )
    val orderedCalls =
      if (loadArtifactsFirst) listOf(artifactCall, currencyCall)
      else listOf(currencyCall, artifactCall)
    var calls = 0
    var answerRequest: LlmRequest? = null
    val model =
      DummyModel("artifact-model") { request ->
        calls++
        if (calls == 1) {
          flowOf(LlmResponse(content = Content(role = Role.MODEL, parts = orderedCalls)))
        } else {
          answerRequest = request
          flowOf(LlmResponse(content = modelMessage("Finished")))
        }
      }
    val agent =
      LlmAgent(
        name = "invoice_agent",
        model = model,
        tools =
          listOf(
            DummyTool(name = "get_currency", declares = true) { _, _ ->
              mapOf("currency" to "USD")
            },
            LoadArtifactsTool(),
          ),
      )
    val runner =
      InMemoryRunner(agent = agent, appName = "artifact_app", artifactService = artifacts)
    try {
      val events =
        runner.runAsync("user1", "session1", newMessage = userMessage("Read the invoice")).toList()
      val responses = events.flatMap { it.functionResponses() }
      assertEquals(orderedCalls.map { it.functionCall!!.name }, responses.map { it.name })
      val artifactResponse = responses.single { it.name == "load_artifacts" }
      assertEquals(listOf(filename), artifactResponse.response["artifact_names"])
      val nextRequest = assertNotNull(answerRequest)
      assertEquals(2, calls)
      assertTrue(
        nextRequest.contents.flatMap { it.parts }.any { it.text == payload },
        "A successful load_artifacts response must supply the file to the answering model; " +
          "loadArtifactsFirst=$loadArtifactsFirst",
      )
    } finally {
      runner.close()
    }
  }
}
