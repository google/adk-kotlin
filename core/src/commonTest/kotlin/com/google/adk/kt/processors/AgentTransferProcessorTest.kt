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
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.testSession
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AgentTransferProcessorTest {

  @Test
  fun processRequest_withSubAgents_addsInstructionsAndTool() = runTest {
    val processedRequest =
      AgentTransferProcessor()
        .process(
          InvocationContext(
            session = testSession(),
            runConfig = null,
            agent =
              LlmAgent(
                name = "root",
                description = "Root agent",
                model = DummyModel("root"),
                subAgents =
                  listOf(
                    LlmAgent(name = "sub", description = "Sub agent", model = DummyModel("sub"))
                  ),
              ),
          ),
          LlmRequest(),
        )

    val instructionText =
      processedRequest.config.systemInstruction
        ?.parts
        ?.mapNotNull { it.text }
        ?.joinToString("\n")
        .orEmpty()
    val expectedInstruction =
      """
      |You have a list of other agents to transfer to:
      |
      |Agent name: sub
      |Agent description: Sub agent
      |
      |If you are the best to answer the question according to your description,
      |you can answer it.
      |
      |If another agent is better for answering the question according to its
      |description, call `transfer_to_agent` function to transfer the question to that
      |agent. When transferring, do not generate any text other than the function
      |call.
      |
      |**NOTE**: the only available agents for `transfer_to_agent` function are
      |`sub`.
      """
        .trimMargin()
    assertEquals(expectedInstruction, instructionText)
    assertTrue(
      processedRequest.config.tools.orEmpty().any {
        it.functionDeclarations.orEmpty().any { fd -> fd.name == "transfer_to_agent" }
      }
    )
  }

  @Test
  fun processRequest_withParentAndPeers_addsInstructions() = runTest {
    val subAgent = LlmAgent(name = "sub", description = "Sub agent", model = DummyModel("sub"))
    val peerAgent = LlmAgent(name = "peer", description = "Peer agent", model = DummyModel("peer"))
    val unused =
      LlmAgent(
        name = "parent",
        description = "Parent agent",
        model = DummyModel("parent"),
        subAgents = listOf(subAgent, peerAgent),
      )

    val processedRequest =
      AgentTransferProcessor()
        .process(
          InvocationContext(session = testSession(), runConfig = null, agent = subAgent),
          LlmRequest(),
        )

    val instructionText =
      processedRequest.config.systemInstruction
        ?.parts
        ?.mapNotNull { it.text }
        ?.joinToString("\n")
        .orEmpty()
    val expectedInstruction =
      """
      |You have a list of other agents to transfer to:
      |
      |Agent name: parent
      |Agent description: Parent agent
      |
      |Agent name: peer
      |Agent description: Peer agent
      |
      |If you are the best to answer the question according to your description,
      |you can answer it.
      |
      |If another agent is better for answering the question according to its
      |description, call `transfer_to_agent` function to transfer the question to that
      |agent. When transferring, do not generate any text other than the function
      |call.
      |
      |**NOTE**: the only available agents for `transfer_to_agent` function are
      |`parent`, `peer`.
      |
      |If neither you nor the other agents are best for the question, transfer to your parent agent parent.
      """
        .trimMargin()
    assertEquals(expectedInstruction, instructionText)
  }

  @Test
  fun processRequest_noTargets_returnsUnmodifiedRequest() = runTest {
    val agent = LlmAgent(name = "alone", description = "Standalone", model = DummyModel("alone"))
    val processedRequest =
      AgentTransferProcessor()
        .process(
          InvocationContext(session = testSession(), runConfig = null, agent = agent),
          LlmRequest(),
        )

    assertNull(processedRequest.config.systemInstruction)
    assertTrue(processedRequest.config.tools.isNullOrEmpty())
  }

  @Test
  fun processRequest_disallowParentAndPeers_noSubAgents_noTransferToAgentTool() = runTest {
    val subAgent =
      LlmAgent(
        name = "sub",
        description = "Sub agent",
        model = DummyModel("sub"),
        disallowTransferToParent = true,
        disallowTransferToPeers = true,
      )
    val peerAgent = LlmAgent(name = "peer", description = "Peer agent", model = DummyModel("peer"))
    // Creating the parent agent establishes the parent-child relationship in BaseAgent's init
    // block.
    // This allows the processor to discover the parent and peers when processing the sub-agent.
    val unusedParentAgent =
      LlmAgent(
        name = "parent",
        description = "Parent agent",
        model = DummyModel("parent"),
        subAgents = listOf(subAgent, peerAgent),
      )

    val processedRequest =
      AgentTransferProcessor()
        .process(
          InvocationContext(session = testSession(), runConfig = null, agent = subAgent),
          LlmRequest(),
        )
    assertNull(processedRequest.config.systemInstruction)
    assertTrue(processedRequest.config.tools.isNullOrEmpty())
  }

  @Test
  fun processRequest_disallowParentOnly_includesPeersButNotParent() = runTest {
    val subAgent =
      LlmAgent(
        name = "sub",
        description = "Sub agent",
        model = DummyModel("sub"),
        disallowTransferToParent = true,
        disallowTransferToPeers = false,
      )
    val peerAgent = LlmAgent(name = "peer", description = "Peer agent", model = DummyModel("peer"))
    val unused =
      LlmAgent(
        name = "parent",
        description = "Parent agent",
        model = DummyModel("parent"),
        subAgents = listOf(subAgent, peerAgent),
      )

    val processedRequest =
      AgentTransferProcessor()
        .process(
          InvocationContext(session = testSession(), runConfig = null, agent = subAgent),
          LlmRequest(),
        )

    val instructionText =
      processedRequest.config.systemInstruction
        ?.parts
        ?.mapNotNull { it.text }
        ?.joinToString("\n")
        .orEmpty()
    val expectedInstruction =
      """
      |You have a list of other agents to transfer to:
      |
      |Agent name: peer
      |Agent description: Peer agent
      |
      |If you are the best to answer the question according to your description,
      |you can answer it.
      |
      |If another agent is better for answering the question according to its
      |description, call `transfer_to_agent` function to transfer the question to that
      |agent. When transferring, do not generate any text other than the function
      |call.
      |
      |**NOTE**: the only available agents for `transfer_to_agent` function are
      |`peer`.
      """
        .trimMargin()
    assertEquals(expectedInstruction, instructionText)
  }

  @Test
  fun processRequest_disallowPeersOnly_includesParentButNotPeers() = runTest {
    val subAgent =
      LlmAgent(
        name = "sub",
        description = "Sub agent",
        model = DummyModel("sub"),
        disallowTransferToParent = false,
        disallowTransferToPeers = true,
      )
    val peerAgent = LlmAgent(name = "peer", description = "Peer agent", model = DummyModel("peer"))
    val unused =
      LlmAgent(
        name = "parent",
        description = "Parent agent",
        model = DummyModel("parent"),
        subAgents = listOf(subAgent, peerAgent),
      )

    val processedRequest =
      AgentTransferProcessor()
        .process(
          InvocationContext(session = testSession(), runConfig = null, agent = subAgent),
          LlmRequest(),
        )

    val instructionText =
      processedRequest.config.systemInstruction
        ?.parts
        ?.mapNotNull { it.text }
        ?.joinToString("\n")
        .orEmpty()
    val expectedInstruction =
      """
      |You have a list of other agents to transfer to:
      |
      |Agent name: parent
      |Agent description: Parent agent
      |
      |If you are the best to answer the question according to your description,
      |you can answer it.
      |
      |If another agent is better for answering the question according to its
      |description, call `transfer_to_agent` function to transfer the question to that
      |agent. When transferring, do not generate any text other than the function
      |call.
      |
      |**NOTE**: the only available agents for `transfer_to_agent` function are
      |`parent`.
      |
      |If neither you nor the other agents are best for the question, transfer to your parent agent parent.
      """
        .trimMargin()
    assertEquals(expectedInstruction, instructionText)
  }

  @Test
  fun processRequest_duplicateTargets_throwsException() = runTest {
    val subAgentA = LlmAgent(name = "A", description = "Sub agent A", model = DummyModel("subA"))
    val peerAgentA = LlmAgent(name = "A", description = "Peer agent A", model = DummyModel("peerA"))
    val subAgentMain =
      LlmAgent(
        name = "Main",
        description = "Main agent",
        model = DummyModel("Main"),
        subAgents = listOf(subAgentA),
      )
    val unused =
      LlmAgent(
        name = "parent",
        description = "Parent agent",
        model = DummyModel("parent"),
        subAgents = listOf(subAgentMain, peerAgentA),
      )

    val exception =
      assertFailsWith<IllegalArgumentException> {
        AgentTransferProcessor()
          .process(
            InvocationContext(session = testSession(), runConfig = null, agent = subAgentMain),
            LlmRequest(),
          )
      }

    assertEquals(
      "Duplicate agent names found in transfer targets: A. Agent names must be unique within reachable transfer scope.",
      exception.message,
    )
  }

  @Test
  fun processRequest_toolSchemaContainsCorrectAgentNames() = runTest {
    val subAgent1 = LlmAgent(name = "sub1", description = "Sub1", model = DummyModel("sub1"))
    val subAgent2 = LlmAgent(name = "sub2", description = "Sub2", model = DummyModel("sub2"))
    val rootAgent =
      LlmAgent(
        name = "root",
        description = "Root",
        model = DummyModel("root"),
        subAgents = listOf(subAgent1, subAgent2),
      )

    val processedRequest =
      AgentTransferProcessor()
        .process(
          InvocationContext(session = testSession(), runConfig = null, agent = rootAgent),
          LlmRequest(),
        )

    val declaration =
      processedRequest.config.tools
        ?.flatMap { it.functionDeclarations.orEmpty() }
        ?.firstOrNull { it.name == "transfer_to_agent" }
    assertNotNull(declaration)
    val agentNameSchema = declaration.parameters?.properties?.get("agent_name")
    assertNotNull(agentNameSchema)
    assertEquals(listOf("sub1", "sub2"), agentNameSchema.enum)
  }
}
