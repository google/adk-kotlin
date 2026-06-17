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

import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.LoopAgent
import com.google.adk.kt.agents.SequentialAgent
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.TRANSFER_TO_AGENT_RESPONSE_PART
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.modelTransferToAgentResponse
import com.google.adk.kt.testing.simplifyEvents
import com.google.adk.kt.testing.textAgent
import com.google.adk.kt.testing.transferToAgentCallPart
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.tools.TransferToAgentTool.Companion.TRANSFER_TO_AGENT_TOOL_NAME
import com.google.adk.kt.types.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/**
 * Multi-turn agent-transfer behaviour through the public Runner. Complements the single-transfer
 * scenario in [RunnerTest.runAsync_withAgentTransfer_transfersCorrectly]; mirrors selected
 * scenarios from Python ADK's `flows/llm_flows/test_agent_transfer.py`.
 */
class AgentTransferIntegrationTest {

  /**
   * A `transfer_to_agent` keeps the parent's branch: the transferred-to sub-agent's events carry
   * the same branch as the transferring parent's, rather than a deeper one. Mirrors Python ADK 1.x,
   * where a transfer does not create a new branch. This is what lets the sub-agent see the
   * conversation so far (and, across turns, its own replies).
   */
  @Test
  fun runAsync_transfer_keepsParentBranch() = runTest {
    val rootAgent =
      LlmAgent(
        name = "root",
        model = DummyModel("root-model") { flowOf(modelTransferToAgentResponse("sub")) },
        subAgents =
          listOf(
            LlmAgent(
              name = "sub",
              model =
                DummyModel("sub-model") { flowOf(LlmResponse(content = modelMessage("done"))) },
            )
          ),
      )
    val runner = InMemoryRunner(agent = rootAgent)

    val events =
      runner
        .runAsync(userId = "user1", sessionId = "session1", newMessage = userMessage("hi"))
        .toList()

    val agentEvents = events.filter { it.author != Role.USER }
    assertTrue(
      agentEvents.any { it.author == "sub" },
      "sub-agent should have run after the transfer",
    )
    // Root runs on the invocation's root (null) branch; the transfer must not deepen it for `sub`.
    assertTrue(
      agentEvents.all { it.branch == null },
      "transfer should keep the parent branch; got ${agentEvents.map { it.author to it.branch }}",
    )
  }

  /**
   * Regression test for branch handling across turns. When a sub-agent keeps handling follow-up
   * turns after a transfer, it must still see its own earlier replies. The model receives the
   * conversation history filtered by the running agent's branch, so the sub-agent has to run on the
   * same branch on every turn. Mirrors Python ADK 1.x, where a transfer keeps the parent's branch.
   */
  @Test
  fun runAsync_continuingSubAgent_seesItsOwnPriorTurnReply() = runTest {
    val subRequests = mutableListOf<LlmRequest>()
    var subTurn = 0
    val rootAgent =
      LlmAgent(
        name = "root",
        model = DummyModel("root-model") { flowOf(modelTransferToAgentResponse("sub")) },
        subAgents =
          listOf(
            LlmAgent(
              name = "sub",
              model =
                DummyModel("sub-model") { request ->
                  subRequests.add(request)
                  subTurn++
                  flowOf(LlmResponse(content = modelMessage("sub-reply-$subTurn")))
                },
            )
          ),
      )
    val runner = InMemoryRunner(agent = rootAgent)

    // Turn 1: root transfers to sub, which replies "sub-reply-1".
    runner
      .runAsync(userId = "user1", sessionId = "session1", newMessage = userMessage("hi"))
      .toList()
    // Turn 2: sub keeps handling the conversation.
    runner
      .runAsync(userId = "user1", sessionId = "session1", newMessage = userMessage("again"))
      .toList()

    val turn2Prompt =
      subRequests.last().contents.flatMap { it.parts }.mapNotNull { it.text }.joinToString("")
    // The sub-agent's turn-2 context: the original "hi", the transfer scaffolding, its own turn-1
    // reply ("sub-reply-1"), then "again". The turn-1 reply is the part that used to be missing.
    val expectedPrompt =
      "hi" +
        "For context:" +
        "[root] called tool `transfer_to_agent` with parameters: {\"agent_name\":\"sub\"}" +
        "For context:" +
        "[root] `transfer_to_agent` tool returned result: {}" +
        "sub-reply-1" +
        "again"
    assertEquals(expectedPrompt, turn2Prompt)
  }

  /**
   * `disallowTransferToParent` has a slightly confusing name: it doesn't disable the *first*
   * transfer (the parent can still transfer to the sub-agent). It controls what happens on the
   * *next* user turn after that first transfer. With `disallowTransferToParent = true` set on a
   * sub-agent, the runner routes the next turn back to the root agent rather than letting the
   * sub-agent keep handling the conversation. This test verifies that contract end-to-end.
   */
  @Test
  fun runAsync_disallowTransferToParent_secondInvocationReturnsToRoot() = runTest {
    val rootAgent =
      LlmAgent(
        name = "root",
        model =
          DummyModel.createSequential(
            "root-model",
            listOf(
              modelTransferToAgentResponse("single_sub"),
              LlmResponse(content = modelMessage("root-second-response")),
            ),
          ),
        subAgents =
          listOf(
            LlmAgent(
              name = "single_sub",
              model =
                DummyModel("sub-model") {
                  flowOf(LlmResponse(content = modelMessage("single-sub-response")))
                },
              disallowTransferToParent = true,
            )
          ),
      )
    val runner = InMemoryRunner(agent = rootAgent)

    runner
      .runAsync(userId = "user1", sessionId = "session1", newMessage = userMessage("first"))
      .toList()
    val secondTurnEvents =
      runner
        .runAsync(userId = "user1", sessionId = "session1", newMessage = userMessage("second"))
        .toList()

    val secondTurnAgentEvents = secondTurnEvents.filter { it.author != Role.USER }
    assertEquals("root", secondTurnAgentEvents.firstOrNull()?.author)
    assertEquals(
      "root-second-response",
      secondTurnAgentEvents.firstOrNull()?.content?.parts?.singleOrNull()?.text,
    )
  }

  /**
   * Counterpart of the previous test: when a sub-agent does NOT set `disallowTransferToParent` (the
   * default), the runner should keep routing follow-up turns to it after the parent's first
   * transfer.
   */
  @Test
  fun runAsync_subAgentWithAllowedTransfers_secondInvocationContinuesWithSubAgent() = runTest {
    val rootAgent =
      LlmAgent(
        name = "root",
        model = DummyModel("root-model") { flowOf(modelTransferToAgentResponse("sub")) },
        subAgents =
          listOf(
            LlmAgent(
              name = "sub",
              model =
                DummyModel.createSequential(
                  "sub-model",
                  listOf(
                    LlmResponse(content = modelMessage("sub-first-response")),
                    LlmResponse(content = modelMessage("sub-second-response")),
                  ),
                ),
            )
          ),
      )
    val runner = InMemoryRunner(agent = rootAgent)

    runner
      .runAsync(userId = "user1", sessionId = "session1", newMessage = userMessage("first"))
      .toList()
    val secondTurnEvents =
      runner
        .runAsync(userId = "user1", sessionId = "session1", newMessage = userMessage("second"))
        .toList()

    val secondTurnAgentEvents = secondTurnEvents.filter { it.author != Role.USER }
    assertEquals("sub", secondTurnAgentEvents.firstOrNull()?.author)
    assertEquals(
      "sub-second-response",
      secondTurnAgentEvents.firstOrNull()?.content?.parts?.singleOrNull()?.text,
    )
  }

  /**
   * Verifies the recursive transfer logic: root → mid → leaf in a single invocation, with the leaf
   * producing the final answer.
   */
  @Test
  fun runAsync_multiLevelAgentTransfer_eachLevelEmitsTransferAndLeafProducesFinalAnswer() =
    runTest {
      val rootAgent =
        LlmAgent(
          name = "root",
          model =
            DummyModel("root-model") {
              flowOf(modelTransferToAgentResponse("mid", id = "root_to_mid"))
            },
          subAgents =
            listOf(
              LlmAgent(
                name = "mid",
                model =
                  DummyModel("mid-model") {
                    flowOf(modelTransferToAgentResponse("leaf", id = "mid_to_leaf"))
                  },
                subAgents =
                  listOf(
                    LlmAgent(
                      name = "leaf",
                      model =
                        DummyModel("leaf-model") {
                          flowOf(LlmResponse(content = modelMessage("leaf-response")))
                        },
                    )
                  ),
              )
            ),
        )
      val runner = InMemoryRunner(agent = rootAgent)

      val events =
        runner
          .runAsync(userId = "user1", sessionId = "session1", newMessage = userMessage("hi"))
          .toList()

      val transferAuthors =
        events
          .filter { event -> event.functionCalls().any { it.name == TRANSFER_TO_AGENT_TOOL_NAME } }
          .map { it.author }
      assertEquals(listOf("root", "mid"), transferAuthors)
      val finalEvent = events.last { it.author != Role.USER }
      assertEquals("leaf", finalEvent.author)
      assertEquals("leaf-response", finalEvent.content?.parts?.singleOrNull()?.text)
    }

  /**
   * A [SequentialAgent] is a valid transfer target: once dispatched, it runs its children in
   * declared order using the same invocation context. Mirrors Python's `test_auto_to_sequential` in
   * `flows/llm_flows/test_agent_transfer.py`.
   */
  @Test
  fun runAsync_transferToSequentialAgent_executesAllChildrenInOrder() = runTest {
    val rootAgent =
      LlmAgent(
        name = "root",
        model = DummyModel("root-model") { flowOf(modelTransferToAgentResponse("sequential_sub")) },
        subAgents =
          listOf(
            SequentialAgent(
              name = "sequential_sub",
              description = "Runs first then second.",
              subAgents = listOf(textAgent("first", "first"), textAgent("second", "second")),
            )
          ),
      )
    val runner = InMemoryRunner(agent = rootAgent)

    val events =
      runner
        .runAsync(userId = "user1", sessionId = "session1", newMessage = userMessage("hi"))
        .toList()

    assertEquals(
      listOf(
        "root" to transferToAgentCallPart("sequential_sub"),
        "root" to TRANSFER_TO_AGENT_RESPONSE_PART,
        "first" to "first",
        "second" to "second",
      ),
      simplifyEvents(events),
    )
  }

  /**
   * A [LoopAgent] is also a valid transfer target. Mirrors Python's `test_auto_to_loop` in
   * `flows/llm_flows/test_agent_transfer.py`.
   */
  @Test
  fun runAsync_transferToLoopAgent_iteratesChildUntilMaxIterations() = runTest {
    var loopChildTurn = 0
    val rootAgent =
      LlmAgent(
        name = "root",
        model = DummyModel("root-model") { flowOf(modelTransferToAgentResponse("loop_sub")) },
        subAgents =
          listOf(
            LoopAgent(
              name = "loop_sub",
              description = "Loops its child.",
              maxIterations = 2,
              subAgents =
                listOf(
                  LlmAgent(
                    name = "loop_child",
                    description = "Loop child",
                    model =
                      DummyModel("loop-child-model") {
                        loopChildTurn++
                        flowOf(LlmResponse(content = modelMessage("iter-$loopChildTurn")))
                      },
                  )
                ),
            )
          ),
      )
    val runner = InMemoryRunner(agent = rootAgent)

    val events =
      runner
        .runAsync(userId = "user1", sessionId = "session1", newMessage = userMessage("hi"))
        .toList()

    assertEquals(
      listOf(
        "root" to transferToAgentCallPart("loop_sub"),
        "root" to TRANSFER_TO_AGENT_RESPONSE_PART,
        "loop_child" to "iter-1",
        "loop_child" to "iter-2",
      ),
      simplifyEvents(events),
    )
    assertEquals(2, loopChildTurn)
  }
}
