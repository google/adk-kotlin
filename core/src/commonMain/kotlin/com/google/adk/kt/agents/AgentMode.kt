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

package com.google.adk.kt.agents

import com.google.adk.kt.annotations.ExperimentalWorkflowApi

/**
 * How an [LlmAgent] converses when it runs as a workflow node or a delegated sub-agent. The modes
 * are part of the experimental workflow API.
 */
enum class AgentMode {
  /** Answers one input with one response, without conversing with the user. */
  @ExperimentalWorkflowApi SINGLE_TURN,

  /** Holds a multi-turn conversation with the user over the shared session history. */
  @ExperimentalWorkflowApi CHAT,

  /** Works on a delegated task in its own isolated history until it calls `finish_task`. */
  @ExperimentalWorkflowApi TASK,
}

/**
 * Whether an agent in this mode is offered to its parent as a tool rather than reached by transfer.
 */
@OptIn(ExperimentalWorkflowApi::class)
internal val AgentMode?.runsAsTool: Boolean
  get() = this == AgentMode.TASK || this == AgentMode.SINGLE_TURN
