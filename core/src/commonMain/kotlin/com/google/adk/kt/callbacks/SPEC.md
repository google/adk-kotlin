# Callbacks

> Internal. Part of the ADK Kotlin specification: the repo-root `SPEC.md` is the
> charter and index, and `DECISIONS.md` holds the `[D-n]` design decisions. Not
> exported to GitHub.

*Package `com.google.adk.kt.callbacks`.*

Callbacks are the per-hook functional units that plugin adapters and
agent-declared handlers both implement. They are driven by the internal
callbacks pipeline (see below).

## Callback

Marker interface for all callback types, providing a `name` used in pipeline
logging that defaults to the class simple name.

```kotlin
interface Callback {
  val name: String
    get() = this::class.simpleName ?: "Unknown"   // default getter
}
```

## CallbackChoice

Sealed result type expressing the two-way outcome of a control hook: `Continue`
carries the value threaded to the next stage; `Break` carries the value that
short-circuits the pipeline [D-12].

```kotlin
sealed interface CallbackChoice<out ContinueT, out BreakT> {
  data class Continue<out ContinueT>(val value: ContinueT) : CallbackChoice<ContinueT, Nothing>
  data class Break<out BreakT>(val value: BreakT) : CallbackChoice<Nothing, BreakT>
}
```

## The 12 callback interfaces

Each of the following is declared as `interface X : Callback` (not `fun
interface`). Each exposes exactly one abstract `suspend fun call(...)` plus a
companion `operator fun invoke(...)` that provides SAM-style construction
manually (a workaround for a Kotlin/Gradle codegen bug with abstract `suspend`
methods).

## BeforeAgentCallback

Runs before an agent executes; `Break` bypasses the agent, `Continue` supplies
`EventActions` to merge.

```kotlin
interface BeforeAgentCallback : Callback {
  suspend fun call(context: CallbackContext): CallbackChoice<EventActions, Content>
  companion object {
    operator fun invoke(
      block: suspend (context: CallbackContext) -> CallbackChoice<EventActions, Content>
    ): BeforeAgentCallback
  }
}
```

## AfterAgentCallback

Runs after an agent executes; `Break` overrides the agent response.

```kotlin
interface AfterAgentCallback : Callback {
  suspend fun call(context: CallbackContext): CallbackChoice<Unit, Content>
  companion object {
    operator fun invoke(
      block: suspend (context: CallbackContext) -> CallbackChoice<Unit, Content>
    ): AfterAgentCallback
  }
}
```

## BeforeModelCallback

Runs before a model call; `Break` suppresses the call with an `LlmResponse`,
`Continue` threads a (possibly modified) `LlmRequest`.

```kotlin
interface BeforeModelCallback : Callback {
  suspend fun call(
    context: CallbackContext,
    request: LlmRequest,
  ): CallbackChoice<LlmRequest, LlmResponse>
  companion object {
    operator fun invoke(
      block: suspend (context: CallbackContext, request: LlmRequest) -> CallbackChoice<LlmRequest, LlmResponse>
    ): BeforeModelCallback
  }
}
```

## AfterModelCallback

Runs after a model call; transforms and threads the `LlmResponse` (no `Break` at
this stage).

```kotlin
interface AfterModelCallback : Callback {
  suspend fun call(context: CallbackContext, response: LlmResponse): LlmResponse
  companion object {
    operator fun invoke(
      block: suspend (context: CallbackContext, response: LlmResponse) -> LlmResponse
    ): AfterModelCallback
  }
}
```

## OnModelErrorCallback

Runs when a model call throws; `Break` resolves with a fallback `LlmResponse`.

```kotlin
interface OnModelErrorCallback : Callback {
  suspend fun call(
    context: CallbackContext,
    request: LlmRequest,
    error: Throwable,
  ): CallbackChoice<Unit, LlmResponse>
  companion object {
    operator fun invoke(
      block: suspend (context: CallbackContext, request: LlmRequest, error: Throwable) -> CallbackChoice<Unit, LlmResponse>
    ): OnModelErrorCallback
  }
}
```

## BeforeToolCallback

Runs before a tool call; `Break` short-circuits with a result map, `Continue`
threads the args map.

```kotlin
interface BeforeToolCallback : Callback {
  suspend fun call(
    context: ToolContext,
    tool: BaseTool,
    args: Map<String, Any>,
  ): CallbackChoice<Map<String, Any>, Map<String, Any>>
  companion object {
    operator fun invoke(
      block: suspend (context: ToolContext, tool: BaseTool, args: Map<String, Any>) -> CallbackChoice<Map<String, Any>, Map<String, Any>>
    ): BeforeToolCallback
  }
}
```

## AfterToolCallback

Runs after a tool call; transforms and threads the result map.

```kotlin
interface AfterToolCallback : Callback {
  suspend fun call(
    context: ToolContext,
    tool: BaseTool,
    args: Map<String, Any>,
    result: Map<String, Any>,
  ): Map<String, Any>
  companion object {
    operator fun invoke(
      block: suspend (context: ToolContext, tool: BaseTool, args: Map<String, Any>, result: Map<String, Any>) -> Map<String, Any>
    ): AfterToolCallback
  }
}
```

## OnToolErrorCallback

Runs when a tool call throws; `Break` resolves with a result map.

```kotlin
interface OnToolErrorCallback : Callback {
  suspend fun call(
    context: ToolContext,
    tool: BaseTool,
    args: Map<String, Any>,
    error: Throwable,
  ): CallbackChoice<Unit, Map<String, Any>>
  companion object {
    operator fun invoke(
      block: suspend (context: ToolContext, tool: BaseTool, args: Map<String, Any>, error: Throwable) -> CallbackChoice<Unit, Map<String, Any>>
    ): OnToolErrorCallback
  }
}
```

## BeforeRunCallback

Runs before the invocation starts; `Break` aborts the invocation.

```kotlin
interface BeforeRunCallback : Callback {
  suspend fun call(invocationContext: InvocationContext): CallbackChoice<Unit, Content>
  companion object {
    operator fun invoke(
      block: suspend (invocationContext: InvocationContext) -> CallbackChoice<Unit, Content>
    ): BeforeRunCallback
  }
}
```

## AfterRunCallback

Runs after the invocation completes; used for cleanup and logging.

```kotlin
interface AfterRunCallback : Callback {
  suspend fun call(invocationContext: InvocationContext)
  companion object {
    operator fun invoke(
      block: suspend (invocationContext: InvocationContext) -> Unit
    ): AfterRunCallback
  }
}
```

## OnEventCallback

Runs for each emitted event; transforms and threads the `Event`.

```kotlin
interface OnEventCallback : Callback {
  suspend fun call(invocationContext: InvocationContext, event: Event): Event
  companion object {
    operator fun invoke(
      block: suspend (invocationContext: InvocationContext, event: Event) -> Event
    ): OnEventCallback
  }
}
```

## OnUserMessageCallback

Runs once on the incoming user message; transforms and threads the `Content`.

```kotlin
interface OnUserMessageCallback : Callback {
  suspend fun call(invocationContext: InvocationContext, userMessage: Content): Content
  companion object {
    operator fun invoke(
      block: suspend (invocationContext: InvocationContext, userMessage: Content) -> Content
    ): OnUserMessageCallback
  }
}
```

## CallbacksPipeline

Internal (not public API). The file is entirely internal: `internal sealed class
PipelineStep<...>` plus the `internal suspend fun run*CallbacksPipeline(...)`
helpers. It defines how a list of callbacks for one hook is executed.

**Logic.**

1.  **Generic driver.** `runCallbacksPipeline(callbacks, initialState,
    onComplete, executor)` iterates callbacks in list order, running
    `executor(callback, currentState)`. A `PipelineStep.Continue(newState)`
    updates the state and keeps looping; a `PipelineStep.ShortCircuit(result)`
    logs and returns that result immediately; any thrown exception is logged
    with the callback name and rethrown. After the loop it returns
    `onComplete(currentState)`.
2.  **Control pipelines - first `Break` short-circuits.** Each callback returns
    a `CallbackChoice`; `Break` maps to `ShortCircuit` (returns its value and
    stops), `Continue` maps to `Continue`. So the first `Break` wins and the
    remaining callbacks do not run. These are: `beforeAgent` (`Break` bypasses
    the agent, `Continue` additionally merges the returned `EventActions`),
    `afterAgent` (`Break` overrides the response), `beforeModel` (`Break`
    suppresses the model call, `Continue` threads the `LlmRequest`),
    `onModelError` (`Break` resolves a fallback `LlmResponse`), `beforeRun`
    (`Break` aborts the invocation), `beforeTool` (`Break` short-circuits a
    result map, `Continue` threads the args map), and `onToolError` (`Break`
    resolves a result map).
3.  **Transform pipelines - value threaded through all.** Every callback runs,
    each returns a plain value that becomes the next state, and `onComplete = {
    it }` returns the final threaded value. These are: `afterModel` (threads
    `LlmResponse`), `onUserMessage` (threads `Content`), `onEvent` (threads
    `Event`), `afterTool` (threads the result `Map`), and `afterRun` (pure
    iteration for cleanup, state `Unit`).
4.  **Merged order: plugin callbacks then agent callbacks.** The callers
    concatenate the manager's pre-aggregated list first and the agent's own
    callbacks second (for example `context.pluginManager.beforeAgentCallbacks +
    beforeAgentCallbacks`). The concatenated list is consumed left-to-right, so
    plugin callbacks always run before agent callbacks, for both "before" and
    "after" hooks (verified: no reversal for "after"). Because the driver
    iterates in list order, the first `Break` in a control pipeline may come
    from either a plugin or an agent callback, but plugins get first say.

--------------------------------------------------------------------------------
