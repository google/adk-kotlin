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

/**
 * Name of the sub-agent (and resulting function tool) created by [createVertexAiRagRetrievalAgent].
 */
internal const val VERTEX_AI_RAG_RETRIEVAL_AGENT_NAME = "vertex_ai_rag_retrieval_agent"

/**
 * Creates a sub-agent whose only tool is the given [VertexAiRagRetrieval].
 *
 * Used by the multi-tools-limit workaround so that the RAG retrieval can be exposed to the parent
 * agent as a function tool (via [VertexAiRagRetrievalAgentTool]) and therefore coexist with other
 * tools. Since the sub-agent has a single tool, it is never itself rewritten by the workaround.
 */
internal fun createVertexAiRagRetrievalAgent(model: Model, tool: VertexAiRagRetrieval): LlmAgent =
  LlmAgent(
    name = VERTEX_AI_RAG_RETRIEVAL_AGENT_NAME,
    model = model,
    description = "An agent for retrieving grounding data using the Vertex AI RAG store",
    instruction =
      Instruction.Text(
        "You are a specialized Vertex AI RAG retrieval agent.\n\n" +
          "When given a query, retrieve the related information from the Vertex AI RAG store."
      ),
    tools = listOf(tool),
  )

/**
 * A tool that wraps a sub-agent which only uses a [VertexAiRagRetrieval].
 *
 * Lets Vertex AI RAG retrieval be used alongside other tools (a built-in retrieval tool cannot be
 * combined with other tools in a single request). The built-in is wrapped in a sub-agent and
 * exposed as a function tool, and the sub-agent's grounding metadata is propagated to the parent.
 *
 * Mirrors [VertexAiSearchAgentTool]: the Python ADK queries the Vertex RAG service directly, but
 * that client is not available here, so the sub-agent mechanism is reused instead.
 */
internal class VertexAiRagRetrievalAgentTool(agent: LlmAgent) :
  AgentTool(agent = agent, propagateGroundingMetadata = true)
