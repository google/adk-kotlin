# State, sessions, artifacts, memory

## Reading and writing state

| From | Read | Write |
|---|---|---|
| tool | `context.context.state["k"]` | `context.actions.stateDelta["k"] = v` |
| callback | `context.state["k"]` (merged view) | `context.updateState("k", v)` |
| instruction | `{k}` or `{k?}` | n/a |
| custom agent | `context.session.state["k"]` | `EventActions(stateDelta = mutableMapOf("k" to v))` on an emitted event |
| caller | `runner.sessionService.getSession(key)?.state` | `runAsync(..., stateDelta = mapOf(...))` |
| `LlmAgent` | | `outputKey = "k"` saves the final text |

State values are `Any` and never null. To delete a key durably, call `removeStateByKey("k")` on the event's `actions`, which puts the `State.REMOVED` sentinel in the delta the runner persists. `State.remove` is a different thing: it mutates the live session object and records the removal only in that `State`'s own delta, so nothing reaches the event history.

Writes go into the event's `stateDelta` and are applied to the session when the runner persists the event. Direct mutation of `session.state` from an agent bypasses history and is not replayed.

## Prefixes

| Prefix | Visible to | Persisted |
|---|---|---|
| none | this session | yes |
| `user:` | every session of this user in the app | yes |
| `app:` | every user of the app | yes |
| `temp:` | the rest of this invocation | no; stripped from the event before it is stored |

`temp:` is right for scratch values between agents in one `SequentialAgent` run. Do not expect to read `temp:` keys off the events returned by `runAsync`, and do not expect `AgentTool` to pass them to a wrapped agent.

## outputKey and outputSchema

```kotlin
LlmAgent(
  name = "extractor",
  model = model,
  instruction = Instruction("Extract the order id and total from the message."),
  outputSchema = Schema(
    type = Type.OBJECT,
    properties = mapOf("orderId" to Schema(type = Type.STRING), "total" to Schema(type = Type.NUMBER)),
    required = listOf("orderId", "total"),
  ),
  outputKey = "order",
)
```

- `outputKey` writes only when the event is this agent's final response. With no schema the value is the raw text. With a schema the parsed map is stored; if parsing or validation fails the raw text is stored and an error is logged, so check the type when you read it.
- `outputSchema` must be a top-level `OBJECT`. There is no helper that turns a `@Serializable` class into a `Schema`; build it by hand.
- On Gemini 2.x, an agent with `outputSchema` and any tool, including the implicit `transfer_to_agent` added by `subAgents`, switches to a `set_model_response` tool internally. It works, but explicit nulls are dropped from the result.

## Sessions

`SessionKey(appName, userId, sessionId)` identifies a session. `InMemoryRunner` uses `appName = "InMemoryRunner"` unless told otherwise, so look sessions up with that name. Services:

| Service | Where | Use |
|---|---|---|
| `InMemorySessionService` | any | tests and local runs |
| `VertexAiSessionService` | JVM | Vertex AI Agent Engine sessions |
| `RoomSessionService` | Android | on-device persistence |

`runner.sessionService.getSession(key)` returns the live session with all events; `listSessions(appName, userId)` returns summaries.

## Artifacts

`ToolContext.saveArtifact(name, Part)` returns the version and records it in `actions.artifactDelta`. `loadArtifact(name, version = null)` returns the latest. A `user:` prefix on the filename makes it user-scoped across sessions. Without an `ArtifactService` on the runner, save throws `IllegalStateException` and load returns null. `InMemoryRunner` installs `InMemoryArtifactService` by default; `FileArtifactService(dir)` and `GcsArtifactService(bucket, client)` are the persistent options.

## Memory

`MemoryService.searchMemory(appName, userId, query)` is the read side, wired into agents through `LoadMemoryTool` or `PreloadMemoryTool`. `CallbackContext.addSessionToMemory()` and `addEventsToMemory(events)` write. `InMemoryMemoryService` is the default; `VertexAiMemoryBankService` and `VertexAiRagMemoryService` are the JVM options, `AppSearchMemoryService` the Android one.

## Compaction and caching

Long conversations can be compacted with a sliding window or a token threshold, and Gemini context caching can be enabled, but only through an `App`:

```kotlin
val app = App(appName = "support", rootAgent = root, eventsCompactionConfig = EventsCompactionConfig(...))
InMemoryRunner(app = app)
```

The `InMemoryRunner(agent = ...)` constructor leaves both off.
