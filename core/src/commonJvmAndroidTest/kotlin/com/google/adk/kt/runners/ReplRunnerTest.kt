/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.adk.kt.runners

import com.google.adk.kt.events.Event
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.events.ToolConfirmation
import com.google.adk.kt.tools.FunctionTool
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Unit tests for [ReplRunner]'s pure helpers:
 * - [ReplRunner.resolvePendingConfirmations] - the synth-call \u2192 pending-confirmation
 *   resolution logic that powers the runner's HITL prompt.
 * - [ReplRunner.shouldDisplayError] - the predicate that decides whether a tool's `error` response
 *   field is a real failure worth surfacing to the operator or a transient confirmation-gate
 *   placeholder that should be suppressed.
 */
@RunWith(JUnit4::class)
class ReplRunnerTest {

  @Test
  fun resolvePendingConfirmations_syntheticEvent_pairsSynthIdWithConfirmation() {
    val originalCallId = "orig-1"
    val synthCallId = "synth-1"
    val confirmation = ToolConfirmation(confirmed = false, hint = "approve?")
    val synthEvent =
      syntheticConfirmationEvent(originalCallId, synthCallId, confirmation = confirmation)

    val resolved = ReplRunner.resolvePendingConfirmations(synthEvent)

    assertThat(resolved).containsExactly(synthCallId, confirmation)
  }

  @Test
  fun resolvePendingConfirmations_responseEventWithSharedActions_returnsEmpty() {
    val originalCallId = "orig-1"
    val confirmation = ToolConfirmation(confirmed = false, hint = "approve?")
    // The action object that LlmAgentTurn shares between the synth event and the response event:
    // both events satisfy `requestedToolConfirmations.isNotEmpty()`.
    val sharedActions =
      EventActions(requestedToolConfirmations = mutableMapOf(originalCallId to confirmation))
    val responseEvent = toolResponseEvent("transfer_money", originalCallId, sharedActions)

    val resolved = ReplRunner.resolvePendingConfirmations(responseEvent)

    assertThat(resolved).isEmpty()
  }

  @Test
  fun resolvePendingConfirmations_eventWithNoRequestedConfirmations_returnsEmpty() {
    val plainEvent =
      Event(
        author = AGENT,
        content =
          Content(
            role = Role.MODEL,
            parts =
              listOf(Part(functionCall = FunctionCall(name = "scan_planet", args = emptyMap()))),
          ),
      )

    val resolved = ReplRunner.resolvePendingConfirmations(plainEvent)

    assertThat(resolved).isEmpty()
  }

  @Test
  fun resolvePendingConfirmations_synthEventWithUnknownOriginalId_skipsThatCall() {
    val confirmation = ToolConfirmation(confirmed = false, hint = "approve?")
    // The synthetic call references original id "orig-1" but actions only carries "orig-OTHER":
    // the pairing should fail for this synth call and the resolved map should be empty.
    val synthEvent =
      Event(
        author = AGENT,
        content =
          Content(
            role = Role.MODEL,
            parts = listOf(syntheticConfirmationCallPart("orig-1", "synth-1")),
          ),
        actions =
          EventActions(requestedToolConfirmations = mutableMapOf("orig-OTHER" to confirmation)),
      )

    val resolved = ReplRunner.resolvePendingConfirmations(synthEvent)

    assertThat(resolved).isEmpty()
  }

  @Test
  fun resolvePendingInputRequest_getUserChoice_capturesOptions() {
    val event =
      longRunningCallEvent(
        "get_user_choice",
        "choice-1",
        mapOf("options" to listOf("red", "green", "blue")),
      )

    val resolved = ReplRunner.resolvePendingInputRequest(event)

    assertThat(resolved)
      .isEqualTo(
        ReplRunner.PendingInputRequest(
          "choice-1",
          "get_user_choice",
          listOf("red", "green", "blue"),
        )
      )
  }

  @Test
  fun resolvePendingInputRequest_requestInput_hasNoOptions() {
    val event =
      longRunningCallEvent("adk_request_input", "input-1", mapOf("message" to "What is your name?"))

    val resolved = ReplRunner.resolvePendingInputRequest(event)

    assertThat(resolved)
      .isEqualTo(ReplRunner.PendingInputRequest("input-1", "adk_request_input", null))
  }

  @Test
  fun resolvePendingInputRequest_confirmationCall_returnsNull() {
    // Confirmation calls have dedicated handling and must not be treated as input requests.
    val event =
      longRunningCallEvent(
        FunctionCall.REQUEST_CONFIRMATION_FUNCTION_CALL_NAME,
        "synth-1",
        emptyMap(),
      )

    assertThat(ReplRunner.resolvePendingInputRequest(event)).isNull()
  }

  @Test
  fun resolvePendingInputRequest_callNotLongRunning_returnsNull() {
    // A function call whose id is absent from longRunningToolIds is not a pause.
    val event =
      Event(
        author = AGENT,
        content =
          Content(
            role = Role.MODEL,
            parts = listOf(Part(functionCall = FunctionCall(name = "get_user_choice", id = "c1"))),
          ),
      )

    assertThat(ReplRunner.resolvePendingInputRequest(event)).isNull()
  }

  @Test
  fun shouldDisplayError_realError_returnsTrue() {
    assertThat(ReplRunner.shouldDisplayError("Something actually went wrong")).isTrue()
  }

  @Test
  fun shouldDisplayError_rejectedError_returnsTrue() {
    // After the user types 'no', the framework returns REJECTED_ERROR. The runner SHOULD echo it
    // so the user has explicit confirmation that their rejection took effect.
    assertThat(ReplRunner.shouldDisplayError(FunctionTool.REJECTED_ERROR)).isTrue()
  }

  @Test
  fun shouldDisplayError_confirmationRequiredPlaceholder_returnsFalse() {
    // The transient placeholder a confirmation-gated tool returns on its first invocation. The
    // framework re-executes the tool once the user supplies a confirmation; printing this as
    // "BaseTool Error" misleads the operator into thinking something failed.
    assertThat(ReplRunner.shouldDisplayError(FunctionTool.CONFIRMATION_REQUIRED_ERROR)).isFalse()
  }

  @Test
  fun shouldDisplayError_emptyString_returnsFalse() {
    assertThat(ReplRunner.shouldDisplayError("")).isFalse()
  }

  @Test
  fun shouldDisplayError_whitespaceOnly_returnsFalse() {
    assertThat(ReplRunner.shouldDisplayError("   ")).isFalse()
  }

  private fun longRunningCallEvent(name: String, callId: String, args: Map<String, Any>): Event =
    Event(
      author = AGENT,
      content =
        Content(
          role = Role.MODEL,
          parts = listOf(Part(functionCall = FunctionCall(name = name, id = callId, args = args))),
        ),
      longRunningToolIds = setOf(callId),
    )

  private fun syntheticConfirmationEvent(
    originalCallId: String,
    synthCallId: String,
    confirmation: ToolConfirmation,
  ): Event =
    Event(
      author = AGENT,
      content =
        Content(
          role = Role.MODEL,
          parts = listOf(syntheticConfirmationCallPart(originalCallId, synthCallId)),
        ),
      actions =
        EventActions(requestedToolConfirmations = mutableMapOf(originalCallId to confirmation)),
    )

  private fun syntheticConfirmationCallPart(originalCallId: String, synthCallId: String): Part =
    Part(
      functionCall =
        FunctionCall(
          name = FunctionCall.REQUEST_CONFIRMATION_FUNCTION_CALL_NAME,
          id = synthCallId,
          args =
            mapOf(
              FunctionCall.ORIGINAL_FUNCTION_CALL_KEY to
                mapOf(
                  FunctionCall.NAME_KEY to "transfer_money",
                  FunctionCall.ARGS_KEY to mapOf("amount" to 100),
                  FunctionCall.ID_KEY to originalCallId,
                )
            ),
        )
    )

  private fun toolResponseEvent(toolName: String, callId: String, actions: EventActions): Event =
    Event(
      author = AGENT,
      content =
        Content(
          role = Role.USER,
          parts =
            listOf(
              Part(
                functionResponse =
                  FunctionResponse(
                    name = toolName,
                    id = callId,
                    response =
                      mapOf(
                        "error" to "This tool call requires confirmation, please approve or reject."
                      ),
                  )
              )
            ),
        ),
      actions = actions,
    )

  private companion object {
    const val AGENT = "agent"
  }
}
