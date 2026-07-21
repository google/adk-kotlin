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

import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.callbacks.CallbackChoice
import com.google.adk.kt.logging.LoggerFactory
import com.google.adk.kt.plugins.Plugin
import com.google.adk.kt.serialization.Json
import com.google.adk.kt.types.Content
import com.google.cloud.bigquery.BigQuery
import com.google.cloud.bigquery.BigQueryException
import com.google.cloud.bigquery.BigQueryOptions
import com.google.cloud.bigquery.Clustering
import com.google.cloud.bigquery.InsertAllRequest
import com.google.cloud.bigquery.StandardTableDefinition
import com.google.cloud.bigquery.TableId
import com.google.cloud.bigquery.TableInfo
import com.google.cloud.bigquery.TimePartitioning
import java.time.Instant
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * An ADK [Plugin] that records agent invocation lifecycle events to a BigQuery table. If the
 * destination table does not exist, the plugin automatically creates and configures the partitioned
 * table on first use. Row inserts currently execute synchronously on the invocation path, blocking
 * until the BigQuery write completes.
 */
class BigQueryAgentAnalyticsPlugin(
  private val config: BigQueryLoggerConfig,
  private val bigQuery: BigQuery = createBigQuery(config),
  @Suppress("GlobalCoroutineDispatchers") private val ioContext: CoroutineContext = Dispatchers.IO,
  private val timeSource: TimeSource = TimeSource.Monotonic,
) : Plugin {

  override val name: String = "bigquery_agent_analytics"

  @Volatile private var tableEnsured = false
  private val mutex = Mutex()
  private var lastCheckTime: TimeMark? = null

  override suspend fun beforeRun(
    invocationContext: InvocationContext
  ): CallbackChoice<Unit, Content> {
    logEvent("INVOCATION_STARTING", invocationContext, "Invocation started")
    return CallbackChoice.Continue(Unit)
  }

  override suspend fun afterRun(invocationContext: InvocationContext) {
    logEvent("INVOCATION_COMPLETED", invocationContext, "Invocation completed")
  }

  /**
   * Logs an event to BigQuery.
   *
   * Populates core invocation metadata and a summary message in `content`:
   * - Populated: `timestamp`, `event_type`, `agent`, `session_id`, `invocation_id`, `user_id`, and
   *   `content` (as a JSON string containing `{"message": ...}`).
   * - Left null (not yet populated): `trace_id`, `span_id`, `parent_span_id`, `content_parts`,
   *   `attributes`, `latency_ms`, `status`, `error_message`, and `is_truncated`.
   */
  private suspend fun logEvent(
    eventType: String,
    invocationContext: InvocationContext,
    message: String,
  ) {
    if (!config.enabled) return

    if (!ensureTableExistsOnce()) {
      logger.debug { "Table is not created, skipping event logging" }
      return
    }

    val row =
      mapOf<String, Any?>(
        "timestamp" to Instant.now().toString(),
        "event_type" to eventType,
        "agent" to invocationContext.agent.name,
        "session_id" to invocationContext.session.key.id,
        "invocation_id" to invocationContext.invocationId,
        "user_id" to invocationContext.session.key.userId,
        "content" to Json.toJsonString(mapOf("message" to message)),
      )

    val tableId = TableId.of(config.projectId, config.datasetId, config.tableName)
    withContext(ioContext) {
      try {
        val response = bigQuery.insertAll(InsertAllRequest.newBuilder(tableId).addRow(row).build())
        if (response.hasErrors()) {
          logger.error {
            "BigQuery insert failed for $eventType: ${response.insertErrors.size} row error(s)"
          }
        } else {
          logger.debug { "Successfully inserted row: $eventType" }
        }
      } catch (e: BigQueryException) {
        logger.error(e) { "Failed to insert row into BigQuery" }
      } catch (e: RuntimeException) {
        if (e is CancellationException) throw e
        logger.error(e) { "Failed to insert row into BigQuery" }
      }
    }
  }

  private suspend fun ensureTableExistsOnce(): Boolean {
    if (tableEnsured) return true

    mutex.withLock {
      if (tableEnsured) return true

      val elapsed = lastCheckTime?.elapsedNow()
      if (elapsed != null && elapsed < TABLE_CREATION_RETRY_INTERVAL) return false

      lastCheckTime = timeSource.markNow()
      if (ensureTableExists()) {
        tableEnsured = true
        return true
      }
      return false
    }
  }

  private suspend fun ensureTableExists(): Boolean =
    withContext(ioContext) {
      val tableId = TableId.of(config.projectId, config.datasetId, config.tableName)
      try {
        val table = bigQuery.getTable(tableId)
        if (table == null) {
          logger.info { "Creating BigQuery table: $tableId" }
          val schema = BigQuerySchema.getEventsSchema()
          val tableDefinition =
            StandardTableDefinition.newBuilder()
              .setSchema(schema)
              .setTimePartitioning(
                TimePartitioning.newBuilder(TimePartitioning.Type.DAY).setField("timestamp").build()
              )
              .setClustering(
                Clustering.newBuilder()
                  .setFields(BigQuerySchema.getDefaultClusteringFields())
                  .build()
              )
              .build()
          val tableInfo =
            TableInfo.newBuilder(tableId, tableDefinition)
              .setLabels(
                mapOf(BigQuerySchema.SCHEMA_VERSION_LABEL_KEY to BigQuerySchema.SCHEMA_VERSION)
              )
              .build()
          bigQuery.create(tableInfo)
          logger.info { "Table created: $tableId" }
        } else {
          logger.info { "Table already exists: $tableId" }
        }
        true
      } catch (e: BigQueryException) {
        if (e.code == 409) {
          logger.info { "Table was created concurrently" }
          true
        } else {
          logger.error(e) { "Failed to ensure BigQuery table exists" }
          false
        }
      } catch (e: RuntimeException) {
        if (e is CancellationException) throw e
        logger.error(e) { "Failed to ensure BigQuery table exists" }
        false
      }
    }

  companion object {
    private val TABLE_CREATION_RETRY_INTERVAL = 10.seconds
    private val logger = LoggerFactory.getLogger(BigQueryAgentAnalyticsPlugin::class)

    /**
     * Creates a [BigQuery] client instance from [config].
     *
     * Uses GCP default auth unless specific credentials are provided in [config].
     */
    private fun createBigQuery(config: BigQueryLoggerConfig): BigQuery {
      val builder = BigQueryOptions.newBuilder()
      if (config.credentials != null) {
        builder.setCredentials(config.credentials)
      }
      return builder.setLocation(config.location).setProjectId(config.projectId).build().service
    }
  }
}
