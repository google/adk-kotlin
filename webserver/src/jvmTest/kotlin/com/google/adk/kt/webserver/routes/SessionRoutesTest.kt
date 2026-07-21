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
import com.google.adk.kt.serialization.adkJson
import com.google.adk.kt.sessions.ListSessionsResponse
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.sessions.SessionService
import com.google.adk.kt.sessions.State
import com.google.common.truth.Truth.assertThat
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@OptIn(FrameworkInternalApi::class)
@RunWith(JUnit4::class)
class SessionRoutesTest {

  class FakeSessionService : SessionService {
    val createdSessions = mutableListOf<Session>()
    val deletedSessions = mutableSetOf<String>()

    override suspend fun createSession(key: SessionKey, state: Map<String, Any>?): Session {
      val session =
        Session(
          key = SessionKey(key.appName, key.userId, key.id ?: "gen-id"),
          state = state?.let { State(it.toMutableMap()) } ?: State(),
          events = mutableListOf(),
        )
      createdSessions.add(session)
      return session
    }

    override suspend fun getSession(
      key: SessionKey,
      config: com.google.adk.kt.sessions.GetSessionConfig?,
    ): Session? {
      return createdSessions.find { it.key.id == key.id }
    }

    override suspend fun listSessions(appName: String, userId: String): ListSessionsResponse {
      return ListSessionsResponse(
        createdSessions.filter { it.key.appName == appName && it.key.userId == userId }
      )
    }

    override suspend fun deleteSession(key: SessionKey) {
      val id = checkNotNull(key.id) { "deleteSession requires a non-null id" }
      deletedSessions.add(id)
      createdSessions.removeIf { it.key.id == id }
    }

    override suspend fun listEvents(
      key: SessionKey
    ): com.google.adk.kt.sessions.ListEventsResponse = TODO()
  }

  @Test
  fun listSessions_empty_returnsEmptyList() = testApplication {
    val fakeService = FakeSessionService()
    application {
      install(ContentNegotiation) { json(adkJson) }
      routing { sessionRoutes(fakeService) }
    }

    val response = client.get("/apps/testApp/users/testUser/sessions")

    assertThat(response.status).isEqualTo(HttpStatusCode.OK)
    assertThat(response.bodyAsText()).isEqualTo("[]")
  }

  @Test
  fun createSession_validInput_createsSession() = testApplication {
    val fakeService = FakeSessionService()
    application {
      install(ContentNegotiation) { json(adkJson) }
      routing { sessionRoutes(fakeService) }
    }

    val response = client.post("/apps/testApp/users/testUser/sessions")

    assertThat(response.status).isEqualTo(HttpStatusCode.OK)
    assertThat(fakeService.createdSessions).hasSize(1)
    val createdSession = fakeService.createdSessions.first()
    assertThat(createdSession.key.appName).isEqualTo("testApp")
    assertThat(createdSession.key.userId).isEqualTo("testUser")
  }

  @Test
  fun getSession_existing_returnsSession() = testApplication {
    val fakeService = FakeSessionService()
    fakeService.createdSessions.add(
      Session(key = SessionKey(appName = "testApp", userId = "testUser", id = "test-session"))
    )
    application {
      install(ContentNegotiation) { json(adkJson) }
      routing { sessionRoutes(fakeService) }
    }

    val response = client.get("/apps/testApp/users/testUser/sessions/test-session")

    assertThat(response.status).isEqualTo(HttpStatusCode.OK)
    assertThat(response.bodyAsText()).contains("\"id\":\"test-session\"")
  }

  @Test
  fun deleteSession_existing_removesSession() = testApplication {
    val fakeService = FakeSessionService()
    fakeService.createdSessions.add(
      Session(key = SessionKey(appName = "testApp", userId = "testUser", id = "test-session"))
    )
    application {
      install(ContentNegotiation) { json(adkJson) }
      routing { sessionRoutes(fakeService) }
    }

    val response = client.delete("/apps/testApp/users/testUser/sessions/test-session")

    assertThat(response.status).isEqualTo(HttpStatusCode.NoContent)
    assertThat(fakeService.deletedSessions).contains("test-session")
    assertThat(fakeService.createdSessions).isEmpty()
  }

  @Test
  fun extractSessionParams_allPresent_returnsSuccess() {
    val params =
      io.ktor.http.parametersOf(
        "appName" to listOf("testApp"),
        "userId" to listOf("testUser"),
        "sessionId" to listOf("testSession"),
      )
    val result = extractSessionParams(params)
    assertThat(result).isInstanceOf(SessionRoutesResult.Success::class.java)
    val success = result as SessionRoutesResult.Success
    assertThat(success.params.appName).isEqualTo("testApp")
    assertThat(success.params.userId).isEqualTo("testUser")
    assertThat(success.params.sessionId).isEqualTo("testSession")
  }

  @Test
  fun extractSessionParams_missingAppName_returnsError() {
    val params = io.ktor.http.parametersOf("userId" to listOf("testUser"))
    val result = extractSessionParams(params)
    assertThat(result).isInstanceOf(SessionRoutesResult.Error::class.java)
    val error = result as SessionRoutesResult.Error
    assertThat(error.error).isEqualTo(SessionRoutesErrors.ERR_MISSING_APP_NAME)
  }

  @Test
  fun extractSessionParams_missingSessionId_whenRequired_returnsError() {
    val params =
      io.ktor.http.parametersOf("appName" to listOf("testApp"), "userId" to listOf("testUser"))
    val result = extractSessionParams(params, requireSessionId = true)
    assertThat(result).isInstanceOf(SessionRoutesResult.Error::class.java)
    val error = result as SessionRoutesResult.Error
    assertThat(error.error).isEqualTo(SessionRoutesErrors.ERR_MISSING_SESSION_ID)
  }
}
