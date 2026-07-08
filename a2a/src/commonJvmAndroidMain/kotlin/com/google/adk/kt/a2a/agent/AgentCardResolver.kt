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

package com.google.adk.kt.a2a.agent

import com.google.adk.kt.a2a.agent.BaseRemoteA2AAgent.AgentCardResolutionError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.a2aproject.sdk.client.http.A2AHttpClient
import org.a2aproject.sdk.client.http.A2AHttpResponse
import org.a2aproject.sdk.jsonrpc.common.json.JsonProcessingException
import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil
import org.a2aproject.sdk.spec.AgentCard
import org.a2aproject.sdk.util.Utils

/**
 * Fetches an [AgentCard] from [agentCardUrl] (a base agent URL or a full card URL) over
 * [httpClient], reading the standard `/.well-known/agent-card.json` endpoint. Proto-free: parses
 * with the SDK's reflection-free [JsonUtil], so it also works on Android, where the SDK's own
 * proto-based `A2ACardResolver` isn't available.
 *
 * Framework-internal building block behind the `A2AAgent(...)`/`androidA2AAgent(...)` factories.
 */
internal suspend fun resolveAgentCard(httpClient: A2AHttpClient, agentCardUrl: String): AgentCard {
  val cardUrl =
    Utils.buildCardUrl(Utils.stripWellKnownSuffix(agentCardUrl), Utils.DEFAULT_AGENT_CARD_PATH)
  val response = withContext(Dispatchers.IO) { httpGetCard(httpClient, cardUrl) }
  if (!response.success()) {
    throw AgentCardResolutionError(
      "Failed to fetch agent card from $cardUrl: HTTP ${response.status()}"
    )
  }
  return try {
    JsonUtil.fromJson(response.body(), AgentCard::class.java)
  } catch (e: JsonProcessingException) {
    throw AgentCardResolutionError("Failed to parse agent card from $cardUrl", e)
  }
}

// Blocking GET, kept out of the suspend body so the SuspendBlocks lint is satisfied.
private fun httpGetCard(httpClient: A2AHttpClient, cardUrl: String): A2AHttpResponse =
  httpClient.createGet().url(cardUrl).addHeader("Accept", A2AHttpClient.APPLICATION_JSON).get()
