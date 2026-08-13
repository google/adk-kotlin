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

package com.google.adk.kt.events

import com.google.adk.kt.agents.TypedData
import com.google.adk.kt.collections.concurrentMutableMapOf
import com.google.adk.kt.sessions.State
import kotlin.jvm.JvmStatic
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Represents the actions attached to an event.
 *
 * @property skipSummarization If true, it won't call the model to summarize the function response.
 *   Only used for a function response event.
 * @property stateDelta Indicates that the event is updating the state with the given delta.
 * @property artifactDelta Indicates that the event is updating an artifact. The key is the
 *   filename, and the value is the version.
 * @property transferToAgent If set, the event transfers to the specified agent.
 * @property escalate The agent is escalating to a higher level agent.
 * @property endOfAgent If true, the current agent has finished its current run. Note that there can
 *   be multiple events with [endOfAgent] set to `true` for the same agent within one invocation
 *   when there is a loop. The ADK workflow sets this when an agent's run completes naturally. In
 *   addition, tools and callbacks may set this on an event they produce to request the current LLM
 *   agent's per-step loop to stop after the current step, mirroring Java ADK's
 *   `EventActions.setEndInvocation(true)` / `setEndOfAgent(true)`. Note: this only stops the
 *   current LLM agent's step loop; it does not terminate an enclosing workflow agent
 *   (`SequentialAgent`, `LoopAgent`, `ParallelAgent`). To break out of a `LoopAgent`, set
 *   [escalate] instead. `CallbackContext.endInvocation()` / `ToolContext.endInvocation()` are
 *   convenience helpers for the same per-agent stop signal via
 *   [InvocationContext.isEndOfInvocation].
 * @property requestedToolConfirmations A map of tool confirmations requested by this event, keyed
 *   by function call ID.
 * @property rewindBeforeInvocationId If set, the agent will rewind history before the specified
 *   invocation ID.
 * @property agentState The state of the agent for resumability.
 * @property compaction If set, this event carries a context-compaction summary that replaces the
 *   compacted range of events when the next LLM prompt is built. See [EventCompaction].
 */
@Serializable
data class EventActions(
  var skipSummarization: Boolean = false,
  val stateDelta: MutableMap<String, @Contextual Any> = concurrentMutableMapOf(),
  val artifactDelta: MutableMap<String, Int> = concurrentMutableMapOf(),
  var transferToAgent: String? = null,
  var escalate: Boolean = false,
  var endOfAgent: Boolean = false,
  val requestedToolConfirmations: MutableMap<String, ToolConfirmation> = concurrentMutableMapOf(),
  var rewindBeforeInvocationId: String? = null,
  var agentState: TypedData? = null,
  var compaction: EventCompaction? = null,
) {
  /**
   * Removes a key from the state delta.
   *
   * @param key The key to remove.
   */
  fun removeStateByKey(key: String) {
    stateDelta[key] = State.REMOVED
  }

  /**
   * Merges this [EventActions] with another one.
   *
   * @param other The other [EventActions] to merge with.
   * @return A new [EventActions] object containing the merged results.
   */
  fun mergeWith(other: EventActions): EventActions =
    copy(
      skipSummarization = this.skipSummarization || other.skipSummarization,
      stateDelta =
        concurrentMutableMapOf<String, Any>().apply {
          putAll(this@EventActions.stateDelta)
          putAll(other.stateDelta)
        },
      artifactDelta =
        concurrentMutableMapOf<String, Int>().apply {
          putAll(this@EventActions.artifactDelta)
          putAll(other.artifactDelta)
        },
      transferToAgent = other.transferToAgent ?: this.transferToAgent,
      escalate = this.escalate || other.escalate,
      endOfAgent = this.endOfAgent || other.endOfAgent,
      requestedToolConfirmations =
        concurrentMutableMapOf<String, ToolConfirmation>().apply {
          putAll(this@EventActions.requestedToolConfirmations)
          putAll(other.requestedToolConfirmations)
        },
      rewindBeforeInvocationId = other.rewindBeforeInvocationId ?: this.rewindBeforeInvocationId,
      agentState = other.agentState ?: this.agentState,
      compaction = other.compaction ?: this.compaction,
    )

  /**
   * Fluent builder for [EventActions], provided primarily for Java callers. Any property left unset
   * falls back to the same default as the constructor.
   */
  @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
  class Builder {
    private var skipSummarization: Boolean = false
    private var stateDelta: MutableMap<String, @Contextual Any> = concurrentMutableMapOf()
    private var artifactDelta: MutableMap<String, Int> = concurrentMutableMapOf()
    private var transferToAgent: String? = null
    private var escalate: Boolean = false
    private var endOfAgent: Boolean = false
    private var requestedToolConfirmations: MutableMap<String, ToolConfirmation> =
      concurrentMutableMapOf()
    private var rewindBeforeInvocationId: String? = null
    private var agentState: TypedData? = null
    private var compaction: EventCompaction? = null

    fun skipSummarization(skipSummarization: Boolean): Builder = apply {
      this.skipSummarization = skipSummarization
    }

    fun stateDelta(stateDelta: MutableMap<String, @Contextual Any>): Builder = apply {
      this.stateDelta = stateDelta
    }

    fun artifactDelta(artifactDelta: MutableMap<String, Int>): Builder = apply {
      this.artifactDelta = artifactDelta
    }

    fun transferToAgent(transferToAgent: String?): Builder = apply {
      this.transferToAgent = transferToAgent
    }

    fun escalate(escalate: Boolean): Builder = apply { this.escalate = escalate }

    fun endOfAgent(endOfAgent: Boolean): Builder = apply { this.endOfAgent = endOfAgent }

    fun requestedToolConfirmations(
      requestedToolConfirmations: MutableMap<String, ToolConfirmation>
    ): Builder = apply { this.requestedToolConfirmations = requestedToolConfirmations }

    fun rewindBeforeInvocationId(rewindBeforeInvocationId: String?): Builder = apply {
      this.rewindBeforeInvocationId = rewindBeforeInvocationId
    }

    fun agentState(agentState: TypedData?): Builder = apply { this.agentState = agentState }

    fun compaction(compaction: EventCompaction?): Builder = apply { this.compaction = compaction }

    fun build(): EventActions =
      EventActions(
        skipSummarization = skipSummarization,
        stateDelta = stateDelta,
        artifactDelta = artifactDelta,
        transferToAgent = transferToAgent,
        escalate = escalate,
        endOfAgent = endOfAgent,
        requestedToolConfirmations = requestedToolConfirmations,
        rewindBeforeInvocationId = rewindBeforeInvocationId,
        agentState = agentState,
        compaction = compaction,
      )
  }

  companion object {
    @JvmStatic fun builder(): Builder = Builder()
  }
}
