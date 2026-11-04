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

import com.google.api.core.ApiFuture
import com.google.api.core.ApiFutures
import com.google.api.core.SettableApiFuture
import com.google.api.gax.core.GoogleCredentialsProvider
import com.google.api.gax.rpc.NotFoundException
import com.google.api.gax.rpc.StatusCode
import com.google.auth.Credentials
import com.google.cloud.bigquery.storage.v1.AppendRowsResponse
import com.google.cloud.bigquery.storage.v1.Exceptions
import com.google.cloud.bigquery.storage.v1.StreamWriter
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import io.grpc.Status
import java.io.IOException
import java.time.Instant
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.mockito.kotlin.mock

class StorageWriteApiWriterTest {

  @Test
  fun write_appendsTheRowsAsJson(): Unit = runBlocking {
    val stream = FakeStream()
    val writer = StorageWriteApiWriter(openStream = { stream })

    val result =
      writer.write(
        listOf(
          mapOf(
            "timestamp" to Instant.ofEpochSecond(1, 2_000),
            "event_type" to "LLM_REQUEST",
            "error_message" to null,
            "is_truncated" to false,
            "content_parts" to listOf(mapOf("part_index" to 0, "text" to "hi", "uri" to null)),
          )
        )
      )

    assertEquals(WriteResult.Written, result)
    assertEquals(
      JsonParser.parseString(
        """
        [{"timestamp": 1000002, "event_type": "LLM_REQUEST", "is_truncated": false,
          "content_parts": [{"part_index": 0, "text": "hi"}]}]
        """
      ),
      stream.appended.single(),
    )
  }

  @Test
  fun write_failedAppend_isClassifiedByItsStatusCode(): Unit = runBlocking {
    val expected =
      mapOf(
        Status.UNAVAILABLE to WriteResult.Retryable,
        Status.INTERNAL to WriteResult.Retryable,
        Status.DEADLINE_EXCEEDED to WriteResult.Retryable,
        Status.RESOURCE_EXHAUSTED to WriteResult.Retryable,
        Status.PERMISSION_DENIED to WriteResult.Dropped(BatchProcessor.PERMISSION_DENIED),
        Status.NOT_FOUND to WriteResult.Dropped(BatchProcessor.NOT_FOUND),
        Status.INVALID_ARGUMENT to WriteResult.Dropped(BatchProcessor.NON_RETRYABLE),
        Status.UNAUTHENTICATED to WriteResult.Dropped(BatchProcessor.NON_RETRYABLE),
        Status.ABORTED to WriteResult.Dropped(BatchProcessor.NON_RETRYABLE),
      )

    for ((status, result) in expected) {
      val stream = FakeStream { ApiFutures.immediateFailedFuture(status.asRuntimeException()) }
      val writer = StorageWriteApiWriter(openStream = { stream })

      assertEquals(result, writer.write(ROWS), status.code.name)
    }
  }

  @Test
  fun write_appendThatThrows_isClassifiedLikeAFailedAppend(): Unit = runBlocking {
    val stream = FakeStream { throw Status.UNAVAILABLE.asRuntimeException() }
    val writer = StorageWriteApiWriter(openStream = { stream })

    assertEquals(WriteResult.Retryable, writer.write(ROWS))
  }

  @Test
  fun write_rowsTheTableRejects_areDroppedAsNonRetryable(): Unit = runBlocking {
    val error =
      Exceptions.AppendSerializationError(
        Status.Code.INVALID_ARGUMENT.value(),
        "invalid rows",
        "stream",
        mapOf(0 to "bad value"),
      )
    val stream = FakeStream { ApiFutures.immediateFailedFuture(error) }
    val writer = StorageWriteApiWriter(openStream = { stream })

    assertEquals(WriteResult.Dropped(BatchProcessor.NON_RETRYABLE), writer.write(ROWS))
  }

  @Test
  fun write_ioException_isRetryableAndLogged(): Unit = runBlocking {
    val writer =
      StorageWriteApiWriter(
        openStream = { throw IOException("default credentials were not found") }
      )

    val records = logRecordsDuring { assertEquals(WriteResult.Retryable, writer.write(ROWS)) }

    assertTrue(records.any { it.message?.contains("default credentials were not found") == true })
  }

  @Test
  fun write_unexpectedException_isLeftToTheCaller(): Unit = runBlocking {
    val stream = FakeStream { throw IllegalStateException("bug") }
    val writer = StorageWriteApiWriter(openStream = { stream })

    assertFailsWith<IllegalStateException> { writer.write(ROWS) }
  }

  @Test
  fun write_appendThatTimesOut_isDroppedAndItsStreamReplaced(): Unit = runBlocking {
    val future = SettableApiFuture.create<AppendRowsResponse>()
    val streams = listOf(FakeStream { future }, FakeStream())
    var opened = 0
    val writer =
      StorageWriteApiWriter(openStream = { streams[opened++] }, appendTimeout = 50.milliseconds)

    // The stalled stream may still write the rows, so they are not appended again.
    assertEquals(WriteResult.Dropped(BatchProcessor.RETRY_EXHAUSTED), writer.write(ROWS))
    assertTrue(future.isCancelled)
    eventually { streams[0].isClosed }
    assertEquals(WriteResult.Written, writer.write(ROWS))
    assertEquals(listOf(1, 1), streams.map { it.appended.size })
  }

  @Test
  fun write_failureToOpenTheStream_isClassified(): Unit = runBlocking {
    val notFound =
      NotFoundException(
        RuntimeException("no table"),
        object : StatusCode {
          override fun getCode() = StatusCode.Code.NOT_FOUND

          override fun getTransportCode(): Any? = null
        },
        /* retryable= */ false,
      )
    val writer = StorageWriteApiWriter(openStream = { throw notFound })

    assertEquals(WriteResult.Dropped(BatchProcessor.NOT_FOUND), writer.write(ROWS))
  }

  @Test
  fun write_afterTheStreamCloses_opensANewOne(): Unit = runBlocking {
    val streams = listOf(FakeStream(), FakeStream())
    var opened = 0
    val writer = StorageWriteApiWriter(openStream = { streams[opened++] })
    assertEquals(WriteResult.Written, writer.write(ROWS))
    streams[0].isClosed = true

    assertEquals(WriteResult.Written, writer.write(ROWS))

    assertEquals(2, opened)
    assertEquals(listOf(1, 1), streams.map { it.appended.size })
  }

  @Test
  fun close_closesTheStreamAndReleasesTheClient(): Unit = runBlocking {
    val stream = FakeStream()
    var released = false
    val writer = StorageWriteApiWriter(openStream = { stream }, onClose = { released = true })
    assertEquals(WriteResult.Written, writer.write(ROWS))

    writer.close()

    assertTrue(stream.isClosed)
    assertTrue(released)
  }

  @Test
  fun release_closesTheStreamAndTheNextWriteOpensANewOne(): Unit = runBlocking {
    val streams = listOf(FakeStream(), FakeStream())
    var opened = 0
    val writer = StorageWriteApiWriter(openStream = { streams[opened++] })
    assertEquals(WriteResult.Written, writer.write(ROWS))

    writer.release()

    eventually { streams[0].isClosed }
    assertEquals(WriteResult.Written, writer.write(ROWS))
    assertEquals(2, opened)
  }

  @Test
  fun close_whileAStreamOpens_closesThatStreamAndReleasesTheClientAgain(): Unit = runBlocking {
    val stream = FakeStream()
    var clientReleases = 0
    var closeWriter: () -> Unit = {}
    val writer =
      StorageWriteApiWriter(
        openStream = {
          closeWriter()
          stream
        },
        onClose = { clientReleases++ },
      )
    closeWriter = writer::close

    assertFailsWith<IllegalStateException> { writer.write(ROWS) }
    assertTrue(stream.isClosed)
    // Once by close, before the client existed, and once after the late open created it.
    assertEquals(2, clientReleases)
  }

  @Test
  fun close_whileAStreamFailsToOpen_releasesTheClientAgain(): Unit = runBlocking {
    var clientReleases = 0
    var closeWriter: () -> Unit = {}
    val writer =
      StorageWriteApiWriter(
        openStream = {
          closeWriter()
          throw IOException("no table")
        },
        onClose = { clientReleases++ },
      )
    closeWriter = writer::close

    assertEquals(WriteResult.Retryable, writer.write(ROWS))
    // Once by close, before the client existed, and once after the failed open created it.
    assertEquals(2, clientReleases)
  }

  @Test
  fun write_failure_doesNotLogRowContent(): Unit = runBlocking {
    val stream = FakeStream {
      ApiFutures.immediateFailedFuture(
        Status.INVALID_ARGUMENT.withDescription("bad SECRET-PAYLOAD").asRuntimeException()
      )
    }
    val writer = StorageWriteApiWriter(openStream = { stream })

    val records = logRecordsDuring {
      assertEquals(
        WriteResult.Dropped(BatchProcessor.NON_RETRYABLE),
        writer.write(listOf(mapOf("content" to "SECRET-PAYLOAD"))),
      )
    }

    assertTrue(records.isNotEmpty())
    assertFalse(records.any { it.message?.contains("SECRET-PAYLOAD") == true })
  }

  @Test
  fun traceId_hasTheFormTheWriteClientRequires() {
    val traceId = StorageWriteApiWriter.traceId()

    // Throws unless the id has the form "name:id".
    val unused =
      StreamWriter.newBuilder("projects/p/datasets/d/tables/t/streams/_default").setTraceId(traceId)
    assertTrue(traceId.startsWith("${BigQueryUtils.getVersionHeaderValue()}:"))
  }

  @Test
  fun clientSettings_withCredentials_usesThem() {
    val credentials = mock<Credentials>()

    val settings = StorageWriteApiWriter.clientSettings(credentials)

    assertSame(credentials, settings.credentialsProvider.credentials)
  }

  @Test
  fun clientSettings_withoutCredentials_keepsTheApplicationDefaultCredentials() {
    val settings = StorageWriteApiWriter.clientSettings(credentials = null)

    assertIs<GoogleCredentialsProvider>(settings.credentialsProvider)
  }

  /** Returns the records the writer logs while [block] runs. */
  private suspend fun logRecordsDuring(block: suspend () -> Unit): List<LogRecord> {
    val records = mutableListOf<LogRecord>()
    val handler =
      object : Handler() {
        override fun publish(record: LogRecord?) {
          if (record != null) records += record
        }

        override fun flush() {}

        override fun close() {}
      }
    val julLogger = Logger.getLogger(StorageWriteApiWriter::class.java.name)
    julLogger.addHandler(handler)
    try {
      block()
    } finally {
      julLogger.removeHandler(handler)
    }
    return records
  }

  private suspend fun eventually(condition: () -> Boolean): Unit =
    withTimeout(5.seconds) { while (!condition()) delay(5) }

  private companion object {
    val ROWS = listOf(mapOf<String, Any?>("event_type" to "LLM_REQUEST"))
  }
}

/** A [RowStream] that records each append and answers with [answer]. */
private class FakeStream(
  private val answer: (JsonArray) -> ApiFuture<AppendRowsResponse> = {
    ApiFutures.immediateFuture(AppendRowsResponse.getDefaultInstance())
  }
) : RowStream {
  val appended = mutableListOf<JsonArray>()
  // The writer may close a stream on its own thread.
  @Volatile override var isClosed = false

  override fun append(rows: JsonArray): ApiFuture<AppendRowsResponse> {
    appended += rows
    return answer(rows)
  }

  override fun close() {
    isClosed = true
  }
}
