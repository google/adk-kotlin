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
import com.google.adk.kt.annotations.AdkJavaInteropApi
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

/**
 * Optional settings for the [A2AAgent] factories, grouped into one object so Java callers can
 * assemble them with [Builder] instead of a long positional call. Every property defaults to the
 * same value the [A2AAgent] parameters use.
 *
 * @property httpClient HTTP client backing the JSON-RPC transport; defaults to a
 *   [JdkA2AHttpClient].
 * @property description overrides the agent's description; when null, the remote card's description
 *   is used (matching ADK Python/Go).
 * @property subAgents child agents in the ADK agent tree.
 * @property beforeAgentCallbacks callbacks invoked before the agent runs.
 * @property afterAgentCallbacks callbacks invoked after the agent runs.
 */
data class A2AAgentConfig(
  val httpClient: A2AHttpClient = JdkA2AHttpClient(),
  val description: String? = null,
  val subAgents: List<BaseAgent> = emptyList(),
  val beforeAgentCallbacks: List<BeforeAgentCallback> = emptyList(),
  val afterAgentCallbacks: List<AfterAgentCallback> = emptyList(),
) {
  /**
   * Returns a [Builder] initialized with this instance's properties, primarily for Java callers.
   * Prefer it over `copy` from Java: `copy` takes every property positionally, so its signature
   * changes whenever a property is added.
   */
  @AdkJavaInteropApi
  fun toBuilder(): Builder =
    Builder()
      .httpClient(httpClient)
      .description(description)
      .subAgents(subAgents)
      .beforeAgentCallbacks(beforeAgentCallbacks)
      .afterAgentCallbacks(afterAgentCallbacks)

  /**
   * Fluent builder for [A2AAgentConfig], provided primarily for Java callers. Any property left
   * unset falls back to the same default as the constructor.
   */
  @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
  class Builder {
    // Created in build() only if unset: constructing JdkA2AHttpClient starts a thread.
    private var httpClient: A2AHttpClient? = null
    private var description: String? = null
    private var subAgents: List<BaseAgent> = emptyList()
    private var beforeAgentCallbacks: List<BeforeAgentCallback> = emptyList()
    private var afterAgentCallbacks: List<AfterAgentCallback> = emptyList()

    fun httpClient(httpClient: A2AHttpClient): Builder = apply { this.httpClient = httpClient }

    fun description(description: String?): Builder = apply { this.description = description }

    fun subAgents(subAgents: List<BaseAgent>): Builder = apply { this.subAgents = subAgents }

    fun beforeAgentCallbacks(beforeAgentCallbacks: List<BeforeAgentCallback>): Builder = apply {
      this.beforeAgentCallbacks = beforeAgentCallbacks
    }

    fun afterAgentCallbacks(afterAgentCallbacks: List<AfterAgentCallback>): Builder = apply {
      this.afterAgentCallbacks = afterAgentCallbacks
    }

    fun build(): A2AAgentConfig =
      A2AAgentConfig(
        httpClient = httpClient ?: JdkA2AHttpClient(),
        description = description,
        subAgents = subAgents,
        beforeAgentCallbacks = beforeAgentCallbacks,
        afterAgentCallbacks = afterAgentCallbacks,
      )
  }

  companion object {
    @AdkJavaInteropApi @JvmStatic fun builder(): Builder = Builder()
  }
}

/**
 * Builds a JVM A2A agent from a resolved [agentCard], taking its optional settings from [config].
 */
fun A2AAgent(name: String, agentCard: AgentCard, config: A2AAgentConfig): BaseRemoteA2AAgent =
  A2AAgent(
    name = name,
    agentCard = agentCard,
    httpClient = config.httpClient,
    description = config.description,
    subAgents = config.subAgents,
    beforeAgentCallbacks = config.beforeAgentCallbacks,
    afterAgentCallbacks = config.afterAgentCallbacks,
  )

/**
 * Builds a JVM A2A agent from [agentCardUrl], taking its optional settings from [config]. Suspends
 * on the network fetch of the [AgentCard].
 */
suspend fun A2AAgent(
  name: String,
  agentCardUrl: String,
  config: A2AAgentConfig,
): BaseRemoteA2AAgent =
  A2AAgent(
    name = name,
    agentCardUrl = agentCardUrl,
    httpClient = config.httpClient,
    description = config.description,
    subAgents = config.subAgents,
    beforeAgentCallbacks = config.beforeAgentCallbacks,
    afterAgentCallbacks = config.afterAgentCallbacks,
  )
