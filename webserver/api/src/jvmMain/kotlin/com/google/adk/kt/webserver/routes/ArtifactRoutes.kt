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

import com.google.adk.kt.artifacts.ArtifactService
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.types.Part
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

data class ArtifactRoutesError(val message: String, val code: HttpStatusCode)

object ArtifactRoutesErrors {
  val ERR_MISSING_APP_NAME = ArtifactRoutesError("Missing appName", HttpStatusCode.BadRequest)
  val ERR_MISSING_USER_ID = ArtifactRoutesError("Missing userId", HttpStatusCode.BadRequest)
  val ERR_MISSING_SESSION_ID = ArtifactRoutesError("Missing sessionId", HttpStatusCode.BadRequest)
  val ERR_MISSING_ARTIFACT_NAME =
    ArtifactRoutesError("Missing artifactName", HttpStatusCode.BadRequest)
  val ERR_ARTIFACT_NOT_FOUND = ArtifactRoutesError("Artifact not found", HttpStatusCode.NotFound)
}

data class ArtifactParams(
  val appName: String,
  val userId: String,
  val sessionId: String,
  val artifactName: String? = null,
)

sealed class ArtifactRoutesResult {
  data class Success(val params: ArtifactParams) : ArtifactRoutesResult()

  data class Error(val error: ArtifactRoutesError) : ArtifactRoutesResult()
}

fun extractArtifactParams(
  parameters: io.ktor.http.Parameters,
  requireArtifactName: Boolean = false,
): ArtifactRoutesResult {
  val appName =
    parameters["appName"]
      ?: return ArtifactRoutesResult.Error(ArtifactRoutesErrors.ERR_MISSING_APP_NAME)
  val userId =
    parameters["userId"]
      ?: return ArtifactRoutesResult.Error(ArtifactRoutesErrors.ERR_MISSING_USER_ID)
  val sessionId =
    parameters["sessionId"]
      ?: return ArtifactRoutesResult.Error(ArtifactRoutesErrors.ERR_MISSING_SESSION_ID)
  val artifactName = parameters["artifactName"]
  if (requireArtifactName && artifactName == null) {
    return ArtifactRoutesResult.Error(ArtifactRoutesErrors.ERR_MISSING_ARTIFACT_NAME)
  }
  return ArtifactRoutesResult.Success(ArtifactParams(appName, userId, sessionId, artifactName))
}

fun Route.artifactRoutes(artifactService: ArtifactService) {

  route("/apps/{appName}/users/{userId}/sessions/{sessionId}/artifacts") {
    get {
      val result = extractArtifactParams(call.parameters)
      val params =
        when (result) {
          is ArtifactRoutesResult.Success -> result.params
          is ArtifactRoutesResult.Error -> {
            return@get call.respond(result.error.code, result.error.message)
          }
        }
      val appName = params.appName
      val userId = params.userId
      val sessionId = params.sessionId

      call.respond(artifactService.listArtifactKeys(SessionKey(appName, userId, sessionId)))
    }

    post {
      val result = extractArtifactParams(call.parameters)
      val params =
        when (result) {
          is ArtifactRoutesResult.Success -> result.params
          is ArtifactRoutesResult.Error -> {
            return@post call.respond(result.error.code, result.error.message)
          }
        }
      val appName = params.appName
      val userId = params.userId
      val sessionId = params.sessionId

      val part = call.receive<Part>()
      val artifactName =
        part.fileData?.displayName
          ?: part.inlineData?.displayName
          ?: part.fileData?.fileUri?.substringAfterLast('/')
          ?: return@post call.respond(HttpStatusCode.BadRequest, "Artifact name not found in part")

      val savedPart =
        artifactService.saveAndReloadArtifact(
          SessionKey(appName, userId, sessionId),
          artifactName,
          part,
        )
      call.respond(HttpStatusCode.OK, savedPart)
    }

    route("/{artifactName}") {
      get {
        val result = extractArtifactParams(call.parameters, requireArtifactName = true)
        val params =
          when (result) {
            is ArtifactRoutesResult.Success -> result.params
            is ArtifactRoutesResult.Error -> {
              return@get call.respond(result.error.code, result.error.message)
            }
          }
        val appName = params.appName
        val userId = params.userId
        val sessionId = params.sessionId
        val artifactName = params.artifactName ?: return@get

        val part =
          artifactService.loadArtifact(SessionKey(appName, userId, sessionId), artifactName)
            ?: return@get call.respond(
              ArtifactRoutesErrors.ERR_ARTIFACT_NOT_FOUND.code,
              ArtifactRoutesErrors.ERR_ARTIFACT_NOT_FOUND.message,
            )
        call.respond(part)
      }

      delete {
        val result = extractArtifactParams(call.parameters, requireArtifactName = true)
        val params =
          when (result) {
            is ArtifactRoutesResult.Success -> result.params
            is ArtifactRoutesResult.Error -> {
              return@delete call.respond(result.error.code, result.error.message)
            }
          }
        val appName = params.appName
        val userId = params.userId
        val sessionId = params.sessionId
        val artifactName = params.artifactName ?: return@delete

        artifactService.deleteArtifact(SessionKey(appName, userId, sessionId), artifactName)
        call.respond(HttpStatusCode.NoContent)
      }
    }
  }
}
