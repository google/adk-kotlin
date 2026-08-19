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
import io.ktor.server.testing.testApplication
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** The server resolves the Development UI from `browser/` on the classpath. */
@RunWith(JUnit4::class)
class DevUiAssetsTest {

  @Test
  fun devUi_index_isServedFromClasspath() = withoutWebUiDir {
    testApplication {
      application { routing { staticRoutes(this@application) } }

      val response = client.get("/dev-ui/index.html")

      assertThat(response.status).isEqualTo(HttpStatusCode.OK)
      assertThat(response.bodyAsText()).contains("<html")
    }
  }

  @Test
  fun devUi_nestedAsset_isServedFromClasspath() = withoutWebUiDir {
    testApplication {
      application { routing { staticRoutes(this@application) } }

      // A nested path proves the whole asset tree is packaged, not just the entry point.
      assertThat(client.get("/dev-ui/assets/audio-processor.js").status)
        .isEqualTo(HttpStatusCode.OK)
    }
  }

  /** Runs [body] with the `adk.web.ui.dir` system property cleared. */
  private fun withoutWebUiDir(body: () -> Unit) {
    val previous: String? = System.getProperty(WEB_UI_DIR_PROPERTY)
    System.clearProperty(WEB_UI_DIR_PROPERTY)
    try {
      body()
    } finally {
      if (previous != null) System.setProperty(WEB_UI_DIR_PROPERTY, previous)
    }
  }

  private companion object {
    const val WEB_UI_DIR_PROPERTY = "adk.web.ui.dir"
  }
}
