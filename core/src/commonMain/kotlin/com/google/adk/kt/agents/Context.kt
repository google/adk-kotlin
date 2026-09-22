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
import com.google.adk.kt.workflow.BranchPath
import com.google.adk.kt.workflow.EventSink
import com.google.adk.kt.workflow.Node
import com.google.adk.kt.workflow.NodeExecutionFailure
import com.google.adk.kt.workflow.OutputRecord
import com.google.adk.kt.workflow.Route

/**
 * Execution context passed to agent callbacks, model callbacks, tools, and workflow nodes during an
 * invocation.
 *
 * Provides read access to invocation metadata and session state, along with methods to record state
 * deltas, artifacts, and control-flow signals. Tool-specific operations such as
 * [requestConfirmation] require a [functionCallId], which is only populated during a tool call. The
 * node-only members (such as [output] and [routes]) throw [IllegalStateException] unless this
 * context was created for a workflow node; a callback, model-callback, or tool context is not a
 * node activation and so has none of them.
 *
 * A context is created in one of two ways: the public constructor is used for callbacks and tools,
 * while the internal constructor is used for workflow node activations. Only the internal
 * constructor populates node execution state (such as [node] and [parent]); contexts created with
 * the public constructor leave that state empty, causing node-only members to throw.
 *
 * [CallbackContext] and [com.google.adk.kt.tools.ToolContext] are thin subclasses kept for backward
 * compatibility. Prefer [Context] in new code.
 *
 * This class is `open` only so those two subclasses can exist as distinct JVM types that Java
 * callers can reference. Every member is `final` so subclasses only forward constructor arguments
 * without changing behavior.
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

  /**
   * Creates the context of one node activation.
   *
   * The `node` and `eventSink` parameters have no counterpart on the public constructor, so this
   * constructor is what a call resolves to whenever they are supplied; that is why it needs no
   * separate marker to disambiguate it.
   *
   * @param eventSink Where this activation's events are sent; one workflow run shares a single
   *   sink.
   * @param actions Deltas this node accumulates, flushed onto the next event it emits.
   */
  @ExperimentalWorkflowApi
  internal constructor(
    invocationContext: InvocationContext,
    node: Node,
    eventSink: EventSink,
    parent: Context? = null,
    runId: String = "1",
    attemptCount: Int = 1,
    resumeInputs: Map<String, Any?> = emptyMap(),
    actions: EventActions = EventActions(),
    nodePath: String? = null,
  ) : this(invocationContext, actions) {
    this.parent = parent
    this.runId = runId
    this.attemptCount = attemptCount
    this.resumeInputs = resumeInputs
    val resolvedNodePath = nodePath ?: buildNodePath(parent?.nodeState?.nodePath, node.name, runId)
    val resolvedEventAuthor = parent?.nodeState?.eventAuthor ?: ""
    this.nodeState =
      NodeExecutionState(
        node = node,
        eventSink = eventSink,
        nodePath = resolvedNodePath,
        eventAuthor = resolvedEventAuthor,
      )
  }

  /** The context of the node that scheduled this one, or null at the root or off-graph. */
  @ExperimentalWorkflowApi
  var parent: Context? = null
    private set

  /**
   * This activation's id within its node, counting from "1". It is a string because it forms the
   * `name@runId` segment of a node path. "1" off-graph.
   */
  @ExperimentalWorkflowApi
  var runId: String = "1"
    private set

  /** 1-based attempt number, which a retry increments. 1 off-graph. */
  @ExperimentalWorkflowApi
  var attemptCount: Int = 1
    private set

  /** Answers to this node's interrupts, keyed by interrupt id. Empty off-graph. */
  @ExperimentalWorkflowApi
  var resumeInputs: Map<String, Any?> = emptyMap()
    private set

  // Delegate ReadonlyContext members explicitly rather than using `by`, because Kotlin generates
  // delegated interface members as `open` and every member of this class must remain `final`.
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
   * The event actions for the current context, holding state deltas and control-flow signals to be
   * attached to emitted events.
   */
  var actions: EventActions = actions ?: EventActions()
    private set

  /** The same [EventActions] instance as [actions], under the property name used by callbacks. */
  val eventActions: EventActions
    get() = actions

  // A fresh committed-only readonly view, matching the pre-unification `ToolContext.context`. Not
  // `this`, so a caller reading `context.context.state` still sees committed session state only.
  final override val context: ReadonlyContext
    get() = invocationContext.toReadonlyContext()

  /** Per-invocation scratch data; see [ContextFrameworkData.callbackContextData]. */
  @FrameworkInternalApi
  val callbackContextData: MutableMap<String, Any>
    get() = invocationContext.frameworkData.callbackContextData

  /**
   * The delta-aware state of the current session: committed session state merged with pending
   * [actions] `stateDelta` writes and this activation's transient writes, with removed keys
   * filtered out.
   *
   * This map is read-only; modify state through [updateState]. A [State.TEMP_PREFIX] key on a node
   * activation stays on that activation, per [updateState].
   */
  final override val state: Map<String, Any>
    get() = buildMap {
      putAll(invocationContext.session.state.toMap())
      putAll(actions.stateDelta)
      putAll(nodeState?.transientState.orEmpty())
      values.removeAll { it == State.REMOVED }
    }

  /**
   * Records a state change so it shows up in [state] and, on a callback or tool context, flushes on
   * the next event as a delta.
   *
   * On a node activation, a [State.TEMP_PREFIX] key is held on this activation alone: it shows up
   * in [state] but reaches no event and no successor node. Every other key writes into [actions]
   * `stateDelta` via copy-on-write, so a holder of the previous [actions] instance does not see the
   * write.
   */
  fun updateState(key: String, value: Any) {
    val ns = nodeState
    if (ns != null && key.startsWith(State.TEMP_PREFIX)) {
      ns.transientState[key] = value
    } else {
      actions = actions.copy(stateDelta = (actions.stateDelta + (key to value)).toMutableMap())
    }
  }

  /**
   * Merges the given event actions into the current event actions, replacing [actions] with the
   * merged result. Any reference to [actions] taken before the merge will not receive subsequent
   * writes.
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
   * Saves [artifact] under [name] in the current session, records the new version in [actions]'
   * `artifactDelta`, and returns the saved version number.
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

  /** The node this context is an activation of. */
  @ExperimentalWorkflowApi
  val node: Node
    get() = requireNodeState().node

  /** This activation's path: `name@runId` segments joined by `/`, rooted at the outermost node. */
  @ExperimentalWorkflowApi
  val nodePath: String
    get() = requireNodeState().nodePath

  /** The author stamped on events this node emits. */
  @ExperimentalWorkflowApi
  var eventAuthor: String
    get() = requireNodeState().eventAuthor
    set(value) {
      requireNodeState().eventAuthor = value
    }

  /**
   * The node's result. Settable once per activation, whether by emitting it or by assigning it.
   *
   * @throws IllegalStateException if set a second time.
   */
  @ExperimentalWorkflowApi
  var output: Any?
    get() = requireNodeState().output
    set(value) {
      requireNodeState().produceOutput(value)
    }

  /** Whether an output has been set, which distinguishes "no output" from "the output was null". */
  @ExperimentalWorkflowApi
  val hasProducedOutput: Boolean
    get() = requireNodeState().hasProducedOutput

  /** The routes this node selected, read by the scheduler to pick the outgoing edges. */
  @ExperimentalWorkflowApi
  var routes: List<Route>?
    get() = requireNodeState().selectedRoutes
    set(value) {
      requireNodeState().selectRoutes(value)
    }

  /**
   * The ids of the input requests this activation raised and is now waiting on, which the graph
   * pauses on until an answer arrives keyed by that id.
   */
  @ExperimentalWorkflowApi
  val interruptIds: Set<String>
    get() = requireNodeState().interruptIds.toSet()

  /**
   * This activation's node execution state. Present only on a node activation; null on a callback,
   * model-callback, or tool context, which is why the public node-only members below throw.
   */
  private var nodeState: NodeExecutionState? = null

  internal fun requireNodeState(): NodeExecutionState =
    checkNotNull(nodeState) { "This member is available only on a node activation context." }

  companion object {
    internal fun buildNodePath(parentPath: String?, name: String, runId: String): String =
      BranchPath.appendSegment(parentPath, name, runId, separator = '/')
  }
}

/**
 * The engine state of one node activation: the data a running node accumulates and the behavior
 * over it. A [Context] holds one only while it is a node activation, which is what makes Context's
 * node-only members throw on a callback or tool context.
 */
internal class NodeExecutionState(
  val node: Node,
  val eventSink: EventSink,
  val nodePath: String,
  var eventAuthor: String,
) {
  val interruptIds = mutableSetOf<String>()
  // Holds temporary state for the current activation only.
  val transientState = mutableMapOf<String, Any>()
  var selectedRoutes: List<Route>? = null
  var routesEmitted: Boolean = false
  var failure: NodeExecutionFailure? = null

  private var outputRecord: OutputRecord = OutputRecord.None

  /** The node's result, or null if none was produced or the produced value was null. */
  val output: Any?
    get() =
      when (val r = outputRecord) {
        is OutputRecord.None -> null
        is OutputRecord.Produced -> r.value
        is OutputRecord.Emitted -> r.value
      }

  /** Whether an output has been set, which distinguishes "no output" from "the output was null". */
  val hasProducedOutput: Boolean
    get() = outputRecord !is OutputRecord.None

  /** Whether an event carrying the output has already been sent. */
  val hasEmittedOutput: Boolean
    get() = outputRecord is OutputRecord.Emitted

  /** Records this activation's single output; a second call is a programming error. */
  fun produceOutput(value: Any?) {
    check(outputRecord is OutputRecord.None) {
      "Node '${node.name}' produced a second output; a node produces at most one output."
    }
    outputRecord = OutputRecord.Produced(value)
  }

  /** Marks the produced output as emitted on the wire; only a produced output may be marked. */
  fun markOutputEmitted() {
    when (val r = outputRecord) {
      is OutputRecord.Produced -> outputRecord = OutputRecord.Emitted(r.value)
      is OutputRecord.Emitted -> error("Node '${node.name}' output was already emitted.")
      is OutputRecord.None -> error("Node '${node.name}' has no produced output to emit.")
    }
  }

  /** Selects the outgoing routes; a fresh selection has not been dispatched on an event yet. */
  fun selectRoutes(routes: List<Route>?) {
    selectedRoutes = routes
    routesEmitted = false
  }

  /** Records the interrupts this activation is waiting on; [interruptIds] reads them back. */
  fun addInterruptIds(ids: Collection<String>) {
    interruptIds.addAll(ids)
  }
}
