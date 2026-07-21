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
import com.google.cloud.bigquery.FieldList
import com.google.cloud.bigquery.Schema
import com.google.cloud.bigquery.StandardSQLTypeName

/** Utility for defining the BigQuery events table schema. */
object BigQuerySchema {

  const val SCHEMA_VERSION = "1"
  const val SCHEMA_VERSION_LABEL_KEY = "adk_schema_version"

  /** Returns names of fields to cluster by default. */
  fun getDefaultClusteringFields(): List<String> = listOf("event_type", "agent", "user_id")

  /**
   * Returns the BigQuery schema for the events table.
   *
   * Defines fields for metadata, latency, and attributes.
   */
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
      Field.newBuilder(
          "content_parts",
          StandardSQLTypeName.STRUCT,
          FieldList.of(
            Field.newBuilder("mime_type", StandardSQLTypeName.STRING)
              .setMode(Field.Mode.NULLABLE)
              .setDescription("The MIME type of the content part.")
              .build(),
            Field.newBuilder("uri", StandardSQLTypeName.STRING)
              .setMode(Field.Mode.NULLABLE)
              .setDescription("The URI of the content part if stored externally.")
              .build(),
            Field.newBuilder(
                "object_ref",
                StandardSQLTypeName.STRUCT,
                FieldList.of(
                  Field.newBuilder("uri", StandardSQLTypeName.STRING)
                    .setMode(Field.Mode.NULLABLE)
                    .build(),
                  Field.newBuilder("version", StandardSQLTypeName.STRING)
                    .setMode(Field.Mode.NULLABLE)
                    .build(),
                  Field.newBuilder("authorizer", StandardSQLTypeName.STRING)
                    .setMode(Field.Mode.NULLABLE)
                    .build(),
                  Field.newBuilder("details", StandardSQLTypeName.JSON)
                    .setMode(Field.Mode.NULLABLE)
                    .build(),
                ),
              )
              .setMode(Field.Mode.NULLABLE)
              .setDescription("The ObjectRef of the content part if stored externally.")
              .build(),
            Field.newBuilder("text", StandardSQLTypeName.STRING)
              .setMode(Field.Mode.NULLABLE)
              .setDescription("The raw text content.")
              .build(),
            Field.newBuilder("part_index", StandardSQLTypeName.INT64)
              .setMode(Field.Mode.NULLABLE)
              .setDescription("The zero-based index of this part.")
              .build(),
            Field.newBuilder("part_attributes", StandardSQLTypeName.STRING)
              .setMode(Field.Mode.NULLABLE)
              .setDescription("Additional metadata as a JSON object string.")
              .build(),
            Field.newBuilder("storage_mode", StandardSQLTypeName.STRING)
              .setMode(Field.Mode.NULLABLE)
              .setDescription("Indicates how the content part is stored.")
              .build(),
          ),
        )
        .setMode(Field.Mode.REPEATED)
        .setDescription("Multi-modal events content parts.")
        .build(),
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
        .build(),
    )
  }
}
