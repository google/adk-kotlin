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

@file:OptIn(ExperimentalResumabilityFeature::class)

package com.google.adk.kt.runners

import com.google.adk.kt.agents.BaseAgent
import com.google.adk.kt.agents.ContextCacheConfig
import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.ResumabilityConfig
import com.google.adk.kt.agents.RunConfig
import com.google.adk.kt.agents.findAgent
import com.google.adk.kt.annotations.ExperimentalResumabilityFeature
import com.google.adk.kt.apps.App
import com.google.adk.kt.artifacts.ArtifactService
import com.google.adk.kt.callbacks.CallbackChoice
import com.google.adk.kt.callbacks.runAfterRunCallbacksPipeline
import com.google.adk.kt.callbacks.runBeforeRunCallbacksPipeline
import com.google.adk.kt.callbacks.runOnEventCallbacksPipeline
import com.google.adk.kt.callbacks.runOnUserMessageCallbacksPipeline
import com.google.adk.kt.events.Event
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.ids.Uuid
import com.google.adk.kt.logging.LoggerFactory
import com.google.adk.kt.memory.MemoryService
import com.google.adk.kt.plugins.PluginManager
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.sessions.SessionService
import com.google.adk.kt.sessions.State
import com.google.adk.kt.summarizer.EventsCompactionConfig
import com.google.adk.kt.summarizer.LlmEventSummarizer
import com.google.adk.kt.summarizer.SlidingWindowEventCompactor
import com.google.adk.kt.telemetry.trace
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/** An abstract base class for [Runner] implementations that provides common orchestration logic. */
abstract class AbstractRunner : Runner {

  val app: App?
  final override val appName: String
  final override val agent: BaseAgent
  final override val sessionService: SessionService
  final override val artifactService: ArtifactService?
  final override val memoryService: MemoryService?
  final override val pluginManager: PluginManager
  final override val resumabilityConfig: ResumabilityConfig

  /**
   * The context cache configuration applied to invocations created by this runner, or `null` when
   * context caching is disabled. Only the [App]-based constructor populates this from
   * [App.contextCacheConfig].
   */
  val contextCacheConfig: ContextCacheConfig?

  /** Creates a runner from explicit fields, not using an [App]. */
  constructor(
    appName: String,
    agent: BaseAgent,
    sessionService: SessionService,
    artifactService: ArtifactService?,
    memoryService: MemoryService?,
    pluginManager: PluginManager,
    resumabilityConfig: ResumabilityConfig = ResumabilityConfig(),
  ) {
    this.appName = appName
    this.agent = agent
    this.sessionService = sessionService
    this.artifactService = artifactService
    this.memoryService = memoryService
    this.pluginManager = pluginManager
    this.resumabilityConfig = resumabilityConfig
    this.app = null
    this.contextCacheConfig = null
  }

  /**
   * Creates a runner from an [App], deriving its [App.appName], [App.rootAgent], [App.plugins], and
   * [App.resumabilityConfig].
   *
   * This is the recommended way to configure plugins and resumability: the [App] is the single
   * source of truth. The field-based primary constructor still accepts a [pluginManager] and
   * [resumabilityConfig] for backward compatibility.
   *
   * The compaction config is resolved at construction (failing fast if a default summarizer is
   * required but the root agent is not an [LlmAgent]) and stored back on the [app], so
   * [App.eventsCompactionConfig] returns the effective config.
   */
  constructor(
    app: App,
    sessionService: SessionService,
    artifactService: ArtifactService?,
    memoryService: MemoryService?,
  ) {
    this.appName = app.appName
    this.agent = app.rootAgent
    this.sessionService = sessionService
    this.artifactService = artifactService
    this.memoryService = memoryService
    this.pluginManager = PluginManager(app.plugins)
    this.resumabilityConfig = app.resumabilityConfig ?: ResumabilityConfig()
    this.contextCacheConfig = app.contextCacheConfig
    this.app =
      app.copy(
        eventsCompactionConfig =
          resolveEventsCompactionConfig(app.rootAgent, app.eventsCompactionConfig)
      )
  }

  /**
   * Main entry method to run the agent in this runner.
   *
   * @param userId The user ID of the session.
   * @param sessionId The session ID of the session.
   * @param invocationId The invocation ID of the session, set this to resume an interrupted
   *   invocation.
   * @param newMessage A new message to append to the session.
   * @param stateDelta Optional state changes to apply to the session.
   * @param runConfig The run config for the agent.
   * @return The events generated by the agent.
   */
  override fun runAsync(
    userId: String,
    sessionId: String,
    invocationId: String?,
    newMessage: Content?,
    stateDelta: Map<String, Any>?,
    runConfig: RunConfig?,
  ): Flow<Event> =
    flow {
        // 1. Get or create session
        val key = SessionKey(appName, userId, sessionId)
        val session = sessionService.getSession(key) ?: sessionService.createSession(key)

        // 2. Build the invocation context (resolving the agent to run).
        val context =
          createInvocationContext(session, invocationId, newMessage, stateDelta, runConfig)

        // 3. No-op if the resolved agent for a resumed invocation is already final -- there is
        // nothing left to run. Mirrors Python ADK 1.x `runners.run_async`. For a new invocation
        // `endOfAgents` is empty, so this never short-circuits a fresh run.
        if (context.endOfAgents[context.agent.name] == true) {
          return@flow
        }

        // 4. Run agent with plugins
        emitAll(runAgentWithPlugins(context))

        // 5. Post-invocation context compaction. Runs once the agent has finished emitting and all
        // its events have been appended to `session`.
        runPostInvocationCompaction(session)
      }
      .trace("invocation")

  /**
   * Sync interface for local testing and convenience purpose.
   *
   * @param userId The user ID of the session.
   * @param sessionId The session ID of the session.
   * @param newMessage A new message to append to the session.
   * @param runConfig The run config for the agent.
   * @return The events generated by the agent.
   */
  override fun run(
    userId: String,
    sessionId: String,
    newMessage: Content,
    runConfig: RunConfig?,
  ): Iterator<Event> {
    return runBlocking {
      runAsync(
          userId = userId,
          sessionId = sessionId,
          newMessage = newMessage,
          runConfig = runConfig,
        )
        .toList()
        .iterator()
    }
  }

  override suspend fun rewindAsync(
    userId: String,
    sessionId: String,
    rewindBeforeInvocationId: String,
  ) {
    val key = SessionKey(appName, userId, sessionId)
    val session =
      sessionService.getSession(key)
        ?: throw IllegalArgumentException("Session not found: $sessionId")

    val rewindEventIndex =
      session.events.indexOfFirst { it.invocationId == rewindBeforeInvocationId }
    if (rewindEventIndex == -1) {
      throw IllegalArgumentException("Invocation ID not found: $rewindBeforeInvocationId")
    }

    val stateDelta = computeStateDeltaForRewind(session, rewindEventIndex)
    val artifactDelta = computeArtifactDeltaForRewind(session, rewindEventIndex)

    val rewindEvent =
      Event(
        invocationId = newInvocationId(),
        author = Role.USER,
        actions =
          EventActions(
            rewindBeforeInvocationId = rewindBeforeInvocationId,
            stateDelta = stateDelta,
            artifactDelta = artifactDelta,
          ),
      )

    val unused = sessionService.appendEvent(session, rewindEvent)
  }

  /**
   * Computes a state delta that, when applied, reverses every state mutation that happened on or
   * after [rewindEventIndex]. Keys prefixed with [State.APP_PREFIX] or [State.USER_PREFIX] are left
   * untouched. Mirrors Python ADK `runners.py:_compute_state_delta_for_rewind`.
   */
  private fun computeStateDeltaForRewind(
    session: Session,
    rewindEventIndex: Int,
  ): MutableMap<String, Any> {
    val stateAtRewindPoint = mutableMapOf<String, Any>()
    for (i in 0 until rewindEventIndex) {
      for ((k, v) in session.events[i].actions.stateDelta) {
        if (k.startsWith(State.APP_PREFIX) || k.startsWith(State.USER_PREFIX)) continue
        if (v === State.REMOVED) {
          stateAtRewindPoint.remove(k)
        } else {
          stateAtRewindPoint[k] = v
        }
      }
    }

    val currentState = session.state
    val rewindStateDelta = mutableMapOf<String, Any>()

    // 1. Restore keys whose values changed or vanished since the rewind point.
    for ((key, valueAtRewind) in stateAtRewindPoint) {
      if (currentState[key] != valueAtRewind) {
        rewindStateDelta[key] = valueAtRewind
      }
    }

    // 2. Mark keys added after the rewind point for removal. `State.REMOVED` is the sentinel that
    // `SessionService.appendEvent` is expected to recognize.
    for (key in currentState.keys) {
      if (key.startsWith(State.APP_PREFIX) || key.startsWith(State.USER_PREFIX)) continue
      if (key !in stateAtRewindPoint) {
        rewindStateDelta[key] = State.REMOVED
      }
    }

    return rewindStateDelta
  }

  /**
   * Computes an artifact delta that restores artifacts to their state at [rewindEventIndex] and
   * re-saves them under fresh version numbers. Artifacts prefixed with [State.USER_PREFIX] are
   * preserved. Mirrors Python ADK `runners.py:_compute_artifact_delta_for_rewind`.
   */
  private suspend fun computeArtifactDeltaForRewind(
    session: Session,
    rewindEventIndex: Int,
  ): MutableMap<String, Int> {
    val artifactService = this.artifactService ?: return mutableMapOf()

    val versionsAtRewindPoint = mutableMapOf<String, Int>()
    for (i in 0 until rewindEventIndex) {
      versionsAtRewindPoint.putAll(session.events[i].actions.artifactDelta)
    }

    val currentVersions = mutableMapOf<String, Int>()
    for (event in session.events) {
      currentVersions.putAll(event.actions.artifactDelta)
    }

    val rewindArtifactDelta = mutableMapOf<String, Int>()
    for ((filename, vn) in currentVersions) {
      // User artifacts outlive the rewind window, mirroring the Python behavior.
      if (filename.startsWith(State.USER_PREFIX)) continue
      val vt = versionsAtRewindPoint[filename]
      if (vt == vn) continue

      rewindArtifactDelta[filename] = vn + 1
      val artifact =
        if (vt == null) {
          // Artifact did not exist at rewind point. Mark it as inaccessible.
          Part(inlineData = Blob(mimeType = "application/octet-stream", data = ByteArray(0)))
        } else {
          // Load actual data via the artifact service (rather than constructing a `fileData` part)
          // because file-backed services (e.g. GCS) reject `fileData` writes.
          artifactService.loadArtifact(session.key, filename, version = vt)
            ?: run {
              logger.warn {
                "Artifact $filename version $vt not found during rewind for session " +
                  "${session.key.id}. Replacing with empty data."
              }
              Part(inlineData = Blob(mimeType = "application/octet-stream", data = ByteArray(0)))
            }
        }
      val unusedVersion = artifactService.saveArtifact(session.key, filename, artifact)
    }

    return rewindArtifactDelta
  }

  /**
   * Creates an [InvocationContext] for the given [session].
   *
   * @param session The current session.
   * @param invocationId An optional ID for the invocation.
   * @param newMessage The new message content, if any.
   * @param stateDelta Optional state changes.
   * @param runConfig Configuration for the run.
   * @return A newly created [InvocationContext].
   */
  protected suspend fun createInvocationContext(
    session: Session,
    invocationId: String?,
    newMessage: Content?,
    stateDelta: Map<String, Any>?,
    runConfig: RunConfig?,
  ): InvocationContext {
    val isResumable = resumabilityConfig.isResumable
    if (!isResumable) {
      if (newMessage == null) {
        throw IllegalArgumentException("No new message provided and session is not resumable")
      }
      return setupContextForNewInvocation(session, invocationId, newMessage, runConfig, stateDelta)
    }
    // Resumable mode: pick the existing invocation to resume if we can. If `newMessage` is a
    // `FunctionResponse`, look up the originating function-call event in session history and
    // resume its invocation. Otherwise fall back to the caller-supplied `invocationId`. If
    // neither resolves, treat this as a brand-new invocation. Mirrors Python ADK
    // `runners.py:_resolve_invocation_id`.
    val resolvedInvocationId = resolveInvocationId(session, newMessage, invocationId)
    if (resolvedInvocationId == null) {
      if (newMessage == null) {
        throw IllegalArgumentException(
          "No new message provided and no resumable invocation to resume"
        )
      }
      return setupContextForNewInvocation(session, invocationId, newMessage, runConfig, stateDelta)
    }
    return setupContextForResumedInvocation(
      session,
      newMessage,
      resolvedInvocationId,
      runConfig,
      stateDelta,
    )
  }

  /**
   * Resolves the invocation ID for a resumable runner call.
   *
   * - If [newMessage] carries a `FunctionResponse`, find the matching `FunctionCall` event in the
   *   session and return its `invocationId` (the response resumes that invocation).
   * - Otherwise return [invocationId] (which is typically `null` for a first-time call).
   *
   * Mirrors Python ADK `runners.py:_resolve_invocation_id`.
   */
  private fun resolveInvocationId(
    session: Session,
    newMessage: Content?,
    invocationId: String?,
  ): String? {
    val functionResponse =
      newMessage?.parts?.firstNotNullOfOrNull { it.functionResponse } ?: return invocationId
    val targetId = functionResponse.id ?: return invocationId
    val fcEvent =
      session.events.asReversed().firstOrNull { event ->
        event.functionCalls().any { it.id == targetId }
      }
        ?: throw IllegalArgumentException(
          "Function call event not found for function response id: $targetId"
        )
    return fcEvent.invocationId
  }

  /**
   * Runs the agent with the registered plugins.
   *
   * @param context The current [InvocationContext].
   * @return A [Flow] of [Event]s generated during the execution.
   */
  protected fun runAgentWithPlugins(context: InvocationContext): Flow<Event> {
    return flow {
      // 1. Run beforeRun callback
      val beforeResult = runBeforeRunCallbacksPipeline(pluginManager.beforeRunCallbacks, context)
      when (beforeResult) {
        is CallbackChoice.Break -> {
          context.isEndOfInvocation = true
          val earlyExitEvent =
            Event(
                invocationId = context.invocationId,
                author = Role.MODEL,
                content = beforeResult.value,
              )
              .let { applyRunConfigCustomMetadata(it, context.runConfig) }
          val shouldAppendEvent = true
          if (shouldAppendEvent) {
            val unused = sessionService.appendEvent(context.session, earlyExitEvent)
          }
          emit(earlyExitEvent)
        }
        is CallbackChoice.Continue -> {
          // 2. Dispatch to `context.agent` rather than the runner's root `agent`: on a follow-up
          // user turn, `findAgentToRun` may have selected a sub-agent based on the prior turn's
          // history (see `findAgentToRun` below for the selection rules).
          context.agent
            .runAsync(context)
            .map { applyRunConfigCustomMetadata(it, context.runConfig) }
            .collect { event ->
              val isLiveCall = false
              if (!isLiveCall) {
                if (event.partial == false) {
                  val unused = sessionService.appendEvent(context.session, event)
                }
              }
              val finalEvent =
                runOnEventCallbacksPipeline(pluginManager.onEventCallbacks, context, event).let {
                  applyRunConfigCustomMetadata(it, context.runConfig)
                }

              emit(finalEvent)
            }
        }
      }
      // 4. Run afterRun callback
      val unused = runAfterRunCallbacksPipeline(pluginManager.afterRunCallbacks, context)
    }
  }

  /**
   * Handles new user content by running plugins and appending events to the session.
   *
   * @param context The current [InvocationContext].
   * @param newMessage The new content from the user.
   * @param stateDelta Optional state changes.
   * @return The updated [InvocationContext].
   */
  protected suspend fun handleNewUserContent(
    context: InvocationContext,
    newMessage: Content,
    stateDelta: Map<String, Any>?,
  ): InvocationContext {
    val currentContext =
      context.copy(userContent = newMessage).let { updatedContext ->
        updatedContext.copy(
          userContent =
            runOnUserMessageCallbacksPipeline(
              pluginManager.onUserMessageCallbacks,
              updatedContext,
              newMessage,
            )
        )
      }

    val event =
      Event(
          invocationId = currentContext.invocationId,
          author = Role.USER,
          content = currentContext.userContent,
        )
        .apply { applyStateDelta(this, stateDelta) }
        .let { applyRunConfigCustomMetadata(it, context.runConfig) }
        .withBranchFromMatchingCall(currentContext)

    val unused = sessionService.appendEvent(context.session, event)

    return currentContext
  }

  /**
   * Applies the provided [stateDelta] to the given [event].
   *
   * @param event The event to modify.
   * @param stateDelta The state changes to apply.
   */
  fun applyStateDelta(event: Event, stateDelta: Map<String, Any>?) {
    if (stateDelta != null) {
      event.actions.stateDelta.putAll(stateDelta)
    }
  }

  private suspend fun Event.withBranchFromMatchingCall(context: InvocationContext): Event =
    context.findMatchingFunctionCall(this)?.branch?.let { branch -> this.copy(branch = branch) }
      ?: this

  /**
   * Sets up an [InvocationContext] for a brand new invocation.
   *
   * @param session The current session.
   * @param invocationId An optional ID for the invocation.
   * @param newMessage The initial user message.
   * @param runConfig Configuration for the run.
   * @param stateDelta Optional state changes.
   * @return The prepared [InvocationContext].
   */
  protected suspend fun setupContextForNewInvocation(
    session: Session,
    invocationId: String?,
    newMessage: Content,
    runConfig: RunConfig?,
    stateDelta: Map<String, Any>?,
  ): InvocationContext {
    // Create invocation context
    return InvocationContext(
        session = session,
        runConfig = runConfig,
        agent = agent,
        invocationId = invocationId ?: newInvocationId(),
        artifactService = artifactService,
        memoryService = memoryService,
        sessionService = sessionService,
        userContent = newMessage,
        pluginManager = pluginManager,
        resumabilityConfig = resumabilityConfig,
        contextCacheConfig = contextCacheConfig,
      )
      .let {
        // Run callbacks and append user message to session
        handleNewUserContent(it, newMessage, stateDelta)
      }
      .let {
        // Find agent to run in this invocation
        it.copy(agent = findAgentToRun(it, agent))
      }
  }

  /**
   * Sets up an [InvocationContext] for a resumed invocation.
   *
   * @param session The current session.
   * @param newMessage Optional new message content.
   * @param invocationId The ID of the invocation to resume.
   * @return The prepared [InvocationContext].
   * @throws UnsupportedOperationException if resumable sessions are not supported.
   */
  protected suspend fun setupContextForResumedInvocation(
    session: Session,
    newMessage: Content?,
    invocationId: String?,
    runConfig: RunConfig?,
    stateDelta: Map<String, Any>?,
  ): InvocationContext {
    if (session.events.isEmpty()) {
      throw IllegalArgumentException("Session ${session.key.id} has no events to resume.")
    }

    val effectiveInvocationId =
      invocationId
        ?: session.events.lastOrNull { it.invocationId != null }?.invocationId
        ?: throw IllegalArgumentException("No invocation ID found to resume.")

    val userMessage =
      newMessage
        ?: findUserMessageForInvocation(session.events, effectiveInvocationId)
        ?: throw IllegalArgumentException(
          "No user message available for resuming invocation: $effectiveInvocationId"
        )

    val context =
      InvocationContext(
        session = session,
        runConfig = runConfig,
        agent = agent,
        invocationId = effectiveInvocationId,
        artifactService = artifactService,
        memoryService = memoryService,
        sessionService = sessionService,
        userContent = userMessage,
        pluginManager = pluginManager,
        resumabilityConfig = resumabilityConfig,
        contextCacheConfig = contextCacheConfig,
      )

    val currentContext =
      if (newMessage != null) {
        handleNewUserContent(context, newMessage, stateDelta)
      } else {
        context
      }

    currentContext.populateInvocationAgentStates()

    return if (!currentContext.endOfAgents.containsKey(agent.name)) {
      currentContext.copy(agent = findAgentToRun(currentContext, agent))
    } else {
      currentContext
    }
  }

  private fun findUserMessageForInvocation(events: List<Event>, invocationId: String): Content? {
    for (event in events) {
      if (
        event.invocationId == invocationId &&
          event.author == "user" &&
          event.content != null &&
          event.content.parts.isNotEmpty() &&
          event.content.parts[0].text?.isNotEmpty() == true
      ) {
        return event.content
      }
    }
    return null
  }

  /**
   * Finds the appropriate [BaseAgent] to run for the given [session].
   *
   * @param context The current invocation context.
   * @param rootAgent The root agent of the runner.
   * @return The agent that should be executed.
   */
  protected suspend fun findAgentToRun(
    context: InvocationContext,
    rootAgent: BaseAgent,
  ): BaseAgent {
    val lastEvent = context.session.events.lastOrNull()

    // 1. If the last event is a USER function response, route it back to the agent that issued the
    // corresponding function call, regardless of that agent's type or transferability (e.g. a
    // remote a2a agent may surface a credential request as a long-running function call). Mirrors
    // Python ADK 1.x `runners.py:_find_agent_to_run`.
    if (lastEvent?.author == Role.USER && lastEvent.functionResponses().isNotEmpty()) {
      val matchingCall = context.findMatchingFunctionCall(lastEvent)
      if (matchingCall != null && matchingCall.author != Role.USER) {
        val agentToRun = rootAgent.findAgent(matchingCall.author) ?: rootAgent

        validatePeerTransfer(agentToRun, matchingCall.actions.transferToAgent, rootAgent)

        return agentToRun
      }
    }

    // 2. Scan backwards to find the last active agent.
    //
    // Both filters are off: this matches Python's `reversed(session.events)` in
    // `_find_agent_to_run` (see `runners.py` line 1185 for the equivalent). Filtering by
    // current branch would be wrong because the runner constructs a fresh context here whose
    // `branch` is null, while events from prior turns of sub-agents are tagged with non-null
    // branch paths (e.g. "root.subagent") - a branch filter would discard the very events we
    // need to look at to decide which sub-agent should keep handling the conversation.
    val filteredEvents =
      context.getEvents(currentInvocation = false, currentBranch = false).reversed().filter { event
        ->
        event.author != Role.USER && event.actions.agentState == null && !event.actions.endOfAgent
      }

    for (event in filteredEvents) {
      if (event.author == rootAgent.name) {
        return rootAgent
      }

      val agentToRun = rootAgent.findAgent(event.author) ?: continue

      // Peer transfer validation.
      validatePeerTransfer(agentToRun, event.actions.transferToAgent, rootAgent)

      // Check for transferability up the tree.
      var current: BaseAgent? = agentToRun
      var isTransferable = true
      while (current != null && current != rootAgent) {
        if (current.disallowTransferToParent) {
          isTransferable = false
          break
        }
        current = current.parentAgent
      }

      if (isTransferable) {
        return agentToRun
      }
    }

    return rootAgent
  }

  private fun validatePeerTransfer(agent: BaseAgent, targetName: String?, rootAgent: BaseAgent) {
    if (!agent.disallowTransferToPeers) return

    if (targetName != null && targetName != agent.name) {
      val targetAgent = rootAgent.findAgent(targetName)
      val parent = agent.parentAgent
      if (parent != null && targetAgent?.parentAgent?.name == parent.name) {
        throw IllegalArgumentException(
          "Agent '${agent.name}' is not allowed to transfer to peer '$targetName'."
        )
      }
    }
  }

  private fun applyRunConfigCustomMetadata(event: Event, runConfig: RunConfig?): Event {
    val runConfigMetadata = runConfig?.customMetadata
    if (runConfigMetadata.isNullOrEmpty()) return event

    return event.copy(customMetadata = runConfigMetadata + (event.customMetadata ?: emptyMap()))
  }

  private fun newInvocationId(): String {
    return "e-" + Uuid.random()
  }

  /**
   * Runs post-invocation sliding-window context compaction over [session] when configured. A no-op
   * when no compaction config was supplied or sliding-window compaction is not configured. The
   * compactor appends a single summary [Event] to [session] (via [sessionService]) when the
   * configured invocation interval is reached.
   */
  private suspend fun runPostInvocationCompaction(session: Session) {
    val config = app?.eventsCompactionConfig ?: return
    if (!config.hasSlidingWindowConfig()) return
    SlidingWindowEventCompactor(config).compact(session, sessionService)
  }

  private companion object {
    private val logger = LoggerFactory.getLogger(AbstractRunner::class)

    /**
     * Returns [config] with a default [LlmEventSummarizer] injected when compaction is configured
     * but no summarizer was supplied. The default summarizer uses [rootAgent]'s model, so
     * [rootAgent] must be an [LlmAgent] in that case; otherwise an [IllegalArgumentException] is
     * thrown. Returns [config] unchanged when it is `null` or already carries a summarizer. Any
     * configured compaction strategy needs a summarizer, so this does not depend on which strategy
     * is set.
     */
    private fun resolveEventsCompactionConfig(
      rootAgent: BaseAgent,
      config: EventsCompactionConfig?,
    ): EventsCompactionConfig? {
      if (config == null || config.summarizer != null) return config
      val model =
        (rootAgent as? LlmAgent)?.model
          ?: throw IllegalArgumentException("No BaseLlm model available for event compaction")
      return config.copy(summarizer = LlmEventSummarizer(model))
    }
  }
}
