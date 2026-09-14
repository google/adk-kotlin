# Events, sessions and state

Files: `events/Event.kt`, `events/EventActions.kt`, `sessions/Session.kt`,
`sessions/State.kt`, `sessions/SessionService.kt`,
`sessions/InMemorySessionService.kt`.

## Event

`@Serializable data class Event` with, among others:

| Field | Notes |
|---|---|
| `id` | `Uuid.random()`; `@EncodeDefault(ALWAYS)` so a round-trip keeps it |
| `invocationId` | set by the runner |
| `author` | agent name, or `"user"` (`Role.USER`) |
| `content` | the `Content` (parts: text, function call, function response, inline data) |
| `actions` | `EventActions`, see below |
| `longRunningToolIds` | ids of function calls to long-running tools |
| `partial` | streaming chunk; never persisted |
| `turnComplete`, `finishReason`, `usageMetadata`, `groundingMetadata`, `citationMetadata`, `cacheMetadata`, `modelVersion` | copied from the `LlmResponse` |
| `errorCode`, `errorMessage`, `interrupted` | model-side error reporting |
| `branch` | dotted path from `ParallelAgent` |
| `customMetadata` | from `RunConfig.customMetadata` or callbacks |
| `timestamp` | epoch millis, `@EncodeDefault(ALWAYS)` |

Helpers: `functionCalls()`, `functionResponses()`,
`contentText(separator = "", includeThoughts = false)`, and the property that
drives the turn loop:

```kotlin
val isFinalResponse: Boolean
  get() {
    if (actions.skipSummarization || longRunningToolIds.isNotEmpty()) return true
    return functionCalls().isEmpty() && functionResponses().isEmpty() && !partial
  }
```

## EventActions

```kotlin
var skipSummarization: Boolean
val stateDelta: MutableMap<String, Any>            // concurrent map
val artifactDelta: MutableMap<String, Int>         // filename -> version
var transferToAgent: String?
var escalate: Boolean
var endOfAgent: Boolean
val requestedToolConfirmations: MutableMap<String, ToolConfirmation>
var rewindBeforeInvocationId: String?
var agentState: TypedData?
var compaction: EventCompaction?
```

`removeStateByKey(key)` records `State.REMOVED` in the delta so the removal
persists. `removeTempKeys()` drops `temp:` entries. `mergeWith(other)` ORs the
booleans, unions the maps with `other` winning, and is how parallel tool
responses are folded into one event.

Events from one LLM step can share one mutable `EventActions` instance. A
tool that writes `context.actions.stateDelta` late is visible on already
emitted partial events. Partials are never persisted, so session state is
unaffected, but a consumer reading actions off a partial event may see them
change.

## State

`class State(initialState, initialDelta) : Map<String, Any>`; every read and
write is guarded by a `Lock`. Mutators: `set`, `putAll`, `remove` (records
`REMOVED`), `clear`, `applyDelta`, `applyTempDelta`.

Prefixes:

| Prefix | Scope | Handling in `InMemorySessionService` |
|---|---|---|
| none | this session | stored with the session |
| `app:` | every user and session of the app | moved to a per-app map, prefix stripped in storage, restored on read |
| `user:` | every session of this user | moved to a per-user map, same treatment |
| `temp:` | this invocation only | applied to the live `Session.state`, then stripped from the event before it is stored |

## The `temp:` contract in `SessionService.appendEvent`

The default implementation is the contract every session service must keep:

```kotlin
suspend fun appendEvent(session: Session, event: Event): Event {
  if (event.partial) return event
  session.state.applyTempDelta(event.actions.stateDelta)  // temp: keys hit the live session
  event.actions.removeTempKeys()                          // and are stripped from the event, in place
  session.state.applyDelta(event.actions.stateDelta)      // everything else
  session.events.add(event)
  session.lastUpdateTime = Instant.fromEpochMilliseconds(event.timestamp)
  return event
}
```

Two consequences. A tool can write `temp:x` and a later agent in the same
invocation can read it, but the event a caller gets back from `runAsync` no
longer carries `temp:x` in its `stateDelta`. And an overriding service must
either call `super.appendEvent` first and then persist the trimmed event, or
call `applyTempDelta` and `removeTempKeys` itself before persisting.
`RoomSessionService` does the latter because its stale-write check needs the
pre-update `lastUpdateTime`.

## Session services

| Class | Source set | Persistence |
|---|---|---|
| `InMemorySessionService` | commonMain | maps behind a `Mutex`; app and user state live outside the session objects |
| `VertexAiSessionService` | jvmMain | Vertex AI Agent Engine sessions; `super.appendEvent` then a remote append |
| `RoomSessionService` | androidMain | Room database with an atomic append inside a `@Transaction` |

Artifact services: `InMemoryArtifactService`, `FileArtifactService`
(JVM and Android, `Dispatchers.IO`), `GcsArtifactService` (JVM), plus a
`ForwardingArtifactService` used by `ToolContext`. A filename starting with
`user:` is user-scoped rather than session-scoped.

Memory services: `InMemoryMemoryService`, `VertexAiMemoryBankService`,
`VertexAiRagMemoryService` (JVM), `AppSearchMemoryService` (Android).

## Rewind

`Runner.rewindAsync(userId, sessionId, rewindBeforeInvocationId)` computes the
reverse `stateDelta` (skipping `app:` and `user:` keys, using `State.REMOVED`
for keys that did not exist) and the reverse `artifactDelta` (re-saving the
older version as a new version), then appends a synthetic `user` event with
`rewindBeforeInvocationId` set. History is never deleted.
