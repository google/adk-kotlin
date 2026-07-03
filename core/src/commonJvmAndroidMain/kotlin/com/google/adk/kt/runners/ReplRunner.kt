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
import com.google.adk.kt.events.Event
import com.google.adk.kt.events.ToolConfirmation
import com.google.adk.kt.tools.FunctionTool
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.common.annotations.VisibleForTesting
import java.util.Scanner
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking

/** A runner for Kotlin agents that provides a simple REPL for debugging. */
open class ReplRunner(agent: BaseAgent) : InMemoryRunner(agent) {
  private val userId = "user-" + UUID.randomUUID().toString()
  private val sessionId = "session-" + UUID.randomUUID().toString()

  /**
   * Synthetic `adk_request_confirmation` calls awaiting a user yes/no decision. Keyed by the
   * synthetic call id so we can send a wire-format `FunctionResponse` back to resume the paused
   * invocation. Resolved from events' [Event.functionCalls] in [handleEvent] whenever an event
   * arrives with [com.google.adk.kt.events.EventActions.requestedToolConfirmations] populated.
   */
  private var pendingConfirmations: Map<String, ToolConfirmation> = emptyMap()

  /**
   * A paused long-running call (other than a confirmation) awaiting a user-provided response --
   * e.g. `adk_request_input` or `get_user_choice`. When set, the next line the user types is sent
   * back as a wire-format `FunctionResponse` to resume the invocation instead of starting a new
   * turn. Resolved by [resolvePendingInputRequest] from a paused function-call event.
   */
  private var pendingInputRequest: PendingInputRequest? = null

  fun start() {
    val scanner = Scanner(System.`in`)
    println("Agent ${agent.name} is ready. Type 'exit' to quit.")

    while (true) {
      displayPrompt()
      if (!scanner.hasNextLine()) break
      val input = scanner.nextLine()
      if (shouldExit(input)) break

      runBlocking { execute(input) }
    }
    println("Exiting agent.")
  }

  private fun displayPrompt() {
    val prompt =
      when {
        pendingConfirmations.isNotEmpty() -> {
          val toolNames = pendingConfirmations.keys.joinToString(", ")
          "\n${agent.name} > Awaiting confirmation for '$toolNames'. Type 'yes' to confirm, 'no' to deny > "
        }
        pendingInputRequest != null -> "\n${agent.name} > Your response > "
        else -> "\nYou > "
      }
    print(prompt)
  }

  private fun shouldExit(input: String?): Boolean =
    input.isNullOrBlank() || input.trim().lowercase().let { it == "exit" || it == "quit" }

  private suspend fun execute(input: String) {
    val confirmations = pendingConfirmations
    val inputRequest = pendingInputRequest
    pendingConfirmations = emptyMap()
    pendingInputRequest = null

    when {
      confirmations.isNotEmpty() -> {
        val approved = input.trim().lowercase().let { it == "yes" || it == "y" }
        handleConfirmation(approved, confirmations)
      }
      inputRequest != null -> handleInputResponse(inputRequest, input)
      else -> handleNewMessage(input)
    }
  }

  /**
   * Resumes a paused long-running call (e.g. `adk_request_input`, `get_user_choice`) by sending the
   * user's line back as a wire-format `FunctionResponse` keyed by the paused call id. For a choice
   * with options, a numeric reply selects that option; otherwise the raw text is used.
   */
  private suspend fun handleInputResponse(request: PendingInputRequest, input: String) {
    val options = request.options
    val answer =
      if (options != null) input.trim().toIntOrNull()?.let { options.getOrNull(it - 1) } ?: input
      else input
    val resumeMessage =
      Content(
        role = Role.USER,
        parts =
          listOf(
            Part(
              functionResponse =
                FunctionResponse(
                  name = request.toolName,
                  id = request.callId,
                  response = mapOf(RESPONSE_VALUE_KEY to answer),
                )
            )
          ),
      )
    collectEvents(runAsync(userId, sessionId, invocationId = null, newMessage = resumeMessage))
  }

  /**
   * Resumes a paused HITL invocation by sending one user-authored `FunctionResponse` per pending
   * synthetic `adk_request_confirmation` call. The framework's
   * [com.google.adk.kt.processors.RequestConfirmationProcessor] picks up the responses and
   * re-executes the original tool calls.
   */
  private suspend fun handleConfirmation(
    approved: Boolean,
    confirmations: Map<String, ToolConfirmation>,
  ) {
    // The wire-format resume path expects exactly one FunctionResponse per pending synthetic call.
    // If there are multiple pending calls, send them in turn; the runner glues them to the original
    // invocation via Event id resolution.
    confirmations.forEach { (synthCallId, confirmation) ->
      val resumeMessage =
        Content(
          role = Role.USER,
          parts =
            listOf(
              Part(
                functionResponse =
                  FunctionResponse(
                    name = FunctionCall.REQUEST_CONFIRMATION_FUNCTION_CALL_NAME,
                    id = synthCallId,
                    response =
                      mapOf(
                        ToolConfirmation.CONFIRMED_KEY to approved,
                        ToolConfirmation.PAYLOAD_KEY to confirmation.payload,
                        ToolConfirmation.HINT_KEY to confirmation.hint,
                      ),
                  )
              )
            ),
        )
      collectEvents(runAsync(userId, sessionId, invocationId = null, newMessage = resumeMessage))
    }
  }

  private suspend fun handleNewMessage(input: String) {
    val newMessage = Content(role = Role.USER, parts = listOf(Part(text = input)))
    collectEvents(runAsync(userId, sessionId, invocationId = null, newMessage = newMessage))
  }

  private suspend fun collectEvents(eventFlow: Flow<Event>) {
    eventFlow.collect { event -> handleEvent(event) }
  }

  private fun handleEvent(event: Event) {
    val content = event.content ?: return
    val parts = content.parts
    if (parts.isNotEmpty()) {
      print("\n${event.author} > ")
    }

    val inputRequest = resolvePendingInputRequest(event)
    for (part in parts) {
      part.text?.let { text -> println(text) }
      part.functionCall?.let { fc ->
        if (inputRequest != null && fc.id == inputRequest.callId) {
          printInputRequest(fc)
        } else {
          println("calls tool: ${fc.name}")
        }
      }
      part.functionResponse?.let { fr ->
        val stdout = fr.response["stdout"] as? String
        if (!stdout.isNullOrBlank()) {
          println(stdout.trimEnd())
        }
        val stderr = fr.response["stderr"] as? String
        if (!stderr.isNullOrBlank()) {
          println("Error: " + stderr.trimEnd())
        }
        val error = fr.response[FunctionTool.ERROR_KEY] as? String
        if (error != null && shouldDisplayError(error)) {
          println("BaseTool Error: " + error.trimEnd())
        }
      }
    }
    val newPending = resolvePendingConfirmations(event)
    if (newPending.isNotEmpty()) {
      pendingConfirmations = newPending
    }
    if (inputRequest != null) {
      pendingInputRequest = inputRequest
    }
  }

  /** Renders a paused input request: its `message`, or a numbered list of its `options`. */
  private fun printInputRequest(functionCall: FunctionCall) {
    val message = functionCall.args["message"] as? String
    val options = functionCall.args["options"] as? List<*>
    if (message != null) {
      println(message)
    }
    if (options != null) {
      options.forEachIndexed { index, option -> println("  ${index + 1}. $option") }
    }
    if (message == null && options == null) {
      println("waiting for your input (${functionCall.name})")
    }
  }

  /** A paused long-running call awaiting a user-provided response (not a confirmation). */
  data class PendingInputRequest(
    val callId: String,
    val toolName: String,
    val options: List<String>?,
  )

  companion object {
    /** Key under which the user's reply is placed in the resume [FunctionResponse]. */
    const val RESPONSE_VALUE_KEY = "value"

    /**
     * Resolves a paused long-running call awaiting user input (e.g. `adk_request_input`,
     * `get_user_choice`) from an event. Returns the call whose id is in [Event.longRunningToolIds]
     * and is not a confirmation call (those have dedicated handling), or null if there is none. Its
     * `options` arg, when present, is captured so a numeric reply can select an option.
     */
    @VisibleForTesting
    fun resolvePendingInputRequest(event: Event): PendingInputRequest? {
      val call =
        event.functionCalls().firstOrNull { fc ->
          val id = fc.id
          id != null &&
            id in event.longRunningToolIds &&
            fc.name != FunctionCall.REQUEST_CONFIRMATION_FUNCTION_CALL_NAME
        } ?: return null
      val options = (call.args["options"] as? List<*>)?.map { it.toString() }
      return PendingInputRequest(callId = call.id!!, toolName = call.name, options = options)
    }

    /**
     * Returns true when the given non-null `error` string from a [FunctionResponse] should be
     * surfaced to the operator. Suppresses blank values and
     * [FunctionTool.CONFIRMATION_REQUIRED_ERROR], which is the transient placeholder that a
     * confirmation-gated [FunctionTool] returns on its first invocation - it is not a real failure
     * (the framework re-executes the tool once the user supplies a confirmation), and printing it
     * as "BaseTool Error" misleads the operator. Other error strings - including
     * [FunctionTool.REJECTED_ERROR] after the user explicitly types 'no' - are still surfaced.
     */
    @VisibleForTesting
    fun shouldDisplayError(error: String): Boolean =
      error.isNotBlank() && error != FunctionTool.CONFIRMATION_REQUIRED_ERROR

    /**
     * Resolves an event's [Event.actions.requestedToolConfirmations] into a map keyed by SYNTHETIC
     * `adk_request_confirmation` call id (the id the wire-format `FunctionResponse` resume path
     * expects), suitable to assign to [pendingConfirmations].
     *
     * Returns the empty map for events that do not carry synthetic confirmation calls. This is
     * important because [LlmAgentTurn] emits TWO events per pause - the synthetic-call event and
     * the underlying tool's response event - and both share the same [Event.actions] reference (so
     * both satisfy `requestedToolConfirmations.isNotEmpty()`). Only the synthetic-call event
     * actually contains the synth calls; the response event must not be allowed to wipe the pending
     * state.
     */
    @VisibleForTesting
    fun resolvePendingConfirmations(event: Event): Map<String, ToolConfirmation> {
      if (event.actions.requestedToolConfirmations.isEmpty()) return emptyMap()
      val synthCalls =
        event.functionCalls().filter {
          it.name == FunctionCall.REQUEST_CONFIRMATION_FUNCTION_CALL_NAME
        }
      if (synthCalls.isEmpty()) return emptyMap()
      // Pair each synthetic call's id with the matching ToolConfirmation (the wire-format resume
      // path expects FunctionResponses keyed by the SYNTHETIC call id, not the original tool call
      // id).
      return synthCalls
        .mapNotNull { synthCall ->
          val origId =
            (synthCall.args[FunctionCall.ORIGINAL_FUNCTION_CALL_KEY] as? Map<*, *>)?.get(
              FunctionCall.ID_KEY
            ) as? String ?: return@mapNotNull null
          val confirmation =
            event.actions.requestedToolConfirmations[origId] ?: return@mapNotNull null
          synthCall.id?.let { synthId -> synthId to confirmation }
        }
        .toMap()
    }
  }
}
