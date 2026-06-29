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

package com.google.adk.kt.tools

import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.models.Model

/** Name of the sub-agent (and resulting function tool) created by [createVertexAiSearchAgent]. */
internal const val VERTEX_AI_SEARCH_AGENT_NAME = "vertex_ai_search_agent"

/**
 * Creates a sub-agent whose only tool is the given [VertexAiSearchTool].
 *
 * Used by the multi-tools-limit workaround so that `vertex_ai_search` can be exposed to the parent
 * agent as a function tool (via [VertexAiSearchAgentTool]) and therefore coexist with other tools.
 * The original [tool] is reused as the sub-agent's only tool; since the sub-agent has a single
 * tool, it is never itself rewritten by the workaround.
 */
internal fun createVertexAiSearchAgent(model: Model, tool: VertexAiSearchTool): LlmAgent =
  LlmAgent(
    name = VERTEX_AI_SEARCH_AGENT_NAME,
    model = model,
    description = "An agent for searching using the `vertex_ai_search` tool",
    instruction =
      Instruction.Text(
        "You are a specialized Vertex AI Search agent.\n\n" +
          "When given a search query, use the `vertex_ai_search` tool to find the related " +
          "information."
      ),
    tools = listOf(tool),
  )

/**
 * A tool that wraps a sub-agent which only uses a [VertexAiSearchTool].
 *
 * Lets `vertex_ai_search` be used alongside other tools (built-in tools cannot be combined with
 * other tools in a single request). The built-in is wrapped in a sub-agent and exposed as a
 * function tool, and the sub-agent's grounding metadata is propagated back to the parent.
 *
 * NOTE: this intentionally diverges from the Python ADK, which instead converts the
 * `VertexAiSearchTool` into a `DiscoveryEngineSearchTool` that queries the Discovery Engine API
 * directly. That public Discovery Engine client is not available as a dependency here, so this
 * reuses the same sub-agent mechanism as [GoogleSearchAgentTool].
 */
internal class VertexAiSearchAgentTool(agent: LlmAgent) :
  AgentTool(agent = agent, propagateGroundingMetadata = true)
