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
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.events.Event
import com.google.adk.kt.events.ToolConfirmation
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.serialization.adkJson
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * On every turn, looks at the most recent user event for `FunctionResponse`s named
 * [FunctionCall.REQUEST_CONFIRMATION_FUNCTION_CALL_NAME]. For each one, parses the
 * [ToolConfirmation] out of the response, finds the original `FunctionCall` that triggered the
 * pause, and re-executes that tool with the confirmation plumbed into
 * [com.google.adk.kt.tools.ToolContext.toolConfirmation] (which is how a confirmation-gated
 * [com.google.adk.kt.tools.FunctionTool] sees it and proceeds past its `requiresConfirmation`
 * gate).
 *
 * Skips re-execution if the original tool's `FunctionResponse` already exists later in the session
 * - that means an earlier pass through this processor on the same turn already handled it.
 *
 * Mirrors Python ADK's `flows/llm_flows/request_confirmation.py`.
 */
@OptIn(FrameworkInternalApi::class)
internal class RequestConfirmationProcessor : LlmRequestProcessor {

  override suspend fun process(
    context: InvocationContext,
    request: LlmRequest,
    emitEvent: suspend (Event) -> Unit,
  ): LlmRequest {
    require(context.agent is LlmAgent) { "RequestConfirmationProcessor requires an LlmAgent." }
    val agent = context.agent

    // Scan ALL session events (not invocation-scoped) so the resume works even when the
    // confirmation `runAsync(...)` lands in a fresh invocation.
    val events = context.getEvents(currentInvocation = false, currentBranch = true)
    val (lastUserIndex, lastUserEvent) =
      events.withIndex().findLast { it.value.author == Role.USER } ?: return request
    val responses = lastUserEvent.functionResponses()
    // No function responses in the latest user event, meaning no confirmations either.
    if (responses.isEmpty()) return request

    val confirmationsBySynthId =
      responses
        .filter { it.name == FunctionCall.REQUEST_CONFIRMATION_FUNCTION_CALL_NAME }
        .mapNotNull { response ->
          val id = response.id ?: return@mapNotNull null
          parseToolConfirmation(response.response)?.let { id to it }
        }
        .toMap()
    // No tool confirmations in the latest user event.
    if (confirmationsBySynthId.isEmpty()) return request

    val pendingByOriginalId =
      events
        .asSequence()
        .flatMap { it.functionCalls().asSequence() }
        .mapNotNull { synth ->
          val confirmation = synth.id?.let(confirmationsBySynthId::get) ?: return@mapNotNull null
          val original = synth.embeddedOriginalCall() ?: return@mapNotNull null
          val originalId = original.id ?: return@mapNotNull null
          ConfirmedCall(originalId, original, confirmation)
        }
        .associateBy { it.originalId }
    if (pendingByOriginalId.isEmpty()) return request

    // Drop calls already re-executed on this same turn (a previous pass through this processor
    // already produced a real `FunctionResponse` for them after the confirmation event).
    val alreadyExecutedIds =
      events
        .asSequence()
        .drop(lastUserIndex + 1)
        .flatMap { it.functionResponses().asSequence() }
        .mapNotNull { it.id }
        .toSet()
    // Find "truly pending" FunctionCalls that haven't been re-executed yet.
    val pending = pendingByOriginalId.filterKeys { it !in alreadyExecutedIds }
    if (pending.isEmpty()) return request

    // Re-execute the original calls with the confirmations, and emit the resulting events.
    context
      .handleFunctionCalls(
        functionCalls = pending.values.map { it.originalCall },
        tools = agent.tools.associateBy { it.name },
        filters = pending.keys,
        toolConfirmations = pending.mapValues { it.value.confirmation },
      )
      ?.let { emitEvent(it) }

    return request
  }

  private data class ConfirmedCall(
    val originalId: String,
    val originalCall: FunctionCall,
    val confirmation: ToolConfirmation,
  )

  /**
   * Recovers the original `FunctionCall` that this synthetic `adk_request_confirmation` call was
   * created to gate, from the synth call's `args[`[FunctionCall.ORIGINAL_FUNCTION_CALL_KEY]`]`
   * payload.
   */
  private fun FunctionCall.embeddedOriginalCall(): FunctionCall? {
    val raw = args[FunctionCall.ORIGINAL_FUNCTION_CALL_KEY] as? Map<*, *> ?: return null
    val name = raw[FunctionCall.NAME_KEY] as? String ?: return null
    val id = raw[FunctionCall.ID_KEY] as? String ?: return null
    @Suppress("UNCHECKED_CAST")
    val args = (raw[FunctionCall.ARGS_KEY] as? Map<String, Any?>) ?: emptyMap()
    return FunctionCall(name = name, args = args, id = id)
  }

  private fun parseToolConfirmation(response: Map<String, Any?>?): ToolConfirmation? {
    if (response == null) return null
    // Wire format A (ADK client/API wrapper): a single "response" key whose value is the
    // ToolConfirmation encoded as a JSON string. Mirrors the Java/Python decoders.
    val unwrapped =
      if (response.size == 1) {
        (response[WRAPPED_RESPONSE_KEY] as? String)?.let { jsonString ->
          try {
            val element = adkJson.parseToJsonElement(jsonString) as? JsonObject ?: return null
            adkJson.decodeFromJsonElement<ToolConfirmation>(element)
          } catch (e: SerializationException) {
            null
          } catch (e: IllegalArgumentException) {
            null
          }
        }
      } else {
        null
      }
    if (unwrapped != null) return unwrapped
    // Wire format B (direct): the response map already IS the ToolConfirmation.
    val confirmed = response[ToolConfirmation.CONFIRMED_KEY] as? Boolean ?: return null
    return ToolConfirmation(
      confirmed = confirmed,
      payload = response[ToolConfirmation.PAYLOAD_KEY],
      hint = response[ToolConfirmation.HINT_KEY] as? String,
    )
  }

  private companion object {
    /**
     * The single key the ADK client/API wrapper uses to nest the ToolConfirmation JSON. Matches the
     * Java/Python decoders' `"response"` key.
     */
    const val WRAPPED_RESPONSE_KEY = "response"
  }
}

/**
 * Encodes pending tool-confirmation requests into a synthetic agent-authored event whose
 * [Event.content] carries one [FunctionCall] per request, named
 * [FunctionCall.REQUEST_CONFIRMATION_FUNCTION_CALL_NAME]. The synthetic call ids are added to
 * [Event.longRunningToolIds] so the runner pauses, and the original call args + the
 * [ToolConfirmation] details are embedded in the synthetic call's `args` map under
 * [FunctionCall.ORIGINAL_FUNCTION_CALL_KEY] and [FunctionCall.TOOL_CONFIRMATION_KEY].
 *
 * The decoder counterpart lives in [RequestConfirmationProcessor]: on the resume turn it reads the
 * user's [com.google.adk.kt.types.FunctionResponse] for the synthetic call, recovers the original
 * [FunctionCall] from the embedded args, and re-executes the underlying tool with the supplied
 * [ToolConfirmation].
 *
 * Returns `null` if [functionResponseEvent] does not carry any
 * [com.google.adk.kt.events.EventActions.requestedToolConfirmations] or if none of those ids
 * correspond to function calls in [functionCallEvent].
 */
internal fun generateRequestConfirmationEvent(
  invocationContext: InvocationContext,
  functionCallEvent: Event,
  functionResponseEvent: Event,
): Event? {
  if (functionResponseEvent.actions.requestedToolConfirmations.isEmpty()) return null

  val parts = mutableListOf<Part>()
  val longRunningToolIds = mutableSetOf<String>()
  val functionCalls = functionCallEvent.functionCalls()

  for ((functionCallId, toolConfirmation) in
    functionResponseEvent.actions.requestedToolConfirmations) {
    val originalFunctionCall = functionCalls.find { it.id == functionCallId } ?: continue

    val args =
      mapOf(
        FunctionCall.ORIGINAL_FUNCTION_CALL_KEY to
          mapOf(
            FunctionCall.NAME_KEY to originalFunctionCall.name,
            FunctionCall.ARGS_KEY to originalFunctionCall.args,
            FunctionCall.ID_KEY to originalFunctionCall.id,
          ),
        FunctionCall.TOOL_CONFIRMATION_KEY to
          mapOf(
            ToolConfirmation.CONFIRMED_KEY to toolConfirmation.confirmed,
            ToolConfirmation.PAYLOAD_KEY to toolConfirmation.payload,
            ToolConfirmation.HINT_KEY to toolConfirmation.hint,
          ),
      )

    val confirmationCallId = FunctionCall.generateId()
    val requestConfirmationFunctionCall =
      FunctionCall(
        name = FunctionCall.REQUEST_CONFIRMATION_FUNCTION_CALL_NAME,
        args = args,
        id = confirmationCallId,
      )
    longRunningToolIds.add(confirmationCallId)
    parts.add(Part(functionCall = requestConfirmationFunctionCall))
  }
  if (parts.isEmpty()) return null

  return Event(
    invocationId = invocationContext.invocationId,
    author = invocationContext.agent.name,
    branch = invocationContext.branch,
    content = Content(role = functionCallEvent.content?.role ?: "user", parts = parts),
    longRunningToolIds = longRunningToolIds,
    actions = functionResponseEvent.actions,
  )
}
