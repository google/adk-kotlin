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

package com.google.adk.kt.testing

import com.google.adk.kt.agents.BaseAgent
import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.callbacks.AfterAgentCallback
import com.google.adk.kt.callbacks.BeforeAgentCallback
import com.google.adk.kt.events.Event
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow

/**
 * A configurable dummy implementation of [BaseAgent] designed for unit testing.
 *
 * This fixture assists developers in validating hierarchical agent designs and callback routing
 * logic without relying on deeply wired production agents.
 *
 * @param name The agent's identifier (defaults to "test-agent").
 * @param subAgents The sub-agents managed by this agent (default is empty).
 * @param beforeAgentCallbacks Callbacks executed prior to this agent's run.
 * @param afterAgentCallbacks Callbacks executed after this agent's run.
 * @param description The agent's description (default is empty).
 * @param disallowTransferToParent Forwarded to [BaseAgent.disallowTransferToParent].
 * @param disallowTransferToPeers Forwarded to [BaseAgent.disallowTransferToPeers].
 * @param onRunAsync Configures the lambda to simulate execution within [runAsyncImpl].
 */
class DummyAgent(
  name: String = "test-agent",
  subAgents: List<BaseAgent> = emptyList(),
  beforeAgentCallbacks: List<BeforeAgentCallback> = emptyList(),
  afterAgentCallbacks: List<AfterAgentCallback> = emptyList(),
  description: String = "",
  disallowTransferToParent: Boolean = false,
  disallowTransferToPeers: Boolean = false,
  val onRunAsync: suspend FlowCollector<Event>.(InvocationContext) -> Unit = {},
) :
  BaseAgent(
    name = name,
    description = description,
    subAgents = subAgents,
    beforeAgentCallbacks = beforeAgentCallbacks,
    afterAgentCallbacks = afterAgentCallbacks,
    disallowTransferToParent = disallowTransferToParent,
    disallowTransferToPeers = disallowTransferToPeers,
  ) {
  override fun runAsyncImpl(context: InvocationContext): Flow<Event> = flow { onRunAsync(context) }
}
