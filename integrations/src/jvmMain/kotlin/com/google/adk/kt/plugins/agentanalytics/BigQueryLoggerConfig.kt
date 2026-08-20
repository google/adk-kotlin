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

/** Configuration for the BigQueryAgentAnalyticsPlugin. */
data class BigQueryLoggerConfig(
  val projectId: String,
  val datasetId: String,
  val enabled: Boolean = true,
  val location: String = "US",
  val tableName: String = "agent_events",
  val credentials: Credentials? = null,
  val eventAllowlist: Set<String> = emptySet(),
  val eventDenylist: Set<String> = emptySet(),
  val maxContentLength: Int = 500 * 1024,
  val clusteringFields: List<String> = listOf("event_type", "agent", "user_id"),
  val logMultiModalContent: Boolean = true,
  val logSessionMetadata: Boolean = true,
  val customTags: Map<String, Any?> = emptyMap(),
  val autoSchemaUpgrade: Boolean = true,
  val createViews: Boolean = false,
  val viewPrefix: String = "v",
  val connectionId: String? = null,
  val contentFormatter: ((Any, String) -> Any?)? = null,
)
