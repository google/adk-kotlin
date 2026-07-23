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
import com.google.adk.kt.types.Content
import com.google.cloud.bigquery.BigQuery
import com.google.cloud.bigquery.BigQueryOptions
import com.google.cloud.bigquery.InsertAllRequest
import com.google.cloud.bigquery.StandardTableDefinition
import com.google.cloud.bigquery.TableId
import com.google.cloud.bigquery.TableInfo
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Kotlin version of BigQueryAgentAnalyticsPlugin.
 *
 * Phase 1 Implementation: Basic functionality and connectivity.
 */
class BigQueryAgentAnalyticsPlugin(
  private val config: BigQueryLoggerConfig,
  private val bigQuery: BigQuery = createBigQuery(config),
) : Plugin {

  override val name: String = "bigquery_agent_analytics"

  private val tableEnsured = AtomicBoolean(false)

  override suspend fun beforeRun(
    invocationContext: InvocationContext
  ): CallbackChoice<Unit, Content> {
    logEvent("INVOCATION_STARTING", invocationContext, "Invocation started")
    return CallbackChoice.Continue(Unit)
  }

  override suspend fun afterRun(invocationContext: InvocationContext) {
    logEvent("INVOCATION_COMPLETED", invocationContext, "Invocation completed")
  }

  private fun logEvent(eventType: String, invocationContext: InvocationContext, message: String) {
    if (!config.enabled) return
    ensureTableExistsOnce()

    val row = mutableMapOf<String, Any>()
    row["timestamp"] = Instant.now().toString()
    row["event_type"] = eventType
    row["agent"] = invocationContext.agent.name
    row["session_id"] = invocationContext.session.key.id ?: "unknown"
    row["invocation_id"] = invocationContext.invocationId
    row["user_id"] = invocationContext.session.key.userId

    // In Phase 1, we just put a simple message in content or attributes if we want to test
    // Let's put it in attributes for simplicity if content is meant for JSON payload.
    // Actually, schema has 'content' as JSON. Let's try to put message in content if we can
    // serialize it simply,
    // or just leave it empty for now and test other fields.
    // Let's use standard BigQuery insertAll for Phase 1.

    val tableId = TableId.of(config.datasetId, config.tableName)
    try {
      val response = bigQuery.insertAll(InsertAllRequest.newBuilder(tableId).addRow(row).build())
      if (response.hasErrors()) {
        logger.error { "Error inserting row into BigQuery: ${response.insertErrors}" }
      } else {
        logger.info { "Successfully inserted row: $eventType" }
      }
    } catch (e: Exception) {
      logger.error(e) { "Failed to insert row into BigQuery" }
    }
  }

  private fun ensureTableExistsOnce() {
    if (!tableEnsured.get()) {
      synchronized(this) {
        if (!tableEnsured.get()) {
          if (ensureTableExists()) {
            tableEnsured.set(true)
          }
        }
      }
    }
  }

  private fun ensureTableExists(): Boolean {
    val tableId = TableId.of(config.datasetId, config.tableName)
    return try {
      val table = bigQuery.getTable(tableId)
      if (table == null) {
        logger.info { "Creating BigQuery table: $tableId" }
        val schema = BigQuerySchema.getEventsSchema()
        val tableDefinition = StandardTableDefinition.of(schema)
        val tableInfo = TableInfo.newBuilder(tableId, tableDefinition).build()
        bigQuery.create(tableInfo)
        logger.info { "Table created: $tableId" }
      } else {
        logger.info { "Table already exists: $tableId" }
      }
      true
    } catch (e: Exception) {
      logger.error(e) { "Failed to ensure BigQuery table exists" }
      false
    }
  }

  companion object {
    private val logger = LoggerFactory.getLogger(BigQueryAgentAnalyticsPlugin::class)

    private fun createBigQuery(config: BigQueryLoggerConfig): BigQuery {
      val builder = BigQueryOptions.newBuilder()
      // For simplicity in Phase 1, we omit custom headers and credentials provider if not set in
      // config,
      // relying on GCP default auth.
      if (config.credentials != null) {
        builder.setCredentials(config.credentials)
      }
      return builder.setLocation(config.location).setProjectId(config.projectId).build().service
    }
  }
}
