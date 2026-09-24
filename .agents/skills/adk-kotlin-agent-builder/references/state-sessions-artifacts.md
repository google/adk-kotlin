# State, sessions, artifacts, memory

## State

| From | Read | Write |
| --- | --- | --- |
| tool or callback | `context.state[k]` | `context.updateState(k, v)` |
| instruction | `{k}`, or `{k?}` when optional | |
| caller | the session from `sessionService.getSession(...)` | `stateDelta` on `runAsync` |
| `LlmAgent` | | `outputKey = "k"` stores the agent's final text |

Prefixes set the scope: none is this session, `user:` is every session of the user, `app:` is every user of the app, and `temp:` is this invocation only (never stored). Whether `user:` and `app:` keys are really shared depends on the session service: the in-memory and Room services share them across sessions, while `VertexAiSessionService` stores them per session. Values are never null; remove a key with `context.actions.removeStateByKey(k)`.

## outputKey and outputSchema

`outputKey` stores the agent's final text in state. With `outputSchema` (a hand-built `Schema` of type `OBJECT`), the parsed map is stored instead; if parsing fails the raw text is stored, so check the type when reading. Also see the structured-output rule in best-practices.md.

## Sessions

A session is identified by `SessionKey(appName, userId, id)`. `InMemoryRunner` uses the app name `"InMemoryRunner"` unless given one. `getSession` returns a copy; changing it does not change the stored session. Use the in-memory service for tests; the persistent services (Vertex AI on JVM, Room on Android) live in the platform source sets.

## Artifacts

`context.saveArtifact(name, part)` stores a new version and returns its number; `loadArtifact(name)` returns the latest. A `user:` filename prefix makes an artifact user-scoped across sessions. The runner needs an artifact service; `InMemoryRunner` provides one.

## Memory

Agents read long-term memory through `LoadMemoryTool` (the model searches on demand) or `PreloadMemoryTool` (relevant memories are added to every request). Write with `context.addSessionToMemory()` and related methods.

## Compaction, caching, resumability

These are configured on an `App` (not on the agent), and the runner is built from the app with `InMemoryRunner(app = ...)`. Construct `App` with named arguments; see `apps/App.kt` for the current options.
