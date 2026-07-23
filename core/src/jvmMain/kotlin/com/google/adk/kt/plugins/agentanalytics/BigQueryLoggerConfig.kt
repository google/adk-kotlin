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

import com.google.auth.Credentials
import java.time.Duration

/** Configuration for the BigQueryAgentAnalyticsPlugin. */
data class BigQueryLoggerConfig(
  val enabled: Boolean = true,
  val eventAllowlist: List<String> = emptyList(),
  val eventDenylist: List<String> = emptyList(),
  val maxContentLength: Int = 500 * 1024,
  val location: String = "us",
  val projectId: String,
  val datasetId: String = "agent_analytics",
  val tableName: String = "events",
  val clusteringFields: List<String> = listOf("event_type", "agent", "user_id"),
  val logMultiModalContent: Boolean = false,
  val retryConfig: RetryConfig = RetryConfig(),
  val batchSize: Int = 1,
  val batchFlushInterval: Duration = Duration.ofSeconds(1),
  val shutdownTimeout: Duration = Duration.ofSeconds(10),
  val queueMaxSize: Int = 10000,
  // For Phase 1, we might not need all of these, but keeping them for parity.
  val logSessionMetadata: Boolean = true,
  val customTags: Map<String, Any> = emptyMap(),
  val autoSchemaUpgrade: Boolean = true,
  val createViews: Boolean = false,
  val viewPrefix: String = "v",
  val gcsBucketName: String = "",
  val connectionId: String? = null,
  val credentials: Credentials? = null
) {
  init {
    require(batchSize > 0) { "batchSize must be positive, got $batchSize" }
    require(queueMaxSize > 0) { "queueMaxSize must be positive, got $queueMaxSize" }
    require(maxContentLength > 0) { "maxContentLength must be positive, got $maxContentLength" }
  }

  data class RetryConfig(
    val maxRetries: Int = 3,
    val initialDelay: Duration = Duration.ofSeconds(1),
    val multiplier: Double = 2.0,
    val maxDelay: Duration = Duration.ofSeconds(10)
  )
}
