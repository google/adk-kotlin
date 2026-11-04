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

import com.google.adk.kt.logging.LoggerFactory
import com.google.api.core.ApiFuture
import com.google.api.core.ApiFutureCallback
import com.google.api.core.ApiFutures
import com.google.api.gax.batching.FlowControlSettings
import com.google.api.gax.batching.FlowController
import com.google.api.gax.core.FixedCredentialsProvider
import com.google.api.gax.rpc.ApiException
import com.google.api.gax.rpc.FixedHeaderProvider
import com.google.auth.Credentials
import com.google.cloud.bigquery.storage.v1.AppendRowsResponse
import com.google.cloud.bigquery.storage.v1.BigQueryWriteClient
import com.google.cloud.bigquery.storage.v1.BigQueryWriteSettings
import com.google.cloud.bigquery.storage.v1.Exceptions
import com.google.cloud.bigquery.storage.v1.JsonStreamWriter
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import io.grpc.Status
import io.grpc.StatusRuntimeException
import java.io.IOException
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** An open stream that appends JSON rows to the events table. */
internal interface RowStream : AutoCloseable {
  /** Whether the stream can no longer append, so that a new one has to be opened. */
  val isClosed: Boolean

  fun append(rows: JsonArray): ApiFuture<AppendRowsResponse>
}

/**
 * Writes rows to the events table's default stream with the BigQuery Storage Write API.
 *
 * [openStream] opens a stream, blocking on network calls, and is called again once a stream closes.
 * [onClose] releases what the streams share, such as their client, and may run more than once.
 */
internal class StorageWriteApiWriter(
  private val openStream: () -> RowStream,
  private val onClose: () -> Unit = {},
  private val appendTimeout: Duration = APPEND_TIMEOUT,
) : RowWriter {
  private val lock = Any()
  // Written by the batch writer and by close.
  @Volatile private var stream: RowStream? = null
  @Volatile private var closed = false

  override suspend fun write(rows: List<Map<String, Any?>>): WriteResult {
    val response =
      try {
        val future = currentStream().append(toJsonArray(rows))
        withTimeoutOrNull(appendTimeout) { future.await() }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        return classify(e, rows.size)
      }
    if (response == null) {
      // The stream still holds the rows and may yet write them, so they are not appended again.
      release()
      logger.warn {
        "BigQuery append of ${rows.size} row(s) timed out after $appendTimeout; dropped them."
      }
      // Not retried; Python ADK counts writes that keep timing out as retry_exhausted too.
      return WriteResult.Dropped(BatchProcessor.RETRY_EXHAUSTED)
    }
    return WriteResult.Written
  }

  // Closing waits for any request the stream still holds, which can take minutes.
  override fun release() {
    val idle = takeStream() ?: return
    thread(name = "bigquery-analytics-stream-close", isDaemon = true) { idle.closeQuietly() }
  }

  // Blocks, unlike release(), so that callers decide how long to wait for in-flight rows.
  override fun close() {
    closed = true
    takeStream()?.closeQuietly()
    onClose()
  }

  private fun currentStream(): RowStream {
    val open = stream
    if (open != null && !open.isClosed) return open
    takeStream()?.closeQuietly()
    check(!closed) { "The BigQuery writer is closed." }
    val opened =
      try {
        openStream()
      } catch (e: Exception) {
        // Close may have run while the open created the client.
        if (closed) onClose()
        throw e
      }
    // A stream opened while close runs must not stay open, nor the client it may have created.
    synchronized(lock) {
      if (!closed) {
        stream = opened
        return opened
      }
    }
    opened.closeQuietly()
    onClose()
    error("The BigQuery writer is closed.")
  }

  // Takes the stream under the lock, so that each stream is closed once.
  private fun takeStream(): RowStream? =
    synchronized(lock) {
      val taken = stream
      stream = null
      taken
    }

  private fun RowStream.closeQuietly() {
    try {
      close()
    } catch (e: Exception) {
      logger.debug { "Closing a BigQuery write stream failed: ${e::class.simpleName}" }
    }
  }

  companion object {
    // Bounds one append or stream open, so that a stalled connection cannot hold up the writer.
    private val APPEND_TIMEOUT = 30.seconds

    private val RETRYABLE_CODES =
      setOf(
        Status.Code.DEADLINE_EXCEEDED,
        Status.Code.INTERNAL,
        Status.Code.UNAVAILABLE,
        Status.Code.RESOURCE_EXHAUSTED,
      )

    private val logger = LoggerFactory.getLogger(StorageWriteApiWriter::class)

    /**
     * Returns a writer to [config]'s table that creates its client on the first write, with
     * [credentials] or, if they are null, Application Default Credentials.
     */
    fun create(config: BigQueryLoggerConfig, credentials: Credentials?): StorageWriteApiWriter {
      val streamName =
        "projects/${config.projectId}/datasets/${config.datasetId}/tables/${config.tableName}" +
          "/streams/_default"
      val client = lazy { BigQueryWriteClient.create(clientSettings(credentials)) }
      return StorageWriteApiWriter(
        openStream = { JsonRowStream(createJsonStreamWriter(streamName, client.value)) },
        onClose = { if (client.isInitialized()) client.value.close() },
      )
    }

    /**
     * Returns the trace id that identifies this plugin as the writer, in the `name:id` form the
     * client requires. Visible for tests.
     */
    fun traceId(): String = "${BigQueryUtils.getVersionHeaderValue()}:${UUID.randomUUID()}"

    // Logs codes, counts and I/O messages, never row content; other errors are rethrown.
    private fun classify(error: Throwable, rowCount: Int): WriteResult =
      when (error) {
        is Exceptions.AppendSerializationError -> {
          logger.error {
            "BigQuery rejected ${error.rowIndexToErrorMessage.size} of $rowCount row(s) that do " +
              "not match the table schema; dropped the batch."
          }
          WriteResult.Dropped(BatchProcessor.NON_RETRYABLE)
        }
        is Exceptions.SchemaMismatchedException -> {
          logger.error {
            "BigQuery schema mismatch: the table lacks columns this plugin writes. Turn on " +
              "autoSchemaUpgrade or add the columns; dropped $rowCount row(s)."
          }
          WriteResult.Dropped(BatchProcessor.NON_RETRYABLE)
        }
        // The next attempt opens a new stream.
        is Exceptions.StreamWriterClosedException -> WriteResult.Retryable
        is StatusRuntimeException -> fromStatus(error.status.code, rowCount)
        is ApiException -> fromStatus(Status.Code.valueOf(error.statusCode.code.name), rowCount)
        // Such as missing credentials, raised when a write client is created.
        is IOException -> {
          logger.warn { "BigQuery write of $rowCount row(s) failed: $error" }
          WriteResult.Retryable
        }
        else -> throw error
      }

    // Writes timestamps in epoch microseconds and leaves out null values.
    private fun toJsonArray(rows: List<Map<String, Any?>>): JsonArray =
      JsonArray(rows.size).apply { for (row in rows) add(toJson(row)) }

    private fun toJson(value: Any?): JsonElement =
      when (value) {
        null -> JsonNull.INSTANCE
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Instant -> JsonPrimitive(ChronoUnit.MICROS.between(Instant.EPOCH, value))
        is Map<*, *> ->
          JsonObject().apply {
            for ((key, item) in value) if (item != null) add(key.toString(), toJson(item))
          }
        is Iterable<*> -> JsonArray().apply { for (item in value) add(toJson(item)) }
        else -> JsonPrimitive(value.toString())
      }

    private fun fromStatus(code: Status.Code, rowCount: Int): WriteResult =
      when (code) {
        in RETRYABLE_CODES -> {
          logger.warn { "BigQuery Write API returned $code for $rowCount row(s)." }
          WriteResult.Retryable
        }
        Status.Code.PERMISSION_DENIED -> {
          logger.error {
            "BigQuery Write API denied permission, or the table does not exist; dropped " +
              "$rowCount row(s). The credentials need bigquery.tables.get and " +
              "bigquery.tables.updateData on the table."
          }
          WriteResult.Dropped(BatchProcessor.PERMISSION_DENIED)
        }
        Status.Code.NOT_FOUND -> {
          logger.error {
            "BigQuery Write API did not find the table or stream; dropped $rowCount row(s)."
          }
          WriteResult.Dropped(BatchProcessor.NOT_FOUND)
        }
        else -> {
          logger.error { "BigQuery Write API returned $code; dropped $rowCount row(s)." }
          WriteResult.Dropped(BatchProcessor.NON_RETRYABLE)
        }
      }

    /**
     * Returns the write client's settings, with [credentials] or, if they are null, Application
     * Default Credentials. Visible for tests.
     */
    fun clientSettings(credentials: Credentials?): BigQueryWriteSettings {
      val settings =
        BigQueryWriteSettings.newBuilder()
          .setHeaderProvider(
            FixedHeaderProvider.create(mapOf("user-agent" to BigQueryUtils.getVersionHeaderValue()))
          )
      // GetWriteStream would retry for minutes on open; the batch writer retries instead.
      val unused =
        settings.writeStreamSettings.setSimpleTimeoutNoRetriesDuration(
          APPEND_TIMEOUT.toJavaDuration()
        )
      if (credentials != null) {
        settings.setCredentialsProvider(FixedCredentialsProvider.create(credentials))
      }
      return settings.build()
    }

    // The location comes from the stream; setting it as well fails on any spelling difference.
    private fun createJsonStreamWriter(
      streamName: String,
      client: BigQueryWriteClient,
    ): JsonStreamWriter =
      JsonStreamWriter.newBuilder(streamName, client)
        .setTraceId(traceId())
        // Fails an append over the in-flight limit rather than blocking the batch writer.
        .setFlowControlSettings(
          FlowControlSettings.newBuilder()
            .setLimitExceededBehavior(FlowController.LimitExceededBehavior.ThrowException)
            .build()
        )
        .build()
  }
}

private class JsonRowStream(private val writer: JsonStreamWriter) : RowStream {
  override val isClosed: Boolean
    get() = writer.isClosed

  override fun append(rows: JsonArray): ApiFuture<AppendRowsResponse> = writer.append(rows)

  override fun close() {
    writer.close()
  }
}

private suspend fun <T> ApiFuture<T>.await(): T = suspendCancellableCoroutine { continuation ->
  ApiFutures.addCallback(
    this,
    object : ApiFutureCallback<T> {
      override fun onSuccess(result: T) {
        continuation.resume(result)
      }

      override fun onFailure(t: Throwable) {
        continuation.resumeWithException(t)
      }
    },
    Runnable::run,
  )
  continuation.invokeOnCancellation { this@await.cancel(/* mayInterruptIfRunning= */ false) }
}
