/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.adk.kt.artifacts

import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.logging.LoggerFactory
import com.google.adk.kt.serialization.adkJson
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.FileData
import com.google.adk.kt.types.Part
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

/**
 * An [ArtifactService] that persists artifacts on the local filesystem.
 *
 * The on-disk layout mirrors the other ADK file-backed artifact services so the same directory can
 * be opened interchangeably across language ports. Each artifact version is a directory holding the
 * raw payload (named after the artifact's basename) plus a `metadata.json` sidecar:
 * ```
 * <baseDir>/users/<userId>/sessions/<sessionId>/artifacts/<artifactPath>/versions/<v>/<basename>
 * <baseDir>/users/<userId>/sessions/<sessionId>/artifacts/<artifactPath>/versions/<v>/metadata.json
 * <baseDir>/users/<userId>/artifacts/<artifactPath>/versions/<v>/<basename>            # user-scoped
 * ```
 *
 * An artifact is user-scoped when the session id is `null` or the filename starts with `user:` (the
 * prefix is stripped from the path and preserved in metadata). `<artifactPath>` is derived from the
 * filename, so nested names such as `images/photo.png` create nested directories; absolute paths
 * and names that traverse outside the storage scope (e.g. `../secret`) are rejected.
 *
 * Versions are 0-based and increase monotonically. A version is published atomically by staging its
 * payload and metadata in a temporary directory and renaming it into place, so concurrent readers
 * only ever observe complete versions (payload and metadata together). Writes to the same artifact
 * are additionally serialized by an in-process per-artifact lock; this does not coordinate across
 * separate OS processes.
 *
 * This impl lives in `commonJvmAndroidMain` because it only needs [java.io.File], so the same code
 * serves both Android and the JVM. On Android, prefer the `fromContext` factory (in the
 * `androidMain` source set) which roots [baseDir] at the app-specific external files directory.
 *
 * @property baseDir path to the root directory under which all artifacts are stored.
 */
@OptIn(FrameworkInternalApi::class)
class FileArtifactService(private val baseDir: String) : ArtifactService {

  private val logger = LoggerFactory.getLogger(FileArtifactService::class)

  /**
   * Per-artifact write locks, keyed by the resolved artifact directory path. Entries are never
   * evicted; the map is bounded by the number of distinct artifacts a process touches, which is
   * small in practice. [ConcurrentHashMap.computeIfAbsent] guarantees a single [Mutex] instance per
   * key even under concurrent first access.
   */
  private val locks = ConcurrentHashMap<String, Mutex>()

  private fun lockFor(artifactDir: File): Mutex =
    locks.computeIfAbsent(artifactDir.path) { Mutex() }

  @Suppress("GlobalCoroutineDispatchers") // Blocking java.io must run off the caller's thread.
  override suspend fun saveArtifact(sessionKey: SessionKey, filename: String, artifact: Part): Int =
    withContext(Dispatchers.IO) {
      val inlineData =
        requireNotNull(artifact.inlineData) { "Saveable artifact must have inline data." }
      val data = requireNotNull(inlineData.data) { "Saveable artifact data must be non-empty." }
      require(data.isNotEmpty()) { "Saveable artifact data must be non-empty." }

      val artifactDir = resolveArtifactDir(sessionKey, filename)
      lockFor(artifactDir).withLock {
        val versionsDir = versionsDir(artifactDir)
        val nextVersion = (listVersionsOnDisk(artifactDir).lastOrNull() ?: -1) + 1
        val basename = artifactDir.name
        val finalVersionDir = File(versionsDir, nextVersion.toString())
        val canonicalUri = File(finalVersionDir, basename).toURI().toString()

        // Stage payload + metadata in a temp dir, then publish with a single atomic move so a
        // reader never sees a version missing either file.
        versionsDir.mkdirs()
        val staging = File(versionsDir, ".staging-$nextVersion-${System.nanoTime()}")
        staging.mkdirs()
        try {
          File(staging, basename).writeBytes(data)
          val metadata =
            FileArtifactMetadata(
              version = nextVersion,
              fileName = filename,
              canonicalUri = canonicalUri,
              createTime = System.currentTimeMillis() / 1000.0,
              mimeType = inlineData.mimeType,
              displayName = inlineData.displayName,
            )
          File(staging, METADATA_FILE_NAME)
            .writeText(adkJson.encodeToString(FileArtifactMetadata.serializer(), metadata))

          Files.move(staging.toPath(), finalVersionDir.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally {
          // No-op on success (already moved away); cleans up a half-written stage on failure.
          if (staging.exists()) staging.deleteRecursively()
        }

        logger.debug { "Saved artifact version $nextVersion." }
        nextVersion
      }
    }

  @Suppress("GlobalCoroutineDispatchers") // File.toURI() stats the filesystem; run off-thread.
  override suspend fun saveAndReloadArtifact(
    sessionKey: SessionKey,
    filename: String,
    artifact: Part,
  ): Part {
    val version = saveArtifact(sessionKey, filename, artifact)
    // File.toURI() calls isDirectory(), a blocking stat, so resolve it on the IO dispatcher.
    val uri =
      withContext(Dispatchers.IO) {
        val artifactDir = resolveArtifactDir(sessionKey, filename)
        File(File(versionsDir(artifactDir), version.toString()), artifactDir.name)
          .toURI()
          .toString()
      }
    return Part(
      fileData =
        FileData(fileUri = uri, mimeType = artifact.inlineData?.mimeType ?: DEFAULT_MIME_TYPE)
    )
  }

  @Suppress("GlobalCoroutineDispatchers") // Blocking java.io must run off the caller's thread.
  override suspend fun loadArtifact(
    sessionKey: SessionKey,
    filename: String,
    version: Int?,
  ): Part? =
    withContext(Dispatchers.IO) {
      val artifactDir = resolveArtifactDir(sessionKey, filename)
      val versions = listVersionsOnDisk(artifactDir)
      if (versions.isEmpty()) return@withContext null

      val versionToLoad =
        when (version) {
          null -> versions.last()
          else -> if (version in versions) version else return@withContext null
        }

      val versionDir = File(versionsDir(artifactDir), versionToLoad.toString())
      val payload = File(versionDir, artifactDir.name)
      val metadata = readMetadata(File(versionDir, METADATA_FILE_NAME))
      try {
        if (!payload.exists()) {
          logger.warn { "Artifact payload missing for version $versionToLoad." }
          return@withContext null
        }
        Part(
          inlineData =
            Blob(
              data = payload.readBytes(),
              mimeType = metadata?.mimeType,
              displayName = metadata?.displayName,
            )
        )
      } catch (e: IOException) {
        // The payload may be deleted or become unreadable between the existence check and the read
        // (e.g. a concurrent deleteArtifact). Treat that as a cache miss rather than propagating.
        // Log only the exception type, not the throwable: java.io messages embed the absolute path
        // (and thus userId/filename), which must not reach the logs.
        logger.warn {
          "Failed to read artifact payload for version $versionToLoad (${e::class.simpleName})."
        }
        null
      }
    }

  @Suppress("GlobalCoroutineDispatchers") // Blocking java.io must run off the caller's thread.
  override suspend fun listArtifactKeys(sessionKey: SessionKey): List<String> =
    withContext(Dispatchers.IO) {
      val base = baseRoot(sessionKey.userId)
      val keys = mutableSetOf<String>()

      val sessionId = sessionKey.id
      if (sessionId != null) {
        val sessionRoot = sessionArtifactsDir(base, sessionId)
        for (dir in iterArtifactDirs(sessionRoot)) {
          keys.add(latestMetadata(dir)?.fileName ?: relativePosix(sessionRoot, dir))
        }
      }

      val userRoot = userArtifactsDir(base)
      for (dir in iterArtifactDirs(userRoot)) {
        keys.add(
          latestMetadata(dir)?.fileName ?: "$USER_NAMESPACE_PREFIX${relativePosix(userRoot, dir)}"
        )
      }

      keys.sorted()
    }

  @Suppress("GlobalCoroutineDispatchers") // Blocking java.io must run off the caller's thread.
  override suspend fun deleteArtifact(sessionKey: SessionKey, filename: String): Unit =
    withContext(Dispatchers.IO) {
      val artifactDir = resolveArtifactDir(sessionKey, filename)
      lockFor(artifactDir).withLock {
        if (artifactDir.exists()) {
          val deleted = artifactDir.deleteRecursively()
          logger.debug { "Deleted artifact (success=$deleted)." }
        } else {
          logger.debug { "Delete requested for missing artifact; nothing to do." }
        }
      }
    }

  @Suppress("GlobalCoroutineDispatchers") // Blocking java.io must run off the caller's thread.
  override suspend fun listVersions(sessionKey: SessionKey, filename: String): List<Int> =
    withContext(Dispatchers.IO) { listVersionsOnDisk(resolveArtifactDir(sessionKey, filename)) }

  // ---------------------------------------------------------------------------------------------
  // Path resolution
  // ---------------------------------------------------------------------------------------------

  /** Returns the per-user artifacts root, validating the user id as a single path segment. */
  private fun baseRoot(userId: String): File {
    validatePathSegment(userId, "userId")
    return File(baseDir, "users/$userId")
  }

  private fun userArtifactsDir(base: File): File = File(base, "artifacts")

  private fun sessionArtifactsDir(base: File, sessionId: String): File {
    validatePathSegment(sessionId, "sessionId")
    return File(base, "sessions/$sessionId/artifacts")
  }

  /** An artifact is user-scoped when there is no session or the filename opts in via `user:`. */
  private fun isUserScoped(sessionId: String?, filename: String): Boolean =
    sessionId == null || filename.startsWith(USER_NAMESPACE_PREFIX)

  /** Resolves the directory that holds all versions of a given artifact. */
  private fun resolveArtifactDir(sessionKey: SessionKey, filename: String): File {
    val base = baseRoot(sessionKey.userId)
    val scopeRoot =
      if (isUserScoped(sessionKey.id, filename)) {
        userArtifactsDir(base)
      } else {
        sessionArtifactsDir(base, requireNotNull(sessionKey.id))
      }
    return resolveScopedArtifactDir(scopeRoot, filename)
  }

  /**
   * Joins [filename] under [scopeRoot], rejecting absolute paths or paths that escape the scope.
   * Empty names resolve to a stable `artifact` directory, matching the other ADK ports.
   */
  private fun resolveScopedArtifactDir(scopeRoot: File, filename: String): File {
    // Normalize Windows separators so the same name resolves identically on every platform.
    val stripped = stripUserNamespace(filename).trim().replace('\\', '/')
    val relative = Paths.get(stripped).normalize()
    require(!relative.isAbsolute && !relative.startsWith(Paths.get(".."))) {
      "Artifact filename '$filename' must be relative to the storage scope and must not traverse " +
        "outside it."
    }
    val relativeString = relative.toString()
    return if (relativeString.isEmpty()) {
      File(scopeRoot, "artifact")
    } else {
      File(scopeRoot, relativeString)
    }
  }

  /** Rejects identifiers that could alter the constructed filesystem path. */
  private fun validatePathSegment(value: String, fieldName: String) {
    require(value.isNotEmpty()) { "$fieldName must not be empty." }
    require(!value.contains('\u0000')) { "$fieldName must not contain null bytes." }
    require(!value.contains('/') && !value.contains('\\')) {
      "$fieldName '$value' must not contain path separators."
    }
    require(value != "." && value != "..") {
      "$fieldName '$value' must not be a traversal segment."
    }
  }

  private fun stripUserNamespace(filename: String): String =
    if (filename.startsWith(USER_NAMESPACE_PREFIX)) {
      filename.substring(USER_NAMESPACE_PREFIX.length)
    } else {
      filename
    }

  // ---------------------------------------------------------------------------------------------
  // On-disk helpers
  // ---------------------------------------------------------------------------------------------

  private fun versionsDir(artifactDir: File): File = File(artifactDir, "versions")

  /** Lists the published version numbers under an artifact, sorted ascending. */
  private fun listVersionsOnDisk(artifactDir: File): List<Int> =
    versionsDir(artifactDir)
      .listFiles()
      ?.filter { it.isDirectory }
      ?.mapNotNull { it.name.toIntOrNull() }
      ?.sorted() ?: emptyList()

  /** Walks [root], returning every directory that contains a `versions` subdirectory. */
  private fun iterArtifactDirs(root: File): List<File> {
    if (!root.exists()) return emptyList()
    val result = mutableListOf<File>()
    fun walk(dir: File) {
      if (File(dir, "versions").exists()) {
        result.add(dir)
        return // An artifact directory is a leaf; do not descend into its versions.
      }
      dir.listFiles()?.forEach { if (it.isDirectory) walk(it) }
    }
    walk(root)
    return result
  }

  private fun latestMetadata(artifactDir: File): FileArtifactMetadata? {
    val latest = listVersionsOnDisk(artifactDir).lastOrNull() ?: return null
    return readMetadata(File(File(versionsDir(artifactDir), latest.toString()), METADATA_FILE_NAME))
  }

  private fun readMetadata(file: File): FileArtifactMetadata? {
    if (!file.exists()) return null
    return try {
      adkJson.decodeFromString(FileArtifactMetadata.serializer(), file.readText())
    } catch (e: SerializationException) {
      // Log only the exception type: the throwable can echo metadata.json content (e.g. fileName).
      logger.warn { "Failed to parse artifact metadata (${e::class.simpleName})." }
      null
    } catch (e: IOException) {
      // Log only the exception type: java.io messages embed the absolute path.
      logger.warn { "Failed to read artifact metadata (${e::class.simpleName})." }
      null
    }
  }

  /** Returns [dir]'s path relative to [root] using POSIX separators. */
  private fun relativePosix(root: File, dir: File): String =
    root.toPath().relativize(dir.toPath()).joinToString("/")

  companion object {
    /** Default sub-directory of the Android storage root under which artifacts are stored. */
    const val DEFAULT_ARTIFACTS_SUBDIR: String = "adk/artifacts"

    private const val USER_NAMESPACE_PREFIX: String = "user:"
    private const val METADATA_FILE_NAME: String = "metadata.json"
    private const val DEFAULT_MIME_TYPE: String = "application/octet-stream"
  }
}

/**
 * On-disk metadata sidecar for a single artifact version.
 *
 * The field names are the JSON keys and intentionally match the camelCase schema written by the
 * other ADK file-backed artifact services, so versions written by one port are readable by another.
 * Unknown keys (such as `customMetadata`, which this port does not surface) are ignored on read via
 * [adkJson]; null optional fields are omitted on write.
 */
@Serializable
internal data class FileArtifactMetadata(
  val version: Int,
  val fileName: String,
  val canonicalUri: String,
  val createTime: Double,
  val mimeType: String? = null,
  val displayName: String? = null,
)
