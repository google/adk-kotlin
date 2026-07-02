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

package com.google.adk.kt.examples.a2a

import com.google.adk.kt.a2a.agent.A2AAgent
import org.a2aproject.sdk.client.Client
import org.a2aproject.sdk.client.http.JdkA2AHttpClient
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransport
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransportConfig
import org.a2aproject.sdk.spec.AgentCapabilities
import org.a2aproject.sdk.spec.AgentCard
import org.a2aproject.sdk.spec.AgentInterface

/**
 * Example agent demonstrating how to use [RemoteA2AAgent] to communicate with a remote
 * A2A-compliant agent.
 *
 * This demo showcases:
 * 1. Initializing an [Client] with the JSON-RPC transport.
 * 2. Injecting a [JdkA2AHttpClient] explicitly as the JVM HTTP client. (On Android, inject
 *    `AndroidA2AHttpClient` instead. Explicit injection is preferred over relying on SDK
 *    ServiceLoader auto-resolution.)
 * 3. Wrapping the remote agent as a standard ADK [com.google.adk.kt.agents.Agent].
 */
object JvmA2AAgentDemo {

  @JvmField
  val rootAgent = run {
    println("Starting JvmA2AAgentDemo...")

    val agentUrl = System.getenv("A2A_AGENT_URL") ?: "http://localhost:8080/a2a"
    val agentName = System.getenv("A2A_AGENT_NAME") ?: "remote-agent"

    val agentCard =
      AgentCard.builder()
        .name(agentName)
        .url(agentUrl)
        .description("A remote A2A agent")
        .version("1.0.0")
        .preferredTransport("JSONRPC")
        .defaultInputModes(listOf("text"))
        .defaultOutputModes(listOf("text"))
        .capabilities(
          // Advertise non-streaming so the a2a Client uses `message/send` instead
          // of SSE `message/stream`. This is what selects the transport in
          // Client (it checks agentCard.capabilities().streaming()).
          AgentCapabilities.builder().streaming(false).pushNotifications(false).build()
        )
        .skills(emptyList())
        .supportedInterfaces(listOf(AgentInterface("JSONRPC", agentUrl)))
        .build()

    val a2aClient =
      Client.builder(agentCard)
        .withTransport(JSONRPCTransport::class.java, JSONRPCTransportConfig(JdkA2AHttpClient()))
        .build()

    // Use non-streaming (message/send) so the demo works against any A2A server
    // regardless of its streaming support.
    A2AAgent(name = agentName, client = a2aClient, agentCard = agentCard, streaming = false)
  }
}
