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

package com.google.adk.kt.a2a.jvm

import com.google.adk.kt.a2a.agent.A2AAgentImpl
import com.google.adk.kt.a2a.agent.BaseRemoteA2AAgent
import com.google.adk.kt.a2a.agent.resolveAgentCard
import com.google.adk.kt.agents.BaseAgent
import com.google.adk.kt.callbacks.AfterAgentCallback
import com.google.adk.kt.callbacks.BeforeAgentCallback
import org.a2aproject.sdk.client.Client
import org.a2aproject.sdk.client.config.ClientConfig
import org.a2aproject.sdk.client.http.A2AHttpClient
import org.a2aproject.sdk.client.http.JdkA2AHttpClient
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransport
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransportConfig
import org.a2aproject.sdk.spec.AgentCard

/**
 * Builds a JVM A2A agent from an already-resolved [agentCard], wiring up the client so the caller
 * never supplies a client and card separately.
 *
 * Streaming is chosen per invocation from `RunConfig.streamingMode` (gated by the card's
 * capability), matching ADK Go/Python -- there is no per-agent streaming flag.
 *
 * @param name this agent's identifier in the ADK agent tree (event author and `transfer_to_agent`
 *   target), independent of the card's advertised name.
 * @param agentCard the resolved remote agent card.
 * @param httpClient HTTP client backing the JSON-RPC transport; defaults to a [JdkA2AHttpClient].
 * @param description overrides the agent's description; when null, the remote card's description is
 *   used (matching ADK Python/Go).
 * @param subAgents child agents in the ADK agent tree.
 * @param beforeAgentCallbacks callbacks invoked before the agent runs.
 * @param afterAgentCallbacks callbacks invoked after the agent runs.
 */
fun A2AAgent(
  name: String,
  agentCard: AgentCard,
  httpClient: A2AHttpClient = JdkA2AHttpClient(),
  description: String? = null,
  subAgents: List<BaseAgent> = emptyList(),
  beforeAgentCallbacks: List<BeforeAgentCallback> = emptyList(),
  afterAgentCallbacks: List<AfterAgentCallback> = emptyList(),
): BaseRemoteA2AAgent =
  A2AAgentImpl(
    name = name,
    userDescription = description,
    clientProvider = { streaming ->
      Client.builder(agentCard)
        .clientConfig(ClientConfig.Builder().setStreaming(streaming).build())
        .withTransport(JSONRPCTransport::class.java, JSONRPCTransportConfig(httpClient))
        .build()
    },
    agentCard = agentCard,
    transportSupportsStreaming = true,
    subAgents = subAgents,
    beforeAgentCallbacks = beforeAgentCallbacks,
    afterAgentCallbacks = afterAgentCallbacks,
  )

/**
 * Builds a JVM A2A agent from [agentCardUrl], auto-fetching the [AgentCard] from the remote agent's
 * `/.well-known/agent-card.json` (like ADK Python/Go). Suspends on the network fetch.
 *
 * @param name this agent's identifier in the ADK agent tree (event author and `transfer_to_agent`
 *   target), independent of the card's advertised name.
 * @param agentCardUrl the remote agent's base URL or full agent-card URL.
 * @param httpClient HTTP client backing the JSON-RPC transport; defaults to a [JdkA2AHttpClient].
 * @param description overrides the agent's description; when null, the remote card's description is
 *   used (matching ADK Python/Go).
 * @param subAgents child agents in the ADK agent tree.
 * @param beforeAgentCallbacks callbacks invoked before the agent runs.
 * @param afterAgentCallbacks callbacks invoked after the agent runs.
 */
suspend fun A2AAgent(
  name: String,
  agentCardUrl: String,
  httpClient: A2AHttpClient = JdkA2AHttpClient(),
  description: String? = null,
  subAgents: List<BaseAgent> = emptyList(),
  beforeAgentCallbacks: List<BeforeAgentCallback> = emptyList(),
  afterAgentCallbacks: List<AfterAgentCallback> = emptyList(),
): BaseRemoteA2AAgent =
  A2AAgent(
    name = name,
    agentCard = resolveAgentCard(httpClient, agentCardUrl),
    httpClient = httpClient,
    description = description,
    subAgents = subAgents,
    beforeAgentCallbacks = beforeAgentCallbacks,
    afterAgentCallbacks = afterAgentCallbacks,
  )
