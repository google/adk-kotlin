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

package com.google.adk.kt.telemetry

/** OpenTelemetry semantic attributes mapped to the Google ADK execution flow. */
object TelemetryAttributes {
  // GenAI attributes
  const val GEN_AI_OPERATION_NAME = "gen_ai.operation.name"
  const val GEN_AI_SYSTEM = "gen_ai.system"
  const val GEN_AI_AGENT_NAME = "gen_ai.agent.name"
  const val GEN_AI_AGENT_DESCRIPTION = "gen_ai.agent.description"
  const val GEN_AI_CONVERSATION_ID = "gen_ai.conversation.id"
  const val GEN_AI_TOOL_NAME = "gen_ai.tool.name"
  const val GEN_AI_TOOL_DESCRIPTION = "gen_ai.tool.description"
  const val GEN_AI_TOOL_TYPE = "gen_ai.tool.type"
  const val GEN_AI_TOOL_CALL_ID = "gen_ai.tool.call.id"
  const val GEN_AI_REQUEST_MODEL = "gen_ai.request.model"
  const val GEN_AI_REQUEST_MAX_TOKENS = "gen_ai.request.max_tokens"
  const val GEN_AI_REQUEST_TOP_P = "gen_ai.request.top_p"
  const val GEN_AI_RESPONSE_FINISH_REASONS = "gen_ai.response.finish_reasons"
  const val GEN_AI_USAGE_INPUT_TOKENS = "gen_ai.usage.input_tokens"
  const val GEN_AI_USAGE_OUTPUT_TOKENS = "gen_ai.usage.output_tokens"
  const val GEN_AI_USAGE_CACHE_READ_INPUT_TOKENS = "gen_ai.usage.cache_read.input_tokens"
  const val GEN_AI_USAGE_REASONING_OUTPUT_TOKENS = "gen_ai.usage.reasoning.output_tokens"
  const val GEN_AI_USAGE_REASONING_TOKENS_LIMIT = "gen_ai.usage.experimental.reasoning_tokens_limit"

  // General OpenTelemetry attributes
  const val ERROR_TYPE = "error.type"

  // Common attribute values
  const val SYSTEM_GCP_VERTEX_AGENT = "gcp.vertex.agent"
  const val OPERATION_INVOKE_AGENT = "invoke_agent"
  const val OPERATION_EXECUTE_TOOL = "execute_tool"

  // Custom ADK attributes
  const val GCP_VERTEX_AGENT_INVOCATION_ID = "gcp.vertex.agent.invocation_id"
  const val GCP_VERTEX_AGENT_SESSION_ID = "gcp.vertex.agent.session_id"
  const val GCP_VERTEX_AGENT_EVENT_ID = "gcp.vertex.agent.event_id"
  const val GCP_VERTEX_AGENT_TOOL_CALL_ARGS = "gcp.vertex.agent.tool_call_args"
  const val GCP_VERTEX_AGENT_TOOL_RESPONSE = "gcp.vertex.agent.tool_response"
  const val GCP_VERTEX_AGENT_LLM_REQUEST = "gcp.vertex.agent.llm_request"
  const val GCP_VERTEX_AGENT_LLM_RESPONSE = "gcp.vertex.agent.llm_response"
}
