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
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.net.URI
import java.nio.file.Files
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * The Development UI redirects survive a reverse proxy that strips a path prefix: the `Location` is
 * relative, so the browser resolves it against the URL it actually used. That holds only while the
 * browser's URL ends in a slash, which is the form a proxy normally redirects to.
 */
@RunWith(JUnit4::class)
class RootRedirectPrefixTest {

  @Test
  fun root_behindPrefixStrippingProxy_resolvesToPrefixedDevUi() = withoutWebUiDir {
    val location = locationOf("/")

    // What a browser at https://gw.example.com/my-app/ computes from that Location.
    assertThat(URI(BROWSER_ROOT).resolve(location).toString())
      .isEqualTo("https://gw.example.com/my-app/dev-ui/")
  }

  @Test
  fun devUi_behindPrefixStrippingProxy_resolvesToPrefixedDevUiWithSlash() = withoutWebUiDir {
    val location = locationOf("/dev-ui")

    assertThat(URI(BROWSER_ROOT + "dev-ui").resolve(location).toString())
      .isEqualTo("https://gw.example.com/my-app/dev-ui/")
  }

  @Test
  fun root_withoutProxy_redirectsToDevUi() = withoutWebUiDir {
    val location = locationOf("/")

    assertThat(URI("http://localhost:8080/").resolve(location).toString())
      .isEqualTo("http://localhost:8080/dev-ui/")
  }

  @Test
  fun devUi_withOnDiskUiDir_resolvesToPrefixedDevUiWithSlash() {
    // The on-disk branch of staticRoutes registers its own copy of this redirect, so it needs its
    // own coverage; the tests above exercise the classpath fallback.
    val uiDir = Files.createTempDirectory("adk-dev-ui")
    val index = uiDir.resolve("index.html")
    Files.writeString(index, "<html></html>")
    try {
      withWebUiDir(uiDir.toString()) {
        val location = locationOf("/dev-ui")

        assertThat(URI(BROWSER_ROOT + "dev-ui").resolve(location).toString())
          .isEqualTo("https://gw.example.com/my-app/dev-ui/")
      }
    } finally {
      Files.deleteIfExists(index)
      Files.deleteIfExists(uiDir)
    }
  }

  /** The `Location` header of the redirect served for [path], with redirect following disabled. */
  private fun locationOf(path: String): String {
    var location: String? = null
    testApplication {
      application { routing { staticRoutes(this@application) } }
      val response = createClient { followRedirects = false }.get(path)

      assertThat(response.status).isEqualTo(HttpStatusCode.Found)
      location = response.headers[HttpHeaders.Location]
    }
    return checkNotNull(location)
  }

  private companion object {
    const val BROWSER_ROOT = "https://gw.example.com/my-app/"
  }
}
