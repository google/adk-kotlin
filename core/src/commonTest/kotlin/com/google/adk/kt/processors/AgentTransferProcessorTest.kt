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

import com.google.adk.kt.agents.LiveRequestQueue
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.LoopAgent
import com.google.adk.kt.agents.ParallelAgent
import com.google.adk.kt.agents.SequentialAgent
import com.google.adk.kt.annotations.ExperimentalLiveApi
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.testInvocationContext
import com.google.adk.kt.tools.TransferToAgentTool.Companion.TRANSFER_TO_AGENT_TOOL_NAME
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AgentTransferProcessorTest {

  @Test
  fun processRequest_withSubAgents_addsInstructionsAndTool() = runTest {
    val processedRequest =
      AgentTransferProcessor()
        .process(
          testInvocationContext(
            agent =
              LlmAgent(
                name = "root",
                description = "Root agent",
                model = DummyModel("root"),
                subAgents =
                  listOf(
                    LlmAgent(name = "sub", description = "Sub agent", model = DummyModel("sub"))
                  ),
              )
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
        it.functionDeclarations.orEmpty().any { fd -> fd.name == TRANSFER_TO_AGENT_TOOL_NAME }
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
      AgentTransferProcessor().process(testInvocationContext(agent = subAgent), LlmRequest())

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

  @OptIn(ExperimentalLiveApi::class)
  @Test
  fun processRequest_onALiveRun_offersTransfer() = runBlocking {
    val context =
      testInvocationContext(
          agent =
            LlmAgent(
              name = "root",
              description = "Root agent",
              model = DummyModel("root"),
              subAgents =
                listOf(LlmAgent(name = "sub", description = "Sub agent", model = DummyModel("sub"))),
            )
        )
        .apply { frameworkData.liveRequestQueue = LiveRequestQueue() }

    val processedRequest = AgentTransferProcessor().process(context, LlmRequest())

    assertNotNull(processedRequest.config.systemInstruction)
    assertTrue(
      processedRequest.config.tools.orEmpty().any {
        it.functionDeclarations.orEmpty().any { fd -> fd.name == TRANSFER_TO_AGENT_TOOL_NAME }
      }
    )
  }

  @OptIn(ExperimentalLiveApi::class)
  @Test
  fun processRequest_onALiveRun_offersASequentialAgentButNotLoopOrParallel() = runBlocking {
    // A live run offers only the agent types that run live: an LlmAgent or a SequentialAgent.
    val context =
      testInvocationContext(
          agent =
            LlmAgent(
              name = "root",
              description = "Root agent",
              model = DummyModel("root"),
              subAgents =
                listOf(
                  LlmAgent(name = "live_sub", description = "Live sub", model = DummyModel("live")),
                  SequentialAgent(
                    name = "sequence",
                    subAgents =
                      listOf(LlmAgent(name = "step", description = "s", model = DummyModel("step"))),
                  ),
                  LoopAgent(
                    name = "loop",
                    subAgents =
                      listOf(LlmAgent(name = "lstep", description = "l", model = DummyModel("l"))),
                  ),
                  ParallelAgent(
                    name = "parallel",
                    subAgents =
                      listOf(LlmAgent(name = "pstep", description = "p", model = DummyModel("p"))),
                  ),
                ),
            )
        )
        .apply { frameworkData.liveRequestQueue = LiveRequestQueue() }

    val processedRequest = AgentTransferProcessor().process(context, LlmRequest())

    val instructions =
      processedRequest.config.systemInstruction?.parts.orEmpty().joinToString { it.text.orEmpty() }
    assertTrue("live_sub" in instructions, "the live LlmAgent target should be offered")
    assertTrue("sequence" in instructions, "a SequentialAgent should be offered on a live run")
    assertTrue("loop" !in instructions, "a LoopAgent must not be offered on a live run")
    assertTrue("parallel" !in instructions, "a ParallelAgent must not be offered on a live run")
  }

  @OptIn(ExperimentalLiveApi::class)
  @Test
  fun processRequest_onALiveRun_doesNotOfferALoopParent() = runBlocking {
    // A LoopAgent parent cannot run live; the sub-agent is there so instructions are built at all.
    val helper = LlmAgent(name = "helper", description = "Helper", model = DummyModel("helper"))
    val worker =
      LlmAgent(
        name = "worker",
        description = "Worker",
        model = DummyModel("worker"),
        subAgents = listOf(helper),
      )
    val unused = LoopAgent(name = "loop", subAgents = listOf(worker))
    val context =
      testInvocationContext(agent = worker).apply {
        frameworkData.liveRequestQueue = LiveRequestQueue()
      }

    val processedRequest = AgentTransferProcessor().process(context, LlmRequest())

    val instructions =
      processedRequest.config.systemInstruction?.parts.orEmpty().joinToString { it.text.orEmpty() }
    assertTrue("helper" in instructions, "the live sub-agent should be offered")
    assertTrue(
      "transfer to your parent agent" !in instructions,
      "a loop parent must not be offered on a live run",
    )
  }

  @OptIn(ExperimentalLiveApi::class)
  @Test
  fun processRequest_onALiveRun_stepOfASequence_isOfferedNeitherItsSequenceNorItsPeers() =
    runBlocking {
      // A live step reaches only its own sub-agents, not its sequence or a peer, as in Python.
      val child = LlmAgent(name = "child", description = "c", model = DummyModel("c"))
      val step1 =
        LlmAgent(
          name = "step1",
          description = "s1",
          model = DummyModel("s1"),
          subAgents = listOf(child),
        )
      val step2 = LlmAgent(name = "step2", description = "s2", model = DummyModel("s2"))
      val unused = SequentialAgent(name = "sequence", subAgents = listOf(step1, step2))
      val context =
        testInvocationContext(agent = step1).apply {
          frameworkData.liveRequestQueue = LiveRequestQueue()
        }

      val processedRequest = AgentTransferProcessor().process(context, LlmRequest())

      val instructions =
        processedRequest.config.systemInstruction?.parts.orEmpty().joinToString {
          it.text.orEmpty()
        }
      assertTrue("child" in instructions, "the step's own sub-agent should be offered")
      assertTrue("sequence" !in instructions, "the parent sequence must not be offered")
      assertTrue("step2" !in instructions, "a peer step must not be offered")
      assertTrue("transfer to your parent agent" !in instructions)
    }

  @OptIn(ExperimentalLiveApi::class)
  @Test
  fun processRequest_onALiveRun_stepWithDuplicateSubAgentNames_throws() = runBlocking {
    val step =
      LlmAgent(
        name = "step",
        description = "s",
        model = DummyModel("s"),
        subAgents =
          listOf(
            LlmAgent(name = "x", description = "x1", model = DummyModel("x1")),
            LlmAgent(name = "x", description = "x2", model = DummyModel("x2")),
          ),
      )
    val unused = SequentialAgent(name = "sequence", subAgents = listOf(step))
    val context =
      testInvocationContext(agent = step).apply {
        frameworkData.liveRequestQueue = LiveRequestQueue()
      }

    val error =
      assertFailsWith<IllegalArgumentException> {
        AgentTransferProcessor().process(context, LlmRequest())
      }

    assertEquals(
      "Duplicate agent names found in transfer targets: x. Agent names must be unique within reachable transfer scope.",
      error.message,
    )
  }

  @Test
  fun processRequest_noTargets_returnsUnmodifiedRequest() = runTest {
    val agent = LlmAgent(name = "alone", description = "Standalone", model = DummyModel("alone"))
    val processedRequest =
      AgentTransferProcessor().process(testInvocationContext(agent = agent), LlmRequest())

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
      AgentTransferProcessor().process(testInvocationContext(agent = subAgent), LlmRequest())
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
      AgentTransferProcessor().process(testInvocationContext(agent = subAgent), LlmRequest())

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
      AgentTransferProcessor().process(testInvocationContext(agent = subAgent), LlmRequest())

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
        AgentTransferProcessor().process(testInvocationContext(agent = subAgentMain), LlmRequest())
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
      AgentTransferProcessor().process(testInvocationContext(agent = rootAgent), LlmRequest())

    val declaration =
      processedRequest.config.tools
        ?.flatMap { it.functionDeclarations.orEmpty() }
        ?.firstOrNull { it.name == TRANSFER_TO_AGENT_TOOL_NAME }
    assertNotNull(declaration)
    val agentNameSchema = declaration.parameters?.properties?.get("agent_name")
    assertNotNull(agentNameSchema)
    assertEquals(listOf("sub1", "sub2"), agentNameSchema.enum)
  }
}
