# Runner and the invocation lifecycle

Files: `runners/Runner.kt`, `runners/AbstractRunner.kt`, `runners/InMemoryRunner.kt`, `agents/InvocationContext.kt`, `agents/BaseAgent.kt`.

## Runner interface

```kotlin
interface Runner : AutoCloseable {
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

  fun run(userId: String, sessionId: String, newMessage: Content, runConfig: RunConfig? = null): Iterator<Event>
  suspend fun rewindAsync(userId: String, sessionId: String, rewindBeforeInvocationId: String)
}
```

`run` is `runBlocking { runAsync(...).toList().iterator() }`. It exists for Java and scripts; never call it from a coroutine.

`InMemoryRunner(agent, appName = "InMemoryRunner", sessionService = InMemorySessionService(), artifactService = InMemoryArtifactService(), memoryService = InMemoryMemoryService(), plugins = emptyList())` is the concrete runner for tests and local runs. A second constructor takes an `App` instead of an agent and is the only way to enable events compaction or context caching, because those configs live on `App`.

## What `runAsync` does, in order

1. Captures the current telemetry context on the calling thread, because the returned flow is cold and may be collected on another dispatcher.
2. Inside `flow { }`: looks the session up by `SessionKey(appName, userId, sessionId)` and **creates it if missing** with empty state.
3. Builds the `InvocationContext` (see below). In a non-resumable app `newMessage` is required. In a resumable app, a `FunctionResponse` in the new message with an `id` resumes the invocation that issued the matching `FunctionCall`.
4. Merges the caller's `stateDelta` into the live session state, runs `onUserMessage` plugin callbacks, and appends a `user` event carrying the content and the delta.
5. Returns early if `endOfAgents[agent.name]` is already true for the chosen agent.
6. Runs `beforeRun` plugin callbacks. A `Break` ends the invocation with a model-authored event containing the returned content.
7. Collects `context.agent.runAsync(context)`. For every event: applies `RunConfig.customMetadata`, runs `onEvent` plugin callbacks, and **if the event is not partial** calls `sessionService.appendEvent(session, finalEvent)`. The event that was transformed by `onEvent` is the one persisted and the one emitted.
8. Runs `afterRun` plugin callbacks.
9. On an exception other than `CancellationException`, runs `onRunError` callbacks (notification only) and rethrows.
10. After the flow completes, runs sliding-window compaction when the `App` configured it.

The whole flow is wrapped in a span named `invocation`.

## Which agent runs first

`findAgentToRun(context, rootAgent)` decides which agent `context.agent` is for this invocation. Runner does not always start at the root:

1. If the latest session event is a `user` event containing function responses, the agent that issued the matching function call runs, so a tool round-trip resumes inside the sub-agent that asked for it.
2. Otherwise the most recent non-user event whose author is an agent in the tree selects that agent, but only if every agent from it up to the root is an `LlmAgent` with `disallowTransferToParent == false`. That keeps the conversation with the sub-agent it was transferred to.
3. Otherwise the root agent.

`disallowTransferToPeers` is validated here as well; a violation throws `IllegalArgumentException`.

## InvocationContext

A `data class`; every child context is a `copy()`.

| Field | Meaning |
|---|---|
| `session`, `sessionService`, `artifactService`, `memoryService` | Services the agent tree can reach. |
| `agent` | The agent currently executing. `forAgent(child)` copies with a new agent and the same branch. |
| `branch` | Dotted path used only by `ParallelAgent` (`context.branch(child)` appends the child name). Events written on a branch carry it. |
| `invocationId` | `"e-" + Uuid`, one per `runAsync`. |
| `runConfig` | `StreamingMode`, `maxLlmCalls` (default 500), `customMetadata`. |
| `userContent` | The `newMessage` for this invocation. |
| `agentStates`, `endOfAgents` | Resumability bookkeeping keyed by agent name. |
| `extraTools` | Tools added for this invocation; `LlmAgentTurn` merges them with `agent.tools` every turn. |
| `isEndOfInvocation` | Volatile flag; once set, `BaseAgent.runAsync` stops after the current stage. |
| `pluginManager` | Shared with the runner. |
| `invocationCostManager` | Shared across copies; `incrementLlmCallsCount()` throws `LlmCallsLimitExceededException` past `maxLlmCalls`. |

`getEvents(currentInvocation, currentBranch)` reads the in-memory `session.events`; it never re-fetches from the session service.

## BaseAgent template method

```kotlin
abstract class BaseAgent(
  val name: String,
  open val description: String = "",
  val subAgents: List<BaseAgent> = emptyList(),
  val beforeAgentCallbacks: List<BeforeAgentCallback> = emptyList(),
  val afterAgentCallbacks: List<AfterAgentCallback> = emptyList(),
  val disallowTransferToParent: Boolean = false,
  val disallowTransferToPeers: Boolean = false,
)
```

- `init` sets `parentAgent` on each sub-agent and throws if one already has a parent. An agent instance can belong to exactly one tree.
- `name` must match `_?[a-zA-Z0-9]*([. _-][a-zA-Z0-9]+)*` and cannot be `"user"`.
- `runAsync(parentContext)` is public and final in spirit: it derives the child context, runs plugin-then-agent `beforeAgent` callbacks, returns if the invocation ended, emits everything from `runAsyncImpl`, then runs `afterAgent` callbacks. The whole thing is a span `invoke_agent <name>`.
- Subclasses implement `protected abstract fun runAsyncImpl(context): Flow<Event>`. It is not `suspend`; build a `flow { }`.
- `findAgent(name)` is a top-level extension doing a depth-first search.

`SequentialAgent` iterates sub-agents, resuming from a saved index. `LoopAgent(maxIterations = null)` repeats until an event has `actions.escalate` or the count is reached. `ParallelAgent` merges one flow per sub-agent on separate branches (see the coroutine reference).

## Closing

`Runner.close()` walks the agent tree, collects every `BaseTool` and `Toolset` that is `AutoCloseable`, closes each once, then closes the plugin manager. Session, artifact and memory services are deliberately left open because the caller owns them.
