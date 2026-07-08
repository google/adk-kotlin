# Sessions and state

> Internal. Part of the ADK Kotlin specification: the repo-root `SPEC.md` is the
> charter and index, and `DECISIONS.md` holds the `[D-n]` design decisions. Not
> exported to GitHub.

*Package `com.google.adk.kt.sessions`.*

Sessions hold the ordered event log and the mutable state map for one
conversation. `SessionService` is the storage contract; `State` is the
delta-tracking map used everywhere state is read or written.

## Session

Encapsulates the `State` and `Event`s of one session, addressed by a
`SessionKey`.

```kotlin
data class Session(
  val key: SessionKey,
  val state: State = State(),
  val events: MutableList<Event> = mutableListOf(),
  var lastUpdateTime: Instant = Instant.fromEpochMilliseconds(0),
)
```

## SessionService

Storage contract for sessions. `createSession`, `getSession`, `listSessions`,
`deleteSession`, and `listEvents` are abstract. `closeSession` and `appendEvent`
have default bodies.

```kotlin
interface SessionService {
  suspend fun createSession(key: SessionKey, state: Map<String, Any>? = null): Session   // abstract
  suspend fun getSession(key: SessionKey, config: GetSessionConfig? = null): Session?     // abstract
  suspend fun listSessions(appName: String, userId: String): ListSessionsResponse         // abstract
  suspend fun deleteSession(key: SessionKey)                                              // abstract
  suspend fun listEvents(key: SessionKey): ListEventsResponse                             // abstract

  suspend fun closeSession(session: Session) { /* default */ }
  suspend fun appendEvent(session: Session, event: Event): Event { /* default */ }
}
```

**Logic.** Default `appendEvent`:

1.  If `event.partial == true`, return `event` unchanged - no state or
    event-list mutation.
2.  Apply `event.actions.stateDelta` via `session.state.applyDelta` (drops
    `temp:` keys, honors the `REMOVED` sentinel).
3.  Append the event to `session.events`.
4.  Set `session.lastUpdateTime` from `event.timestamp` (event timestamp, not
    wall clock).
5.  Return the same `event`.

An override does its persistence-specific work first, then calls
`super.appendEvent(session, event)` so the caller's in-memory `Session` (its
`events`, `state`, `lastUpdateTime`) mirrors what was persisted. An override
must not also apply the delta to the same `session.state` object, or it would
double-apply.

## InMemorySessionService

In-memory `SessionService` for tests and single-node use. All members are
overrides.

```kotlin
class InMemorySessionService : SessionService {
  override suspend fun createSession(key: SessionKey, state: Map<String, Any>?): Session
  override suspend fun getSession(key: SessionKey, config: GetSessionConfig?): Session?
  override suspend fun listSessions(appName: String, userId: String): ListSessionsResponse
  override suspend fun deleteSession(key: SessionKey)
  override suspend fun listEvents(key: SessionKey): ListEventsResponse
  override suspend fun appendEvent(session: Session, event: Event): Event
}
```

**Logic.** [D-9]

1.  A `Mutex` serializes every public method. Canonical stores: `sessions` (per
    `SessionKey`), `userState` (per app+user, keys held without the `user:`
    prefix), and `appState` (per app, keys held without the `app:` prefix).
2.  `createSession` stores only session-scoped state; global app/user state is
    layered on only in the returned copy (`mergeWithGlobalState`).
    `getSession`/`listSessions` return copies.
3.  `appendEvent` routes each `stateDelta` entry: `app:` -> `appState` (prefix
    stripped), `user:` -> `userState` (prefix stripped), else ->
    `storedSession.state`; `REMOVED` removes, otherwise sets.
4.  It then appends to `storedSession.events`, advances `lastUpdateTime`, and
    calls `super.appendEvent(session, event)` to sync the caller's `Session`.

CORRECTION: the routing loop has no `temp:` case. A `temp:` key matches neither
`app:` nor `user:`, so it falls to the `else` branch and is written into the
stored session's `state`. The canonical in-memory session therefore retains
`temp:` keys; only the caller-side sync via `super` (`State.applyDelta`) drops
them. This differs from `RoomSessionService`, which strips `temp:` before
persisting. The in-memory service also has no concurrent-collision check - it
serializes on its mutex and always appends.

## State

Thread-safe state map that holds current values and tracks pending changes as a
delta. Implements a read-only `Map<String, Any>` view plus explicit mutators.

```kotlin
class State(
  initialState: Map<String, Any> = emptyMap(),
  initialDelta: Map<String, Any> = emptyMap(),
) : Map<String, Any> {

  // Map<String, Any> read-only overrides
  override val entries: Set<Map.Entry<String, Any>>
  override val keys: Set<String>
  override val size: Int
  override val values: Collection<Any>
  override fun isEmpty(): Boolean
  override fun containsKey(key: String): Boolean
  override fun containsValue(value: Any): Boolean
  override operator fun get(key: String): Any?
  override fun toString(): String

  // mutators
  operator fun set(key: String, value: Any): Any?
  fun clear()
  fun putAll(from: Map<out String, Any>)
  fun remove(key: String): Any?
  fun applyDelta(delta: Map<String, Any>)

  val hasDelta: Boolean

  companion object {
    const val APP_PREFIX = "app:"
    const val USER_PREFIX = "user:"
    const val TEMP_PREFIX = "temp:"
    val REMOVED: Any
  }
}
```

**Logic.** [D-15]

1.  Twin backing maps: `state` (current values) and `delta` (pending changes).
    Both are copied from the constructor args, so the caller's maps are never
    mutated.
2.  A `Lock` guards both maps. On JVM/Android it is a reentrant
    `ReentrantReadWriteLock`; reentrancy matters because `applyDelta` holds the
    write lock and calls `set`/`remove`, which re-acquire it.
3.  Reads run under a read lock and return snapshot copies for the collection
    views. The `Map` view is read-only; the only mutation entry points are
    `set`, `putAll`, `remove`, `clear`, and `applyDelta`.
4.  `set` records `delta[key] = value`; `remove` records `delta[key] = REMOVED`
    only if the key was present; `putAll` copies entries verbatim into both maps
    (no `temp:` filter, no `REMOVED` interpretation); `clear` empties both.
5.  `applyDelta`: for each entry, skip keys starting with `temp:`; if the value
    `=== REMOVED` (identity), remove the key; otherwise set it.
6.  `REMOVED` is a private identity sentinel matched by `===`, not `equals`, so
    a different object that stringifies the same is not treated as a tombstone.
    `hasDelta` is `delta.isNotEmpty()`.
7.  Prefixes: `app:` is shared across an app's sessions, `user:` across a user's
    sessions, `temp:` is transient and must not be persisted.

## SessionKey

Composite identifier for a `Session`. `id` may be null on `createSession` to
request generation, but must be non-null for get/delete/listEvents.

```kotlin
data class SessionKey(val appName: String, val userId: String, val id: String?)
```

## GetSessionConfig

Options for reading a session: cap the number of recent events, or filter events
after a timestamp.

```kotlin
data class GetSessionConfig(
  val numRecentEvents: Int? = null,
  val afterTimestamp: Instant? = null,
)
```

## ListEventsResponse

Response for listing a session's events, with an optional page token.

```kotlin
data class ListEventsResponse(
  val events: List<Event> = emptyList(),
  val nextPageToken: String? = null,
)
```

## ListSessionsResponse

Response for listing sessions, with a convenience view of session ids.

```kotlin
data class ListSessionsResponse(val sessions: List<Session> = emptyList()) {
  val sessionIds: List<String>
    get() = sessions.map { it.key.id!! }
}
```

## Lock

Public read/write lock abstraction with an `expect` factory. On JVM/Android the
factory returns a `ReentrantReadWriteLock`-backed implementation.

```kotlin
interface Lock {
  fun <T> read(action: () -> T): T
  fun <T> write(action: () -> T): T
}

expect fun Lock(): Lock
```

## RoomSessionService

Persistent `SessionService` backed by Room/SQLite (androidMain). The primary
constructor is `internal`; construct via the `fromContext` factory.

```kotlin
class RoomSessionService internal constructor(
  private val database: AdkSessionsDatabase,   // internal ctor; not a public entry point
) : SessionService {

  override suspend fun createSession(key: SessionKey, state: Map<String, Any>?): Session
  override suspend fun getSession(key: SessionKey, config: GetSessionConfig?): Session?
  override suspend fun listSessions(appName: String, userId: String): ListSessionsResponse
  override suspend fun deleteSession(key: SessionKey)
  override suspend fun listEvents(key: SessionKey): ListEventsResponse
  override suspend fun appendEvent(session: Session, event: Event): Event

  fun close()

  companion object {
    const val DEFAULT_DATABASE_NAME: String = "adk_sessions.db"
    fun fromContext(context: Context, databaseName: String = DEFAULT_DATABASE_NAME): RoomSessionService
  }
}
```

**Logic.** [D-9]

1.  Backed by Room/SQLite; state survives process death and reboot.
    `createSession` seeds the app-state and user-state rows inside a single
    transaction (insert-if-absent) so a partial failure cannot leave a session
    without its state seeds. Reads run inside a transaction for a consistent
    snapshot.
2.  `appendEvent` skips partial events, then applies `temp:` keys to the caller
    `Session` only, before persisting, so later agents within the same
    invocation (for example a `SequentialAgent` reading a `temp:` output key)
    still see them.
3.  It buckets the persistable delta into app/user/session deltas, stripping the
    `app:`/`user:` prefixes and dropping `temp:` keys, and strips `temp:` from
    the serialized event so a future replay never reintroduces them. The
    caller's `Event` object is left untouched.
4.  It persists atomically via `appendEventAtomic`, passing
    `expectedUpdateTime = session.lastUpdateTime`.
5.  It then calls `super.appendEvent(session, event)` to sync the caller
    `Session`; `State.applyDelta` inside `super` ignores the already-applied
    `temp:` keys and applies the rest.
6.  `appendEventAtomic` runs in one transaction with a stale-write check by
    exact equality in both directions: a stored time greater than expected means
    another writer raced, a stored time less than expected means the caller's
    in-memory session is ahead of storage (fabricated timestamp, backup restore,
    or non-monotonic `event.timestamp`); either mismatch throws
    `IllegalStateException`. It then read-merge-writes each non-empty bucket,
    calls `touchSession` if the session delta is empty, and inserts the event
    row.

--------------------------------------------------------------------------------
