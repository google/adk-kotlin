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

package com.google.adk.kt.models

import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.GenerateContentConfig
import com.google.adk.kt.types.HarmBlockThreshold
import com.google.adk.kt.types.HarmCategory
import com.google.adk.kt.types.LiveConnectConfig
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.SafetySetting
import com.google.adk.kt.types.ThinkingConfig
import com.google.adk.kt.types.Tool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Tests how [carryingAgentConfig] folds an agent's configuration into a live connect config. */
class LiveConnectConfigAssemblyTest {

  private val weatherTool =
    Tool(
      functionDeclarations =
        listOf(FunctionDeclaration(name = "get_weather", description = "looks up the weather"))
    )

  private fun agentConfig(
    instruction: String? = "be brief",
    tools: List<Tool> = listOf(weatherTool),
    thinking: ThinkingConfig? = null,
    safety: List<SafetySetting> = emptyList(),
  ) =
    GenerateContentConfig(
      systemInstruction =
        instruction?.let { Content(role = Role.USER, parts = listOf(Part(text = it))) },
      tools = tools,
      thinkingConfig = thinking,
      safetySettings = safety.ifEmpty { null },
    )

  @Test
  fun carryingAgentConfig_deliversTheAgentsTools() {
    // Without this the model is never told the tools exist, so it cannot issue a call at all.
    val assembled = LiveConnectConfig().carryingAgentConfig(agentConfig())

    assertEquals(
      listOf("get_weather"),
      assembled.tools?.flatMap { tool -> tool.functionDeclarations.orEmpty().map { it.name } },
    )
  }

  @Test
  fun carryingAgentConfig_deliversTheAgentsInstructionAsASystemTurn() {
    val assembled = LiveConnectConfig().carryingAgentConfig(agentConfig(instruction = "be brief"))

    val instruction = assembled.systemInstruction
    assertNotNull(instruction)
    assertEquals("be brief", instruction.parts.single().text)
    // The role is what reaches the wire, so it is forced rather than inherited from the agent.
    assertEquals(Role.SYSTEM, instruction.role)
  }

  @Test
  fun carryingAgentConfig_withNoInstruction_omitsIt() {
    // Unlike ADK Python: Vertex refuses an empty part (1007), so a missing instruction is omitted.
    val assembled = LiveConnectConfig().carryingAgentConfig(agentConfig(instruction = null))

    assertNull(
      assembled.systemInstruction,
      "an absent instruction must be omitted, not sent as an empty part",
    )
  }

  @Test
  fun carryingAgentConfig_agentsToolsOutrankTheLiveConfigs() {
    // The opposite rule to safety below: the agent's tools win.
    val staleTool =
      Tool(
        functionDeclarations =
          listOf(FunctionDeclaration(name = "stale_tool", description = "no longer offered"))
      )

    val assembled = LiveConnectConfig(tools = listOf(staleTool)).carryingAgentConfig(agentConfig())

    assertEquals(
      listOf("get_weather"),
      assembled.tools?.flatMap { tool -> tool.functionDeclarations.orEmpty().map { it.name } },
    )
  }

  @Test
  fun carryingAgentConfig_withNullAgentTools_clearsTheLiveConfigsTools() {
    // As in Python, the agent's tools are assigned unconditionally, even when it has none.
    val callerTool =
      Tool(
        functionDeclarations =
          listOf(FunctionDeclaration(name = "caller_tool", description = "set by the caller"))
      )

    val assembled =
      LiveConnectConfig(tools = listOf(callerTool))
        .carryingAgentConfig(agentConfig().copy(tools = null))

    assertNull(assembled.tools)
  }

  @Test
  fun carryingAgentConfig_withEmptyAgentTools_clobbersTheLiveConfigsTools() {
    val callerTool =
      Tool(
        functionDeclarations =
          listOf(FunctionDeclaration(name = "caller_tool", description = "set by the caller"))
      )

    val assembled =
      LiveConnectConfig(tools = listOf(callerTool))
        .carryingAgentConfig(agentConfig(tools = emptyList()))

    assertEquals(emptyList(), assembled.tools)
  }

  @Test
  fun carryingAgentConfig_agentsInstructionOutranksTheLiveConfigs() {
    // Same rule as tools: the agent's instruction wins.
    val assembled =
      LiveConnectConfig(
          systemInstruction = Content(role = Role.SYSTEM, parts = listOf(Part(text = "stale")))
        )
        .carryingAgentConfig(agentConfig(instruction = "be brief"))

    val instruction = assembled.systemInstruction
    assertNotNull(instruction)
    assertEquals("be brief", instruction.parts.single().text)
  }

  @Test
  fun carryingAgentConfig_forwardsThinkingWhenTheAgentSetsIt() {
    val thinking = ThinkingConfig(includeThoughts = true)

    val assembled = LiveConnectConfig().carryingAgentConfig(agentConfig(thinking = thinking))

    assertEquals(thinking, assembled.thinkingConfig)
  }

  @Test
  fun carryingAgentConfig_forwardsSafetyWhenTheLiveConfigHasNone() {
    // Safety is configured on the agent, so a live run should honor the same settings.
    val safety =
      listOf(
        SafetySetting(
          category = HarmCategory.HARM_CATEGORY_HARASSMENT,
          threshold = HarmBlockThreshold.BLOCK_ONLY_HIGH,
        )
      )

    val assembled = LiveConnectConfig().carryingAgentConfig(agentConfig(safety = safety))

    assertEquals(safety, assembled.safetySettings)
  }

  @Test
  fun carryingAgentConfig_keepsSafetyChosenForThisConnection() {
    // The other direction: a value set on the live config outranks the agent's.
    val chosenForTheConnection =
      listOf(
        SafetySetting(
          category = HarmCategory.HARM_CATEGORY_HARASSMENT,
          threshold = HarmBlockThreshold.BLOCK_NONE,
        )
      )
    val agentWide =
      listOf(
        SafetySetting(
          category = HarmCategory.HARM_CATEGORY_HARASSMENT,
          threshold = HarmBlockThreshold.BLOCK_ONLY_HIGH,
        )
      )

    val assembled =
      LiveConnectConfig(safetySettings = chosenForTheConnection)
        .carryingAgentConfig(agentConfig(safety = agentWide))

    assertEquals(chosenForTheConnection, assembled.safetySettings)
  }

  @Test
  fun carryingAgentConfig_thinkingOnBoth_takesTheAgents() {
    val live = LiveConnectConfig(thinkingConfig = ThinkingConfig(thinkingBudget = 1))

    val assembled =
      live.carryingAgentConfig(agentConfig(thinking = ThinkingConfig(thinkingBudget = 2)))

    assertEquals(2, assembled.thinkingConfig?.thinkingBudget)
  }

  @Test
  fun carryingAgentConfig_instructionOnlyOnTheLiveConfig_isCleared() {
    // Assigned unconditionally; with no instruction it clears the live config's own rather than
    // replacing it with an empty part (which Vertex refuses, 1007).
    val ownInstruction = Content(role = Role.SYSTEM, parts = listOf(Part(text = "live persona")))
    val live = LiveConnectConfig(systemInstruction = ownInstruction)

    val assembled = live.carryingAgentConfig(agentConfig(instruction = null))

    assertNull(assembled.systemInstruction)
  }

  /**
   * Folding in the agent must not overwrite the connection tuning the caller already decided.
   * `tools` and `systemInstruction` are excluded deliberately: the agent wins those two, and each
   * has its own test above.
   */
  @Test
  fun carryingAgentConfig_leavesTheRunConfigsOwnChoicesAlone() {
    val ownSafety =
      listOf(
        SafetySetting(
          category = HarmCategory.HARM_CATEGORY_HARASSMENT,
          threshold = HarmBlockThreshold.BLOCK_NONE,
        )
      )
    val fromRunConfig =
      LiveConnectConfig(seed = 42, temperature = 0.25f, safetySettings = ownSafety)

    val assembled = fromRunConfig.carryingAgentConfig(agentConfig())

    assertEquals(42, assembled.seed)
    assertEquals(0.25f, assembled.temperature)
    assertEquals(ownSafety, assembled.safetySettings)
  }
}
