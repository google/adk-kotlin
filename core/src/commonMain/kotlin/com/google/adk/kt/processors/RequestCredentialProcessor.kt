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

@file:OptIn(ExperimentalAuthApi::class, FrameworkInternalApi::class)

package com.google.adk.kt.processors

import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.toReadonlyContext
import com.google.adk.kt.annotations.ExperimentalAuthApi
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.auth.CredentialKey
import com.google.adk.kt.auth.RequestCredentialArgs
import com.google.adk.kt.auth.SignInReplyResult
import com.google.adk.kt.auth.storeSignInReply
import com.google.adk.kt.events.Event
import com.google.adk.kt.logging.LoggerFactory
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.Role

/**
 * Before each model call, stores the credentials the app sent back for this agent's credential
 * requests and re-runs the tool calls waiting on them. Only responses in the latest event with
 * content count, and only when the user wrote it.
 */
internal class RequestCredentialProcessor : LlmRequestProcessor {

  override suspend fun process(
    context: InvocationContext,
    request: LlmRequest,
    emitEvent: suspend (Event) -> Unit,
  ): LlmRequest {
    val agent =
      requireNotNull(context.agent as? LlmAgent) {
        "RequestCredentialProcessor requires an LlmAgent."
      }
    // All invocations, so a response that starts a new invocation still finds its request.
    val events = context.getEvents(currentInvocation = false, currentBranch = true)
    val credentialResponses = events.latestCredentialResponses()
    if (credentialResponses.isEmpty()) return request

    // Only this agent's events count, so a peer can't choose what is stored or re-run.
    val agentEvents = events.filter { it.author == agent.name }
    val requestingCallIds = mutableSetOf<String>()
    val storedKeys = mutableSetOf<CredentialKey>()
    for ((id, response) in credentialResponses) {
      val credentialRequest = agentEvents.findCredentialRequest(id)
      if (credentialRequest == null) {
        logger.warn { "Ignoring a credential response that matches no credential request." }
        continue
      }
      requestingCallIds += credentialRequest.functionCallId
      val authConfig = credentialRequest.authConfig
      // Straight into the live session, so ADK copies the token into no event.
      when (authConfig.storeSignInReply(response, context.session.state)) {
        SignInReplyResult.STORED -> storedKeys += authConfig.credentialKey
        SignInReplyResult.NO_CREDENTIAL ->
          logger.warn { "Ignoring a credential response that holds no credential." }
        SignInReplyResult.NEEDS_CODE_EXCHANGE ->
          logger.warn {
            "Ignoring a credential response without an access token; ADK can't exchange an " +
              "authorization code yet."
          }
      }
    }

    val callIds = agentEvents.callIdsToRerun(requestingCallIds, storedKeys)
    val callEvent =
      agentEvents.lastOrNull { event -> event.functionCalls().any { it.id in callIds } }
        ?: return request
    context
      .handleFunctionCalls(
        functionCalls = callEvent.functionCalls().filter { it.id in callIds },
        tools = agent.toolsByName(context),
      )
      ?.let { emitEvent(it) }
    return request
  }

  private companion object {
    private val logger = LoggerFactory.getLogger(RequestCredentialProcessor::class)
  }
}

/**
 * Returns the `adk_request_credential` responses in the latest event with content, by id, or none
 * unless the user wrote that event.
 */
private fun List<Event>.latestCredentialResponses(): Map<String, Map<String, Any?>> {
  val event =
    lastOrNull { it.content != null }?.takeIf { it.author == Role.USER } ?: return emptyMap()
  return event
    .functionResponses()
    .filter { it.name == FunctionCall.REQUEST_EUC_FUNCTION_CALL_NAME }
    .mapNotNull { response -> response.id?.let { id -> id to response.response } }
    .toMap()
}

/**
 * Returns the arguments of the latest valid credential request with [id] in these events that was
 * made with the config one of their tools asked for, or null if there is none. No other request
 * counts, so neither a peer nor the model can choose where or how a credential is stored.
 */
private fun List<Event>.findCredentialRequest(id: String): RequestCredentialArgs? {
  val requestedConfigs =
    flatMap { it.actions.requestedAuthConfigs.entries }.groupBy({ it.key }, { it.value })
  return asSequence()
    .flatMap { it.functionCalls() }
    .filter { it.name == FunctionCall.REQUEST_EUC_FUNCTION_CALL_NAME && it.id == id }
    .mapNotNull { RequestCredentialArgs.fromMap(it.args) }
    .lastOrNull { it.authConfig in requestedConfigs[it.functionCallId].orEmpty() }
}

/**
 * Returns the tool calls to re-run: [requestingCallIds], and each other call in the same
 * `requestedAuthConfigs` map of these events whose credential key is in [storedKeys].
 */
private fun List<Event>.callIdsToRerun(
  requestingCallIds: Set<String>,
  storedKeys: Set<CredentialKey>,
): Set<String> {
  val sameMapCallIds =
    asSequence()
      .map { it.actions.requestedAuthConfigs }
      .filter { configs -> requestingCallIds.any(configs::containsKey) }
      .flatMap { configs -> configs.filterValues { it.credentialKey in storedKeys }.keys }
  return requestingCallIds + sameMapCallIds
}

/** Returns the tools this agent can call, by name: its own, the runtime's and its toolsets'. */
private suspend fun LlmAgent.toolsByName(context: InvocationContext): Map<String, BaseTool> {
  val toolsetTools = toolsets.flatMap { it.getTools(context.toReadonlyContext()) }
  return (tools + context.extraTools.values + toolsetTools).associateBy { it.name }
}
