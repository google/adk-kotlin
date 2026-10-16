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
import com.google.cloud.bigquery.BigQuery
import com.google.cloud.bigquery.BigQueryException
import com.google.cloud.bigquery.Field
import com.google.cloud.bigquery.FieldList
import com.google.cloud.bigquery.JobException
import com.google.cloud.bigquery.QueryJobConfiguration
import com.google.cloud.bigquery.Schema
import com.google.cloud.bigquery.StandardSQLTypeName
import com.google.cloud.bigquery.StandardTableDefinition
import com.google.cloud.bigquery.Table
import java.util.Locale

/** Utility for managing BigQuery schema upgrades and analytics views. */
internal object BigQueryUtils {

  private val logger = LoggerFactory.getLogger(BigQueryUtils::class)

  const val A2A_PREFIX = "a2a:"
  const val A2A_REQUEST_KEY = "a2a:request"
  const val A2A_RESPONSE_KEY = "a2a:response"
  private const val A2A_TASK_ID_KEY = "a2a:task_id"
  private const val A2A_CONTEXT_ID_KEY = "a2a:context_id"

  private const val FRAMEWORK_PREFIX = "google-adk-bq-logger-kotlin"
  private const val VERSION = "0.1.0"

  private val VIEW_COMMON_COLUMNS =
    listOf(
      "timestamp",
      "event_id",
      "event_type",
      "agent",
      "session_id",
      "invocation_id",
      "user_id",
      "trace_id",
      "span_id",
      "parent_span_id",
      "status",
      "error_message",
      "is_truncated",
    )

  private val EVENT_VIEW_DEFS: Map<String, List<String>> =
    mapOf(
      EventType.USER_MESSAGE_RECEIVED to emptyList(),
      EventType.LLM_REQUEST to
        listOf(
          "JSON_VALUE(attributes, '$.model') AS model",
          "content AS request_content",
          "JSON_QUERY(attributes, '$.llm_config') AS llm_config",
          "JSON_QUERY(attributes, '$.tools') AS tools",
        ),
      EventType.LLM_RESPONSE to
        listOf(
          "JSON_QUERY(content, '$.response') AS response",
          "CAST(JSON_VALUE(content, '$.usage.prompt') AS INT64) AS usage_prompt_tokens",
          "CAST(JSON_VALUE(content, '$.usage.completion') AS INT64) AS usage_completion_tokens",
          "CAST(JSON_VALUE(content, '$.usage.total') AS INT64) AS usage_total_tokens",
          "CAST(JSON_VALUE(attributes, '$.usage_metadata.cached_content_token_count') AS INT64)" +
            " AS usage_cached_tokens",
          "CAST(JSON_VALUE(attributes, '$.usage_metadata.thoughts_token_count') AS INT64)" +
            " AS usage_thinking_tokens",
          "CAST(JSON_VALUE(attributes, '$.usage_metadata.tool_use_prompt_token_count') AS INT64)" +
            " AS usage_tool_use_tokens",
          "SAFE_DIVIDE(CAST(JSON_VALUE(attributes, " +
            "'$.usage_metadata.cached_content_token_count') AS INT64), " +
            "CAST(JSON_VALUE(content, '$.usage.prompt') AS INT64)) AS context_cache_hit_rate",
          "CAST(JSON_VALUE(latency_ms, '$.total_ms') AS INT64) AS total_ms",
          "CAST(JSON_VALUE(latency_ms, '$.time_to_first_token_ms') AS INT64) AS ttft_ms",
          "JSON_VALUE(attributes, '$.model_version') AS model_version",
          "JSON_QUERY(attributes, '$.usage_metadata') AS usage_metadata",
          "JSON_QUERY(attributes, '$.cache_metadata') AS cache_metadata",
          "JSON_VALUE(attributes, '$.cache_type') AS cache_type",
          "JSON_VALUE(attributes, '$.finish_reason') AS finish_reason",
        ),
      EventType.LLM_ERROR to
        listOf("CAST(JSON_VALUE(latency_ms, '$.total_ms') AS INT64) AS total_ms"),
      EventType.TOOL_STARTING to
        listOf(
          "JSON_VALUE(content, '$.tool') AS tool_name",
          "JSON_QUERY(content, '$.args') AS tool_args",
          "JSON_VALUE(content, '$.tool_origin') AS tool_origin",
        ),
      EventType.TOOL_COMPLETED to
        listOf(
          "JSON_VALUE(content, '$.tool') AS tool_name",
          "JSON_QUERY(content, '$.result') AS tool_result",
          "JSON_VALUE(content, '$.tool_origin') AS tool_origin",
          "CAST(JSON_VALUE(latency_ms, '$.total_ms') AS INT64) AS total_ms",
          "JSON_VALUE(attributes, '$.adk.pause_kind') AS pause_kind",
          "JSON_VALUE(attributes, '$.adk.function_call_id') AS function_call_id",
        ),
      EventType.TOOL_PAUSED to
        listOf(
          "JSON_VALUE(content, '$.tool') AS tool_name",
          "JSON_QUERY(content, '$.args') AS tool_args",
          "JSON_VALUE(attributes, '$.adk.pause_kind') AS pause_kind",
          "JSON_VALUE(attributes, '$.adk.function_call_id') AS function_call_id",
        ),
      EventType.TOOL_ERROR to
        listOf(
          "JSON_VALUE(content, '$.tool') AS tool_name",
          "JSON_QUERY(content, '$.args') AS tool_args",
          "JSON_VALUE(content, '$.tool_origin') AS tool_origin",
          "CAST(JSON_VALUE(latency_ms, '$.total_ms') AS INT64) AS total_ms",
        ),
      EventType.AGENT_STARTING to
        listOf(
          "COALESCE(JSON_VALUE(content, '$.text_summary')," +
            " IF(REGEXP_CONTAINS(JSON_VALUE(content, '$')," +
            " r'^(?:<(?:function|bound method) |<.+ at" +
            " 0x[0-9a-fA-F]+>|functools\\.partial\\()'), NULL," +
            " JSON_VALUE(content, '$'))) AS agent_instruction",
          "JSON_VALUE(attributes, '$.instruction_source') AS instruction_source",
          "JSON_VALUE(attributes, '$.static_instruction') AS static_instruction",
          "JSON_VALUE(attributes, '$.agent_description') AS agent_description",
          "JSON_VALUE(attributes, '$.model') AS model",
          "JSON_QUERY(attributes, '$.sub_agents') AS sub_agents",
        ),
      EventType.AGENT_COMPLETED to
        listOf("CAST(JSON_VALUE(latency_ms, '$.total_ms') AS INT64) AS total_ms"),
      EventType.INVOCATION_STARTING to emptyList(),
      EventType.INVOCATION_COMPLETED to emptyList(),
      EventType.INVOCATION_ERROR to
        listOf("JSON_VALUE(content, '$.error_traceback') AS error_traceback"),
      EventType.STATE_DELTA to listOf("JSON_QUERY(attributes, '$.state_delta') AS state_delta"),
      EventType.HITL_CREDENTIAL_REQUEST to
        listOf(
          "JSON_VALUE(content, '$.tool') AS tool_name",
          "JSON_QUERY(content, '$.args') AS tool_args",
        ),
      EventType.HITL_CONFIRMATION_REQUEST to
        listOf(
          "JSON_VALUE(content, '$.tool') AS tool_name",
          "JSON_QUERY(content, '$.args') AS tool_args",
        ),
      EventType.HITL_INPUT_REQUEST to
        listOf(
          "JSON_VALUE(content, '$.tool') AS tool_name",
          "JSON_QUERY(content, '$.args') AS tool_args",
        ),
      EventType.A2A_INTERACTION to
        listOf(
          "content AS response_content",
          "JSON_VALUE(attributes, '$.a2a_metadata.\"$A2A_TASK_ID_KEY\"') AS a2a_task_id",
          "JSON_VALUE(attributes, '$.a2a_metadata.\"$A2A_CONTEXT_ID_KEY\"') AS a2a_context_id",
          "JSON_QUERY(attributes, '$.a2a_metadata.\"$A2A_REQUEST_KEY\"') AS a2a_request",
          "JSON_QUERY(attributes, '$.a2a_metadata.\"$A2A_RESPONSE_KEY\"') AS a2a_response",
        ),
      EventType.AGENT_RESPONSE to
        listOf(
          "JSON_VALUE(content, '$.response') AS response_text",
          "JSON_VALUE(attributes, '$.source_event_id') AS source_event_id",
          "JSON_VALUE(attributes, '$.source_event_author') AS source_event_author",
          "JSON_VALUE(attributes, '$.source_event_branch') AS source_event_branch",
        ),
    )

  /**
   * Returns the framework/version string identifying this plugin as the writer of a row.
   *
   * Not yet used: it belongs on the Storage Write API's `AppendRowsRequest.trace_id`, and the
   * legacy `insertAll` path this plugin writes through has no equivalent field.
   */
  fun getVersionHeaderValue(): String = "$FRAMEWORK_PREFIX/$VERSION"

  private val SAFE_IDENTIFIER = Regex("""[A-Za-z0-9_-]+""")

  private fun isSafeIdentifier(id: String): Boolean = SAFE_IDENTIFIER.matches(id)

  /**
   * Creates or replaces one view per event type; a view that fails is logged and skipped.
   *
   * [throwIfCancelled] runs before each view, so a cancelled caller creates no further views.
   */
  fun createAnalyticsViews(
    bigQuery: BigQuery,
    config: BigQueryLoggerConfig,
    throwIfCancelled: () -> Unit = {},
  ) {
    if (
      !isSafeIdentifier(config.projectId) ||
        !isSafeIdentifier(config.datasetId) ||
        !isSafeIdentifier(config.tableName) ||
        !isSafeIdentifier(config.viewPrefix)
    ) {
      logger.warn {
        "Skipping analytics view creation: project/dataset/table/viewPrefix contains unsafe " +
          "characters."
      }
      return
    }
    for ((eventType, extraCols) in EVENT_VIEW_DEFS) {
      throwIfCancelled()
      val viewName = "${config.viewPrefix}_${eventType.lowercase(Locale.ROOT)}"
      val columns = (VIEW_COMMON_COLUMNS + extraCols).joinToString(",\n  ")
      val sql =
        """
        CREATE OR REPLACE VIEW `${config.projectId}.${config.datasetId}.$viewName` AS
        SELECT
          $columns
        FROM
          `${config.projectId}.${config.datasetId}.${config.tableName}`
        WHERE
          event_type = '$eventType'
        """
          .trimIndent()

      try {
        val queryConfig = QueryJobConfiguration.newBuilder(sql).build()
        bigQuery.query(queryConfig)
      } catch (e: BigQueryException) {
        logger.warn(e) { "Failed to create or update view $viewName" }
      } catch (e: JobException) {
        logger.warn(e) { "Failed to create or update view $viewName" }
      }
    }
  }

  fun maybeUpgradeSchema(bigQuery: BigQuery, existingTable: Table): Boolean {
    val currentDefinition = existingTable.getDefinition<StandardTableDefinition>() ?: return true
    val existingFields = currentDefinition.schema?.fields
    val desiredFields = BigQuerySchema.getEventsSchema().fields
    val diff = diffFields(existingFields, desiredFields)

    if (diff.newFields.isEmpty() && diff.updatedRecordFields.isEmpty()) {
      return true
    }

    logger.info {
      "Auto-upgrading table ${existingTable.tableId}: new columns " +
        "${diff.newFields.map { it.name }}, updated RECORD fields " +
        "${diff.updatedRecordFields.map { it.name }}"
    }

    try {
      val labels = existingTable.labels.orEmpty().toMutableMap()
      // A newer writer may have labeled the table already; never move the label backwards.
      val labeledVersion = labels[BigQuerySchema.SCHEMA_VERSION_LABEL_KEY]?.toIntOrNull()
      if (labeledVersion == null || labeledVersion < BigQuerySchema.SCHEMA_VERSION.toInt()) {
        labels[BigQuerySchema.SCHEMA_VERSION_LABEL_KEY] = BigQuerySchema.SCHEMA_VERSION
      }

      val updatedSchema = Schema.of(diff.applyTo(existingFields))
      val updatedTable =
        existingTable
          .toBuilder()
          .setDefinition(currentDefinition.toBuilder().setSchema(updatedSchema).build())
          .setLabels(labels)
          .build()

      val unusedUpdated = bigQuery.update(updatedTable)
      return true
    } catch (e: BigQueryException) {
      logger.warn(e) { "Schema auto-upgrade failed for ${existingTable.tableId}" }
      return false
    }
  }

  private data class SchemaDiff(val newFields: List<Field>, val updatedRecordFields: List<Field>)

  private fun SchemaDiff.applyTo(existing: FieldList?): List<Field> {
    val updated = updatedRecordFields.associateBy { it.columnKey() }
    return existing.orEmpty().map { updated[it.columnKey()] ?: it } + newFields
  }

  // BigQuery column names are case-insensitive, so fields are matched by their lowercase names.
  private fun Field.columnKey(): String = name.lowercase(Locale.ROOT)

  private fun diffFields(existing: FieldList?, desired: FieldList): SchemaDiff {
    val existingByName = existing?.associateBy { it.columnKey() } ?: emptyMap()
    val added = mutableListOf<Field>()
    val updatedRecords = mutableListOf<Field>()

    for (desiredField in desired) {
      val existingField = existingByName[desiredField.columnKey()]
      if (existingField == null) {
        added.add(desiredField)
        continue
      }
      warnOnIncompatibleDrift(existingField, desiredField)
      if (
        desiredField.type.standardType == StandardSQLTypeName.STRUCT &&
          existingField.type.standardType == StandardSQLTypeName.STRUCT &&
          desiredField.subFields != null
      ) {
        val subDiff = diffFields(existingField.subFields, desiredField.subFields)
        if (subDiff.newFields.isNotEmpty() || subDiff.updatedRecordFields.isNotEmpty()) {
          val mergedSubFields = FieldList.of(subDiff.applyTo(existingField.subFields))
          updatedRecords.add(
            existingField.toBuilder().setType(StandardSQLTypeName.STRUCT, mergedSubFields).build()
          )
        }
      }
    }
    return SchemaDiff(added, updatedRecords)
  }

  private fun warnOnIncompatibleDrift(existingField: Field, desiredField: Field) {
    val typeDrift = desiredField.type.standardType != existingField.type.standardType
    val modeDrift = normalizeMode(existingField.mode) != normalizeMode(desiredField.mode)
    if (typeDrift || modeDrift) {
      logger.warn {
        val existing = "${existingField.type.standardType}/${normalizeMode(existingField.mode)}"
        val desired = "${desiredField.type.standardType}/${normalizeMode(desiredField.mode)}"
        "Incompatible schema drift on column '${desiredField.name}': table has $existing but " +
          "plugin expects $desired."
      }
    }
  }

  private fun normalizeMode(mode: Field.Mode?): Field.Mode = mode ?: Field.Mode.NULLABLE
}
