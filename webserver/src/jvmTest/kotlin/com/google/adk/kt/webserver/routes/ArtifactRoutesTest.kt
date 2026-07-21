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
import com.google.adk.kt.artifacts.ArtifactService
import com.google.adk.kt.serialization.adkJson
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.FileData
import com.google.adk.kt.types.Part
import com.google.common.truth.Truth.assertThat
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@OptIn(FrameworkInternalApi::class)
@RunWith(JUnit4::class)
class ArtifactRoutesTest {

  class FakeArtifactService(
    val onListArtifactKeys: suspend (SessionKey) -> List<String> = { emptyList() },
    val onLoadArtifact: suspend (SessionKey, String, Int?) -> Part? = { _, _, _ -> null },
  ) : ArtifactService {
    override suspend fun saveArtifact(
      sessionKey: SessionKey,
      filename: String,
      artifact: Part,
    ): Int = 0

    override suspend fun saveAndReloadArtifact(
      sessionKey: SessionKey,
      filename: String,
      artifact: Part,
    ): Part = artifact

    override suspend fun loadArtifact(
      sessionKey: SessionKey,
      filename: String,
      version: Int?,
    ): Part? = onLoadArtifact(sessionKey, filename, version)

    override suspend fun listArtifactKeys(sessionKey: SessionKey): List<String> =
      onListArtifactKeys(sessionKey)

    override suspend fun deleteArtifact(sessionKey: SessionKey, filename: String) {}

    override suspend fun listVersions(sessionKey: SessionKey, filename: String): List<Int> =
      emptyList()
  }

  @Test
  fun listArtifacts_empty_returnsEmptyList() = testApplication {
    val dummyService = FakeArtifactService(onListArtifactKeys = { emptyList() })
    application {
      install(ContentNegotiation) { json(adkJson) }
      routing { artifactRoutes(dummyService) }
    }

    val response = client.get("/apps/testApp/users/testUser/sessions/testSession/artifacts")

    assertThat(response.status).isEqualTo(HttpStatusCode.OK)
    assertThat(response.bodyAsText()).isEqualTo("[]")
  }

  @Test
  fun listArtifacts_notEmpty_returnsFilenames() = testApplication {
    val dummyService =
      FakeArtifactService(onListArtifactKeys = { listOf("file1.txt", "file2.txt") })
    application {
      install(ContentNegotiation) { json(adkJson) }
      routing { artifactRoutes(dummyService) }
    }

    val response = client.get("/apps/testApp/users/testUser/sessions/testSession/artifacts")

    assertThat(response.status).isEqualTo(HttpStatusCode.OK)
    val body = response.bodyAsText()
    assertThat(body).contains("file1.txt")
    assertThat(body).contains("file2.txt")
  }

  @Test
  fun loadArtifact_existing_returnsArtifact() = testApplication {
    val testPart = Part(text = "test content")
    val dummyService =
      FakeArtifactService(
        onLoadArtifact = { _, filename, _ -> if (filename == "test.txt") testPart else null }
      )
    application {
      install(ContentNegotiation) { json(adkJson) }
      routing { artifactRoutes(dummyService) }
    }

    val response =
      client.get("/apps/testApp/users/testUser/sessions/testSession/artifacts/test.txt")

    assertThat(response.status).isEqualTo(HttpStatusCode.OK)
    assertThat(response.bodyAsText()).contains("test content")
  }

  @Test
  fun loadArtifact_notFound_returnsNotFound() = testApplication {
    val dummyService = FakeArtifactService(onLoadArtifact = { _, _, _ -> null })
    application {
      install(ContentNegotiation) { json(adkJson) }
      routing { artifactRoutes(dummyService) }
    }

    val response =
      client.get("/apps/testApp/users/testUser/sessions/testSession/artifacts/missing.txt")

    assertThat(response.status).isEqualTo(HttpStatusCode.NotFound)
  }

  @Test
  fun deleteArtifact_existing_returnsNoContent() = testApplication {
    var deleteCalledWithSession: SessionKey? = null
    var deleteCalledWithFilename: String? = null
    val dummyService =
      object : ArtifactService by FakeArtifactService() {
        override suspend fun deleteArtifact(sessionKey: SessionKey, filename: String) {
          deleteCalledWithSession = sessionKey
          deleteCalledWithFilename = filename
        }
      }
    application {
      install(ContentNegotiation) { json(adkJson) }
      routing { artifactRoutes(dummyService) }
    }

    val response =
      client.delete("/apps/testApp/users/testUser/sessions/testSession/artifacts/test.txt")

    assertThat(response.status).isEqualTo(HttpStatusCode.NoContent)
    assertThat(deleteCalledWithFilename).isEqualTo("test.txt")
    assertThat(deleteCalledWithSession)
      .isEqualTo(SessionKey(appName = "testApp", userId = "testUser", id = "testSession"))
  }

  @Test
  fun extractArtifactParams_allPresent_returnsSuccess() {
    val params =
      io.ktor.http.parametersOf(
        "appName" to listOf("testApp"),
        "userId" to listOf("testUser"),
        "sessionId" to listOf("testSession"),
        "artifactName" to listOf("testArtifact"),
      )
    val result = extractArtifactParams(params)
    assertThat(result).isInstanceOf(ArtifactRoutesResult.Success::class.java)
    val success = result as ArtifactRoutesResult.Success
    assertThat(success.params.appName).isEqualTo("testApp")
    assertThat(success.params.userId).isEqualTo("testUser")
    assertThat(success.params.sessionId).isEqualTo("testSession")
    assertThat(success.params.artifactName).isEqualTo("testArtifact")
  }

  @Test
  fun extractArtifactParams_missingAppName_returnsError() {
    val params =
      io.ktor.http.parametersOf(
        "userId" to listOf("testUser"),
        "sessionId" to listOf("testSession"),
      )
    val result = extractArtifactParams(params)
    assertThat(result).isInstanceOf(ArtifactRoutesResult.Error::class.java)
    val error = result as ArtifactRoutesResult.Error
    assertThat(error.error).isEqualTo(ArtifactRoutesErrors.ERR_MISSING_APP_NAME)
  }

  @Test
  fun extractArtifactParams_missingArtifactName_whenRequired_returnsError() {
    val params =
      io.ktor.http.parametersOf(
        "appName" to listOf("testApp"),
        "userId" to listOf("testUser"),
        "sessionId" to listOf("testSession"),
      )
    val result = extractArtifactParams(params, requireArtifactName = true)
    assertThat(result).isInstanceOf(ArtifactRoutesResult.Error::class.java)
    val error = result as ArtifactRoutesResult.Error
    assertThat(error.error).isEqualTo(ArtifactRoutesErrors.ERR_MISSING_ARTIFACT_NAME)
  }

  @Test
  fun saveArtifact_withFileDataDisplayName_returnsSavedPart() = testApplication {
    val testPart = Part(fileData = FileData(displayName = "test.txt"))
    var savedFilename: String? = null
    val dummyService =
      object : ArtifactService by FakeArtifactService() {
        override suspend fun saveAndReloadArtifact(
          sessionKey: SessionKey,
          filename: String,
          artifact: Part,
        ): Part {
          savedFilename = filename
          return artifact
        }
      }
    application {
      install(ContentNegotiation) { json(adkJson) }
      routing { artifactRoutes(dummyService) }
    }

    val response =
      client.post("/apps/testApp/users/testUser/sessions/testSession/artifacts") {
        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        setBody(adkJson.encodeToString(testPart))
      }

    assertThat(response.status).isEqualTo(HttpStatusCode.OK)
    assertThat(savedFilename).isEqualTo("test.txt")
    val responsePart = adkJson.decodeFromString<Part>(response.bodyAsText())
    assertThat(responsePart.fileData?.displayName).isEqualTo("test.txt")
  }

  @Test
  fun saveArtifact_withInlineDataDisplayName_returnsSavedPart() = testApplication {
    val testPart = Part(inlineData = Blob(displayName = "inline.png", data = byteArrayOf(1, 2)))
    var savedFilename: String? = null
    val dummyService =
      object : ArtifactService by FakeArtifactService() {
        override suspend fun saveAndReloadArtifact(
          sessionKey: SessionKey,
          filename: String,
          artifact: Part,
        ): Part {
          savedFilename = filename
          return artifact
        }
      }
    application {
      install(ContentNegotiation) { json(adkJson) }
      routing { artifactRoutes(dummyService) }
    }

    val response =
      client.post("/apps/testApp/users/testUser/sessions/testSession/artifacts") {
        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        setBody(adkJson.encodeToString(testPart))
      }

    assertThat(response.status).isEqualTo(HttpStatusCode.OK)
    assertThat(savedFilename).isEqualTo("inline.png")
  }

  @Test
  fun saveArtifact_missingFilename_returnsBadRequest() = testApplication {
    val testPart = Part(text = "no filename")
    val dummyService = FakeArtifactService()
    application {
      install(ContentNegotiation) { json(adkJson) }
      routing { artifactRoutes(dummyService) }
    }

    val response =
      client.post("/apps/testApp/users/testUser/sessions/testSession/artifacts") {
        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        setBody(adkJson.encodeToString(testPart))
      }

    assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
  }
}
