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

import com.google.adk.kt.events.Event
import java.time.Duration

/** Optional fields and span overrides for one BigQuery event row. */
internal data class EventData(
  val spanIdOverride: String? = null,
  val parentSpanIdOverride: String? = null,
  val status: String? = null,
  val errorMessage: String? = null,
  val latency: Duration? = null,
  val timeToFirstToken: Duration? = null,
  val model: String? = null,
  val modelVersion: String? = null,
  val extraAttributes: Map<String, Any?> = emptyMap(),
  /** Keys merged into the `adk` attribute envelope. */
  val adkExtras: Map<String, Any?> = emptyMap(),
  /** The event the row is derived from, which adds its id and branch to the `adk` envelope. */
  val sourceEvent: Event? = null,
)
