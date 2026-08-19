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

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

fun Route.evalRoutes() {
  route("/apps/{app_name}") {
    get("/eval_results") { call.respond(HttpStatusCode.NotImplemented, "Not implemented yet") }

    get("/eval_sets") { call.respond(HttpStatusCode.NotImplemented, "Not implemented yet") }

    route("/eval_sets/{eval_set_id}") {
      post { call.respond(HttpStatusCode.NotImplemented, "Not implemented yet") }

      post("/add_session") { call.respond(HttpStatusCode.NotImplemented, "Not implemented yet") }

      get("/evals") { call.respond(HttpStatusCode.NotImplemented, "Not implemented yet") }

      post("/run_eval") { call.respond(HttpStatusCode.NotImplemented, "Not implemented yet") }
    }
  }
}
