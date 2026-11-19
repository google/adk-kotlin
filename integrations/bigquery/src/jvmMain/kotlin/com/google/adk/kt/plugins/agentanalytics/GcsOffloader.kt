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

package com.google.adk.kt.plugins.agentanalytics

import com.google.api.gax.rpc.FixedHeaderProvider
import com.google.auth.Credentials
import com.google.cloud.http.HttpTransportOptions
import com.google.cloud.storage.BlobId
import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.Storage
import com.google.cloud.storage.StorageOptions
import java.util.Locale
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.runInterruptible

/** Stores content that is too large or too binary to log inline, so that a row can reference it. */
internal interface ContentOffloader {
  /**
   * Uploads [data] as a new object at [path] and returns its URI; an object that already exists
   * there fails the upload rather than being replaced.
   */
  @Suppress("KotlinArray")
  suspend fun uploadContent(data: ByteArray, contentType: String, path: String): String
}

/**
 * Uploads content to [bucketName] in [ioContext], with a client that [openStorage] opens on the
 * first upload.
 */
internal class GcsOffloader(
  private val bucketName: String,
  private val ioContext: CoroutineContext,
  private val openStorage: () -> Storage,
) : ContentOffloader {
  private val storage: Storage by lazy(openStorage)

  @Suppress("KotlinArray")
  override suspend fun uploadContent(data: ByteArray, contentType: String, path: String): String =
    runInterruptible(ioContext) {
      val blob =
        BlobInfo.newBuilder(BlobId.of(bucketName, path)).setContentType(contentType).build()
      // Object names are unique per part, so an existing object is a collision to fail on.
      val unused = storage.create(blob, data, Storage.BlobTargetOption.doesNotExist())
      "gs://$bucketName/$path"
    }

  companion object {
    private const val DEFAULT_EXTENSION = ".bin"
    private const val MAX_ATTEMPTS = 1
    private const val SOCKET_TIMEOUT_MILLIS = 10_000

    private val EXTENSIONS =
      mapOf(
        "image/jpeg" to ".jpg",
        "image/png" to ".png",
        "image/gif" to ".gif",
        "image/webp" to ".webp",
        "image/bmp" to ".bmp",
        "image/tiff" to ".tiff",
        "image/svg+xml" to ".svg",
        "audio/mpeg" to ".mp3",
        "audio/ogg" to ".ogg",
        "audio/wav" to ".wav",
        "audio/x-wav" to ".wav",
        "audio/webm" to ".webm",
        "audio/aac" to ".aac",
        "audio/midi" to ".mid",
        "audio/mp4" to ".m4a",
        "audio/x-m4a" to ".m4a",
        "audio/flac" to ".flac",
        "video/mp4" to ".mp4",
        "video/mpeg" to ".mpeg",
        "video/ogg" to ".ogv",
        "video/webm" to ".webm",
        "video/avi" to ".avi",
        "video/x-msvideo" to ".avi",
        "video/quicktime" to ".mov",
        "application/pdf" to ".pdf",
        "text/plain" to ".txt",
        "text/csv" to ".csv",
        "text/html" to ".html",
        "application/json" to ".json",
        "application/xml" to ".xml",
        "text/xml" to ".xml",
      )

    /**
     * Returns an offloader to [config]'s bucket whose client is created on the first upload, with
     * [credentials] or, if they are null, Application Default Credentials.
     */
    fun create(
      config: BigQueryLoggerConfig,
      credentials: Credentials?,
      ioContext: CoroutineContext,
    ): GcsOffloader {
      val bucketName = requireNotNull(config.gcsBucketName) { "gcsBucketName is not set." }
      return GcsOffloader(bucketName, ioContext) { storageOptions(config, credentials).service }
    }

    /**
     * Returns the client options for [config]'s project, with [credentials] or, if they are null,
     * Application Default Credentials. Visible for tests.
     */
    fun storageOptions(config: BigQueryLoggerConfig, credentials: Credentials?): StorageOptions {
      val builder =
        StorageOptions.newBuilder()
          .setProjectId(config.projectId)
          .setHeaderProvider(
            FixedHeaderProvider.create(mapOf("user-agent" to BigQueryUtils.getVersionHeaderValue()))
          )
          // Bounds connect and read only; HTTP writes have no timeout and ignore interrupts.
          .setTransportOptions(
            HttpTransportOptions.newBuilder()
              .setConnectTimeout(SOCKET_TIMEOUT_MILLIS)
              .setReadTimeout(SOCKET_TIMEOUT_MILLIS)
              .build()
          )
          // One attempt, so a failed upload falls back inline without retrying.
          .setRetrySettings(
            StorageOptions.getDefaultRetrySettings()
              .toBuilder()
              .setMaxAttempts(MAX_ATTEMPTS)
              .build()
          )
      if (credentials != null) builder.setCredentials(credentials)
      return builder.build()
    }

    /** Returns the file extension for [mimeType], whose parameters are ignored, or `.bin`. */
    fun extensionForMimeType(mimeType: String): String =
      EXTENSIONS[mimeType.substringBefore(';').trim().lowercase(Locale.ROOT)] ?: DEFAULT_EXTENSION
  }
}
