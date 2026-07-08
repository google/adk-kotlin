# Artifacts

> Internal. Part of the ADK Kotlin specification: the repo-root `SPEC.md` is the
> charter and index, and `DECISIONS.md` holds the `[D-n]` design decisions. Not
> exported to GitHub.

*Package `com.google.adk.kt.artifacts`.*

An artifact is a named, versioned binary blob attached to a session.
`ArtifactService` is the contract; versions are 0-based integers.

## ArtifactService

Storage contract for versioned artifacts, addressed by `SessionKey` + filename.

```kotlin
interface ArtifactService {
  suspend fun saveArtifact(sessionKey: SessionKey, filename: String, artifact: Part): Int
  suspend fun saveAndReloadArtifact(sessionKey: SessionKey, filename: String, artifact: Part): Part
  suspend fun loadArtifact(sessionKey: SessionKey, filename: String, version: Int? = null): Part?
  suspend fun listArtifactKeys(sessionKey: SessionKey): List<String>
  suspend fun deleteArtifact(sessionKey: SessionKey, filename: String)
  suspend fun listVersions(sessionKey: SessionKey, filename: String): List<Int>
}
```

## InMemoryArtifactService

In-memory `ArtifactService` for tests and single-node use.

```kotlin
class InMemoryArtifactService() : ArtifactService {
  override suspend fun saveArtifact(sessionKey: SessionKey, filename: String, artifact: Part): Int
  override suspend fun loadArtifact(sessionKey: SessionKey, filename: String, version: Int?): Part?
  override suspend fun listArtifactKeys(sessionKey: SessionKey): List<String>
  override suspend fun deleteArtifact(sessionKey: SessionKey, filename: String)
  override suspend fun saveAndReloadArtifact(sessionKey: SessionKey, filename: String, artifact: Part): Part
  override suspend fun listVersions(sessionKey: SessionKey, filename: String): List<Int>
}
```

**Logic.**

1.  Store: `SessionKey` -> filename -> `MutableList<Part>`, guarded by a
    `Mutex`. A version is the index into the per-filename list.
2.  `saveArtifact` appends and returns `size - 1`, so versions are 0-based,
    increase monotonically by 1 per save, follow insertion order, and have no
    gaps.
3.  `loadArtifact` returns null for an unknown filename; a null `version`
    returns the latest, otherwise the value is bounds-checked in `0 until size`.
4.  `listVersions` returns `0..size-1`; `deleteArtifact` removes the whole
    filename entry (all versions); `saveAndReloadArtifact` saves and returns the
    same `Part`.

CORRECTION: `InMemoryArtifactService` does NOT do `user:` scoping. It keys
purely by `SessionKey` + filename and never inspects the filename for a `user:`
prefix, so two sessions with different `SessionKey`s do not share a
`user:`-prefixed artifact. `user:` scoping exists only in `GcsArtifactService`,
via a blob prefix. The in-memory service also has no concurrent-collision check:
it serializes on its mutex and always appends, so it cannot collide.

## GcsArtifactService

`ArtifactService` backed by Google Cloud Storage (jvmMain). Constructor params
are `private val`, not exposed as public properties.

```kotlin
class GcsArtifactService(
  private val bucketName: String,
  private val storageClient: Storage,   // com.google.cloud.storage.Storage
) : ArtifactService {
  override suspend fun saveArtifact(sessionKey: SessionKey, filename: String, artifact: Part): Int
  override suspend fun saveAndReloadArtifact(sessionKey: SessionKey, filename: String, artifact: Part): Part
  override suspend fun loadArtifact(sessionKey: SessionKey, filename: String, version: Int?): Part?
  override suspend fun listArtifactKeys(sessionKey: SessionKey): List<String>
  override suspend fun deleteArtifact(sessionKey: SessionKey, filename: String)
  override suspend fun listVersions(sessionKey: SessionKey, filename: String): List<Int>
}
```

**Logic.**

1.  Blob naming: a `user:`-prefixed filename uses
    `{appName}/{userId}/user/{filename}/`, dropping the `sessionId` segment so
    the artifact is shared across the user's sessions; any other filename uses
    `{appName}/{userId}/{sessionId}/{filename}/`. The integer version is
    appended to the blob name. This is where real `user:` scoping lives.
2.  `nextVersion = (max existing version ?: -1) + 1`, 0-based like the in-memory
    service, computed from what currently exists in GCS.
3.  Concurrent-collision: the create call passes `doesNotExist()`. If two
    writers compute the same `nextVersion`, the second create fails with GCS
    HTTP 412, mapped to `IllegalStateException("Concurrent write collision
    detected for artifact <filename>")`; any other `StorageException` becomes
    `IllegalStateException("Failed to save artifact to GCS")`. So GCS gives an
    optimistic-concurrency guarantee: a racing save throws rather than silently
    overwriting a version.

## FileArtifactService

File-backed `ArtifactService` for JVM/Android. The constructor param is a
`private val`. It publishes new versions atomically and guards against path
traversal in filenames. Android factory extensions build one from a context's
external or internal files directory.

```kotlin
class FileArtifactService(private val baseDir: String) : ArtifactService {
  override suspend fun saveArtifact(sessionKey: SessionKey, filename: String, artifact: Part): Int
  override suspend fun saveAndReloadArtifact(sessionKey: SessionKey, filename: String, artifact: Part): Part
  override suspend fun loadArtifact(sessionKey: SessionKey, filename: String, version: Int?): Part?
  override suspend fun listArtifactKeys(sessionKey: SessionKey): List<String>
  override suspend fun deleteArtifact(sessionKey: SessionKey, filename: String)
  override suspend fun listVersions(sessionKey: SessionKey, filename: String): List<Int>

  companion object {
    const val DEFAULT_ARTIFACTS_SUBDIR: String = "adk/artifacts"
  }
}

// Android factory extensions on FileArtifactService.Companion:
fun FileArtifactService.Companion.fromExternalFilesDir(
  context: Context,
  subDir: String = FileArtifactService.DEFAULT_ARTIFACTS_SUBDIR,
): FileArtifactService   // throws IllegalStateException if external storage unavailable

fun FileArtifactService.Companion.fromInternalFilesDir(
  context: Context,
  subDir: String = FileArtifactService.DEFAULT_ARTIFACTS_SUBDIR,
): FileArtifactService
```

## Internal (not public API)

-   `ForwardingArtifactService.kt` - `internal class
    ForwardingArtifactService(...) : ArtifactService`.
-   `FileArtifactService.kt` - `internal data class FileArtifactMetadata(...)`.

--------------------------------------------------------------------------------
