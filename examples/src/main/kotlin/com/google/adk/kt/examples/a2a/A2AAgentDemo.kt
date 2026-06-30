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

import com.google.adk.kt.a2a.jvm.A2AAgent
import kotlinx.coroutines.runBlocking

/**
 * Example agent demonstrating how to use [A2AAgent] to communicate with a remote A2A-compliant
 * agent on the JVM.
 *
 * This demo showcases:
 * 1. Auto-fetching the remote agent's `AgentCard` from its `/.well-known/agent-card.json` endpoint
 *    (the common case). To supply a pre-built card instead, use the `A2AAgent(name, agentCard)`
 *    overload.
 * 2. Talking to the agent over the JSON-RPC transport backed by `JdkA2AHttpClient`, the JVM HTTP
 *    client. (On Android, use `androidA2AAgent`, which uses `AndroidA2AHttpClient`.) The factory
 *    injects the transport explicitly rather than relying on SDK ServiceLoader auto-resolution.
 * 3. Wrapping the remote agent as a standard ADK [com.google.adk.kt.agents.Agent].
 */
object A2AAgentDemo {

  @JvmField
  val rootAgent = run {
    val agentUrl = System.getenv("A2A_AGENT_URL") ?: "http://localhost:8080/a2a"
    val agentName = System.getenv("A2A_AGENT_NAME") ?: "remote-agent"
    // Non-streaming so the Client uses `message/send`, not SSE `message/stream`.
    runBlocking { A2AAgent(name = agentName, agentCardUrl = agentUrl, streaming = false) }
  }
}
