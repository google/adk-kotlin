# ADK Kotlin Rules That Bite

The failure modes that account for most broken ADK Kotlin agents. Each is a silent or confusing failure rather than a clear compile error, which is why they are collected here. Every rule is traceable to a source comment, an error message, or a `fix:` entry in `CHANGELOG.md`.

## Tool results must be JSON-native

A hand-written `BaseTool` or `FunctionTool` may return a data class; it compiles, the tool runs, and then persisting the event fails with `AnySerializer cannot serialize value of type X`. Return a `Map`, `List`, `String`, number or `Boolean`, or use `@Tool` and let KSP generate the conversion.

```kotlin
// Fails at event persistence
override suspend fun run(context: ToolContext, args: Map<String, Any?>): Any = Report(total = 3)

// Works
override suspend fun run(context: ToolContext, args: Map<String, Any?>): Any = mapOf("total" to 3)

// Works, and the model sees {"result": {"total": 3}}
@Tool fun report(): Report = Report(total = 3)
```

A returned `Map` keeps only its `String`-keyed entries; `Map<Int, X>` reaches the model as `{}`. Any non-map value is wrapped as `{"result": value}`.

## `@Tool` defaults must be nullable

The generated `execute` cannot omit an argument, so `level: Double = 1.0` fails KSP with `Default arguments must be nullable`. Write `level: Double? = 1.0` and treat `null` as "not provided". The same applies to data-class constructor parameters used as tool arguments.

## Generated tools live in leaf source sets

In a Kotlin Multiplatform module, `commonMain` cannot see KSP output. Put the `LlmAgent` that calls `Service().generatedTools()` in `jvmMain` or `androidMain`, and put tests that use generated tools in `jvmTest` or `androidHostTest`. An `Unresolved reference: generatedTools` with a correct KSP configuration is almost always this.

## `Unit` from a long-running tool means "not yet"

For a tool with `isLongRunning = true`, returning `Unit` suppresses the function response, marks the event final, and pauses the turn. Returning an empty map is a completed response and does not pause. For a regular tool, `Unit` becomes `{}`. Decide which you want before choosing the return type.

## State is written through `EventActions`, not by mutating the session

Tools write `context.actions.stateDelta["k"] = v`; callbacks call `updateState`; custom agents attach `EventActions(stateDelta = ...)` to an emitted event. The runner applies deltas when it persists the event. A direct `context.session.state["k"] = v` is invisible to history, to compaction, and to any replay.

`State` values are `Any`, never `null`. To delete a key, call `removeStateByKey` on the event's `actions`; it records a sentinel in the delta the runner persists. Calling `remove` on the `State` object itself only changes the in-memory session and is never written to history.

## `temp:` keys vanish before persistence

`temp:` values are applied to the live session and then stripped from the event in place. They are readable by later agents in the same invocation, but `runAsync` hands back events without them, and `AgentTool` does not seed them into the wrapped agent's session.

## `{placeholder}` throws when the key is missing

`Instruction("Write about {topic}")` raises `IllegalArgumentException: Context variable not found` if `topic` is not in state when the turn starts. Use `{topic?}` for optional keys, or seed the key through `stateDelta` on `runAsync`. `staticInstruction` is never templated.

## `staticInstruction` forces `instruction` to be user content

With `staticInstruction` set, the resolved `instruction` is appended as user content instead of becoming the system instruction, and the processor then requires `role == "user"`. `Instruction("some text")` resolves to a `Content` whose role is null, so pairing the two throws `IllegalArgumentException: Instruction content must have role 'user'` on the first turn. Pass the content explicitly:

```kotlin
LlmAgent(
  name = "cached",
  model = model,
  staticInstruction = Content(parts = listOf(Part(text = longCacheablePrefix))),
  instruction = Instruction(Content(role = Role.USER, parts = listOf(Part(text = "Answer concisely.")))),
)
```

## `escalate` exits a loop; `endInvocation()` does not

`context.endInvocation()` (and `actions.endOfAgent`) stops the current `LlmAgent`'s step loop only. A `SequentialAgent` moves to the next child and a `LoopAgent` starts the next iteration. To leave a `LoopAgent`, set `context.actions.escalate = true` or give the agent `ExitLoopTool()`.

## One parent per agent instance

`BaseAgent.init` sets `parentAgent`; a second parent throws `Agent X already has a parent: Y`. Build a fresh instance for each place in the tree. This bites when a shared `val` agent is reused in two `SequentialAgent`s.

## Streaming emits partials and the final event

With `StreamingMode.SSE`, the flow yields every partial chunk and then the aggregated final event. Filter on `event.partial` before printing or accumulating, or the answer appears twice. Partials are never persisted.

## `outputSchema` plus tools changes the mechanism

On Gemini 2.x a response schema cannot coexist with tools, so when an agent has `outputSchema` and any tool, including the implicit `transfer_to_agent` that `subAgents` adds, the framework routes the answer through a `set_model_response` tool. Explicit nulls are dropped on that path, and validation failures are hard errors, whereas the direct path stores raw text and logs. Keep structured-output agents tool-free and leaf-level when you can.

## Tools inherit the caller's dispatcher

Tool calls run with `async` inside `coroutineScope` and no dispatcher of their own. A tool that blocks (JDBC, `HttpURLConnection`, file I/O) blocks the thread that collected `runAsync` and stalls sibling tools. Wrap blocking work in `withContext(Dispatchers.IO)` inside the tool.

## Close the runner

`Runner` and `Toolset` are `AutoCloseable`. Forgetting `use { }` leaks MCP sessions and plugins. Session, artifact and memory services are not closed by the runner; close them yourself if they hold connections.

## `runner.run` and `AsyncJavaHelpers` block

Both use `runBlocking`. Calling them from inside a coroutine, a Ktor handler, or any thread the dispatcher needs will deadlock. From Kotlin, collect `runAsync` instead.

## Unknown tool names do not fail the turn

If the model calls a tool that is not in the agent's list, the runtime answers with an error function-response naming the available tools and lets the model retry. A misspelt tool name therefore looks like a model that keeps "trying", not like an exception. Check `event.functionResponses()` for an `error` entry when debugging.

## Provider-side tools do not mix with function tools

`GoogleSearchTool()` alongside function tools is rejected by the API unless `bypassMultiToolsLimit = true`, which silently swaps it for an agent-tool that does the search in a sub-agent. Prefer a dedicated search agent as a sub-agent or `AgentTool` when you need both.

## Android differences

- The GenAI SDK's `Gemini` model does not accept API keys or `GoogleCredentials` on Android; use the Firebase AI model or an on-device model.
- `AppFunctionsToolset` needs the app to add `androidx.appfunctions` itself (core has it `compileOnly`), and a device without AppFunctions contributes zero tools rather than failing.
- ML Kit's Gemini Nano model drops function-call parts; do not give that agent tools.

## Names are validated

Agent names must match `_?[a-zA-Z0-9]*([. _-][a-zA-Z0-9]+)*` and cannot be `user`. `App` names must match `[a-zA-Z][a-zA-Z0-9_-]*`. `@Param(name)` wire names must be unique and non-blank within one function. All of these throw at construction or at KSP time, before any model call.
