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
import java.io.IOException
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
 * with the SDK's reflection-free [JsonUtil].
 *
 * Framework-internal building block behind the `A2AAgent(...)` factory.
 */
internal suspend fun resolveAgentCard(httpClient: A2AHttpClient, agentCardUrl: String): AgentCard {
  val cardUrl =
    Utils.buildCardUrl(Utils.stripWellKnownSuffix(agentCardUrl), Utils.DEFAULT_AGENT_CARD_PATH)
  val response =
    try {
      withContext(Dispatchers.IO) { httpGetCard(httpClient, cardUrl) }
    } catch (e: IOException) {
      throw AgentCardResolutionError("Failed to fetch agent card from $cardUrl", e)
    } catch (e: InterruptedException) {
      Thread.currentThread().interrupt()
      throw AgentCardResolutionError("Interrupted while fetching agent card from $cardUrl", e)
    }
  if (!response.success()) {
    throw AgentCardResolutionError(
      "Failed to fetch agent card from $cardUrl: HTTP ${response.status()}"
    )
  }
  // Nullable on purpose: fromJson can return null (empty body) but is typed non-null here, so a
  // non-null binding would NPE before the `?:` under call-assertion toolchains like Gradle.
  val card: AgentCard? =
    try {
      JsonUtil.fromJson(response.body(), AgentCard::class.java)
    } catch (e: JsonProcessingException) {
      throw AgentCardResolutionError("Failed to parse agent card from $cardUrl", e)
    } catch (e: RuntimeException) {
      // A card missing a required field trips the SDK record ctor's Assert.checkNotNullParam; Gson
      // surfaces that as a RuntimeException wrapping the IllegalArgumentException.
      throw AgentCardResolutionError("Failed to parse agent card from $cardUrl", e)
    }
  return card ?: throw AgentCardResolutionError("Empty agent card response from $cardUrl")
}

// Blocking GET, kept out of the suspend body so the SuspendBlocks lint is satisfied.
private fun httpGetCard(httpClient: A2AHttpClient, cardUrl: String): A2AHttpResponse =
  httpClient.createGet().url(cardUrl).addHeader("Accept", A2AHttpClient.APPLICATION_JSON).get()
