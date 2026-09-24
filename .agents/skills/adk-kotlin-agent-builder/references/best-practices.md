# Rules that bite

Failure modes that show up as confusing runtime behaviour rather than compile errors. Each comes from a design decision, not an implementation detail. If one no longer matches the code, trust the code and fix the rule.

## Tool results must be JSON-native

Return `Map`, `List`, `String`, numbers or `Boolean` from a hand-written tool (`null` too, for Java tools; Kotlin's `run` and `execute` return non-null `Any`). A data class works under `InMemoryRunner`, which never serializes events, and then fails wherever events are serialized (persistent sessions, the web server, the Dev UI). `@Tool` functions may return data classes because KSP generates the conversion.

A returned `Map` keeps only its `String` keys. `Unit` and `null` become `{}`; any other non-map value is wrapped as `{"result": value}`.

## `@Tool` optional parameters are nullable, and the default goes in the body

A model that omits an argument sends nothing, so the generated tool passes `null`. Declare `level: Double? = null` and write `level ?: 1.0` in the body. A non-null default (`level: Double = 1.0`) is rejected by KSP.

## Generated tools are only visible from leaf source sets

In a Kotlin Multiplatform module, `commonMain` cannot see KSP output. Build the agent that calls `generatedTools()` in `jvmMain` or `androidMain`, and test it from a leaf test source set (`jvmTest`, and `androidHostTest`, or `androidUnitTest` in projects that use `androidTarget()`). An unresolved `generatedTools` with correct KSP wiring is almost always this.

## Read state through `context.state`, write through `context.updateState`

`context.state` includes the writes this tool or callback has made. `context.context.state` is committed-only and does not, so write-then-read through it looks like a lost write. Assigning to `session.state` directly is never recorded in history.

## `temp:` keys never reach storage

They are readable by later agents in the same invocation, then stripped from the event before it is stored. They are not on the events `runAsync` returns, and `AgentTool` does not pass them to the wrapped agent.

## A missing `{placeholder}` throws

`Instruction("Write about {topic}")` fails the turn when `topic` is not in state. Use `{topic?}` for optional keys, or seed the key with `stateDelta`.

## `Unit` from a long-running tool means "not yet"

For an `isLongRunning` tool, returning `Unit` emits no response and ends the run; the client continues later by sending the response in a new `runAsync`. Returning any value, even an empty map, completes the call.

## `escalate` exits a loop; `endInvocation()` does not

`context.endInvocation()` stops only the current `LlmAgent`. A `BeforeAgentCallback` `Break` likewise skips only that agent. Enclosing `SequentialAgent` and `LoopAgent`s carry on. To leave a `LoopAgent`, give the agent `ExitLoopTool()`, or from a custom tool set both `escalate` and `skipSummarization`. With `escalate` alone the agent keeps calling the model until its turn ends.

## One parent per agent instance

Adding the same agent instance to two `subAgents` lists throws. Build a fresh instance for each place in the tree.

## Structured output and tools interact

Some models cannot combine a response schema with tools, so an agent with `outputSchema` and any tool may answer through an internal `set_model_response` tool, which behaves slightly differently (explicit nulls dropped, validation failures are errors). "Any tool" includes the implicit `transfer_to_agent`, which an agent gets when it has sub-agents, or a parent, unless both `disallowTransferToParent` and `disallowTransferToPeers` are set. Keep structured-output agents tool-free, and set both flags when they are sub-agents.

## Tools inherit the caller's dispatcher

Tool calls from one model response run concurrently in the collector's context. A tool that blocks (JDBC, blocking HTTP, file I/O) blocks that thread and delays its siblings. Wrap blocking work in `withContext(Dispatchers.IO)` inside the tool.

## Close the runner; don't block inside coroutines

Use `runner.use { }` so MCP sessions and plugins are closed; session, artifact and memory services are yours to close. `runner.run(...)` and the blocking `AsyncJavaHelpers` methods (`await`, `collect`, `forEach`) use `runBlocking`, so calling them from a coroutine or a server handler can deadlock. From Kotlin, collect `runAsync`.

## Unknown tool names do not throw

If the model calls a tool the agent does not have, it gets an error response listing the available tools and may retry. A misspelt tool name looks like a model that keeps trying; check `functionResponses()` for an `error` entry.

## Names are validated early

Agent names allow letters, digits, and space, `_`, `-` or `.` separators, and cannot be `user`. `App` names are stricter. Both are validated when the object is constructed, before any model call.
