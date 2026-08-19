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

import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.serialization.adkJson
import com.google.adk.kt.webserver.telemetry.ApiServerSpanExporter
import com.google.common.truth.Truth.assertThat
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@OptIn(FrameworkInternalApi::class)
@RunWith(JUnit4::class)
class DebugRoutesTest {

  @Test
  fun getTrace_existing_returnsData() = testApplication {
    val exporter = ApiServerSpanExporter()
    exporter.eventIdTraceStorage["test-event"] = mapOf("key" to "value")

    application {
      install(ContentNegotiation) { json(adkJson) }
      routing { debugRoutes(exporter) }
    }

    val response = client.get("/debug/trace/test-event")

    assertThat(response.status).isEqualTo(HttpStatusCode.OK)
    assertThat(response.bodyAsText()).contains("\"key\":\"value\"")
  }

  @Test
  fun getTrace_nonexistent_returnsNotFound() = testApplication {
    val exporter = ApiServerSpanExporter()

    application {
      install(ContentNegotiation) { json(adkJson) }
      routing { debugRoutes(exporter) }
    }

    val response = client.get("/debug/trace/nonexistent")

    assertThat(response.status).isEqualTo(HttpStatusCode.NotFound)
  }

  @Test
  fun getSessionTrace_empty_returnsEmptyList() = testApplication {
    val exporter = ApiServerSpanExporter()

    application {
      install(ContentNegotiation) { json(adkJson) }
      routing { debugRoutes(exporter) }
    }

    val response = client.get("/debug/trace/session/nonexistent-session")

    assertThat(response.status).isEqualTo(HttpStatusCode.OK)
    assertThat(response.bodyAsText()).isEqualTo("[]")
  }
}
