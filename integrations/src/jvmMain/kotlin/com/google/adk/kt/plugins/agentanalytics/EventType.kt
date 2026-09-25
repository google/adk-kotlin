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

package com.google.adk.kt.plugins.agentanalytics

/** The `event_type` vocabulary written to the BigQuery events table. */
internal object EventType {

  const val USER_MESSAGE_RECEIVED = "USER_MESSAGE_RECEIVED"

  const val INVOCATION_STARTING = "INVOCATION_STARTING"
  const val INVOCATION_COMPLETED = "INVOCATION_COMPLETED"
  const val INVOCATION_ERROR = "INVOCATION_ERROR"

  const val AGENT_STARTING = "AGENT_STARTING"
  const val AGENT_COMPLETED = "AGENT_COMPLETED"
  const val AGENT_RESPONSE = "AGENT_RESPONSE"

  const val LLM_REQUEST = "LLM_REQUEST"
  const val LLM_RESPONSE = "LLM_RESPONSE"
  const val LLM_ERROR = "LLM_ERROR"

  const val TOOL_STARTING = "TOOL_STARTING"
  const val TOOL_COMPLETED = "TOOL_COMPLETED"
  const val TOOL_ERROR = "TOOL_ERROR"
  const val TOOL_PAUSED = "TOOL_PAUSED"

  const val STATE_DELTA = "STATE_DELTA"
  const val A2A_INTERACTION = "A2A_INTERACTION"

  const val HITL_CREDENTIAL_REQUEST = "HITL_CREDENTIAL_REQUEST"
  const val HITL_CONFIRMATION_REQUEST = "HITL_CONFIRMATION_REQUEST"
  const val HITL_INPUT_REQUEST = "HITL_INPUT_REQUEST"

  /** Suffix appended to a HITL request type to name its completion event. */
  const val COMPLETED_SUFFIX = "_COMPLETED"

  /**
   * Event types Python ADK emits that this plugin does not, recorded so the gap is explicit.
   *
   * `AGENT_ERROR` is the notable one: the Kotlin plugin API surfaces model and tool errors through
   * their own callbacks and has no agent-level error hook, so agent failures are recorded as
   * `status = "ERROR"` on the surrounding event instead of as a separate row.
   */
  val NOT_EMITTED_BY_KOTLIN =
    setOf(
      "AGENT_ERROR",
      "AGENT_STATE_CHECKPOINT",
      "AGENT_TRANSFER",
      "EVENT_COMPACTION",
      "NODE_OUTPUT",
    )
}
