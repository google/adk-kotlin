# Memory

> Internal. Part of the ADK Kotlin specification: the repo-root `SPEC.md` is the
> charter and index, and `DECISIONS.md` holds the `[D-n]` design decisions. Not
> exported to GitHub.

*Package `com.google.adk.kt.memory`.*

Memory is long-term recall across sessions: sessions or events are ingested,
then searched by query. `MemoryService` is the contract.

## MemoryService

Contract for ingesting session history into long-term memory and searching it.

```kotlin
interface MemoryService {
  suspend fun addSessionToMemory(session: Session)

  suspend fun addEventsToMemory(
    appName: String,
    userId: String,
    events: List<Event>,
    sessionId: String? = null,
    customMetadata: Map<String, Any?>? = null,
  ) { /* default */ }

  suspend fun addMemory(
    appName: String,
    userId: String,
    memories: List<MemoryEntry>,
    customMetadata: Map<String, Any?>? = null,
  ) { /* default */ }

  suspend fun searchMemory(appName: String, userId: String, query: String): SearchMemoryResponse
}
```

**Logic.** [D-9]

1.  `addSessionToMemory` and `searchMemory` are abstract; every implementation
    must define them.
2.  `addEventsToMemory` and `addMemory` have default bodies that throw
    `UnsupportedOperationException`, so finer-grained ingestion is opt-in per
    implementation.

## InMemoryMemoryService

In-memory `MemoryService` with keyword-only search. For prototyping only.

```kotlin
class InMemoryMemoryService() : MemoryService {
  override suspend fun addSessionToMemory(session: Session)
  override suspend fun addEventsToMemory(
    appName: String, userId: String, events: List<Event>,
    sessionId: String?, customMetadata: Map<String, Any?>?,
  )
  override suspend fun addMemory(
    appName: String, userId: String, memories: List<MemoryEntry>,
    customMetadata: Map<String, Any?>?,
  )
  override suspend fun searchMemory(appName: String, userId: String, query: String): SearchMemoryResponse
}
```

It overrides all four methods, including the two that throw by default. Search
is keyword-based only and is not suitable for production recall.

## MemoryEntry

One stored memory: content plus optional id, author, timestamp, and metadata.

```kotlin
data class MemoryEntry(
  val content: Content,
  val id: String? = null,
  val author: String? = null,
  val timestamp: String? = null,
  val customMetadata: Map<String, Any> = emptyMap(),
)
```

## SearchMemoryResponse

Result of a memory search: the matched entries and an optional page token.

```kotlin
data class SearchMemoryResponse(
  val memories: List<MemoryEntry>,
  val nextPageToken: String? = null,
)
```

--------------------------------------------------------------------------------
