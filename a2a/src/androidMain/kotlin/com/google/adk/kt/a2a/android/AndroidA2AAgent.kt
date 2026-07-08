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

package com.google.adk.kt.a2a.android

import com.google.adk.kt.a2a.agent.A2AAgentImpl
import com.google.adk.kt.a2a.agent.BaseRemoteA2AAgent
import com.google.adk.kt.a2a.agent.resolveAgentCard
import com.google.adk.kt.agents.BaseAgent
import com.google.adk.kt.callbacks.AfterAgentCallback
import com.google.adk.kt.callbacks.BeforeAgentCallback
import org.a2aproject.sdk.client.Client
import org.a2aproject.sdk.client.clientWithTransport
import org.a2aproject.sdk.client.http.A2AHttpClient
import org.a2aproject.sdk.client.http.AndroidA2AHttpClient
import org.a2aproject.sdk.spec.AgentCard
import org.a2aproject.sdk.spec.TransportProtocol

/**
 * Builds an A2A [Client] for Android by wiring a proto-free [JsonRpcHttpClientTransport] over
 * [httpClient] (an [AndroidA2AHttpClient] by default) directly into a [Client] -- the SDK's own
 * JSON-RPC transport marshals through protobuf and isn't Android-buildable. Constructed directly
 * (not via the ServiceLoader SPI) so nothing here needs to be reflectively discoverable.
 */
internal fun androidA2AClient(
  agentCard: AgentCard,
  httpClient: A2AHttpClient = AndroidA2AHttpClient(),
): Client {
  // `url()` is non-null under Blaze (JSpecify) but platform-nullable under Gradle's SDK jar, so
  // normalize both candidates to String? and require one before building the transport.
  val jsonRpcUrl: String? =
    agentCard
      .supportedInterfaces()
      .firstOrNull { it.protocolBinding() == TransportProtocol.JSONRPC.asString() }
      ?.url()
  val cardUrl: String? = agentCard.url()
  val url: String = jsonRpcUrl ?: cardUrl ?: error("AgentCard has no transport URL")
  return clientWithTransport(agentCard, JsonRpcHttpClientTransport(httpClient, url))
}

/**
 * Builds an Android [A2AAgent] from an already-resolved [agentCard], wiring up the Android client
 * so the caller never supplies a client and card separately.
 */
fun androidA2AAgent(
  name: String,
  agentCard: AgentCard,
  httpClient: A2AHttpClient = AndroidA2AHttpClient(),
  streaming: Boolean = true,
  subAgents: List<BaseAgent> = emptyList(),
  beforeAgentCallbacks: List<BeforeAgentCallback> = emptyList(),
  afterAgentCallbacks: List<AfterAgentCallback> = emptyList(),
): BaseRemoteA2AAgent =
  A2AAgentImpl(
    name = name,
    a2aClient = androidA2AClient(agentCard, httpClient),
    agentCard = agentCard,
    streaming = streaming,
    subAgents = subAgents,
    beforeAgentCallbacks = beforeAgentCallbacks,
    afterAgentCallbacks = afterAgentCallbacks,
  )

/**
 * Builds an Android [A2AAgent] from [agentCardUrl], auto-fetching the [AgentCard] from the remote
 * agent's `/.well-known/agent-card.json` (like ADK Python/Go). Suspends on the network fetch, so
 * call it off the main thread.
 */
suspend fun androidA2AAgent(
  name: String,
  agentCardUrl: String,
  httpClient: A2AHttpClient = AndroidA2AHttpClient(),
  streaming: Boolean = true,
  subAgents: List<BaseAgent> = emptyList(),
  beforeAgentCallbacks: List<BeforeAgentCallback> = emptyList(),
  afterAgentCallbacks: List<AfterAgentCallback> = emptyList(),
): BaseRemoteA2AAgent =
  androidA2AAgent(
    name = name,
    agentCard = resolveAgentCard(httpClient, agentCardUrl),
    httpClient = httpClient,
    streaming = streaming,
    subAgents = subAgents,
    beforeAgentCallbacks = beforeAgentCallbacks,
    afterAgentCallbacks = afterAgentCallbacks,
  )
