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

import com.fasterxml.jackson.databind.JsonNode
import com.google.adk.kt.agents.CallbackContext
import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.callbacks.BeforeModelCallback
import com.google.adk.kt.callbacks.CallbackChoice
import com.google.adk.kt.events.Event
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.models.CacheMetadata
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.Model
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.testing.DummyAgent
import com.google.adk.kt.testing.DummyTool
import com.google.adk.kt.testing.testInvocationContext
import com.google.adk.kt.tools.AgentTool
import com.google.adk.kt.tools.FunctionTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FinishReason
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.GenerateContentConfig
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Schema as AdkSchema
import com.google.adk.kt.types.Tool
import com.google.adk.kt.types.Type
import com.google.adk.kt.types.UsageMetadata
import com.google.cloud.bigquery.BigQuery
import com.google.cloud.bigquery.BigQueryException
import com.google.cloud.bigquery.Field
import com.google.cloud.bigquery.InsertAllRequest
import com.google.cloud.bigquery.InsertAllResponse
import com.google.cloud.bigquery.QueryJobConfiguration
import com.google.cloud.bigquery.Schema
import com.google.cloud.bigquery.StandardSQLTypeName
import com.google.cloud.bigquery.StandardTableDefinition
import com.google.cloud.bigquery.Table
import com.google.cloud.bigquery.TableId
import com.google.cloud.bigquery.TableInfo
import com.google.cloud.bigquery.TableResult
import com.google.cloud.bigquery.TimePartitioning
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger
import java.util.logging.SimpleFormatter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
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
          assertEquals("OK", row["status"])
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
    stubInsertAll()

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
    stubInsertAll()

    val callbackContext = CallbackContext(invocationContext = invocationContext)
    plugin.beforeAgent(callbackContext)
    plugin.afterAgent(callbackContext)

    val rows = capturedRows()
    assertEquals(listOf<Any?>("AGENT_STARTING", "AGENT_COMPLETED"), rows.map { it["event_type"] })
    assertNull(rows.ofType("AGENT_COMPLETED")["content"])
  }

  @Test
  @Suppress("CheckReturnValue")
  fun beforeModel_and_afterModel_logsModelEvents(): Unit = runBlocking {
    stubInsertAll()

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

    val rows = capturedRows()
    val request = rows.ofType("LLM_REQUEST")
    val response = rows.ofType("LLM_RESPONSE")
    assertEquals("Prompt", contentOf(request).at("/prompt/0/content").asText())
    assertEquals("Response", contentOf(response).at("/response/text_summary").asText())
    assertEquals(30, contentOf(response).at("/usage/total").asInt())
    assertEquals(request["span_id"], response["span_id"])
  }

  @Test
  @Suppress("CheckReturnValue")
  fun beforeTool_and_afterTool_logsToolEvents(): Unit = runBlocking {
    stubInsertAll()

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
    stubInsertAll()

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
    stubInsertAll()

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
  fun onEvent_stateDelta_logsOnlyTheDelta(): Unit = runBlocking {
    stubInsertAll()
    val actions = EventActions(stateDelta = mutableMapOf("key" to "value"))
    val event = Event(author = "test_agent", content = modelText("answer"), actions = actions)

    plugin.onEvent(invocationContext, event)

    val row = capturedRows().ofType("STATE_DELTA")
    assertNull(row["content"])
    val attributes = attributesOf(row)
    assertEquals("value", attributes.at("/state_delta/key").asText())
    assertFalse(attributes.has("author"))
    assertEquals(event.id, attributes.at("/adk/source_event_id").asText())
  }

  @Test
  @Suppress("CheckReturnValue")
  fun logEvent_stampsEventIdAndAdkEnvelope(): Unit = runBlocking {
    whenever(mockBigQuery.getTable(any<TableId>())).thenReturn(mockTable)
    stubInsertAll()

    plugin.beforeRun(invocationContext)

    verify(mockBigQuery)
      .insertAll(
        check<InsertAllRequest> { request ->
          val row = request.rows.single().content
          assertTrue(Regex("[0-9a-f]{32}").matches(row["event_id"] as String))
          val adk = attributesOf(row).get(BigQuerySchema.ADK_ATTRIBUTE_KEY)
          assertEquals(
            BigQuerySchema.ADK_ENVELOPE_SCHEMA_VERSION,
            adk.get("schema_version").asText(),
          )
          assertEquals("test_app_name", adk.get("app_name").asText())
          assertFalse(adk.has("source_event_id"))
          assertFalse(adk.has("branch"))
        }
      )
  }

  @Test
  @Suppress("CheckReturnValue")
  fun logEvent_assignsADistinctEventIdPerRow(): Unit = runBlocking {
    whenever(mockBigQuery.getTable(any<TableId>())).thenReturn(mockTable)
    stubInsertAll()

    val eventIds = mutableListOf<String>()
    plugin.beforeRun(invocationContext)
    plugin.beforeRun(invocationContext)

    verify(mockBigQuery, times(2))
      .insertAll(
        check<InsertAllRequest> { request ->
          eventIds.add(request.rows.single().content["event_id"] as String)
        }
      )
    assertEquals(2, eventIds.toSet().size)
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

    assertEquals(17, fields.size)

    // Verify timestamp field
    val timestampField = fields.find { it.name == "timestamp" }
    assertNotNull(timestampField)
    assertEquals(StandardSQLTypeName.TIMESTAMP, timestampField.type.standardType)
    assertEquals(Field.Mode.REQUIRED, timestampField.mode)

    // Verify a subset of other fields to ensure types are correct
    val eventIdField = fields.find { it.name == "event_id" }
    assertNotNull(eventIdField)
    assertEquals(StandardSQLTypeName.STRING, eventIdField.type.standardType)
    assertEquals(Field.Mode.NULLABLE, eventIdField.mode)

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
    stubInsertAll()

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
          val adk = attributesOf(row).get(BigQuerySchema.ADK_ATTRIBUTE_KEY)
          assertEquals("tool", adk.get("pause_kind").asText())
          assertEquals("call-lr-1", adk.get("function_call_id").asText())
          assertEquals(event.id, adk.get("source_event_id").asText())
          assertTrue(adk.path("branch").isNull)
        }
      )
  }

  @Test
  @Suppress("CheckReturnValue")
  fun onEvent_finalResponse_logsAgentResponse(): Unit = runBlocking {
    stubInsertAll()

    val event =
      Event(
        id = "event-123",
        author = "test_agent",
        branch = "root.sub",
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
          val attributes = attributesOf(row)
          assertEquals("event-123", attributes.get("source_event_id").asText())
          assertEquals("root.sub", attributes.get("source_event_branch").asText())
          val adk = attributes.get(BigQuerySchema.ADK_ATTRIBUTE_KEY)
          assertEquals("event-123", adk.get("source_event_id").asText())
          assertEquals("root.sub", adk.get("branch").asText())
          assertEquals("text: 'Final answer'", contentOf(row).get("response").asText())
        }
      )
  }

  @Test
  @Suppress("CheckReturnValue")
  fun onEvent_hitlConfirmationRequest_logsHitlEvent(): Unit = runBlocking {
    stubInsertAll()

    val functionCall =
      FunctionCall(
        name = "adk_request_confirmation",
        args = mapOf("prompt" to "confirm?"),
        id = "c1",
      )
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
    stubInsertAll()

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
    stubInsertAll()

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
          val metadata = attributesOf(row).get("a2a_metadata")
          assertEquals(
            "do something",
            metadata.get(BigQueryUtils.A2A_REQUEST_KEY).get("task").asText(),
          )
          assertEquals(
            "completed",
            metadata.get(BigQueryUtils.A2A_RESPONSE_KEY).get("status").asText(),
          )
          assertEquals("completed", contentOf(row).get("status").asText())
        }
      )
  }

  @Test
  @Suppress("CheckReturnValue")
  fun interleavedInvocations_eachCloseTheirOwnSpans(): Unit = runBlocking {
    stubInsertAll()
    val first = testInvocationContext(agent = DummyAgent("agent_a"), invocationId = "inv-a")
    val second = testInvocationContext(agent = DummyAgent("agent_b"), invocationId = "inv-b")

    plugin.beforeRun(first)
    plugin.beforeAgent(CallbackContext(invocationContext = first))
    plugin.beforeRun(second)
    plugin.beforeAgent(CallbackContext(invocationContext = second))
    plugin.afterAgent(CallbackContext(invocationContext = first))
    plugin.afterRun(first)
    plugin.afterAgent(CallbackContext(invocationContext = second))
    plugin.afterRun(second)

    val rows = capturedRows()
    for (invocationId in listOf("inv-a", "inv-b")) {
      assertCompletionsCloseTheirStartingSpans(rows.filter { it["invocation_id"] == invocationId })
    }
  }

  @Test
  fun concurrentRuns_eachCloseTheirOwnSpans(): Unit = runBlocking {
    stubInsertAll()
    val agent =
      DummyAgent(
        "test_agent",
        onRunAsync = { emit(Event(author = "test_agent", content = modelText("done"))) },
      )

    InMemoryRunner(agent, plugins = listOf(plugin)).use { runner ->
      (1..10)
        .map { i ->
          launch(Dispatchers.Default) {
            runner.runAsync(userId = "u", sessionId = "s$i", newMessage = userText("hi")).toList()
          }
        }
        .joinAll()
    }

    val rowsByInvocation = capturedRows().groupBy { it["invocation_id"] }
    assertEquals(10, rowsByInvocation.size)
    for (rows in rowsByInvocation.values) {
      assertCompletionsCloseTheirStartingSpans(rows)
    }
  }

  @Test
  fun nestedAgentToolRun_keepsTheParentsSpans(): Unit = runBlocking {
    stubInsertAll()
    val child =
      LlmAgent(
        name = "child",
        model = ScriptedModel("child-model", modelResponse(modelText("child answer"))),
      )
    val callChild = FunctionCall(name = "child", args = mapOf("request" to "go"), id = "call-child")
    val parent =
      LlmAgent(
        name = "parent",
        model =
          ScriptedModel(
            "parent-model",
            modelResponse(Content(role = "model", parts = listOf(Part(functionCall = callChild)))),
            modelResponse(modelText("parent answer")),
          ),
        tools = listOf(AgentTool(child)),
      )

    InMemoryRunner(parent, plugins = listOf(plugin)).use { runner ->
      runner.runAsync(userId = "u", sessionId = "s", newMessage = userText("hi")).toList()
    }

    val rows = capturedRows()
    // The parent's user message is the first row; the child run has its own invocation id.
    val (parentRows, childRows) =
      rows.partition { it["invocation_id"] == rows.first()["invocation_id"] }
    assertCompletionsCloseTheirStartingSpans(parentRows)
    assertEquals(
      parentRows.ofType("TOOL_STARTING")["span_id"],
      parentRows.ofType("TOOL_COMPLETED")["span_id"],
    )
    assertNotNull(parentRows.ofType("TOOL_COMPLETED")["latency_ms"])
    assertEquals("child", childRows.ofType("AGENT_STARTING")["agent"])
    assertCompletionsCloseTheirStartingSpans(childRows)
  }

  @Test
  fun parallelToolCalls_eachCloseTheirOwnSpan(): Unit = runBlocking {
    stubInsertAll()
    // Each tool waits for the other to start, so both calls are in flight at once.
    val bothStarted = CompletableDeferred<Unit>()
    val started = AtomicInteger()
    fun barrierTool(name: String) =
      DummyTool(name) { _, _ ->
        if (started.incrementAndGet() == 2) bothStarted.complete(Unit)
        bothStarted.await()
        mapOf("tool" to name)
      }
    val calls =
      Content(
        role = "model",
        parts =
          listOf(
            Part(functionCall = FunctionCall(name = "tool_a", id = "call-a")),
            Part(functionCall = FunctionCall(name = "tool_b", id = "call-b")),
          ),
      )
    val agent =
      LlmAgent(
        name = "test_agent",
        model = ScriptedModel("model", modelResponse(calls), modelResponse(modelText("done"))),
        tools = listOf(barrierTool("tool_a"), barrierTool("tool_b")),
      )

    InMemoryRunner(agent, plugins = listOf(plugin)).use { runner ->
      withContext(Dispatchers.Default) {
        runner.runAsync(userId = "u", sessionId = "s", newMessage = userText("hi")).toList()
      }
    }

    val rows = capturedRows()
    val agentSpan = rows.ofType("AGENT_STARTING")["span_id"]
    for (tool in listOf("tool_a", "tool_b")) {
      val starting = rows.single { it["event_type"] == "TOOL_STARTING" && toolOf(it) == tool }
      val completed = rows.single { it["event_type"] == "TOOL_COMPLETED" && toolOf(it) == tool }
      assertEquals(starting["span_id"], completed["span_id"])
      assertEquals(agentSpan, starting["parent_span_id"])
      assertEquals(agentSpan, completed["parent_span_id"])
    }
  }

  @Test
  @Suppress("CheckReturnValue")
  fun toolCallsWithoutFunctionCallId_eachCloseTheirOwnSpan(): Unit = runBlocking {
    stubInsertAll()
    val tool = DummyTool("my_tool")
    val first = ToolContext(invocationContext = invocationContext)
    val second = ToolContext(invocationContext = invocationContext)

    plugin.beforeTool(first, tool, emptyMap())
    plugin.beforeTool(second, tool, emptyMap())
    plugin.afterTool(first, tool, emptyMap(), emptyMap())
    plugin.onToolError(second, tool, emptyMap(), RuntimeException("failed"))

    val rows = capturedRows()
    val startingSpans = rows.filter { it["event_type"] == "TOOL_STARTING" }.map { it["span_id"] }
    assertEquals(2, startingSpans.toSet().size)
    assertEquals(startingSpans[0], rows.ofType("TOOL_COMPLETED")["span_id"])
    assertEquals(startingSpans[1], rows.ofType("TOOL_ERROR")["span_id"])
  }

  @Test
  fun failedRun_logsInvocationErrorAndTheNextRunCompletes(): Unit = runBlocking {
    stubInsertAll()
    var runs = 0
    val agent =
      DummyAgent(
        "flaky_agent",
        onRunAsync = {
          if (runs++ == 0) throw IllegalStateException("upstream rejected Bearer ya29.secret")
          emit(Event(author = "flaky_agent", content = modelText("done")))
        },
      )

    InMemoryRunner(agent, plugins = listOf(plugin)).use { runner ->
      assertFailsWith<IllegalStateException> {
        runner.runAsync(userId = "u", sessionId = "s", newMessage = userText("hi")).toList()
      }
      runner.runAsync(userId = "u", sessionId = "s", newMessage = userText("again")).toList()
    }

    val rows = capturedRows()
    val error = rows.ofType("INVOCATION_ERROR")
    assertEquals("ERROR", error["status"])
    assertEquals("upstream rejected Bearer [REDACTED]", error["error_message"])
    assertEquals(true, error["is_truncated"])
    val traceback = contentOf(error).get("error_traceback").asText()
    assertTrue(
      traceback.startsWith("java.lang.IllegalStateException: upstream rejected Bearer [REDACTED]")
    )
    val completed = rows.ofType("INVOCATION_COMPLETED")
    assertEquals("OK", completed["status"])
    assertNotEquals(error["invocation_id"], completed["invocation_id"])
  }

  @Test
  fun analyticsFailure_neverFailsTheRun(): Unit = runBlocking {
    // A checked exception gets past the insert's own handler, so only the callback guard stops it.
    whenever(mockBigQuery.insertAll(any<InsertAllRequest>())).thenAnswer {
      throw IOException("connection reset")
    }
    val agent =
      DummyAgent(
        "test_agent",
        onRunAsync = { emit(Event(author = "test_agent", content = modelText("done"))) },
      )

    val events =
      InMemoryRunner(agent, plugins = listOf(plugin)).use { runner ->
        runner.runAsync(userId = "u", sessionId = "s", newMessage = userText("hi")).toList()
      }

    assertEquals("done", events.single().content?.parts?.single()?.text)
    verify(mockBigQuery, atLeastOnce()).insertAll(any<InsertAllRequest>())
  }

  @Test
  @Suppress("CheckReturnValue")
  fun onToolError_redactsCredentialsInTheErrorMessage(): Unit = runBlocking {
    stubInsertAll()
    val toolContext =
      ToolContext(invocationContext = invocationContext, functionCallId = "call-123")
    val error = RuntimeException("401 from upstream: Bearer ya29.secret-token")

    plugin.onToolError(toolContext, DummyTool("my_tool"), emptyMap(), error)

    val row = capturedRows().single()
    assertEquals("401 from upstream: Bearer [REDACTED]", row["error_message"])
    assertEquals(true, row["is_truncated"])
  }

  @Test
  @Suppress("CheckReturnValue")
  fun onModelError_boundsALongErrorMessage(): Unit = runBlocking {
    stubInsertAll()
    val boundedPlugin =
      BigQueryAgentAnalyticsPlugin(
        config.copy(maxContentLength = 64),
        mockBigQuery,
        ioContext = Dispatchers.Unconfined,
      )
    val callbackContext = CallbackContext(invocationContext = invocationContext)

    boundedPlugin.onModelError(callbackContext, LlmRequest(), RuntimeException("x".repeat(1_000)))

    val row = capturedRows().single()
    val message = row["error_message"] as String
    assertEquals(64, message.length)
    assertTrue(message.endsWith(JsonFormatter.TRUNCATION_SUFFIX))
    assertEquals(true, row["is_truncated"])
  }

  @Test
  @Suppress("CheckReturnValue")
  fun onEvent_oversizedStateDelta_isBoundedAndFlagged(): Unit = runBlocking {
    stubInsertAll()
    val boundedPlugin =
      BigQueryAgentAnalyticsPlugin(
        config.copy(maxContentLength = 100),
        mockBigQuery,
        ioContext = Dispatchers.Unconfined,
      )
    val event =
      Event(
        author = "test_agent",
        actions = EventActions(stateDelta = mutableMapOf("notes" to "n".repeat(1_000))),
      )

    boundedPlugin.onEvent(invocationContext, event)

    val row = capturedRows().single()
    val notes = attributesOf(row).at("/state_delta/notes").asText()
    assertEquals(100, notes.length)
    assertTrue(notes.endsWith(JsonFormatter.TRUNCATION_SUFFIX))
    assertEquals(true, row["is_truncated"])
  }

  @Test
  @Suppress("CheckReturnValue")
  fun beforeAgent_logsTheInstructionAndAgentShape(): Unit = runBlocking {
    stubInsertAll()
    val agent =
      LlmAgent(
        name = "planner",
        model = ScriptedModel("fake-model"),
        description = "Plans trips",
        subAgents = listOf(DummyAgent("booker")),
        instruction = Instruction("Be brief."),
        staticInstruction = Content(parts = listOf(Part(text = "You are a planner."))),
      )

    plugin.beforeAgent(CallbackContext(invocationContext = testInvocationContext(agent = agent)))

    val row = capturedRows().single()
    assertEquals("AGENT_STARTING", row["event_type"])
    assertEquals("Be brief.", contentOf(row).asText())
    val attributes = attributesOf(row)
    assertEquals("fake-model", attributes.get("model").asText())
    assertEquals("Plans trips", attributes.get("agent_description").asText())
    assertEquals("You are a planner.", attributes.get("static_instruction").asText())
    assertEquals(listOf("booker"), attributes.get("sub_agents").map { it.asText() })
    assertFalse(attributes.has("instruction_source"))
  }

  @Test
  @Suppress("CheckReturnValue")
  fun beforeAgent_structuredInstruction_logsItsText(): Unit = runBlocking {
    stubInsertAll()
    val instruction = Content(parts = listOf(Part(text = "Line 1"), Part(text = "Line 2")))
    val agent =
      LlmAgent(name = "a", model = ScriptedModel("m"), instruction = Instruction(instruction))

    plugin.beforeAgent(CallbackContext(invocationContext = testInvocationContext(agent = agent)))

    assertEquals("Line 1\nLine 2", contentOf(capturedRows().single()).asText())
  }

  @Test
  @Suppress("CheckReturnValue")
  fun beforeAgent_instructionProvider_logsItsSourceButNoInstruction(): Unit = runBlocking {
    stubInsertAll()
    val agent =
      LlmAgent(
        name = "a",
        model = ScriptedModel("m"),
        instruction = Instruction { _ -> Content(parts = listOf(Part(text = "dynamic"))) },
      )

    plugin.beforeAgent(CallbackContext(invocationContext = testInvocationContext(agent = agent)))

    val row = capturedRows().single()
    assertNull(row["content"])
    assertEquals("provider", attributesOf(row).get("instruction_source").asText())
  }

  @Test
  @Suppress("CheckReturnValue")
  fun onEvent_pausedForALongRunningTool_logsNoAgentResponse(): Unit = runBlocking {
    stubInsertAll()
    val event =
      Event(
        author = "test_agent",
        content =
          Content(
            role = "model",
            parts =
              listOf(
                Part(text = "Starting the job."),
                Part(functionCall = FunctionCall(name = "start_job", id = "call-lr-2")),
              ),
          ),
        longRunningToolIds = setOf("call-lr-2"),
      )

    plugin.onEvent(invocationContext, event)

    assertEquals(listOf<Any?>("TOOL_PAUSED"), capturedRows().map { it["event_type"] })
  }

  @Test
  @Suppress("CheckReturnValue")
  fun onEvent_longRunningConfirmationRequest_pausesWithItsHitlKind(): Unit = runBlocking {
    stubInsertAll()
    val functionCall = FunctionCall(name = "adk_request_confirmation", id = "c2")
    val event =
      Event(
        author = "test_agent",
        content = Content(role = "model", parts = listOf(Part(functionCall = functionCall))),
        longRunningToolIds = setOf("c2"),
      )

    plugin.onEvent(invocationContext, event)

    val rows = capturedRows()
    assertEquals(
      listOf<Any?>("HITL_CONFIRMATION_REQUEST", "TOOL_PAUSED"),
      rows.map { it["event_type"] },
    )
    val adk = attributesOf(rows.ofType("TOOL_PAUSED")).get(BigQuerySchema.ADK_ATTRIBUTE_KEY)
    assertEquals("hitl_confirmation", adk.get("pause_kind").asText())
    assertEquals("c2", adk.get("function_call_id").asText())
  }

  @Test
  @Suppress("CheckReturnValue")
  fun onUserMessage_longRunningToolResult_logsPairKeysUnderAdk(): Unit = runBlocking {
    stubInsertAll()
    val result =
      FunctionResponse(name = "my_lr_tool", id = "call-lr-1", response = mapOf("status" to "done"))
    val message = Content(role = "user", parts = listOf(Part(functionResponse = result)))

    plugin.onUserMessage(invocationContext, message)

    val adk =
      attributesOf(capturedRows().ofType("TOOL_COMPLETED")).get(BigQuerySchema.ADK_ATTRIBUTE_KEY)
    assertEquals("tool", adk.get("pause_kind").asText())
    assertEquals("call-lr-1", adk.get("function_call_id").asText())
    assertEquals(BigQuerySchema.ADK_ENVELOPE_SCHEMA_VERSION, adk.get("schema_version").asText())
    assertFalse(adk.has("source_event_id"))
  }

  @Test
  @Suppress("CheckReturnValue")
  fun contentFormatterFailure_logsAMarkerInsteadOfTheContent(): Unit = runBlocking {
    stubInsertAll()
    val failingConfig =
      config.copy(contentFormatter = { content, _ -> error("cannot format $content") })
    val failingPlugin =
      BigQueryAgentAnalyticsPlugin(failingConfig, mockBigQuery, ioContext = Dispatchers.Unconfined)
    val toolContext =
      ToolContext(invocationContext = invocationContext, functionCallId = "call-123")
    val args = mapOf<String, Any?>("ssn" to "123-45-6789")

    val logs = logRecordsDuring {
      failingPlugin.onToolError(toolContext, DummyTool("my_tool"), args, RuntimeException("failed"))
    }

    val row = capturedRows().single()
    assertEquals("[FORMATTER_FAILED]", contentOf(row).asText())
    assertEquals("failed; content_formatter raised IllegalStateException", row["error_message"])
    assertEquals("ERROR", row["status"])
    val messages = logs.map { SimpleFormatter().formatMessage(it) }
    assertTrue(messages.any { "IllegalStateException" in it })
    assertTrue(messages.none { "123-45-6789" in it })
    assertTrue(logs.all { it.thrown == null })
  }

  @Test
  @Suppress("CheckReturnValue")
  fun unlimitedMaxContentLength_logsTheWholeValue(): Unit = runBlocking {
    stubInsertAll()
    val unlimitedPlugin =
      BigQueryAgentAnalyticsPlugin(
        config.copy(maxContentLength = -1),
        mockBigQuery,
        ioContext = Dispatchers.Unconfined,
      )
    val notes = "n".repeat(1_000)
    val event =
      Event(
        author = "test_agent",
        actions = EventActions(stateDelta = mutableMapOf("notes" to notes)),
      )

    unlimitedPlugin.onEvent(invocationContext, event)

    val row = capturedRows().single()
    assertEquals(notes, attributesOf(row).at("/state_delta/notes").asText())
    assertEquals(false, row["is_truncated"])
  }

  @Test
  @Suppress("CheckReturnValue")
  fun failedSchemaUpgrade_dropsRowsAndRetriesAfterTheInterval(): Unit = runBlocking {
    stubInsertAll()
    val testTimeSource = TestTimeSource()
    val testPlugin =
      BigQueryAgentAnalyticsPlugin(
        config = config,
        bigQuery = mockBigQuery,
        ioContext = Dispatchers.Unconfined,
        timeSource = testTimeSource,
      )
    val outdatedFields = BigQuerySchema.getEventsSchema().fields.filter { it.name != "event_id" }
    val tableBuilder = mock<Table.Builder>()
    whenever(mockTable.getDefinition<StandardTableDefinition>())
      .thenReturn(StandardTableDefinition.of(Schema.of(outdatedFields)))
    whenever(mockTable.toBuilder()).thenReturn(tableBuilder)
    whenever(tableBuilder.setDefinition(any())).thenReturn(tableBuilder)
    whenever(tableBuilder.setLabels(any())).thenReturn(tableBuilder)
    whenever(tableBuilder.build()).thenReturn(mock<Table>())
    whenever(mockBigQuery.getTable(any<TableId>())).thenReturn(mockTable)
    whenever(mockBigQuery.update(any<TableInfo>())).thenThrow(BigQueryException(403, "denied"))

    testPlugin.beforeRun(invocationContext)
    testTimeSource += 11.seconds
    testPlugin.beforeRun(invocationContext)

    verify(mockBigQuery, times(2)).update(any<TableInfo>())
    verify(mockBigQuery, never()).insertAll(any<InsertAllRequest>())
  }

  @Test
  @Suppress("CheckReturnValue")
  fun cancelledViewCreation_stopsTheViewsAndRetriesSetupOnTheNextCallback(): Unit = runBlocking {
    stubInsertAll()
    val testPlugin =
      BigQueryAgentAnalyticsPlugin(
        config = config.copy(createViews = true),
        bigQuery = mockBigQuery,
        ioContext = Dispatchers.Unconfined,
        timeSource = TestTimeSource(),
      )
    whenever(mockBigQuery.getTable(any<TableId>())).thenReturn(mockTable)
    val run = launch(start = CoroutineStart.LAZY) { testPlugin.beforeRun(invocationContext) }
    stubViewQueries {
      run.cancel()
      null
    }

    run.join()
    verifyViewQueries(count = 1)
    stubViewQueries { null }
    testPlugin.beforeRun(invocationContext)

    assertTrue(run.isCancelled)
    verify(mockBigQuery, times(2)).getTable(any<TableId>())
    verify(mockBigQuery, times(1)).insertAll(any<InsertAllRequest>())
  }

  @Test
  fun shortCircuitedModelCall_closesTheAgentAndInvocationSpans(): Unit = runBlocking {
    stubInsertAll()
    val cached = BeforeModelCallback { _, _ ->
      CallbackChoice.Break(modelResponse(modelText("hit")))
    }
    val agent =
      LlmAgent(
        name = "test_agent",
        model = ScriptedModel("model"),
        beforeModelCallbacks = listOf(cached),
      )

    InMemoryRunner(agent, plugins = listOf(plugin)).use { runner ->
      runner.runAsync(userId = "u", sessionId = "s", newMessage = userText("hi")).toList()
    }

    val rows = capturedRows()
    assertCompletionsCloseTheirStartingSpans(rows)
    assertEquals(rows.ofType("AGENT_STARTING")["span_id"], rows.ofType("AGENT_RESPONSE")["span_id"])
  }

  @Test
  fun longRunningToolCall_closesTheAgentAndInvocationSpans(): Unit = runBlocking {
    stubInsertAll()
    val startJob = FunctionCall(name = "start_job", id = "call-lr")
    val agent =
      LlmAgent(
        name = "test_agent",
        model =
          ScriptedModel(
            "model",
            modelResponse(Content(role = "model", parts = listOf(Part(functionCall = startJob)))),
          ),
        tools = listOf(DummyTool("start_job", isLongRunning = true) { _, _ -> Unit }),
      )

    InMemoryRunner(agent, plugins = listOf(plugin)).use { runner ->
      runner.runAsync(userId = "u", sessionId = "s", newMessage = userText("hi")).toList()
    }

    assertCompletionsCloseTheirStartingSpans(capturedRows())
  }

  @Test
  @Suppress("CheckReturnValue")
  fun beforeModelWithoutAfterModel_parentsTheNextRequestOnTheAgent(): Unit = runBlocking {
    stubInsertAll()
    val callbackContext = CallbackContext(invocationContext = invocationContext)

    plugin.beforeAgent(callbackContext)
    plugin.beforeModel(callbackContext, LlmRequest())
    plugin.beforeModel(callbackContext, LlmRequest())

    val rows = capturedRows()
    val agentSpan = rows.ofType("AGENT_STARTING")["span_id"]
    assertEquals(
      listOf(agentSpan, agentSpan),
      rows.filter { it["event_type"] == "LLM_REQUEST" }.map { it["parent_span_id"] },
    )
  }

  @Test
  @Suppress("CheckReturnValue")
  fun beforeModel_logsTheModelConfigLabelsAndTools(): Unit = runBlocking {
    stubInsertAll()
    val weather =
      FunctionDeclaration(
        name = "get_weather",
        description = "Looks up the weather.",
        parameters =
          AdkSchema(
            type = Type.OBJECT,
            properties = mapOf("city" to AdkSchema(type = Type.STRING)),
            required = listOf("city"),
          ),
      )
    val time = FunctionDeclaration(name = "get_time", description = "")
    val request =
      LlmRequest(
        model = ScriptedModel("fake-model"),
        config =
          GenerateContentConfig(
            tools = listOf(Tool(functionDeclarations = listOf(weather, time))),
            labels = mapOf("team" to "search"),
            temperature = 0.5f,
            seed = 7,
            responseLogprobs = true,
          ),
      )

    plugin.beforeModel(CallbackContext(invocationContext = invocationContext), request)

    val attributes = attributesOf(capturedRows().single())
    assertEquals("fake-model", attributes.get("model").asText())
    val llmConfig = attributes.get("llm_config")
    assertEquals(0.5, llmConfig.get("temperature").asDouble())
    assertEquals(7, llmConfig.get("seed").asInt())
    assertTrue(llmConfig.get("response_logprobs").asBoolean())
    assertFalse(llmConfig.has("top_p"))
    assertEquals("search", attributes.at("/labels/team").asText())
    assertEquals(
      listOf(
        mapOf(
          "name" to "get_weather",
          "description" to "Looks up the weather.",
          "parameters" to
            mapOf(
              "type" to "OBJECT",
              "properties" to mapOf("city" to mapOf("type" to "STRING")),
              "required" to listOf("city"),
            ),
        ),
        mapOf("name" to "get_time"),
      ),
      JsonFormatter.toKotlinObject(attributes.get("tools")),
    )
  }

  @Test
  @Suppress("CheckReturnValue")
  fun beforeModel_toolParametersThatDoNotSerialize_areLeftOff(): Unit = runBlocking {
    stubInsertAll()
    val tool =
      FunctionDeclaration(
        name = "lookup",
        description = "Looks something up.",
        parameters = AdkSchema(type = Type.STRING, default = Any()),
      )
    val request =
      LlmRequest(
        config = GenerateContentConfig(tools = listOf(Tool(functionDeclarations = listOf(tool))))
      )

    plugin.beforeModel(CallbackContext(invocationContext = invocationContext), request)

    val attributes = attributesOf(capturedRows().single())
    assertEquals(
      listOf(mapOf("name" to "lookup", "description" to "Looks something up.")),
      JsonFormatter.toKotlinObject(attributes.get("tools")),
    )
  }

  @Test
  @Suppress("CheckReturnValue")
  fun beforeModel_withoutAModelNameOrFunctionDeclarations_logsNeither(): Unit = runBlocking {
    stubInsertAll()
    val request =
      LlmRequest(model = ScriptedModel(""), config = GenerateContentConfig(tools = listOf(Tool())))

    plugin.beforeModel(CallbackContext(invocationContext = invocationContext), request)

    val attributes = attributesOf(capturedRows().single())
    assertFalse(attributes.has("model"))
    assertFalse(attributes.has("tools"))
  }

  @Test
  @Suppress("CheckReturnValue")
  fun afterModel_logsUsageFinishReasonCacheAndError(): Unit = runBlocking {
    stubInsertAll()
    val callbackContext = CallbackContext(invocationContext = invocationContext)
    val cache =
      CacheMetadata(
        fingerprint = "abc123",
        contentsCount = 2,
        cacheName = "cachedContents/42",
        expireTime = 1_700_000_060_000,
        invocationsUsed = 1,
        createdAt = 1_700_000_000_000,
      )
    val response =
      LlmResponse(
        content = modelText("Partial answer"),
        usageMetadata =
          UsageMetadata(
            promptTokenCount = 10,
            candidatesTokenCount = 20,
            totalTokenCount = 38,
            thoughtsTokenCount = 5,
            toolUsePromptTokenCount = 3,
            cachedContentTokenCount = 8,
            trafficType = "ON_DEMAND",
          ),
        finishReason = FinishReason.MAX_TOKENS,
        errorMessage = "Output was cut off",
        modelVersion = "fake-model-001",
        cacheMetadata = cache,
      )

    plugin.beforeModel(callbackContext, LlmRequest())
    plugin.afterModel(callbackContext, response)

    val row = capturedRows().ofType("LLM_RESPONSE")
    assertEquals("OK", row["status"])
    assertEquals("Output was cut off", row["error_message"])
    assertNotNull(row["latency_ms"])
    val attributes = attributesOf(row)
    assertEquals(
      mapOf(
        "prompt_token_count" to 10,
        "candidates_token_count" to 20,
        "total_token_count" to 38,
        "cached_content_token_count" to 8,
        "thoughts_token_count" to 5,
        "tool_use_prompt_token_count" to 3,
        "traffic_type" to "ON_DEMAND",
      ),
      JsonFormatter.toKotlinObject(attributes.get("usage_metadata")),
    )
    assertEquals("MAX_TOKENS", attributes.get("finish_reason").asText())
    assertEquals("fake-model-001", attributes.get("model_version").asText())
    assertEquals("explicit", attributes.get("cache_type").asText())
    assertEquals(
      mapOf(
        "cache_name" to "cachedContents/42",
        "expire_time" to 1_700_000_060.0,
        "fingerprint" to "abc123",
        "invocations_used" to 1,
        "contents_count" to 2,
        "created_at" to 1_700_000_000.0,
      ),
      JsonFormatter.toKotlinObject(attributes.get("cache_metadata")),
    )
  }

  @Test
  @Suppress("CheckReturnValue")
  fun afterModel_cacheType_followsTheCachedTokenCount(): Unit = runBlocking {
    stubInsertAll()
    val callbackContext = CallbackContext(invocationContext = invocationContext)
    val fingerprintOnly = CacheMetadata(fingerprint = "abc123", contentsCount = 2)

    plugin.afterModel(callbackContext, LlmResponse())
    plugin.afterModel(
      callbackContext,
      LlmResponse(
        usageMetadata = UsageMetadata(cachedContentTokenCount = 0),
        cacheMetadata = fingerprintOnly,
      ),
    )
    plugin.afterModel(
      callbackContext,
      LlmResponse(usageMetadata = UsageMetadata(cachedContentTokenCount = 4)),
    )

    assertEquals(
      listOf("none", "none", "implicit"),
      capturedRows().map { attributesOf(it).get("cache_type").asText() },
    )
  }

  @Test
  @Suppress("CheckReturnValue")
  fun afterModel_streamedResponse_logsOnlyTheFinalChunk(): Unit = runBlocking {
    stubInsertAll()
    val callbackContext = CallbackContext(invocationContext = invocationContext)

    plugin.beforeModel(callbackContext, LlmRequest())
    plugin.afterModel(callbackContext, LlmResponse(content = modelText("Hel"), partial = true))
    delay(50)
    plugin.afterModel(callbackContext, LlmResponse(content = modelText("Hello")))

    val response = capturedRows().ofType("LLM_RESPONSE")
    val latency = JsonFormatter.mapper.readTree(response["latency_ms"] as String)
    val firstTokenMs = latency.get("time_to_first_token_ms").asLong()
    assertTrue(latency.get("total_ms").asLong() - firstTokenMs >= 40)
  }

  @Test
  @Suppress("CheckReturnValue")
  fun beforeTool_logsTheToolOrigin(): Unit = runBlocking {
    stubInsertAll()
    val localTool =
      object : FunctionTool("local_tool", "A local tool.") {
        override fun declaration(): FunctionDeclaration? = null

        override suspend fun execute(context: ToolContext, args: Map<String, Any?>): Any = Unit
      }
    // No MCP case: McpTool is final and only core can construct it.
    val tools =
      listOf(
        localTool,
        AgentTool(DummyAgent("helper")),
        DummyTool("transfer_to_agent"),
        DummyTool("other_tool"),
      )

    for ((index, tool) in tools.withIndex()) {
      val toolContext =
        ToolContext(invocationContext = invocationContext, functionCallId = "call-$index")
      plugin.beforeTool(toolContext, tool, emptyMap())
    }

    assertEquals(
      listOf("LOCAL", "SUB_AGENT", "TRANSFER_AGENT", "UNKNOWN"),
      capturedRows().map { contentOf(it).get("tool_origin").asText() },
    )
  }

  @Test
  @Suppress("CheckReturnValue")
  fun toolEvents_giveTheFormatterRedactedAndBoundedPlainValues(): Unit = runBlocking {
    stubInsertAll()
    val formatted = mutableMapOf<String, Any>()
    val formattingConfig =
      config.copy(
        maxContentLength = 32,
        contentFormatter = { content, eventType ->
          formatted[eventType] = content
          content
        },
      )
    val formattingPlugin =
      BigQueryAgentAnalyticsPlugin(
        formattingConfig,
        mockBigQuery,
        ioContext = Dispatchers.Unconfined,
      )
    val toolContext = ToolContext(invocationContext = invocationContext, functionCallId = "call-1")
    val args = mapOf<String, Any?>("api_key" to "secret", "query" to "q".repeat(100))

    formattingPlugin.beforeTool(toolContext, DummyTool("search"), args)
    formattingPlugin.afterTool(toolContext, DummyTool("search"), args, mapOf("hits" to 3))

    val startingArgs = (formatted.getValue("TOOL_STARTING") as Map<*, *>)["args"] as Map<*, *>
    assertEquals("[REDACTED]", startingArgs["api_key"])
    assertEquals(32, (startingArgs["query"] as String).length)
    assertEquals(true, capturedRows().ofType("TOOL_STARTING")["is_truncated"])
    assertEquals(mapOf("hits" to 3), (formatted.getValue("TOOL_COMPLETED") as Map<*, *>)["result"])
  }

  @Test
  @Suppress("CheckReturnValue")
  fun everyRow_carriesTheRootAgentSessionAndCustomTags(): Unit = runBlocking {
    stubInsertAll()
    val taggedPlugin =
      BigQueryAgentAnalyticsPlugin(
        config.copy(customTags = mapOf("env" to "test")),
        mockBigQuery,
        ioContext = Dispatchers.Unconfined,
      )
    val subAgentContext = testInvocationContext(agent = DummyAgent("sub_agent"))

    taggedPlugin.beforeRun(invocationContext)
    taggedPlugin.beforeAgent(CallbackContext(invocationContext = subAgentContext))

    val rows = capturedRows()
    assertEquals(2, rows.size)
    for (row in rows) {
      val attributes = attributesOf(row)
      assertEquals("test_agent", attributes.get("root_agent_name").asText())
      assertEquals("test_session_id", attributes.at("/session_metadata/session_id").asText())
      assertEquals("test", attributes.at("/custom_tags/env").asText())
    }
  }

  @Test
  @Suppress("CheckReturnValue")
  fun resumedRun_closesTheInvocationSpanOnASubAgentBranch(): Unit = runBlocking {
    stubInsertAll()
    val resumedContext = testInvocationContext(agent = DummyAgent("sub"), branch = "test_agent.sub")

    plugin.onUserMessage(invocationContext, userText("approve"))
    plugin.beforeRun(resumedContext)
    plugin.afterRun(resumedContext)

    val rows = capturedRows()
    val completed = rows.ofType("INVOCATION_COMPLETED")
    assertEquals(rows.ofType("INVOCATION_STARTING")["span_id"], completed["span_id"])
    assertNotNull(completed["latency_ms"])
  }

  @Test
  @Suppress("CheckReturnValue")
  fun hitlCompletions_logNoPairKeys(): Unit = runBlocking {
    stubInsertAll()
    val confirmation =
      FunctionResponse(
        name = "adk_request_confirmation",
        id = "c1",
        response = mapOf("confirmed" to true),
      )
    val message = Content(role = "user", parts = listOf(Part(functionResponse = confirmation)))

    plugin.onUserMessage(invocationContext, message)
    plugin.onEvent(invocationContext, Event(author = "user", content = message))

    val completions =
      capturedRows().filter { it["event_type"] == "HITL_CONFIRMATION_REQUEST_COMPLETED" }
    assertEquals(2, completions.size)
    for (row in completions) {
      assertFalse(attributesOf(row).get(BigQuerySchema.ADK_ATTRIBUTE_KEY).has("pause_kind"))
    }
  }

  private fun stubInsertAll() {
    whenever(mockBigQuery.insertAll(any<InsertAllRequest>())).thenReturn(mockInsertAllResponse)
    whenever(mockInsertAllResponse.hasErrors()).thenReturn(false)
  }

  // Kept out of suspend bodies, since BigQuery.query declares InterruptedException (SuspendBlocks).
  private fun stubViewQueries(answer: () -> TableResult?) {
    whenever(mockBigQuery.query(any<QueryJobConfiguration>())).thenAnswer { answer() }
  }

  private fun verifyViewQueries(count: Int) {
    verify(mockBigQuery, times(count)).query(any<QueryJobConfiguration>())
  }

  /** Returns the records the plugin logs while [block] runs. */
  private suspend fun logRecordsDuring(block: suspend () -> Unit): List<LogRecord> {
    val records = mutableListOf<LogRecord>()
    val handler =
      object : Handler() {
        override fun publish(record: LogRecord?) {
          record?.let { records += it }
        }

        override fun flush() {}

        override fun close() {}
      }
    val julLogger = Logger.getLogger(BigQueryAgentAnalyticsPlugin::class.java.name)
    julLogger.addHandler(handler)
    try {
      block()
    } finally {
      julLogger.removeHandler(handler)
    }
    return records
  }

  private fun capturedRows(): List<Map<String, Any?>> {
    val captor = argumentCaptor<InsertAllRequest>()
    verify(mockBigQuery, atLeastOnce()).insertAll(captor.capture())
    return captor.allValues.map { it.rows.single().content }
  }

  private fun List<Map<String, Any?>>.ofType(eventType: String): Map<String, Any?> = single {
    it["event_type"] == eventType
  }

  private fun attributesOf(row: Map<String, Any?>): JsonNode =
    JsonFormatter.mapper.readTree(row["attributes"] as String)

  private fun contentOf(row: Map<String, Any?>): JsonNode =
    JsonFormatter.mapper.readTree(row["content"] as String)

  private fun toolOf(row: Map<String, Any?>): String = contentOf(row).get("tool").asText()

  private fun userText(text: String) = Content(role = "user", parts = listOf(Part(text = text)))

  private fun modelText(text: String) = Content(role = "model", parts = listOf(Part(text = text)))

  private fun modelResponse(content: Content) = LlmResponse(content = content)

  /** Asserts that each completion row of one invocation closes the span its starting row opened. */
  private fun assertCompletionsCloseTheirStartingSpans(rows: List<Map<String, Any?>>) {
    for ((starting, completed) in
      listOf(
        "INVOCATION_STARTING" to "INVOCATION_COMPLETED",
        "AGENT_STARTING" to "AGENT_COMPLETED",
      )) {
      assertEquals(rows.ofType(starting)["span_id"], rows.ofType(completed)["span_id"])
      assertNotNull(rows.ofType(completed)["latency_ms"])
    }
    assertEquals(1, rows.map { it["trace_id"] }.toSet().size)
  }
}

/** A model that replays one scripted response per call. */
private class ScriptedModel(override val name: String, vararg responses: LlmResponse) : Model {
  private val script = ArrayDeque(responses.toList())

  override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> =
    flowOf(synchronized(script) { script.removeFirst() })
}
