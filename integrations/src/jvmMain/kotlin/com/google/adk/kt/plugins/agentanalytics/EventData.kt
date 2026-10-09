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

import java.time.Duration

/** Internal data structure holding metadata overrides for BigQuery event rows. */
internal data class EventData(
  val traceIdOverride: String? = null,
  val spanIdOverride: String? = null,
  val parentSpanIdOverride: String? = null,
  val status: String? = null,
  val errorMessage: String? = null,
  val latency: Duration? = null,
  val timeToFirstToken: Duration? = null,
  val model: String? = null,
  val modelVersion: String? = null,
  val usageMetadata: Map<String, Any?>? = null,
  val extraAttributes: Map<String, Any?> = emptyMap(),
  val fallbackAgentName: String? = null,
)
