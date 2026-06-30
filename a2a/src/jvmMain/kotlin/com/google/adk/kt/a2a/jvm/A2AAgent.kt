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
 */
fun A2AAgent(
  name: String,
  agentCard: AgentCard,
  httpClient: A2AHttpClient = JdkA2AHttpClient(),
  streaming: Boolean = true,
  subAgents: List<BaseAgent> = emptyList(),
  beforeAgentCallbacks: List<BeforeAgentCallback> = emptyList(),
  afterAgentCallbacks: List<AfterAgentCallback> = emptyList(),
): BaseRemoteA2AAgent =
  A2AAgentImpl(
    name = name,
    a2aClient =
      Client.builder(agentCard)
        .clientConfig(ClientConfig.Builder().setStreaming(streaming).build())
        .withTransport(JSONRPCTransport::class.java, JSONRPCTransportConfig(httpClient))
        .build(),
    agentCard = agentCard,
    streaming = streaming,
    subAgents = subAgents,
    beforeAgentCallbacks = beforeAgentCallbacks,
    afterAgentCallbacks = afterAgentCallbacks,
  )

/**
 * Builds a JVM A2A agent from [agentCardUrl], auto-fetching the [AgentCard] from the remote agent's
 * `/.well-known/agent-card.json` (like ADK Python/Go). Suspends on the network fetch.
 */
suspend fun A2AAgent(
  name: String,
  agentCardUrl: String,
  httpClient: A2AHttpClient = JdkA2AHttpClient(),
  streaming: Boolean = true,
  subAgents: List<BaseAgent> = emptyList(),
  beforeAgentCallbacks: List<BeforeAgentCallback> = emptyList(),
  afterAgentCallbacks: List<AfterAgentCallback> = emptyList(),
): BaseRemoteA2AAgent =
  A2AAgent(
    name = name,
    agentCard = resolveAgentCard(httpClient, agentCardUrl),
    httpClient = httpClient,
    streaming = streaming,
    subAgents = subAgents,
    beforeAgentCallbacks = beforeAgentCallbacks,
    afterAgentCallbacks = afterAgentCallbacks,
  )
