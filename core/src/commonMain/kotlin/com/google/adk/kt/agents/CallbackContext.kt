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

import com.google.adk.kt.events.EventActions
import com.google.adk.kt.sessions.State
import com.google.adk.kt.types.Part

/**
 * The context provided to agents and tools during a callback, such as when a tool is run.
 *
 * It provides access to the current invocation context, event actions, and state.
 */
class CallbackContext(
  internal val invocationContext: InvocationContext,
  eventActions: EventActions? = null,
) : ReadonlyContext by ReadonlyContextImpl(invocationContext) {
  val agent: BaseAgent = invocationContext.agent

  var eventActions: EventActions = eventActions ?: EventActions()
    private set

  override val state: Map<String, Any>
    get() =
      (invocationContext.session.state.toMap() + eventActions.stateDelta).filterValues {
        it != State.REMOVED
      }

  /** Updates the state delta in the event actions. */
  fun updateState(key: String, value: Any) {
    val newDelta = (eventActions.stateDelta + (key to value)).toMutableMap()
    eventActions = eventActions.copy(stateDelta = newDelta)
  }

  /** Merges the given event actions into the current event actions. */
  fun mergeEventActions(actions: EventActions) {
    eventActions = eventActions.mergeWith(actions)
  }

  /**
   * Requests the current LLM agent to stop after the current step completes.
   *
   * Scope is exactly this LLM agent: the per-step loop in [LlmAgent.executeTurns] exits after the
   * current step, and this agent's remaining after-agent callbacks (the checks at the end of
   * [BaseAgent.runAsync]) are skipped.
   *
   * The flag does NOT propagate to any other agent. Enclosing workflow agents ([SequentialAgent],
   * [LoopAgent], [ParallelAgent]) do not read [InvocationContext.isEndOfInvocation], and each child
   * agent runs under its own context copy produced by `InvocationContext.forAgent(...)` /
   * branching, so the mutation never reaches the parent's context. In `Sequential[A, B]`, a
   * callback in `A` calling `endInvocation()` still lets `B` run. This matches Python ADK
   * (`sequential_agent.py:91-99`, `loop_agent.py:113-122`, per-agent context copy in
   * `base_agent.py:433`) and Java ADK (`LoopAgent.java:146` -> `takeUntil(hasEscalateAction)`,
   * per-agent `toBuilder()` in `InvocationContext.java:270`). To break out of a [LoopAgent], set
   * `EventActions.escalate = true` instead.
   *
   * Mirrors Python ADK's `callback_context._invocation_context.end_invocation = True` and Java
   * ADK's `EventActions.setEndInvocation(true)` / `setEndOfAgent(true)`.
   */
  fun endInvocation() {
    invocationContext.isEndOfInvocation = true
  }

  /**
   * Loads an artifact by [name] from the invocation's
   * [com.google.adk.kt.artifacts.ArtifactService]. Returns `null` if no artifact service is
   * configured or the artifact is not found.
   */
  suspend fun loadArtifact(name: String, version: Int? = null): Part? {
    val service = invocationContext.artifactService ?: return null
    return service.loadArtifact(invocationContext.session.key, name, version)
  }

  /**
   * Saves [artifact] under [name] on the invocation's
   * [com.google.adk.kt.artifacts.ArtifactService], records the new version into [eventActions]'
   * `artifactDelta`, and returns the version.
   *
   * Throws [IllegalStateException] if the invocation has no artifact service configured.
   */
  suspend fun saveArtifact(name: String, artifact: Part): Int {
    val service =
      invocationContext.artifactService
        ?: throw IllegalStateException(
          "artifactService not configured on invocation; cannot save artifact '$name'."
        )
    val version = service.saveArtifact(invocationContext.session.key, name, artifact)
    eventActions.artifactDelta[name] = version
    return version
  }

  /**
   * Lists the artifact names visible to this invocation. Returns an empty list if no artifact
   * service is configured.
   */
  suspend fun listArtifacts(): List<String> {
    val service = invocationContext.artifactService ?: return emptyList()
    return service.listArtifactKeys(invocationContext.session.key)
  }

  /**
   * Triggers memory generation for the current session.
   *
   * This method saves the current session's events to the memory service.
   */
  suspend fun addSessionToMemory() {
    val memoryService =
      invocationContext.memoryService
        ?: throw IllegalStateException(
          "Cannot add session to memory: memory service is not available."
        )
    memoryService.addSessionToMemory(invocationContext.session)
  }
}

/**
 * Creates a callback context for the current invocation.
 *
 * @param eventActions Optional initial event actions.
 */
internal fun InvocationContext.toCallbackContext(
  eventActions: EventActions? = null
): CallbackContext = CallbackContext(this, eventActions)
