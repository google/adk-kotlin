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

import com.google.adk.kt.agents.BaseAgent
import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.LoopAgent
import com.google.adk.kt.agents.SequentialAgent
import com.google.adk.kt.events.Event
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.TRANSFER_TO_AGENT_RESPONSE_PART
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.modelTransferToAgentResponse
import com.google.adk.kt.testing.simplifyEvents
import com.google.adk.kt.testing.transferToAgentCallPart
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.tools.TransferToAgentTool.Companion.TRANSFER_TO_AGENT_TOOL_NAME
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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
   * End-to-end counterpart to `findAgentToRun_candidateUnderWorkflowAgent_fallsBackToRoot`: root
   * (an [LlmAgent]) transfers to a [SequentialAgent] whose child handles the first turn. On the
   * second turn the child cannot host the conversation -- its ancestor is a workflow agent -- so
   * routing falls back to the root. Mirrors Python ADK's
   * `tests/unittests/workflow/test_agent_transfer.py::test_auto_to_sequential`
   * (`is_resumable=False`).
   */
  @Test
  fun runAsync_transferToAgentUnderWorkflow_secondTurnFallsBackToRoot() = runTest {
    val rootModel =
      DummyModel.createSequential(
        "root-model",
        listOf(
          modelTransferToAgentResponse("seq"),
          LlmResponse(content = modelMessage("root turn 2")),
        ),
      )
    val rootAgent =
      LlmAgent(
        name = "root",
        model = rootModel,
        subAgents =
          listOf(
            SequentialAgent(
              name = "seq",
              subAgents =
                listOf(
                  LlmAgent(
                    name = "child",
                    model =
                      DummyModel("child-model") {
                        flowOf(LlmResponse(content = modelMessage("child response")))
                      },
                  )
                ),
            )
          ),
      )
    val runner = InMemoryRunner(agent = rootAgent)

    val turn1 =
      simplifyEvents(
        runner.runAsync(userId = "u", sessionId = "s", newMessage = userMessage("t1")).toList()
      )
    val turn2 =
      simplifyEvents(
        runner.runAsync(userId = "u", sessionId = "s", newMessage = userMessage("t2")).toList()
      )

    assertEquals(
      listOf(
        "root" to transferToAgentCallPart("seq"),
        "root" to TRANSFER_TO_AGENT_RESPONSE_PART,
        "child" to "child response",
      ),
      turn1,
    )
    // The child sits under a SequentialAgent, so it is not transferable; the next turn is the
    // root's.
    assertEquals(listOf("root" to "root turn 2"), turn2)
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
   *
   * Note on same-invocation vs. next-turn resume: a SequentialAgent that ends with a final-response
   * text event terminates the parent's per-turn loop (the parent observes a `finalResponse` as the
   * step's last event). Returning to the parent in that case happens on the *next* user turn via
   * `AbstractRunner.findAgentToRun`, not in the same invocation -- asserted below by the second
   * `runAsync` call. See [runAsync_transferToWorkflow_endsWithNonFinalEvent_returnsToRoot] for the
   * same-invocation resume scenario, which requires the last event to be non-final. In particular,
   * Kotlin's real [com.google.adk.kt.tools.ExitLoopTool] sets both `escalate` and
   * `skipSummarization` (matching Python 1.x/2.0), so a real `LoopAgent` + `exit_loop` produces a
   * final-response last event and does NOT trigger same-invocation resume -- diverging from Java
   * ADK. See [runAsync_transferToLoopAgent_realExitLoopTool_resumesOnNextTurn] for that path.
   */
  @Test
  fun runAsync_transferToSequentialAgent_executesAllChildrenInOrder() = runTest {
    val rootAgent =
      LlmAgent(
        name = "root",
        model =
          DummyModel.createSequential(
            "root-model",
            listOf(
              modelTransferToAgentResponse("sequential_sub"),
              LlmResponse(content = modelMessage("root-second-response")),
            ),
          ),
        subAgents =
          listOf(
            SequentialAgent(
              name = "sequential_sub",
              description = "Runs first then second.",
              // Match Python's test_auto_to_sequential: children are one-shot utility agents that
              // should not keep handling follow-up turns; the runner routes the next user turn back
              // to root via findAgentToRun.
              subAgents =
                listOf(
                  LlmAgent(
                    name = "first",
                    model =
                      DummyModel("first-model") {
                        flowOf(LlmResponse(content = modelMessage("first")))
                      },
                    disallowTransferToParent = true,
                    disallowTransferToPeers = true,
                  ),
                  LlmAgent(
                    name = "second",
                    model =
                      DummyModel("second-model") {
                        flowOf(LlmResponse(content = modelMessage("second")))
                      },
                    disallowTransferToParent = true,
                    disallowTransferToPeers = true,
                  ),
                ),
            )
          ),
      )
    val runner = InMemoryRunner(agent = rootAgent)

    // Turn 1: root transfers to the SequentialAgent, which runs `first` then `second`.
    // The workflow ends with a final-response text event, so root does NOT resume in the same
    // invocation. Same-invocation resume is exercised by
    // runAsync_transferToWorkflow_endsWithNonFinalEvent_returnsToRoot.
    val firstTurnEvents =
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
      simplifyEvents(firstTurnEvents),
    )

    // Turn 2: the next user message routes back to root via `findAgentToRun` (parity with
    // Python's test_auto_to_sequential asserting `[('root_agent', 'response3')]`).
    val secondTurnEvents =
      runner
        .runAsync(userId = "user1", sessionId = "session1", newMessage = userMessage("test2"))
        .toList()
    val secondTurnAgentEvents = secondTurnEvents.filter { it.author != Role.USER }
    assertEquals("root", secondTurnAgentEvents.firstOrNull()?.author)
    assertEquals(
      "root-second-response",
      secondTurnAgentEvents.firstOrNull()?.content?.parts?.singleOrNull()?.text,
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

  /**
   * Parity with Python's `test_auto_to_loop`, exercising the real [ExitLoopTool] exit path (as
   * opposed to `maxIterations`, covered by
   * [runAsync_transferToLoopAgent_iteratesChildUntilMaxIterations]).
   *
   * Because Kotlin's [ExitLoopTool] sets both `escalate` and `skipSummarization` (matching Python
   * 1.x/2.0), the loop's last event is a final response, so the parent (root) must NOT resume in
   * the same invocation -- root produces its follow-up on the *next* user turn via
   * `AbstractRunner.findAgentToRun`. This diverges from Java ADK, whose `ExitLoopTool` variant
   * omits `skipSummarization` and thus resumes root in the same invocation.
   *
   * Regression guard: if `skipSummarization` were ever dropped from Kotlin's [ExitLoopTool], Kotlin
   * would silently flip to Java's same-invocation-resume behavior and diverge from Python.
   */
  @Test
  fun runAsync_transferToLoopAgent_realExitLoopTool_resumesOnNextTurn() = runTest {
    var loopChildTurn = 0
    val loopChildAgent =
      LlmAgent(
        name = "loop_child",
        description = "Emits text on turn 1, calls exit_loop on turn 2.",
        model =
          DummyModel("loop-child-model") {
            loopChildTurn++
            if (loopChildTurn >= 2) {
              flowOf(
                LlmResponse(
                  content =
                    com.google.adk.kt.types.Content(
                      role = Role.MODEL,
                      parts =
                        listOf(
                          Part(
                            functionCall =
                              com.google.adk.kt.types.FunctionCall(
                                name = "exit_loop",
                                args = emptyMap(),
                                id = "exit-1",
                              )
                          )
                        ),
                    )
                )
              )
            } else {
              flowOf(LlmResponse(content = modelMessage("iter-$loopChildTurn")))
            }
          },
        tools = listOf(com.google.adk.kt.tools.ExitLoopTool()),
        disallowTransferToParent = true,
        disallowTransferToPeers = true,
      )
    val rootAgent =
      LlmAgent(
        name = "root",
        model =
          DummyModel.createSequential(
            "root-model",
            listOf(
              modelTransferToAgentResponse("loop_sub"),
              LlmResponse(content = modelMessage("root-second-response")),
            ),
          ),
        subAgents =
          listOf(
            LoopAgent(
              name = "loop_sub",
              description = "Loops its child; child exits via exit_loop.",
              maxIterations = 5,
              subAgents = listOf(loopChildAgent),
            )
          ),
      )
    val runner = InMemoryRunner(agent = rootAgent)

    // Turn 1: last event is authored by loop_child (the exit_loop function-response) and root
    // does NOT resume, because ExitLoopTool sets skipSummarization so the last event is final.
    val firstTurnEvents =
      runner
        .runAsync(userId = "user1", sessionId = "session1", newMessage = userMessage("hi"))
        .toList()
    val firstTurnAgentEvents = firstTurnEvents.filter { it.author != Role.USER }
    val firstTurnAuthors = firstTurnAgentEvents.map { it.author }
    assertEquals(
      "loop_child",
      firstTurnAgentEvents.last().author,
      "Root must not resume in the same invocation when the loop exits via ExitLoopTool " +
        "(final-response last event). Got authors=$firstTurnAuthors",
    )
    assertEquals(
      false,
      firstTurnAuthors.contains("root") && firstTurnAgentEvents.last().author == "root",
      "root should not produce a follow-up in the same invocation; got authors=$firstTurnAuthors",
    )
    // Loop must have iterated twice: once with text, once with exit_loop.
    assertEquals(2, loopChildTurn)

    // Turn 2: the next user message routes back to root via findAgentToRun -- parity with
    // Python's test_auto_to_loop asserting the follow-up turn is served by root.
    val secondTurnEvents =
      runner
        .runAsync(userId = "user1", sessionId = "session1", newMessage = userMessage("again"))
        .toList()
    val secondTurnAgentEvents = secondTurnEvents.filter { it.author != Role.USER }
    assertEquals("root", secondTurnAgentEvents.firstOrNull()?.author)
    assertEquals(
      "root-second-response",
      secondTurnAgentEvents.firstOrNull()?.content?.parts?.singleOrNull()?.text,
    )
  }

  /**
   * Regression test for agent transfer: when an auto (LLM) agent transfers into a workflow
   * sub-agent and that workflow finishes emitting a non-final last event (e.g. a function-response
   * carrying `escalate=true` without `skipSummarization`), control must return to the parent auto
   * agent within the same invocation so the parent can produce its follow-up response. Mirrors Java
   * ADK's `AgentTransferTest.testAutoToLoop` (where the loop ends via an `escalate`-only tool,
   * making the loop's last event non-final).
   *
   * The transferred-to agent here is a hand-rolled [BaseAgent] that emits a single non-final event
   * (a function-response with `escalate=true`). This isolates the parent-resume behavior from any
   * workflow-agent specifics.
   */
  @Test
  fun runAsync_transferToWorkflow_endsWithNonFinalEvent_returnsToRoot() = runTest {
    val workflow =
      object :
        BaseAgent(
          name = "workflow",
          description = "Emits one non-final event (function-response with escalate=true).",
        ) {
        override fun runAsyncImpl(context: InvocationContext): Flow<Event> = flow {
          emit(
            Event(
              author = name,
              invocationId = context.invocationId,
              content =
                Content(
                  role = Role.USER,
                  parts =
                    listOf(
                      Part(
                        functionResponse =
                          FunctionResponse(name = "escalate", id = "esc-1", response = emptyMap())
                      )
                    ),
                ),
              actions = EventActions(escalate = true),
            )
          )
        }
      }

    val rootAgent =
      LlmAgent(
        name = "root",
        model =
          DummyModel.createSequential(
            "root-model",
            listOf(
              modelTransferToAgentResponse("workflow"),
              LlmResponse(content = modelMessage("root-after-workflow")),
            ),
          ),
        subAgents = listOf(workflow),
      )
    val runner = InMemoryRunner(agent = rootAgent)

    val events =
      runner
        .runAsync(userId = "user1", sessionId = "session1", newMessage = userMessage("hi"))
        .toList()

    val agentEvents = events.filter { it.author != Role.USER }
    val lastAgentEvent = agentEvents.last()
    assertEquals(
      "root",
      lastAgentEvent.author,
      "Parent should resume after a workflow ends with a non-final event; got authors=${
        agentEvents.map { it.author }
      }",
    )
    assertEquals("root-after-workflow", lastAgentEvent.content?.parts?.singleOrNull()?.text)
  }

  /**
   * Parity with Python's `test_auto_to_auto_to_auto_forms_transfer_loop`: a chain of auto (LLM)
   * agents transfers back to the root agent within the same invocation, and the root then produces
   * its own follow-up response. On the next user turn, root continues.
   *
   * Tree:
   * - root (auto) -> sub_agent_1 (auto) -> sub_agent_2 (auto)
   *
   * The transfer target is looked up via `rootAgent.findAgent(name)` (see
   * [com.google.adk.kt.agents.LlmAgentTurn]'s `handleActions`), and `BaseAgent.findAgent` returns
   * `this` when `name == this.name`, so transferring to `"root"` from any descendant resolves
   * correctly.
   */
  @Test
  fun runAsync_autoToAutoToAuto_transferBackToRoot_resumesInSameInvocation() = runTest {
    val subAgent2 =
      LlmAgent(
        name = "sub_agent_2",
        model =
          DummyModel("sub-agent-2-model") {
            flowOf(modelTransferToAgentResponse("root", id = "sub2_to_root"))
          },
      )
    val subAgent1 =
      LlmAgent(
        name = "sub_agent_1",
        model =
          DummyModel("sub-agent-1-model") {
            flowOf(modelTransferToAgentResponse("sub_agent_2", id = "sub1_to_sub2"))
          },
        subAgents = listOf(subAgent2),
      )
    val rootAgent =
      LlmAgent(
        name = "root",
        model =
          DummyModel.createSequential(
            "root-model",
            listOf(
              modelTransferToAgentResponse("sub_agent_1", id = "root_to_sub1"),
              LlmResponse(content = modelMessage("root-after-cycle")),
              LlmResponse(content = modelMessage("root-next-turn")),
            ),
          ),
        subAgents = listOf(subAgent1),
      )
    val runner = InMemoryRunner(agent = rootAgent)

    // Turn 1: root -> sub_agent_1 -> sub_agent_2 -> root, then root produces "root-after-cycle"
    // in the SAME invocation. Before the isEndOfInvocation fix this final "root-after-cycle"
    // would never have been emitted.
    val firstTurnEvents =
      runner
        .runAsync(userId = "user1", sessionId = "session1", newMessage = userMessage("hi"))
        .toList()

    assertEquals(
      listOf(
        "root" to transferToAgentCallPart("sub_agent_1"),
        "root" to TRANSFER_TO_AGENT_RESPONSE_PART,
        "sub_agent_1" to transferToAgentCallPart("sub_agent_2"),
        "sub_agent_1" to TRANSFER_TO_AGENT_RESPONSE_PART,
        "sub_agent_2" to transferToAgentCallPart("root"),
        "sub_agent_2" to TRANSFER_TO_AGENT_RESPONSE_PART,
        "root" to "root-after-cycle",
      ),
      simplifyEvents(firstTurnEvents),
    )

    // Turn 2: the next user message routes back to root via findAgentToRun (root authored the
    // last final-response event of turn 1, so it is the natural continuation target).
    val secondTurnEvents =
      runner
        .runAsync(userId = "user1", sessionId = "session1", newMessage = userMessage("again"))
        .toList()
    val secondTurnAgentEvents = secondTurnEvents.filter { it.author != Role.USER }
    assertEquals("root", secondTurnAgentEvents.firstOrNull()?.author)
    assertEquals(
      "root-next-turn",
      secondTurnAgentEvents.firstOrNull()?.content?.parts?.singleOrNull()?.text,
    )
  }
}
