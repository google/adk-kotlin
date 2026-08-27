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

import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.serialization.adkJson
import com.google.adk.kt.types.FileData
import com.google.adk.kt.types.Part
import com.google.common.truth.Truth.assertThat
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** [AdkServerConfig.inMemory] is the one-line way to serve a single agent. */
@OptIn(FrameworkInternalApi::class)
@RunWith(JUnit4::class)
class AdkServerConfigTest {
  private val agent = FakeAgent()

  @Test
  fun inMemory_servesTheAgent() = testApplication {
    application { adkApiModule(AdkServerConfig.inMemory(agent)) }

    assertThat(client.get("/health").status).isEqualTo(HttpStatusCode.OK)
    assertThat(client.get("/list-apps").bodyAsText()).contains(agent.name)
  }

  @Test
  fun inMemory_retainsSessionState() = testApplication {
    application { adkApiModule(AdkServerConfig.inMemory(agent)) }
    val session = "/apps/${agent.name}/users/u/sessions/s1"

    assertThat(client.post(session).status).isEqualTo(HttpStatusCode.OK)

    // A stateless session service would 404 here; the in-memory one hands the session back.
    assertThat(client.get(session).status).isEqualTo(HttpStatusCode.OK)
  }

  @Test
  fun inMemory_rejectsARunForAnUnknownAppName() = testApplication {
    application { adkApiModule(AdkServerConfig.inMemory(agent)) }

    // The single-agent loader used to answer with its agent whatever name was asked for.
    val response =
      client.post("/run") {
        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        setBody("""{"appName":"not-${agent.name}","userId":"u"}""")
      }

    assertThat(response.status).isEqualTo(HttpStatusCode.NotFound)
  }

  @Test
  fun inMemory_retainsArtifactState() = testApplication {
    application { adkApiModule(AdkServerConfig.inMemory(agent)) }
    val artifacts = "/apps/${agent.name}/users/u/sessions/s1/artifacts"

    val saved =
      client.post(artifacts) {
        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        setBody(adkJson.encodeToString(Part(fileData = FileData(displayName = "note.txt"))))
      }
    assertThat(saved.status).isEqualTo(HttpStatusCode.OK)

    // A write-dropping artifact service would hand back an empty list here.
    assertThat(client.get(artifacts).bodyAsText()).contains("note.txt")
  }

  @Test
  fun inMemory_defaultsThePort() {
    assertThat(AdkServerConfig.inMemory(agent).port).isEqualTo(AdkServerConfig.DEFAULT_PORT)
    assertThat(AdkServerConfig.inMemory(agent, port = 9123).port).isEqualTo(9123)
  }

  @Test
  fun inMemory_leavesTheDevUiToTheServerVariant() {
    assertThat(AdkServerConfig.inMemory(agent).webUiEnabled).isNull()
  }
}
