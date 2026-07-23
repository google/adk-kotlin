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
import com.google.cloud.bigquery.InsertAllRequest
import com.google.cloud.bigquery.InsertAllResponse
import com.google.cloud.bigquery.Table
import com.google.cloud.bigquery.TableId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class BigQueryAgentAnalyticsPluginTest {

  private val mockBigQuery = mock<BigQuery>()
  private val mockTable = mock<Table>()
  private val mockInsertAllResponse = mock<InsertAllResponse>()

  private val config = BigQueryLoggerConfig(
    projectId = "test-project",
    datasetId = "test-dataset",
    tableName = "test-table"
  )

  private val plugin = BigQueryAgentAnalyticsPlugin(config, mockBigQuery)
  private val mockAgent = DummyAgent("test_agent")
  private val invocationContext = testInvocationContext(agent = mockAgent)

  @Test
  fun beforeRun_ensuresTableAndLogsEvent(): Unit = runBlocking {
    val tableId = TableId.of("test-dataset", "test-table")
    whenever(mockBigQuery.getTable(tableId)).thenReturn(mockTable)
    whenever(mockBigQuery.insertAll(any<InsertAllRequest>())).thenReturn(mockInsertAllResponse)
    whenever(mockInsertAllResponse.hasErrors()).thenReturn(false)

    val result = plugin.beforeRun(invocationContext)

    assertEquals(CallbackChoice.Continue(Unit), result)
    verify(mockBigQuery).getTable(tableId)
    verify(mockBigQuery).insertAll(any<InsertAllRequest>())
  }

  @Test
  fun afterRun_logsEvent(): Unit = runBlocking {
     whenever(mockBigQuery.insertAll(any<InsertAllRequest>())).thenReturn(mockInsertAllResponse)
     whenever(mockInsertAllResponse.hasErrors()).thenReturn(false)

     plugin.afterRun(invocationContext)

     verify(mockBigQuery).insertAll(any<InsertAllRequest>())
  }

  @Test
  fun ensureTableExistsOnce_calledOnlyOnce(): Unit = runBlocking {
    val tableId = TableId.of("test-dataset", "test-table")
    whenever(mockBigQuery.getTable(tableId)).thenReturn(mockTable)
    whenever(mockBigQuery.insertAll(any<InsertAllRequest>())).thenReturn(mockInsertAllResponse)
    whenever(mockInsertAllResponse.hasErrors()).thenReturn(false)

    val unused1 = plugin.beforeRun(invocationContext)
    val unused2 = plugin.beforeRun(invocationContext)

    verify(mockBigQuery, org.mockito.kotlin.times(1)).getTable(tableId)
  }
}

