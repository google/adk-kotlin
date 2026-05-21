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
package com.google.adk.kt.runners

import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.sessions.State
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/**
 * [Instruction] resolution through the public Runner. Mirrors selected scenarios from Python ADK's
 * `flows/llm_flows/test_instructions.py` and Go ADK's `TestInstructionProvider` in
 * `agent/llmagent/llmagent_test.go`.
 *
 * Processor-level tests in [com.google.adk.kt.processors.InstructionsProcessorTest] verify each
 * [Instruction] variant in isolation; the existing E2E test
 * [InMemoryRunnerTest.runAsync_withLlmAgentAndProviderInstruction_interpolatesState] covers
 * `{state_var}` placeholder substitution. This file fills two remaining gaps: programmatic access
 * to session state from inside an `Instruction.Provider`'s suspend lambda, and per-turn
 * re-resolution.
 */
class InstructionsIntegrationTest {

  /**
   * An `Instruction.Provider` whose suspend lambda reads multiple state values from
   * [com.google.adk.kt.agents.ReadonlyContext.state] and assembles a multi-key Content.
   */
  @Test
  fun runAsync_instructionProviderReadsSessionState_modelSeesAssembledInstruction() = runTest {
    var capturedSystemInstruction = ""
    val agent =
      LlmAgent(
        name = "agent",
        model =
          DummyModel(name = "model") { request ->
            capturedSystemInstruction =
              request.config.systemInstruction
                ?.parts
                ?.mapNotNull { it.text }
                ?.joinToString(" ")
                .orEmpty()
            flowOf(LlmResponse(content = modelMessage("ok")))
          },
        instruction =
          Instruction { context ->
            val role = context.state["role"] as? String ?: "unknown"
            val tier = context.state["tier"] as? String ?: "free"
            Content(parts = listOf(Part(text = "User role=$role, tier=$tier.")))
          },
      )
    val runner = InMemoryRunner(agent = agent)
    val unused =
      runner.sessionService.createSession(
        SessionKey(runner.appName, "u", "s"),
        State().apply {
          this["role"] = "admin"
          this["tier"] = "gold"
        },
      )

    runner.runAsync(userId = "u", sessionId = "s", newMessage = userMessage("hi")).toList()

    assertEquals("User role=admin, tier=gold.", capturedSystemInstruction)
  }

  /**
   * Verifies the `Instruction.Provider`'s suspend lambda is re-invoked on every turn (not memoized
   * at agent-construction time).
   *
   * This deliberately does not assert state mutation cross-turn through `stateDelta`. The current
   * Kotlin runner populates [InvocationContext.session] from a
   * [com.google.adk.kt.sessions.InMemorySessionService.getSession] copy taken at the start of each
   * `runAsync`; a `stateDelta` applied via the same `runAsync`'s `handleNewUserContent` persists to
   * the stored session but is not reflected in the in-flight copy that `ReadonlyContextImpl.state`
   * reads. That stale-state behaviour is out of scope here.
   */
  @Test
  fun runAsync_instructionProviderReResolvedOnEachTurn_invokedOncePerInvocation() = runTest {
    var providerInvocations = 0
    val capturedInstructions = mutableListOf<String>()
    val agent =
      LlmAgent(
        name = "agent",
        model =
          DummyModel(name = "model") { request ->
            capturedInstructions +=
              request.config.systemInstruction
                ?.parts
                ?.mapNotNull { it.text }
                ?.joinToString(" ")
                .orEmpty()
            flowOf(LlmResponse(content = modelMessage("turn ok")))
          },
        instruction =
          Instruction { _ ->
            providerInvocations++
            Content(parts = listOf(Part(text = "turn-$providerInvocations")))
          },
      )
    val runner = InMemoryRunner(agent = agent)

    runner.runAsync(userId = "u", sessionId = "s", newMessage = userMessage("first")).toList()
    runner.runAsync(userId = "u", sessionId = "s", newMessage = userMessage("second")).toList()

    assertEquals(2, providerInvocations)
    assertEquals(listOf("turn-1", "turn-2"), capturedInstructions)
  }

  /**
   * Static instruction goes to the system-instruction channel; dynamic provider instruction goes to
   * the model's user-content channel. Mirrors the processor-level
   * [com.google.adk.kt.processors.InstructionsProcessorTest.run_withProviderAndStaticInstruction_appendsToContent]
   * but at the runner level.
   */
  @Test
  fun runAsync_staticAndDynamicInstructionsTogether_staticGoesToSystemDynamicGoesToContents() =
    runTest {
      var capturedSystemText = ""
      var capturedDynamicTextInContents: String? = null
      val agent =
        LlmAgent(
          name = "agent",
          model =
            DummyModel(name = "model") { request ->
              capturedSystemText =
                request.config.systemInstruction
                  ?.parts
                  ?.mapNotNull { it.text }
                  ?.joinToString(" ")
                  .orEmpty()
              capturedDynamicTextInContents =
                request.contents
                  .flatMap { it.parts }
                  .mapNotNull { it.text }
                  .firstOrNull { it.contains("dynamic-instruction") }
              flowOf(LlmResponse(content = modelMessage("ok")))
            },
          staticInstruction = Content(parts = listOf(Part(text = "static-instruction"))),
          instruction = Instruction { _ -> userMessage("dynamic-instruction") },
        )
      val runner = InMemoryRunner(agent = agent)

      runner.runAsync(userId = "u", sessionId = "s", newMessage = userMessage("hi")).toList()

      assertEquals("static-instruction", capturedSystemText)
      assertNotNull(capturedDynamicTextInContents)
      assertTrue(capturedDynamicTextInContents!!.contains("dynamic-instruction"))
    }
}
