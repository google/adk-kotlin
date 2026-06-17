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

import com.google.adk.kt.callbacks.AfterAgentCallback
import com.google.adk.kt.callbacks.BeforeAgentCallback
import com.google.adk.kt.events.Event
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.ids.Uuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge

/**
 * A shell agent that runs its sub-agents in parallel in an isolated manner.
 *
 * This approach is beneficial for scenarios requiring multiple perspectives or attempts on a single
 * task, such as:
 * - Running different algorithms simultaneously.
 * - Generating multiple responses for review by a subsequent evaluation agent.
 */
class ParallelAgent(
  name: String,
  description: String = "",
  subAgents: List<BaseAgent> = emptyList(),
  beforeAgentCallbacks: List<BeforeAgentCallback> = emptyList(),
  afterAgentCallbacks: List<AfterAgentCallback> = emptyList(),
) :
  BaseAgent(
    name = name,
    description = description,
    subAgents = subAgents,
    beforeAgentCallbacks = beforeAgentCallbacks,
    afterAgentCallbacks = afterAgentCallbacks,
  ) {

  override fun runAsyncImpl(context: InvocationContext): Flow<Event> =
    executeParallel(context) { agent, invocationContext -> agent.runAsync(invocationContext) }

  private fun executeParallel(
    context: InvocationContext,
    runBlock: (BaseAgent, InvocationContext) -> Flow<Event>,
  ): Flow<Event> = flow {
    if (subAgents.isEmpty()) return@flow

    val activeSubAgents = subAgents.filter { context.endOfAgents[it.name] != true }

    if (context.isResumable && !context.agentStates.containsKey(name)) {
      context.setAgentState(name, TypedData.MapValue(emptyMap()))
      emit(
        Event(
          id = Uuid.random(),
          invocationId = context.invocationId,
          author = name,
          branch = context.branch,
          actions = EventActions(agentState = TypedData.MapValue(emptyMap())),
        )
      )
    }

    var pauseInvocation = false

    // Run each sub-agent on its own isolated branch (`<parent>.<parallel>.<sub>`) so parallel
    // siblings cannot see each other's conversation history. This is the only place a branch is
    // deepened; plain agent runs and transfers keep their parent's branch. Mirrors Python ADK 1.x
    // `ParallelAgent._create_branch_ctx_for_sub_agent`.
    val flows = activeSubAgents.map { subAgent ->
      runBlock(subAgent, context.branch(this@ParallelAgent).branch(subAgent))
    }

    flows.merge().collect { event ->
      emit(event)
      if (context.shouldPauseInvocation(event)) {
        pauseInvocation = true
      }
    }

    if (pauseInvocation) return@flow

    if (context.isResumable && activeSubAgents.all { context.endOfAgents[it.name] == true }) {
      context.setAgentState(name, endOfAgent = true)
      emitEndOfAgent(context)
    }
  }
}
