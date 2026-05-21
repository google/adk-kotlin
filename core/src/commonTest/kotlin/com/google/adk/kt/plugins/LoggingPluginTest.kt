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

import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.agents.toCallbackContext
import com.google.adk.kt.callbacks.CallbackChoice
import com.google.adk.kt.events.Event
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.plugins.LoggingPlugin.Companion.MAX_ARGS_LENGTH
import com.google.adk.kt.plugins.LoggingPlugin.Companion.MAX_CONTENT_LENGTH
import com.google.adk.kt.testing.DummyAgent
import com.google.adk.kt.testing.DummyTool
import com.google.adk.kt.testing.testSession
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class LoggingPluginTest {

  private val loggingPlugin = LoggingPlugin()

  private val mockAgent = DummyAgent("test_agent")

  private val mockTool = DummyTool("test_tool", "Test tool")

  private val session = testSession()
  private val invocationContext =
    InvocationContext(session = session, runConfig = null, agent = mockAgent)
  private val callbackContext = invocationContext.toCallbackContext()
  private val toolContext = ToolContext(invocationContext)

  @Test
  fun onUserMessageCallback_returnsContinue() = runTest {
    val content = Content()
    val result = loggingPlugin.onUserMessage(invocationContext, content)
    assertEquals(content, result)
  }

  @Test
  fun beforeRunCallback_returnsContinue() = runTest {
    val result = loggingPlugin.beforeRun(invocationContext)
    assertEquals(CallbackChoice.Continue(Unit), result)
  }

  @Test
  fun onEventCallback_returnsContinue() = runTest {
    val event = Event(author = "author")
    val result = loggingPlugin.onEvent(invocationContext, event)
    assertEquals(event, result)
  }

  @Test
  fun afterRunCallback_runsWithoutError() = runTest {
    loggingPlugin.afterRun(invocationContext)
    // Runs without error
  }

  @Test
  fun beforeAgentCallback_returnsContinue() = runTest {
    val result = loggingPlugin.beforeAgent(callbackContext)
    assertEquals(CallbackChoice.Continue(EventActions()), result)
  }

  @Test
  fun afterAgentCallback_returnsContinue() = runTest {
    val result = loggingPlugin.afterAgent(callbackContext)
    assertEquals(CallbackChoice.Continue(Unit), result)
  }

  @Test
  fun beforeModelCallback_returnsContinue() = runTest {
    val request = LlmRequest()
    val result = loggingPlugin.beforeModel(callbackContext, request)
    assertEquals(CallbackChoice.Continue(request), result)
  }

  @Test
  fun afterModelCallback_returnsContinue() = runTest {
    val response = LlmResponse()
    val result = loggingPlugin.afterModel(callbackContext, response)
    assertEquals(response, result)
  }

  @Test
  fun onModelErrorCallback_returnsPropagate() = runTest {
    val result = loggingPlugin.onModelError(callbackContext, LlmRequest(), Exception("Test Error"))
    assertEquals(CallbackChoice.Continue(Unit), result)
  }

  @Test
  fun beforeToolCallback_returnsContinue() = runTest {
    val args = emptyMap<String, Any>()
    val result = loggingPlugin.beforeTool(toolContext, mockTool, args)
    assertEquals(CallbackChoice.Continue(args), result)
  }

  @Test
  fun afterToolCallback_returnsContinue() = runTest {
    val args = emptyMap<String, Any>()
    val toolResult = emptyMap<String, Any>()
    val result = loggingPlugin.afterTool(toolContext, mockTool, args, toolResult)
    assertEquals(toolResult, result)
  }

  @Test
  fun onToolErrorCallback_returnsContinue() = runTest {
    val result =
      loggingPlugin.onToolError(toolContext, mockTool, emptyMap(), Exception("Test Error"))
    assertEquals(CallbackChoice.Continue(Unit), result)
  }

  @Test
  fun formatArgs_nullOrEmpty_returnsEmptyBraces() = runTest {
    assertEquals("{}", loggingPlugin.formatArgs(null))
    assertEquals("{}", loggingPlugin.formatArgs(emptyMap()))
  }

  @Test
  fun formatArgs_atLimit_returnsFullString() = runTest {
    val mapFormatOverhead = 4 // "{a=}"
    val atLimit = mapOf("a" to "0".repeat(MAX_ARGS_LENGTH - mapFormatOverhead))
    assertEquals(atLimit.toString(), loggingPlugin.formatArgs(atLimit))
  }

  @Test
  fun formatArgs_aboveLimit_returnsTruncatedString() = runTest {
    val mapFormatOverhead = 4 // "{a=}"
    val aboveLimit = mapOf("a" to "0".repeat(MAX_ARGS_LENGTH - mapFormatOverhead + 1))
    val expected = aboveLimit.toString().substring(0, MAX_ARGS_LENGTH - 4) + "...}"
    assertEquals(expected, loggingPlugin.formatArgs(aboveLimit))
  }

  @Test
  fun formatContent_nullOrEmpty_returnsNone() = runTest {
    assertEquals("None", loggingPlugin.formatContent(null))
    assertEquals("None", loggingPlugin.formatContent(Content()))
  }

  @Test
  fun formatContent_atLimit_returnsFullString() = runTest {
    val atLimitText = "0".repeat(MAX_CONTENT_LENGTH)
    val atLimitContent = Content(parts = listOf(Part(text = atLimitText)))
    assertEquals("text: '$atLimitText'", loggingPlugin.formatContent(atLimitContent))
  }

  @Test
  fun formatContent_aboveLimit_returnsTruncatedString() = runTest {
    val aboveLimitText = "0".repeat(MAX_CONTENT_LENGTH + 1)
    val aboveLimitContent = Content(parts = listOf(Part(text = aboveLimitText)))
    val expectedContent = "text: '" + aboveLimitText.substring(0, MAX_CONTENT_LENGTH - 3) + "...'"
    assertEquals(expectedContent, loggingPlugin.formatContent(aboveLimitContent))
  }
}
