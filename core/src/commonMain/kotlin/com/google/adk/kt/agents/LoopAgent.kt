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
import com.google.adk.kt.logging.LoggerFactory
import kotlin.jvm.JvmStatic
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Persistent state of a [LoopAgent].
 *
 * @property currentSubAgent Name of the current or next sub-agent.
 * @property timesLooped Number of completed loop iterations.
 */
data class LoopAgentState(val currentSubAgent: String, val timesLooped: Int) : AgentState {
  override fun toStateValue(): TypedData.MapValue {
    return TypedData.MapValue(
      mapOf(
        KEY_CURRENT_SUB_AGENT to TypedData.StringValue(currentSubAgent),
        KEY_TIMES_LOOPED to TypedData.IntValue(timesLooped),
      )
    )
  }

  companion object {
    private const val KEY_CURRENT_SUB_AGENT = "current_sub_agent"
    private const val KEY_TIMES_LOOPED = "times_looped"

    fun fromValue(node: TypedData.MapValue): LoopAgentState? {
      val currentSubAgent = node.getString(KEY_CURRENT_SUB_AGENT) ?: return null
      val timesLooped = node.getInt(KEY_TIMES_LOOPED) ?: return null
      return LoopAgentState(currentSubAgent, timesLooped)
    }
  }
}

/**
 * A shell agent that runs its sub-agents in a loop.
 *
 * It stops when a sub-agent generates an event with escalate or maxIterations are reached.
 *
 * @property maxIterations The maximum number of iterations to run the loop. If null, runs
 *   indefinitely until escalate.
 */
class LoopAgent(
  name: String,
  val maxIterations: Int? = null,
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

  override fun runAsyncImpl(context: InvocationContext): Flow<Event> = flow {
    if (subAgents.isEmpty()) return@flow

    val agentState = context.agentStates[name] as? TypedData.MapValue
    val loopState = agentState?.let { LoopAgentState.fromValue(it) }

    var timesLooped = loopState?.timesLooped ?: 0
    val startSubAgentName = loopState?.currentSubAgent
    var startIndex = subAgents.findIndexForResumption(startSubAgentName, logger)

    var isResuming = startSubAgentName != null

    var shouldExit = false
    var pauseInvocation = false

    outer@ while (
      (maxIterations == null || timesLooped < maxIterations) && !shouldExit && !pauseInvocation
    ) {
      for (subAgent in subAgents.subList(startIndex, subAgents.size)) {

        if (context.isResumable && !isResuming) {
          saveAndEmitState(context, LoopAgentState(subAgent.name, timesLooped))
        }

        isResuming = false

        // Emit all of the sub-agent's events, then decide whether to stop. We must not truncate the
        // sub-agent's stream early (e.g. via takeWhile): the event that triggers a pause -- a
        // long-running function call -- is immediately followed by its function-response event,
        // which still needs to be emitted. Mirrors Python ADK 1.x loop_agent.
        subAgent.runAsync(context).collect { event ->
          emit(event)
          if (event.actions.escalate) {
            shouldExit = true
          }
          if (context.shouldPauseInvocation(event)) {
            pauseInvocation = true
          }
        }

        if (shouldExit || pauseInvocation) {
          break@outer
        }
      }

      if (!pauseInvocation) {
        timesLooped++
        startIndex = 0
        context.resetSubAgentStates(name)
      }
    }

    if (pauseInvocation) {
      return@flow
    }

    if (context.isResumable) {
      emitEndOfAgent(context)
    }
  }

  /**
   * Fluent builder for [LoopAgent], provided primarily for Java callers. Any property left unset
   * falls back to the same default as the constructor.
   */
  @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
  class Builder {
    private var name: String? = null
    private var maxIterations: Int? = null
    private var description: String = ""
    private var subAgents: List<BaseAgent> = emptyList()
    private var beforeAgentCallbacks: List<BeforeAgentCallback> = emptyList()
    private var afterAgentCallbacks: List<AfterAgentCallback> = emptyList()

    fun name(name: String): Builder = apply { this.name = name }

    fun maxIterations(maxIterations: Int?): Builder = apply { this.maxIterations = maxIterations }

    fun description(description: String): Builder = apply { this.description = description }

    fun subAgents(subAgents: List<BaseAgent>): Builder = apply { this.subAgents = subAgents }

    fun subAgents(vararg subAgents: BaseAgent): Builder = apply {
      this.subAgents = subAgents.toList()
    }

    fun beforeAgentCallbacks(callbacks: List<BeforeAgentCallback>): Builder = apply {
      this.beforeAgentCallbacks = callbacks
    }

    fun beforeAgentCallbacks(vararg callbacks: BeforeAgentCallback): Builder = apply {
      this.beforeAgentCallbacks = callbacks.toList()
    }

    fun afterAgentCallbacks(callbacks: List<AfterAgentCallback>): Builder = apply {
      this.afterAgentCallbacks = callbacks
    }

    fun afterAgentCallbacks(vararg callbacks: AfterAgentCallback): Builder = apply {
      this.afterAgentCallbacks = callbacks.toList()
    }

    fun build(): LoopAgent =
      LoopAgent(
        name = checkNotNull(name) { "LoopAgent.Builder requires name to be set." },
        maxIterations = maxIterations,
        description = description,
        subAgents = subAgents,
        beforeAgentCallbacks = beforeAgentCallbacks,
        afterAgentCallbacks = afterAgentCallbacks,
      )
  }

  companion object {
    @JvmStatic fun builder(): Builder = Builder()

    private val logger = LoggerFactory.getLogger(LoopAgent::class)
  }
}
