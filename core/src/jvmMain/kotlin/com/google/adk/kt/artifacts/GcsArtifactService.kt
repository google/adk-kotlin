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

import com.google.adk.kt.logging.LoggerFactory
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.FileData
import com.google.adk.kt.types.Part
import com.google.cloud.storage.Blob as GcsBlob
import com.google.cloud.storage.BlobId
import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.Storage
import com.google.cloud.storage.Storage.BlobListOption
import com.google.cloud.storage.StorageException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * An artifact service implementation using Google Cloud Storage (GCS).
 *
 * The blob name format used depends on whether the filename has a user namespace:
 * - For files with user namespace (starting with "user:"):
 *   `{appName}/{userId}/user/{filename}/{version}`
 * - For regular session-scoped files: `{appName}/{userId}/{sessionId}/{filename}/{version}`
 *
 * @property bucketName The name of the GCS bucket to store artifacts in.
 * @property storageClient The configured Google Cloud Storage client.
 */
class GcsArtifactService(private val bucketName: String, private val storageClient: Storage) :
  ArtifactService {

  private val logger = LoggerFactory.getLogger(GcsArtifactService::class)

  /** Checks if the filename has a user namespace (starts with "user:"). */
  private fun fileHasUserNamespace(filename: String?): Boolean =
    filename?.startsWith("user:") == true

  /** Constructs the blob name prefix in GCS for a given artifact. */
  private fun getBlobPrefix(sessionKey: SessionKey, filename: String): String {
    val sessionId =
      requireNotNull(sessionKey.id) { "SessionKey.id is required for GCS artifact operations." }
    return if (fileHasUserNamespace(filename)) {
      "${sessionKey.appName}/${sessionKey.userId}/user/$filename/"
    } else {
      "${sessionKey.appName}/${sessionKey.userId}/$sessionId/$filename/"
    }
  }

  /** Constructs the full blob name in GCS including the version. */
  private fun getBlobName(sessionKey: SessionKey, filename: String, version: Int): String =
    "${getBlobPrefix(sessionKey, filename)}$version"

  override suspend fun saveArtifact(sessionKey: SessionKey, filename: String, artifact: Part): Int =
    withContext(Dispatchers.IO) {
      saveArtifactAndReturnBlob(sessionKey, filename, artifact).version
    }

  override suspend fun saveAndReloadArtifact(
    sessionKey: SessionKey,
    filename: String,
    artifact: Part,
  ): Part =
    withContext(Dispatchers.IO) {
      val savedBlob = saveArtifactAndReturnBlob(sessionKey, filename, artifact).blob
      Part(
        fileData =
          FileData(
            fileUri = "gs://${savedBlob.bucket}/${savedBlob.name}",
            mimeType =
              savedBlob.contentType ?: artifact.inlineData?.mimeType ?: "application/octet-stream",
          )
      )
    }

  private class SaveResult(val blob: GcsBlob, val version: Int)

  private fun saveArtifactAndReturnBlob(
    sessionKey: SessionKey,
    filename: String,
    artifact: Part,
  ): SaveResult {
    requireNotNull(artifact.inlineData) { "Saveable artifact must have inline data." }
    requireNotNull(artifact.inlineData.data) { "Saveable artifact data must be non-empty." }

    val nextVersion = (listVersionsSync(sessionKey, filename).maxOrNull() ?: -1) + 1

    val blobId = BlobId.of(bucketName, getBlobName(sessionKey, filename, nextVersion))
    val blobInfo = BlobInfo.newBuilder(blobId).setContentType(artifact.inlineData.mimeType).build()

    try {
      return SaveResult(
        storageClient.create(
          blobInfo,
          artifact.inlineData.data,
          // doesNotExist() ensures the write fails (throwing HTTP 412) if the version was created
          // concurrently.
          Storage.BlobTargetOption.doesNotExist(),
        ),
        nextVersion,
      )
    } catch (e: StorageException) {
      if (e.code == 412) {
        throw IllegalStateException("Concurrent write collision detected for artifact $filename", e)
      }
      throw IllegalStateException("Failed to save artifact to GCS", e)
    }
  }

  override suspend fun loadArtifact(
    sessionKey: SessionKey,
    filename: String,
    version: Int?,
  ): Part? =
    withContext(Dispatchers.IO) {
      val versionToLoad =
        version ?: listVersionsSync(sessionKey, filename).maxOrNull() ?: return@withContext null

      try {
        val blob =
          storageClient.get(BlobId.of(bucketName, getBlobName(sessionKey, filename, versionToLoad)))
        if (blob?.exists() != true) return@withContext null
        Part(inlineData = Blob(data = blob.getContent(), mimeType = blob.contentType))
      } catch (e: StorageException) {
        // Log only the exception type, not the throwable or filename: GCS messages embed the blob
        // path (and thus userId/sessionId/filename), which must not reach the logs.
        logger.warn {
          "Failed to load artifact version $versionToLoad from GCS (${e::class.simpleName})"
        }
        null
      }
    }

  override suspend fun listArtifactKeys(sessionKey: SessionKey): List<String> =
    withContext(Dispatchers.IO) {
      val sessionId =
        requireNotNull(sessionKey.id) { "SessionKey.id is required for GCS artifact operations." }
      // Extracts the filename from GCS blob names.
      // - Regular: {appName}/{userId}/{sessionId}/{filename}/{version}
      // - User:    {appName}/{userId}/user/{filename}/{version}
      // Index 3 is always the filename in both formats.
      fun getFilenames(prefix: String, errorMessage: String): Set<String> =
        try {
          storageClient
            .list(bucketName, BlobListOption.prefix(prefix))
            .iterateAll()
            .asSequence()
            .map { it.name.split("/") }
            .filter { it.size >= 4 }
            .map { it[3] }
            .toSet()
        } catch (e: StorageException) {
          throw IllegalStateException(errorMessage, e)
        }

      val sessionFiles =
        getFilenames(
          "${sessionKey.appName}/${sessionKey.userId}/$sessionId/",
          "Failed to list session artifacts from GCS",
        )
      val userFiles =
        getFilenames(
          "${sessionKey.appName}/${sessionKey.userId}/user/",
          "Failed to list user artifacts from GCS",
        )

      (sessionFiles + userFiles).sorted()
    }

  override suspend fun deleteArtifact(sessionKey: SessionKey, filename: String): Unit =
    withContext(Dispatchers.IO) {
      val versions = listVersionsSync(sessionKey, filename)
      if (versions.isEmpty()) {
        // The filename is user-derived and must not reach the logs.
        logger.warn { "Attempted to delete an artifact that does not exist in GCS" }
        return@withContext
      }

      try {
        val unused =
          storageClient.delete(
            versions.map { BlobId.of(bucketName, getBlobName(sessionKey, filename, it)) }
          )
      } catch (e: StorageException) {
        throw IllegalStateException("Failed to delete artifact versions from GCS", e)
      }
    }

  override suspend fun listVersions(sessionKey: SessionKey, filename: String): List<Int> =
    withContext(Dispatchers.IO) { listVersionsSync(sessionKey, filename) }

  private fun listVersionsSync(sessionKey: SessionKey, filename: String): List<Int> {
    val prefix = getBlobPrefix(sessionKey, filename)
    return try {
      storageClient
        .list(bucketName, BlobListOption.prefix(prefix))
        .iterateAll()
        .mapNotNull { blob ->
          val name = blob.name
          val versionDelimiterIndex = name.lastIndexOf('/')
          if (versionDelimiterIndex != -1 && versionDelimiterIndex < name.length - 1) {
            name.substring(versionDelimiterIndex + 1).toIntOrNull()
          } else {
            null
          }
        }
        .sorted()
    } catch (e: StorageException) {
      emptyList()
    }
  }
}
