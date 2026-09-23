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
import com.google.adk.kt.events.isStateOnlyUserEvent
import com.google.adk.kt.logging.LoggerFactory
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
 * Resumption is guarded: only a call this agent emitted, that a tool actually requested
 * confirmation for, and whose payload still matches what was emitted, is re-executed. See
 * [isResumable].
 *
 * Mirrors Python ADK's `flows/llm_flows/request_confirmation.py`, with two deliberate divergences:
 * the filter on the author of the event carrying the confirmation call has no Python counterpart
 * and is what rejects a peer-injected confirmation before any payload parsing; and where Python
 * raises on a mismatch, this logs and skips, so a hostile payload cannot abort the invocation.
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
    // A state-only user event is not a turn, so look past it for the latest user reply.
    val (lastUserIndex, lastUserEvent) =
      events.withIndex().findLast {
        it.value.author == Role.USER && !it.value.isStateOnlyUserEvent()
      } ?: return request
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

    // The real tool calls in history, indexed by id together with the author of the event that
    // carried each. Confirmation calls are excluded because a confirmation resumes a real tool
    // call, never another confirmation.
    //
    // Collisions resolve last-wins, so an agent-authored re-issue of an id supersedes an earlier
    // one - except that a foreign author may never displace a call this agent emitted. Ids are not
    // globally unique and anyone can put an event in the session, so without that precedence a
    // peer could reuse the id of a call this agent is waiting on, shadow it, and have the author
    // guard in `isResumable` reject the *legitimate* confirmation - turning this check into a way
    // for a peer to veto any pending tool call.
    val historyCallsById =
      events
        .asSequence()
        .flatMap { event -> event.functionCalls().asSequence().map { it to event.author } }
        .filter { (call, _) ->
          call.id != null && call.name != FunctionCall.REQUEST_CONFIRMATION_FUNCTION_CALL_NAME
        }
        .fold(mutableMapOf<String, AuthoredCall>()) { acc, (call, author) ->
          val id = call.id!!
          val existing = acc[id]
          if (existing == null || author == agent.name || existing.author != agent.name) {
            acc[id] = AuthoredCall(author, call)
          }
          acc
        }

    // Ids a tool actually asked to have confirmed, via `ToolContext.requestConfirmation` - which a
    // tool declared with `requireConfirmation` also routes through.
    //
    // This accumulates over ALL events rather than keeping one event per id, and that is load
    // bearing: re-executing a confirmed tool emits a second function response carrying the same id
    // but an empty `requestedToolConfirmations`. Under a keep-last implementation that second
    // response would shadow the original request and make the confirmation un-resumable, so do not
    // "simplify" this to an `associateBy`.
    val confirmationRequestedIds =
      events
        .asSequence()
        .flatMap { event ->
          val requested = event.actions.requestedToolConfirmations
          event.functionResponses().asSequence().mapNotNull { response ->
            response.id?.takeIf(requested::containsKey)
          }
        }
        .toSet()

    // Calls already re-executed after the confirmation, either by a previous pass through this
    // processor or in a later turn. Applied before the resumability check rather than after it,
    // because the scan above re-matches the same stale user event on every later LLM call, so a
    // settled confirmation would otherwise be re-examined - and re-logged - for the rest of the
    // session.
    //
    // Only responses this agent produced count. A peer event landing after the approval that
    // reuses the pending call's id would otherwise convince this scan the tool had already run,
    // silently dropping the approval - and it short-circuits before `isResumable`, so that would
    // not even leave a log line.
    val alreadyExecutedIds =
      events
        .asSequence()
        .drop(lastUserIndex + 1)
        .filter { it.author == agent.name }
        .flatMap { it.functionResponses().asSequence() }
        .mapNotNull { it.id }
        .toSet()

    val pending =
      events
        .asSequence()
        // Only this agent can ask this agent's user for confirmation. Function call parts also
        // reach the session from an A2A peer response, which the remote agent turns into an event
        // authored by itself, and honouring a confirmation call from there would let the peer
        // choose which local tool runs.
        .filter { it.author == agent.name }
        .flatMap { it.functionCalls().asSequence() }
        .mapNotNull { synth ->
          val confirmation = synth.id?.let(confirmationsBySynthId::get) ?: return@mapNotNull null
          val original = synth.embeddedOriginalCall() ?: return@mapNotNull null
          val originalId = original.id ?: return@mapNotNull null
          if (originalId in alreadyExecutedIds) return@mapNotNull null
          if (
            !isResumable(
              original,
              originalId,
              historyCallsById,
              confirmationRequestedIds,
              agent.name,
            )
          ) {
            return@mapNotNull null
          }
          ConfirmedCall(originalId, original, confirmation)
        }
        .associateBy { it.originalId }
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

  /** A tool call from session history, together with the author of the event carrying it. */
  private data class AuthoredCall(val author: String, val call: FunctionCall)

  /**
   * Returns whether [original] faithfully reproduces a tool call [agentName] emitted and that was
   * genuinely awaiting confirmation.
   *
   * The resumed call is read out of the `originalFunctionCall` argument of an
   * `adk_request_confirmation` call found in session history, and that payload is only ever data.
   * Re-executing it unchecked would let whoever authored the surrounding event pick both the tool
   * and its arguments, so only resume a call matching one this agent emitted - by id, author, name
   * and arguments - and that a tool actually asked to have confirmed.
   *
   * Known boundary: these guards do not close a same-session replay. The already-executed filter
   * has to be scoped to events after the last user event, because the pause turn emits a
   * placeholder response carrying the original call's id and a session-wide scope would suppress
   * every legitimate resume. A call confirmed and executed earlier in the session therefore still
   * satisfies every guard here, so a second `adk_request_confirmation` naming the same id, name and
   * arguments plus a fresh approval re-runs the tool. That needs an agent-authored synthetic call,
   * so it is not reachable from an A2A peer, and Python has the identical window.
   *
   * [originalId] is [original]`.id` already narrowed to non-null by the caller. It is passed in
   * rather than re-derived here so the narrowing stays at the single place that establishes it;
   * re-deriving it would add a branch no caller can reach. A second caller must preserve that
   * pairing.
   */
  private fun isResumable(
    original: FunctionCall,
    originalId: String,
    historyCallsById: Map<String, AuthoredCall>,
    confirmationRequestedIds: Set<String>,
    agentName: String,
  ): Boolean {
    val emitted = historyCallsById[originalId]
    if (emitted == null) {
      logger.warn { "Ignoring tool confirmation for $originalId: no such call in session history." }
      return false
    }
    if (emitted.author != agentName) {
      logger.debug {
        "Skipping tool confirmation for $originalId: emitted by ${emitted.author}, " +
          "not by $agentName."
      }
      return false
    }
    if (emitted.call.name != original.name) {
      logger.warn { "Ignoring tool confirmation for $originalId: tool name does not match." }
      return false
    }
    if (emitted.call.args != original.args) {
      logger.warn { "Ignoring tool confirmation for $originalId: arguments do not match." }
      return false
    }
    if (originalId !in confirmationRequestedIds) {
      logger.warn { "Ignoring tool confirmation for $originalId: no tool requested confirmation." }
      return false
    }
    return true
  }

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

    private val logger = LoggerFactory.getLogger(RequestConfirmationProcessor::class)
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
