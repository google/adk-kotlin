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

import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.ReadonlyContext
import com.google.adk.kt.agents.toCallbackContext
import com.google.adk.kt.callbacks.CallbackChoice
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.sessions.State
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.modelTransferToAgentResponse
import com.google.adk.kt.testing.testInvocationContext
import com.google.adk.kt.testing.testSession
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.GenerateContentConfig
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.fail
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

class GlobalInstructionPluginTest {

  @Test
  fun constructor_defaultName() {
    assertEquals("global_instruction", GlobalInstructionPlugin("Be brief.").name)
  }

  @Test
  fun constructor_textWithName_usesThatName() {
    assertEquals("custom", GlobalInstructionPlugin("Be brief.", name = "custom").name)
  }

  @Test
  fun constructor_providerWithName_usesThatName() {
    assertEquals("custom", GlobalInstructionPlugin({ "Be brief." }, name = "custom").name)
  }

  @Test
  fun constructor_providerValue_usesTheDefaultName() {
    val provider: suspend (ReadonlyContext) -> String? = { "Be brief." }

    assertEquals("global_instruction", GlobalInstructionPlugin(provider).name)
  }

  @Test
  fun beforeModel_noSystemInstruction_setsTheGlobalInstruction() = runBlocking {
    val request = GlobalInstructionPlugin("Be brief.").prepare(LlmRequest())

    assertEquals(
      Content(parts = listOf(Part(text = "Be brief."))),
      request.config.systemInstruction,
    )
  }

  @Test
  fun beforeModel_existingSystemInstruction_putsTheGlobalInstructionFirst() = runBlocking {
    val existing =
      Content(role = Role.SYSTEM, parts = listOf(Part(text = "First."), Part(text = "\n\nSecond.")))

    val request = GlobalInstructionPlugin("Be brief.").prepare(requestWith(existing))

    assertEquals(
      Content(
        role = Role.SYSTEM,
        parts =
          listOf(Part(text = "Be brief.\n\n"), Part(text = "First."), Part(text = "\n\nSecond.")),
      ),
      request.config.systemInstruction,
    )
  }

  @Test
  fun beforeModel_systemInstructionWithoutParts_getsOnlyTheGlobalInstruction() = runBlocking {
    val request =
      GlobalInstructionPlugin("Be brief.").prepare(requestWith(Content(role = Role.SYSTEM)))

    assertEquals(
      Content(role = Role.SYSTEM, parts = listOf(Part(text = "Be brief."))),
      request.config.systemInstruction,
    )
  }

  @Test
  fun beforeModel_emptyInstruction_leavesTheRequestUnchanged() = runBlocking {
    val original = requestWith(Content(parts = listOf(Part(text = "Agent instruction."))))

    assertSame(original, GlobalInstructionPlugin("").prepare(original))
  }

  @Test
  fun beforeModel_instructionResolvesToEmpty_leavesTheRequestUnchanged() = runBlocking {
    val original = LlmRequest()

    assertSame(original, GlobalInstructionPlugin("{nickname?}").prepare(original))
  }

  @Test
  fun beforeModel_fillsInSessionStatePlaceholders() = runBlocking {
    val session = sessionWithState("user_name" to "Alice")

    val request =
      GlobalInstructionPlugin("Address {user_name} by name.{nickname?}")
        .prepare(LlmRequest(), session)

    assertEquals("Address Alice by name.", request.config.systemInstruction?.text())
  }

  @Test
  fun beforeModel_missingStateVariable_throws() = runBlocking {
    val plugin = GlobalInstructionPlugin("Address {user_name} by name.")

    val thrown = assertFailsWith<IllegalArgumentException> { plugin.prepare(LlmRequest()) }

    assertEquals("Context variable not found: `user_name`.", thrown.message)
  }

  @Test
  fun beforeModel_provider_appliesTheInstructionAsIs() = runBlocking {
    val plugin = GlobalInstructionPlugin({ context -> "Help {user_name}, id ${context.userId}." })

    val request = plugin.prepare(LlmRequest(), sessionWithState("user_name" to "Alice"))

    assertEquals("Help {user_name}, id test_user_id.", request.config.systemInstruction?.text())
  }

  @Test
  fun beforeModel_providerReturnsNull_leavesTheRequestUnchanged() = runBlocking {
    val original = LlmRequest()

    assertSame(original, GlobalInstructionPlugin({ null }).prepare(original))
  }

  @Test
  fun runAsync_everyAgentGetsTheGlobalInstructionFirst() = runBlocking {
    val systemInstructions = mutableMapOf<String, Content?>()
    fun model(agentName: String, response: LlmResponse) =
      DummyModel("mock-model") { request ->
        systemInstructions[agentName] = request.config.systemInstruction
        flowOf(response)
      }
    val helperAgent =
      LlmAgent(
        name = "helper",
        model = model("helper", LlmResponse(content = modelMessage("Done."))),
        instruction = Instruction("Helper instruction."),
      )
    val rootAgent =
      LlmAgent(
        name = "root",
        model = model("root", modelTransferToAgentResponse("helper")),
        instruction = Instruction("Root instruction."),
        subAgents = listOf(helperAgent),
      )

    val events =
      InMemoryRunner(
          agent = rootAgent,
          plugins = listOf(GlobalInstructionPlugin("You work for Acme.")),
        )
        .runAsync(userId = "user1", sessionId = "session1", newMessage = userMessage("Hi"))
        .toList()

    assertEquals("Done.", events.last().content?.text())
    val root = systemInstructions.getValue("root")
    assertEquals(Part(text = "You work for Acme.\n\n"), root?.parts?.first())
    assertContains(root?.text().orEmpty(), "Root instruction.")
    val helper = systemInstructions.getValue("helper")
    assertEquals(Part(text = "You work for Acme.\n\n"), helper?.parts?.first())
    assertContains(helper?.text().orEmpty(), "Helper instruction.")
  }

  private fun requestWith(systemInstruction: Content): LlmRequest =
    LlmRequest(config = GenerateContentConfig(systemInstruction = systemInstruction))

  private fun sessionWithState(vararg entries: Pair<String, Any>): Session =
    testSession().copy(state = State(mapOf(*entries)))

  private suspend fun GlobalInstructionPlugin.prepare(
    request: LlmRequest,
    session: Session = testSession(),
  ): LlmRequest {
    val context = testInvocationContext(session = session).toCallbackContext()
    return when (val choice = beforeModel(context, request)) {
      is CallbackChoice.Continue -> choice.value
      is CallbackChoice.Break -> fail("Expected the plugin to let the model call proceed.")
    }
  }
}
