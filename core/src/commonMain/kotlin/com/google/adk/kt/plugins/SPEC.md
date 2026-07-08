# Plugins

> Internal. Part of the ADK Kotlin specification: the repo-root `SPEC.md` is the
> charter and index, and `DECISIONS.md` holds the `[D-n]` design decisions. Not
> exported to GitHub.

*Package `com.google.adk.kt.plugins`.*

A plugin is a cross-cutting extension registered on an `App` that observes and
can intervene at every stage of an invocation. Every hook has a default body, so
an implementation overrides only the stages it cares about.

## Plugin

Interface for a named extension exposing 13 optional hooks (12 lifecycle hooks
plus `close`). Hooks that can suppress or override a stage return a
`CallbackChoice`; pure transform hooks return the transformed value directly.

```kotlin
interface Plugin {
  val name: String

  suspend fun onUserMessage(invocationContext: InvocationContext, userMessage: Content): Content { /* default */ }

  suspend fun beforeRun(invocationContext: InvocationContext): CallbackChoice<Unit, Content> { /* default */ }

  suspend fun onEvent(invocationContext: InvocationContext, event: Event): Event { /* default */ }

  suspend fun afterRun(invocationContext: InvocationContext) { /* default */ }

  suspend fun beforeAgent(context: CallbackContext): CallbackChoice<EventActions, Content> { /* default */ }

  suspend fun afterAgent(context: CallbackContext): CallbackChoice<Unit, Content> { /* default */ }

  suspend fun beforeModel(
    context: CallbackContext, request: LlmRequest,
  ): CallbackChoice<LlmRequest, LlmResponse> { /* default */ }

  suspend fun afterModel(context: CallbackContext, response: LlmResponse): LlmResponse { /* default */ }

  suspend fun onModelError(
    context: CallbackContext, request: LlmRequest, error: Throwable,
  ): CallbackChoice<Unit, LlmResponse> { /* default */ }

  suspend fun beforeTool(
    context: ToolContext, tool: BaseTool, args: Map<String, Any>,
  ): CallbackChoice<Map<String, Any>, Map<String, Any>> { /* default */ }

  suspend fun afterTool(
    context: ToolContext, tool: BaseTool, args: Map<String, Any>, result: Map<String, Any>,
  ): Map<String, Any> { /* default */ }

  suspend fun onToolError(
    context: ToolContext, tool: BaseTool, args: Map<String, Any>, error: Throwable,
  ): CallbackChoice<Unit, Map<String, Any>> { /* default */ }

  suspend fun close() { /* default */ }
}
```

**Logic.** The 12 lifecycle hooks and when each fires:

1.  `onUserMessage(...): Content` - fires once per invocation on the incoming
    user message; the returned `Content` is threaded forward as the new message.
2.  `beforeRun(...): CallbackChoice<Unit, Content>` - fires before the
    invocation starts; `Break` aborts the invocation with the returned
    `Content`, `Continue` proceeds.
3.  `onEvent(...): Event` - fires for each event emitted during the invocation;
    the returned `Event` replaces it.
4.  `afterRun(...)` - fires after the invocation completes; used for cleanup and
    logging.
5.  `beforeAgent(...): CallbackChoice<EventActions, Content>` - fires before an
    agent runs; `Break` bypasses the agent, `Continue` merges the returned
    `EventActions` into the context and proceeds.
6.  `afterAgent(...): CallbackChoice<Unit, Content>` - fires after an agent
    runs; `Break` overrides the agent response.
7.  `beforeModel(...): CallbackChoice<LlmRequest, LlmResponse>` - fires before a
    model call; `Break` suppresses the call and returns the `LlmResponse`,
    `Continue` threads the (possibly modified) `LlmRequest` forward.
8.  `afterModel(...): LlmResponse` - fires after a model call; the returned
    `LlmResponse` is threaded forward (no `Break` at this stage).
9.  `onModelError(...): CallbackChoice<Unit, LlmResponse>` - fires when a model
    call throws; `Break` resolves with a fallback `LlmResponse`, `Continue` lets
    the next handler try.
10. `beforeTool(...): CallbackChoice<Map<String, Any>, Map<String, Any>>` -
    fires before a tool call; `Break` short-circuits with a result map,
    `Continue` threads the args map forward.
11. `afterTool(...): Map<String, Any>` - fires after a tool call; the returned
    result map is threaded forward.
12. `onToolError(...): CallbackChoice<Unit, Map<String, Any>>` - fires when a
    tool call throws; `Break` resolves with a result map, `Continue` lets the
    next handler try.

## PluginManager

Holds the registered plugins for a runner and pre-aggregates each plugin hook
into a per-hook callback list consumed by the callbacks pipeline. Also owns
plugin teardown.

```kotlin
class PluginManager(
  val plugins: List<Plugin> = emptyList(),
  val skipClosingPlugins: Boolean = false,
) {
  val onUserMessageCallbacks: List<OnUserMessageCallback>
  val beforeRunCallbacks: List<BeforeRunCallback>
  val onEventCallbacks: List<OnEventCallback>
  val afterRunCallbacks: List<AfterRunCallback>
  val beforeAgentCallbacks: List<BeforeAgentCallback>
  val afterAgentCallbacks: List<AfterAgentCallback>
  val beforeModelCallbacks: List<BeforeModelCallback>
  val afterModelCallbacks: List<AfterModelCallback>
  val onModelErrorCallbacks: List<OnModelErrorCallback>
  val beforeToolCallbacks: List<BeforeToolCallback>
  val afterToolCallbacks: List<AfterToolCallback>
  val onToolErrorCallbacks: List<OnToolErrorCallback>

  fun getPlugin(pluginName: String): Plugin?
  suspend fun close()
}
```

**Logic.**

1.  **Unique-name check (construction).** Duplicate names are computed via
    `plugins.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys`;
    `require` the set is empty, otherwise throw `IllegalArgumentException`
    listing the duplicates.
2.  **Hook pre-aggregation (construction).** For each of the 12 hook types the
    manager builds a `List<XCallback>` by mapping over `plugins`, wrapping each
    plugin method as one functional callback adapter (for example
    `onUserMessageCallbacks = plugins.map { plugin -> OnUserMessageCallback {
    ctx, msg -> plugin.onUserMessage(ctx, msg) } }`). Each list holds one
    adapter per plugin, in plugin registration order, computed once and reused
    for every invocation.
3.  **getPlugin.** Returns `plugins.find { it.name == pluginName }`.
4.  **close - exception aggregation.** If `skipClosingPlugins` is true, no-op
    and return (used when a sub-runner shares a parent's plugin instances and
    must not tear them down). Otherwise call `close()` on every plugin,
    collecting any thrown exception into a list rather than stopping, so every
    plugin gets a close attempt. If any exceptions were collected, `fold` them
    into a single `RuntimeException("Multiple exceptions occurred during
    close")` with each added via `addSuppressed`, and throw.

## LoggingPlugin

Diagnostic plugin that overrides every `Plugin` hook to log the stage and return
the pass-through value unchanged. Truncates rendered content and args to bounded
lengths.

```kotlin
class LoggingPlugin(override val name: String = "logging_plugin") : Plugin {
  // Overrides every Plugin callback (onUserMessage, beforeRun, onEvent, afterRun, beforeAgent,
  // afterAgent, beforeModel, afterModel, onModelError, beforeTool, afterTool, onToolError) with
  // the same signatures as Plugin above; each logs and returns the pass-through value.

  fun formatContent(content: Content?): String
  fun formatArgs(args: Map<String, Any>?): String

  companion object {
    const val MAX_CONTENT_LENGTH = 200
    const val MAX_ARGS_LENGTH = 300
  }
}
```

--------------------------------------------------------------------------------
