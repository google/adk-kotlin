# Callbacks and plugins

## Agent callbacks

Callbacks hook the agent (before / after), the model call (before / after / on error) and each tool call (before / after / on error). Pass them as lists on the agent. Each is an interface with a companion `invoke`, so a lambda works:

```kotlin
val blockDelete = BeforeToolCallback { context, tool, args ->
  if (tool.name == "delete_everything") CallbackChoice.Break(mapOf("error" to "not allowed"))
  else CallbackChoice.Continue(args)
}

LlmAgent(name = "a", model = model, tools = tools, beforeToolCallbacks = listOf(blockDelete))
```

- Most callbacks return a `CallbackChoice`. `CallbackChoice.Continue(value)` passes a possibly modified value to the next callback. `CallbackChoice.Break(value)` short-circuits with a replacement: a before-model `Break` skips the model call, a before-tool `Break` skips the tool, an on-error `Break` recovers.
- **After-model and after-tool callbacks are different:** they return the (possibly replaced) `LlmResponse` or result map directly. Do not wrap it in `Continue(...)`; that does not compile.
- A before-agent `Break` skips **that agent only**; an enclosing sequential or loop agent continues.
- Callbacks run in list order, and the first `Break` wins.
- The callback's context is a `Context`: read `state`, write with `updateState`, save artifacts, add to memory.

`core/src/commonMain/kotlin/com/google/adk/kt/callbacks/` has the exact interfaces.

## Plugins

A `Plugin` bundles the same callback kinds and installs them on the runner, so they apply to every agent. It also has runner-level hooks agents cannot see: on the user message, before and after the run, on each event (it may replace the event that is stored), and on run error (notification only).

```kotlin
class AuditPlugin : Plugin {
  override val name = "audit"
  override suspend fun onEvent(invocationContext: InvocationContext, event: Event): Event = event
}

InMemoryRunner(agent = root, plugins = listOf(AuditPlugin()))
```

Every hook has a pass-through default. Plugin callbacks run before the agent's own. Plugin names must be unique, and plugins are closed with the runner.
