# Coroutine model

## Two channels: Flow and suspend

Cold `Flow` producers (non-suspend functions that return `Flow`):
`Runner.runAsync`, `BaseAgent.runAsync`, `BaseAgent.runAsyncImpl`,
`LlmAgent.executeTurns`, `LlmAgentTurn.execute`, `Model.generateContent`.
Nothing happens until the flow is collected, and collecting twice runs the
invocation twice.

`suspend` functions: every method on `SessionService`, `ArtifactService`,
`MemoryService`; every callback `call`; every `Plugin` hook; `BaseTool.run`;
`Toolset.getTools`; the processor `process` methods;
`InvocationContext.handleFunctionCalls`, `getEvents`,
`findMatchingFunctionCall`; `Runner.rewindAsync`.

## Dispatchers

The agent, model and tool path in `commonMain` never names a dispatcher. It
inherits the collector's context. Explicit dispatchers appear only at the
edges:

| Dispatcher | Where | Why |
|---|---|---|
| `Dispatchers.IO` | `FileArtifactService`, `GcsArtifactService`, `GoogleApiClient`, `AppFunctionsToolset` | blocking file, GCS and HTTP I/O |
| `Dispatchers.Default` in `CoroutineScope(SupervisorJob() + Dispatchers.Default)` | `interop/PublisherRunner`, `interop/BaseFutureSessionService` | scopes for Java callers that hand out `Publisher`s and `CompletableFuture`s |
| `flowOn` | `LlmAgentTurn` (model stream) and `telemetry/Coroutines.kt` (`Flow.trace`) | telemetry context propagation, not thread switching |

There is no `GlobalScope` and no `channelFlow` in production code.

Consequence for authors: a tool that performs blocking I/O must wrap it in
`withContext(Dispatchers.IO)` itself; the runtime will not do it. Because
tool calls run with `async` inside `coroutineScope`, a blocking tool also
delays its siblings.

## Structured concurrency

- **Parallel tool calls**: `coroutineScope { calls.map { async { ... } }.awaitAll() }`
  in `InvocationContext.handleFunctionCalls`. One failing tool that is not
  recovered by `onToolError` cancels the siblings and fails the turn.
- **`ParallelAgent`**: builds one flow per sub-agent on its own branch
  (`context.branch(parallelAgent).branch(subAgent)`), then
  `flows.merge().transformWhile { emit(it); !it.actions.escalate }`. When a
  direct sub-agent escalates, the collector stops, and `merge()` cancels the
  remaining branches through structured concurrency.
- **Session events under parallelism**: `Session.events` is a
  `CopyOnWriteArrayList` on JVM and Android (`concurrentMutableListOf()`), so
  a branch appending while another reads does not throw.

## Cancellation

`CancellationException` is rethrown untouched everywhere it could be caught:
the runner's `catch`, the `withSpan` / `trace` / `tracedFlow` helpers, and
`LlmAgentTurn`. Cancelling the collecting coroutine cancels the model stream
and every in-flight tool. `PublisherRunner.close()` cancels its scope before
closing the delegate.

## Synchronisation primitives

| Primitive | Used by |
|---|---|
| `kotlinx.coroutines.sync.Mutex` | `InMemorySessionService`, `InMemoryArtifactService`, `InMemoryMemoryService`, `StreamingResponseAggregator`, `FileArtifactService`, `McpSessionManager`, `McpToolset`, `LocalStorageMemoryIndex`, `AppFunctionsToolset` |
| `sessions/Lock` (`expect fun Lock()`, a non-suspending read/write lock) | `State` |
| `kotlin.concurrent.atomics.AtomicInt` | `InvocationCostManager` |
| `concurrentMutableMapOf()` / `concurrentMutableListOf()` (`expect`, `ConcurrentHashMap` / `CopyOnWriteArrayList`) | `InvocationContext` maps, `EventActions` maps, `Session.events` |

`kotlinx-atomicfu` is still a declared dependency of core but no longer used
in source.

## Blocking bridges

`runBlocking` exists in exactly four places, all at the boundary:
`AbstractRunner.run`, `ReplRunner.start`, `McpSessionManager.close` (because
`AutoCloseable.close` is not `suspend`), and `interop/AsyncJavaHelpers`.

`AsyncJavaHelpers` (`@JvmStatic` members on an object) is the Java entry
point:

```kotlin
fun <T> await(block: suspend () -> T): T                       // runBlocking
fun <T> async(scope: CoroutineScope, block: suspend () -> T): CompletableFuture<T>
fun <T> collect(flow: Flow<T>): List<T>
fun <T> forEach(flow: Flow<T>, action: Consumer<T>)
fun <T : Any> asPublisher(flow: Flow<T>): Publisher<T>
fun <T : Any> asFlow(publisher: Publisher<T>): Flow<T>
```

`PublisherRunner` wraps a `Runner` and exposes `runAsync(...): Publisher<Event>`
with the same parameters. The `BaseFuture*` and `BasePublisher*` classes in
the same package let Java implement `SessionService`, `ArtifactService`,
`MemoryService`, `Plugin`, `BaseTool`, `Toolset`, `SkillSource`, `BaseAgent`
and `Model` with `CompletableFuture` and `Publisher` instead of `suspend` and
`Flow`.
