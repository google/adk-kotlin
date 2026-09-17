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

import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.testing.DummyAgent
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.testSession
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Role
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import org.junit.Test

class IdentityProcessorTest {

  @Test
  fun process_withoutDescription_appendsNameOnlyIdentity() = runBlocking {
    val agent = LlmAgent(name = "test_agent", model = DummyModel("gemini"))
    val session = testSession()
    val context = InvocationContext(session = session, runConfig = null, agent = agent)

    val request = IdentityProcessor().process(context, LlmRequest())

    val systemInstruction = request.config.systemInstruction
    assertNotNull(systemInstruction)
    assertEquals(
      "You are an agent. Your internal name is \"test_agent\".",
      systemInstruction.parts.firstOrNull()?.text,
    )
  }

  @Test
  fun process_withDescription_appendsNameAndDescriptionIdentity() = runBlocking {
    val agent =
      LlmAgent(name = "test_agent", model = DummyModel("gemini"), description = "helps with tests")
    val session = testSession()
    val context = InvocationContext(session = session, runConfig = null, agent = agent)

    val request = IdentityProcessor().process(context, LlmRequest())

    val systemInstruction = request.config.systemInstruction
    assertNotNull(systemInstruction)
    assertEquals(
      "You are an agent. Your internal name is \"test_agent\". " +
        "The description about you is \"helps with tests\".",
      systemInstruction.parts.firstOrNull()?.text,
    )
  }

  @Test
  fun process_withExistingSystemInstruction_appendsIdentityAfterIt() = runBlocking {
    val agent = LlmAgent(name = "test_agent", model = DummyModel("gemini"))
    val session = testSession()
    val context = InvocationContext(session = session, runConfig = null, agent = agent)
    val request = LlmRequest().appendInstructions(Content.fromText(Role.SYSTEM, "existing"))

    val processed = IdentityProcessor().process(context, request)

    val systemInstruction = processed.config.systemInstruction
    assertNotNull(systemInstruction)
    assertEquals("existing", systemInstruction.parts[0].text)
    assertEquals(
      "\n\nYou are an agent. Your internal name is \"test_agent\".",
      systemInstruction.parts[1].text,
    )
  }

  @Test
  fun process_withNonLlmAgent_returnsRequestUnchanged() = runBlocking {
    val context =
      InvocationContext(session = testSession(), runConfig = null, agent = DummyAgent("non_llm"))
    val request = LlmRequest()

    val processed = IdentityProcessor().process(context, request)

    assertEquals(request, processed)
    assertNull(processed.config.systemInstruction)
  }
}
