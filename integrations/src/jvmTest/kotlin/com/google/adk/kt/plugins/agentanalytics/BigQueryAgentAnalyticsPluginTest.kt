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

import com.google.adk.kt.callbacks.CallbackChoice
import com.google.adk.kt.testing.DummyAgent
import com.google.adk.kt.testing.testInvocationContext
import com.google.cloud.bigquery.BigQuery
import com.google.cloud.bigquery.Field
import com.google.cloud.bigquery.InsertAllRequest
import com.google.cloud.bigquery.InsertAllResponse
import com.google.cloud.bigquery.StandardSQLTypeName
import com.google.cloud.bigquery.StandardTableDefinition
import com.google.cloud.bigquery.Table
import com.google.cloud.bigquery.TableId
import com.google.cloud.bigquery.TableInfo
import com.google.cloud.bigquery.TimePartitioning
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.mockito.kotlin.any
import org.mockito.kotlin.check
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class BigQueryAgentAnalyticsPluginTest {

  private val mockBigQuery = mock<BigQuery>()
  private val mockTable = mock<Table>()
  private val mockInsertAllResponse = mock<InsertAllResponse>()

  private val config =
    BigQueryLoggerConfig(
      projectId = "test-project",
      datasetId = "test-dataset",
      tableName = "test-table",
    )

  private val plugin = BigQueryAgentAnalyticsPlugin(config, mockBigQuery)
  private val mockAgent = DummyAgent("test_agent")
  private val invocationContext = testInvocationContext(agent = mockAgent)

  @Test
  fun beforeRun_ensuresTableAndLogsEvent(): Unit = runBlocking {
    val tableId = TableId.of("test-project", "test-dataset", "test-table")
    whenever(mockBigQuery.getTable(tableId)).thenReturn(mockTable)
    whenever(mockBigQuery.insertAll(any<InsertAllRequest>())).thenReturn(mockInsertAllResponse)
    whenever(mockInsertAllResponse.hasErrors()).thenReturn(false)

    val result = plugin.beforeRun(invocationContext)

    assertEquals(CallbackChoice.Continue(Unit), result)
    verify(mockBigQuery).getTable(tableId)
    verify(mockBigQuery)
      .insertAll(
        check<InsertAllRequest> { request ->
          assertEquals(TableId.of("test-project", "test-dataset", "test-table"), request.table)
          val row = request.rows.single().content
          assertEquals("INVOCATION_STARTING", row["event_type"])
          assertEquals("test_agent", row["agent"])
          assertEquals("test_user_id", row["user_id"])
          assertEquals("test-invocation-id", row["invocation_id"])
        }
      )
  }

  @Test
  fun beforeRun_createsTableWhenMissing(): Unit = runBlocking {
    val tableId = TableId.of("test-project", "test-dataset", "test-table")
    whenever(mockBigQuery.getTable(tableId)).thenReturn(null)
    whenever(mockBigQuery.insertAll(any<InsertAllRequest>())).thenReturn(mockInsertAllResponse)
    whenever(mockInsertAllResponse.hasErrors()).thenReturn(false)

    val result = plugin.beforeRun(invocationContext)

    assertEquals(CallbackChoice.Continue(Unit), result)
    verify(mockBigQuery).getTable(tableId)
    verify(mockBigQuery)
      .create(
        check<TableInfo> { tableInfo ->
          assertEquals(
            BigQuerySchema.SCHEMA_VERSION,
            tableInfo.labels[BigQuerySchema.SCHEMA_VERSION_LABEL_KEY],
          )
          val definition = tableInfo.getDefinition<StandardTableDefinition>()
          assertEquals(BigQuerySchema.getEventsSchema(), definition.schema)
          assertEquals("timestamp", definition.timePartitioning?.field)
          assertEquals(TimePartitioning.Type.DAY, definition.timePartitioning?.type)
          assertEquals(BigQuerySchema.getDefaultClusteringFields(), definition.clustering?.fields)
        }
      )
    verify(mockBigQuery)
      .insertAll(
        check<InsertAllRequest> { request ->
          assertEquals(TableId.of("test-project", "test-dataset", "test-table"), request.table)
          val row = request.rows.single().content
          assertEquals("INVOCATION_STARTING", row["event_type"])
          assertEquals("test_agent", row["agent"])
          assertEquals("test_user_id", row["user_id"])
          assertEquals("test-invocation-id", row["invocation_id"])
        }
      )
  }

  @Test
  fun afterRun_logsEvent(): Unit = runBlocking {
    whenever(mockBigQuery.insertAll(any<InsertAllRequest>())).thenReturn(mockInsertAllResponse)
    whenever(mockInsertAllResponse.hasErrors()).thenReturn(false)

    plugin.afterRun(invocationContext)

    verify(mockBigQuery)
      .insertAll(
        check<InsertAllRequest> { request ->
          assertEquals(TableId.of("test-project", "test-dataset", "test-table"), request.table)
          val row = request.rows.single().content
          assertEquals("INVOCATION_COMPLETED", row["event_type"])
          assertEquals("test_agent", row["agent"])
          assertEquals("test_user_id", row["user_id"])
          assertEquals("test-invocation-id", row["invocation_id"])
        }
      )
  }

  @Test
  @Suppress("CheckReturnValue")
  fun ensureTableExistsOnce_calledOnlyOnce(): Unit = runBlocking {
    val tableId = TableId.of("test-project", "test-dataset", "test-table")
    whenever(mockBigQuery.getTable(tableId)).thenReturn(mockTable)
    whenever(mockBigQuery.insertAll(any<InsertAllRequest>())).thenReturn(mockInsertAllResponse)
    whenever(mockInsertAllResponse.hasErrors()).thenReturn(false)

    plugin.beforeRun(invocationContext)
    plugin.beforeRun(invocationContext)

    verify(mockBigQuery, times(1)).getTable(tableId)
  }

  @Test
  @Suppress("CheckReturnValue")
  fun logEvent_whenDisabled_doesNothing(): Unit = runBlocking {
    val disabledConfig = config.copy(enabled = false)
    val disabledPlugin = BigQueryAgentAnalyticsPlugin(disabledConfig, mockBigQuery)

    disabledPlugin.beforeRun(invocationContext)

    verify(mockBigQuery, never()).insertAll(any<InsertAllRequest>())
  }

  @Test
  fun logEvent_insertAllHasErrors_failsSafe(): Unit = runBlocking {
    whenever(mockBigQuery.insertAll(any<InsertAllRequest>())).thenReturn(mockInsertAllResponse)
    whenever(mockInsertAllResponse.hasErrors()).thenReturn(true)

    plugin.afterRun(invocationContext)

    verify(mockBigQuery).insertAll(any<InsertAllRequest>())
  }

  @Test
  fun logEvent_insertAllThrows_failsSafe(): Unit = runBlocking {
    whenever(mockBigQuery.insertAll(any<InsertAllRequest>()))
      .thenThrow(RuntimeException("Bq Error"))

    plugin.afterRun(invocationContext)

    verify(mockBigQuery).insertAll(any<InsertAllRequest>())
  }

  @Test
  @Suppress("CheckReturnValue")
  fun beforeRun_getTableThrows_failsSafe(): Unit = runBlocking {
    val tableId = TableId.of("test-project", "test-dataset", "test-table")
    whenever(mockBigQuery.getTable(tableId)).thenThrow(RuntimeException("Bq Error"))
    whenever(mockBigQuery.insertAll(any<InsertAllRequest>())).thenReturn(mockInsertAllResponse)
    whenever(mockInsertAllResponse.hasErrors()).thenReturn(false)

    plugin.beforeRun(invocationContext)

    verify(mockBigQuery).getTable(tableId)
    verify(mockBigQuery, never()).insertAll(any<InsertAllRequest>())
  }

  @Test
  fun schema_hasCorrectFields() {
    val schema = BigQuerySchema.getEventsSchema()
    val fields = schema.fields

    assertEquals(16, fields.size)

    // Verify timestamp field
    val timestampField = fields.find { it.name == "timestamp" }
    assertNotNull(timestampField)
    assertEquals(StandardSQLTypeName.TIMESTAMP, timestampField.type.standardType)
    assertEquals(Field.Mode.REQUIRED, timestampField.mode)

    // Verify a subset of other fields to ensure types are correct
    val eventTypeField = fields.find { it.name == "event_type" }
    assertNotNull(eventTypeField)
    assertEquals(StandardSQLTypeName.STRING, eventTypeField.type.standardType)
    assertEquals(Field.Mode.NULLABLE, eventTypeField.mode)

    val contentField = fields.find { it.name == "content" }
    assertNotNull(contentField)
    assertEquals(StandardSQLTypeName.JSON, contentField.type.standardType)
    assertEquals(Field.Mode.NULLABLE, contentField.mode)

    val isTruncatedField = fields.find { it.name == "is_truncated" }
    assertNotNull(isTruncatedField)
    assertEquals(StandardSQLTypeName.BOOL, isTruncatedField.type.standardType)
    assertEquals(Field.Mode.NULLABLE, isTruncatedField.mode)
  }

  @Test
  @Suppress("CheckReturnValue")
  fun ensureTableExistsOnce_concurrentCalls_calledOnlyOnce(): Unit = runBlocking {
    val tableId = TableId.of("test-project", "test-dataset", "test-table")
    whenever(mockBigQuery.getTable(tableId)).thenReturn(mockTable)
    whenever(mockBigQuery.insertAll(any<InsertAllRequest>())).thenReturn(mockInsertAllResponse)
    whenever(mockInsertAllResponse.hasErrors()).thenReturn(false)

    val jobs = List(10) { launch(Dispatchers.Default) { plugin.beforeRun(invocationContext) } }
    jobs.joinAll()

    verify(mockBigQuery, times(1)).getTable(tableId)
  }

  @Test
  @Suppress("CheckReturnValue")
  fun ensureTableExistsOnce_retryInsideWindow_doesNotCallGetTable(): Unit = runBlocking {
    val testTimeSource = TestTimeSource()
    val testPlugin =
      BigQueryAgentAnalyticsPlugin(
        config = config,
        bigQuery = mockBigQuery,
        timeSource = testTimeSource,
      )
    val tableId = TableId.of("test-project", "test-dataset", "test-table")
    whenever(mockBigQuery.getTable(tableId)).thenThrow(RuntimeException("Bq Error"))

    testPlugin.beforeRun(invocationContext)

    clearInvocations(mockBigQuery)
    testTimeSource += 5.seconds

    testPlugin.beforeRun(invocationContext)

    verify(mockBigQuery, never()).getTable(any())
  }

  @Test
  @Suppress("CheckReturnValue")
  fun ensureTableExistsOnce_retryAfterWindow_callsGetTable(): Unit = runBlocking {
    val testTimeSource = TestTimeSource()
    val testPlugin =
      BigQueryAgentAnalyticsPlugin(
        config = config,
        bigQuery = mockBigQuery,
        timeSource = testTimeSource,
      )
    val tableId = TableId.of("test-project", "test-dataset", "test-table")
    whenever(mockBigQuery.getTable(tableId)).thenThrow(RuntimeException("Bq Error"))

    testPlugin.beforeRun(invocationContext)

    clearInvocations(mockBigQuery)
    testTimeSource += 11.seconds

    testPlugin.beforeRun(invocationContext)

    verify(mockBigQuery, times(1)).getTable(tableId)
  }
}
