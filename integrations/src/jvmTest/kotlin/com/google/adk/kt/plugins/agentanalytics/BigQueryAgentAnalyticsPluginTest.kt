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

import com.google.adk.kt.agents.CallbackContext
import com.google.adk.kt.callbacks.CallbackChoice
import com.google.adk.kt.events.Event
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.testing.DummyAgent
import com.google.adk.kt.testing.DummyTool
import com.google.adk.kt.testing.testInvocationContext
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.UsageMetadata
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

  private val plugin =
    BigQueryAgentAnalyticsPlugin(config, mockBigQuery, ioContext = Dispatchers.Unconfined)
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
  fun onUserMessage_logsUserMessageReceived(): Unit = runBlocking {
    whenever(mockBigQuery.insertAll(any<InsertAllRequest>())).thenReturn(mockInsertAllResponse)
    whenever(mockInsertAllResponse.hasErrors()).thenReturn(false)

    val userMessage = Content(role = "user", parts = listOf(Part(text = "Hello")))
    val result = plugin.onUserMessage(invocationContext, userMessage)

    assertEquals(userMessage, result)
    verify(mockBigQuery)
      .insertAll(
        check<InsertAllRequest> { request ->
          val row = request.rows.single().content
          assertEquals("USER_MESSAGE_RECEIVED", row["event_type"])
          assertEquals("test_agent", row["agent"])
        }
      )
  }

  @Test
  @Suppress("CheckReturnValue")
  fun beforeAgent_and_afterAgent_logsLifecycleEvents(): Unit = runBlocking {
    whenever(mockBigQuery.insertAll(any<InsertAllRequest>())).thenReturn(mockInsertAllResponse)
    whenever(mockInsertAllResponse.hasErrors()).thenReturn(false)

    val callbackContext = CallbackContext(invocationContext = invocationContext)
    plugin.beforeAgent(callbackContext)
    plugin.afterAgent(callbackContext)

    verify(mockBigQuery, times(2)).insertAll(any<InsertAllRequest>())
  }

  @Test
  @Suppress("CheckReturnValue")
  fun beforeModel_and_afterModel_logsModelEvents(): Unit = runBlocking {
    whenever(mockBigQuery.insertAll(any<InsertAllRequest>())).thenReturn(mockInsertAllResponse)
    whenever(mockInsertAllResponse.hasErrors()).thenReturn(false)

    val callbackContext = CallbackContext(invocationContext = invocationContext)
    val llmRequest = LlmRequest(contents = listOf(Content(parts = listOf(Part(text = "Prompt")))))
    val llmResponse =
      LlmResponse(
        content = Content(parts = listOf(Part(text = "Response"))),
        usageMetadata =
          UsageMetadata(promptTokenCount = 10, candidatesTokenCount = 20, totalTokenCount = 30),
      )

    plugin.beforeModel(callbackContext, llmRequest)
    plugin.afterModel(callbackContext, llmResponse)

    verify(mockBigQuery, times(2)).insertAll(any<InsertAllRequest>())
  }

  @Test
  @Suppress("CheckReturnValue")
  fun beforeTool_and_afterTool_logsToolEvents(): Unit = runBlocking {
    whenever(mockBigQuery.insertAll(any<InsertAllRequest>())).thenReturn(mockInsertAllResponse)
    whenever(mockInsertAllResponse.hasErrors()).thenReturn(false)

    val dummyTool = DummyTool("my_tool")
    val toolContext =
      ToolContext(invocationContext = invocationContext, functionCallId = "call-123")
    val args = mapOf<String, Any?>("param" to "val")
    val res = mapOf<String, Any?>("output" to "ok")

    plugin.beforeTool(toolContext, dummyTool, args)
    plugin.afterTool(toolContext, dummyTool, args, res)

    verify(mockBigQuery, times(2)).insertAll(any<InsertAllRequest>())
  }

  @Test
  @Suppress("CheckReturnValue")
  fun onToolError_logsToolError(): Unit = runBlocking {
    whenever(mockBigQuery.insertAll(any<InsertAllRequest>())).thenReturn(mockInsertAllResponse)
    whenever(mockInsertAllResponse.hasErrors()).thenReturn(false)

    val dummyTool = DummyTool("my_tool")
    val toolContext =
      ToolContext(invocationContext = invocationContext, functionCallId = "call-123")
    val args = mapOf<String, Any?>("param" to "val")
    val error = RuntimeException("Tool failed")

    plugin.onToolError(toolContext, dummyTool, args, error)

    verify(mockBigQuery)
      .insertAll(
        check<InsertAllRequest> { request ->
          val row = request.rows.single().content
          assertEquals("TOOL_ERROR", row["event_type"])
          assertEquals("ERROR", row["status"])
          assertEquals("Tool failed", row["error_message"])
        }
      )
  }

  @Test
  @Suppress("CheckReturnValue")
  fun onModelError_logsModelError(): Unit = runBlocking {
    whenever(mockBigQuery.insertAll(any<InsertAllRequest>())).thenReturn(mockInsertAllResponse)
    whenever(mockInsertAllResponse.hasErrors()).thenReturn(false)

    val callbackContext = CallbackContext(invocationContext = invocationContext)
    val llmRequest = LlmRequest()
    val error = RuntimeException("LLM failed")

    plugin.onModelError(callbackContext, llmRequest, error)

    verify(mockBigQuery)
      .insertAll(
        check<InsertAllRequest> { request ->
          val row = request.rows.single().content
          assertEquals("LLM_ERROR", row["event_type"])
          assertEquals("ERROR", row["status"])
          assertEquals("LLM failed", row["error_message"])
        }
      )
  }

  @Test
  @Suppress("CheckReturnValue")
  fun onEvent_stateDelta_logsStateDelta(): Unit = runBlocking {
    whenever(mockBigQuery.insertAll(any<InsertAllRequest>())).thenReturn(mockInsertAllResponse)
    whenever(mockInsertAllResponse.hasErrors()).thenReturn(false)

    val actions = EventActions(stateDelta = mutableMapOf("key" to "value"))
    val event = Event(author = "test_agent", actions = actions)

    plugin.onEvent(invocationContext, event)

    verify(mockBigQuery)
      .insertAll(
        check<InsertAllRequest> { request ->
          val row = request.rows.single().content
          assertEquals("STATE_DELTA", row["event_type"])
        }
      )
  }

  @Test
  fun redactTree_redactsSensitiveKeys() {
    val input =
      mapOf("api_key" to "secret-123", "password" to "pass-456", "normal_field" to "hello")
    val redacted = JsonFormatter.redactTree(input)
    assertEquals(JsonFormatter.REDACTED_MESSAGE, redacted.get("api_key").asText())
    assertEquals(JsonFormatter.REDACTED_MESSAGE, redacted.get("password").asText())
    assertEquals("hello", redacted.get("normal_field").asText())
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

    val timestampField = fields.find { it.name == "timestamp" }
    assertNotNull(timestampField)
    assertEquals(StandardSQLTypeName.TIMESTAMP, timestampField.type.standardType)
    assertEquals(Field.Mode.REQUIRED, timestampField.mode)

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
        ioContext = Dispatchers.Unconfined,
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
        ioContext = Dispatchers.Unconfined,
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

  @Test
  @Suppress("CheckReturnValue")
  fun onEvent_longRunningTool_logsToolPaused(): Unit = runBlocking {
    whenever(mockBigQuery.insertAll(any<InsertAllRequest>())).thenReturn(mockInsertAllResponse)
    whenever(mockInsertAllResponse.hasErrors()).thenReturn(false)

    val functionCall =
      FunctionCall(name = "my_lr_tool", args = mapOf("param" to "val"), id = "call-lr-1")
    val event =
      Event(
        author = "test_agent",
        content = Content(role = "model", parts = listOf(Part(functionCall = functionCall))),
        longRunningToolIds = setOf("call-lr-1"),
      )

    plugin.onEvent(invocationContext, event)

    verify(mockBigQuery)
      .insertAll(
        check<InsertAllRequest> { request ->
          val row = request.rows.single().content
          assertEquals("TOOL_PAUSED", row["event_type"])
          assertEquals("test_agent", row["agent"])
          val attributes = row["attributes"]?.toString() ?: ""
          kotlin.test.assertTrue(attributes.contains("call-lr-1"))
        }
      )
  }

  @Test
  @Suppress("CheckReturnValue")
  fun onEvent_finalResponse_logsAgentResponse(): Unit = runBlocking {
    whenever(mockBigQuery.insertAll(any<InsertAllRequest>())).thenReturn(mockInsertAllResponse)
    whenever(mockInsertAllResponse.hasErrors()).thenReturn(false)

    val event =
      Event(
        id = "event-123",
        author = "test_agent",
        content =
          Content(role = "model", parts = listOf(Part(text = "Final answer", thought = false))),
      )

    plugin.onEvent(invocationContext, event)

    verify(mockBigQuery)
      .insertAll(
        check<InsertAllRequest> { request ->
          val row = request.rows.single().content
          assertEquals("AGENT_RESPONSE", row["event_type"])
          assertEquals("test_agent", row["agent"])
          val attributes = row["attributes"]?.toString() ?: ""
          kotlin.test.assertTrue(attributes.contains("event-123"))
        }
      )
  }

  @Test
  @Suppress("CheckReturnValue")
  fun onEvent_hitlConfirmationRequest_logsHitlEvent(): Unit = runBlocking {
    whenever(mockBigQuery.insertAll(any<InsertAllRequest>())).thenReturn(mockInsertAllResponse)
    whenever(mockInsertAllResponse.hasErrors()).thenReturn(false)

    val functionCall =
      FunctionCall(name = "adk_request_confirmation", args = mapOf("prompt" to "confirm?"), id = "c1")
    val event =
      Event(
        author = "test_agent",
        content = Content(role = "model", parts = listOf(Part(functionCall = functionCall))),
      )

    plugin.onEvent(invocationContext, event)

    verify(mockBigQuery)
      .insertAll(
        check<InsertAllRequest> { request ->
          val row = request.rows.single().content
          assertEquals("HITL_CONFIRMATION_REQUEST", row["event_type"])
        }
      )
  }

  @Test
  @Suppress("CheckReturnValue")
  fun onEvent_hitlFunctionResponse_logsHitlCompleted(): Unit = runBlocking {
    whenever(mockBigQuery.insertAll(any<InsertAllRequest>())).thenReturn(mockInsertAllResponse)
    whenever(mockInsertAllResponse.hasErrors()).thenReturn(false)

    val functionResponse =
      FunctionResponse(
        name = "adk_request_confirmation",
        id = "c1",
        response = mapOf("confirmed" to true),
      )
    val event =
      Event(
        author = "test_agent",
        content = Content(role = "user", parts = listOf(Part(functionResponse = functionResponse))),
      )

    plugin.onEvent(invocationContext, event)

    verify(mockBigQuery)
      .insertAll(
        check<InsertAllRequest> { request ->
          val row = request.rows.single().content
          assertEquals("HITL_CONFIRMATION_REQUEST_COMPLETED", row["event_type"])
        }
      )
  }

  @Test
  @Suppress("CheckReturnValue")
  fun onEvent_a2aMetadata_logsA2AInteraction(): Unit = runBlocking {
    whenever(mockBigQuery.insertAll(any<InsertAllRequest>())).thenReturn(mockInsertAllResponse)
    whenever(mockInsertAllResponse.hasErrors()).thenReturn(false)

    val event =
      Event(
        author = "test_agent",
        customMetadata =
          mapOf(
            BigQueryUtils.A2A_REQUEST_KEY to mapOf("task" to "do something"),
            BigQueryUtils.A2A_RESPONSE_KEY to mapOf("status" to "completed"),
          ),
      )

    plugin.onEvent(invocationContext, event)

    verify(mockBigQuery)
      .insertAll(
        check<InsertAllRequest> { request ->
          val row = request.rows.single().content
          assertEquals("A2A_INTERACTION", row["event_type"])
        }
      )
  }
}
