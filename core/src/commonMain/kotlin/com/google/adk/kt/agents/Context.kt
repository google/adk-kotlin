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

import com.google.adk.kt.SchemaUtils
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
import com.google.adk.kt.types.Schema
import com.google.adk.kt.workflow.BranchPath
import com.google.adk.kt.workflow.DynamicNodeFailedException
import com.google.adk.kt.workflow.EventSink
import com.google.adk.kt.workflow.Node
import com.google.adk.kt.workflow.NodeExecutionFailure
import com.google.adk.kt.workflow.NodeInterruptedException
import com.google.adk.kt.workflow.NodeRunner
import com.google.adk.kt.workflow.OutputState
import com.google.adk.kt.workflow.Route
import com.google.adk.kt.workflow.Workflow
import com.google.adk.kt.workflow.validateNodeName
import com.google.adk.kt.workflow.validateRunId
import com.google.errorprone.annotations.CanIgnoreReturnValue
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

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
   * @param useAsOutput Whether this node's output also serves as its parent's output.
   * @param childRunIds Per-name run-ID counters for this node's children.
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
    useAsOutput: Boolean = false,
    nodePath: String? = null,
    childRunIds: ChildRunIds = ChildRunIds(),
  ) : this(invocationContext, actions) {
    this.parent = parent
    this.runId = runId
    this.attemptCount = attemptCount
    this.resumeInputs = resumeInputs
    val parentState = parent?.nodeState
    val resolvedNodePath = nodePath ?: buildNodePath(parentState?.nodePath, node.name, runId)
    val resolvedEventAuthor = parentState?.eventAuthor ?: ""
    val outputForAncestors =
      if (useAsOutput && parentState != null) {
        parentState.outputFor
      } else {
        emptyList()
      }
    this.nodeState =
      NodeExecutionState(
        node = node,
        eventSink = eventSink,
        nodePath = resolvedNodePath,
        eventAuthor = resolvedEventAuthor,
        outputParent = if (useAsOutput) parentState else null,
        outputForAncestors = outputForAncestors,
        childRunIds = childRunIds,
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
   * On a node activation, a [State.TEMP_PREFIX] key is held on this activation alone, reaching no
   * event and no successor node; every other key writes into [actions] `stateDelta` via
   * copy-on-write, so a holder of the previous [actions] instance does not see the write. Under a
   * node's state schema the write must match its declared key, or it fails.
   */
  fun updateState(key: String, value: Any) {
    effectiveStateSchema?.let { validateStateEntry(it, key, value) }
    val ns = nodeState
    if (ns != null && key.startsWith(State.TEMP_PREFIX)) {
      ns.transientState[key] = value
    } else {
      actions = actions.copy(stateDelta = (actions.stateDelta + (key to value)).toMutableMap())
    }
  }

  /** Validates every entry of [delta] against the state schema in force here, if there is one. */
  internal fun validateStateDelta(delta: Map<String, Any>) {
    val schema = effectiveStateSchema ?: return
    for ((key, value) in delta) validateStateEntry(schema, key, value)
  }

  /**
   * Validates one state write against [schema] without changing the value. A scoped key (`app:`,
   * `user:`, or `temp:`) passes unchecked, as does the removal sentinel, which stands for the key's
   * absence rather than a value of its type.
   */
  private fun validateStateEntry(schema: Schema, key: String, value: Any) {
    val scoped =
      key.startsWith(State.APP_PREFIX) ||
        key.startsWith(State.USER_PREFIX) ||
        key.startsWith(State.TEMP_PREFIX)
    if (scoped || value === State.REMOVED) return
    val propertySchema =
      requireNotNull(schema.properties?.get(key)) {
        "validation error: state key '$key' is not declared in the state schema."
      }
    SchemaUtils.validateValue(value, propertySchema, argsName = "state key '$key'").getOrThrow()
  }

  /**
   * The state schema in force here: this node's own [Node.stateSchema], or the nearest ancestor's.
   * Null off-graph, so a callback or tool context validates nothing.
   */
  @OptIn(ExperimentalWorkflowApi::class)
  private val effectiveStateSchema: Schema?
    get() {
      val ns = nodeState ?: return null
      return ns.node.stateSchema ?: parent?.effectiveStateSchema
    }

  /**
   * Merges the given event actions into the current event actions, replacing [actions] with the
   * merged result, after checking their state delta against the state schema in force here. Any
   * reference to [actions] taken before the merge will not receive subsequent writes.
   */
  fun mergeEventActions(actions: EventActions) {
    validateStateDelta(actions.stateDelta)
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
   * The node's output value. Can be set at most once per activation. Assigning `null` before any
   * output is a no-op.
   *
   * @throws IllegalStateException if an output was already produced, or if this node delegated its
   *   output via `useAsOutput`.
   */
  @ExperimentalWorkflowApi
  var output: Any?
    get() = requireNodeState().output
    set(value) {
      requireNodeState().produceOutput(value)
    }

  /** Whether an output has been produced, by this node or by a `useAsOutput` delegate. */
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

  /** Interrupt IDs this node activation is waiting on. */
  @ExperimentalWorkflowApi
  val interruptIds: Set<String>
    get() = requireNodeState().interruptIds

  /**
   * Executes [node] as a child of this node activation and returns its output.
   *
   * Child events are recorded under `<thisNodePath>/<node.name>@<runId>`. If [node] pauses for
   * input or fails, this call throws an internal exception that the workflow runtime catches to
   * mark this node as waiting or failed. The interrupt is delivered as a [Throwable] that is not an
   * [Exception], so `catch (e: Exception)` does not swallow it; do not wrap this call in
   * `runCatching` or `catch (t: Throwable)`.
   *
   * @param node Child node to execute.
   * @param nodeInput Input passed to [node], or `null` if none.
   * @param runId Explicit run ID for [node]. Must contain at least one non-digit character and must
   *   not contain `'/'`, `'@'`, or `'.'`. When `null` or empty, an incrementing numeric ID is
   *   generated per child node name.
   * @param useAsOutput When `true`, delegates this node's output to [node]: this node must not
   *   produce an output of its own afterwards, even if [node] finishes without one. The claim is
   *   released only if [node] fails before emitting an output, so a caller that catches the failure
   *   may fall back to its own output. Once [node] has emitted, its output is this node's output
   *   and successors receive it.
   * @param useSubBranch When `true`, runs [node] on a sub-branch `<branch>.<node.name>@<runId>`.
   * @param overrideBranch Branch for [node] to run on instead of this node's branch.
   * @param raiseOnWait When `true` and [node] is a [Workflow] or sets [Node.waitForOutput] but
   *   finishes without an output, aborts this node's run like an interrupt, without recording one:
   *   this node completes with any output it already produced, or else waits only if it sets
   *   [Node.waitForOutput].
   * @throws IllegalStateException if called outside a node activation, if this node does not set
   *   `rerunOnResume = true`, or if this node already produced or delegated its output and
   *   [useAsOutput] is `true`.
   * @throws IllegalArgumentException if [node]'s name or [runId] is invalid.
   */
  @CanIgnoreReturnValue
  @ExperimentalWorkflowApi
  suspend fun runNode(
    node: Node,
    nodeInput: Any? = null,
    runId: String? = null,
    useAsOutput: Boolean = false,
    useSubBranch: Boolean = false,
    overrideBranch: String? = null,
    raiseOnWait: Boolean = false,
  ): Any? {
    val ns = requireNodeState()
    check(ns.node.rerunOnResume) {
      "Node '${ns.node.name}' must set rerunOnResume = true to call runNode: a dynamically" +
        " dispatched child may be interrupted, and the workflow re-runs the caller on resume to" +
        " collect the child's output."
    }
    require(runId.isNullOrEmpty() || runId.any { !it.isDigit() }) {
      "runId \"$runId\" for node '${node.name}' must contain a non-digit character so it cannot" +
        " collide with auto-generated IDs."
    }
    return runNodeUnchecked(
      node,
      nodeInput,
      runId,
      useAsOutput = useAsOutput,
      useSubBranch = useSubBranch,
      overrideBranch = overrideBranch,
      raiseOnWait = raiseOnWait,
    )
  }

  /**
   * Runs [node] without the caller-policy checks of [runNode]: the caller need not set
   * [Node.rerunOnResume], and [runId] may be all digits. [node]'s name and [runId] are still
   * validated for path safety.
   */
  @CanIgnoreReturnValue
  @ExperimentalWorkflowApi
  internal suspend fun runNodeUnchecked(
    node: Node,
    nodeInput: Any? = null,
    runId: String? = null,
    useAsOutput: Boolean = false,
    useSubBranch: Boolean = false,
    overrideBranch: String? = null,
    raiseOnWait: Boolean = false,
  ): Any? {
    val ns = requireNodeState()
    val delegatesOutput = useAsOutput && ns.node !is Workflow
    if (delegatesOutput) ns.claimOutputDelegation()

    val childContext =
      try {
        runNodeForContext(
          node,
          nodeInput,
          runId,
          useAsOutput = useAsOutput,
          useSubBranch = useSubBranch,
          overrideBranch = overrideBranch,
        )
      } catch (e: Exception) {
        if (delegatesOutput) ns.releaseOutputDelegation()
        throw e
      }
    val childState = childContext.requireNodeState()
    childState.failure?.let {
      if (delegatesOutput) ns.releaseOutputDelegation()
      throw DynamicNodeFailedException(it.cause, it.nodePath)
    }
    if (childState.interruptIds.isNotEmpty()) {
      ns.addInterruptIds(childState.interruptIds)
      throw NodeInterruptedException()
    }
    val executedNode = childState.node
    if (
      raiseOnWait &&
        !childState.hasProducedOutput &&
        childContext.actions.transferToAgent == null &&
        (executedNode is Workflow || executedNode.waitForOutput)
    ) {
      throw NodeInterruptedException()
    }
    return childState.output
  }

  /** Runs [node] as a child activation and returns its [Context]. */
  @ExperimentalWorkflowApi
  internal suspend fun runNodeForContext(
    node: Node,
    nodeInput: Any? = null,
    runId: String? = null,
    useAsOutput: Boolean = false,
    useSubBranch: Boolean = false,
    overrideBranch: String? = null,
  ): Context {
    validateNodeName(node.name)
    val ns = requireNodeState()
    val id =
      if (runId.isNullOrEmpty()) {
        ns.nextChildRunId(node.name)
      } else {
        validateRunId(runId, node.name)
        runId
      }
    return NodeRunner(
        node = node,
        parent = this,
        runId = id,
        useAsOutput = useAsOutput,
        useSubBranch = useSubBranch,
        overrideBranch = overrideBranch,
      )
      .run(nodeInput)
  }

  private var nodeState: NodeExecutionState? = null

  internal fun requireNodeState(): NodeExecutionState =
    checkNotNull(nodeState) { "This member is available only on a node activation context." }

  companion object {
    internal fun buildNodePath(parentPath: String?, name: String, runId: String): String =
      BranchPath.appendSegment(parentPath, name, runId, separator = '/')
  }
}

/** Mutable execution state for a single node activation. */
@OptIn(ExperimentalAtomicApi::class)
internal class NodeExecutionState(
  val node: Node,
  val eventSink: EventSink,
  val nodePath: String,
  var eventAuthor: String,
  /** Parent state that delegated its output to this activation when `useAsOutput` is `true`. */
  private val outputParent: NodeExecutionState? = null,
  /** Ancestor node paths this activation's output also satisfies, nearest ancestor first. */
  val outputForAncestors: List<String> = emptyList(),
  private val childRunIds: ChildRunIds = ChildRunIds(),
) {
  /** Node paths this activation's output satisfies: [nodePath] followed by [outputForAncestors]. */
  val outputFor: List<String> = listOf(nodePath) + outputForAncestors

  private val interruptIdsRef = AtomicReference<Set<String>>(emptySet())
  private val outputStateRef = AtomicReference<OutputState>(OutputState.None)

  /** Interrupt IDs this activation is waiting on. */
  val interruptIds: Set<String>
    get() = interruptIdsRef.load()

  val transientState = mutableMapOf<String, Any>()
  var selectedRoutes: List<Route>? = null
  var routesEmitted: Boolean = false
  var failure: NodeExecutionFailure? = null

  /** The node's output value, or `null` if none was produced. */
  val output: Any?
    get() =
      when (val s = outputStateRef.load()) {
        OutputState.None,
        OutputState.Delegated -> null
        is OutputState.Produced -> s.value
        is OutputState.Emitted -> s.value
        is OutputState.DelegateEmitted -> s.value
      }

  /** Whether an output has been produced, by this activation or by a `useAsOutput` delegate. */
  val hasProducedOutput: Boolean
    get() = outputStateRef.load().let { it !is OutputState.None && it !is OutputState.Delegated }

  /**
   * Whether the output was marked emitted; a workflow's validated output is marked but never sent.
   */
  val hasEmittedOutput: Boolean
    get() =
      outputStateRef.load().let { it is OutputState.Emitted || it is OutputState.DelegateEmitted }

  /**
   * Records this activation's output value. Assigning `null` before any output is a no-op; any
   * assignment after an output was produced or delegated throws [IllegalStateException].
   */
  fun produceOutput(value: Any?) {
    while (true) {
      when (val current = outputStateRef.load()) {
        OutputState.None -> {
          if (value == null) return
          if (outputStateRef.compareAndSet(current, OutputState.Produced(value))) return
        }
        OutputState.Delegated,
        is OutputState.DelegateEmitted -> error(delegatedOutputMessage())
        is OutputState.Produced,
        is OutputState.Emitted -> error(secondOutputMessage())
      }
    }
  }

  /** Marks the produced output as emitted and records it on every delegating ancestor. */
  fun markOutputEmitted() {
    while (true) {
      when (val current = outputStateRef.load()) {
        is OutputState.Produced -> {
          if (!outputStateRef.compareAndSet(current, OutputState.Emitted(current.value))) continue
          var ancestor = outputParent
          while (ancestor != null) {
            ancestor.recordDelegatedOutput(current.value)
            ancestor = ancestor.outputParent
          }
          return
        }
        is OutputState.Emitted,
        is OutputState.DelegateEmitted -> error("Node '${node.name}' output was already emitted.")
        OutputState.None,
        OutputState.Delegated -> error("Node '${node.name}' has no produced output to emit.")
      }
    }
  }

  /**
   * Records [value] as the output a `useAsOutput` descendant marked emitted for this activation. A
   * no-op unless this activation delegated its output; a later value (a workflow's validated
   * output, marked emitted but never sent) replaces its terminal node's raw one.
   */
  private fun recordDelegatedOutput(value: Any) {
    while (true) {
      val current = outputStateRef.load()
      if (current !is OutputState.Delegated && current !is OutputState.DelegateEmitted) return
      if (outputStateRef.compareAndSet(current, OutputState.DelegateEmitted(value))) return
    }
  }

  /** Selects the outgoing routes for this activation. */
  fun selectRoutes(routes: List<Route>?) {
    selectedRoutes = routes
    routesEmitted = false
  }

  /** Adds [ids] to the set of interrupts this activation is waiting on. */
  fun addInterruptIds(ids: Collection<String>) {
    if (ids.isEmpty()) return
    while (true) {
      val current = interruptIdsRef.load()
      if (interruptIdsRef.compareAndSet(current, current + ids)) return
    }
  }

  /** Returns the next run ID for a dynamically dispatched child named [name]. */
  fun nextChildRunId(name: String): String = childRunIds.next(name)

  /**
   * Claims this activation's output for a `useAsOutput` child, or throws if it is already taken.
   */
  fun claimOutputDelegation() {
    while (true) {
      when (val current = outputStateRef.load()) {
        OutputState.None -> if (outputStateRef.compareAndSet(current, OutputState.Delegated)) return
        OutputState.Delegated,
        is OutputState.DelegateEmitted ->
          error("Node '${node.name}' already delegated its output to a useAsOutput child.")
        is OutputState.Produced,
        is OutputState.Emitted ->
          error(
            "Node '${node.name}' already produced an output and cannot delegate it to a" +
              " useAsOutput child."
          )
      }
    }
  }

  /**
   * Releases the claim after a delegate failed before emitting; a no-op once the delegate's output
   * is recorded.
   */
  fun releaseOutputDelegation() {
    outputStateRef.compareAndSet(OutputState.Delegated, OutputState.None)
  }

  private fun secondOutputMessage() =
    "Node '${node.name}' produced a second output; a node produces at most one output."

  private fun delegatedOutputMessage() =
    "Node '${node.name}' delegated its output to a useAsOutput child and must not produce one of" +
      " its own."
}

/** Per-child-name run-ID counters for a node activation. */
@OptIn(ExperimentalAtomicApi::class)
internal class ChildRunIds {
  private val counters = AtomicReference<Map<String, Int>>(emptyMap())

  /** Returns the next 1-based run ID for a child named [name]. */
  fun next(name: String): String {
    while (true) {
      val current = counters.load()
      val next = (current[name] ?: 0) + 1
      if (counters.compareAndSet(current, current + (name to next))) return next.toString()
    }
  }
}
