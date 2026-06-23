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

import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.testToolContext
import com.google.adk.kt.types.GenerateContentConfig
import com.google.adk.kt.types.GoogleSearch
import com.google.adk.kt.types.Tool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class GoogleSearchToolTest {

  @Test
  fun declaration_returnsNull() {
    val tool = GoogleSearchTool()
    assertNull(tool.declaration())
  }

  @Test
  fun processLlmRequest_gemini2_addsGoogleSearch() = runTest {
    val tool = GoogleSearchTool()
    val context = testToolContext()
    var request = LlmRequest(model = DummyModel("gemini-2.0-flash"))

    request = tool.processLlmRequest(context, request)

    val tools = request.config.tools
    assertNotNull(tools)
    assertEquals(1, tools.size)
    assertNotNull(tools[0].googleSearch)
  }

  @Test
  fun processLlmRequest_nonGeminiModel_addsGoogleSearch() = runTest {
    val tool = GoogleSearchTool()
    val context = testToolContext()
    var request = LlmRequest(model = DummyModel("gpt-4"))

    request = tool.processLlmRequest(context, request)

    val tools = request.config.tools
    assertNotNull(tools)
    assertEquals(1, tools.size)
    assertNotNull(tools[0].googleSearch)
  }

  @Test
  fun processLlmRequest_customModelName_addsGoogleSearch() = runTest {
    val tool = GoogleSearchTool()
    val context = testToolContext()
    // Any model name is accepted; the tool no longer inspects it.
    var request = LlmRequest(model = DummyModel("custom-model"))

    request = tool.processLlmRequest(context, request)

    val tools = request.config.tools
    assertNotNull(tools)
    assertEquals(1, tools.size)
    assertNotNull(tools[0].googleSearch)
  }

  @Test
  fun processLlmRequest_existingConfigFields_preserved() = runTest {
    val tool = GoogleSearchTool()
    val context = testToolContext()
    val request =
      LlmRequest(
        config = GenerateContentConfig(temperature = 0.5f),
        model = DummyModel("gemini-2.0-flash"),
      )

    val result = tool.processLlmRequest(context, request)

    assertEquals(0.5f, result.config.temperature)
    assertNotNull(result.config.tools?.firstOrNull { it.googleSearch != null })
  }

  @Test
  fun processLlmRequest_noModel_addsGoogleSearch() = runTest {
    val tool = GoogleSearchTool()
    val context = testToolContext()
    var request = LlmRequest()

    request = tool.processLlmRequest(context, request)

    val tools = request.config.tools
    assertNotNull(tools)
    assertEquals(1, tools.size)
    assertNotNull(tools[0].googleSearch)
  }

  @Test
  fun processLlmRequest_gemini2_withExistingGoogleSearch_doesNotAddTool() = runTest {
    val tool = GoogleSearchTool()
    val context = testToolContext()
    val existingTool = Tool(googleSearch = GoogleSearch())
    var request =
      LlmRequest(
        model = DummyModel("gemini-2.0-flash"),
        config = GenerateContentConfig(tools = listOf(existingTool)),
      )

    request = tool.processLlmRequest(context, request)

    val tools = request.config.tools
    assertNotNull(tools)
    assertEquals(1, tools.size)
    assertNotNull(tools[0].googleSearch)
  }

  @Suppress("DEPRECATION")
  @Test
  fun processLlmRequest_deprecatedModelParamSet_isIgnored() = runTest {
    val tool = GoogleSearchTool(model = "gpt-4")
    val context = testToolContext()
    var request = LlmRequest(model = DummyModel("gemini-2.0-flash"))

    request = tool.processLlmRequest(context, request)

    val tools = request.config.tools
    assertNotNull(tools)
    assertEquals(1, tools.size)
    assertNotNull(tools[0].googleSearch)
  }
}
