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
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.adk.kt.agents.BaseAgent
import com.google.adk.kt.agents.CallbackContext
import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.callbacks.CallbackChoice
import com.google.adk.kt.events.Event
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.logging.LoggerFactory
import com.google.adk.kt.models.CacheMetadata
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.plugins.Plugin
import com.google.adk.kt.plugins.agentanalytics.JsonFormatter.mapper
import com.google.adk.kt.plugins.agentanalytics.JsonFormatter.smartTruncate
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.tools.AgentTool
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.FunctionTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.tools.mcp.McpTool
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.UsageMetadata
import com.google.auth.Credentials
import com.google.cloud.bigquery.BigQuery
import com.google.cloud.bigquery.BigQueryException
import com.google.cloud.bigquery.BigQueryOptions
import com.google.cloud.bigquery.Clustering
import com.google.cloud.bigquery.StandardTableDefinition
import com.google.cloud.bigquery.TableId
import com.google.cloud.bigquery.TableInfo
import com.google.cloud.bigquery.TimePartitioning
import java.time.Instant
import java.util.Collections
import java.util.UUID
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.coroutines.CoroutineContext
import kotlin.jvm.JvmStatic
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlin.time.toKotlinDuration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * An ADK [Plugin] that records agent execution events to a BigQuery table. If the destination table
 * does not exist, the plugin automatically creates and configures the partitioned table on first
 * use. Rows are queued and written in the background in batches with the BigQuery Storage Write
 * API; call [close] when done to write the rows still queued.
 */
class BigQueryAgentAnalyticsPlugin
internal constructor(
  private val config: BigQueryLoggerConfig,
  private val bigQuery: BigQuery,
  private val ioContext: CoroutineContext,
  private val timeSource: TimeSource,
  private val createRowWriter: (Credentials?) -> RowWriter,
) : Plugin {

  override val name: String = "bigquery_agent_analytics"

  @Volatile private var tableEnsured = false
  private val mutex = Mutex()
  private var lastCheckTime: TimeMark? = null
  private val traceManager = TraceManager()
  // -1 means unlimited.
  private val maxContentLength =
    if (config.maxContentLength == -1) Int.MAX_VALUE else config.maxContentLength
  private val parser = Parser(maxContentLength)
  // Shared by every pipeline, so counts outlive a close; seeded, so its keys never change.
  private val drops = DropCounter().apply { add(SETUP_UNAVAILABLE, count = 0) }
  private val pipelineLock = Any()
  // Guarded by pipelineLock.
  private var pipeline: Pipeline? = null
  // Callers of close wait for one in progress, so that the shutdown hook does not end it early.
  private val closeLock = Any()

  /**
   * Creates a plugin that logs to the table [config] names.
   *
   * @param config Where and what to log.
   * @param bigQuery Client that creates and upgrades the table and its views; the Storage Write API
   *   writes the rows with this client's credentials.
   * @param ioContext Context that blocking BigQuery calls and the background writer run in.
   * @param timeSource Clock for the interval between attempts to create the table.
   */
  constructor(
    config: BigQueryLoggerConfig,
    bigQuery: BigQuery = createBigQuery(config),
    @Suppress("GlobalCoroutineDispatchers") ioContext: CoroutineContext = Dispatchers.IO,
    timeSource: TimeSource = TimeSource.Monotonic,
  ) : this(
    config,
    bigQuery,
    ioContext,
    timeSource,
    createRowWriter = { credentials -> StorageWriteApiWriter.create(config, credentials) },
  )

  override suspend fun onUserMessage(
    invocationContext: InvocationContext,
    userMessage: Content,
  ): Content {
    if (!config.enabled) return userMessage
    guarded("onUserMessage") {
      traceManager.ensureInvocationSpan(invocationContext)
      logEvent(EventType.USER_MESSAGE_RECEIVED, invocationContext, userMessage)

      for (part in userMessage.parts) {
        val functionResponse = part.functionResponse ?: continue
        val responseName = functionResponse.name
        val hitlEvent = HITL_EVENT_TYPES[responseName]
        val result = smartTruncate(functionResponse.response, maxContentLength)
        logEvent(
          eventType =
            if (hitlEvent != null) hitlEvent + EventType.COMPLETED_SUFFIX
            else EventType.TOOL_COMPLETED,
          invocationContext = invocationContext,
          content = mapOf("tool" to responseName, "result" to result.toPlainValue()),
          isContentTruncated = result.isTruncated,
          // Only a resumed long-running tool gets the pair keys.
          eventData =
            EventData(
              adkExtras =
                if (hitlEvent == null) pairKeys(responseName, functionResponse.id) else emptyMap()
            ),
        )
      }
    }
    return userMessage
  }

  override suspend fun beforeRun(
    invocationContext: InvocationContext
  ): CallbackChoice<Unit, Content> {
    if (!config.enabled) return CallbackChoice.Continue(Unit)
    guarded("beforeRun") {
      traceManager.ensureInvocationSpan(invocationContext)
      logEvent(
        EventType.INVOCATION_STARTING,
        invocationContext,
        mapOf("message" to "Invocation started"),
      )
    }
    return CallbackChoice.Continue(Unit)
  }

  override suspend fun onEvent(invocationContext: InvocationContext, event: Event): Event {
    if (!config.enabled) return event
    guarded("onEvent") { logEventRows(invocationContext, event) }
    return event
  }

  private suspend fun logEventRows(invocationContext: InvocationContext, event: Event) {
    // Short-circuited model call skips afterModel, so its span closes here.
    if (!event.partial) {
      val unusedClosed = traceManager.popSpan(invocationContext, LLM_REQUEST_KIND)
    }
    if (event.actions.stateDelta.isNotEmpty()) {
      logEvent(
        eventType = EventType.STATE_DELTA,
        invocationContext = invocationContext,
        eventData =
          EventData(
            extraAttributes = mapOf("state_delta" to event.actions.stateDelta),
            sourceEvent = event,
          ),
      )
    }

    event.content?.parts?.let { parts ->
      val longRunningIds = event.longRunningToolIds
      for (part in parts) {
        val functionCall = part.functionCall
        if (functionCall != null) {
          val callName = functionCall.name
          val hitlEvent = HITL_EVENT_TYPES[callName]
          if (hitlEvent != null) {
            val truncatedResult = smartTruncate(functionCall.args, maxContentLength)
            logEvent(
              eventType = hitlEvent,
              invocationContext = invocationContext,
              content = mapOf("tool" to callName, "args" to truncatedResult.toPlainValue()),
              isContentTruncated = truncatedResult.isTruncated,
              eventData = EventData(sourceEvent = event),
            )
          }
          if (functionCall.id != null && longRunningIds.contains(functionCall.id)) {
            val truncatedResult = smartTruncate(functionCall.args, maxContentLength)
            logEvent(
              eventType = EventType.TOOL_PAUSED,
              invocationContext = invocationContext,
              content = mapOf("tool" to callName, "args" to truncatedResult.toPlainValue()),
              isContentTruncated = truncatedResult.isTruncated,
              eventData =
                EventData(adkExtras = pairKeys(callName, functionCall.id), sourceEvent = event),
            )
          }
        }
        val functionResponse = part.functionResponse ?: continue
        val hitlEvent = HITL_EVENT_TYPES[functionResponse.name] ?: continue
        val truncatedResult = smartTruncate(functionResponse.response, maxContentLength)
        logEvent(
          eventType = hitlEvent + EventType.COMPLETED_SUFFIX,
          invocationContext = invocationContext,
          content =
            mapOf("tool" to functionResponse.name, "result" to truncatedResult.toPlainValue()),
          isContentTruncated = truncatedResult.isTruncated,
          eventData = EventData(sourceEvent = event),
        )
      }
    }

    event.customMetadata?.let { meta ->
      val a2aKeys = meta.filterKeys { it.startsWith(BigQueryUtils.A2A_PREFIX) }
      val responsePayload = a2aKeys[BigQueryUtils.A2A_RESPONSE_KEY]
      if (a2aKeys[BigQueryUtils.A2A_REQUEST_KEY] != null || responsePayload != null) {
        val response =
          if (responsePayload != null) smartTruncate(responsePayload, maxContentLength) else null
        val a2aMetadata = smartTruncate(a2aKeys, maxContentLength)
        logEvent(
          eventType = EventType.A2A_INTERACTION,
          invocationContext = invocationContext,
          content = response?.toPlainValue(),
          isContentTruncated = a2aMetadata.isTruncated || response?.isTruncated == true,
          eventData =
            EventData(
              extraAttributes = mapOf("a2a_metadata" to a2aMetadata.toPlainValue()),
              sourceEvent = event,
            ),
        )
      }
    }

    // Partial, function-call-containing, and long-running tool events are not responses.
    if (
      event.isFinalResponse &&
        !event.partial &&
        event.functionCalls().isEmpty() &&
        event.functionResponses().isEmpty() &&
        event.longRunningToolIds.isEmpty()
    ) {
      val visibleTexts =
        event.content
          ?.parts
          .orEmpty()
          .filter { it.thought != true }
          .mapNotNull { it.text?.ifEmpty { null } }
      if (visibleTexts.isNotEmpty()) {
        val formatted = parser.formatText(visibleTexts)
        val extraAttributes =
          mapOf(
            "source_event_id" to event.id,
            "source_event_author" to event.author,
            "source_event_branch" to event.branch,
          )
        logEvent(
          eventType = EventType.AGENT_RESPONSE,
          invocationContext = invocationContext,
          content = mapOf("response" to formatted.text),
          isContentTruncated = formatted.isChanged,
          isContentPreformatted = true,
          eventData = EventData(extraAttributes = extraAttributes, sourceEvent = event),
        )
      }
    }
  }

  override suspend fun afterRun(invocationContext: InvocationContext) {
    if (!config.enabled) return
    try {
      guarded("afterRun") {
        logEvent(
          eventType = EventType.INVOCATION_COMPLETED,
          invocationContext = invocationContext,
          content = mapOf("message" to "Invocation completed"),
          eventData = closeSpan(invocationContext, TraceManager.INVOCATION_KIND),
        )
      }
    } finally {
      traceManager.endInvocation(invocationContext)
    }
    if (config.flushOnRunEnd) flush()
  }

  override suspend fun onRunError(invocationContext: InvocationContext, error: Throwable) {
    if (!config.enabled) return
    try {
      guarded("onRunError") {
        val completedData = closeSpan(invocationContext, TraceManager.INVOCATION_KIND)
        val traceback = JsonFormatter.sanitizeText(error.stackTraceToString(), maxContentLength)
        logEvent(
          eventType = EventType.INVOCATION_ERROR,
          invocationContext = invocationContext,
          content = mapOf("error_traceback" to traceback.text),
          isContentTruncated = traceback.isChanged,
          eventData = completedData.copy(status = STATUS_ERROR, errorMessage = error.message),
        )
      }
    } finally {
      traceManager.endInvocation(invocationContext)
    }
    if (config.flushOnRunEnd) flush()
  }

  override suspend fun beforeAgent(
    context: CallbackContext
  ): CallbackChoice<EventActions, Content> {
    if (!config.enabled) return CallbackChoice.Continue(EventActions())
    guarded("beforeAgent") {
      val invocationContext = context.invocationContext
      val agent = invocationContext.agent
      val unusedSpanId = traceManager.pushSpan(invocationContext, agentSpanKind(agent))
      val llmAgent = agent as? LlmAgent
      logEvent(
        EventType.AGENT_STARTING,
        invocationContext,
        content = instructionText(llmAgent?.instruction),
        eventData =
          EventData(
            model = llmAgent?.model?.name,
            extraAttributes = agentStartingAttributes(agent, llmAgent),
          ),
      )
    }
    return CallbackChoice.Continue(EventActions())
  }

  override suspend fun afterAgent(context: CallbackContext): CallbackChoice<Unit, Content> {
    if (!config.enabled) return CallbackChoice.Continue(Unit)
    guarded("afterAgent") {
      val invocationContext = context.invocationContext
      val completedData = closeSpan(invocationContext, agentSpanKind(invocationContext.agent))
      logEvent(EventType.AGENT_COMPLETED, invocationContext, eventData = completedData)
    }
    return CallbackChoice.Continue(Unit)
  }

  override suspend fun beforeModel(
    context: CallbackContext,
    request: LlmRequest,
  ): CallbackChoice<LlmRequest, LlmResponse> {
    if (!config.enabled) return CallbackChoice.Continue(request)
    guarded("beforeModel") {
      val generationConfig = request.config
      val llmConfig =
        mapOf(
            "temperature" to generationConfig.temperature,
            "top_p" to generationConfig.topP,
            "top_k" to generationConfig.topK,
            "candidate_count" to generationConfig.candidateCount,
            "max_output_tokens" to generationConfig.maxOutputTokens,
            "stop_sequences" to generationConfig.stopSequences,
            "presence_penalty" to generationConfig.presencePenalty,
            "frequency_penalty" to generationConfig.frequencyPenalty,
            "response_mime_type" to generationConfig.responseMimeType,
            "response_schema" to generationConfig.responseSchema,
            "seed" to generationConfig.seed,
            "response_logprobs" to generationConfig.responseLogprobs,
          )
          .filterValues { it != null }
      val tools =
        generationConfig.tools
          .orEmpty()
          .flatMap { it.functionDeclarations.orEmpty() }
          .map(::toolDeclaration)
      val attributes = buildMap {
        if (llmConfig.isNotEmpty()) put("llm_config", llmConfig)
        val labels = generationConfig.labels
        if (!labels.isNullOrEmpty()) put("labels", labels)
        if (tools.isNotEmpty()) put("tools", tools)
      }

      // Closes a short-circuited request whose response event was never seen.
      val unusedStale = traceManager.popSpan(context.invocationContext, LLM_REQUEST_KIND)
      val unusedSpanId = traceManager.pushSpan(context.invocationContext, LLM_REQUEST_KIND)
      logEvent(
        EventType.LLM_REQUEST,
        context.invocationContext,
        content = request,
        eventData = EventData(model = request.model?.name, extraAttributes = attributes),
      )
    }
    return CallbackChoice.Continue(request)
  }

  override suspend fun afterModel(context: CallbackContext, response: LlmResponse): LlmResponse {
    if (!config.enabled) return response
    guarded("afterModel") {
      val invocationContext = context.invocationContext
      val spanId = traceManager.getCurrentSpanId(invocationContext)
      if (spanId != null) traceManager.recordFirstToken(invocationContext, spanId)
      // Read before popSpan drops the record that holds the first-token time.
      val timeToFirstToken =
        if (spanId != null) traceManager.timeToFirstToken(invocationContext, spanId) else null
      // A streaming chunk leaves the model call's span open.
      val popped =
        if (response.partial) null else traceManager.popSpan(invocationContext, LLM_REQUEST_KIND)
      val eventData =
        EventData(
          errorMessage = if (response.partial) null else response.errorMessage,
          latency =
            when {
              !response.partial -> popped?.duration
              spanId != null -> traceManager.elapsed(invocationContext, spanId)
              else -> null
            },
          timeToFirstToken = timeToFirstToken,
          modelVersion = response.modelVersion,
          spanIdOverride = popped?.spanId,
          parentSpanIdOverride = popped?.parentSpanId,
          extraAttributes = llmResponseAttributes(response),
        )
      logEvent(EventType.LLM_RESPONSE, invocationContext, content = response, eventData = eventData)
    }
    return response
  }

  override suspend fun onModelError(
    context: CallbackContext,
    request: LlmRequest,
    error: Throwable,
  ): CallbackChoice<Unit, LlmResponse> {
    if (!config.enabled) return CallbackChoice.Continue(Unit)
    guarded("onModelError") {
      val invocationContext = context.invocationContext
      val popped = traceManager.popSpan(invocationContext, LLM_REQUEST_KIND)
      val eventData =
        EventData(
          status = STATUS_ERROR,
          errorMessage = error.message,
          latency = popped?.duration,
          spanIdOverride = popped?.spanId,
          parentSpanIdOverride =
            popped?.parentSpanId ?: traceManager.getCurrentSpanId(invocationContext),
        )
      logEvent(EventType.LLM_ERROR, invocationContext, content = null, eventData = eventData)
    }
    return CallbackChoice.Continue(Unit)
  }

  override suspend fun beforeTool(
    context: ToolContext,
    tool: BaseTool,
    args: Map<String, Any?>,
  ): CallbackChoice<Map<String, Any?>, Map<String, Any?>> {
    if (!config.enabled) return CallbackChoice.Continue(args)
    guarded("beforeTool") {
      val opId = toolOperationId(context)
      val truncatedArgs = smartTruncate(args, maxContentLength)
      val contentMap =
        mapOf(
          "tool" to tool.name,
          "args" to truncatedArgs.toPlainValue(),
          "tool_origin" to getToolOrigin(tool),
        )
      val toolSpan = traceManager.pushSpanRecord(context.invocationContext, TOOL_KIND, opId)
      val startingData =
        EventData(spanIdOverride = toolSpan.spanId, parentSpanIdOverride = toolSpan.parentSpanId)
      logEvent(
        EventType.TOOL_STARTING,
        context.invocationContext,
        content = contentMap,
        isContentTruncated = truncatedArgs.isTruncated,
        eventData = startingData,
      )
    }
    return CallbackChoice.Continue(args)
  }

  override suspend fun afterTool(
    context: ToolContext,
    tool: BaseTool,
    args: Map<String, Any?>,
    result: Map<String, Any?>,
  ): Map<String, Any?> {
    if (!config.enabled) return result
    guarded("afterTool") {
      val invocationContext = context.invocationContext
      traceManager.ensureInvocationSpan(invocationContext)
      val popped =
        releaseToolOperationId(context)?.let {
          traceManager.popSpan(invocationContext, TOOL_KIND, it)
        }
      val truncationResult = smartTruncate(result, maxContentLength)
      val contentMap =
        mapOf(
          "tool" to tool.name,
          "result" to truncationResult.toPlainValue(),
          "tool_origin" to getToolOrigin(tool),
        )
      val eventData =
        EventData(
          latency = popped?.duration,
          spanIdOverride = popped?.spanId,
          parentSpanIdOverride = popped?.parentSpanId,
        )
      logEvent(
        eventType = EventType.TOOL_COMPLETED,
        invocationContext = invocationContext,
        content = contentMap,
        isContentTruncated = truncationResult.isTruncated,
        eventData = eventData,
      )
    }
    return result
  }

  override suspend fun onToolError(
    context: ToolContext,
    tool: BaseTool,
    args: Map<String, Any?>,
    error: Throwable,
  ): CallbackChoice<Unit, Map<String, Any?>> {
    if (!config.enabled) return CallbackChoice.Continue(Unit)
    guarded("onToolError") {
      val invocationContext = context.invocationContext
      traceManager.ensureInvocationSpan(invocationContext)
      val popped =
        releaseToolOperationId(context)?.let {
          traceManager.popSpan(invocationContext, TOOL_KIND, it)
        }
      val truncationResult = smartTruncate(args, maxContentLength)
      val contentMap =
        mapOf(
          "tool" to tool.name,
          "args" to truncationResult.toPlainValue(),
          "tool_origin" to getToolOrigin(tool),
        )
      val eventData =
        EventData(
          status = STATUS_ERROR,
          errorMessage = error.message,
          latency = popped?.duration,
          spanIdOverride = popped?.spanId,
          parentSpanIdOverride = popped?.parentSpanId,
        )
      logEvent(
        eventType = EventType.TOOL_ERROR,
        invocationContext = invocationContext,
        content = contentMap,
        isContentTruncated = truncationResult.isTruncated,
        eventData = eventData,
      )
    }
    return CallbackChoice.Continue(Unit)
  }

  /**
   * Waits until every row logged before the call is written to BigQuery or dropped.
   *
   * While it waits, queued rows are written without waiting for their batch to fill.
   */
  suspend fun flush() {
    synchronized(pipelineLock) { pipeline?.processor }?.flush()
  }

  /**
   * Returns how many rows were dropped so far for each reason, such as `queue_full` or
   * `retry_exhausted`. Every reason is listed, even one with no drops.
   */
  fun getDropStats(): Map<String, Long> = drops.snapshot()

  /**
   * Writes the rows still queued, waiting up to [BigQueryLoggerConfig.shutdownTimeout], and
   * releases the write client. The next row logged starts a new one.
   */
  override fun close() {
    synchronized(closeLock) {
      // Kept until shutdown returns, so that a flush meanwhile waits for the rows to be written.
      val stopping = synchronized(pipelineLock) { pipeline }
      if (stopping != null) {
        try {
          val timeout = config.shutdownTimeout.toKotlinDuration()
          val start = TimeSource.Monotonic.markNow()
          stopping.processor.shutdown(timeout)
          closeWriter(stopping.writer, within = timeout - start.elapsedNow())
        } finally {
          openPlugins.remove(this)
          synchronized(pipelineLock) { pipeline = null }
        }
      }
      traceManager.clear()
    }
  }

  // The first row after a close starts a new pipeline.
  private fun getOrStartProcessor(): BatchProcessor =
    synchronized(pipelineLock) { (pipeline ?: startPipeline().also { pipeline = it }).processor }

  private fun startPipeline(): Pipeline {
    // Rows are written as the same identity that creates the table.
    val writer = createRowWriter(bigQuery.options?.scopedCredentials)
    val processor =
      BatchProcessor(
        writer = writer,
        batchSize = config.batchSize,
        flushInterval = config.batchFlushInterval.toKotlinDuration(),
        queueMaxSize = config.queueMaxSize,
        retryConfig = config.retryConfig,
        context = ioContext,
        drops = drops,
      )
    try {
      registerShutdownHook()
    } catch (e: IllegalStateException) {
      logger.debug { "JVM is shutting down; rows logged now are written only if close runs." }
    }
    openPlugins.add(this)
    return Pipeline(processor, writer)
  }

  // The client can wait minutes for writes still in flight, so it closes on its own thread.
  private fun closeWriter(writer: RowWriter, within: Duration) {
    val closer =
      thread(name = "bigquery-analytics-writer-close", isDaemon = true) {
        try {
          writer.close()
        } catch (e: Exception) {
          logger.warn { "Closing the BigQuery write client failed: ${e::class.simpleName}" }
        }
      }
    try {
      // join(0) would wait forever.
      closer.join(within.inWholeMilliseconds.coerceAtLeast(1))
    } catch (e: InterruptedException) {
      Thread.currentThread().interrupt()
    }
  }

  private suspend fun logEvent(
    eventType: String,
    invocationContext: InvocationContext,
    content: Any? = null,
    isContentTruncated: Boolean = false,
    isContentPreformatted: Boolean = false,
    eventData: EventData = EventData(),
  ) {
    if (config.eventAllowlist.isNotEmpty() && !config.eventAllowlist.contains(eventType)) return
    if (config.eventDenylist.contains(eventType)) return

    val errorMessage =
      eventData.errorMessage?.let { JsonFormatter.sanitizeText(it, maxContentLength) }
    var formattedContent = content
    var formatterNote: String? = null
    if (config.contentFormatter != null && content != null) {
      try {
        formattedContent = config.contentFormatter.invoke(content, eventType)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        // The formatter is a redaction boundary.
        val failure = e::class.simpleName ?: "<unknown class>"
        logger.warn { "Content formatter failed for $eventType: $failure" }
        formattedContent = FORMATTER_FAILED
        formatterNote = "content_formatter raised $failure"
      }
    }
    val eventError = errorMessage?.text
    val errorText =
      when {
        formatterNote == null -> eventError
        eventError.isNullOrEmpty() -> formatterNote
        else -> "$eventError; $formatterNote"
      }

    if (!ensureTableExistsOnce()) {
      drops.add(SETUP_UNAVAILABLE)
      logger.debug { "Table is not created, skipping event logging" }
      return
    }

    val currentSpans = traceManager.getCurrentSpanAndParent(invocationContext)
    val latencyMap = extractLatency(eventData)
    val attributes = smartTruncate(getAttributes(eventData, invocationContext), maxContentLength)
    val parsed =
      if (isContentPreformatted && formattedContent === content) {
        Parser.ParsedContent(emptyList(), mapper.valueToTree(formattedContent), isTruncated = false)
      } else {
        parser.parse(formattedContent)
      }

    val row =
      mapOf<String, Any?>(
        "timestamp" to Instant.now(),
        "event_id" to UUID.randomUUID().toString().replace("-", ""),
        "event_type" to eventType,
        "agent" to invocationContext.agent.name,
        "session_id" to invocationContext.session.key.id,
        "invocation_id" to invocationContext.invocationId,
        "user_id" to invocationContext.session.key.userId,
        "trace_id" to traceManager.getTraceId(invocationContext),
        "span_id" to (eventData.spanIdOverride ?: currentSpans.spanId),
        "parent_span_id" to (eventData.parentSpanIdOverride ?: currentSpans.parentSpanId),
        "status" to (eventData.status ?: STATUS_OK),
        "error_message" to errorText,
        "latency_ms" to if (latencyMap != null) mapper.writeValueAsString(latencyMap) else null,
        "attributes" to mapper.writeValueAsString(attributes.node),
        "content_parts" to
          if (config.logMultiModalContent) {
            parsed.parts.mapNotNull { JsonFormatter.toKotlinObject(it) }
          } else {
            emptyList<Any>()
          },
        "content" to if (parsed.content.isNull) null else mapper.writeValueAsString(parsed.content),
        "is_truncated" to
          (isContentTruncated ||
            parsed.isTruncated ||
            errorMessage?.isChanged == true ||
            attributes.isTruncated),
      )

    getOrStartProcessor().append(row, isTerminal = eventType in TERMINAL_EVENT_TYPES)
  }

  private suspend fun ensureTableExistsOnce(): Boolean {
    if (tableEnsured) return true

    mutex.withLock {
      if (tableEnsured) return true

      val elapsed = lastCheckTime?.elapsedNow()
      if (elapsed != null && elapsed < TABLE_CREATION_RETRY_INTERVAL) return false

      if (ensureTableExists()) {
        tableEnsured = true
        return true
      }
      // Only a failed attempt starts the retry interval; a cancelled one throws before this.
      lastCheckTime = timeSource.markNow()
      return false
    }
  }

  private suspend fun ensureTableExists(): Boolean =
    withContext(ioContext) {
      val tableId = TableId.of(config.projectId, config.datasetId, config.tableName)
      try {
        val table = bigQuery.getTable(tableId)
        when {
          table == null -> {
            logger.info { "Creating BigQuery table: $tableId" }
            val schema = BigQuerySchema.getEventsSchema()
            val tableDefinitionBuilder =
              StandardTableDefinition.newBuilder()
                .setSchema(schema)
                .setTimePartitioning(
                  TimePartitioning.newBuilder(TimePartitioning.Type.DAY)
                    .setField("timestamp")
                    .build()
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
            try {
              bigQuery.create(tableInfo)
              logger.info { "Table created: $tableId" }
            } catch (e: BigQueryException) {
              if (e.code != 409) throw e
              logger.info { "Table was created concurrently: $tableId" }
              if (config.autoSchemaUpgrade) {
                val existing = bigQuery.getTable(tableId) ?: return@withContext false
                if (!BigQueryUtils.maybeUpgradeSchema(bigQuery, existing)) return@withContext false
              }
            }
          }
          config.autoSchemaUpgrade ->
            if (!BigQueryUtils.maybeUpgradeSchema(bigQuery, table)) return@withContext false
          else -> logger.info { "Table already exists: $tableId" }
        }
        if (config.createViews) {
          BigQueryUtils.createAnalyticsViews(bigQuery, config) { ensureActive() }
        }
        true
      } catch (e: BigQueryException) {
        logger.error(e) { "Failed to ensure BigQuery table exists" }
        false
      } catch (e: RuntimeException) {
        if (e is CancellationException) throw e
        logger.error(e) { "Failed to ensure BigQuery table exists" }
        false
      }
    }

  private fun toolOperationId(toolContext: ToolContext): String =
    toolContext.functionCallId?.ifEmpty { null } ?: traceManager.acquireToolCallId(toolContext)

  private fun releaseToolOperationId(toolContext: ToolContext): String? =
    toolContext.functionCallId?.ifEmpty { null } ?: traceManager.releaseToolCallId(toolContext)

  // A callback is guarded against exceptions, which are logged but never thrown into the run.
  private inline fun guarded(callback: String, block: () -> Unit) {
    try {
      block()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      logger.warn { "BigQuery analytics callback $callback failed: ${e::class.simpleName}" }
    }
  }

  // A provider is resolved per turn.
  private fun instructionText(instruction: Instruction?): String? =
    when (instruction) {
      is Instruction.Text -> instruction.text.ifEmpty { null }
      is Instruction.Structured -> joinedText(instruction.content)
      is Instruction.Provider,
      null -> null
    }

  private fun agentSpanKind(agent: BaseAgent): String = "agent:${agent.name}"

  private fun agentStartingAttributes(agent: BaseAgent, llmAgent: LlmAgent?): Map<String, Any?> =
    buildMap {
      if (llmAgent?.instruction is Instruction.Provider) put("instruction_source", "provider")
      joinedText(llmAgent?.staticInstruction)?.let { put("static_instruction", it) }
      if (agent.description.isNotEmpty()) put("agent_description", agent.description)
      if (agent.subAgents.isNotEmpty()) put("sub_agents", agent.subAgents.map { it.name })
    }

  // Joins the non-empty text parts.
  private fun joinedText(content: Content?): String? =
    content?.parts?.mapNotNull { it.text?.ifEmpty { null } }?.joinToString("\n")?.ifEmpty { null }

  // Parameters that fail to serialize are left off.
  private fun toolDeclaration(declaration: FunctionDeclaration): Map<String, Any?> = buildMap {
    put("name", declaration.name)
    if (declaration.description.isNotEmpty()) put("description", declaration.description)
    val parameters = declaration.parameters
    if (parameters != null) {
      try {
        put("parameters", mapper.valueToTree<JsonNode>(parameters).also(::removeNullFields))
      } catch (e: IllegalArgumentException) {
        logger.debug { "Tool parameters not logged: ${e::class.simpleName}" }
      }
    }
  }

  private fun removeNullFields(node: JsonNode) {
    if (node is ObjectNode) node.remove(node.properties().filter { it.value.isNull }.map { it.key })
    node.forEach(::removeNullFields)
  }

  // A streaming chunk has no finish reason or cache type yet.
  private fun llmResponseAttributes(response: LlmResponse): Map<String, Any?> = buildMap {
    val usage = response.usageMetadata
    if (usage != null) put("usage_metadata", usageAttributes(usage))
    if (!response.partial) {
      response.finishReason?.let { put("finish_reason", it.name) }
      // Attached cache metadata means ADK's explicit cache served the tokens.
      put(
        "cache_type",
        when {
          (usage?.cachedContentTokenCount ?: 0) <= 0 -> "none"
          response.cacheMetadata != null -> "explicit"
          else -> "implicit"
        },
      )
    }
    response.cacheMetadata?.let { put("cache_metadata", cacheMetadataAttributes(it)) }
  }

  // Snake_case keys, without the per-modality token details.
  private fun usageAttributes(usage: UsageMetadata): Map<String, Any?> =
    mapOf(
        "prompt_token_count" to usage.promptTokenCount,
        "candidates_token_count" to usage.candidatesTokenCount,
        "total_token_count" to usage.totalTokenCount,
        "cached_content_token_count" to usage.cachedContentTokenCount,
        "thoughts_token_count" to usage.thoughtsTokenCount,
        "tool_use_prompt_token_count" to usage.toolUsePromptTokenCount,
        "traffic_type" to usage.trafficType,
      )
      .filterValues { it != null }

  // Snake_case keys, with times in epoch seconds.
  private fun cacheMetadataAttributes(cache: CacheMetadata): Map<String, Any?> =
    mapOf(
        "cache_name" to cache.cacheName,
        "expire_time" to cache.expireTime?.let { it / 1000.0 },
        "fingerprint" to cache.fingerprint,
        "invocations_used" to cache.invocationsUsed,
        "contents_count" to cache.contentsCount,
        "created_at" to cache.createdAt?.let { it / 1000.0 },
      )
      .filterValues { it != null }

  private fun extractLatency(eventData: EventData): Map<String, Any?>? =
    mapOf(
        "total_ms" to eventData.latency?.toMillis(),
        "time_to_first_token_ms" to eventData.timeToFirstToken?.toMillis(),
      )
      .filterValues { it != null }
      .ifEmpty { null }

  private fun getAttributes(
    eventData: EventData,
    invocationContext: InvocationContext,
  ): Map<String, Any?> = buildMap {
    putAll(eventData.extraAttributes)
    // The envelope's own keys win over adkExtras.
    put(
      BigQuerySchema.ADK_ATTRIBUTE_KEY,
      eventData.adkExtras + adkEnvelope(invocationContext, eventData.sourceEvent),
    )
    put("root_agent_name", traceManager.getRootAgentName(invocationContext))
    if (!eventData.model.isNullOrEmpty()) put("model", eventData.model)
    if (!eventData.modelVersion.isNullOrEmpty()) put("model_version", eventData.modelVersion)

    if (config.logSessionMetadata) {
      try {
        put("session_metadata", sessionMetadata(invocationContext.session))
      } catch (e: RuntimeException) {
        if (e is CancellationException) throw e
        logger.warn { "Failed to log session metadata: ${e::class.simpleName}" }
      }
    }

    if (config.customTags.isNotEmpty()) put("custom_tags", config.customTags)
  }

  private fun sessionMetadata(session: Session): Map<String, Any?> = buildMap {
    put("session_id", session.key.id)
    put("user_id", session.key.userId)
    put("app_name", session.key.appName)
    if (session.state.isNotEmpty()) put("state", session.state.toMap())
  }

  // Only a row built from a runner event gets the event keys.
  private fun adkEnvelope(
    invocationContext: InvocationContext,
    sourceEvent: Event?,
  ): Map<String, Any?> = buildMap {
    put("schema_version", BigQuerySchema.ADK_ENVELOPE_SCHEMA_VERSION)
    put("app_name", invocationContext.session.key.appName)
    if (sourceEvent != null) {
      if (sourceEvent.id.isNotEmpty()) put("source_event_id", sourceEvent.id)
      put("branch", sourceEvent.branch)
    }
  }

  private fun closeSpan(invocationContext: InvocationContext, kind: String): EventData {
    val popped = traceManager.unwindSpan(invocationContext, kind) ?: return EventData()
    return EventData(
      latency = popped.duration,
      spanIdOverride = popped.spanId,
      parentSpanIdOverride = popped.parentSpanId ?: traceManager.getCurrentSpanId(invocationContext),
    )
  }

  private fun getToolOrigin(tool: BaseTool): String =
    when {
      tool is McpTool -> "MCP"
      tool.name == "transfer_to_agent" -> "TRANSFER_AGENT"
      tool is AgentTool -> "SUB_AGENT"
      tool is FunctionTool -> "LOCAL"
      else -> "UNKNOWN"
    }

  private fun pairKeys(toolName: String, functionCallId: String?): Map<String, Any?> = buildMap {
    put("pause_kind", HITL_PAUSE_KIND_MAP[toolName] ?: "tool")
    if (!functionCallId.isNullOrEmpty()) put("function_call_id", functionCallId)
  }

  private class Pipeline(val processor: BatchProcessor, val writer: RowWriter)

  /**
   * Fluent builder for [BigQueryAgentAnalyticsPlugin], provided primarily for Java callers. Any
   * property left unset falls back to the same default as the constructor.
   */
  @AdkJavaInteropApi
  @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
  class Builder {
    private var config: BigQueryLoggerConfig? = null
    private var bigQuery: BigQuery? = null

    @Suppress("GlobalCoroutineDispatchers") private var ioContext: CoroutineContext = Dispatchers.IO

    private var timeSource: TimeSource = TimeSource.Monotonic

    fun config(config: BigQueryLoggerConfig): Builder = apply { this.config = config }

    fun bigQuery(bigQuery: BigQuery): Builder = apply { this.bigQuery = bigQuery }

    fun ioContext(ioContext: CoroutineContext): Builder = apply { this.ioContext = ioContext }

    fun timeSource(timeSource: TimeSource): Builder = apply { this.timeSource = timeSource }

    fun build(): BigQueryAgentAnalyticsPlugin {
      val config = checkNotNull(config) { "BigQueryAgentAnalyticsPlugin.Builder requires config." }
      // Defer the bigQuery default to the constructor so the two never drift apart.
      val bigQuery = bigQuery
      return if (bigQuery == null) {
        BigQueryAgentAnalyticsPlugin(
          config = config,
          ioContext = ioContext,
          timeSource = timeSource,
        )
      } else {
        BigQueryAgentAnalyticsPlugin(
          config = config,
          bigQuery = bigQuery,
          ioContext = ioContext,
          timeSource = timeSource,
        )
      }
    }
  }

  companion object {
    private val TABLE_CREATION_RETRY_INTERVAL = 10.seconds
    private val logger = LoggerFactory.getLogger(BigQueryAgentAnalyticsPlugin::class)
    private const val STATUS_OK = "OK"
    private const val STATUS_ERROR = "ERROR"
    private const val FORMATTER_FAILED = "[FORMATTER_FAILED]"
    private const val LLM_REQUEST_KIND = "llm_request"
    private const val TOOL_KIND = "tool"

    // Rows lost because the table could not be created; counted by the plugin.
    private const val SETUP_UNAVAILABLE = "setup_unavailable"

    @AdkJavaInteropApi @JvmStatic fun builder(): Builder = Builder()

    // Rows that close an invocation, which may take the queue slots kept for them.
    private val TERMINAL_EVENT_TYPES =
      setOf(EventType.INVOCATION_COMPLETED, EventType.INVOCATION_ERROR)

    // Plugins that may hold rows; weak, so that a plugin dropped without close can be collected.
    private val openPlugins: MutableSet<BigQueryAgentAnalyticsPlugin> =
      Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))
    private val shutdownHookRegistered = AtomicBoolean(false)

    // One hook for every plugin: it writes the rows still queued when the JVM exits.
    private fun registerShutdownHook() {
      if (shutdownHookRegistered.compareAndSet(false, true)) {
        Runtime.getRuntime().addShutdownHook(Thread(::closeOpenPlugins, "bigquery-analytics-exit"))
      }
    }

    // Internal for tests; closes in parallel, so exit waits only for the slowest close.
    internal fun closeOpenPlugins() {
      // Unlike toList, this copy does not throw if the GC clears an entry mid-copy.
      val plugins = synchronized(openPlugins) { openPlugins.toMutableList() }
      val closers = plugins.map { plugin ->
        thread(name = "bigquery-analytics-exit-close") { plugin.close() }
      }
      for (closer in closers) closer.join()
    }

    private val HITL_EVENT_TYPES =
      mapOf(
        "adk_request_credential" to EventType.HITL_CREDENTIAL_REQUEST,
        "adk_request_confirmation" to EventType.HITL_CONFIRMATION_REQUEST,
        "adk_request_input" to EventType.HITL_INPUT_REQUEST,
      )

    private val HITL_PAUSE_KIND_MAP =
      mapOf(
        "adk_request_credential" to "hitl_credential",
        "adk_request_confirmation" to "hitl_confirmation",
        "adk_request_input" to "hitl_input",
      )

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
