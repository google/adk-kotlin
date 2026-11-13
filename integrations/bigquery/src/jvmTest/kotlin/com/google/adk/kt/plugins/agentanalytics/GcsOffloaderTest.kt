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

import com.google.auth.Credentials
import com.google.cloud.storage.BlobId
import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.Storage
import com.google.cloud.storage.StorageException
import java.io.IOException
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.stub
import org.mockito.kotlin.times
import org.mockito.kotlin.verify

class GcsOffloaderTest {

  private val storage = mock<Storage>()
  private val opened = AtomicInteger()
  private val offloader =
    GcsOffloader(BUCKET, Dispatchers.Default) {
      opened.incrementAndGet()
      storage
    }

  @Test
  fun uploadContent_createsTheObjectOnlyIfItDoesNotExist(): Unit = runBlocking {
    val data = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
    val path = "2026-11-13/trace/span_uid_c0_p0.png"

    val uri = offloader.uploadContent(data, "image/png", path)

    assertEquals("gs://$BUCKET/$path", uri)
    val blobInfo = argumentCaptor<BlobInfo>()
    val bytes = argumentCaptor<ByteArray>()
    val options = argumentCaptor<Storage.BlobTargetOption>()
    verify(storage).create(blobInfo.capture(), bytes.capture(), options.capture())
    assertEquals(BlobId.of(BUCKET, path), blobInfo.firstValue.blobId)
    assertEquals("image/png", blobInfo.firstValue.contentType)
    assertContentEquals(data, bytes.firstValue)
    assertEquals(listOf(Storage.BlobTargetOption.doesNotExist()), options.allValues)
  }

  @Test
  @Suppress("CheckReturnValue")
  fun uploadContent_text_uploadsItsUtf8Bytes(): Unit = runBlocking {
    val text = "Grüße, 世界"

    offloader.uploadContent(text.toByteArray(Charsets.UTF_8), "text/plain", "t/s_uid_c0_p0.txt")

    val blobInfo = argumentCaptor<BlobInfo>()
    val bytes = argumentCaptor<ByteArray>()
    verify(storage).create(blobInfo.capture(), bytes.capture(), any<Storage.BlobTargetOption>())
    assertEquals("text/plain", blobInfo.firstValue.contentType)
    assertEquals(text, bytes.firstValue.toString(Charsets.UTF_8))
  }

  @Test
  fun uploadContent_objectThatExists_failsWithTheStorageException(): Unit = runBlocking {
    storage.stub {
      on { create(any<BlobInfo>(), any<ByteArray>(), any<Storage.BlobTargetOption>()) } doThrow
        StorageException(412, "Precondition Failed")
    }

    val failure =
      assertFailsWith<StorageException> {
        offloader.uploadContent(byteArrayOf(1), "image/png", "t/s_uid_c0_p0.png")
      }

    assertEquals(412, failure.code)
  }

  @Test
  @Suppress("CheckReturnValue")
  fun uploadContent_opensTheClientOnFirstUseOnly(): Unit = runBlocking {
    assertEquals(0, opened.get())

    repeat(2) { offloader.uploadContent(byteArrayOf(1), "image/png", "t/s_uid_c0_p$it.png") }

    assertEquals(1, opened.get())
  }

  @Test
  @Suppress("CheckReturnValue")
  fun close_closesTheClientOnce(): Unit = runBlocking {
    offloader.uploadContent(byteArrayOf(1), "image/png", "t/s_uid_c0_p0.png")

    offloader.close()
    offloader.close()

    verify(storage, times(1)).close()
  }

  @Test
  fun close_beforeAnyUpload_opensNoClient() {
    offloader.close()

    assertEquals(0, opened.get())
  }

  @Test
  fun uploadContent_afterClose_fails(): Unit = runBlocking {
    offloader.close()

    assertFailsWith<IllegalStateException> {
      offloader.uploadContent(byteArrayOf(1), "image/png", "t/s_uid_c0_p0.png")
    }
    assertEquals(0, opened.get())
  }

  @Test
  fun close_whileTheClientOpens_closesItAndFailsTheUpload(): Unit = runBlocking {
    val opening = CompletableDeferred<Unit>()
    val proceed = CountDownLatch(1)
    val slowOffloader =
      GcsOffloader(BUCKET, Dispatchers.Default) {
        opening.complete(Unit)
        proceed.await()
        storage
      }
    supervisorScope {
      val upload =
        async(Dispatchers.Default) {
          slowOffloader.uploadContent(byteArrayOf(1), "image/png", "t/s_uid_c0_p0.png")
        }
      opening.await()

      slowOffloader.close()
      proceed.countDown()

      assertFailsWith<IllegalStateException> { upload.await() }
    }
    verify(storage).close()
    verify(storage, never())
      .create(any<BlobInfo>(), any<ByteArray>(), any<Storage.BlobTargetOption>())
  }

  @Test
  fun storageOptions_useTheProjectTheCredentialsAndThePluginUserAgent() {
    val credentials = mock<Credentials>()
    val config = BigQueryLoggerConfig("my-project", "d", gcsBucketName = BUCKET)

    val options = GcsOffloader.storageOptions(config, credentials)

    assertEquals("my-project", options.projectId)
    assertSame(credentials, options.credentials)
    assertEquals(BigQueryUtils.getVersionHeaderValue(), options.userAgent)
    assertEquals(1, options.retrySettings.maxAttempts)
    assertEquals(Duration.ofSeconds(10), options.retrySettings.initialRpcTimeoutDuration)
    assertEquals(Duration.ofSeconds(10), options.retrySettings.maxRpcTimeoutDuration)
    assertEquals(Duration.ofSeconds(10), options.retrySettings.totalTimeoutDuration)

    val defaultCredentialsOptions = GcsOffloader.storageOptions(config, credentials = null)
    assertEquals("my-project", defaultCredentialsOptions.projectId)
  }

  @Test
  fun uploadContent_exceedingTimeout_throwsIoException(): Unit = runBlocking {
    val unblock = CountDownLatch(1)
    val timedOffloader =
      GcsOffloader(BUCKET, Dispatchers.Default, uploadTimeout = 20.milliseconds) {
        unblock.await()
        storage
      }
    try {
      assertFailsWith<IOException> {
        timedOffloader.uploadContent(byteArrayOf(1), "image/png", "t/s_uid_c0_p0.png")
      }
    } finally {
      unblock.countDown()
    }
  }

  @Test
  fun create_requiresBucketNameAndBuildsStorageClientWithCredentials(): Unit = runBlocking {
    assertFailsWith<IllegalArgumentException> {
      GcsOffloader.create(
        BigQueryLoggerConfig("p", "d"),
        credentials = null,
        Dispatchers.Unconfined,
      )
    }

    val credentials =
      mock<Credentials> { on { universeDomain } doThrow IllegalStateException("offline") }
    val config = BigQueryLoggerConfig("my-project", "d", gcsBucketName = BUCKET)
    GcsOffloader.create(config, credentials, Dispatchers.Unconfined).use { created ->
      assertFailsWith<StorageException> {
        created.uploadContent(byteArrayOf(1), "text/plain", "2026-11-13/t/s.txt")
      }
    }
    verify(credentials).universeDomain
  }

  @Test
  fun extensionForMimeType_mapsCommonTypesAndIgnoresParameters() {
    val expected =
      mapOf(
        "image/jpeg" to ".jpg",
        "image/png" to ".png",
        "image/svg+xml" to ".svg",
        "audio/mpeg" to ".mp3",
        "audio/x-wav" to ".wav",
        "audio/mp4" to ".m4a",
        "video/mp4" to ".mp4",
        "video/ogg" to ".ogv",
        "video/quicktime" to ".mov",
        "application/pdf" to ".pdf",
        "application/json" to ".json",
        "text/xml" to ".xml",
        "text/plain; charset=utf-8" to ".txt",
        " IMAGE/PNG " to ".png",
        "application/x-unknown" to ".bin",
        "" to ".bin",
      )

    assertEquals(expected, expected.keys.associateWith(GcsOffloader::extensionForMimeType))
  }

  private companion object {
    const val BUCKET = "test-bucket"
  }
}
