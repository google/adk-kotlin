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

package com.google.adk.kt.examples.android.a2a

import com.google.adk.kt.a2a.android.androidA2AAgent
import kotlinx.coroutines.runBlocking

/**
 * Android counterpart of [com.google.adk.kt.examples.a2a.A2AAgentDemo], demonstrating how to use
 * [androidA2AAgent] to communicate with a remote A2A-compliant agent.
 *
 * This demo showcases:
 * 1. Auto-fetching the remote agent's `AgentCard` from its `/.well-known/agent-card.json` endpoint
 *    (the common case). To supply a pre-built card instead, use the `androidA2AAgent(name,
 *    agentCard)` overload.
 * 2. Talking to the agent over the JSON-RPC transport backed by `AndroidA2AHttpClient`, the Android
 *    HTTP client. The factory injects the transport explicitly rather than relying on SDK
 *    ServiceLoader auto-resolution.
 * 3. Wrapping the remote agent as a standard ADK [com.google.adk.kt.agents.Agent].
 *
 * A consuming app must hold the `INTERNET` permission (see the example manifest).
 */
object AndroidA2AAgentDemo {

  @JvmField
  val rootAgent = run {
    // `10.0.2.2` is the emulator's alias for the host loopback; point this at your A2A server.
    val agentUrl = "http://10.0.2.2:8080/a2a"
    val agentName = "remote-agent"
    // Non-streaming so the Client uses `message/send`, not SSE `message/stream`.
    // `runBlocking` keeps the sample simple; real apps fetch off the main thread.
    runBlocking { androidA2AAgent(name = agentName, agentCardUrl = agentUrl, streaming = false) }
  }
}
