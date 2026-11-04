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
import java.util.concurrent.CopyOnWriteArrayList

/** The trace id this server injects into a span's attributes. */
internal const val TRACE_ID_ATTRIBUTE = "trace_id"

/** The span id this server injects into a span's attributes. */
internal const val SPAN_ID_ATTRIBUTE = "span_id"

/**
 * Stores exported spans for the Development UI's trace views. It keeps the attributes of event
 * spans (`call_llm`, `send_data`, tool spans) by `gcp.vertex.agent.event_id`, and each session's
 * distinct trace ids, taken from any span that carries the session id.
 */
class ApiServerSpanExporter : SpanExporter {

  companion object {
    private const val MAX_EVENTS_TO_STORE = 1000
    private const val MAX_SESSIONS_TO_STORE = 1000
    private const val MAX_TOTAL_SPANS_TO_STORE = 10000
    private val SESSION_ID_KEY = AttributeKey.stringKey("gcp.vertex.agent.session_id")
    private val CONVERSATION_ID_KEY = AttributeKey.stringKey("gen_ai.conversation.id")
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

  // Copy-on-write lists, since the session trace route reads one while export may append to it.
  private val sessionToTraceIdsMap: MutableMap<String, CopyOnWriteArrayList<String>> =
    Collections.synchronizedMap(
      object :
        LinkedHashMap<String, CopyOnWriteArrayList<String>>(MAX_SESSIONS_TO_STORE, 0.75f, true) {
        override fun removeEldestEntry(
          eldest: MutableMap.MutableEntry<String, CopyOnWriteArrayList<String>>
        ): Boolean {
          return size > MAX_SESSIONS_TO_STORE
        }
      }
    )

  private val allExportedSpans = Collections.synchronizedList(mutableListOf<SpanData>())

  fun getEventTraceAttributes(eventId: String): Map<String, Any>? {
    return eventIdTraceStorage[eventId]
  }

  /** A copy of each session's trace ids, safe to iterate while spans are exported. */
  fun getSessionToTraceIdsMap(): Map<String, List<String>> =
    synchronized(sessionToTraceIdsMap) { sessionToTraceIdsMap.toMap() }

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
          attributesMap[TRACE_ID_ATTRIBUTE] = span.spanContext.traceId
          attributesMap[SPAN_ID_ATTRIBUTE] = span.spanContext.spanId
          attributesMap.putIfAbsent("gcp.vertex.agent.event_id", eventId)
          eventIdTraceStorage[eventId] = attributesMap
        }
      }

      val sessionId = sessionIdOf(span) ?: continue
      sessionToTraceIdsMap
        .computeIfAbsent(sessionId) { CopyOnWriteArrayList() }
        .addIfAbsent(span.spanContext.traceId)
    }

    return CompletableResultCode.ofSuccess()
  }

  /** The session id on [span]: `gcp.vertex.agent.session_id`, else `gen_ai.conversation.id`. */
  private fun sessionIdOf(span: SpanData): String? =
    span.attributes.get(SESSION_ID_KEY)?.takeIf { it.isNotEmpty() }
      ?: span.attributes.get(CONVERSATION_ID_KEY)?.takeIf { it.isNotEmpty() }

  override fun flush(): CompletableResultCode {
    return CompletableResultCode.ofSuccess()
  }

  override fun shutdown(): CompletableResultCode {
    return CompletableResultCode.ofSuccess()
  }
}
