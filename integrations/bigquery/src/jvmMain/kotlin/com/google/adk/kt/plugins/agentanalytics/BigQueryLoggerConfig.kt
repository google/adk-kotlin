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

import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.auth.Credentials
import java.time.Duration
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic

/**
 * Configuration for the BigQueryAgentAnalyticsPlugin.
 *
 * @property projectId Google Cloud project that holds the dataset.
 * @property datasetId BigQuery dataset that holds the events table.
 * @property enabled Whether the plugin logs anything.
 * @property location BigQuery location of the dataset, such as `US`.
 * @property tableName Events table, created on first use if it does not exist.
 * @property credentials Credentials of the BigQuery client the plugin creates if no client is
 *   passed in; `null` uses Application Default Credentials.
 * @property eventAllowlist Event types to log, such as `LLM_REQUEST`; empty logs every type.
 * @property eventDenylist Event types never to log, even when allowlisted.
 * @property maxContentLength Budget for each logged string in UTF-8 bytes, including the
 *   `...[TRUNCATED]` marker; `-1` is unlimited.
 * @property clusteringFields Clustering columns for a newly created table.
 * @property logMultiModalContent Whether to fill the `content_parts` column.
 * @property logSessionMetadata Whether to log the session ids and state under
 *   `attributes.session_metadata`.
 * @property customTags Tags logged with every event under `attributes.custom_tags`.
 * @property autoSchemaUpgrade Whether to add missing columns to an existing table, which needs the
 *   `bigquery.tables.update` permission; rows are dropped until the upgrade succeeds. When off, the
 *   table must already have every column.
 * @property createViews Whether to create or replace one view per event type when the first event
 *   is logged.
 * @property viewPrefix Prefix of the view names, so `v` gives `v_llm_request`. Views are skipped if
 *   this prefix or the table's dataset or name has characters other than letters, digits, `_` and
 *   `-`, or if [projectId] is not a valid project identifier or `domain:project` identifier.
 * @property contentFormatter Called with each event's content and type; its result is logged in
 *   place of the content. If it throws, `[FORMATTER_FAILED]` is logged instead.
 * @property retryConfig How a batch of rows that fails to write is retried.
 * @property batchSize Maximum rows per write request; at least 1.
 * @property batchFlushInterval Longest the background writer waits for a batch to fill once it has
 *   a row; must be positive.
 * @property shutdownTimeout How long [BigQueryAgentAnalyticsPlugin.close] waits for queued rows to
 *   be written; must be positive. Rows still queued after it are dropped.
 * @property queueMaxSize Maximum rows queued in memory, at least 1; rows logged while it is full
 *   are dropped. About 1% of the slots, at least 2 but never all of them, are kept for
 *   `INVOCATION_COMPLETED` and `INVOCATION_ERROR` rows.
 * @property flushOnRunEnd Whether a run waits, before it completes, for every row logged so far by
 *   any run to be written or dropped. As in Python ADK, the wait has no time limit.
 */
data class BigQueryLoggerConfig
@JvmOverloads
constructor(
  val projectId: String,
  val datasetId: String,
  val enabled: Boolean = true,
  val location: String = "US",
  val tableName: String = "agent_events",
  val credentials: Credentials? = null,
  val eventAllowlist: Set<String> = emptySet(),
  val eventDenylist: Set<String> = emptySet(),
  val maxContentLength: Int = 500 * 1024,
  val clusteringFields: List<String> = BigQuerySchema.getDefaultClusteringFields(),
  val logMultiModalContent: Boolean = true,
  val logSessionMetadata: Boolean = true,
  val customTags: Map<String, Any?> = emptyMap(),
  val autoSchemaUpgrade: Boolean = true,
  val createViews: Boolean = false,
  val viewPrefix: String = "v",
  val contentFormatter: ((Any, String) -> Any?)? = null,
  val retryConfig: RetryConfig = RetryConfig(),
  val batchSize: Int = 1,
  val batchFlushInterval: Duration = Duration.ofSeconds(1),
  val shutdownTimeout: Duration = Duration.ofSeconds(10),
  val queueMaxSize: Int = 10_000,
  val flushOnRunEnd: Boolean = true,
) {

  init {
    require(maxContentLength == -1 || maxContentLength >= 1) {
      "maxContentLength must be -1 (unlimited) or >= 1, got $maxContentLength."
    }
    require(batchSize >= 1) { "batchSize must be >= 1, got $batchSize." }
    require(batchFlushInterval > Duration.ZERO) {
      "batchFlushInterval must be positive, got $batchFlushInterval."
    }
    require(shutdownTimeout > Duration.ZERO) {
      "shutdownTimeout must be positive, got $shutdownTimeout."
    }
    require(queueMaxSize >= 1) { "queueMaxSize must be >= 1, got $queueMaxSize." }
  }

  /**
   * Returns a [Builder] initialized with this instance's properties, primarily for Java callers.
   * Prefer it over `copy` from Java: `copy` takes every property positionally, so its signature
   * changes whenever a property is added.
   */
  @AdkJavaInteropApi
  fun toBuilder(): Builder =
    Builder()
      .projectId(projectId)
      .datasetId(datasetId)
      .enabled(enabled)
      .location(location)
      .tableName(tableName)
      .credentials(credentials)
      .eventAllowlist(eventAllowlist)
      .eventDenylist(eventDenylist)
      .maxContentLength(maxContentLength)
      .clusteringFields(clusteringFields)
      .logMultiModalContent(logMultiModalContent)
      .logSessionMetadata(logSessionMetadata)
      .customTags(customTags)
      .autoSchemaUpgrade(autoSchemaUpgrade)
      .createViews(createViews)
      .viewPrefix(viewPrefix)
      .contentFormatter(contentFormatter)
      .retryConfig(retryConfig)
      .batchSize(batchSize)
      .batchFlushInterval(batchFlushInterval)
      .shutdownTimeout(shutdownTimeout)
      .queueMaxSize(queueMaxSize)
      .flushOnRunEnd(flushOnRunEnd)

  /**
   * Fluent builder for [BigQueryLoggerConfig], provided primarily for Java callers. Any property
   * left unset falls back to the same default as the constructor.
   */
  @AdkJavaInteropApi
  @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
  class Builder {
    private var projectId: String? = null
    private var datasetId: String? = null
    private var enabled: Boolean = true
    private var location: String = "US"
    private var tableName: String = "agent_events"
    private var credentials: Credentials? = null
    private var eventAllowlist: Set<String> = emptySet()
    private var eventDenylist: Set<String> = emptySet()
    private var maxContentLength: Int = 500 * 1024
    private var clusteringFields: List<String> = BigQuerySchema.getDefaultClusteringFields()
    private var logMultiModalContent: Boolean = true
    private var logSessionMetadata: Boolean = true
    private var customTags: Map<String, Any?> = emptyMap()
    private var autoSchemaUpgrade: Boolean = true
    private var createViews: Boolean = false
    private var viewPrefix: String = "v"
    private var contentFormatter: ((Any, String) -> Any?)? = null
    private var retryConfig: RetryConfig = RetryConfig()
    private var batchSize: Int = 1
    private var batchFlushInterval: Duration = Duration.ofSeconds(1)
    private var shutdownTimeout: Duration = Duration.ofSeconds(10)
    private var queueMaxSize: Int = 10_000
    private var flushOnRunEnd: Boolean = true

    fun projectId(projectId: String): Builder = apply { this.projectId = projectId }

    fun datasetId(datasetId: String): Builder = apply { this.datasetId = datasetId }

    fun enabled(enabled: Boolean): Builder = apply { this.enabled = enabled }

    fun location(location: String): Builder = apply { this.location = location }

    fun tableName(tableName: String): Builder = apply { this.tableName = tableName }

    fun credentials(credentials: Credentials?): Builder = apply { this.credentials = credentials }

    fun eventAllowlist(eventAllowlist: Set<String>): Builder = apply {
      this.eventAllowlist = eventAllowlist
    }

    fun eventDenylist(eventDenylist: Set<String>): Builder = apply {
      this.eventDenylist = eventDenylist
    }

    fun maxContentLength(maxContentLength: Int): Builder = apply {
      this.maxContentLength = maxContentLength
    }

    fun clusteringFields(clusteringFields: List<String>): Builder = apply {
      this.clusteringFields = clusteringFields
    }

    fun logMultiModalContent(logMultiModalContent: Boolean): Builder = apply {
      this.logMultiModalContent = logMultiModalContent
    }

    fun logSessionMetadata(logSessionMetadata: Boolean): Builder = apply {
      this.logSessionMetadata = logSessionMetadata
    }

    fun customTags(customTags: Map<String, Any?>): Builder = apply { this.customTags = customTags }

    fun autoSchemaUpgrade(autoSchemaUpgrade: Boolean): Builder = apply {
      this.autoSchemaUpgrade = autoSchemaUpgrade
    }

    fun createViews(createViews: Boolean): Builder = apply { this.createViews = createViews }

    fun viewPrefix(viewPrefix: String): Builder = apply { this.viewPrefix = viewPrefix }

    fun contentFormatter(contentFormatter: ((Any, String) -> Any?)?): Builder = apply {
      this.contentFormatter = contentFormatter
    }

    fun retryConfig(retryConfig: RetryConfig): Builder = apply { this.retryConfig = retryConfig }

    fun batchSize(batchSize: Int): Builder = apply { this.batchSize = batchSize }

    fun batchFlushInterval(batchFlushInterval: Duration): Builder = apply {
      this.batchFlushInterval = batchFlushInterval
    }

    fun shutdownTimeout(shutdownTimeout: Duration): Builder = apply {
      this.shutdownTimeout = shutdownTimeout
    }

    fun queueMaxSize(queueMaxSize: Int): Builder = apply { this.queueMaxSize = queueMaxSize }

    fun flushOnRunEnd(flushOnRunEnd: Boolean): Builder = apply {
      this.flushOnRunEnd = flushOnRunEnd
    }

    fun build(): BigQueryLoggerConfig =
      BigQueryLoggerConfig(
        projectId = checkNotNull(projectId) { "BigQueryLoggerConfig.Builder requires projectId." },
        datasetId = checkNotNull(datasetId) { "BigQueryLoggerConfig.Builder requires datasetId." },
        enabled = enabled,
        location = location,
        tableName = tableName,
        credentials = credentials,
        eventAllowlist = eventAllowlist,
        eventDenylist = eventDenylist,
        maxContentLength = maxContentLength,
        clusteringFields = clusteringFields,
        logMultiModalContent = logMultiModalContent,
        logSessionMetadata = logSessionMetadata,
        customTags = customTags,
        autoSchemaUpgrade = autoSchemaUpgrade,
        createViews = createViews,
        viewPrefix = viewPrefix,
        contentFormatter = contentFormatter,
        retryConfig = retryConfig,
        batchSize = batchSize,
        batchFlushInterval = batchFlushInterval,
        shutdownTimeout = shutdownTimeout,
        queueMaxSize = queueMaxSize,
        flushOnRunEnd = flushOnRunEnd,
      )
  }

  companion object {
    @AdkJavaInteropApi @JvmStatic fun builder(): Builder = Builder()
  }
}
