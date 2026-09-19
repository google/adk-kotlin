# Callbacks and plugins

## Agent-level callbacks

Pass lists on `LlmAgent` (and the agent-level pair on any `BaseAgent`). Each callback type is an interface with one `suspend fun call(...)`, plus a companion `invoke` so a lambda works:

```kotlin
val logArgs = BeforeToolCallback { context, tool, args ->
  println("calling ${tool.name} with $args")
  CallbackChoice.Continue(args)
}

val blockDelete = BeforeToolCallback { context, tool, args ->
  if (tool.name == "delete_everything") CallbackChoice.Break(mapOf("error" to "not allowed"))
  else CallbackChoice.Continue(args)
}

LlmAgent(name = "a", model = model, tools = tools, beforeToolCallbacks = listOf(logArgs, blockDelete))
```

| Callback | Signature | `Break` means |
|---|---|---|
| `BeforeAgentCallback` | `(CallbackContext) -> CallbackChoice<EventActions, Content>` | skip the agent, emit this content, end the invocation |
| `AfterAgentCallback` | `(CallbackContext) -> CallbackChoice<Unit, Content>` | append this content as the agent's final response |
| `BeforeModelCallback` | `(CallbackContext, LlmRequest) -> CallbackChoice<LlmRequest, LlmResponse>` | skip the model call and use this response (does not count toward `maxLlmCalls`) |
| `AfterModelCallback` | `(CallbackContext, LlmResponse) -> LlmResponse` | return value replaces the response |
| `OnModelErrorCallback` | `(CallbackContext, LlmRequest, Throwable) -> CallbackChoice<Unit, LlmResponse>` | recover with this response |
| `BeforeToolCallback` | `(ToolContext, BaseTool, Map<String, Any?>) -> CallbackChoice<Map<String, Any?>, Map<String, Any?>>` | skip the tool and use this map |
| `AfterToolCallback` | `(ToolContext, BaseTool, args, result) -> Map<String, Any?>` | return value replaces the result |
| `OnToolErrorCallback` | `(ToolContext, BaseTool, args, Throwable) -> CallbackChoice<Unit, Map<String, Any?>>` | recover with this map |

`CallbackChoice.Continue(value)` passes the possibly-modified value to the next callback. Callbacks run in list order; the first `Break` wins.

`CallbackContext` gives you `state` (merged view of session state plus this event's pending delta), `updateState(key, value)`, `mergeEventActions(...)`, `endInvocation()`, artifact save/load, and `addSessionToMemory()`. `BeforeAgentCallback` returning `Continue(EventActions(...))` lets you attach state changes without producing content.

## Plugins

A `Plugin` is a named bundle of callbacks installed on the runner rather than on an agent, plus five runner-level hooks agents cannot see:

```kotlin
class AuditPlugin : Plugin {
  override val name = "audit"
  override suspend fun onUserMessage(ctx: InvocationContext, userMessage: Content): Content = userMessage
  override suspend fun beforeRun(ctx: InvocationContext): CallbackChoice<Unit, Content> = CallbackChoice.Continue(Unit)
  override suspend fun onEvent(ctx: InvocationContext, event: Event): Event = event   // the returned event is what gets persisted
  override suspend fun afterRun(ctx: InvocationContext) {}
  override suspend fun onRunError(ctx: InvocationContext, error: Throwable) {}         // notification only
  // beforeAgent / afterAgent / beforeModel / afterModel / onModelError / beforeTool / afterTool / onToolError
}

InMemoryRunner(agent = root, plugins = listOf(AuditPlugin()))
```

Every hook has a pass-through default. Plugin callbacks always run before the agent's own callbacks of the same kind. Plugin names must be unique within a runner. Plugins are `AutoCloseable` and are closed with the runner unless the `App`-based runner constructor is given `skipClosingPlugins = true`.

Bundled: `LoggingPlugin`, `DebugLoggingPlugin` (JVM and Android), and the BigQuery analytics plugin in `google-adk-kotlin-integrations`.
