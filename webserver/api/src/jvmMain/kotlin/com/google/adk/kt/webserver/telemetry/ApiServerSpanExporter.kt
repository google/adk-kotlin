/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.adk.kt.webserver.telemetry

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SpanExporter
import java.util.Collections

/**
 * A custom SpanExporter that stores relevant span data. It handles two types of trace data storage:
 * 1. Event-ID based: Stores attributes of specific spans (call_llm, send_data, tool_response) keyed
 *    by `gcp.vertex.agent.event_id`. This is used for debugging individual events.
 * 2. Session-ID based: Stores all exported spans and maintains a mapping from `session_id`
 *    (extracted from `call_llm` spans) to a list of `trace_id`s. This is used for retrieving all
 *    spans related to a session.
 */
class ApiServerSpanExporter : SpanExporter {

  companion object {
    private const val MAX_EVENTS_TO_STORE = 1000
    private const val MAX_SESSIONS_TO_STORE = 1000
    private const val MAX_TOTAL_SPANS_TO_STORE = 10000
  }

  internal val eventIdTraceStorage: MutableMap<String, Map<String, Any>> =
    Collections.synchronizedMap(
      object : LinkedHashMap<String, Map<String, Any>>(MAX_EVENTS_TO_STORE, 0.75f, true) {
        override fun removeEldestEntry(
          eldest: MutableMap.MutableEntry<String, Map<String, Any>>
        ): Boolean {
          return size > MAX_EVENTS_TO_STORE
        }
      }
    )

  internal val sessionToTraceIdsMap: MutableMap<String, MutableList<String>> =
    Collections.synchronizedMap(
      object : LinkedHashMap<String, MutableList<String>>(MAX_SESSIONS_TO_STORE, 0.75f, true) {
        override fun removeEldestEntry(
          eldest: MutableMap.MutableEntry<String, MutableList<String>>
        ): Boolean {
          return size > MAX_SESSIONS_TO_STORE
        }
      }
    )

  private val allExportedSpans = Collections.synchronizedList(mutableListOf<SpanData>())

  fun getEventTraceAttributes(eventId: String): Map<String, Any>? {
    return eventIdTraceStorage[eventId]
  }

  fun getSessionToTraceIdsMap(): Map<String, List<String>> {
    return sessionToTraceIdsMap
  }

  fun getAllExportedSpans(): List<SpanData> {
    synchronized(allExportedSpans) {
      return allExportedSpans.toList()
    }
  }

  override fun export(spans: Collection<SpanData>): CompletableResultCode {
    val currentBatch = spans.toList()
    allExportedSpans.addAll(currentBatch)
    synchronized(allExportedSpans) {
      if (allExportedSpans.size > MAX_TOTAL_SPANS_TO_STORE) {
        val toRemove = allExportedSpans.size - MAX_TOTAL_SPANS_TO_STORE
        allExportedSpans.subList(0, toRemove).clear()
      }
    }

    for (span in currentBatch) {
      val spanName = span.name
      if (
        spanName == "call_llm" ||
          spanName == "send_data" ||
          spanName.startsWith("tool_response") ||
          spanName.startsWith("execute_tool")
      ) {
        val eventId = span.attributes.get(AttributeKey.stringKey("gcp.vertex.agent.event_id"))
        if (!eventId.isNullOrEmpty()) {
          val attributesMap = mutableMapOf<String, Any>()
          span.attributes.forEach { key, value -> attributesMap[key.key] = value }
          attributesMap["trace_id"] = span.spanContext.traceId
          attributesMap["span_id"] = span.spanContext.spanId
          attributesMap.putIfAbsent("gcp.vertex.agent.event_id", eventId)
          eventIdTraceStorage[eventId] = attributesMap
        }
      }

      if (spanName == "call_llm") {
        val sessionId = span.attributes.get(AttributeKey.stringKey("gcp.vertex.agent.session_id"))
        if (!sessionId.isNullOrEmpty()) {
          val traceId = span.spanContext.traceId
          sessionToTraceIdsMap
            .computeIfAbsent(sessionId) { Collections.synchronizedList(mutableListOf()) }
            .add(traceId)
        }
      }
    }

    return CompletableResultCode.ofSuccess()
  }

  override fun flush(): CompletableResultCode {
    return CompletableResultCode.ofSuccess()
  }

  override fun shutdown(): CompletableResultCode {
    return CompletableResultCode.ofSuccess()
  }
}
