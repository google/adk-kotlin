# Event compaction

> Internal. Part of the ADK Kotlin specification: the repo-root `SPEC.md` is the
> charter and index, and `DECISIONS.md` holds the `[D-n]` design decisions. Not
> exported to GitHub.

*Package `com.google.adk.kt.summarizer`.*

Event compaction condenses older conversation events into a compact summary so
long sessions stay within model context limits, while never dropping an
in-flight tool interaction.

## EventSummarizer

Strategy that turns a window of events into a single summary event, or `null`
when there is nothing to summarize.

```kotlin
interface EventSummarizer {
  suspend fun summarizeEvents(events: List<Event>): Event?
}
```

## EventCompactor

Strategy that selects which events of a session to compact and appends the
resulting summary via the session service.

```kotlin
interface EventCompactor {
  suspend fun compact(session: Session, sessionService: SessionService)
}
```

## EventsCompactionConfig

Configuration attached to an `App` enabling sliding-window compaction. The two
size knobs must be set together or both left null; the summarizer is optional.

```kotlin
data class EventsCompactionConfig(
  val compactionInterval: Int? = null,
  val overlapSize: Int? = null,
  val summarizer: EventSummarizer? = null,
) {
  fun hasSlidingWindowConfig(): Boolean
}
// init: compactionInterval > 0 (if set); overlapSize >= 0 (if set); both set or both null.
```

Both `compactionInterval` and `overlapSize` are validated together:
`compactionInterval` must be `> 0` when set, `overlapSize` must be `>= 0` when
set, and either both are set or both are null. When `summarizer` is null the
default is an `LlmEventSummarizer` built on the root `LlmAgent`'s model.

## LlmEventSummarizer

Default `EventSummarizer`: it formats the events into a prompt, calls a model,
and wraps the model's summary in an `EventCompaction`.

```kotlin
class LlmEventSummarizer(
  val model: Model,
  val promptTemplate: String = DEFAULT_PROMPT_TEMPLATE,   // default constant is private
) : EventSummarizer {
  override suspend fun summarizeEvents(events: List<Event>): Event?
}
```

**Logic.**

1.  Return `null` immediately if `events` is empty.
2.  Build the prompt by replacing the `"{conversation_history}"` placeholder
    (required in the template, enforced at construction) with the formatted
    events.
3.  Build an `LlmRequest` with the model and a single user `Content` carrying
    the prompt.
4.  Call the model non-streaming and take the first response; warn and return
    `null` if the response or its content is null.
5.  Wrap the result in an `EventCompaction` whose `startTimestamp` is the first
    event's timestamp, `endTimestamp` is the last event's timestamp, and
    `compactedContent` is the summary content (role set to model).
6.  Return a new `Event` carrying that compaction in its `EventActions`, along
    with the response usage metadata.

## SlidingWindowEventCompactor

`EventCompactor` that walks the live event list in reverse to pick a compaction
window, shrinks it to a self-contained prefix, summarizes it, and appends the
summary.

```kotlin
// Constructor param is `private val` - not exposed as a public property.
class SlidingWindowEventCompactor(private val config: EventsCompactionConfig) : EventCompactor {
  override suspend fun compact(session: Session, sessionService: SessionService)
}
```

**Logic.**

1.  **compact.** Return early if `!config.hasSlidingWindowConfig()`; require a
    non-null `config.summarizer`; compute `liveEvents =
    applyRewinds(session.events)` so rewound content cannot leak back through a
    summary; select the window with `selectCompactionWindow(liveEvents)` (return
    if null); summarize it (return if null); then
    `sessionService.appendEvent(session, compactionEvent)`.
2.  **selectCompactionWindow - reverse walk.** Read `compactionInterval` and
    `overlapSize` from the config (return null if either is missing), then walk
    events from last to first tracking selected invocations and a
    `lastCompactTimestamp` boundary.
    -   For a normal event (has an `invocationId` and is not itself a compaction
        event): if its invocation is already selected, add it and continue. On
        crossing the most recent existing compaction boundary (`event.timestamp
        <= lastCompactTimestamp`), stop if fewer than `compactionInterval` new
        invocations were gathered, otherwise lock in `targetSize = new
        invocations + overlapSize`. Accumulate the event while still gathering
        new invocations or still within the overlap budget, otherwise break.
    -   For a compaction event: advance the boundary via `lastCompactTimestamp =
        max(lastCompactTimestamp, compaction.endTimestamp)`.
    -   After the walk, return null if fewer than `compactionInterval`
        invocations were selected; otherwise reverse to chronological order,
        shrink via `longestSelfContainedPrefix()`, and return it unless empty.
3.  **longestSelfContainedPrefix - the safety net.** A single left-to-right pass
    tracks open obligations keyed by call id: function responses close
    obligations, function calls open them, and requested tool confirmations
    (HITL) also open obligations. After each event, if no obligations are open,
    that index is a balanced cut point. The returned prefix ends at the last
    balanced point, so it never splits a function call from its response nor a
    HITL confirmation request from its resolving response; it is empty if the
    window never reaches balance.

**Status.** Auth-request handling in window selection is not yet implemented
(b/505630632).

--------------------------------------------------------------------------------
