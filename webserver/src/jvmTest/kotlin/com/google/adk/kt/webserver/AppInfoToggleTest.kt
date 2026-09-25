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
 * Whether `/apps/{appName}/app-info` is mounted at all.
 *
 * The loader serves `mock-agent`, so a mounted route answers 200 and an unmounted one 404, which
 * separates "the endpoint is off" from "the app is unknown".
 */
@RunWith(JUnit4::class)
class AppInfoToggleTest {
  private val sessionService = FakeSessionService()
  private val artifactService = FakeArtifactService()
  private val agentLoader = FakeAgentLoader()

  @Test
  fun appInfo_unsetEverywhere_isNotMounted() =
    withAppInfoProperty(null) {
      testApplication {
        installAdk(includeAppInfo = null)

        assertThat(appInfoStatus()).isEqualTo(HttpStatusCode.NotFound)
      }
    }

  @Test
  fun appInfo_enabledByProperty_isMounted() =
    withAppInfoProperty("true") {
      testApplication {
        installAdk(includeAppInfo = null)

        assertThat(appInfoStatus()).isEqualTo(HttpStatusCode.OK)
      }
    }

  @Test
  fun appInfo_enabledOnTheServerConfig_isMounted() =
    withAppInfoProperty(null) {
      testApplication {
        installAdk(includeAppInfo = true)

        assertThat(appInfoStatus()).isEqualTo(HttpStatusCode.OK)
      }
    }

  @Test
  fun appInfo_property_overridesTheServerConfig() =
    withAppInfoProperty("true") {
      testApplication {
        installAdk(includeAppInfo = false)

        assertThat(appInfoStatus()).isEqualTo(HttpStatusCode.OK)
      }
    }

  @Test
  fun appInfo_propertyDeclining_unmountsWhatTheServerConfigAskedFor() =
    withAppInfoProperty("false") {
      testApplication {
        installAdk(includeAppInfo = true)

        // The lever that needs no rebuild: the endpoint reports every agent's instruction.
        assertThat(appInfoStatus()).isEqualTo(HttpStatusCode.NotFound)
      }
    }

  @Test
  fun appInfo_enabledInApplicationConfig_isMounted() =
    withAppInfoProperty(null) {
      testApplication {
        environment { config = MapApplicationConfig(APP_INFO_ENABLED_PROPERTY to "true") }
        installAdk(includeAppInfo = null)

        assertThat(appInfoStatus()).isEqualTo(HttpStatusCode.OK)
      }
    }

  @Test
  fun appInfo_paddedMixedCaseProperty_isTrimmedAndHonoured() =
    withAppInfoProperty(" True ") {
      testApplication {
        installAdk(includeAppInfo = null)

        assertThat(appInfoStatus()).isEqualTo(HttpStatusCode.OK)
      }
    }

  @Test
  fun appInfo_blankProperty_fallsThroughToTheApplicationConfig() =
    withAppInfoProperty("  ") {
      testApplication {
        environment { config = MapApplicationConfig(APP_INFO_ENABLED_PROPERTY to "true") }
        installAdk(includeAppInfo = null)

        assertThat(appInfoStatus()).isEqualTo(HttpStatusCode.OK)
      }
    }

  @Test
  fun appInfo_applicationConfig_overridesTheServerConfig() =
    withAppInfoProperty(null) {
      testApplication {
        environment { config = MapApplicationConfig(APP_INFO_ENABLED_PROPERTY to "true") }
        installAdk(includeAppInfo = false)

        assertThat(appInfoStatus()).isEqualTo(HttpStatusCode.OK)
      }
    }

  @Test
  fun appInfo_nonBooleanProperty_fallsBackToTheServerConfig() =
    withAppInfoProperty("perhaps") {
      testApplication {
        installAdk(includeAppInfo = true)

        // An unparseable value is ignored, not read as off.
        assertThat(appInfoStatus()).isEqualTo(HttpStatusCode.OK)
      }
    }

  @Test
  fun appInfo_property_overridesTheApplicationConfig() =
    withAppInfoProperty("false") {
      testApplication {
        environment { config = MapApplicationConfig(APP_INFO_ENABLED_PROPERTY to "true") }
        installAdk(includeAppInfo = null)

        assertThat(appInfoStatus()).isEqualTo(HttpStatusCode.NotFound)
      }
    }

  private fun ApplicationTestBuilder.installAdk(includeAppInfo: Boolean?) {
    application {
      adkApiModule(
        AdkServerConfig(
          agentLoader = agentLoader,
          sessionService = sessionService,
          artifactService = artifactService,
          includeAppInfo = includeAppInfo,
        )
      )
    }
  }

  private suspend fun ApplicationTestBuilder.appInfoStatus(): HttpStatusCode =
    client.get("/apps/mock-agent/app-info").status

  /** Runs [body] with `adk.app.info.enabled` set to [value], or unset when it is null. */
  private fun withAppInfoProperty(value: String?, body: () -> Unit) {
    val previous: String? = System.getProperty(APP_INFO_ENABLED_PROPERTY)
    if (value == null) {
      System.clearProperty(APP_INFO_ENABLED_PROPERTY)
    } else {
      System.setProperty(APP_INFO_ENABLED_PROPERTY, value)
    }
    try {
      body()
    } finally {
      if (previous == null) {
        System.clearProperty(APP_INFO_ENABLED_PROPERTY)
      } else {
        System.setProperty(APP_INFO_ENABLED_PROPERTY, previous)
      }
    }
  }
}
