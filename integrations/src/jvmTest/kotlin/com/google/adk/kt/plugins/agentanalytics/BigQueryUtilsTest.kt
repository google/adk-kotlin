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

import com.google.cloud.bigquery.BigQuery
import com.google.cloud.bigquery.BigQueryException
import com.google.cloud.bigquery.Field
import com.google.cloud.bigquery.FieldList
import com.google.cloud.bigquery.QueryJobConfiguration
import com.google.cloud.bigquery.Schema
import com.google.cloud.bigquery.StandardSQLTypeName
import com.google.cloud.bigquery.StandardTableDefinition
import com.google.cloud.bigquery.Table
import com.google.cloud.bigquery.TableDefinition
import com.google.cloud.bigquery.TableId
import com.google.cloud.bigquery.TableInfo
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import java.util.logging.SimpleFormatter
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeast
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class BigQueryUtilsTest {

  private val mockBigQuery = mock<BigQuery>()

  private val config =
    BigQueryLoggerConfig(
      projectId = "test-project",
      datasetId = "test_dataset",
      tableName = "test_table",
    )

  private fun capturedViewQueries(): List<String> {
    val captor = argumentCaptor<QueryJobConfiguration>()
    verify(mockBigQuery, atLeast(1)).query(captor.capture())
    return captor.allValues.map { it.query }
  }

  @Test
  fun createAnalyticsViews_createsOneFilteredViewPerEventType() {
    BigQueryUtils.createAnalyticsViews(mockBigQuery, config)

    val queries = capturedViewQueries()
    val viewNames = queries.map {
      Regex("""VIEW `test-project\.test_dataset\.(\w+)`""").find(it)!!.groupValues[1]
    }
    assertEquals(viewNames.size, viewNames.toSet().size)
    assertTrue("v_llm_request" in viewNames)
    assertTrue("v_tool_completed" in viewNames)
    for (query in queries) {
      assertTrue(Regex("""FROM\s+`test-project\.test_dataset\.test_table`""") in query, query)
      assertTrue("event_id" in query, query)
    }
    val llmRequest = queries.single { "v_llm_request`" in it }
    assertTrue("event_type = '${EventType.LLM_REQUEST}'" in llmRequest)
    assertTrue("JSON_VALUE(attributes, '$.model') AS model" in llmRequest)
  }

  @Test
  fun createAnalyticsViews_usesConfiguredViewPrefix() {
    BigQueryUtils.createAnalyticsViews(mockBigQuery, config.copy(viewPrefix = "adk"))

    val queries = capturedViewQueries()
    assertTrue(queries.all { "`test-project.test_dataset.adk_" in it })
  }

  @Test
  fun createAnalyticsViews_unsafeIdentifier_skipsAllViews() {
    for (unsafe in
      listOf(
        config.copy(projectId = "p`; DROP TABLE x; --"),
        config.copy(datasetId = "d.s"),
        config.copy(tableName = "t name"),
        config.copy(viewPrefix = "v`"),
      )) {
      BigQueryUtils.createAnalyticsViews(mockBigQuery, unsafe)
    }

    verify(mockBigQuery, never()).query(any<QueryJobConfiguration>())
  }

  @Test
  fun createAnalyticsViews_queryFailure_continuesWithRemainingViews() {
    whenever(mockBigQuery.query(any<QueryJobConfiguration>()))
      .thenThrow(BigQueryException(500, "boom"))

    BigQueryUtils.createAnalyticsViews(mockBigQuery, config)

    val queries = capturedViewQueries()
    assertTrue(queries.size > 1)
    assertTrue(queries.any { "v_tool_completed`" in it })
  }

  @Test
  fun createAnalyticsViews_interrupted_stopsAndPropagatesWithoutReinterrupting() {
    whenever(mockBigQuery.query(any<QueryJobConfiguration>())).thenThrow(InterruptedException())

    assertFailsWith<InterruptedException> {
      BigQueryUtils.createAnalyticsViews(mockBigQuery, config)
    }

    assertFalse(Thread.interrupted())
    assertEquals(1, capturedViewQueries().size)
  }

  @Test
  fun createAnalyticsViews_cancelled_stopsBeforeTheNextView() {
    var cancelled = false
    whenever(mockBigQuery.query(any<QueryJobConfiguration>())).thenAnswer {
      cancelled = true
      null
    }

    assertFailsWith<CancellationException> {
      BigQueryUtils.createAnalyticsViews(mockBigQuery, config) {
        if (cancelled) throw CancellationException()
      }
    }

    assertEquals(1, capturedViewQueries().size)
  }

  @Test
  fun maybeUpgradeSchema_noDefinition_returnsTrueWithoutUpdate() {
    val table = mock<Table>()

    assertTrue(BigQueryUtils.maybeUpgradeSchema(mockBigQuery, table))
    verify(mockBigQuery, never()).update(any<TableInfo>())
  }

  @Test
  fun maybeUpgradeSchema_schemaUpToDate_returnsTrueWithoutUpdate() {
    val table = tableWith(desiredFields())

    assertTrue(BigQueryUtils.maybeUpgradeSchema(mockBigQuery, table.table))
    verify(mockBigQuery, never()).update(any<TableInfo>())
  }

  @Test
  fun maybeUpgradeSchema_missingColumn_appendsItAndKeepsExistingColumns() {
    val customField = Field.of("custom_col", StandardSQLTypeName.STRING)
    val existing = desiredFields().filter { it.name != "event_id" } + customField
    val table = tableWith(existing, labels = mapOf("team" to "adk"))

    assertTrue(BigQueryUtils.maybeUpgradeSchema(mockBigQuery, table.table))

    verify(mockBigQuery).update(table.updated)
    val merged = table.capturedSchema().fields.map { it.name }
    assertEquals(existing.map { it.name } + "event_id", merged)
    assertEquals(
      mapOf(
        "team" to "adk",
        BigQuerySchema.SCHEMA_VERSION_LABEL_KEY to BigQuerySchema.SCHEMA_VERSION,
      ),
      table.capturedLabels(),
    )
  }

  @Test
  fun maybeUpgradeSchema_missingNestedField_mergesItIntoTheRecordInPlace() {
    val existing =
      desiredFields().map { field ->
        if (field.name != "content_parts") {
          field
        } else {
          val subFields = field.subFields.filter { it.name != "storage_mode" }
          field.toBuilder().setType(StandardSQLTypeName.STRUCT, FieldList.of(subFields)).build()
        }
      }
    val table = tableWith(existing)

    assertTrue(BigQueryUtils.maybeUpgradeSchema(mockBigQuery, table.table))

    val merged = table.capturedSchema().fields
    assertEquals(existing.map { it.name }, merged.map { it.name })
    val contentParts = merged.get("content_parts")
    assertEquals(Field.Mode.REPEATED, contentParts.mode)
    assertEquals("storage_mode", contentParts.subFields.last().name)
    assertEquals(
      desiredFields().single { it.name == "content_parts" }.subFields.map { it.name }.toSet(),
      contentParts.subFields.map { it.name }.toSet(),
    )
  }

  @Test
  fun maybeUpgradeSchema_columnsInAnotherCase_areMatchedAndKeepTheirCase() {
    fun upper(field: Field): Field {
      val builder = field.toBuilder().setName(field.name.uppercase())
      val subFields = field.subFields ?: return builder.build()
      return builder
        .setType(StandardSQLTypeName.STRUCT, FieldList.of(subFields.map(::upper)))
        .build()
    }
    val existing = desiredFields().filter { it.name != "event_id" }.map(::upper)
    val table = tableWith(existing)

    assertTrue(BigQueryUtils.maybeUpgradeSchema(mockBigQuery, table.table))

    val merged = table.capturedSchema().fields
    assertEquals(existing.map { it.name } + "event_id", merged.map { it.name })
    assertEquals(existing.single { it.name == "CONTENT_PARTS" }, merged.get("CONTENT_PARTS"))
  }

  @Test
  fun maybeUpgradeSchema_updateFails_returnsFalse() {
    val table = tableWith(desiredFields().filter { it.name != "event_id" })
    whenever(mockBigQuery.update(any<TableInfo>())).thenThrow(BigQueryException(403, "denied"))

    assertFalse(BigQueryUtils.maybeUpgradeSchema(mockBigQuery, table.table))
  }

  @Test
  fun maybeUpgradeSchema_newerSchemaLabel_isKept() {
    val newer = mapOf(BigQuerySchema.SCHEMA_VERSION_LABEL_KEY to "99")
    val table = tableWith(desiredFields().filter { it.name != "event_id" }, labels = newer)

    assertTrue(BigQueryUtils.maybeUpgradeSchema(mockBigQuery, table.table))

    assertEquals(newer, table.capturedLabels())
  }

  @Test
  fun maybeUpgradeSchema_olderOrUnreadableSchemaLabel_isRaised() {
    for (label in listOf("1", "not-a-number")) {
      val labels = mapOf(BigQuerySchema.SCHEMA_VERSION_LABEL_KEY to label)
      val table = tableWith(desiredFields().filter { it.name != "event_id" }, labels = labels)

      assertTrue(BigQueryUtils.maybeUpgradeSchema(mockBigQuery, table.table))

      assertEquals(
        mapOf(BigQuerySchema.SCHEMA_VERSION_LABEL_KEY to BigQuerySchema.SCHEMA_VERSION),
        table.capturedLabels(),
      )
    }
  }

  @Test
  fun maybeUpgradeSchema_incompatibleColumns_warnsAndLeavesThemAlone() {
    val drifted =
      desiredFields().map { field ->
        when (field.name) {
          "event_type" -> field.toBuilder().setType(StandardSQLTypeName.INT64).build()
          "agent" -> field.toBuilder().setMode(Field.Mode.REPEATED).build()
          else -> field
        }
      }
    val table = tableWith(drifted)

    val warnings = warningsDuring {
      assertTrue(BigQueryUtils.maybeUpgradeSchema(mockBigQuery, table.table))
    }

    assertEquals(2, warnings.size)
    assertTrue(warnings.any { "'event_type'" in it })
    assertTrue(warnings.any { "'agent'" in it })
    verify(mockBigQuery, never()).update(any<TableInfo>())
  }

  @Test
  fun createAnalyticsViews_readTheKeysThePluginWrites() {
    BigQueryUtils.createAnalyticsViews(mockBigQuery, config)

    val queries = capturedViewQueries()
    for ((view, column) in
      listOf(
        "v_agent_starting" to "AS agent_instruction",
        "v_agent_response" to "JSON_VALUE(content, '$.response') AS response_text",
        "v_tool_paused" to "JSON_VALUE(attributes, '$.adk.pause_kind') AS pause_kind",
        "v_tool_completed" to
          "JSON_VALUE(attributes, '$.adk.function_call_id') AS function_call_id",
        "v_invocation_error" to "JSON_VALUE(content, '$.error_traceback') AS error_traceback",
        "v_a2a_interaction" to "AS a2a_response",
      )) {
      assertTrue(column in queries.single { "$view`" in it }, view)
    }
  }

  private fun desiredFields(): List<Field> = BigQuerySchema.getEventsSchema().fields.toList()

  /** Returns the WARNING messages [BigQueryUtils] logs while [block] runs. */
  private fun warningsDuring(block: () -> Unit): List<String> {
    val messages = mutableListOf<String>()
    val handler =
      object : Handler() {
        override fun publish(record: LogRecord?) {
          if (record?.level == Level.WARNING) messages += SimpleFormatter().formatMessage(record)
        }

        override fun flush() {}

        override fun close() {}
      }
    val julLogger = Logger.getLogger(BigQueryUtils::class.java.name)
    julLogger.addHandler(handler)
    try {
      block()
    } finally {
      julLogger.removeHandler(handler)
    }
    return messages
  }

  /** A mocked table whose builder records the definition and labels passed to it. */
  private class MockedTable(val table: Table, val builder: Table.Builder, val updated: Table) {
    fun capturedSchema(): Schema {
      val captor = argumentCaptor<TableDefinition>()
      verify(builder).setDefinition(captor.capture())
      return (captor.firstValue as StandardTableDefinition).schema!!
    }

    fun capturedLabels(): Map<String, String> {
      val captor = argumentCaptor<Map<String, String>>()
      verify(builder).setLabels(captor.capture())
      return captor.firstValue
    }
  }

  private fun tableWith(fields: List<Field>, labels: Map<String, String>? = null): MockedTable {
    val table = mock<Table>()
    val builder = mock<Table.Builder>()
    val updated = mock<Table>()
    whenever(table.getDefinition<StandardTableDefinition>())
      .thenReturn(StandardTableDefinition.of(Schema.of(fields)))
    whenever(table.labels).thenReturn(labels)
    whenever(table.tableId).thenReturn(TableId.of("test-project", "test_dataset", "test_table"))
    whenever(table.toBuilder()).thenReturn(builder)
    whenever(builder.setDefinition(any())).thenReturn(builder)
    whenever(builder.setLabels(any())).thenReturn(builder)
    whenever(builder.build()).thenReturn(updated)
    return MockedTable(table, builder, updated)
  }
}
