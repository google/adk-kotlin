# One LlmAgent turn

Files: `agents/LlmAgent.kt`, `agents/LlmAgentTurn.kt`, `processors/`,
`agents/InvocationContext.kt` (tool execution), `callbacks/`.

## Loop

`LlmAgent.runAsyncImpl` calls `executeTurns`, which repeats:

```
turn = LlmAgentTurn(agent, context, systemBeforeTurnProcessors, systemAfterTurnProcessors).execute()
collect events, each passed through maybeSaveOutputToState()
stop when: isEndOfInvocation, or the last event isFinalResponse,
          or actions.endOfAgent, or maxSteps reached
```

A turn that ends with function calls is not final, so the loop runs again with
the function responses in the contents. That is how the tool loop works: there
is no separate tool-loop class.

## Request pipeline

`systemBeforeTurnProcessors` is a fixed ordered list on `LlmAgent`:

| Order | Processor | Adds |
|---|---|---|
| 1 | `BasicRequestProcessor` | model name, `generateContentConfig`, output schema plumbing |
| 2 | `RequestConfirmationProcessor` | tool-confirmation replay |
| 3 | `InstructionsProcessor` | `staticInstruction` first, then the resolved `instruction`; the resolved instruction becomes the system instruction only when there is no static instruction, otherwise it is appended as user content |
| 4 | `CompactionRequestProcessor` | compacted history, before contents so the summary replaces the raw events |
| 5 | `ContentsProcessor` | session events filtered to this invocation and branch, honouring `includeContents` |
| 6 | `ContextCacheRequestProcessor` | Gemini context-cache metadata |
| 7 | `AgentTransferProcessor` | `transfer_to_agent` tool and instructions when sub-agents or a transferable parent exist |
| 8 | `OutputSchemaProcessor` | response schema, or the `set_model_response` tool on Gemini 2.x where a schema cannot coexist with tools |

After the list, each tool's `processLlmRequest` and each toolset's
`processLlmRequest` run, which is how tools register their declarations.
The interfaces are internal:

```kotlin
internal interface LlmRequestProcessor {
  suspend fun process(context: InvocationContext, request: LlmRequest, emitEvent: suspend (Event) -> Unit = {}): LlmRequest
}
```

## Model call

`invokeAndProcessModel` is a `call_llm` span. Order:

1. `beforeModel` callbacks, plugins first. `Break(LlmResponse)` skips the
   model entirely and does not count toward `maxLlmCalls`.
2. `context.incrementLlmCallsCount()`.
3. `model.generateContent(request, stream = runConfig.streamingMode == SSE)`
   collected with `flowOn(spanContext)` so chunks stay inside the span.
4. Each chunk goes through `afterModel` callbacks and then becomes an `Event`.
   With SSE the partial chunks are emitted with `partial = true` and the
   aggregated final response follows.
5. An exception runs `onModelError` callbacks. `Break(LlmResponse)` recovers
   with that response; `Continue` rethrows.

## Tool execution is parallel

`InvocationContext.handleFunctionCalls(functionCalls, tools, filters,
toolConfirmations)` runs every function call of a response concurrently:

```kotlin
coroutineScope {
  calls.map { async { executeSingleFunctionCall(it, tools, confirmation) } }.awaitAll()
}
```

No dispatcher is specified; the tools inherit whatever context the collector
used. A tool that blocks a thread therefore blocks the caller's dispatcher.
Per call:

1. `beforeTool` callbacks (plugins first). `Break(map)` becomes the result
   without running the tool.
2. An unknown tool name produces a "tool not found" response listing the
   available tools rather than throwing.
3. `tool.run(toolContext, args)` inside `execute_tool <name>`.
4. An exception runs `onToolError`; `Break(map)` recovers, otherwise it
   rethrows with `error.type` on the span.
5. A long-running tool returning `Unit` produces no response event, which ends
   the turn and pauses the invocation until the client sends the response.
   Other `Unit` or `null` results become `{}`.
6. `afterTool` callbacks can replace the result map.

Responses for the parallel calls are merged into one `user`-role event whose
`EventActions` is the fold of each tool's actions (`mergeWith`).

## Callbacks

Every callback is a plain interface with a `suspend fun call(...)` and a
companion `invoke` so a lambda can be passed. They are not `fun interface`s
because Kotlin cannot compile a functional interface whose only method is
`suspend`. Exact shapes:

```kotlin
BeforeAgentCallback:  suspend fun call(context: CallbackContext): CallbackChoice<EventActions, Content>
AfterAgentCallback:   suspend fun call(context: CallbackContext): CallbackChoice<Unit, Content>
BeforeModelCallback:  suspend fun call(context: CallbackContext, request: LlmRequest): CallbackChoice<LlmRequest, LlmResponse>
AfterModelCallback:   suspend fun call(context: CallbackContext, response: LlmResponse): LlmResponse
OnModelErrorCallback: suspend fun call(context: CallbackContext, request: LlmRequest, error: Throwable): CallbackChoice<Unit, LlmResponse>
BeforeToolCallback:   suspend fun call(context: ToolContext, tool: BaseTool, args: Map<String, Any?>): CallbackChoice<Map<String, Any?>, Map<String, Any?>>
AfterToolCallback:    suspend fun call(context: ToolContext, tool: BaseTool, args: Map<String, Any?>, result: Map<String, Any?>): Map<String, Any?>
OnToolErrorCallback:  suspend fun call(context: ToolContext, tool: BaseTool, args: Map<String, Any?>, error: Throwable): CallbackChoice<Unit, Map<String, Any?>>
```

`CallbackChoice.Continue(value)` passes a possibly modified value down the
chain; `CallbackChoice.Break(value)` short-circuits with a replacement. In
every pipeline the `PluginManager`'s callbacks run before the agent's own.

## Output key and schema

`maybeSaveOutputToState` runs on each event the agent emits. It only acts when
the event is authored by this agent, `outputKey` is set, and
`event.isFinalResponse`. It concatenates the non-thought text parts with no
separator and writes the string to `event.actions.stateDelta[outputKey]`.
With an `outputSchema`, the text is parsed and validated and the resulting
`Map` is stored; on failure the raw text is stored instead and an error is
logged, so downstream code must not assume the value is a map.
