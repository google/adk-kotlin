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

package com.google.adk.kt.webserver

import com.google.adk.kt.webserver.routes.staticRoutes
import com.google.common.truth.Truth.assertThat
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** The server resolves the Development UI from `browser/` on the classpath. */
@RunWith(JUnit4::class)
class DevUiAssetsTest {

  @Test
  fun devUi_index_isServedFromClasspath() = testStaticRoutes {
    val response = client.get("/dev-ui/index.html")

    assertThat(response.status).isEqualTo(HttpStatusCode.OK)
    assertThat(response.bodyAsText()).contains("<html")
  }

  @Test
  fun devUi_nestedAsset_isServedFromClasspath() = testStaticRoutes {
    // A nested path proves the whole asset tree is packaged, not just the entry point.
    assertThat(client.get("/dev-ui/assets/audio-processor.js").status).isEqualTo(HttpStatusCode.OK)
  }

  @Test
  fun devUi_runtimeConfig_pinsTelemetryOff() = testStaticRoutes {
    val config = client.get("/dev-ui/assets/config/runtime-config.json").bodyAsText()

    // Refreshes overwrite this; unpinned, the UI served by the API module asks for consent.
    assertThat(Json.parseToJsonElement(config).jsonObject["telemetry"])
      .isEqualTo(JsonPrimitive(false))
  }

  @Test
  fun devUi_prismThemes_areServed() = testStaticRoutes {
    // The UI loads these for code highlighting, but newer adk-web builds stopped shipping them.
    assertThat(client.get("/dev-ui/prism-light.css").status).isEqualTo(HttpStatusCode.OK)
    assertThat(client.get("/dev-ui/prism-dark.css").status).isEqualTo(HttpStatusCode.OK)
  }

  /** Runs [body] against the static routes, with the `adk.web.ui.dir` system property cleared. */
  private fun testStaticRoutes(body: suspend ApplicationTestBuilder.() -> Unit) {
    val previous: String? = System.getProperty(WEB_UI_DIR_PROPERTY)
    System.clearProperty(WEB_UI_DIR_PROPERTY)
    try {
      testApplication {
        application { routing { staticRoutes(this@application) } }
        body()
      }
    } finally {
      if (previous != null) System.setProperty(WEB_UI_DIR_PROPERTY, previous)
    }
  }

  private companion object {
    const val WEB_UI_DIR_PROPERTY = "adk.web.ui.dir"
  }
}
