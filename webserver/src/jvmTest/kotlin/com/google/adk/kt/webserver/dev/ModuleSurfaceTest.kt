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

package com.google.adk.kt.webserver.dev

import com.google.adk.kt.webserver.APP_INFO_ENABLED_PROPERTY
import com.google.adk.kt.webserver.AdkServerConfig
import com.google.adk.kt.webserver.FakeAppLoader
import com.google.adk.kt.webserver.FakeArtifactService
import com.google.adk.kt.webserver.FakeSessionService
import com.google.adk.kt.webserver.adkApiModule
import com.google.adk.kt.webserver.routes.WEB_UI_ENABLED_PROPERTY
import com.google.adk.kt.webserver.telemetry.ApiServerSpanExporter
import com.google.common.truth.Truth.assertThat
import io.ktor.client.request.get
import io.ktor.server.application.Application
import io.ktor.server.application.plugin
import io.ktor.server.routing.Route
import io.ktor.server.routing.Routing
import io.ktor.server.testing.testApplication
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Which endpoints belong to the runtime contract and which are development-only.
 *
 * These assert on the installed route tree rather than on responses, so a route added to the wrong
 * module fails here even when no request would tell the two apart.
 */
@RunWith(JUnit4::class)
class ModuleSurfaceTest {

  @Test
  fun apiModule_installsTheContractSurface() {
    assertThat(routesOf { adkApiModule(testConfig()) }).containsExactlyElementsIn(CONTRACT_ROUTES)
  }

  @Test
  fun apiModule_mountsTheDevUiWhenTheConfigAsks() {
    assertThat(routesOf { adkApiModule(testConfig().copy(webUiEnabled = true)) })
      .containsExactlyElementsIn(CONTRACT_ROUTES + WEB_UI_ROUTES)
  }

  @Test
  fun devModule_addsTheDevelopmentSurfaceAndTheDevUi() {
    assertThat(routesOf { adkDevModule(testConfig()) })
      .containsExactlyElementsIn(CONTRACT_ROUTES + WEB_UI_ROUTES + DEVELOPMENT_ONLY_ROUTES)
  }

  @Test
  fun devModule_leavesTheDevUiUnmountedWhenTheConfigSaysSo() {
    assertThat(routesOf { adkDevModule(testConfig().copy(webUiEnabled = false)) })
      .containsExactlyElementsIn(CONTRACT_ROUTES + DEVELOPMENT_ONLY_ROUTES)
  }

  @Test
  fun webUiProperty_overridesBothVariants() {
    assertThat(routesOf(webUiProperty = "true") { adkApiModule(testConfig()) })
      .containsExactlyElementsIn(CONTRACT_ROUTES + WEB_UI_ROUTES)
    assertThat(routesOf(webUiProperty = "false") { adkDevModule(testConfig()) })
      .containsExactlyElementsIn(CONTRACT_ROUTES + DEVELOPMENT_ONLY_ROUTES)
  }

  @Test
  fun appInfo_joinsTheContractSurfaceOfBothVariantsWhenAsked() {
    assertThat(routesOf { adkApiModule(testConfig().copy(includeAppInfo = true)) })
      .containsExactlyElementsIn(CONTRACT_ROUTES + APP_INFO_ROUTES)
    assertThat(routesOf { adkDevModule(testConfig().copy(includeAppInfo = true)) })
      .containsExactlyElementsIn(
        CONTRACT_ROUTES + APP_INFO_ROUTES + WEB_UI_ROUTES + DEVELOPMENT_ONLY_ROUTES
      )
  }

  @Test
  fun webUiProperty_overridesAnExplicitConfigValue() {
    assertThat(
        routesOf(webUiProperty = "true") { adkApiModule(testConfig().copy(webUiEnabled = false)) }
      )
      .containsExactlyElementsIn(CONTRACT_ROUTES + WEB_UI_ROUTES)
    assertThat(
        routesOf(webUiProperty = "false") { adkDevModule(testConfig().copy(webUiEnabled = true)) }
      )
      .containsExactlyElementsIn(CONTRACT_ROUTES + DEVELOPMENT_ONLY_ROUTES)
  }

  private fun testConfig() =
    AdkServerConfig(
      appLoader = FakeAppLoader(),
      sessionService = FakeSessionService(),
      artifactService = FakeArtifactService(),
      apiServerSpanExporter = ApiServerSpanExporter(),
    )

  /** The routes [install] mounts, with `adk.web.ui.enabled` set to [webUiProperty] or cleared. */
  private fun routesOf(
    webUiProperty: String? = null,
    install: Application.() -> Unit,
  ): Set<String> {
    val previous: String? = System.getProperty(WEB_UI_ENABLED_PROPERTY)
    if (webUiProperty == null) {
      System.clearProperty(WEB_UI_ENABLED_PROPERTY)
    } else {
      System.setProperty(WEB_UI_ENABLED_PROPERTY, webUiProperty)
    }
    // Only the config decides app-info here, so an ambient property cannot alter the surface.
    val previousAppInfo: String? = System.getProperty(APP_INFO_ENABLED_PROPERTY)
    System.clearProperty(APP_INFO_ENABLED_PROPERTY)
    val routes = mutableSetOf<String>()
    try {
      testApplication {
        application {
          install()
          routes += plugin(Routing).leafRoutes()
        }
        // testApplication builds the Application lazily, so force it.
        client.get("/health")
      }
    } finally {
      if (previous == null) {
        System.clearProperty(WEB_UI_ENABLED_PROPERTY)
      } else {
        System.setProperty(WEB_UI_ENABLED_PROPERTY, previous)
      }
      if (previousAppInfo != null) {
        System.setProperty(APP_INFO_ENABLED_PROPERTY, previousAppInfo)
      }
    }
    return routes
  }

  private fun Route.leafRoutes(): List<String> =
    if (children.isEmpty()) listOf(toString()) else children.flatMap { it.leafRoutes() }

  private companion object {
    /** The agent runtime contract, which a headless deployment serves. */
    val CONTRACT_ROUTES =
      setOf(
        "/health/(method:GET)",
        "/version/(method:GET)",
        "/list-apps/(method:GET)",
        "/run/(method:POST)",
        "/run_sse/(method:POST)",
        "/apps/{appName}/users/{userId}/sessions/(method:GET)",
        "/apps/{appName}/users/{userId}/sessions/(method:POST)",
        "/apps/{appName}/users/{userId}/sessions/{sessionId}/(method:GET)",
        "/apps/{appName}/users/{userId}/sessions/{sessionId}/(method:POST)",
        "/apps/{appName}/users/{userId}/sessions/{sessionId}/(method:DELETE)",
        "/apps/{appName}/users/{userId}/sessions/{sessionId}/artifacts/(method:GET)",
        "/apps/{appName}/users/{userId}/sessions/{sessionId}/artifacts/(method:POST)",
        "/apps/{appName}/users/{userId}/sessions/{sessionId}/artifacts/{artifactName}/(method:GET)",
        "/apps/{appName}/users/{userId}/sessions/{sessionId}/artifacts/{artifactName}/(method:DELETE)",
      )

    /** Opt-in, and on the contract side rather than the development one. */
    val APP_INFO_ROUTES = setOf("/apps/{appName}/app-info/(method:GET)")

    /** The Development UI mount, which Python also serves from the API server. */
    val WEB_UI_ROUTES = setOf("/(method:GET)", "/dev-ui/(method:GET)", "/dev-ui/{...}/(method:GET)")

    /** Mirrors which side Python puts each endpoint on; the URL prefixes differ. */
    val DEVELOPMENT_ONLY_ROUTES =
      setOf(
        "/debug/trace/{eventId}/(method:GET)",
        "/debug/trace/session/{sessionId}/(method:GET)",
        "/config/telemetry/(method:GET)",
        "/dev/apps/{appName}/debug/trace/{eventId}/(method:GET)",
        "/dev/apps/{appName}/debug/trace/session/{sessionId}/(method:GET)",
        "/dev/apps/{appName}/build_graph/(method:GET)",
        "/dev/apps/{appName}/build_graph_image/(method:GET)",
        "/dev/apps/{appName}/builder/(method:GET)",
        "/dev/apps/{appName}/builder/save/(method:POST)",
        "/dev/apps/{appName}/builder/cancel/(method:POST)",
        "/dev/apps/{appName}/eval-sets/(method:POST)",
        "/dev/apps/{appName}/eval-sets/{evalSetId}/(method:GET)",
        "/dev/apps/{appName}/eval-sets/{evalSetId}/(method:DELETE)",
        "/dev/apps/{appName}/eval-sets/{evalSetId}/run/(method:POST)",
        "/dev/apps/{appName}/eval_sets/(method:GET)",
        "/dev/apps/{appName}/eval_sets/{evalSetId}/add_session/(method:POST)",
        "/dev/apps/{appName}/eval_sets/{evalSetId}/evals/(method:GET)",
        "/dev/apps/{appName}/eval_sets/{evalSetId}/evals/{evalId}/(method:GET)",
        "/dev/apps/{appName}/eval_sets/{evalSetId}/evals/{evalId}/(method:PUT)",
        "/dev/apps/{appName}/eval_sets/{evalSetId}/evals/{evalId}/(method:DELETE)",
        "/dev/apps/{appName}/eval_results/(method:GET)",
        "/dev/apps/{appName}/eval_results/{evalResultId}/(method:GET)",
        "/dev/apps/{appName}/metrics-info/(method:GET)",
        "/dev/apps/{appName}/deploy/defaults/(method:GET)",
        "/dev/apps/{appName}/deploy/{target}/(method:POST)",
        "/dev/apps/{appName}/tests/(method:GET)",
        "/dev/apps/{appName}/tests/rebuild/(method:POST)",
        "/dev/apps/{appName}/tests/run/(method:POST)",
        "/dev/apps/{appName}/tests/{testName}/(method:GET)",
        "/dev/apps/{appName}/tests/{testName}/(method:PUT)",
        "/dev/apps/{appName}/tests/{testName}/(method:DELETE)",
        "/apps/{app_name}/eval_sets/(method:GET)",
        "/apps/{app_name}/eval_sets/{eval_set_id}/(method:POST)",
        "/apps/{app_name}/eval_sets/{eval_set_id}/add_session/(method:POST)",
        "/apps/{app_name}/eval_sets/{eval_set_id}/evals/(method:GET)",
        "/apps/{app_name}/eval_sets/{eval_set_id}/run_eval/(method:POST)",
        "/apps/{app_name}/eval_results/(method:GET)",
        "/apps/{appName}/users/{userId}/sessions/{sessionId}/events/{eventId}/graph/(method:GET)",
        "/dev/build_graph/{appName}/(method:GET)",
        "/dev/build_graph_image/{appName}/(method:GET)",
      )
  }
}
