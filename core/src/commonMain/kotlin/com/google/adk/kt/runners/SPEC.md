# Runners

> Internal. Part of the ADK Kotlin specification: the repo-root `SPEC.md` is the
> charter and index, and `DECISIONS.md` holds the `[D-n]` design decisions. Not
> exported to GitHub.

*Package `com.google.adk.kt.runners`.*

A runner drives one agent invocation end to end: it loads or creates the
session, runs the plugin pipeline around the agent, appends events, and can
rewind an invocation. `AbstractRunner` is the shared implementation.

## Runner

The runner contract. `runAsync` streams events; `run` is a blocking convenience;
`rewindAsync` has a default body that throws.

```kotlin
interface Runner {
  val appName: String
  val agent: BaseAgent
  val sessionService: SessionService
  val artifactService: ArtifactService?
  val memoryService: MemoryService?
  val pluginManager: PluginManager
  val resumabilityConfig: ResumabilityConfig

  fun runAsync(
    userId: String,
    sessionId: String,
    invocationId: String? = null,
    newMessage: Content? = null,
    stateDelta: Map<String, Any>? = null,
    runConfig: RunConfig? = null,
  ): Flow<Event>

  fun run(
    userId: String,
    sessionId: String,
    newMessage: Content,
    runConfig: RunConfig? = null,
  ): Iterator<Event>

  suspend fun rewindAsync(userId: String, sessionId: String, rewindBeforeInvocationId: String) {
    /* default */
  }
}
```

**Logic.** `rewindAsync` has a default body that throws
`NotImplementedError("rewindAsync for <class> is not implemented")`. Rewind is
opt-in per implementation; `AbstractRunner` overrides it.

## AbstractRunner

Shared `Runner` implementation. It holds the app and services, orchestrates the
invocation, and implements rewind. Offers a field-based constructor and a
recommended App-based constructor.

```kotlin
abstract class AbstractRunner : Runner {

  val app: App?
  final override val appName: String
  final override val agent: BaseAgent
  final override val sessionService: SessionService
  final override val artifactService: ArtifactService?
  final override val memoryService: MemoryService?
  final override val pluginManager: PluginManager
  final override val resumabilityConfig: ResumabilityConfig

  // Field-based constructor.
  constructor(
    appName: String,
    agent: BaseAgent,
    sessionService: SessionService,
    artifactService: ArtifactService?,
    memoryService: MemoryService?,
    pluginManager: PluginManager,
    resumabilityConfig: ResumabilityConfig = ResumabilityConfig(),
  )

  // App-based constructor (recommended).
  constructor(
    app: App,
    sessionService: SessionService,
    artifactService: ArtifactService?,
    memoryService: MemoryService?,
    skipClosingPlugins: Boolean = false,
  )

  override fun runAsync(
    userId: String, sessionId: String, invocationId: String?,
    newMessage: Content?, stateDelta: Map<String, Any>?, runConfig: RunConfig?,
  ): Flow<Event>

  override fun run(
    userId: String, sessionId: String, newMessage: Content, runConfig: RunConfig?,
  ): Iterator<Event>

  override suspend fun rewindAsync(userId: String, sessionId: String, rewindBeforeInvocationId: String)

  protected suspend fun createInvocationContext(
    session: Session, invocationId: String?, newMessage: Content?,
    stateDelta: Map<String, Any>?, runConfig: RunConfig?,
  ): InvocationContext

  protected fun runAgentWithPlugins(context: InvocationContext): Flow<Event>

  protected suspend fun handleNewUserContent(
    context: InvocationContext, newMessage: Content, stateDelta: Map<String, Any>?,
  ): InvocationContext

  fun applyStateDelta(event: Event, stateDelta: Map<String, Any>?)

  protected suspend fun setupContextForNewInvocation(
    session: Session, invocationId: String?, newMessage: Content,
    runConfig: RunConfig?, stateDelta: Map<String, Any>?,
  ): InvocationContext

  protected suspend fun setupContextForResumedInvocation(
    session: Session, newMessage: Content?, invocationId: String?,
    runConfig: RunConfig?, stateDelta: Map<String, Any>?,
  ): InvocationContext

  protected suspend fun findAgentToRun(context: InvocationContext, rootAgent: BaseAgent): BaseAgent
}
```

The whole file is `@file:OptIn(ExperimentalResumabilityFeature::class)`.

**Logic.** `runAsync` end to end:

1.  `key = SessionKey(appName, userId, sessionId)`; `session = getSession(key)
    ?: createSession(key)`.
2.  `createInvocationContext` runs the `onUserMessage` plugin pipeline, appends
    the user event (always, no partial filtering), and resolves which agent to
    run via `findAgentToRun`. New vs resumed depends on `resumabilityConfig` and
    on whether `newMessage` carries a `FunctionResponse`.
3.  Short-circuit: if `endOfAgents[resolved agent name] == true`, return. A
    fresh invocation has an empty map, so this fires only on a resume whose
    resolved agent already finished.
4.  `runAgentWithPlugins`: `beforeRun` -> `agent.runAsync` -> for each event,
    append only non-partials, then run `onEvent` (which runs on every event,
    including partials), then emit -> `afterRun` (runs on both the Break and
    Continue paths). A `beforeRun` Break appends one early-exit MODEL event and
    skips the agent; `onEvent` is not applied to that early-exit event.
5.  `runPostInvocationCompaction`: after the agent finished and its events were
    appended, run sliding-window compaction if configured, appending one summary
    event when the interval is reached.

**Logic.** `rewindAsync` full algorithm:

1.  Resolve the session by `SessionKey`; throw `IllegalArgumentException` if not
    found.
2.  Find `rewindEventIndex`, the first event whose `invocationId ==
    rewindBeforeInvocationId`; throw if none.
3.  `computeStateDeltaForRewind`: replay the `stateDelta` of events `[0,
    rewindEventIndex)` into a rewind-point snapshot, skipping `app:`/`user:`
    keys and honoring the `REMOVED` sentinel; then diff against the current
    `session.state`, restoring each changed key to its rewind-point value and
    setting each current non-prefixed key absent at the rewind point to
    `REMOVED`.
4.  `computeArtifactDeltaForRewind`: with no `artifactService`, empty.
    Otherwise, for each current artifact (skipping `user:`-prefixed), if its
    version differs from the rewind-point version, write `filename -> vn+1` and
    re-save the rewind-point content: load the old version, or save an empty
    octet-stream placeholder if the artifact did not exist at the rewind point
    or the load fails.
5.  Append one synthetic USER event with `EventActions(rewindBeforeInvocationId,
    stateDelta, artifactDelta)`. Rewind is expressed purely as a reversing
    appended event, not by deleting history.

## InMemoryRunner

`AbstractRunner` wired to in-memory services. The App-based constructor is
recommended; the deprecated constructors steer plugins and resumability into the
`App`.

```kotlin
open class InMemoryRunner : AbstractRunner {

  // From root agent + default in-memory services.
  constructor(
    agent: BaseAgent,
    appName: String = "InMemoryRunner",
    sessionService: SessionService = InMemorySessionService(),
    artifactService: ArtifactService? = InMemoryArtifactService(),
    memoryService: MemoryService? = InMemoryMemoryService(),
  )

  // From App (recommended).
  constructor(
    app: App,
    sessionService: SessionService = InMemorySessionService(),
    artifactService: ArtifactService? = InMemoryArtifactService(),
    memoryService: MemoryService? = InMemoryMemoryService(),
    skipClosingPlugins: Boolean = false,
  )

  @Deprecated(
    "Configure plugins via App.plugins instead, e.g. InMemoryRunner(App(appName, agent, plugins = listOf(...))). Passing a PluginManager directly to the runner is deprecated.",
    ReplaceWith("InMemoryRunner(App(appName, agent, plugins = pluginManager.plugins))"),
    DeprecationLevel.WARNING,
  )
  constructor(
    agent: BaseAgent,
    appName: String = "InMemoryRunner",
    sessionService: SessionService = InMemorySessionService(),
    artifactService: ArtifactService? = InMemoryArtifactService(),
    memoryService: MemoryService? = InMemoryMemoryService(),
    pluginManager: PluginManager,
  )

  @Deprecated(
    "Configure resumability via App.resumabilityConfig instead, e.g. InMemoryRunner(App(appName, agent, resumabilityConfig = ...)). Passing a ResumabilityConfig directly to the runner is deprecated.",
    ReplaceWith("InMemoryRunner(App(appName, agent, resumabilityConfig = resumabilityConfig))"),
    DeprecationLevel.WARNING,
  )
  constructor(
    agent: BaseAgent,
    appName: String = "InMemoryRunner",
    sessionService: SessionService = InMemorySessionService(),
    artifactService: ArtifactService? = InMemoryArtifactService(),
    memoryService: MemoryService? = InMemoryMemoryService(),
    resumabilityConfig: ResumabilityConfig,
  )

  @Deprecated(
    "Configure plugins and resumability via App instead, e.g. InMemoryRunner(App(appName, agent, plugins = listOf(...), resumabilityConfig = ...)). Passing them directly to the runner is deprecated.",
    ReplaceWith("InMemoryRunner(App(appName, agent, plugins = pluginManager.plugins, resumabilityConfig = resumabilityConfig))"),
    DeprecationLevel.WARNING,
  )
  constructor(
    agent: BaseAgent,
    appName: String = "InMemoryRunner",
    sessionService: SessionService = InMemorySessionService(),
    artifactService: ArtifactService? = InMemoryArtifactService(),
    memoryService: MemoryService? = InMemoryMemoryService(),
    pluginManager: PluginManager,
    resumabilityConfig: ResumabilityConfig,
  )
}
```

The whole file is `@file:OptIn(ExperimentalResumabilityFeature::class)`.

**Logic.**

1.  The App-based constructor is recommended: `appName`, root agent, plugins,
    and `resumabilityConfig` all come from the `App`, so the `App` is the single
    source of truth. `skipClosingPlugins = true` is for plugins shared with a
    parent runner.
2.  The non-deprecated agent-based constructor delegates to the field-based
    `AbstractRunner` super with a fresh `PluginManager()` (no plugins) and a
    default, non-resumable `ResumabilityConfig`.
3.  Service defaults wire `InMemorySessionService`, `InMemoryArtifactService`,
    and `InMemoryMemoryService`.
4.  The deprecated constructors (WARNING level) accept a `pluginManager` and/or
    a `resumabilityConfig` directly and each carry a `ReplaceWith` steering to
    the App form. They exist only for backward compatibility.

## ReplRunner

`InMemoryRunner` subclass (commonJvmAndroidMain) that adds an interactive
read-eval-print loop over the agent, including tool-confirmation and
pending-input handling.

```kotlin
open class ReplRunner(agent: BaseAgent) : InMemoryRunner(agent) {

  fun start()

  data class PendingInputRequest(
    val callId: String,
    val toolName: String,
    val options: List<String>?,
  )

  companion object {
    const val RESPONSE_VALUE_KEY = "value"

    @VisibleForTesting
    fun resolvePendingInputRequest(event: Event): PendingInputRequest?

    @VisibleForTesting
    fun shouldDisplayError(error: String): Boolean

    @VisibleForTesting
    fun resolvePendingConfirmations(event: Event): Map<String, ToolConfirmation>
  }
}
```

## Status: live streaming

Live (bidirectional) streaming is not implemented (b/487976632). In the runner
pipeline `isLiveCall` is always `false`, so every emitted event flows through
the standard append-and-`onEvent` path.

--------------------------------------------------------------------------------
