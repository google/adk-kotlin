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

package com.google.adk.kt.processors

import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.testSession
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest
import org.junit.Test

class InstructionsProcessorTest {

  @Test
  fun run_withStaticInstruction_setsSystemInstructionOnRequest() = runTest {
    val agent =
      LlmAgent(
        name = "test",
        model = DummyModel("gemini"),
        staticInstruction = Content.fromText(Role.SYSTEM, "static"),
      )
    val session = testSession()
    val context = InvocationContext(session = session, runConfig = null, agent = agent)
    var request = LlmRequest()

    val processor = InstructionsProcessor()
    request = processor.process(context, request)

    val systemInstruction = request.config.systemInstruction
    assertNotNull(systemInstruction)
    assertEquals("static", systemInstruction.parts.firstOrNull()?.text)
  }

  @Test
  fun run_withProviderInstruction_appendsToSystemInstruction() = runTest {
    val agent =
      LlmAgent(
        name = "test",
        model = DummyModel("gemini"),
        instruction = Instruction.Provider { Content(parts = listOf(Part(text = "dynamic"))) },
      )
    val session = testSession()
    val context = InvocationContext(session = session, runConfig = null, agent = agent)
    var request = LlmRequest()

    val processor = InstructionsProcessor()
    request = processor.process(context, request)

    val systemInstruction = request.config.systemInstruction
    assertNotNull(systemInstruction)
    assertEquals("dynamic", systemInstruction.parts.firstOrNull()?.text)
  }

  @Test
  fun run_withTextInstruction_setsSystemInstructionFromString() = runTest {
    val agent =
      LlmAgent(name = "test", model = DummyModel("gemini"), instruction = Instruction("hello"))
    val session = testSession()
    val context = InvocationContext(session = session, runConfig = null, agent = agent)
    var request = LlmRequest()

    val processor = InstructionsProcessor()
    request = processor.process(context, request)

    val systemInstruction = request.config.systemInstruction
    assertNotNull(systemInstruction)
    assertEquals("hello", systemInstruction.parts.firstOrNull()?.text)
  }

  @Test
  fun run_withStructuredInstruction_setsSystemInstructionFromContent() = runTest {
    val content = Content(parts = listOf(Part(text = "structured-instruction")))
    val agent =
      LlmAgent(name = "test", model = DummyModel("gemini"), instruction = Instruction(content))
    val session = testSession()
    val context = InvocationContext(session = session, runConfig = null, agent = agent)
    var request = LlmRequest()

    val processor = InstructionsProcessor()
    request = processor.process(context, request)

    val systemInstruction = request.config.systemInstruction
    assertNotNull(systemInstruction)
    assertEquals("structured-instruction", systemInstruction.parts.firstOrNull()?.text)
  }

  @Test
  fun run_withProviderAndStaticInstruction_appendsToContent() = runTest {
    val agent =
      LlmAgent(
        name = "test",
        model = DummyModel("gemini"),
        staticInstruction = Content.fromText(Role.SYSTEM, "static"),
        instruction = Instruction.Provider { userMessage("dynamic") },
      )
    val session = testSession()
    val context = InvocationContext(session = session, runConfig = null, agent = agent)
    var request = LlmRequest()

    val processor = InstructionsProcessor()
    request = processor.process(context, request)

    val systemInstruction = request.config.systemInstruction
    assertNotNull(systemInstruction)
    assertEquals("static", systemInstruction.parts.firstOrNull()?.text)

    assertEquals(1, request.contents.size)
    assertEquals("dynamic", request.contents.first().parts.firstOrNull()?.text)
  }
}
