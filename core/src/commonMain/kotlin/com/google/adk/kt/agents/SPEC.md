# Agents

> Internal. Part of the ADK Kotlin specification: the repo-root `SPEC.md` is the
> charter and index, and `DECISIONS.md` holds the `[D-n]` design decisions. Not
> exported to GitHub.

*Package `com.google.adk.kt.agents`.*

This package defines the agent hierarchy and the per-invocation execution
context. Agents are constructed in code from plain constructors and callback
lambdas ([D-4] code-first); no configuration file or registry is involved. Every
agent exposes its work as a cold `Flow<Event>` ([D-2] Flow): nothing runs until
the flow is collected, and cancellation propagates through coroutine structured
concurrency. Resumability (checkpointing and resume) is an experimental, opt-in
feature - the APIs that enable it are annotated
`@ExperimentalResumabilityFeature` and require `@OptIn`.

## BaseAgent

Abstract base for all agents. Its `runAsync` is a template method that derives a
per-agent context, runs before/after-agent callbacks, wraps the subclass logic
in an `invoke_agent` span, and provides protected helpers for state
checkpointing. Subclasses implement only the protected abstract `runAsyncImpl`.

```kotlin
abstract class BaseAgent(
  val name: String,
  open val description: String = "",
  val subAgents: List<BaseAgent> = emptyList(),
  val beforeAgentCallbacks: List<BeforeAgentCallback> = emptyList(),
  val afterAgentCallbacks: List<AfterAgentCallback> = emptyList(),
  val disallowTransferToParent: Boolean = false,
  val disallowTransferToPeers: Boolean = false,
) {
  // Public entry point (text-based).
  fun runAsync(parentContext: InvocationContext): Flow<Event>

  // Protected helpers available to subclasses.
  protected fun <T> loadAgentState(context: InvocationContext, mapper: (TypedData?) -> T): T

  protected fun createStateEvent(context: InvocationContext, state: AgentState): Event

  protected suspend fun FlowCollector<Event>.saveAndEmitState(
    context: InvocationContext,
    state: AgentState,
  )

  protected suspend fun FlowCollector<Event>.emitEndOfAgent(context: InvocationContext)

  // Abstract method subclasses must implement.
  protected abstract fun runAsyncImpl(context: InvocationContext): Flow<Event>
}
```

**Logic.** `runAsync` is a cold `flow { }` wrapped by `.trace("invoke_agent
$name")` ([D-2] Flow):

1.  Derive context: `context = parentContext.forAgent(this)`. `forAgent` swaps
    only `agent` and keeps the parent's `branch` unchanged - entering an agent,
    including via `transfer_to_agent`, does not deepen the branch.
2.  Before-agent callbacks: merged plugins-first
    (`pluginManager.beforeAgentCallbacks + beforeAgentCallbacks`), run over a
    `CallbackContext`. If a callback returns content, emit that event.
3.  End-of-invocation short-circuit #1: if `context.isEndOfInvocation`, return
    without running core logic (a before-callback that returned content sets
    this flag).
4.  Core logic: `emitAll(runAsyncImpl(context))` - stream all subclass events.
5.  End-of-invocation short-circuit #2: if `context.isEndOfInvocation`, return -
    a tool/callback that ended the invocation mid-run suppresses the
    after-callbacks.
6.  After-agent callbacks: same shape with `afterAgentCallbacks`; unlike the
    before pipeline these never short-circuit the invocation. If a callback
    returns content or has pending actions, emit that event.
7.  The trace span records `GEN_AI_OPERATION_NAME`, `GEN_AI_SYSTEM`,
    `GEN_AI_AGENT_NAME`, `GEN_AI_AGENT_DESCRIPTION`, and the conversation id. No
    try/catch: exceptions from `runAsyncImpl` propagate to the collector.

Protected resumability helpers (used by the workflow agents):

-   `loadAgentState(context, mapper)` - `mapper(context.agentStates[name])`; the
    `TypedData?` may be null.
-   `createStateEvent(context, state)` - builds an actions-only `Event` carrying
    `EventActions(agentState = state.toStateValue())`; a pure checkpoint marker
    with no content.
-   `saveAndEmitState(context, state)` - persists then emits: first
    `context.setAgentState(name, state.toStateValue())`, then
    `emit(createStateEvent(...))`.
-   `emitEndOfAgent(context)` - emits an `Event` with a fresh id and
    `EventActions(endOfAgent = true)`, the marker that signals natural
    completion.

## findAgent

Top-level extension that locates an agent by name within a subtree.

```kotlin
fun BaseAgent.findAgent(targetName: String): BaseAgent?
```

**Logic.** Depth-first search including self: return `this` if the name matches,
otherwise recurse into `subAgents` and return the first hit, else `null`.

## LlmAgent

A model-backed agent. It assembles an `LlmRequest` through an ordered
request-processor pipeline, calls the model (streaming when configured),
executes tool calls, and loops steps until it produces a final response,
transfers, or the invocation ends. It supports tools/toolsets, instructions,
input/output schemas, an `outputKey` for writing results into session state,
model/tool callbacks, and a `maxSteps` cap.

```kotlin
class LlmAgent(
  name: String,
  val model: Model,
  description: String = "",
  subAgents: List<BaseAgent> = emptyList(),
  beforeAgentCallbacks: List<BeforeAgentCallback> = emptyList(),
  afterAgentCallbacks: List<AfterAgentCallback> = emptyList(),
  disallowTransferToParent: Boolean = false,
  disallowTransferToPeers: Boolean = false,
  val tools: List<BaseTool> = emptyList(),
  val toolsets: List<Toolset> = emptyList(),
  val generateContentConfig: GenerateContentConfig? = null,
  val instruction: Instruction? = null,
  val staticInstruction: Content? = null,
  val beforeModelCallbacks: List<BeforeModelCallback> = emptyList(),
  val afterModelCallbacks: List<AfterModelCallback> = emptyList(),
  val beforeToolCallbacks: List<BeforeToolCallback> = emptyList(),
  val afterToolCallbacks: List<AfterToolCallback> = emptyList(),
  val inputSchema: Schema? = null,
  val outputSchema: Schema? = null,
  val outputKey: String? = null,
  val onModelErrorCallbacks: List<OnModelErrorCallback> = emptyList(),
  val onToolErrorCallbacks: List<OnToolErrorCallback> = emptyList(),
  val includeContents: IncludeContents = IncludeContents.DEFAULT,
  val maxSteps: Int? = null,
) : BaseAgent(
  name = name,
  description = description,
  subAgents = subAgents,
  beforeAgentCallbacks = beforeAgentCallbacks,
  afterAgentCallbacks = afterAgentCallbacks,
  disallowTransferToParent = disallowTransferToParent,
  disallowTransferToPeers = disallowTransferToPeers,
) {

  enum class IncludeContents {
    DEFAULT,
    NONE,
  }

  override fun runAsyncImpl(context: InvocationContext): Flow<Event>  // protected (inherited)
}
```

**Logic.** `runAsyncImpl` is a cold flow with two entry modes -
resume-into-subagent and the normal step loop:

1.  Read `agentState = context.agentStates[name]`.
2.  Resume-into-subagent: if `agentState != null`, compute the sub-agent to
    resume from session history; if found, run it, mark `setAgentState(name,
    endOfAgent = true)`, `emitEndOfAgent`, and return. This resumes a prior
    transfer without re-invoking the model.
3.  Normal step loop (`executeTurns`): a `do { ... } while (...)` over single
    turns. Each iteration runs one turn, remembers `lastEvent`, and increments
    `stepsCompleted`. Per emitted event: attempt to write the output-key state,
    emit, then set `shouldPause` if the event is a long-running pause point.
4.  Termination: the loop continues only while ALL hold -
    `!context.isEndOfInvocation`, `lastEvent?.isFinalResponse == false`, and
    `lastEvent?.actions?.endOfAgent != true`. So it stops on end-of-invocation,
    a final-response event, an `endOfAgent` event (e.g. from a transferred-to
    sub-agent), a null last event, or the `maxSteps` break.
5.  `maxSteps` cap: checked after running a step with `>=`, so `maxSteps == N`
    runs exactly N steps. It is in-process and non-persisted (resets on runtime
    restart mid-invocation).
6.  Output-key writing (`maybeSaveOutputToState`): only for events authored by
    this agent that are final responses; concatenates non-thought text parts and
    writes to `event.actions.stateDelta[outputKey]`. With an `outputSchema`, it
    validates best-effort and stores raw text on failure.
7.  Transfer target computation: a function response carrying
    `actions.transferToAgent` is resolved against the agent tree; the
    `disallowTransferToParent` / `disallowTransferToPeers` flags (and, in the
    runner, `isTransferableAcrossAgentTree`) govern which targets are reachable,
    and the request-scoped `transfer_to_agent` tool is only injected when
    sub-agents/peers are reachable.
8.  Pause: if `shouldPause`, return WITHOUT emitting `endOfAgent`, so a later
    `runAsync` with the user's function response can resume the same agent. On
    resumable apps a two-event session-tail backstop re-checks the last
    persisted events before emitting `endOfAgent`.

## IncludeContents

Controls how much conversation history the `ContentsProcessor` feeds to the
model. `DEFAULT` includes history; `NONE` omits it. (Nested enum of `LlmAgent`;
listed above.)

## LoopAgent

Workflow agent that runs its sub-agents in order repeatedly until a sub-agent
escalates, a pause occurs, or `maxIterations` is reached. `maxIterations = null`
loops indefinitely until escalate.

```kotlin
class LoopAgent(
  name: String,
  val maxIterations: Int? = null,
  description: String = "",
  subAgents: List<BaseAgent> = emptyList(),
  beforeAgentCallbacks: List<BeforeAgentCallback> = emptyList(),
  afterAgentCallbacks: List<AfterAgentCallback> = emptyList(),
) : BaseAgent(
  name = name,
  description = description,
  subAgents = subAgents,
  beforeAgentCallbacks = beforeAgentCallbacks,
  afterAgentCallbacks = afterAgentCallbacks,
) {
  override fun runAsyncImpl(context: InvocationContext): Flow<Event>  // protected (inherited)
}
```

**Logic.** Cold flow; empty sub-agents is a no-op:

1.  Restore `LoopAgentState` from the checkpoint: `timesLooped` (default 0) and
    `currentSubAgent`; `startIndex =
    subAgents.findIndexForResumption(currentSubAgent)` (unknown/absent name
    falls back to index 0 with a warning). `isResuming = currentSubAgent !=
    null`.
2.  Labeled outer loop with three independent stop conditions: `maxIterations`
    cap, `shouldExit` (escalate), and `pauseInvocation`.
3.  Inner loop over `subAgents.subList(startIndex, size)`. Before each
    sub-agent, if `context.isResumable && !isResuming`,
    `saveAndEmitState(LoopAgentState(subAgent.name, timesLooped))` - the
    checkpoint names the next sub-agent and the current iteration count. Then
    `isResuming = false` (the resume-skip applies only to the first sub-agent of
    the resumed list).
4.  Collect the sub-agent's whole stream, emitting every event. Escalate
    detection reads `event.actions.escalate` off ANY event the sub-agent emits
    (not only its final one) and sets `shouldExit`;
    `shouldPauseInvocation(event)` sets `pauseInvocation`. The stream is
    intentionally not truncated early, so a long-running call event's following
    function-response event is still emitted.
5.  After a sub-agent, if `shouldExit || pauseInvocation`, `break@outer`.
6.  When an inner pass completes with no break and no pause: `timesLooped++`,
    reset `startIndex = 0`, and `resetSubAgentStates(name)` (recursively clears
    descendants' checkpoints so the next iteration starts fresh).
7.  After the loop: if paused, return with no `endOfAgent`; otherwise, if
    resumable, `emitEndOfAgent`. Note `endOfAgent` / `endInvocation` do NOT
    break the loop - only `escalate` does.

## LoopAgentState

Resume checkpoint for `LoopAgent`, naming the sub-agent that was running and the
iteration count. Serializes to a `TypedData.MapValue` with keys
`current_sub_agent` and `times_looped`; `fromValue` returns null if either key
is missing (a malformed checkpoint restarts from the beginning).

```kotlin
data class LoopAgentState(val currentSubAgent: String, val timesLooped: Int) : AgentState {
  override fun toStateValue(): TypedData.MapValue

  companion object {
    fun fromValue(node: TypedData.MapValue): LoopAgentState?
  }
}
```

## ParallelAgent

Workflow agent that runs its sub-agents concurrently, each on a deepened branch
so siblings do not see each other's conversation history.

```kotlin
class ParallelAgent(
  name: String,
  description: String = "",
  subAgents: List<BaseAgent> = emptyList(),
  beforeAgentCallbacks: List<BeforeAgentCallback> = emptyList(),
  afterAgentCallbacks: List<AfterAgentCallback> = emptyList(),
) : BaseAgent(
  name = name,
  description = description,
  subAgents = subAgents,
  beforeAgentCallbacks = beforeAgentCallbacks,
  afterAgentCallbacks = afterAgentCallbacks,
) {
  override fun runAsyncImpl(context: InvocationContext): Flow<Event>  // protected (inherited)
}
```

**Logic.** Cold flow; empty sub-agents is a no-op:

1.  `activeSubAgents = subAgents.filter { context.endOfAgents[it.name] != true
    }` - on resume this excludes already-finished branches so they are not
    re-run.
2.  Resumable start checkpoint: if resumable and no state yet,
    `setAgentState(name, TypedData.MapValue(emptyMap()))` and emit a checkpoint
    event authored by `name`. Its state is only a presence marker (the parallel
    agent has no ordered progress).
3.  Branch deepening: each sub-agent runs on
    `context.branch(this).branch(subAgent)`, appending child names to the branch
    path to give `<current>.<parallel>.<sub>`. This is the only place the branch
    deepens - plain agent entry and transfer keep the parent branch.
4.  Concurrency and merge: build one `Flow<Event>` per active sub-agent and
    `flows.merge().collect { ... }`. All sub-agent flows run concurrently and
    interleave into one stream; the terminal `collect` lambda runs serially, so
    `emit` and the `pauseInvocation` write do not race. Emission order is
    nondeterministic.
5.  Per event: emit, then set `pauseInvocation` if it is a pause point. `merge`
    waits for all merged flows to finish before `collect` returns, so a pause is
    observed only after the whole merged stream drains - siblings are not
    cancelled by one sibling pausing.
6.  Resumable end: if every active sub-agent ended, `setAgentState(name,
    endOfAgent = true)` and `emitEndOfAgent`. A parallel agent with an
    unfinished branch emits no `endOfAgent` and can be resumed. Shared
    invocation state is safe under concurrency: `agentStates`/`endOfAgents` are
    concurrent maps and the LLM-call counter is atomic.

## SequentialAgent

Workflow agent that runs its sub-agents in order, once each.

```kotlin
class SequentialAgent(
  name: String,
  description: String = "",
  subAgents: List<BaseAgent> = emptyList(),
  beforeAgentCallbacks: List<BeforeAgentCallback> = emptyList(),
  afterAgentCallbacks: List<AfterAgentCallback> = emptyList(),
) : BaseAgent(
  name = name,
  description = description,
  subAgents = subAgents,
  beforeAgentCallbacks = beforeAgentCallbacks,
  afterAgentCallbacks = afterAgentCallbacks,
) {
  override fun runAsyncImpl(context: InvocationContext): Flow<Event>  // protected (inherited)
}
```

**Logic.** Cold flow; empty sub-agents is a no-op:

1.  Restore `SequentialAgentState` from the checkpoint; `startSubAgentName =
    currentSubAgent`; `startIndex =
    subAgents.findIndexForResumption(startSubAgentName)` (unknown/absent name
    falls back to 0 with a warning). `isResuming = startSubAgentName != null`.
2.  Iterate `subAgents.subList(startIndex, size)` in order. Before each
    sub-agent, if `context.isResumable && !isResuming`,
    `saveAndEmitState(SequentialAgentState(subAgent.name))` - the checkpoint
    always names the sub-agent about to run. Then `isResuming = false` (the
    resume-skip applies only to the first sub-agent).
3.  Run the sub-agent, forwarding every event upward. If any event is a pause
    point, set `pauseInvocation` and, after the sub-agent drains,
    `return@flow` - remaining sub-agents do not run and no `endOfAgent` is
    emitted, so a later resume knows the sequence is unfinished.
4.  After all sub-agents complete without pausing: if resumable,
    `emitEndOfAgent`. Non-resumable runs are a pure sequential passthrough with
    no checkpoints or end-of-agent event.

## SequentialAgentState

Resume checkpoint for `SequentialAgent`, naming the sub-agent that was running.
Serializes to a `TypedData.MapValue` with key `current_sub_agent`; `fromValue`
returns null if the key is missing.

```kotlin
data class SequentialAgentState(val currentSubAgent: String) : AgentState {
  override fun toStateValue(): TypedData.MapValue

  companion object {
    fun fromValue(node: TypedData.MapValue): SequentialAgentState?
  }
}
```

## InvocationContext

The mutable-ish per-invocation execution context threaded through every agent.
It carries the session, run/resumability config, the current agent and branch,
service handles, and the shared invocation state (agent checkpoints,
end-of-agent flags, cost counter). It also drives function-call execution, event
filtering, the LLM-call budget, and pause detection. Derived contexts
(`forAgent`, `branch`) share the same mutable maps and cost manager via `copy`.

```kotlin
data class InvocationContext(
  val session: Session,
  val runConfig: RunConfig? = null,
  val agent: BaseAgent,
  val branch: String? = null,
  val invocationId: String = "e-" + Uuid.random(),
  val artifactService: ArtifactService? = null,
  val memoryService: MemoryService? = null,
  val sessionService: SessionService? = null,
  val resumabilityConfig: ResumabilityConfig? = null,
  val userContent: Content? = null,
  val agentStates: MutableMap<String, TypedData> = concurrentMutableMapOf(),
  val endOfAgents: MutableMap<String, Boolean> = concurrentMutableMapOf(),
  val extraTools: MutableMap<String, BaseTool> = concurrentMutableMapOf(),
  @Volatile var isEndOfInvocation: Boolean = false,
  @Deprecated(
    message =
      "isPaused is no longer set or read by the framework. Use shouldPauseInvocation or " +
        "Event.isFinalResponse / Event.longRunningToolIds to detect long-running pauses.",
    level = DeprecationLevel.WARNING,
  )
  @Volatile
  var isPaused: Boolean = false,
  val pluginManager: PluginManager = PluginManager(),
) {
  val isResumable: Boolean

  fun incrementLlmCallsCount()

  fun forAgent(childAgent: BaseAgent): InvocationContext

  fun branch(childAgent: BaseAgent): InvocationContext

  fun setAgentState(agentName: String, agentState: TypedData? = null, endOfAgent: Boolean = false)

  fun resetSubAgentStates(agentName: String)

  suspend fun populateInvocationAgentStates()

  suspend fun getEvents(
    currentInvocation: Boolean = false,
    currentBranch: Boolean = false,
  ): List<Event>

  fun shouldPauseInvocation(event: Event): Boolean

  @Suppress("UnsafeCoroutineCrossing")
  suspend fun handleFunctionCalls(
    functionCalls: List<FunctionCall>,
    tools: Map<String, BaseTool>,
    filters: Set<String> = emptySet(),
    toolConfirmations: Map<String, ToolConfirmation>? = null,
  ): Event?

  suspend fun executeSingleFunctionCall(
    functionCall: FunctionCall,
    tools: Map<String, BaseTool>,
    toolConfirmation: ToolConfirmation? = null,
  ): Event?

  suspend fun findMatchingFunctionCall(functionResponseEvent: Event): Event?
}
```

**Logic.**

-   `handleFunctionCalls(functionCalls, tools, filters, toolConfirmations)` -
    runs inside a `coroutineScope` (a failure or cancel cancels siblings).
    Filter the calls (empty `filters` means all; otherwise keep calls whose id
    is in `filters` or is null); if empty, return null. Execute concurrently:
    `map { async { executeSingleFunctionCall(...) }
    }.awaitAll().filterNotNull()`, dropping nulls from deferred long-running
    Unit tools. Merge the surviving responses into one `Content(role="user")`
    event, folding all `EventActions`; a merged span is recorded when more than
    one response merges. Return the merged event (or null if none produced an
    event).
-   `executeSingleFunctionCall(functionCall, tools, toolConfirmation)` - look up
    the tool (unknown name throws `IllegalArgumentException`). Run before-tool
    callbacks (plugins + agent callbacks; skipped when the agent is not an
    `LlmAgent`); a `Break` short-circuits with an override result. Execute
    `tool.run` inside a `withSpan("execute_tool ...")`; on exception,
    on-tool-error callbacks may recover, else rethrow. A long-running tool
    returning `Unit` returns null (the function-CALL event ends the turn); other
    empty results are coerced to `{}`. After-tool callbacks may override the
    result. Build the function-response event, threading any tool-requested
    confirmation via `toolConfirmations[id]`.
-   `forAgent(childAgent)` vs `branch(childAgent)` - `forAgent` is `copy(agent =
    childAgent)` and keeps `branch` unchanged; it is the default agent-entry and
    transfer path. `branch` appends the child name to the branch path
    (`childAgent.name`, or `"$branch.${childAgent.name}"`) and is used only to
    segregate history, as in `ParallelAgent`.
-   `incrementLlmCallsCount()` - delegates to an atomic counter shared across
    `copy`-derived contexts, so the budget spans the whole invocation
    (sub-agents and transfers). It increments then enforces: when
    `runConfig.maxLlmCalls > 0` and the count exceeds it, it throws
    `LlmCallsLimitExceededException`. Default `maxLlmCalls` is 500; a value `<=
    0` means unbounded.
-   `getEvents(currentInvocation, currentBranch)` - source is
    `sessionService.listEvents(...)` if a session service is set, else
    `session.events`. `currentInvocation` filters to matching `invocationId`;
    `currentBranch` keeps events whose branch matches or is null (null-branch
    events are shared history, always visible).
-   `shouldPauseInvocation(event)` - false unless resumable AND the event
    carries a long-running function call whose id is in
    `event.longRunningToolIds` (this includes the synthetic
    `adk_request_confirmation` HITL call). `isEndOfInvocation` is the
    `@Volatile` invocation-end flag; `isPaused` is deprecated and no longer read
    or set by the framework.

## CallbackContext

Read-write context passed to agent/model/tool callbacks. It extends
`ReadonlyContext` (by an internal delegate) and adds mutable state updates,
event-action accumulation, artifact IO, and memory writes.

```kotlin
class CallbackContext(
  internal val invocationContext: InvocationContext,   // constructor param is internal
  eventActions: EventActions? = null,
) : ReadonlyContext by ReadonlyContextImpl(invocationContext) {   // ReadonlyContextImpl is internal (impl detail)

  val agent: BaseAgent

  var eventActions: EventActions
    private set

  override val state: Map<String, Any>

  fun updateState(key: String, value: Any)

  fun mergeEventActions(actions: EventActions)

  fun endInvocation()

  suspend fun loadArtifact(name: String, version: Int? = null): Part?

  suspend fun saveArtifact(name: String, artifact: Part): Int

  suspend fun listArtifacts(): List<String>

  suspend fun addSessionToMemory()
}
```

## ReadonlyContext

Read-only view of an invocation exposed to instruction providers and read-only
callback paths. It surfaces session identity, run config, state, and service
handles, plus a suspend `getEvents`.

```kotlin
interface ReadonlyContext {
  val session: Session
  val runConfig: RunConfig?
  val invocationId: String
  val agentName: String
  val state: Map<String, Any>
  val userId: String
  val userContent: Content?
  val branch: String?
  val artifactService: ArtifactService?
  val memoryService: MemoryService?

  suspend fun getEvents(
    currentInvocation: Boolean = false,
    currentBranch: Boolean = false,
  ): List<Event>
}
```

## StreamingMode

Selects whether the model call streams. `NONE` (default) returns a single
response; `SSE` streams incremental chunks.

```kotlin
enum class StreamingMode {
  NONE,
  SSE,
}
```

## RunConfig

Per-invocation runtime configuration: streaming mode, the LLM-call budget, and
free-form custom metadata attached to emitted events. An `init` block validates
`maxLlmCalls`.

```kotlin
data class RunConfig(
  val streamingMode: StreamingMode = StreamingMode.NONE,
  val maxLlmCalls: Int = 500,
  val customMetadata: Map<String, Any>? = null,
)
```

## ResumabilityConfig

Opt-in switch for the experimental resumability feature. Its constructor is
annotated `@ExperimentalResumabilityFeature`, so constructing it (primary
constructor or `copy`) requires
`@OptIn(ExperimentalResumabilityFeature::class)`; merely reading `isResumable`
is unrestricted.

```kotlin
data class ResumabilityConfig
@ExperimentalResumabilityFeature
constructor(val isResumable: Boolean = false)
```

## AgentState

Interface for a workflow agent's serializable resume checkpoint. Implementations
convert themselves to the type-safe `TypedData.MapValue` tree that rides on
`EventActions.agentState`.

```kotlin
interface AgentState {
  fun toStateValue(): TypedData.MapValue
}
```

## Instruction

Sealed interface for an `LlmAgent`'s system instruction. It has three forms -
plain text, structured content, and a suspend provider computed from a
`ReadonlyContext` - plus `invoke` factory overloads for concise construction.
The `Text`/`Structured` variants are `@JvmInline` value classes.

```kotlin
sealed interface Instruction {

  @JvmInline value class Text(val text: String) : Instruction

  @JvmInline value class Structured(val content: Content) : Instruction

  fun interface Provider : Instruction {
    suspend fun provide(context: ReadonlyContext): Content?
  }

  companion object {
    operator fun invoke(text: String): Instruction

    operator fun invoke(content: Content): Instruction

    operator fun invoke(provider: suspend (ReadonlyContext) -> Content?): Instruction
  }
}
```

## TypedData

`@Serializable` sealed hierarchy used as the on-wire agent-state tree. It
preserves primitive types (Null/Int/Long/String/Boolean/Double) and composites
(List/Map) so values do not collapse to a single scalar type. `MapValue` adds
type-checked `getString`/`getInt` reads that return null on a type mismatch.

```kotlin
@Serializable
sealed class TypedData {

  @Serializable data object NullValue : TypedData()

  @Serializable data class IntValue(val value: Int) : TypedData()

  @Serializable data class LongValue(val value: Long) : TypedData()

  @Serializable data class StringValue(val value: String) : TypedData()

  @Serializable data class BooleanValue(val value: Boolean) : TypedData()

  @Serializable data class DoubleValue(val value: Double) : TypedData()

  @Serializable data class ListValue(val elements: List<TypedData>) : TypedData()

  @Serializable
  data class MapValue(val fields: Map<String, TypedData>) : TypedData() {
    fun getString(key: String): String?

    fun getInt(key: String): Int?
  }
}
```

## LlmCallsLimitExceededException

Thrown by `InvocationContext.incrementLlmCallsCount` when the invocation exceeds
`RunConfig.maxLlmCalls`. It propagates out and aborts the invocation.

```kotlin
class LlmCallsLimitExceededException(message: String) : Exception(message)
```

## Internal architecture (not public API)

The types below are `internal`. They are documented for understanding only; they
are not part of the public surface and may change without notice.

**`LlmAgentTurn`** - THE single turn, constructed per iteration by `LlmAgent`'s
step loop with `(agent, context, requestProcessors, responseProcessors)`. Its
`execute()` is a cold flow in four stages:

1.  Guard: if `context.isEndOfInvocation`, return.
2.  Build the request FIRST (`prepareRequest`), before choosing a resume path,
    so request-scoped tools like `transfer_to_agent` are registered.
    `prepareRequest` folds the six ordered request processors -
    `BasicRequestProcessor`, `RequestConfirmationProcessor` (HITL decode of the
    `adk_request_confirmation` function response on resume),
    `InstructionsProcessor`, `ContentsProcessor`, `AgentTransferProcessor`, and
    `OutputSchemaProcessor` (last, so it sees the full toolset and may install
    the `set_model_response` workaround) - then folds each direct tool's and
    toolset tool's `processLlmRequest`. In current code it always yields a
    `Request`.
3.  Resumption checks: if a still-pending long-running pause is detected
    (`shouldPause()`), return without re-invoking the model. Otherwise, on a
    resumable invocation whose last event has unresolved function calls, run
    `handleActions` on that event directly (executing the pending call, e.g. a
    transfer) without a new model call.
4.  Model call and post-processing (`invokeAndProcessModel`), inside a
    `call_llm` span:
    -   Before-model callbacks (plugins first); a `Break` can short-circuit with
        a response WITHOUT spending budget.
    -   Budget: `incrementLlmCallsCount()` runs after the before-callbacks, so a
        short-circuit does not consume the LLM-call budget; a limit breach
        throws and aborts.
    -   Stream the model: `model.generateContent(request, stream =
        context.runConfig?.streamingMode == StreamingMode.SSE)`, so it streams
        only in SSE mode.
    -   `processModelResponse` folds the response processors, finalizes the
        event (assigning function-call ids and computing `longRunningToolIds`,
        which is what makes a long-running call event a final response and a
        pause point), emits it, and - for non-partial events with function
        calls - runs `handleActions`. Partial streaming chunks are never
        executed.
    -   `handleActions` runs `handleFunctionCalls`, then handles the outcomes:
        it emits the synthetic `adk_request_confirmation` long-running (HITL)
        event when a tool requests confirmation, emits a synthetic
        `set_model_response` final MODEL text event when that tool call is
        present (so the turn terminates and output is saved), and, when the
        function response carries `actions.transferToAgent`, resolves the target
        and streams its whole `runAsync`. Neither the confirmation-pause branch
        nor the transfer branch sets `isEndOfInvocation`; the turn ends instead
        because the placeholder response carries `skipSummarization = true`.
    -   On a model exception, on-model-error callbacks may recover with a
        response; otherwise the exception (including `CancellationException`) is
        rethrown. The budget check (`incrementLlmCallsCount`) runs before this
        try block, so `LlmCallsLimitExceededException` propagates directly
        without passing through on-model-error callbacks.

**`ReadonlyContextImpl`** - internal implementation of `ReadonlyContext`, used
as the delegate backing `CallbackContext`.

**`AgentExtensions`** - internal file contributing
`List<BaseAgent>.findIndexForResumption(...)`, the helper the workflow agents
use to map a restored `currentSubAgent` name back to a start index (null name or
an unknown name falls back to index 0).
