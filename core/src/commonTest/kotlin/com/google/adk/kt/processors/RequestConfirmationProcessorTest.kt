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
import com.google.adk.kt.events.Event
import com.google.adk.kt.events.ToolConfirmation
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.DummyTool
import com.google.adk.kt.testing.testSession
import com.google.adk.kt.testing.userFunctionResponse
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RequestConfirmationProcessorTest {

  @Test
  fun process_noConfirmations_returnsUnmodifiedRequest() = runTest {
    val (_, context) = newConfirmationContext()
    val request = LlmRequest()

    val result = RequestConfirmationProcessor().process(context, request)

    assertEquals(request, result)
  }

  @Test
  fun process_lastUserEventHasNoFunctionResponses_returnsUnmodifiedRequest() = runTest {
    // Mirrors Python's `test_request_confirmation_processor_no_function_responses`: when the most
    // recent user event is plain text (no FunctionResponses at all), the processor must not look
    // any further back in history for a stale confirmation. It must return the request as-is.
    val (session, context) = newConfirmationContext()
    session.events.add(userEvent(context.invocationId, userMessage("hello")))

    val emittedEvents = collectEmittedEvents(context)

    assertEquals(emptyList(), emittedEvents)
  }

  @Test
  fun process_lastUserEventHasUnrelatedFunctionResponses_returnsUnmodifiedRequest() = runTest {
    // Mirrors Python's `test_request_confirmation_processor_no_confirmation_function_response`:
    // the latest user event carries a FunctionResponse but for a different function (e.g. a
    // long-running tool result), not for `adk_request_confirmation`. The processor must not act.
    val (session, context) = newConfirmationContext()
    session.events.add(
      userEvent(
        context.invocationId,
        userFunctionResponse(
          name = "other_function",
          id = "other_1",
          response = mapOf("ok" to true),
        ),
      )
    )

    val emittedEvents = collectEmittedEvents(context)

    assertEquals(emptyList(), emittedEvents)
  }

  @Test
  fun process_approvedConfirmation_executesToolAndEmitsResponseEvent() = runTest {
    val toolName = "risky_tool"
    val (session, context) =
      newConfirmationContext(
        tools = listOf(DummyTool(toolName) { _, _ -> mapOf("status" to "executed") })
      )
    val originalCallId = "orig_1"
    session.events.add(
      agentEvent(
        context.invocationId,
        synthConfirmationCallPart(
          synthId = "synth_1",
          originalToolName = toolName,
          originalCallId = originalCallId,
          originalArgs = mapOf("param" to "value"),
        ),
      )
    )
    session.events.add(approvalEvent(context.invocationId, synthId = "synth_1"))

    val emittedEvents = collectEmittedEvents(context)

    val response = singleEmittedFunctionResponse(emittedEvents)
    assertEquals(toolName, response.name)
    assertEquals(originalCallId, response.id)
    assertEquals(mapOf("status" to "executed"), response.response)
  }

  @Test
  fun process_priorTurnPlaceholderResponseWithSameId_doesNotSuppressConfirmationOnResume() =
    runTest {
      // Locks the dedup window scope (Step 3): the pause turn's placeholder FunctionResponse
      // (which carries the original tool's call id) must NOT be treated as evidence the tool
      // already ran. Without the fix, `executedToolIds` would contain `originalCallId`, the
      // resume would silently no-op, and `emittedEvents` would be empty.
      val toolName = "risky_tool"
      var toolRuns = 0
      val (session, context) =
        newConfirmationContext(
          tools =
            listOf(
              DummyTool(toolName) { _, _ ->
                toolRuns++
                mapOf("status" to "executed")
              }
            )
        )
      val originalCallId = "orig_1"
      session.events.add(
        agentEvent(
          context.invocationId,
          Part(
            functionCall =
              FunctionCall(name = toolName, args = mapOf("param" to "value"), id = originalCallId)
          ),
        )
      )
      session.events.add(
        agentEvent(
          context.invocationId,
          Part(
            functionResponse =
              FunctionResponse(
                name = toolName,
                id = originalCallId,
                response = mapOf("error" to "This tool call requires confirmation, please approve."),
              )
          ),
        )
      )
      session.events.add(
        agentEvent(
          context.invocationId,
          synthConfirmationCallPart(
            synthId = "synth_1",
            originalToolName = toolName,
            originalCallId = originalCallId,
          ),
        )
      )
      session.events.add(approvalEvent(context.invocationId, synthId = "synth_1"))

      val emittedEvents = collectEmittedEvents(context)

      assertEquals(1, toolRuns)
      val response = singleEmittedFunctionResponse(emittedEvents)
      assertEquals(toolName, response.name)
      assertEquals(originalCallId, response.id)
      assertEquals(mapOf("status" to "executed"), response.response)
    }

  @Test
  fun process_confirmationFromPriorInvocation_executesTool() = runTest {
    // Locks the scan-all-session-events behaviour: a fresh runAsync(...) lands the resume in a
    // brand new invocation, so the synthetic confirmation FunctionCall (and its original-call
    // payload) live in a *prior* invocation. The processor must still find them.
    val toolName = "risky_tool"
    val priorInvocationId = "inv-prior"
    val resumeInvocationId = "inv-resume"
    val originalCallId = "orig_1"
    val agent =
      LlmAgent(
        name = "test",
        model = DummyModel("gemini"),
        tools = listOf(DummyTool(toolName) { _, _ -> mapOf("status" to "executed") }),
      )
    val session = testSession()
    session.events.add(
      agentEvent(
        priorInvocationId,
        synthConfirmationCallPart(
          synthId = "synth_1",
          originalToolName = toolName,
          originalCallId = originalCallId,
        ),
      )
    )
    session.events.add(approvalEvent(resumeInvocationId, synthId = "synth_1"))
    val context =
      InvocationContext(
        session = session,
        runConfig = null,
        agent = agent,
        invocationId = resumeInvocationId,
      )

    val emittedEvents = collectEmittedEvents(context)

    val response = singleEmittedFunctionResponse(emittedEvents)
    assertEquals(originalCallId, response.id)
  }

  @Test
  fun process_userConfirmationInDefaultBranch_resolvesChildBranchRequest() = runTest {
    // Mirrors Python's
    // `test_request_confirmation_processor_finds_user_confirmation_in_default_branch`:
    // the agent runs in "child_branch" and the confirmation request lives there, but the user's
    // confirmation arrives on the default (null) branch (e.g. a top-level resume call). The
    // processor scans the current branch OR the default branch, so it must still resolve the
    // confirmation and re-execute the tool.
    val toolName = "risky_tool"
    var toolRuns = 0
    val agent =
      LlmAgent(
        name = "test",
        model = DummyModel("gemini"),
        tools =
          listOf(
            DummyTool(toolName) { _, _ ->
              toolRuns++
              mapOf("status" to "executed")
            }
          ),
      )
    val session = testSession()
    val context =
      InvocationContext(session = session, runConfig = null, agent = agent, branch = "child_branch")
    val originalCallId = "orig_1"
    // The confirmation request is emitted on the agent's child branch.
    session.events.add(
      Event(
        author = "test",
        branch = "child_branch",
        content =
          Content(
            role = Role.MODEL,
            parts =
              listOf(
                synthConfirmationCallPart(
                  synthId = "synth_1",
                  originalToolName = toolName,
                  originalCallId = originalCallId,
                )
              ),
          ),
        invocationId = context.invocationId,
      )
    )
    // The user's confirmation arrives on the default (null) branch.
    session.events.add(
      Event(
        author = Role.USER,
        branch = null,
        content =
          userFunctionResponse(
            name = FunctionCall.REQUEST_CONFIRMATION_FUNCTION_CALL_NAME,
            id = "synth_1",
            response = mapOf(ToolConfirmation.CONFIRMED_KEY to true),
          ),
        invocationId = context.invocationId,
      )
    )

    val emittedEvents = collectEmittedEvents(context)

    assertEquals(1, toolRuns)
    val response = singleEmittedFunctionResponse(emittedEvents)
    assertEquals(toolName, response.name)
    assertEquals(originalCallId, response.id)
  }

  @Test
  fun process_realResponseAfterConfirmation_skipsReExecution() = runTest {
    // Locks the dedup window's other side: a real FunctionResponse that arrives AFTER the user
    // confirmation (e.g. because this processor already re-executed once on this turn) must
    // suppress a second re-execution. Without dedup the same tool would run twice.
    val toolName = "risky_tool"
    var toolRuns = 0
    val (session, context) =
      newConfirmationContext(
        tools =
          listOf(
            DummyTool(toolName) { _, _ ->
              toolRuns++
              mapOf("status" to "executed")
            }
          )
      )
    val originalCallId = "orig_1"
    session.events.add(
      agentEvent(
        context.invocationId,
        synthConfirmationCallPart(
          synthId = "synth_1",
          originalToolName = toolName,
          originalCallId = originalCallId,
        ),
      )
    )
    session.events.add(approvalEvent(context.invocationId, synthId = "synth_1"))
    // A real response for the original tool already exists AFTER the user confirmation.
    session.events.add(
      agentEvent(
        context.invocationId,
        Part(
          functionResponse =
            FunctionResponse(
              name = toolName,
              id = originalCallId,
              response = mapOf("status" to "executed"),
            )
        ),
      )
    )

    val emittedEvents = collectEmittedEvents(context)

    assertEquals(0, toolRuns)
    assertEquals(emptyList(), emittedEvents)
  }

  @Test
  fun process_multipleConfirmationsInSameUserEvent_executesAllConfirmedTools() = runTest {
    // Locks fan-out behaviour: the latest user event may carry confirmations for several pending
    // tool calls in one go (the framework can pause on multiple parallel calls in a single turn).
    // Each confirmed tool must be re-executed.
    val toolNameA = "tool_a"
    val toolNameB = "tool_b"
    val toolRuns = mutableListOf<String>()
    val (session, context) =
      newConfirmationContext(
        tools =
          listOf(
            DummyTool(toolNameA) { _, _ ->
              toolRuns.add(toolNameA)
              mapOf("ok" to "a")
            },
            DummyTool(toolNameB) { _, _ ->
              toolRuns.add(toolNameB)
              mapOf("ok" to "b")
            },
          )
      )
    session.events.add(
      agentEvent(
        context.invocationId,
        synthConfirmationCallPart(
          synthId = "synth_a",
          originalToolName = toolNameA,
          originalCallId = "orig_a",
        ),
        synthConfirmationCallPart(
          synthId = "synth_b",
          originalToolName = toolNameB,
          originalCallId = "orig_b",
        ),
      )
    )
    session.events.add(
      Event(
        author = Role.USER,
        content =
          Content(
            role = Role.USER,
            parts =
              listOf(
                approvalResponsePart(synthId = "synth_a"),
                approvalResponsePart(synthId = "synth_b"),
              ),
          ),
        invocationId = context.invocationId,
      )
    )

    val emittedEvents = collectEmittedEvents(context)

    assertEquals(setOf(toolNameA, toolNameB), toolRuns.toSet())
    assertEquals(1, emittedEvents.size)
    val responseNames =
      emittedEvents.single().content?.parts?.mapNotNull { it.functionResponse?.name }?.toSet()
    assertEquals(setOf(toolNameA, toolNameB), responseNames)
  }

  @Test
  fun process_oneToolAlreadyExecutedAfterConfirmation_skipsItButRunsTheOther() = runTest {
    // Locks partial dedup: when a turn carries confirmations for two pending calls and one of
    // them already has a real `FunctionResponse` after the confirmation event (an earlier pass
    // through this processor handled it), only the OTHER call must be re-executed.
    val toolNameA = "tool_a"
    val toolNameB = "tool_b"
    val toolRuns = mutableListOf<String>()
    val (session, context) =
      newConfirmationContext(
        tools =
          listOf(
            DummyTool(toolNameA) { _, _ ->
              toolRuns.add(toolNameA)
              mapOf("ok" to "a")
            },
            DummyTool(toolNameB) { _, _ ->
              toolRuns.add(toolNameB)
              mapOf("ok" to "b")
            },
          )
      )
    session.events.add(
      agentEvent(
        context.invocationId,
        synthConfirmationCallPart(
          synthId = "synth_a",
          originalToolName = toolNameA,
          originalCallId = "orig_a",
        ),
        synthConfirmationCallPart(
          synthId = "synth_b",
          originalToolName = toolNameB,
          originalCallId = "orig_b",
        ),
      )
    )
    session.events.add(
      Event(
        author = Role.USER,
        content =
          Content(
            role = Role.USER,
            parts =
              listOf(
                approvalResponsePart(synthId = "synth_a"),
                approvalResponsePart(synthId = "synth_b"),
              ),
          ),
        invocationId = context.invocationId,
      )
    )
    // Tool A already has a real response AFTER the confirmation (an earlier processor pass
    // re-executed it).
    session.events.add(
      agentEvent(
        context.invocationId,
        Part(
          functionResponse =
            FunctionResponse(name = toolNameA, id = "orig_a", response = mapOf("ok" to "a"))
        ),
      )
    )

    val emittedEvents = collectEmittedEvents(context)

    assertEquals(listOf(toolNameB), toolRuns)
    val response = singleEmittedFunctionResponse(emittedEvents)
    assertEquals(toolNameB, response.name)
  }

  @Test
  fun process_rejectedConfirmation_isForwardedToReExecution() = runTest {
    // Mirrors Python's `test_request_confirmation_processor_tool_not_confirmed`: even when the
    // user replies with `confirmed = false`, the processor must still forward the call to
    // re-execution (passing the rejection through `toolConfirmation`). A confirmation-gated
    // FunctionTool is responsible for converting the rejection into its own error response;
    // silently dropping rejections here would let the agent re-prompt the user every turn for a
    // tool the user has already denied.
    val toolName = "risky_tool"
    var toolRuns = 0
    val (session, context) =
      newConfirmationContext(
        tools =
          listOf(
            DummyTool(toolName) { _, _ ->
              toolRuns++
              mapOf("error" to "rejected")
            }
          )
      )
    val originalCallId = "orig_1"
    session.events.add(
      agentEvent(
        context.invocationId,
        synthConfirmationCallPart(
          synthId = "synth_1",
          originalToolName = toolName,
          originalCallId = originalCallId,
        ),
      )
    )
    session.events.add(
      userEvent(
        context.invocationId,
        userFunctionResponse(
          name = FunctionCall.REQUEST_CONFIRMATION_FUNCTION_CALL_NAME,
          id = "synth_1",
          response = mapOf(ToolConfirmation.CONFIRMED_KEY to false),
        ),
      )
    )

    val emittedEvents = collectEmittedEvents(context)

    assertEquals(1, toolRuns)
    val response = singleEmittedFunctionResponse(emittedEvents)
    assertEquals(toolName, response.name)
    assertEquals(originalCallId, response.id)
  }

  @Test
  fun process_wrappedConfirmationResponse_executesTool() = runTest {
    // Locks the ADK client/API wire-format compatibility: the client may send the
    // ToolConfirmation as a JSON string under a single "response" key (mirroring the
    // Java/Python decoders). The processor must unwrap it; otherwise the confirmation is
    // dropped and the gated tool is never resumed (b/522629309).
    val toolName = "risky_tool"
    var toolRuns = 0
    val (session, context) =
      newConfirmationContext(
        tools =
          listOf(
            DummyTool(toolName) { _, _ ->
              toolRuns++
              mapOf("status" to "executed")
            }
          )
      )
    val originalCallId = "orig_1"
    session.events.add(
      agentEvent(
        context.invocationId,
        synthConfirmationCallPart(
          synthId = "synth_1",
          originalToolName = toolName,
          originalCallId = originalCallId,
        ),
      )
    )
    session.events.add(
      userEvent(
        context.invocationId,
        userFunctionResponse(
          name = FunctionCall.REQUEST_CONFIRMATION_FUNCTION_CALL_NAME,
          id = "synth_1",
          response =
            mapOf("response" to """{"confirmed":true,"hint":"approved","payload":{"k":"v"}}"""),
        ),
      )
    )

    val emittedEvents = collectEmittedEvents(context)

    assertEquals(1, toolRuns)
    val response = singleEmittedFunctionResponse(emittedEvents)
    assertEquals(toolName, response.name)
    assertEquals(originalCallId, response.id)
  }

  @Test
  fun process_wrappedRejection_isForwardedToReExecution() = runTest {
    val toolName = "risky_tool"
    var toolRuns = 0
    val (session, context) =
      newConfirmationContext(
        tools =
          listOf(
            DummyTool(toolName) { _, _ ->
              toolRuns++
              mapOf("error" to "rejected")
            }
          )
      )
    val originalCallId = "orig_1"
    session.events.add(
      agentEvent(
        context.invocationId,
        synthConfirmationCallPart(
          synthId = "synth_1",
          originalToolName = toolName,
          originalCallId = originalCallId,
        ),
      )
    )
    session.events.add(
      userEvent(
        context.invocationId,
        userFunctionResponse(
          name = FunctionCall.REQUEST_CONFIRMATION_FUNCTION_CALL_NAME,
          id = "synth_1",
          response = mapOf("response" to """{"confirmed":false}"""),
        ),
      )
    )

    val emittedEvents = collectEmittedEvents(context)

    assertEquals(1, toolRuns)
    val response = singleEmittedFunctionResponse(emittedEvents)
    assertEquals(originalCallId, response.id)
  }

  @Test
  fun process_wrappedInvalidJson_dropsConfirmation() = runTest {
    val toolName = "risky_tool"
    var toolRuns = 0
    val (session, context) =
      newConfirmationContext(
        tools =
          listOf(
            DummyTool(toolName) { _, _ ->
              toolRuns++
              mapOf("status" to "executed")
            }
          )
      )
    session.events.add(
      agentEvent(
        context.invocationId,
        synthConfirmationCallPart(
          synthId = "synth_1",
          originalToolName = toolName,
          originalCallId = "orig_1",
        ),
      )
    )
    session.events.add(
      userEvent(
        context.invocationId,
        userFunctionResponse(
          name = FunctionCall.REQUEST_CONFIRMATION_FUNCTION_CALL_NAME,
          id = "synth_1",
          response = mapOf("response" to "not-valid-json"),
        ),
      )
    )

    val emittedEvents = collectEmittedEvents(context)

    assertEquals(0, toolRuns)
    assertEquals(emptyList(), emittedEvents)
  }

  // -- Helpers -----------------------------------------------------------------------------------

  /**
   * Allocates an `LlmAgent` named "test" with [tools] (defaults to none) and a fresh in-memory
   * [Session] / [InvocationContext]. Returns the session (so tests can `.events.add(...)`) and the
   * context (so tests can read its `invocationId` and pass it to the processor).
   */
  private fun newConfirmationContext(
    tools: List<DummyTool> = emptyList()
  ): Pair<Session, InvocationContext> {
    val agent = LlmAgent(name = "test", model = DummyModel("gemini"), tools = tools)
    val session = testSession()
    val context = InvocationContext(session = session, runConfig = null, agent = agent)
    return session to context
  }

  /** An agent-authored [Event] with the given [parts] in a `model`-role [Content]. */
  private fun agentEvent(invocationId: String, vararg parts: Part): Event =
    Event(
      author = "test",
      content = Content(role = Role.MODEL, parts = parts.toList()),
      invocationId = invocationId,
    )

  /** A user-authored [Event] wrapping the given [content]. */
  private fun userEvent(invocationId: String, content: Content): Event =
    Event(author = Role.USER, content = content, invocationId = invocationId)

  /**
   * The synthetic `adk_request_confirmation` `FunctionCall` Part the framework emits to pause for
   * approval. The synth call's `args[ORIGINAL_FUNCTION_CALL_KEY]` carries the `(name, args, id)` of
   * the original call so that on resume the processor can recover and re-execute it.
   */
  private fun synthConfirmationCallPart(
    synthId: String,
    originalToolName: String,
    originalCallId: String,
    originalArgs: Map<String, Any> = mapOf("param" to "value"),
  ): Part =
    Part(
      functionCall =
        FunctionCall(
          name = FunctionCall.REQUEST_CONFIRMATION_FUNCTION_CALL_NAME,
          id = synthId,
          args =
            mapOf(
              FunctionCall.ORIGINAL_FUNCTION_CALL_KEY to
                mapOf(
                  FunctionCall.NAME_KEY to originalToolName,
                  FunctionCall.ARGS_KEY to originalArgs,
                  FunctionCall.ID_KEY to originalCallId,
                )
            ),
        )
    )

  /**
   * A user-authored [Event] approving the synthetic confirmation call with id [synthId]. Equivalent
   * to wrapping [approvalResponsePart] in a single-part user event.
   */
  private fun approvalEvent(invocationId: String, synthId: String): Event =
    userEvent(
      invocationId,
      userFunctionResponse(
        name = FunctionCall.REQUEST_CONFIRMATION_FUNCTION_CALL_NAME,
        id = synthId,
        response = mapOf(ToolConfirmation.CONFIRMED_KEY to true),
      ),
    )

  /**
   * The `FunctionResponse` Part approving the synthetic confirmation call with id [synthId]. Used
   * when constructing a multi-part user event containing several approvals at once.
   */
  private fun approvalResponsePart(synthId: String): Part =
    Part(
      functionResponse =
        FunctionResponse(
          name = FunctionCall.REQUEST_CONFIRMATION_FUNCTION_CALL_NAME,
          id = synthId,
          response = mapOf(ToolConfirmation.CONFIRMED_KEY to true),
        )
    )

  /**
   * Runs the processor against [context] and returns the events it emitted (via the `emitEvent`
   * callback). The request itself is unobserved.
   */
  private suspend fun collectEmittedEvents(context: InvocationContext): List<Event> {
    val events = mutableListOf<Event>()
    val unused =
      RequestConfirmationProcessor().process(context, LlmRequest()) { event -> events.add(event) }
    return events
  }

  /** Asserts there is exactly one emitted event with one [FunctionResponse] part and returns it. */
  private fun singleEmittedFunctionResponse(emittedEvents: List<Event>): FunctionResponse {
    assertEquals(1, emittedEvents.size)
    return emittedEvents.single().content?.parts?.singleOrNull()?.functionResponse
      ?: error("expected a single FunctionResponse part in the emitted event")
  }
}
