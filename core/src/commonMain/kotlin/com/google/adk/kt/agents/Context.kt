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

import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.artifacts.ArtifactService
import com.google.adk.kt.events.Event
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.events.ToolConfirmation
import com.google.adk.kt.memory.MemoryEntry
import com.google.adk.kt.memory.MemoryService
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.sessions.State
import com.google.adk.kt.tools.ReadonlyToolContext
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part

/**
 * The context within an agent run: one unit of work inside an invocation, either a callback or a
 * tool call. It offers the invocation's readonly view plus the writes that flavor allows;
 * [requestConfirmation] needs a [functionCallId], which only a tool call has.
 *
 * [CallbackContext] and [com.google.adk.kt.tools.ToolContext] are thin subclasses that name the two
 * situations. They add nothing; prefer this type in new code.
 *
 * The class is `open` only so those two can exist as JVM types that Java callers can name. Every
 * member is final, so a subclass forwards a constructor and changes no behavior.
 *
 * @property toolConfirmation The tool confirmation of the current tool call.
 */
open class Context(
  val invocationContext: InvocationContext,
  actions: EventActions? = null,
  final override val functionCallId: String? = null,
  val toolConfirmation: ToolConfirmation? = null,
  final override val eventId: String? = null,
) : ReadonlyContext, ReadonlyToolContext {

  // Forwarded explicitly rather than with `by`, because a delegated member is emitted open and a
  // subclass could then override it. Every member of this class is final on purpose.
  private val readonly = ReadonlyContextImpl(invocationContext)

  val agent: BaseAgent = invocationContext.agent

  final override val session: Session
    get() = readonly.session

  final override val runConfig: RunConfig?
    get() = readonly.runConfig

  final override val invocationId: String
    get() = readonly.invocationId

  final override val agentName: String
    get() = readonly.agentName

  final override val userId: String
    get() = readonly.userId

  final override val userContent: Content?
    get() = readonly.userContent

  final override val branch: String?
    get() = readonly.branch

  final override val artifactService: ArtifactService?
    get() = readonly.artifactService

  final override val memoryService: MemoryService?
    get() = readonly.memoryService

  final override suspend fun getEvents(
    currentInvocation: Boolean,
    currentBranch: Boolean,
  ): List<Event> = readonly.getEvents(currentInvocation, currentBranch)

  /**
   * The event actions for the current context: the deltas and signals it accumulates, flushed onto
   * the events it produces.
   */
  var actions: EventActions = actions ?: EventActions()
    private set

  /** The same object as [actions], under the name agent and model callbacks use. */
  val eventActions: EventActions
    get() = actions

  final override val context: ReadonlyContext
    get() = this

  /** Per-invocation scratch data; see [ContextFrameworkData.callbackContextData]. */
  @FrameworkInternalApi
  val callbackContextData: MutableMap<String, Any>
    get() = invocationContext.frameworkData.callbackContextData

  /**
   * The delta-aware state of the current session: the session's own state with the pending delta
   * applied, and removed keys absent. It is read-only; change state through [updateState] (Python's
   * `ctx.state['foo'] = 'bar'` has no direct equivalent here).
   */
  final override val state: Map<String, Any>
    get() =
      (invocationContext.session.state.toMap() + actions.stateDelta).filterValues {
        it != State.REMOVED
      }

  /**
   * Updates the state delta in [actions], in place, so every holder of the current [actions] sees
   * the write.
   */
  fun updateState(key: String, value: Any) {
    actions.stateDelta[key] = value
  }

  /**
   * Merges the given event actions into the current event actions, replacing [actions] with the
   * merged result. A reference taken before the merge no longer receives later writes.
   */
  fun mergeEventActions(actions: EventActions) {
    this.actions = this.actions.mergeWith(actions)
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
   * callback or tool in `A` calling `endInvocation()` still lets `B` run. This matches Python ADK
   * (`sequential_agent.py:91-99`, `loop_agent.py:113-122`, per-agent context copy in
   * `base_agent.py:433`) and Java ADK (`LoopAgent.java:146` -> `takeUntil(hasEscalateAction)`,
   * per-agent `toBuilder()` in `InvocationContext.java:270`). To break out of a [LoopAgent], set
   * `EventActions.escalate = true` instead.
   *
   * Mirrors Python ADK's `callback_context._invocation_context.end_invocation = True` and Java
   * ADK's `EventActions.setEndInvocation(true)` / `setEndOfAgent(true)`. Tools may equivalently set
   * `actions.endOfAgent = true`; both paths cause [LlmAgent.executeTurns] to exit after the current
   * step.
   */
  fun endInvocation() {
    invocationContext.isEndOfInvocation = true
  }

  /**
   * Lists the filenames of the artifacts attached to the current session. Returns an empty list if
   * no artifact service is configured.
   */
  final override suspend fun listArtifacts(): List<String> {
    val service = invocationContext.artifactService ?: return emptyList()
    return service.listArtifactKeys(invocationContext.session.key)
  }

  /**
   * Loads an artifact attached to the current session by [name]. Returns `null` if no artifact
   * service is configured or the artifact is not found.
   *
   * @param version the version to load, or `null` for the latest.
   */
  final override suspend fun loadArtifact(name: String, version: Int?): Part? {
    val service = invocationContext.artifactService ?: return null
    return service.loadArtifact(invocationContext.session.key, name, version)
  }

  /**
   * Saves an artifact and records it as a delta for the current session: it writes [artifact] under
   * [name], records the new version into [actions]' `artifactDelta`, and returns the version.
   *
   * @throws IllegalStateException if the invocation has no artifact service configured.
   */
  suspend fun saveArtifact(name: String, artifact: Part): Int {
    val service =
      invocationContext.artifactService
        ?: throw IllegalStateException(
          "artifactService not configured on invocation; cannot save artifact '$name'."
        )
    val version = service.saveArtifact(invocationContext.session.key, name, artifact)
    actions.artifactDelta[name] = version
    return version
  }

  /**
   * Triggers memory generation for the current session.
   *
   * This saves the current session's events to the memory service, so the agent can recall
   * information from past interactions.
   *
   * @throws IllegalStateException if no memory service is configured on the invocation.
   */
  suspend fun addSessionToMemory() {
    val memoryService =
      invocationContext.memoryService
        ?: throw IllegalStateException(
          "Cannot add session to memory: memory service is not available."
        )
    memoryService.addSessionToMemory(invocationContext.session)
  }

  /**
   * Adds an explicit list of [events] to the memory service, scoped to the current session's
   * app/user/session ids.
   *
   * Unlike [addSessionToMemory], this persists only the given events (e.g. the latest turn) as an
   * incremental update rather than re-ingesting the full session.
   *
   * @param events The events to add to memory.
   * @param customMetadata Optional metadata forwarded to the configured memory service. Supported
   *   keys are implementation-specific.
   * @throws IllegalStateException if no memory service is configured on the invocation.
   */
  suspend fun addEventsToMemory(events: List<Event>, customMetadata: Map<String, Any?>? = null) {
    val memoryService =
      invocationContext.memoryService
        ?: throw IllegalStateException(
          "Cannot add events to memory: memory service is not available."
        )
    val session = invocationContext.session
    memoryService.addEventsToMemory(
      appName = session.key.appName,
      userId = session.key.userId,
      events = events,
      sessionId = session.key.id,
      customMetadata = customMetadata,
    )
  }

  /**
   * Adds explicit [memories] directly to the memory service, scoped to the current session's
   * app/user ids.
   *
   * This is for memory services that support direct memory writes, in addition to the event-based
   * generation done by [addSessionToMemory] / [addEventsToMemory].
   *
   * @param memories Explicit memory items to add.
   * @param customMetadata Optional metadata forwarded to the configured memory service. Supported
   *   keys are implementation-specific.
   * @throws IllegalStateException if no memory service is configured on the invocation.
   */
  suspend fun addMemory(memories: List<MemoryEntry>, customMetadata: Map<String, Any?>? = null) {
    val memoryService =
      invocationContext.memoryService
        ?: throw IllegalStateException("Cannot add memory: memory service is not available.")
    val session = invocationContext.session
    memoryService.addMemory(
      appName = session.key.appName,
      userId = session.key.userId,
      memories = memories,
      customMetadata = customMetadata,
    )
  }

  /**
   * Requests confirmation for the current tool call. Only a tool call has a [functionCallId], so
   * this can only be called in a tool context.
   *
   * @param hint A hint to the user on how to confirm the tool call.
   * @param payload The payload used to confirm the tool call.
   * @throws IllegalStateException if [functionCallId] is not set.
   */
  fun requestConfirmation(hint: String? = null, payload: Any? = null) {
    if (functionCallId == null) {
      throw IllegalStateException("functionCallId is not set.")
    }
    actions.requestedToolConfirmations[functionCallId] =
      ToolConfirmation(hint = hint, confirmed = false, payload = payload)
  }
}
