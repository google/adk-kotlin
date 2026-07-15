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
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.ParallelAgent
import com.google.adk.kt.agents.ResumabilityConfig
import com.google.adk.kt.agents.SequentialAgent
import com.google.adk.kt.agents.SequentialAgentState
import com.google.adk.kt.agents.TypedData
import com.google.adk.kt.apps.App
import com.google.adk.kt.events.Event
import com.google.adk.kt.events.ToolConfirmation
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.DummyTool
import com.google.adk.kt.testing.ResumableEvents.END_OF_AGENT
import com.google.adk.kt.testing.modelFunctionCallResponse
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.modelParallelFunctionCallsResponse
import com.google.adk.kt.testing.simplifyEvents
import com.google.adk.kt.testing.simplifyResumableEvents
import com.google.adk.kt.testing.userFunctionResponse
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.tools.FunctionTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.Part
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/**
 * End-to-end tool-confirmation flow for a [FunctionTool] declared with `requiresConfirmation =
 * true` through the public Runner. This is the recommended way to gate a tool on human
 * confirmation, mirroring Python ADK's `FunctionTool(require_confirmation=True)`.
 *
 * The contract this exercises:
 * 1. The model emits a `FunctionCall` for the secured tool.
 * 2. [FunctionTool.run] sees `requiresConfirmation = true` and no [ToolConfirmation], so it calls
 *    [ToolContext.requestConfirmation] and returns a placeholder error map without invoking the
 *    underlying [FunctionTool.execute]. The framework synthesizes the `adk_request_confirmation`
 *    event and pauses.
 * 3. The caller resumes by sending a user `FunctionResponse` for the synthetic call carrying
 *    `confirmed = true` (approval).
 * 4. On approval the original tool runs; the model is then re-prompted with the real response in
 *    history and produces its final text.
 */
class FunctionToolHitlIntegrationTest {

  /**
   * Pause half: the secured tool's [FunctionTool.execute] must not run; the framework must emit
   * exactly the placeholder function-response and the synthetic `adk_request_confirmation` event
   * carrying the original call info in `args[ORIGINAL_FUNCTION_CALL_KEY]`.
   */
  @Test
  fun runAsync_requiresConfirmation_pausesAndDoesNotInvokeExecute() = runTest {
    var executions = 0
    val secureTool = countingSecureTool { executions++ }
    val agent = singleCallAgent(secureTool)
    val runner = InMemoryRunner(agent = agent)

    val firstTurnEvents =
      runner
        .runAsync(
          userId = USER_ID,
          sessionId = SESSION_ID,
          newMessage = userMessage("transfer 100 dollars"),
        )
        .toList()

    assertEquals(0, executions)
    assertEquals(
      listOf(
        AGENT_NAME to modelOriginalCallPart(),
        AGENT_NAME to syntheticConfirmationCallPart(originalCallId = "secure_call_1"),
        AGENT_NAME to placeholderRequiresConfirmationResponsePart(),
      ),
      simplifyEvents(firstTurnEvents),
    )
  }

  /**
   * Resume half with `confirmed = true`: the framework re-invokes the secured tool exactly once,
   * the model is then re-prompted with the real response in history, and produces the final text.
   */
  @Test
  fun runAsync_requiresConfirmation_resumeOnApproval_runsToolOnceAndReachesFinalText() = runTest {
    var executions = 0
    var modelInvocations = 0
    val secureTool = countingSecureTool { executions++ }
    val agent = singleCallThenFinalAgent(secureTool, onModelInvoke = { modelInvocations++ })
    val runner = InMemoryRunner(agent = agent)

    val firstTurnEvents =
      runner
        .runAsync(
          userId = USER_ID,
          sessionId = SESSION_ID,
          newMessage = userMessage("transfer 100 dollars"),
        )
        .toList()
    val confirmationCallId = synthCallId(firstTurnEvents)

    val resumeEvents =
      runner
        .runAsync(
          userId = USER_ID,
          sessionId = SESSION_ID,
          newMessage =
            userFunctionResponse(
              name = FunctionCall.REQUEST_CONFIRMATION_FUNCTION_CALL_NAME,
              id = confirmationCallId,
              response = mapOf(ToolConfirmation.CONFIRMED_KEY to true),
            ),
        )
        .toList()

    assertEquals(1, executions)
    // First turn: 1) model emits tool call; the placeholder function-response is `skipSummarization
    // = true` so the per-turn loop exits without re-prompting the model on this turn (Python
    // parity). Resume turn: 2) model is re-prompted with the real tool result and produces the
    // final assistant text.
    assertEquals(2, modelInvocations)
    val realResponse = resumeEvents.firstOrNull { event ->
      event.functionResponses().any { it.name == SECURE_TOOL_NAME }
    }
    assertNotNull(realResponse)
    // The response is the (success-wrapped) execute() result and is *not* the rejection error.
    assertEquals(null, realResponse.functionResponses().single().response[FunctionTool.ERROR_KEY])
    val finalText =
      resumeEvents
        .lastOrNull {
          it.author == AGENT_NAME && it.content?.parts?.any { it.text != null } == true
        }
        ?.content
        ?.parts
        ?.singleOrNull()
        ?.text
    assertEquals("done", finalText)
  }

  /**
   * Resume half with `confirmed = false`: the framework re-invokes the tool but [FunctionTool.run]
   * short-circuits with the standard rejection error and the underlying [FunctionTool.execute] is
   * never called. The model is then re-prompted with the rejection in history and produces a final
   * response.
   */
  @Test
  fun runAsync_requiresConfirmation_resumeOnRejection_doesNotRunUnderlyingExecute() = runTest {
    var executions = 0
    var modelInvocations = 0
    val secureTool = countingSecureTool { executions++ }
    val agent = singleCallThenFinalAgent(secureTool, onModelInvoke = { modelInvocations++ })
    val runner = InMemoryRunner(agent = agent)

    val firstTurnEvents =
      runner
        .runAsync(
          userId = USER_ID,
          sessionId = SESSION_ID,
          newMessage = userMessage("transfer 100 dollars"),
        )
        .toList()
    val confirmationCallId = synthCallId(firstTurnEvents)

    val resumeEvents =
      runner
        .runAsync(
          userId = USER_ID,
          sessionId = SESSION_ID,
          newMessage =
            userFunctionResponse(
              name = FunctionCall.REQUEST_CONFIRMATION_FUNCTION_CALL_NAME,
              id = confirmationCallId,
              response = mapOf(ToolConfirmation.CONFIRMED_KEY to false),
            ),
        )
        .toList()

    assertEquals(0, executions)
    val realResponse = resumeEvents.firstOrNull { event ->
      event.functionResponses().any { it.name == SECURE_TOOL_NAME }
    }
    assertNotNull(realResponse)
    assertEquals(
      FunctionTool.REJECTED_ERROR,
      realResponse.functionResponses().single().response[FunctionTool.ERROR_KEY],
    )
    // Model is called twice: 1) emit the original tool call (request turn ends without re-prompt
    // because the placeholder is `skipSummarization = true`); 2) on resume with the rejection
    // error → final assistant response.
    assertEquals(2, modelInvocations)
  }

  /**
   * Parallel batch: one regular tool returns a completed function-response and one
   * confirmation-gated tool returns the placeholder "requires confirmation" error. Python parity:
   * the request turn terminates without re-prompting the model — the merged function-response event
   * carries `skipSummarization = true` (set by `FunctionTool` on the placeholder), so
   * `Event.isFinalResponse` is true and `LlmAgent.executeTurns` exits. The regular tool's completed
   * response is held in session history and is summarized on the **resume turn** along with the
   * real result for the gated tool.
   */
  @Test
  fun runAsync_parallelRegularAndConfirmationGatedTools_requestTurnTerminatesAndResumeSummarizes() =
    runTest {
      val regCallId = "reg_call_1"
      val secureCallId = "secure_call_1"
      val regResponse = mapOf("ok" to true)
      var modelInvocations = 0
      var regInvocations = 0
      var secureExecutions = 0
      val secureTool = countingSecureTool { secureExecutions++ }
      val agent =
        LlmAgent(
          name = AGENT_NAME,
          model =
            DummyModel("model") {
              modelInvocations++
              flowOf(
                if (modelInvocations == 1) {
                  modelParallelFunctionCallsResponse(
                    FunctionCall(name = "regular_tool", args = mapOf("k" to "v"), id = regCallId),
                    FunctionCall(
                      name = SECURE_TOOL_NAME,
                      args = mapOf("amount" to 100),
                      id = secureCallId,
                    ),
                  )
                } else {
                  LlmResponse(content = modelMessage("done"))
                }
              )
            },
          tools =
            listOf(
              secureTool,
              DummyTool(
                name = "regular_tool",
                onRun = { _, _ ->
                  regInvocations++
                  regResponse
                },
              ),
            ),
        )
      val runner = InMemoryRunner(agent = agent)

      val firstTurnEvents =
        runner
          .runAsync(userId = USER_ID, sessionId = SESSION_ID, newMessage = userMessage("go"))
          .toList()

      // Regular tool ran exactly once; the gated tool was NOT executed (still pending
      // confirmation).
      assertEquals(1, regInvocations)
      assertEquals(0, secureExecutions)
      // Request turn: model invoked exactly once (to emit the parallel calls). The
      // `skipSummarization
      // = true` placeholder terminates the per-turn loop without a second model call. The completed
      // regular-tool response is in session history and will be summarized on resume.
      assertEquals(1, modelInvocations)
      // No final assistant text on the request turn — the run is paused on the synthetic
      // `adk_request_confirmation` long-running call.
      val firstTurnFinalText =
        firstTurnEvents
          .lastOrNull {
            it.author == AGENT_NAME && it.content?.parts?.any { p -> p.text != null } == true
          }
          ?.content
          ?.parts
          ?.singleOrNull()
          ?.text
      assertNull(firstTurnFinalText)

      // Resume with approval for the gated tool. The model is re-prompted with both completed
      // function-responses in history (the regular tool's real response from the request turn AND
      // the gated tool's real result produced now), and produces the final assistant text.
      val confirmationCallId = synthCallId(firstTurnEvents)
      val resumeEvents =
        runner
          .runAsync(
            userId = USER_ID,
            sessionId = SESSION_ID,
            newMessage =
              userFunctionResponse(
                name = FunctionCall.REQUEST_CONFIRMATION_FUNCTION_CALL_NAME,
                id = confirmationCallId,
                response = mapOf(ToolConfirmation.CONFIRMED_KEY to true),
              ),
          )
          .toList()

      assertEquals(1, secureExecutions)
      assertEquals(2, modelInvocations)
      val resumeFinalText =
        resumeEvents
          .lastOrNull {
            it.author == AGENT_NAME && it.content?.parts?.any { p -> p.text != null } == true
          }
          ?.content
          ?.parts
          ?.singleOrNull()
          ?.text
      assertEquals("done", resumeFinalText)
    }

  /**
   * Sequential composite agent under a resumable app. Ported from Python ADK 1.x
   * `runners/test_run_tool_confirmation.py::TestHITLConfirmationFlowWithSequentialAgentAndResumableApp`.
   *
   * `root_agent` is a [SequentialAgent] of `agent1` (a confirmation-gated tool) and `agent2` (no
   * tools). Turn 1 must pause at `agent1`'s confirmation request: the `SequentialAgent` checkpoints
   * `current_sub_agent = agent1`, `agent1` emits its call/confirmation/placeholder, and `agent2`
   * never starts. On resume (approval) `agent1`'s tool runs once and it finishes, then the
   * `SequentialAgent` checkpoints `current_sub_agent = agent2` and runs `agent2` to completion.
   */
  @Test
  fun runAsync_sequentialAgent_resumableConfirmation_pausesAtFirstThenContinuesToSecond() =
    runTest {
      var executions = 0
      val secureTool = countingSecureTool { executions++ }
      // One model shared by both sub-agents (mirrors Python's shared MockModel). Responses are
      // consumed in order across all calls: agent1's tool call, agent1's post-tool text, then
      // agent2's text.
      val model =
        DummyModel.createSequential(
          "model",
          listOf(
            modelFunctionCallResponse(
              SECURE_TOOL_NAME,
              args = mapOf("amount" to 100),
              id = "secure_call_1",
            ),
            LlmResponse(content = modelMessage("agent1 done")),
            LlmResponse(content = modelMessage("agent2 done")),
          ),
        )
      val agent1 = LlmAgent(name = "agent1", model = model, tools = listOf(secureTool))
      val agent2 = LlmAgent(name = "agent2", model = model)
      val rootAgent = SequentialAgent(name = "root_agent", subAgents = listOf(agent1, agent2))
      val runner = resumableRunner(rootAgent)

      val firstTurnEvents =
        runner
          .runAsync(
            userId = USER_ID,
            sessionId = SESSION_ID,
            newMessage = userMessage("run the sequence"),
          )
          .toList()

      // Step 1: pause at agent1's confirmation. agent2 never starts; no intermediate llm summary.
      assertEquals(0, executions)
      assertEquals(
        listOf(
          "root_agent" to SequentialAgentState("agent1").toStateValue(),
          "agent1" to modelOriginalCallPart(),
          "agent1" to syntheticConfirmationCallPart(originalCallId = "secure_call_1"),
          "agent1" to placeholderRequiresConfirmationResponsePart(),
        ),
        simplifyResumableEvents(firstTurnEvents),
      )

      // Step 2: resume with approval. agent1's tool runs once and it finishes, then the sequence
      // advances to agent2 and runs it to completion.
      val confirmationCallId = synthCallId(firstTurnEvents)
      val resumeEvents =
        runner
          .runAsync(
            userId = USER_ID,
            sessionId = SESSION_ID,
            newMessage =
              userFunctionResponse(
                name = FunctionCall.REQUEST_CONFIRMATION_FUNCTION_CALL_NAME,
                id = confirmationCallId,
                response = mapOf(ToolConfirmation.CONFIRMED_KEY to true),
              ),
          )
          .toList()

      assertEquals(1, executions)
      assertEquals(
        listOf(
          "agent1" to secureToolResponsePart(),
          "agent1" to "agent1 done",
          "agent1" to END_OF_AGENT,
          "root_agent" to SequentialAgentState("agent2").toStateValue(),
          "agent2" to "agent2 done",
          "agent2" to END_OF_AGENT,
          "root_agent" to END_OF_AGENT,
        ),
        simplifyResumableEvents(resumeEvents),
      )
    }

  /**
   * Parallel composite agent under a resumable app. Ported from Python ADK 1.x
   * `runners/test_run_tool_confirmation.py::TestHITLConfirmationFlowWithParallelAgentAndResumableApp`.
   *
   * `root_agent` is a [ParallelAgent] of `agent1` and `agent2`, each with its own
   * confirmation-gated tool. Each branch must pause independently on its own confirmation request.
   * Resuming one branch (keyed by its confirmation call id) completes only that branch while the
   * sibling stays paused; resuming the second completes it and ends the parallel agent. Branches
   * are asserted in isolation because parallel event interleaving is non-deterministic.
   */
  @Test
  fun runAsync_parallelAgent_resumableConfirmation_pausesAndResumesEachBranchIndependently() =
    runTest {
      var executions = 0
      // Each branch runs concurrently, so each agent gets its own model (independent call counter).
      val agent1 =
        LlmAgent(
          name = "agent1",
          model = secureCallThenFinalModel(callId = "call_1", finalText = "agent1 done"),
          tools = listOf(countingSecureTool { executions++ }),
        )
      val agent2 =
        LlmAgent(
          name = "agent2",
          model = secureCallThenFinalModel(callId = "call_2", finalText = "agent2 done"),
          tools = listOf(countingSecureTool { executions++ }),
        )
      val rootAgent = ParallelAgent(name = "root_agent", subAgents = listOf(agent1, agent2))
      val runner = resumableRunner(rootAgent)

      val firstTurnEvents =
        runner
          .runAsync(
            userId = USER_ID,
            sessionId = SESSION_ID,
            newMessage = userMessage("run in parallel"),
          )
          .toList()

      // Step 1: both branches pause on their confirmation requests; neither executes.
      assertEquals(0, executions)
      // The root branch only carries the parallel agent's (empty) state checkpoint.
      assertEquals(
        listOf("root_agent" to TypedData.MapValue(emptyMap())),
        simplifyResumableEvents(firstTurnEvents.filter { it.branch == null }),
      )
      assertEquals(
        listOf(
          "agent1" to modelOriginalCallPart(),
          "agent1" to syntheticConfirmationCallPart(originalCallId = "call_1"),
          "agent1" to placeholderRequiresConfirmationResponsePart(),
        ),
        simplifyResumableEvents(firstTurnEvents.filter { it.branch == "root_agent.agent1" }),
      )
      assertEquals(
        listOf(
          "agent2" to modelOriginalCallPart(),
          "agent2" to syntheticConfirmationCallPart(originalCallId = "call_2"),
          "agent2" to placeholderRequiresConfirmationResponsePart(),
        ),
        simplifyResumableEvents(firstTurnEvents.filter { it.branch == "root_agent.agent2" }),
      )

      val invocationId = firstTurnEvents.first { it.branch == "root_agent.agent1" }.invocationId

      // Step 2: resume agent1 only. It completes; agent2 stays paused; the root is not yet final.
      val resume1Events =
        runner
          .runAsync(
            userId = USER_ID,
            sessionId = SESSION_ID,
            invocationId = invocationId,
            newMessage =
              userFunctionResponse(
                name = FunctionCall.REQUEST_CONFIRMATION_FUNCTION_CALL_NAME,
                id = synthCallIdForBranch(firstTurnEvents, "root_agent.agent1"),
                response = mapOf(ToolConfirmation.CONFIRMED_KEY to true),
              ),
          )
          .toList()

      assertEquals(1, executions)
      assertTrue(resume1Events.none { it.branch == null }, "root must not be final yet")
      assertTrue(resume1Events.none { it.branch == "root_agent.agent2" }, "agent2 must stay paused")
      assertEquals(
        listOf(
          "agent1" to secureToolResponsePart(),
          "agent1" to "agent1 done",
          "agent1" to END_OF_AGENT,
        ),
        simplifyResumableEvents(resume1Events.filter { it.branch == "root_agent.agent1" }),
      )

      // Step 3: resume agent2. It completes and the parallel root then ends.
      val resume2Events =
        runner
          .runAsync(
            userId = USER_ID,
            sessionId = SESSION_ID,
            invocationId = invocationId,
            newMessage =
              userFunctionResponse(
                name = FunctionCall.REQUEST_CONFIRMATION_FUNCTION_CALL_NAME,
                id = synthCallIdForBranch(firstTurnEvents, "root_agent.agent2"),
                response = mapOf(ToolConfirmation.CONFIRMED_KEY to true),
              ),
          )
          .toList()

      assertEquals(2, executions)
      assertEquals(
        listOf(
          "agent2" to secureToolResponsePart(),
          "agent2" to "agent2 done",
          "agent2" to END_OF_AGENT,
          "root_agent" to END_OF_AGENT,
        ),
        simplifyResumableEvents(resume2Events),
      )
    }

  /**
   * Custom confirmation payload schema round-trip. Ported from Python ADK 1.x
   * `runners/test_run_tool_confirmation.py::TestHITLConfirmationFlowWithCustomPayloadSchema`.
   *
   * Unlike the boolean `requiresConfirmation` gate (which uses a fixed hint and no payload), a tool
   * may call [ToolContext.requestConfirmation] directly with a structured `payload` describing the
   * confirmation schema. This test verifies (1) the emitted `adk_request_confirmation` call carries
   * that payload schema, and (2) on resume the caller-supplied payload is plumbed back into
   * [ToolContext.toolConfirmation] so the tool can read it.
   */
  @Test
  fun runAsync_customPayloadSchemaConfirmation_roundTripsPayloadIntoResumedTool() = runTest {
    val agent =
      LlmAgent(
        name = AGENT_NAME,
        model =
          DummyModel.createSequential(
            "model",
            listOf(
              modelFunctionCallResponse(CUSTOM_TOOL_NAME, id = "custom_call_1"),
              LlmResponse(content = modelMessage("after tool call")),
              LlmResponse(content = modelMessage("after final tool call")),
            ),
          ),
        tools = listOf(CustomSchemaConfirmationTool()),
      )
    val runner = InMemoryRunner(agent = agent)

    val firstTurnEvents =
      runner
        .runAsync(
          userId = USER_ID,
          sessionId = SESSION_ID,
          newMessage = userMessage("test user query"),
        )
        .toList()

    // The confirmation request advertises the tool's custom (default-valued) payload schema.
    val confirmationCall =
      firstTurnEvents
        .flatMap { it.functionCalls() }
        .single { it.name == FunctionCall.REQUEST_CONFIRMATION_FUNCTION_CALL_NAME }
    assertEquals(defaultSchemaPayload(), embeddedRequestedPayload(confirmationCall))

    // Resume with approval and a populated custom payload; the tool must echo it back.
    val customPayload =
      mapOf(
        "test_custom_payload" to
          mapOf("int_field" to 123, "str_field" to "test_str", "bool_field" to true)
      )
    val resumeEvents =
      runner
        .runAsync(
          userId = USER_ID,
          sessionId = SESSION_ID,
          newMessage =
            userFunctionResponse(
              name = FunctionCall.REQUEST_CONFIRMATION_FUNCTION_CALL_NAME,
              id = confirmationCall.id ?: error("confirmation call must have an id"),
              response =
                mapOf(
                  ToolConfirmation.CONFIRMED_KEY to true,
                  ToolConfirmation.PAYLOAD_KEY to customPayload,
                ),
            ),
        )
        .toList()

    val toolResponse = resumeEvents.firstNotNullOf { event ->
      event.functionResponses().firstOrNull { it.name == CUSTOM_TOOL_NAME }
    }
    assertEquals(
      mapOf("result" to "confirmed=true", "custom_payload" to customPayload),
      toolResponse.response,
    )
  }

  // -- Fixtures ----------------------------------------------------------------------------------

  /**
   * A confirmation-gated [FunctionTool] that records every successful execution via [onExecute].
   */
  private class CountingSecureTool(private val onExecute: () -> Unit) :
    FunctionTool(SECURE_TOOL_NAME, "Securely transfers money.", requiresConfirmation = true) {
    override fun declaration() = null

    override suspend fun execute(context: ToolContext, args: Map<String, Any>): Any {
      onExecute()
      return mapOf("status" to "ok")
    }
  }

  private fun countingSecureTool(onExecute: () -> Unit): FunctionTool =
    CountingSecureTool(onExecute)

  /**
   * A tool that drives its own confirmation via [ToolContext.requestConfirmation] with a structured
   * [defaultSchemaPayload] (rather than the boolean `requiresConfirmation` gate). On resume it
   * echoes the caller-supplied [ToolConfirmation] back to the model.
   */
  private class CustomSchemaConfirmationTool :
    FunctionTool(CUSTOM_TOOL_NAME, "Requests confirmation with a custom payload schema.") {
    override fun declaration() = null

    override suspend fun execute(context: ToolContext, args: Map<String, Any>): Any {
      val confirmation = context.toolConfirmation
      if (confirmation == null) {
        context.requestConfirmation(
          hint = "test hint for request_confirmation with custom payload schema",
          payload = defaultSchemaPayload(),
        )
        return mapOf(FunctionTool.ERROR_KEY to FunctionTool.CONFIRMATION_REQUIRED_ERROR)
      }
      return mapOf(
        "result" to "confirmed=${confirmation.confirmed}",
        "custom_payload" to confirmation.payload,
      )
    }
  }

  /** Reads the `payload` embedded under a synthetic confirmation call's `toolConfirmation` args. */
  private fun embeddedRequestedPayload(call: FunctionCall): Any? {
    val confirmation = call.args[FunctionCall.TOOL_CONFIRMATION_KEY] as? Map<*, *> ?: return null
    return confirmation[ToolConfirmation.PAYLOAD_KEY]
  }

  /** Agent whose model emits exactly one secure-tool call and never produces a final response. */
  private fun singleCallAgent(secureTool: FunctionTool): LlmAgent =
    LlmAgent(
      name = AGENT_NAME,
      model =
        DummyModel("model") {
          flowOf(
            modelFunctionCallResponse(
              SECURE_TOOL_NAME,
              args = mapOf("amount" to 100),
              id = "secure_call_1",
            )
          )
        },
      tools = listOf(secureTool),
    )

  /**
   * Agent whose model emits a secure-tool call on its first invocation and a final text response
   * (`"done"`) on every subsequent invocation. Each invocation increments [onModelInvoke].
   */
  private fun singleCallThenFinalAgent(
    secureTool: FunctionTool,
    onModelInvoke: () -> Unit,
  ): LlmAgent {
    var invocations = 0
    return LlmAgent(
      name = AGENT_NAME,
      model =
        DummyModel("model") {
          invocations++
          onModelInvoke()
          flowOf(
            if (invocations == 1)
              modelFunctionCallResponse(
                SECURE_TOOL_NAME,
                args = mapOf("amount" to 100),
                id = "secure_call_1",
              )
            else LlmResponse(content = modelMessage("done"))
          )
        },
      tools = listOf(secureTool),
    )
  }

  /**
   * The model's original [FunctionCall] for the secured tool, as the simplified comparison sees it
   * (function-call ids are stripped by [simplifyEvents]).
   */
  private fun modelOriginalCallPart(): Part =
    Part(functionCall = FunctionCall(name = SECURE_TOOL_NAME, args = mapOf("amount" to 100)))

  /** The placeholder `FunctionResponse` Part that [FunctionTool.run] returns on the pause turn. */
  private fun placeholderRequiresConfirmationResponsePart(): Part =
    Part(
      functionResponse =
        FunctionResponse(
          name = SECURE_TOOL_NAME,
          response = mapOf(FunctionTool.ERROR_KEY to FunctionTool.CONFIRMATION_REQUIRED_ERROR),
        )
    )

  /**
   * The synthetic `adk_request_confirmation` `FunctionCall` Part the framework emits to request
   * approval. The synthetic call gets a fresh id so it is dropped from the simplified comparison by
   * [simplifyEvents]; only the embedded original call info needs to match.
   */
  private fun syntheticConfirmationCallPart(originalCallId: String): Part =
    Part(
      functionCall =
        FunctionCall(
          name = FunctionCall.REQUEST_CONFIRMATION_FUNCTION_CALL_NAME,
          args =
            mapOf(
              FunctionCall.ORIGINAL_FUNCTION_CALL_KEY to
                mapOf(
                  FunctionCall.NAME_KEY to SECURE_TOOL_NAME,
                  FunctionCall.ARGS_KEY to mapOf("amount" to 100),
                  FunctionCall.ID_KEY to originalCallId,
                ),
              FunctionCall.TOOL_CONFIRMATION_KEY to
                mapOf(
                  ToolConfirmation.CONFIRMED_KEY to false,
                  ToolConfirmation.PAYLOAD_KEY to null,
                  ToolConfirmation.HINT_KEY to
                    "Please approve or reject the tool call $SECURE_TOOL_NAME() by responding " +
                      "with a FunctionResponse with an expected ToolConfirmation payload.",
                ),
            ),
        )
    )

  private fun synthCallId(events: List<Event>): String =
    events
      .flatMap { it.functionCalls() }
      .firstOrNull { it.name == FunctionCall.REQUEST_CONFIRMATION_FUNCTION_CALL_NAME }
      ?.id ?: error("first turn must produce an adk_request_confirmation call with an id")

  /** The `adk_request_confirmation` call id emitted on the given parallel [branch]. */
  private fun synthCallIdForBranch(events: List<Event>, branch: String): String =
    events
      .filter { it.branch == branch }
      .flatMap { it.functionCalls() }
      .firstOrNull { it.name == FunctionCall.REQUEST_CONFIRMATION_FUNCTION_CALL_NAME }
      ?.id ?: error("branch $branch must produce an adk_request_confirmation call with an id")

  /**
   * A model that emits the secured tool call (with the given deterministic [callId]) on its first
   * invocation and [finalText] on every subsequent invocation - i.e. agent1/agent2's behaviour
   * across the pause turn and the post-approval resume.
   */
  private fun secureCallThenFinalModel(callId: String, finalText: String): DummyModel =
    DummyModel.createSequential(
      "model-$callId",
      listOf(
        modelFunctionCallResponse(SECURE_TOOL_NAME, args = mapOf("amount" to 100), id = callId),
        LlmResponse(content = modelMessage(finalText)),
      ),
    )

  /**
   * The real (success-wrapped) `FunctionResponse` Part the secured tool returns once approved, as
   * the simplified comparison sees it (function-response ids are stripped).
   */
  private fun secureToolResponsePart(): Part =
    Part(
      functionResponse =
        FunctionResponse(name = SECURE_TOOL_NAME, response = mapOf("status" to "ok"))
    )

  /** An [InMemoryRunner] for [rootAgent] with resumability enabled, so confirmations pause. */
  private fun resumableRunner(rootAgent: BaseAgent): InMemoryRunner =
    InMemoryRunner(
      app =
        App(
          appName = "test_app",
          rootAgent = rootAgent,
          resumabilityConfig = ResumabilityConfig(isResumable = true),
        )
    )

  private companion object {
    const val SECURE_TOOL_NAME = "transfer_money"
    const val CUSTOM_TOOL_NAME = "request_custom_payload"
    const val AGENT_NAME = "agent"
    const val USER_ID = "u"
    const val SESSION_ID = "s"

    /** The default (empty-valued) custom payload schema the tool advertises when requesting. */
    fun defaultSchemaPayload(): Map<String, Any> =
      mapOf(
        "test_custom_payload" to mapOf("int_field" to 0, "str_field" to "", "bool_field" to false)
      )
  }
}
