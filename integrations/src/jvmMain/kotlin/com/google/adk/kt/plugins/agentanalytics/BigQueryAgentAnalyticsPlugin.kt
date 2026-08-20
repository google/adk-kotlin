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
import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.callbacks.CallbackChoice
import com.google.adk.kt.events.Event
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.logging.LoggerFactory
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.plugins.Plugin
import com.google.adk.kt.plugins.agentanalytics.JsonFormatter.smartTruncate
import com.google.adk.kt.tools.AgentTool
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.FunctionTool
import com.google.adk.kt.tools.ToolContext
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
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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
 * An ADK [Plugin] that records agent execution events to a BigQuery table. If the destination table
 * does not exist, the plugin automatically creates and configures the partitioned table on first
 * use.
 */
class BigQueryAgentAnalyticsPlugin
internal constructor(
  private val config: BigQueryLoggerConfig,
  private val bigQuery: BigQuery,
  @Suppress("GlobalCoroutineDispatchers") private val ioContext: CoroutineContext = Dispatchers.IO,
  private val timeSource: TimeSource = TimeSource.Monotonic,
  private val traceManager: TraceManager = TraceManager(),
) : Plugin {

  constructor(
    config: BigQueryLoggerConfig,
    bigQuery: BigQuery = createBigQuery(config),
    ioContext: CoroutineContext = Dispatchers.IO,
  ) : this(config, bigQuery, ioContext, TimeSource.Monotonic, TraceManager())

  override val name: String = "bigquery_agent_analytics"

  @Volatile private var tableEnsured = false
  private val mutex = Mutex()
  private var lastCheckTime: TimeMark? = null
  private val parser = Parser(config.maxContentLength, config.logMultiModalContent)
  private val syntheticToolCallIds = ConcurrentHashMap<ToolContext, String>()

  override suspend fun onUserMessage(
    invocationContext: InvocationContext,
    userMessage: Content,
  ): Content {
    if (!config.enabled) return userMessage
    traceManager.ensureInvocationSpan(invocationContext)
    logEvent("USER_MESSAGE_RECEIVED", invocationContext, userMessage)

    for (part in userMessage.parts) {
      val functionResponse = part.functionResponse ?: continue
      val responseName = functionResponse.name
      val truncatedResult = smartTruncate(functionResponse.response, config.maxContentLength)
      val contentMap = mapOf("tool" to responseName, "result" to truncatedResult.node)

      if (HITL_EVENT_TYPES.containsKey(responseName)) {
        val eventType = "${HITL_EVENT_TYPES.getValue(responseName)}_COMPLETED"
        val pairKeys = hitlPairKeys(responseName, functionResponse.id)
        logEvent(
          eventType = eventType,
          invocationContext = invocationContext,
          content = contentMap,
          isContentTruncated = truncatedResult.isTruncated,
          eventData = EventData(extraAttributes = pairKeys),
        )
      } else {
        val pairKeys = mutableMapOf<String, Any>("pause_kind" to "tool")
        functionResponse.id?.takeIf { it.isNotEmpty() }?.let { pairKeys["function_call_id"] = it }
        logEvent(
          eventType = "TOOL_COMPLETED",
          invocationContext = invocationContext,
          content = contentMap,
          isContentTruncated = truncatedResult.isTruncated,
          eventData = EventData(extraAttributes = pairKeys),
        )
      }
    }
    return userMessage
  }

  override suspend fun beforeRun(
    invocationContext: InvocationContext
  ): CallbackChoice<Unit, Content> {
    if (!config.enabled) return CallbackChoice.Continue(Unit)
    traceManager.ensureInvocationSpan(invocationContext)
    logEvent("INVOCATION_STARTING", invocationContext, "Invocation started")
    return CallbackChoice.Continue(Unit)
  }

  override suspend fun onEvent(invocationContext: InvocationContext, event: Event): Event {
    if (!config.enabled) return event

    if (event.actions.stateDelta.isNotEmpty()) {
      val extraAttributes =
        mutableMapOf<String, Any?>(
          "state_delta" to event.actions.stateDelta,
          "author" to event.author,
        )
      logEvent(
        eventType = "STATE_DELTA",
        invocationContext = invocationContext,
        content = event.content,
        eventData = EventData(extraAttributes = extraAttributes, fallbackAgentName = event.author),
      )
    }

    event.content?.parts?.let { parts ->
      val longRunningIds = event.longRunningToolIds
      for (part in parts) {
        val functionCall = part.functionCall
        if (functionCall != null) {
          val callName = functionCall.name
          if (HITL_EVENT_TYPES.containsKey(callName)) {
            val hitlEvent = HITL_EVENT_TYPES.getValue(callName)
            val truncatedResult = smartTruncate(functionCall.args, config.maxContentLength)
            logEvent(
              eventType = hitlEvent,
              invocationContext = invocationContext,
              content = mapOf("tool" to callName, "args" to truncatedResult.node),
              isContentTruncated = truncatedResult.isTruncated,
            )
          }
          if (functionCall.id != null && longRunningIds.contains(functionCall.id)) {
            val truncatedResult = smartTruncate(functionCall.args, config.maxContentLength)
            val pausedData =
              EventData(
                extraAttributes =
                  mapOf(
                    "pause_kind" to (HITL_PAUSE_KIND_MAP[callName] ?: "tool"),
                    "function_call_id" to functionCall.id!!,
                  ),
                fallbackAgentName = event.author,
              )
            logEvent(
              eventType = "TOOL_PAUSED",
              invocationContext = invocationContext,
              content = mapOf("tool" to callName, "args" to truncatedResult.node),
              isContentTruncated = truncatedResult.isTruncated,
              eventData = pausedData,
            )
          }
        }
        val functionResponse = part.functionResponse
        if (functionResponse != null && HITL_EVENT_TYPES.containsKey(functionResponse.name)) {
          val hitlEvent = "${HITL_EVENT_TYPES.getValue(functionResponse.name)}_COMPLETED"
          val truncatedResult = smartTruncate(functionResponse.response, config.maxContentLength)
          logEvent(
            eventType = hitlEvent,
            invocationContext = invocationContext,
            content = mapOf("tool" to functionResponse.name, "result" to truncatedResult.node),
            isContentTruncated = truncatedResult.isTruncated,
            eventData =
              EventData(extraAttributes = hitlPairKeys(functionResponse.name, functionResponse.id)),
          )
        }
      }
    }

    event.customMetadata?.let { meta ->
      val a2aKeys = mutableMapOf<String, Any?>()
      for ((key, value) in meta) {
        if (key.startsWith(BigQueryUtils.A2A_PREFIX)) {
          a2aKeys[key] = value
        }
      }
      if (
        a2aKeys.containsKey(BigQueryUtils.A2A_REQUEST_KEY) ||
          a2aKeys.containsKey(BigQueryUtils.A2A_RESPONSE_KEY)
      ) {
        val responsePayload = a2aKeys[BigQueryUtils.A2A_RESPONSE_KEY]
        var contentObject: Any? = null
        var contentTruncated = false
        if (responsePayload != null) {
          val responseTruncated = smartTruncate(responsePayload, config.maxContentLength)
          contentObject = JsonFormatter.toKotlinObject(responseTruncated.node)
          contentTruncated = responseTruncated.isTruncated
        }
        val a2aMetaKeys = HashMap(a2aKeys)
        a2aMetaKeys.remove(BigQueryUtils.A2A_RESPONSE_KEY)
        val a2aTruncated = smartTruncate(a2aMetaKeys, config.maxContentLength)
        val extraAttributes = mutableMapOf<String, Any?>()
        JsonFormatter.toKotlinObject(a2aTruncated.node)?.let {
          extraAttributes["a2a_metadata"] = it
        }

        logEvent(
          eventType = "A2A_INTERACTION",
          invocationContext = invocationContext,
          content = contentObject,
          isContentTruncated = a2aTruncated.isTruncated || contentTruncated,
          eventData = EventData(extraAttributes = extraAttributes, fallbackAgentName = event.author),
        )
      }
    }

    if (event.isFinalResponse) {
      val visibleParts =
        event.content?.parts?.filter { it.text != null && it.thought != true } ?: emptyList()
      if (visibleParts.isNotEmpty()) {
        val visibleContent = Content(role = event.content?.role ?: "model", parts = visibleParts)
        val extraAttributes = mutableMapOf<String, Any?>()
        extraAttributes["source_event_id"] = event.id
        extraAttributes["source_event_author"] = event.author
        event.branch?.let { extraAttributes["source_event_branch"] = it }

        logEvent(
          eventType = "AGENT_RESPONSE",
          invocationContext = invocationContext,
          content = visibleContent,
          isContentTruncated = false,
          eventData = EventData(extraAttributes = extraAttributes, fallbackAgentName = event.author),
        )
      }
    }

    return event
  }

  override suspend fun afterRun(invocationContext: InvocationContext) {
    if (!config.enabled) return
    val completedData = getCompletedEventData(invocationContext, "invocation")
    val eventType =
      if (invocationContext.isEndOfInvocation && completedData?.status == "ERROR") {
        "INVOCATION_ERROR"
      } else {
        "INVOCATION_COMPLETED"
      }
    logEvent(
      eventType = eventType,
      invocationContext = invocationContext,
      content = mapOf("message" to "Invocation completed"),
      eventData = completedData,
    )
  }

  override suspend fun beforeAgent(
    context: CallbackContext
  ): CallbackChoice<EventActions, Content> {
    if (!config.enabled) return CallbackChoice.Continue(EventActions())
    val unusedSpanId =
      traceManager.pushSpan(
        context.invocationContext,
        "agent:${context.invocationContext.agent.name}",
      )
    logEvent(
      "AGENT_STARTING",
      context.invocationContext,
      content = mapOf("message" to "Agent starting"),
    )
    return CallbackChoice.Continue(EventActions())
  }

  override suspend fun afterAgent(context: CallbackContext): CallbackChoice<Unit, Content> {
    if (!config.enabled) return CallbackChoice.Continue(Unit)
    val completedData = getCompletedEventData(context.invocationContext, "agent:")
    logEvent(
      "AGENT_COMPLETED",
      context.invocationContext,
      content = mapOf("message" to "Agent completed"),
      eventData = completedData,
    )
    return CallbackChoice.Continue(Unit)
  }

  override suspend fun beforeModel(
    context: CallbackContext,
    request: LlmRequest,
  ): CallbackChoice<LlmRequest, LlmResponse> {
    if (!config.enabled) return CallbackChoice.Continue(request)
    val attributes = mutableMapOf<String, Any?>()
    val llmConfig = mutableMapOf<String, Any?>()

    request.config.temperature?.let { llmConfig["temperature"] = it }
    request.config.topP?.let { llmConfig["top_p"] = it }
    request.config.topK?.let { llmConfig["top_k"] = it }
    request.config.candidateCount?.let { llmConfig["candidate_count"] = it }
    request.config.maxOutputTokens?.let { llmConfig["max_output_tokens"] = it }
    request.config.stopSequences?.let { llmConfig["stop_sequences"] = it }
    request.config.presencePenalty?.let { llmConfig["presence_penalty"] = it }
    request.config.frequencyPenalty?.let { llmConfig["frequency_penalty"] = it }
    request.config.responseMimeType?.let { llmConfig["response_mime_type"] = it }
    request.config.responseSchema?.let { llmConfig["response_schema"] = it }

    if (llmConfig.isNotEmpty()) {
      attributes["llm_config"] = llmConfig
    }
    val toolsList = request.config.tools
    if (!toolsList.isNullOrEmpty()) {
      attributes["tools"] =
        toolsList.mapNotNull { it.functionDeclarations?.map { fd -> fd.name } }.flatten()
    }

    val eventData = EventData(model = request.model?.name ?: "", extraAttributes = attributes)
    val unusedSpanId = traceManager.pushSpan(context.invocationContext, "llm_request")
    logEvent("LLM_REQUEST", context.invocationContext, content = request, eventData = eventData)
    return CallbackChoice.Continue(request)
  }

  override suspend fun afterModel(context: CallbackContext, response: LlmResponse): LlmResponse {
    if (!config.enabled) return response
    val invocationContext = context.invocationContext
    val usageDict = mutableMapOf<String, Any?>()
    response.usageMetadata?.let { usage ->
      usage.promptTokenCount?.let { usageDict["prompt"] = it }
      usage.candidatesTokenCount?.let { usageDict["completion"] = it }
      usage.totalTokenCount?.let { usageDict["total"] = it }
      usage.cachedContentTokenCount?.let { usageDict["cached_content_token_count"] = it }
    }

    var spanId = traceManager.getCurrentSpanId(invocationContext)
    val spanIds = traceManager.getCurrentSpanAndParent(invocationContext)
    val parentSpanId = spanIds.parentSpanId

    var isPopped = false
    var duration = Duration.ZERO
    var ttft: Duration? = null
    var startTime: Instant? = null

    if (spanId != null) {
      traceManager.recordFirstToken(spanId)
      startTime = traceManager.getStartTime(spanId)
      val firstTokenTime = traceManager.getFirstTokenTime(spanId)
      if (startTime != null && firstTokenTime != null) {
        ttft = Duration.between(startTime, firstTokenTime)
      }
    }

    if (response.partial) {
      if (startTime != null) {
        duration = Duration.between(startTime, Instant.now())
      }
    } else {
      val popped = traceManager.popSpan(invocationContext, "llm_request")
      if (popped != null) {
        spanId = popped.spanId
        duration = popped.duration
        isPopped = true
      }
    }

    val eventData =
      EventData(
        latency = if (!duration.isZero) duration else null,
        timeToFirstToken = ttft,
        modelVersion = response.modelVersion,
        usageMetadata = usageDict.ifEmpty { null },
        spanIdOverride = if (isPopped) spanId else null,
        parentSpanIdOverride = if (isPopped) parentSpanId else null,
      )

    logEvent("LLM_RESPONSE", invocationContext, content = response, eventData = eventData)
    return response
  }

  override suspend fun onModelError(
    context: CallbackContext,
    request: LlmRequest,
    error: Throwable,
  ): CallbackChoice<Unit, LlmResponse> {
    if (!config.enabled) return CallbackChoice.Continue(Unit)
    val invocationContext = context.invocationContext
    val popped = traceManager.popSpan(invocationContext, "llm_request")
    val spanIds = traceManager.getCurrentSpanAndParent(invocationContext)

    val eventData =
      EventData(
        status = "ERROR",
        errorMessage = error.message,
        latency = popped?.duration,
        spanIdOverride = popped?.spanId,
        parentSpanIdOverride = popped?.parentSpanId ?: spanIds.spanId,
      )
    logEvent("LLM_ERROR", invocationContext, content = null, eventData = eventData)
    return CallbackChoice.Continue(Unit)
  }

  override suspend fun beforeTool(
    context: ToolContext,
    tool: BaseTool,
    args: Map<String, Any?>,
  ): CallbackChoice<Map<String, Any?>, Map<String, Any?>> {
    if (!config.enabled) return CallbackChoice.Continue(args)
    val opId = toolOperationId(context)
    val contentMap =
      mapOf("tool_origin" to getToolOrigin(tool), "tool" to tool.name, "args" to args)
    val toolSpan = traceManager.pushSpanRecord(context.invocationContext, "tool", opId)
    val startingData =
      EventData(spanIdOverride = toolSpan.spanId, parentSpanIdOverride = toolSpan.parentSpanId)
    logEvent(
      "TOOL_STARTING",
      context.invocationContext,
      content = contentMap,
      eventData = startingData,
    )
    return CallbackChoice.Continue(args)
  }

  override suspend fun afterTool(
    context: ToolContext,
    tool: BaseTool,
    args: Map<String, Any?>,
    result: Map<String, Any?>,
  ): Map<String, Any?> {
    if (!config.enabled) return result
    val invocationContext = context.invocationContext
    traceManager.ensureInvocationSpan(invocationContext)
    val opId = toolOperationId(context)
    val popped = traceManager.popSpan(invocationContext, "tool", opId)
    val truncationResult = smartTruncate(result, config.maxContentLength)
    val contentMap =
      mapOf(
        "tool" to tool.name,
        "result" to truncationResult.node,
        "tool_origin" to getToolOrigin(tool),
      )
    val eventData =
      EventData(
        latency = popped?.duration,
        spanIdOverride = popped?.spanId,
        parentSpanIdOverride = popped?.parentSpanId,
      )
    logEvent(
      eventType = "TOOL_COMPLETED",
      invocationContext = invocationContext,
      content = contentMap,
      isContentTruncated = truncationResult.isTruncated,
      eventData = eventData,
    )
    return result
  }

  override suspend fun onToolError(
    context: ToolContext,
    tool: BaseTool,
    args: Map<String, Any?>,
    error: Throwable,
  ): CallbackChoice<Unit, Map<String, Any?>> {
    if (!config.enabled) return CallbackChoice.Continue(Unit)
    val invocationContext = context.invocationContext
    traceManager.ensureInvocationSpan(invocationContext)
    val opId = toolOperationId(context)
    val popped = traceManager.popSpan(invocationContext, "tool", opId)
    val truncationResult = smartTruncate(args, config.maxContentLength)
    val contentMap =
      mapOf(
        "tool" to tool.name,
        "args" to truncationResult.node,
        "tool_origin" to getToolOrigin(tool),
      )
    val eventData =
      EventData(
        status = "ERROR",
        errorMessage = error.message,
        latency = popped?.duration,
        spanIdOverride = popped?.spanId,
        parentSpanIdOverride = popped?.parentSpanId,
      )
    logEvent(
      eventType = "TOOL_ERROR",
      invocationContext = invocationContext,
      content = contentMap,
      isContentTruncated = truncationResult.isTruncated,
      eventData = eventData,
    )
    return CallbackChoice.Continue(Unit)
  }

  override fun close() {
    traceManager.clearStack()
    syntheticToolCallIds.clear()
  }

  private suspend fun logEvent(
    eventType: String,
    invocationContext: InvocationContext,
    message: String,
  ) {
    logEvent(eventType, invocationContext, mapOf("message" to message))
  }

  private suspend fun logEvent(
    eventType: String,
    invocationContext: InvocationContext,
    content: Any? = null,
    isContentTruncated: Boolean = false,
    eventData: EventData? = null,
  ) {
    if (!config.enabled) return
    if (config.eventAllowlist.isNotEmpty() && !config.eventAllowlist.contains(eventType)) return
    if (config.eventDenylist.contains(eventType)) return

    var formattedContent = content
    if (config.contentFormatter != null && formattedContent != null) {
      try {
        formattedContent = config.contentFormatter.invoke(formattedContent, eventType)
      } catch (e: RuntimeException) {
        logger.warn(e) {
          "Failed to format content for invocation ID: ${invocationContext.invocationId}"
        }
        formattedContent = null
      }
    }

    if (!ensureTableExistsOnce()) {
      logger.debug { "Table is not created, skipping event logging" }
      return
    }

    val traceIds = getResolvedTraceIds(invocationContext, eventData)
    val agentName = resolveAgentName(invocationContext, eventData)
    val latencyMap = extractLatency(eventData)
    val attributesTree = getAttributes(eventData, invocationContext)
    val redactedAttributes = JsonFormatter.redactTree(attributesTree)

    val parsed =
      parser.parse(
        content = formattedContent,
        traceId = traceIds.traceId,
        spanId = traceIds.spanId ?: "no_span",
      )

    val row =
      mutableMapOf<String, Any?>(
        "timestamp" to Instant.now().toString(),
        "event_type" to eventType,
        "agent" to agentName,
        "session_id" to invocationContext.session.key.id,
        "invocation_id" to invocationContext.invocationId,
        "user_id" to invocationContext.session.key.userId,
        "trace_id" to traceIds.traceId,
        "span_id" to traceIds.spanId,
        "parent_span_id" to traceIds.parentSpanId,
        "status" to eventData?.status,
        "error_message" to eventData?.errorMessage,
        "latency_ms" to latencyMap?.let { JsonFormatter.writeValueAsString(it) },
        "attributes" to
          if (redactedAttributes.isNull) null
          else JsonFormatter.writeValueAsString(redactedAttributes),
        "content_parts" to
          if (config.logMultiModalContent) {
            parsed.parts.mapNotNull { JsonFormatter.toKotlinObject(it) }
          } else {
            emptyList<Any>()
          },
        "content" to
          if (content == null || parsed.content.isNull) null
          else JsonFormatter.writeValueAsString(parsed.content),
        "is_truncated" to (isContentTruncated || parsed.isTruncated),
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
          val tableDefinitionBuilder =
            StandardTableDefinition.newBuilder()
              .setSchema(schema)
              .setTimePartitioning(
                TimePartitioning.newBuilder(TimePartitioning.Type.DAY).setField("timestamp").build()
              )
          if (config.clusteringFields.isNotEmpty()) {
            tableDefinitionBuilder.setClustering(
              Clustering.newBuilder().setFields(config.clusteringFields).build()
            )
          }
          val tableInfo =
            TableInfo.newBuilder(tableId, tableDefinitionBuilder.build())
              .setLabels(
                mapOf(BigQuerySchema.SCHEMA_VERSION_LABEL_KEY to BigQuerySchema.SCHEMA_VERSION)
              )
              .build()
          bigQuery.create(tableInfo)
          logger.info { "Table created: $tableId" }
        } else {
          if (config.autoSchemaUpgrade) {
            val unusedUpgraded = BigQueryUtils.maybeUpgradeSchema(bigQuery, table)
          } else {
            logger.info { "Table already exists: $tableId" }
          }
        }
        if (config.createViews) {
          BigQueryUtils.createAnalyticsViews(bigQuery, config)
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

  private fun toolOperationId(toolContext: ToolContext): String {
    val id = toolContext.functionCallId
    if (!id.isNullOrEmpty()) {
      return id
    }
    return syntheticToolCallIds.computeIfAbsent(toolContext) { "tool-ctx-${UUID.randomUUID()}" }
  }

  private fun getResolvedTraceIds(
    invocationContext: InvocationContext,
    eventData: EventData?,
  ): ResolvedTraceIds {
    val traceId = eventData?.traceIdOverride ?: traceManager.getTraceId(invocationContext)
    val spanIds = traceManager.getCurrentSpanAndParent(invocationContext)

    return ResolvedTraceIds(
      traceId = traceId,
      spanId = eventData?.spanIdOverride ?: spanIds.spanId,
      parentSpanId = eventData?.parentSpanIdOverride ?: spanIds.parentSpanId,
    )
  }

  private data class ResolvedTraceIds(
    val traceId: String,
    val spanId: String?,
    val parentSpanId: String?,
  )

  private fun extractLatency(eventData: EventData?): Map<String, Any?>? {
    if (eventData == null) return null
    val latencyMap = mutableMapOf<String, Any?>()
    eventData.latency?.let { latencyMap["total_ms"] = it.toMillis() }
    eventData.timeToFirstToken?.let { latencyMap["time_to_first_token_ms"] = it.toMillis() }
    return latencyMap.ifEmpty { null }
  }

  private fun getAttributes(
    eventData: EventData?,
    invocationContext: InvocationContext,
  ): Map<String, Any?> {
    val attributes = mutableMapOf<String, Any?>()
    eventData?.extraAttributes?.let { attributes.putAll(it) }
    traceManager.initTraceIfNeeded(invocationContext)
    attributes["root_agent_name"] = traceManager.rootAgentName
    eventData?.model?.let { attributes["model"] = it }
    eventData?.modelVersion?.let { attributes["model_version"] = it }
    eventData?.usageMetadata?.let { um ->
      val result = smartTruncate(um, config.maxContentLength)
      attributes["usage_metadata"] = JsonFormatter.toKotlinObject(result.node)
    }

    if (config.logSessionMetadata) {
      try {
        val session = invocationContext.session
        val sessionMeta = mutableMapOf<String, Any?>()
        sessionMeta["session_id"] = session.key.id
        sessionMeta["user_id"] = session.key.userId
        sessionMeta["app_name"] = session.key.appName

        if (session.state.isNotEmpty()) {
          val redactedState = JsonFormatter.redactTree(session.state.toMap())
          val result = smartTruncate(redactedState, config.maxContentLength)
          sessionMeta["state"] = JsonFormatter.toKotlinObject(result.node)
        }
        attributes["session_metadata"] = sessionMeta
      } catch (e: RuntimeException) {
        logger.warn(e) {
          "Failed to log session metadata for invocation ID: ${invocationContext.invocationId}"
        }
      }
    }

    if (config.customTags.isNotEmpty()) {
      attributes["custom_tags"] = config.customTags
    }

    return attributes
  }

  private fun getCompletedEventData(
    invocationContext: InvocationContext,
    expectedKindPrefix: String,
  ): EventData? {
    val traceId = traceManager.getTraceId(invocationContext)
    val popped = traceManager.popSpan(invocationContext, expectedKindPrefix) ?: return null
    val parentSpanId = traceManager.getCurrentSpanId(invocationContext)

    return EventData(
      traceIdOverride = traceId,
      latency = popped.duration,
      spanIdOverride = popped.spanId,
      parentSpanIdOverride = popped.parentSpanId ?: parentSpanId,
    )
  }

  private fun resolveAgentName(
    invocationContext: InvocationContext,
    eventData: EventData?,
  ): String {
    return try {
      invocationContext.agent.name
    } catch (_: RuntimeException) {
      eventData?.fallbackAgentName ?: "unknown"
    }
  }

  private fun getToolOrigin(tool: BaseTool): String {
    if (tool is AgentTool) {
      return "SUB_AGENT"
    }
    if (tool.name == "transfer_to_agent") {
      return "TRANSFER_AGENT"
    }
    if (tool is FunctionTool) {
      return "LOCAL"
    }
    val className = tool::class.simpleName ?: ""
    if (className.contains("Mcp", ignoreCase = true)) {
      return "MCP"
    }
    return "UNKNOWN"
  }

  private fun hitlPairKeys(hitlName: String, functionCallId: String?): Map<String, Any> {
    val keys = mutableMapOf<String, Any>()
    keys["pause_kind"] = HITL_PAUSE_KIND_MAP[hitlName] ?: "tool"
    functionCallId?.takeIf { it.isNotEmpty() }?.let { keys["function_call_id"] = it }
    return keys
  }

  companion object {
    private val TABLE_CREATION_RETRY_INTERVAL = 10.seconds
    private val logger = LoggerFactory.getLogger(BigQueryAgentAnalyticsPlugin::class)

    private val HITL_EVENT_TYPES =
      mapOf(
        "adk_request_credential" to "HITL_CREDENTIAL_REQUEST",
        "adk_request_confirmation" to "HITL_CONFIRMATION_REQUEST",
        "adk_request_input" to "HITL_INPUT_REQUEST",
      )

    private val HITL_PAUSE_KIND_MAP =
      mapOf(
        "adk_request_credential" to "hitl_credential",
        "adk_request_confirmation" to "hitl_confirmation",
        "adk_request_input" to "hitl_input",
      )

    private fun createBigQuery(config: BigQueryLoggerConfig): BigQuery {
      val builder = BigQueryOptions.newBuilder()
      if (config.credentials != null) {
        builder.setCredentials(config.credentials)
      }
      return builder.setLocation(config.location).setProjectId(config.projectId).build().service
    }
  }
}
