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

import com.google.cloud.bigquery.Field
import com.google.cloud.bigquery.Schema
import com.google.cloud.bigquery.StandardSQLTypeName

/** Utility for defining the BigQuery events table schema. */
object BigQuerySchema {

  const val SCHEMA_VERSION = "1"
  const val SCHEMA_VERSION_LABEL_KEY = "adk_schema_version"

  /** Returns the BigQuery schema for the events table. */
  fun getEventsSchema(): Schema {
    return Schema.of(
      Field.newBuilder("timestamp", StandardSQLTypeName.TIMESTAMP)
        .setMode(Field.Mode.REQUIRED)
        .setDescription("The UTC timestamp when the event occurred.")
        .build(),
      Field.newBuilder("event_type", StandardSQLTypeName.STRING)
        .setMode(Field.Mode.NULLABLE)
        .setDescription("The category of the event.")
        .build(),
      Field.newBuilder("agent", StandardSQLTypeName.STRING)
        .setMode(Field.Mode.NULLABLE)
        .setDescription("The name of the agent that generated this event.")
        .build(),
      Field.newBuilder("session_id", StandardSQLTypeName.STRING)
        .setMode(Field.Mode.NULLABLE)
        .setDescription("A unique identifier for the entire conversation session.")
        .build(),
      Field.newBuilder("invocation_id", StandardSQLTypeName.STRING)
        .setMode(Field.Mode.NULLABLE)
        .setDescription("A unique identifier for a single turn or execution.")
        .build(),
      Field.newBuilder("user_id", StandardSQLTypeName.STRING)
        .setMode(Field.Mode.NULLABLE)
        .setDescription("The identifier of the end-user.")
        .build(),
      Field.newBuilder("trace_id", StandardSQLTypeName.STRING)
        .setMode(Field.Mode.NULLABLE)
        .setDescription("OpenTelemetry trace ID.")
        .build(),
      Field.newBuilder("span_id", StandardSQLTypeName.STRING)
        .setMode(Field.Mode.NULLABLE)
        .setDescription("OpenTelemetry span ID.")
        .build(),
      Field.newBuilder("parent_span_id", StandardSQLTypeName.STRING)
        .setMode(Field.Mode.NULLABLE)
        .setDescription("OpenTelemetry parent span ID.")
        .build(),
      Field.newBuilder("content", StandardSQLTypeName.JSON)
        .setMode(Field.Mode.NULLABLE)
        .setDescription("The primary payload of the event.")
        .build(),
      // Skipping content_parts complex STRUCT for Phase 1 to keep it simple,
      // but keeping attributes, latency_ms, etc.
      Field.newBuilder("attributes", StandardSQLTypeName.JSON)
        .setMode(Field.Mode.NULLABLE)
        .setDescription("A JSON object containing arbitrary key-value pairs.")
        .build(),
      Field.newBuilder("latency_ms", StandardSQLTypeName.JSON)
        .setMode(Field.Mode.NULLABLE)
        .setDescription("A JSON object containing latency measurements.")
        .build(),
      Field.newBuilder("status", StandardSQLTypeName.STRING)
        .setMode(Field.Mode.NULLABLE)
        .setDescription("The outcome of the event.")
        .build(),
      Field.newBuilder("error_message", StandardSQLTypeName.STRING)
        .setMode(Field.Mode.NULLABLE)
        .setDescription("Detailed error message if the status is 'ERROR'.")
        .build(),
      Field.newBuilder("is_truncated", StandardSQLTypeName.BOOL)
        .setMode(Field.Mode.NULLABLE)
        .setDescription("Indicates if the 'content' field was truncated.")
        .build()
    )
  }
}
