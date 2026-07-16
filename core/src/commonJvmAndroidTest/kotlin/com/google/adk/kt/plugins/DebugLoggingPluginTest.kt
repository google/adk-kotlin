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

import com.google.adk.kt.agents.toCallbackContext
import com.google.adk.kt.callbacks.CallbackChoice
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.testing.DummyAgent
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.DummyTool
import com.google.adk.kt.testing.testInvocationContext
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.GenerateContentConfig
import com.google.adk.kt.types.Part
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.runBlocking

class DebugLoggingPluginTest {

  private val agent = DummyAgent("test_agent")
  private val tool = DummyTool("test_tool", "Test tool")
  private val invocationContext = testInvocationContext(agent = agent)
  private val callbackContext = invocationContext.toCallbackContext()
  private val toolContext = ToolContext(invocationContext)

  private fun tempFile(): File = File.createTempFile("adk_debug", ".yaml").apply { deleteOnExit() }

  private fun newPlugin(
    file: File,
    includeSessionState: Boolean = true,
    includeSystemInstruction: Boolean = true,
  ) =
    DebugLoggingPlugin(
      outputPath = file.absolutePath,
      includeSessionState = includeSessionState,
      includeSystemInstruction = includeSystemInstruction,
    )

  private suspend fun DebugLoggingPlugin.startInvocation() {
    assertEquals(CallbackChoice.Continue(Unit), beforeRun(invocationContext))
  }

  @Test
  fun defaultName_isDebugLoggingPlugin() {
    assertEquals("debug_logging_plugin", DebugLoggingPlugin().name)
  }

  @Test
  fun writesInvocationDocumentToFile() = runBlocking {
    val file = tempFile()
    val plugin = newPlugin(file)

    plugin.startInvocation()
    plugin.afterRun(invocationContext)

    val text = file.readText()
    assertContains(text, "---")
    assertContains(text, "invocation_id: test-invocation-id")
    assertContains(text, "session_id: test_session_id")
    assertContains(text, "app_name: test_app_name")
    assertContains(text, "entry_type: invocation_start")
    assertContains(text, "entry_type: invocation_end")
  }

  @Test
  fun capturesUserMessageContentInFull() = runBlocking {
    val file = tempFile()
    val plugin = newPlugin(file)
    val message = Content(parts = listOf(Part(text = "hello world")))

    plugin.startInvocation()
    assertEquals(message, plugin.onUserMessage(invocationContext, message))
    plugin.afterRun(invocationContext)

    val text = file.readText()
    assertContains(text, "entry_type: user_message")
    assertContains(text, "hello world")
  }

  @Test
  fun capturesLlmRequestAndResponse() = runBlocking {
    val file = tempFile()
    val plugin = newPlugin(file)
    val request =
      LlmRequest(
        model = DummyModel("dummy-model"),
        contents = listOf(Content(parts = listOf(Part(text = "prompt text")))),
      )
    val response = LlmResponse(content = Content(parts = listOf(Part(text = "response text"))))

    plugin.startInvocation()
    assertEquals(CallbackChoice.Continue(request), plugin.beforeModel(callbackContext, request))
    assertEquals(response, plugin.afterModel(callbackContext, response))
    plugin.afterRun(invocationContext)

    val text = file.readText()
    assertContains(text, "entry_type: llm_request")
    assertContains(text, "dummy-model")
    assertContains(text, "prompt text")
    assertContains(text, "entry_type: llm_response")
    assertContains(text, "response text")
  }

  @Test
  fun capturesToolCallAndResult() = runBlocking {
    val file = tempFile()
    val plugin = newPlugin(file)
    val args = mapOf<String, Any>("city" to "Zurich")
    val result = mapOf<String, Any>("temperature" to "20")

    plugin.startInvocation()
    assertEquals(CallbackChoice.Continue(args), plugin.beforeTool(toolContext, tool, args))
    assertEquals(result, plugin.afterTool(toolContext, tool, args, result))
    plugin.afterRun(invocationContext)

    val text = file.readText()
    assertContains(text, "entry_type: tool_call")
    assertContains(text, "test_tool")
    assertContains(text, "Zurich")
    assertContains(text, "entry_type: tool_response")
    assertContains(text, "temperature")
  }

  @Test
  fun includeSessionStateFalse_omitsSnapshot() = runBlocking {
    val file = tempFile()
    val plugin = newPlugin(file, includeSessionState = false)

    plugin.startInvocation()
    plugin.afterRun(invocationContext)

    assertFalse(file.readText().contains("session_state_snapshot"))
  }

  @Test
  fun includeSystemInstructionFalse_marksPresenceWithoutLeakingText() = runBlocking {
    val file = tempFile()
    val plugin = newPlugin(file, includeSystemInstruction = false)
    val request =
      LlmRequest(
        config =
          GenerateContentConfig(
            systemInstruction = Content(parts = listOf(Part(text = "secret instruction")))
          )
      )

    plugin.startInvocation()
    assertEquals(CallbackChoice.Continue(request), plugin.beforeModel(callbackContext, request))
    plugin.afterRun(invocationContext)

    val text = file.readText()
    assertContains(text, "has_system_instruction")
    assertFalse(text.contains("secret instruction"))
  }

  @Test
  fun missingBeforeRun_doesNotWriteFile() = runBlocking {
    val file = tempFile()
    file.delete()
    val plugin = newPlugin(file)

    // No beforeRun, so there is no invocation state to flush.
    plugin.afterRun(invocationContext)

    assertFalse(file.exists())
  }

  @Test
  fun restrictsOutputFileToOwner() = runBlocking {
    val file = tempFile()
    file.delete()
    val plugin = newPlugin(file)

    plugin.startInvocation()
    plugin.afterRun(invocationContext)

    val posixView = Files.getFileAttributeView(file.toPath(), PosixFileAttributeView::class.java)
    // POSIX permissions are only asserted on filesystems that support them.
    if (posixView != null) {
      val permissions = posixView.readAttributes().permissions()
      assertContains(permissions, PosixFilePermission.OWNER_READ)
      assertContains(permissions, PosixFilePermission.OWNER_WRITE)
      assertFalse(permissions.contains(PosixFilePermission.GROUP_READ))
      assertFalse(permissions.contains(PosixFilePermission.OTHERS_READ))
    }
  }
}
