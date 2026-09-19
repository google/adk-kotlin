# Telemetry and plugins

## Spans

The `telemetry` package defines platform-neutral `Tracer`, `Span`, `SpanBuilder`, `TelemetryContext` and a `TelemetryContextElement` that rides in the `CoroutineContext`. `commonJvmAndroidMain/telemetry/otel/` adapts them to OpenTelemetry; `Telemetry.tracer` defaults to `GlobalOpenTelemetry.getTracerProvider().tracerBuilder("gcp.vertex.agent")`, so with no SDK installed everything degrades to OpenTelemetry's own no-op. `Telemetry.setTracerForTest` swaps in a fake per thread.

| Span | Created in | Notable attributes |
|---|---|---|
| `invocation` | `AbstractRunner.runAsync` | parent is the telemetry context captured when `runAsync` was called |
| `invoke_agent <name>` | `BaseAgent.runAsync` | `gen_ai.operation.name=invoke_agent`, agent name and description, conversation id |
| `call_llm` | `LlmAgentTurn.invokeAndProcessModel` | request model, `top_p`, `max_tokens`, finish reasons, token usage (input, output, cache read, reasoning), `gcp.vertex.agent.llm_request` and `llm_response` JSON (always at least `{}` because the Dev UI parses them unconditionally) |
| `execute_tool <name>` | `InvocationContext.executeSingleFunctionCall` | tool name, description, `gen_ai.tool.call.id`, args and response JSON, MCP server id when the tool has it |
| `execute_tool (merged)` | `InvocationContext.handleFunctionCalls` | only when more than one tool ran in parallel |

Prompt and response content is put on spans only when `ADK_CAPTURE_MESSAGE_CONTENT_IN_SPANS` is `true` or `1`.

Helpers in `telemetry/Coroutines.kt` are internal: `withSpan` uses `withContext` and is unsafe inside `flow { }` (it breaks flow context preservation), so flow code uses `Flow.trace(name)` (backed by `flowOn`) or `tracedFlow(name) { span, spanContext -> ... }`.

## Plugins

```kotlin
interface Plugin : AutoCloseable {
  val name: String
  suspend fun onUserMessage(...), beforeRun(...), onEvent(...), afterRun(...), onRunError(...)
  suspend fun beforeAgent(...), afterAgent(...)
  suspend fun beforeModel(...), afterModel(...), onModelError(...)
  suspend fun beforeTool(...), afterTool(...), onToolError(...)
}
```

Every hook has a pass-through default, so a plugin overrides only what it needs. `PluginManager(plugins, skipClosingPlugins = false)` rejects duplicate names and pre-builds one callback list per hook. In every pipeline the plugin callbacks run **before** the agent's own callbacks. `onRunError` is notification only; it cannot suppress the exception.

Runner-level hooks (`beforeRun`, `afterRun`, `onEvent`, `onUserMessage`, `onRunError`) exist only on plugins; there is no agent-level equivalent. `onEvent` may return a replacement event, and the replacement is what gets persisted.

Bundled: `LoggingPlugin` (commonMain) and `DebugLoggingPlugin` (commonJvmAndroidMain). `integrations` adds a BigQuery analytics plugin.

## App

`App(appName, rootAgent, plugins, resumabilityConfig, eventsCompactionConfig, contextCacheConfig)` groups a root agent with runner-level configuration. `appName` must match `[a-zA-Z][a-zA-Z0-9_-]*` and cannot be `"user"`. Compaction and context caching are only reachable through an `App`; the plain `InMemoryRunner(agent)` constructor leaves them null.
