# Events

> Internal. Part of the ADK Kotlin specification: the repo-root `SPEC.md` is the
> charter and index, and `DECISIONS.md` holds the `[D-n]` design decisions. Not
> exported to GitHub.

*Package `com.google.adk.kt.events`.*

An `Event` is one immutable, serializable entry in a session's log.
`EventActions` carries the side effects an event requests (state delta, artifact
delta, control flags).

## Event

One entry in a session's event log: an author, optional content, and the actions
it requests.

```kotlin
@Serializable
data class Event(
  val id: String = Uuid.random(),
  val invocationId: String? = null,
  val author: String,
  val content: Content? = null,
  val actions: EventActions = EventActions(),
  val longRunningToolIds: Set<String> = emptySet(),
  val partial: Boolean = false,
  val turnComplete: Boolean = false,
  val errorCode: String? = null,
  val errorMessage: String? = null,
  val finishReason: FinishReason? = null,
  val usageMetadata: UsageMetadata? = null,
  val avgLogProbs: Double? = null,
  val interrupted: Boolean = false,
  val branch: String? = null,
  val groundingMetadata: GroundingMetadata? = null,
  val modelVersion: String? = null,
  val citationMetadata: CitationMetadata? = null,
  val customMetadata: Map<String, @Contextual Any>? = null,
  val timestamp: Long = Clock.System.now().toEpochMilliseconds(),
) {
  fun functionCalls(): List<FunctionCall>
  fun functionResponses(): List<FunctionResponse>

  val isFinalResponse: Boolean            // computed getter

  fun hasTrailingCodeExecutionResult(): Boolean   // currently always false
  fun populateClientFunctionCallId(): Event
}
```

**Logic.**

1.  `functionCalls()` and `functionResponses()` map the content's parts to their
    `functionCall` / `functionResponse` values (empty when there is no content).
2.  `isFinalResponse` short-circuits to `true` if `actions.skipSummarization` or
    `longRunningToolIds.isNotEmpty()`. Otherwise it is `true` only when all
    hold: no function calls, no function responses, `!partial`, and
    `!hasTrailingCodeExecutionResult()`.
3.  `hasTrailingCodeExecutionResult()` currently always returns `false` because
    the Kotlin `Part` type does not yet model `codeExecutionResult`. The clause
    is kept in `isFinalResponse` so the structure matches Java ADK and a future
    `Part.codeExecutionResult` wires into the right gate.
4.  `populateClientFunctionCallId()` returns `this` unchanged when there is no
    content or no parts; otherwise it assigns a generated id to each model
    function call that lacks one and returns a copy only if something changed
    (immutable, no copy when nothing changed).

## EventActions

The side effects and control flags an event carries: state and artifact deltas,
transfer/escalate/end-of-agent flags, rewind request, resume checkpoint, and
compaction.

```kotlin
@Serializable
data class EventActions(
  var skipSummarization: Boolean = false,
  val stateDelta: MutableMap<String, @Contextual Any> = concurrentMutableMapOf(),
  val artifactDelta: MutableMap<String, Int> = concurrentMutableMapOf(),
  var transferToAgent: String? = null,
  var escalate: Boolean = false,
  var endOfAgent: Boolean = false,
  val requestedToolConfirmations: MutableMap<String, ToolConfirmation> = concurrentMutableMapOf(),
  var rewindBeforeInvocationId: String? = null,
  var agentState: TypedData? = null,
  var compaction: EventCompaction? = null,
) {
  fun removeStateByKey(key: String)
  fun mergeWith(other: EventActions): EventActions
}
```

There is no `requestedAuth` field (Python has one). Authorization and other
human-in-the-loop approvals are modeled through `requestedToolConfirmations`
(keyed by function-call id) and the `adk_request_confirmation` flow. [D-16]

**Logic.**

1.  `removeStateByKey(key)` sets `stateDelta[key] = State.REMOVED`, recording a
    tombstone so a downstream `applyDelta` / `mergeStateDelta` deletes the key.
2.  `mergeWith(other)` returns a new `EventActions` (via `copy`) combining the
    two:
    -   boolean OR for `skipSummarization`, `escalate`, `endOfAgent`.
    -   map concatenation for `stateDelta`, `artifactDelta`,
        `requestedToolConfirmations`: a fresh concurrent map with `putAll(this)`
        then `putAll(other)`, so on a key collision `other` wins.
    -   `other ?: this` for `transferToAgent`, `rewindBeforeInvocationId`,
        `agentState`, `compaction` (other overrides when set, else keep this).

## EventCompaction

A summary that replaces a span of events. `init` requires `endTimestamp >=
startTimestamp`.

```kotlin
@Serializable
data class EventCompaction(
  val startTimestamp: Long,
  val endTimestamp: Long,
  val compactedContent: Content,
)
```

## ToolConfirmation

A human-in-the-loop confirmation response for a tool call.

```kotlin
@Serializable
data class ToolConfirmation(
  val confirmed: Boolean,
  @Contextual val payload: Any? = null,
  val hint: String? = null,
) {
  companion object {
    const val CONFIRMED_KEY = "confirmed"
    const val PAYLOAD_KEY = "payload"
    const val HINT_KEY = "hint"
  }
}
```

## Internal (not public API)

-   `RewindEvents.kt` - `internal fun applyRewinds(events)`: returns the list of
    live events after honoring rewind requests, the single source of truth
    shared by prompt building and context compaction. It walks the events
    backward; for an event carrying a non-empty `rewindBeforeInvocationId`, it
    scans forward for the first event whose `invocationId` matches, sets the
    index to that position, and breaks. The outer decrement then also skips that
    first event. So a rewind drops the rewind-carrying event plus the entire
    span back to and including the earliest event of the named invocation. An
    unknown invocation id drops only the rewind-carrying event; an empty/null id
    is treated as no rewind.
-   `Event.kt` - `internal fun
    List<FunctionCall>.getLongRunningFunctionIds(...)`.

--------------------------------------------------------------------------------
