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

/** Name of the sub-agent (and the resulting function tool) created by [createGoogleSearchAgent]. */
internal const val GOOGLE_SEARCH_AGENT_NAME = "google_search_agent"

/**
 * Creates a sub-agent whose only tool is [GoogleSearchTool].
 *
 * Used by the multi-tools-limit workaround so that `google_search` can be exposed to the parent
 * agent as a function tool (via [GoogleSearchAgentTool]) and therefore coexist with other tools.
 */
internal fun createGoogleSearchAgent(model: Model): LlmAgent =
  LlmAgent(
    name = GOOGLE_SEARCH_AGENT_NAME,
    model = model,
    description = "An agent for performing Google search using the `google_search` tool",
    instruction =
      Instruction.Text(
        "You are a specialized Google search agent.\n\n" +
          "When given a search query, use the `google_search` tool to find the related information."
      ),
    tools = listOf(GoogleSearchTool()),
  )

/**
 * A tool that wraps a sub-agent which only uses [GoogleSearchTool].
 *
 * This is the Kotlin port of the Python ADK `GoogleSearchAgentTool`: a workaround that lets
 * `google_search` be used alongside other tools. Built-in tools cannot be combined with other tools
 * in a single request, so the built-in is wrapped in a sub-agent and exposed as a function tool
 * instead. The sub-agent's grounding metadata is propagated back to the parent.
 */
internal class GoogleSearchAgentTool(agent: LlmAgent) :
  AgentTool(agent = agent, propagateGroundingMetadata = true)
