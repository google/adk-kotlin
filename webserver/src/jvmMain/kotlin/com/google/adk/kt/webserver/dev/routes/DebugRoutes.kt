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

package com.google.adk.kt.webserver.dev.routes

import com.google.adk.kt.webserver.telemetry.ApiServerSpanExporter
import com.google.adk.kt.webserver.telemetry.SPAN_ID_ATTRIBUTE
import com.google.adk.kt.webserver.telemetry.TRACE_ID_ATTRIBUTE
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.opentelemetry.api.trace.SpanId

internal fun Route.debugRoutes(exporter: ApiServerSpanExporter, camelCase: Boolean = false) {

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
      call.respond(HttpStatusCode.OK, traceData.withInjectedKeysSpelled(camelCase))
    }
  }

  get("/debug/trace/session/{sessionId}") {
    val sessionId = call.parameters["sessionId"]

    if (sessionId == null) {
      call.respond(HttpStatusCode.BadRequest, mapOf("message" to "Missing sessionId"))
      return@get
    }

    val resultSpans =
      exporter.sessionSpans(sessionId).map { span ->
        mapOf(
          "name" to span.name,
          key(SPAN_ID_ATTRIBUTE, "spanId", camelCase) to span.spanContext.spanId,
          key(TRACE_ID_ATTRIBUTE, "traceId", camelCase) to span.spanContext.traceId,
          key("start_time", "startTime", camelCase) to span.startEpochNanos,
          key("end_time", "endTime", camelCase) to span.endEpochNanos,
          "attributes" to span.attributes.asMap().mapKeys { it.key.key },
          key("parent_span_id", "parentSpanId", camelCase) to
            span.parentSpanId.takeIf { SpanId.isValid(it) },
        )
      }

    call.respond(HttpStatusCode.OK, resultSpans)
  }
}

/** The non-enforced key, or the enforced camelCase one when the server enforces it. */
private fun key(legacy: String, camel: String, camelCase: Boolean) =
  if (camelCase) camel else legacy

/**
 * Renames the two keys this server injects into a span's attributes. The rest are OpenTelemetry
 * semantic conventions - dotted, with no camelCase form - so they are left alone.
 */
private fun Map<String, Any>.withInjectedKeysSpelled(camelCase: Boolean): Map<String, Any> =
  if (!camelCase) this
  else
    mapKeys { (name, _) ->
      when (name) {
        TRACE_ID_ATTRIBUTE -> "traceId"
        SPAN_ID_ATTRIBUTE -> "spanId"
        else -> name
      }
    }
