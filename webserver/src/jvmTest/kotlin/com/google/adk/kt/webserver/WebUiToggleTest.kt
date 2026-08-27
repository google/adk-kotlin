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

import com.google.adk.kt.webserver.routes.WEB_UI_ENABLED_PROPERTY
import com.google.adk.kt.webserver.routes.isWebUiEnabled
import com.google.common.truth.Truth.assertThat
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * The `adk.web.ui.enabled` property decides whether the Development UI routes are mounted.
 *
 * `/` is the discriminator: `staticRoutes` registers its redirect only when the UI is mounted.
 */
@RunWith(JUnit4::class)
class WebUiToggleTest {
  private val sessionService = FakeSessionService()
  private val artifactService = FakeArtifactService()
  private val agentLoader = FakeAgentLoader()

  @Test
  fun webUi_unset_isMounted() =
    withWebUiProperty(null) {
      testApplication {
        installAdk()

        assertThat(rootStatus()).isEqualTo(HttpStatusCode.Found)
      }
    }

  @Test
  fun webUi_disabled_isNotMounted() =
    withWebUiProperty("false") {
      testApplication {
        installAdk()

        assertThat(rootStatus()).isEqualTo(HttpStatusCode.NotFound)
        assertThat(client.get(DEV_UI_INDEX).status).isEqualTo(HttpStatusCode.NotFound)
        // The contract endpoints are untouched by the toggle.
        assertThat(client.get("/health").status).isEqualTo(HttpStatusCode.OK)
      }
    }

  @Test
  fun webUi_paddedValue_isTrimmedAndHonoured() =
    withWebUiProperty(" false ") {
      testApplication {
        installAdk()

        assertThat(rootStatus()).isEqualTo(HttpStatusCode.NotFound)
      }
    }

  @Test
  fun webUi_disabledInMixedCase_isNotMounted() =
    withWebUiProperty("False") {
      testApplication {
        installAdk()

        assertThat(rootStatus()).isEqualTo(HttpStatusCode.NotFound)
      }
    }

  @Test
  fun webUi_nonBooleanValue_fallsBackToDefault() =
    withWebUiProperty("perhaps") {
      testApplication {
        installAdk()

        assertThat(rootStatus()).isEqualTo(HttpStatusCode.Found)
      }
    }

  @Test
  fun webUi_disabledInConfig_isNotMounted() =
    withWebUiProperty(null) {
      testApplication {
        environment { config = MapApplicationConfig(WEB_UI_ENABLED_PROPERTY to "false") }
        installAdk()

        assertThat(rootStatus()).isEqualTo(HttpStatusCode.NotFound)
      }
    }

  @Test
  fun webUi_nonBooleanConfigValue_fallsBackToDefault() =
    withWebUiProperty(null) {
      testApplication {
        environment { config = MapApplicationConfig(WEB_UI_ENABLED_PROPERTY to "perhaps") }
        installAdk()

        assertThat(rootStatus()).isEqualTo(HttpStatusCode.Found)
      }
    }

  @Test
  fun webUi_enabledInConfig_overridesCallerDefault() =
    withWebUiProperty(null) {
      testApplication {
        environment { config = MapApplicationConfig(WEB_UI_ENABLED_PROPERTY to "true") }
        application { assertThat(isWebUiEnabled(default = false)).isTrue() }

        client.get("/")
      }
    }

  @Test
  fun webUi_blankProperty_fallsThroughToConfig() =
    withWebUiProperty(" ") {
      testApplication {
        environment { config = MapApplicationConfig(WEB_UI_ENABLED_PROPERTY to "false") }
        installAdk()

        assertThat(rootStatus()).isEqualTo(HttpStatusCode.NotFound)
      }
    }

  @Test
  fun webUi_nonBooleanProperty_fallsThroughToConfig() =
    withWebUiProperty("no") {
      testApplication {
        environment { config = MapApplicationConfig(WEB_UI_ENABLED_PROPERTY to "false") }
        installAdk()

        assertThat(rootStatus()).isEqualTo(HttpStatusCode.NotFound)
      }
    }

  @Test
  fun webUi_property_winsOverConfig() =
    withWebUiProperty("true") {
      testApplication {
        environment { config = MapApplicationConfig(WEB_UI_ENABLED_PROPERTY to "false") }
        installAdk()

        assertThat(rootStatus()).isEqualTo(HttpStatusCode.Found)
      }
    }

  @Test
  fun webUi_unsetEverywhere_usesCallerDefault() =
    withWebUiProperty(null) {
      testApplication {
        application {
          assertThat(isWebUiEnabled(default = false)).isFalse()
          assertThat(isWebUiEnabled(default = true)).isTrue()
        }
        // Start the app here so a failed assertion is reported at this line, not at teardown.
        client.get("/")
      }
    }

  private fun ApplicationTestBuilder.installAdk() {
    // webUiEnabled = true, so each test varies only the property and the application config.
    application {
      adkApiModule(
        AdkServerConfig(
          agentLoader = agentLoader,
          sessionService = sessionService,
          artifactService = artifactService,
          webUiEnabled = true,
        )
      )
    }
  }

  /** Status of `/` without following the redirect, so the redirect itself is what is asserted. */
  private suspend fun ApplicationTestBuilder.rootStatus(): HttpStatusCode =
    createClient { followRedirects = false }.get("/").status

  /** Runs [body] with `adk.web.ui.enabled` set to [value], or unset when it is null. */
  private fun withWebUiProperty(value: String?, body: () -> Unit) {
    val previous: String? = System.getProperty(WEB_UI_ENABLED_PROPERTY)
    if (value == null) {
      System.clearProperty(WEB_UI_ENABLED_PROPERTY)
    } else {
      System.setProperty(WEB_UI_ENABLED_PROPERTY, value)
    }
    try {
      body()
    } finally {
      if (previous == null) {
        System.clearProperty(WEB_UI_ENABLED_PROPERTY)
      } else {
        System.setProperty(WEB_UI_ENABLED_PROPERTY, previous)
      }
    }
  }

  private companion object {
    const val DEV_UI_INDEX = "/dev-ui/index.html"
  }
}
