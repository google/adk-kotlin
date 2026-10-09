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

import io.ktor.http.HttpMethod
import io.ktor.http.HttpMethod.Companion.Delete
import io.ktor.http.HttpMethod.Companion.Get
import io.ktor.http.HttpMethod.Companion.Post
import io.ktor.http.HttpMethod.Companion.Put
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route

/**
 * Answers the development endpoints the Dev UI calls but this server does not implement with 501
 * and a readable `detail`, which a client can tell apart from a 404 for a wrong path. Mount it
 * under `/dev/apps/{appName}`.
 */
internal fun Route.unimplementedDevRoutes() {
  notImplemented(
    "The agent builder is not implemented in ADK Kotlin.",
    Get to "/builder",
    Post to "/builder/save",
    Post to "/builder/cancel",
  )
  // The Dev UI shows its own notice, rather than an error, when the detail says "not installed".
  notImplemented(
    "Evaluation is not installed: ADK Kotlin does not support it.",
    Post to "/eval-sets",
    Get to "/eval-sets/{evalSetId}",
    Delete to "/eval-sets/{evalSetId}",
    Post to "/eval-sets/{evalSetId}/run",
    Get to "/eval_sets",
    Post to "/eval_sets/{evalSetId}/add_session",
    Get to "/eval_sets/{evalSetId}/evals",
    Get to "/eval_sets/{evalSetId}/evals/{evalId}",
    Put to "/eval_sets/{evalSetId}/evals/{evalId}",
    Delete to "/eval_sets/{evalSetId}/evals/{evalId}",
    Get to "/eval_results",
    Get to "/eval_results/{evalResultId}",
    Get to "/metrics-info",
  )
  notImplemented(
    "Deploying from the Dev UI is not supported in ADK Kotlin.",
    Get to "/deploy/defaults",
    Post to "/deploy/{target}",
  )
  notImplemented(
    "Agent tests are not implemented in ADK Kotlin.",
    Get to "/tests",
    Post to "/tests/rebuild",
    Post to "/tests/run",
    Get to "/tests/{testName}",
    Put to "/tests/{testName}",
    Delete to "/tests/{testName}",
  )
}

private fun Route.notImplemented(detail: String, vararg endpoints: Pair<HttpMethod, String>) {
  for ((method, path) in endpoints) {
    route(path, method) {
      handle { call.respond(HttpStatusCode.NotImplemented, mapOf("detail" to detail)) }
    }
  }
}
