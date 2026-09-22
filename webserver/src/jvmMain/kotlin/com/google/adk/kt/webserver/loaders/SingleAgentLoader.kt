/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.adk.kt.webserver.loaders

import com.google.adk.kt.agents.BaseAgent
import com.google.adk.kt.workflow.Node

/** A [NodeLoader] serving one agent or other root [Node], under its own name only. */
class SingleAgentLoader(private val agent: Node) : NodeLoader {
  /** Keeps code compiled against the agent-only constructor linking. */
  @Deprecated("Binary compatibility only.", level = DeprecationLevel.HIDDEN)
  constructor(agent: BaseAgent) : this(agent as Node)

  override fun listAgents(): List<String> = listOf(agent.name)

  override fun loadAgent(agentName: String): BaseAgent? = loadNode(agentName) as? BaseAgent

  override fun loadNode(agentName: String): Node? = agent.takeIf { it.name == agentName }
}
