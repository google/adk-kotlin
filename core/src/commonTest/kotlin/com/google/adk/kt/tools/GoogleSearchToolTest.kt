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

import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.collections.concurrentMutableMapOf
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.Model
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.sessions.State
import com.google.adk.kt.testing.DummyAgent
import com.google.adk.kt.types.GenerateContentConfig
import com.google.adk.kt.types.GoogleSearch
import com.google.adk.kt.types.Tool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest

class GoogleSearchToolTest {
  private fun getTestToolContext(): ToolContext {
    val invocationContext =
      InvocationContext(
        session =
          Session(
            key = SessionKey(appName = "app-name", userId = "user-id", id = "session-id"),
            state = State(concurrentMutableMapOf()),
          ),
        runConfig = null,
        agent = DummyAgent("test-agent"),
        artifactService = null,
      )
    return ToolContext(invocationContext = invocationContext)
  }

  // Helper to create mock model
  private class MockModel(override val name: String) : Model {
    override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> =
      emptyFlow()
  }

  @Test
  fun declaration_returnsNull() {
    val tool = GoogleSearchTool()
    assertNull(tool.declaration())
  }

  @Test
  fun processLlmRequest_gemini2_addsGoogleSearch() = runTest {
    val tool = GoogleSearchTool()
    val context = getTestToolContext()
    var request = LlmRequest(model = MockModel("gemini-2.0-flash"))

    request = tool.processLlmRequest(context, request)

    val tools = request.config.tools
    assertNotNull(tools)
    assertEquals(1, tools.size)
    assertNotNull(tools[0].googleSearch)
  }

  @Test
  fun processLlmRequest_unsupportedModel_throwsException() = runTest {
    val tool = GoogleSearchTool()
    val context = getTestToolContext()
    val request = LlmRequest(model = MockModel("gpt-4"))

    assertFailsWith<IllegalArgumentException> { tool.processLlmRequest(context, request) }
  }

  @Test
  fun processLlmRequest_overrideModel_usesOverride() = runTest {
    val tool = GoogleSearchTool(model = "gemini-2.0-flash")
    val context = getTestToolContext()
    var request = LlmRequest(model = MockModel("gemini-2.0-pro"))

    request = tool.processLlmRequest(context, request)

    val tools = request.config.tools
    assertNotNull(tools)
    assertEquals(1, tools.size)
    assertNotNull(tools[0].googleSearch)
  }

  @Test
  fun processLlmRequest_noModel_throwsException() = runTest {
    val tool = GoogleSearchTool()
    val context = getTestToolContext()
    val request = LlmRequest()

    assertFailsWith<IllegalArgumentException> { tool.processLlmRequest(context, request) }
  }

  @Test
  fun processLlmRequest_gemini2_withExistingGoogleSearch_doesNotAddTool() = runTest {
    val tool = GoogleSearchTool()
    val context = getTestToolContext()
    val existingTool = Tool(googleSearch = GoogleSearch())
    var request =
      LlmRequest(
        model = MockModel("gemini-2.0-flash"),
        config = GenerateContentConfig(tools = listOf(existingTool)),
      )

    request = tool.processLlmRequest(context, request)

    val tools = request.config.tools
    assertNotNull(tools)
    assertEquals(1, tools.size)
    assertNotNull(tools[0].googleSearch)
  }
}
