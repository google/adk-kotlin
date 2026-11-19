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
import com.google.cloud.http.HttpTransportOptions
import com.google.cloud.storage.BlobId
import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.Storage
import com.google.cloud.storage.StorageException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
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
  fun storageOptions_useTheProjectTheCredentialsAndThePluginUserAgent() {
    val credentials = mock<Credentials>()
    val config = BigQueryLoggerConfig("my-project", "d", gcsBucketName = BUCKET)

    val options = GcsOffloader.storageOptions(config, credentials)

    assertEquals("my-project", options.projectId)
    assertSame(credentials, options.credentials)
    assertEquals(BigQueryUtils.getVersionHeaderValue(), options.userAgent)
    val transport = assertIs<HttpTransportOptions>(options.transportOptions)
    assertEquals(10_000, transport.connectTimeout)
    assertEquals(10_000, transport.readTimeout)
    assertEquals(1, options.retrySettings.maxAttempts)

    val defaultCredentialsOptions = GcsOffloader.storageOptions(config, credentials = null)
    assertEquals("my-project", defaultCredentialsOptions.projectId)
  }

  @Test
  fun create_withoutBucketName_fails() {
    assertFailsWith<IllegalArgumentException> {
      GcsOffloader.create(
        BigQueryLoggerConfig("p", "d"),
        credentials = null,
        Dispatchers.Unconfined,
      )
    }
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

  companion object {
    private const val BUCKET = "test-bucket"
  }
}
