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

package com.google.adk.kt.webserver.routes

import com.google.adk.kt.webserver.telemetry.ApiServerSpanExporter
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.debugRoutes(exporter: ApiServerSpanExporter) {

  get("/debug/trace/{eventId}") {
    val eventId = call.parameters["eventId"]

    if (eventId == null) {
      return@get
    }

    val traceData = exporter.getEventTraceAttributes(eventId)
    if (traceData == null) {
      call.respond(
        HttpStatusCode.NotFound,
        mapOf("message" to "Trace not found for eventId: $eventId"),
      )
    } else {
      call.respond(HttpStatusCode.OK, traceData)
    }
  }

  get("/debug/trace/session/{sessionId}") {
    val sessionId = call.parameters["sessionId"]

    if (sessionId == null) {
      call.respond(HttpStatusCode.BadRequest, mapOf("message" to "Missing sessionId"))
      return@get
    }

    val traceIdsForSession = exporter.getSessionToTraceIdsMap()[sessionId]

    if (traceIdsForSession.isNullOrEmpty()) {
      call.respond(HttpStatusCode.OK, emptyList<Any>())
      return@get
    }

    val allSpansSnapshot = exporter.getAllExportedSpans()
    val relevantTraceIds = traceIdsForSession.toSet()
    val resultSpans = mutableListOf<Map<String, Any?>>()

    for (span in allSpansSnapshot) {
      if (relevantTraceIds.contains(span.spanContext.traceId)) {
        val spanMap =
          mutableMapOf<String, Any?>(
            "name" to span.name,
            "span_id" to span.spanContext.spanId,
            "trace_id" to span.spanContext.traceId,
            "start_time" to span.startEpochNanos,
            "end_time" to span.endEpochNanos,
          )

        val attributesMap = mutableMapOf<String, Any>()
        span.attributes.forEach { key, value -> attributesMap[key.key] = value }
        spanMap["attributes"] = attributesMap

        val parentSpanId = span.parentSpanId
        if (parentSpanId != io.opentelemetry.api.trace.SpanId.getInvalid()) {
          spanMap["parent_span_id"] = parentSpanId
        } else {
          spanMap["parent_span_id"] = null
        }
        resultSpans.add(spanMap)
      }
    }

    call.respond(HttpStatusCode.OK, resultSpans)
  }
}
