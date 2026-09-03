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
import com.google.cloud.bigquery.QueryJobConfiguration
import com.google.cloud.bigquery.Schema
import com.google.cloud.bigquery.StandardSQLTypeName
import com.google.cloud.bigquery.StandardTableDefinition
import com.google.cloud.bigquery.Table
import java.util.Locale
import java.util.regex.Pattern

/** Utility for managing BigQuery schema upgrades and analytics views. */
internal object BigQueryUtils {

  private val logger = LoggerFactory.getLogger(BigQueryUtils::class)

  const val A2A_PREFIX = "a2a:"
  const val A2A_REQUEST_KEY = "a2a:request"
  const val A2A_RESPONSE_KEY = "a2a:response"
  const val A2A_TASK_ID_KEY = "a2a:task_id"
  const val A2A_CONTEXT_ID_KEY = "a2a:context_id"

  private const val FRAMEWORK_PREFIX = "google-adk-bq-logger-kotlin"
  private const val VERSION = "0.1.0"

  private val VIEW_COMMON_COLUMNS =
    listOf(
      "timestamp",
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
      "USER_MESSAGE_RECEIVED" to emptyList(),
      "LLM_REQUEST" to
        listOf(
          "JSON_VALUE(attributes, '$.model') AS model",
          "content AS request_content",
          "JSON_QUERY(attributes, '$.llm_config') AS llm_config",
          "JSON_QUERY(attributes, '$.tools') AS tools",
        ),
      "LLM_RESPONSE" to
        listOf(
          "JSON_QUERY(content, '$.response') AS response",
          "CAST(JSON_VALUE(content, '$.usage.prompt') AS INT64) AS usage_prompt_tokens",
          "CAST(JSON_VALUE(content, '$.usage.completion') AS INT64) AS usage_completion_tokens",
          "CAST(JSON_VALUE(content, '$.usage.total') AS INT64) AS usage_total_tokens",
          "CAST(JSON_VALUE(attributes, '$.usage_metadata.cached_content_token_count') AS INT64) AS usage_cached_tokens",
          "SAFE_DIVIDE(CAST(JSON_VALUE(attributes, '$.usage_metadata.cached_content_token_count') AS INT64), CAST(JSON_VALUE(content, '$.usage.prompt') AS INT64)) AS context_cache_hit_rate",
          "CAST(JSON_VALUE(latency_ms, '$.total_ms') AS INT64) AS total_ms",
          "CAST(JSON_VALUE(latency_ms, '$.time_to_first_token_ms') AS INT64) AS ttft_ms",
          "JSON_VALUE(attributes, '$.model_version') AS model_version",
          "JSON_QUERY(attributes, '$.usage_metadata') AS usage_metadata",
        ),
      "LLM_ERROR" to listOf("CAST(JSON_VALUE(latency_ms, '$.total_ms') AS INT64) AS total_ms"),
      "TOOL_STARTING" to
        listOf(
          "JSON_VALUE(content, '$.tool') AS tool_name",
          "JSON_QUERY(content, '$.args') AS tool_args",
          "JSON_VALUE(content, '$.tool_origin') AS tool_origin",
        ),
      "TOOL_COMPLETED" to
        listOf(
          "JSON_VALUE(content, '$.tool') AS tool_name",
          "JSON_QUERY(content, '$.result') AS tool_result",
          "JSON_VALUE(content, '$.tool_origin') AS tool_origin",
          "JSON_VALUE(attributes, '$.pause_kind') AS pause_kind",
          "JSON_VALUE(attributes, '$.function_call_id') AS function_call_id",
          "CAST(JSON_VALUE(latency_ms, '$.total_ms') AS INT64) AS total_ms",
        ),
      "TOOL_PAUSED" to
        listOf(
          "JSON_VALUE(content, '$.tool') AS tool_name",
          "JSON_QUERY(content, '$.args') AS tool_args",
          "JSON_VALUE(attributes, '$.pause_kind') AS pause_kind",
          "JSON_VALUE(attributes, '$.function_call_id') AS function_call_id",
        ),
      "TOOL_ERROR" to
        listOf(
          "JSON_VALUE(content, '$.tool') AS tool_name",
          "JSON_QUERY(content, '$.args') AS tool_args",
          "JSON_VALUE(content, '$.tool_origin') AS tool_origin",
          "CAST(JSON_VALUE(latency_ms, '$.total_ms') AS INT64) AS total_ms",
        ),
      "AGENT_STARTING" to listOf("JSON_VALUE(content, '$.text_summary') AS agent_instruction"),
      "AGENT_COMPLETED" to
        listOf("CAST(JSON_VALUE(latency_ms, '$.total_ms') AS INT64) AS total_ms"),
      "INVOCATION_STARTING" to emptyList(),
      "INVOCATION_COMPLETED" to emptyList(),
      "STATE_DELTA" to listOf("JSON_QUERY(attributes, '$.state_delta') AS state_delta"),
      "HITL_CREDENTIAL_REQUEST" to
        listOf(
          "JSON_VALUE(content, '$.tool') AS tool_name",
          "JSON_QUERY(content, '$.args') AS tool_args",
        ),
      "HITL_CONFIRMATION_REQUEST" to
        listOf(
          "JSON_VALUE(content, '$.tool') AS tool_name",
          "JSON_QUERY(content, '$.args') AS tool_args",
        ),
      "HITL_INPUT_REQUEST" to
        listOf(
          "JSON_VALUE(content, '$.tool') AS tool_name",
          "JSON_QUERY(content, '$.args') AS tool_args",
        ),
      "A2A_INTERACTION" to
        listOf(
          "content AS response_content",
          "JSON_VALUE(attributes, '$.a2a_metadata.\"$A2A_TASK_ID_KEY\"') AS a2a_task_id",
          "JSON_VALUE(attributes, '$.a2a_metadata.\"$A2A_CONTEXT_ID_KEY\"') AS a2a_context_id",
          "JSON_QUERY(attributes, '$.a2a_metadata.\"$A2A_REQUEST_KEY\"') AS a2a_request",
        ),
      "AGENT_RESPONSE" to
        listOf(
          "JSON_VALUE(content, '$.text_summary') AS text_summary",
          "JSON_VALUE(attributes, '$.source_event_id') AS source_event_id",
          "JSON_VALUE(attributes, '$.source_event_author') AS source_event_author",
          "JSON_VALUE(attributes, '$.source_event_branch') AS source_event_branch",
        ),
    )

  fun getVersionHeaderValue(): String = "$FRAMEWORK_PREFIX/$VERSION"

  private val SAFE_IDENTIFIER = Pattern.compile("[A-Za-z0-9_\\-]+")

  fun isSafeIdentifier(id: String?): Boolean {
    return id != null && SAFE_IDENTIFIER.matcher(id).matches()
  }

  fun createAnalyticsViews(bigQuery: BigQuery, config: BigQueryLoggerConfig) {
    if (
      !isSafeIdentifier(config.projectId) ||
        !isSafeIdentifier(config.datasetId) ||
        !isSafeIdentifier(config.tableName) ||
        !isSafeIdentifier(config.viewPrefix)
    ) {
      logger.warn {
        "Skipping analytics view creation: project/dataset/table/viewPrefix contains unsafe characters."
      }
      return
    }
    for ((eventType, extraCols) in EVENT_VIEW_DEFS) {
      val viewName = "${config.viewPrefix}_${eventType.lowercase(Locale.ROOT)}"
      val allCols = VIEW_COMMON_COLUMNS + extraCols
      val columns = allCols.joinToString(",\n  ")
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
      } catch (e: Exception) {
        logger.warn(e) { "Failed to create or update view $viewName" }
      }
    }
  }

  fun maybeUpgradeSchema(bigQuery: BigQuery, existingTable: Table): Boolean {
    val currentDefinition = existingTable.getDefinition<StandardTableDefinition>() ?: return true
    val existingFields = currentDefinition.schema?.fields
    val desiredFields = BigQuerySchema.getEventsSchema().fields
    val diff = schemaFieldsMatch(existingFields, desiredFields)

    if (diff.newTopLevelFields.isEmpty() && diff.updatedRecordFields.isEmpty()) {
      return true
    }

    val updatedFieldsByName = diff.updatedRecordFields.associateBy { it.name }
    val mergedFields = mutableListOf<Field>()
    if (existingFields != null) {
      for (f in existingFields) {
        if (updatedFieldsByName.containsKey(f.name)) {
          mergedFields.add(updatedFieldsByName.getValue(f.name))
        } else {
          mergedFields.add(f)
        }
      }
    }
    mergedFields.addAll(diff.newTopLevelFields)

    logger.info {
      "Auto-upgrading table ${existingTable.tableId}: new columns ${diff.newTopLevelFields.map { it.name }}, updated RECORD fields ${diff.updatedRecordFields.map { it.name }}"
    }

    try {
      val labels = HashMap(existingTable.labels ?: emptyMap())
      labels[BigQuerySchema.SCHEMA_VERSION_LABEL_KEY] = BigQuerySchema.SCHEMA_VERSION

      val updatedTable =
        existingTable
          .toBuilder()
          .setDefinition(currentDefinition.toBuilder().setSchema(Schema.of(mergedFields)).build())
          .setLabels(labels)
          .build()

      val unusedUpdated = bigQuery.update(updatedTable)
      return true
    } catch (e: BigQueryException) {
      logger.warn(e) { "Schema auto-upgrade failed for ${existingTable.tableId}" }
      return false
    }
  }

  private data class SchemaDiff(
    val newTopLevelFields: List<Field>,
    val updatedRecordFields: List<Field>,
  )

  private fun schemaFieldsMatch(existing: FieldList?, desired: FieldList): SchemaDiff {
    val existingByName = existing?.associateBy { it.name } ?: emptyMap()
    val newFields = mutableListOf<Field>()
    val updatedRecords = mutableListOf<Field>()

    for (desiredField in desired) {
      val existingField = existingByName[desiredField.name]
      if (existingField == null) {
        newFields.add(desiredField)
      } else if (
        desiredField.type.standardType == StandardSQLTypeName.STRUCT &&
          existingField.type.standardType == StandardSQLTypeName.STRUCT &&
          desiredField.subFields != null
      ) {
        warnOnIncompatibleDrift(existingField, desiredField)
        val subDiff = schemaFieldsMatch(existingField.subFields, desiredField.subFields)
        if (subDiff.newTopLevelFields.isNotEmpty() || subDiff.updatedRecordFields.isNotEmpty()) {
          val mergedSub = ArrayList(existingField.subFields)
          val updatedSubByName = subDiff.updatedRecordFields.associateBy { it.name }
          for (i in mergedSub.indices) {
            val f = mergedSub[i]
            if (updatedSubByName.containsKey(f.name)) {
              mergedSub[i] = updatedSubByName.getValue(f.name)
            }
          }
          mergedSub.addAll(subDiff.newTopLevelFields)
          updatedRecords.add(
            existingField
              .toBuilder()
              .setType(StandardSQLTypeName.STRUCT, FieldList.of(mergedSub))
              .build()
          )
        }
      } else {
        warnOnIncompatibleDrift(existingField, desiredField)
      }
    }
    return SchemaDiff(newFields, updatedRecords)
  }

  private fun warnOnIncompatibleDrift(existingField: Field, desiredField: Field) {
    val typeDrift = desiredField.type.standardType != existingField.type.standardType
    val modeDrift = normalizeMode(existingField.mode) != normalizeMode(desiredField.mode)
    if (typeDrift || modeDrift) {
      logger.warn {
        "Incompatible schema drift on column '${desiredField.name}': table has ${existingField.type.standardType}/${normalizeMode(existingField.mode)} but plugin expects ${desiredField.type.standardType}/${normalizeMode(desiredField.mode)}."
      }
    }
  }

  private fun normalizeMode(mode: Field.Mode?): Field.Mode = mode ?: Field.Mode.NULLABLE
}
