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

import com.google.adk.kt.agents.BaseAgent
import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.annotations.ExperimentalAuthApi
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.annotations.SecretAccess
import com.google.adk.kt.auth.AuthConfig
import com.google.adk.kt.auth.AuthCredential
import com.google.adk.kt.auth.AuthScheme
import com.google.adk.kt.auth.CredentialKey
import com.google.adk.kt.auth.Secret
import com.google.adk.kt.events.Event
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.Model
import com.google.adk.kt.serialization.adkJson
import com.google.adk.kt.sessions.InMemorySessionService
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.tools.FunctionTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.adk.kt.webserver.dev.adkDevModule
import com.google.adk.kt.webserver.telemetry.ApiServerSpanExporter
import com.google.common.truth.Truth.assertThat
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Credentials reach the agent unchanged but leave the server without their secrets. */
@OptIn(ExperimentalAuthApi::class, FrameworkInternalApi::class, SecretAccess::class)
@RunWith(JUnit4::class)
class CredentialRedactionTest {

  /** The API key each run of [SignInTool] saw. */
  private val seenKeys: MutableList<String?> = mutableListOf()

  @Test
  fun createSession_stateWithCredential_respondsWithoutItsSecrets() = testApplication {
    application { adkApiModule(config()) }

    val response =
      client.post("/apps/$APP/users/$USER/sessions") {
        jsonBody(
          """{"state": {"user:credential": {"authType": "apiKey", "apiKey": "$SENTINEL"}}}"""
        )
      }

    assertThat(response.status).isEqualTo(HttpStatusCode.OK)
    val body = response.bodyAsText()
    assertThat(body).contains("\"authType\":\"apiKey\"")
    assertThat(body).doesNotContain(SENTINEL)
  }

  @Test
  fun listSessions_stateWithCredential_respondsWithoutItsSecrets() = testApplication {
    application { adkApiModule(config()) }
    client.post("/apps/$APP/users/$USER/sessions") {
      jsonBody("""{"state": {"user:credential": {"authType": "apiKey", "apiKey": "$SENTINEL"}}}""")
    }

    val response = client.get("/apps/$APP/users/$USER/sessions")

    assertThat(response.status).isEqualTo(HttpStatusCode.OK)
    val body = response.bodyAsText()
    assertThat(body).contains("\"authType\":\"apiKey\"")
    assertThat(body).doesNotContain(SENTINEL)
  }

  @Test
  fun runSse_eventWithCredential_streamsItWithoutItsSecrets() = testApplication {
    application { adkApiModule(config()) }

    val response = client.post("/run_sse") { jsonBody(runBody(Content.fromText(Role.USER, "hi"))) }

    val body = response.bodyAsText()
    assertThat(body).contains("\"authType\":\"apiKey\"")
    assertThat(body).doesNotContain(SENTINEL)
  }

  @Test
  fun run_eventWithCredential_respondsWithoutItsSecrets() = testApplication {
    application { adkApiModule(config()) }

    val response = client.post("/run") { jsonBody(runBody(Content.fromText(Role.USER, "hi"))) }

    val body = response.bodyAsText()
    assertThat(body).contains("\"authType\":\"apiKey\"")
    assertThat(body).doesNotContain(SENTINEL)
  }

  @Test
  fun signInReply_token_isAbsentFromSessionAndRunResponses() = testApplication {
    application {
      adkDevModule(
        AdkServerConfig(
          appLoader = FakeAppLoader(APP) { signInAgent() },
          sessionService = sessionService(),
          artifactService = FakeArtifactService(),
          apiServerSpanExporter = ApiServerSpanExporter(),
        )
      )
    }
    val firstTurn = client.post("/run") { jsonBody(runBody(Content.fromText(Role.USER, "hi"))) }
    val request =
      adkJson
        .decodeFromString<List<Event>>(firstTurn.bodyAsText())
        .flatMap { it.signInRequests() }
        .single()

    val reply = client.post("/run_sse") { jsonBody(runBody(request.reply(API_KEY))) }

    val sessionPath = "/apps/$APP/users/$USER/sessions"
    val session = client.get("$sessionPath/$SESSION")
    val sessionBody = session.bodyAsText()
    assertThat(seenKeys).containsExactly(null, SENTINEL).inOrder()
    assertThat(session.status).isEqualTo(HttpStatusCode.OK)
    assertThat(sessionBody).contains("\"exchangedAuthCredential\":{\"authType\":\"apiKey\"}")
    for (body in listOf(reply.bodyAsText(), client.get(sessionPath).bodyAsText(), sessionBody)) {
      assertThat(body).doesNotContain(SENTINEL)
    }
  }

  private fun config(): AdkServerConfig =
    AdkServerConfig(
      appLoader = FakeAppLoader(APP) { CredentialEchoAgent() },
      sessionService = sessionService(),
      artifactService = FakeArtifactService(),
      apiServerSpanExporter = ApiServerSpanExporter(),
    )

  /** Holds [SESSION], since `/run` runs only on a session that exists. */
  private fun sessionService(): InMemorySessionService {
    val sessions = InMemorySessionService()
    runBlocking {
      val unused = sessions.createSession(SessionKey(APP, USER, SESSION))
    }
    return sessions
  }

  private fun signInAgent(): LlmAgent =
    LlmAgent(name = "agent", model = SignInModel(), tools = listOf(SignInTool()))

  /** A tool that asks for an API key until it has one, recording what each run saw. */
  private inner class SignInTool : FunctionTool(TOOL, "Reads the user's data.") {
    override fun declaration(): FunctionDeclaration? = null

    override suspend fun execute(context: ToolContext, args: Map<String, Any?>): Any {
      val key = (context.getAuthResponse(CONFIG) as? AuthCredential.ApiKey)?.apiKey?.reveal()
      seenKeys += key
      if (key == null) {
        context.requestCredential(CONFIG)
        return mapOf("status" to "pending")
      }
      return mapOf("status" to "ok")
    }
  }

  /**
   * Calls [TOOL] until the request holds its response, then answers. Stateless, since the app
   * loader builds a new agent for each request.
   */
  private class SignInModel : Model {
    override val name: String = "sign-in-model"

    override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> {
      val answered =
        request.contents.any { content -> content.parts.any { it.functionResponse?.name == TOOL } }
      val part =
        if (answered) Part(text = "done")
        else Part(functionCall = FunctionCall(name = TOOL, args = emptyMap(), id = "call-1"))
      return flowOf(LlmResponse(content = Content(role = Role.MODEL, parts = listOf(part))))
    }
  }

  /** An agent whose one event answers a tool call with a credential, as a tool could return. */
  private class CredentialEchoAgent : BaseAgent(name = "echo", description = "Echoes a key.") {
    override fun runAsyncImpl(context: InvocationContext): Flow<Event> = flow {
      emit(
        Event(
          invocationId = context.invocationId,
          author = name,
          content =
            Content(
              role = Role.USER,
              parts =
                listOf(
                  Part(
                    functionResponse =
                      FunctionResponse(
                        name = "read_key",
                        id = "call-1",
                        response = CREDENTIAL_RESPONSE,
                      )
                  )
                ),
            ),
        )
      )
    }
  }

  private companion object {
    const val APP = "app"
    const val USER = "user"
    const val SESSION = "session"
    const val TOOL = "read_data"
    const val SENTINEL = "secret-sentinel"

    val CONFIG: AuthConfig =
      AuthConfig(
        AuthScheme.ApiKey(name = "X-Key", location = AuthScheme.ApiKey.Location.HEADER),
        credentialKey = CredentialKey("key"),
      )

    val API_KEY: AuthCredential.ApiKey = AuthCredential.ApiKey(Secret(SENTINEL))

    val CREDENTIAL_RESPONSE: Map<String, Any?> =
      mapOf("credential" to mapOf("authType" to "apiKey", "apiKey" to SENTINEL))

    fun runBody(message: Content): String =
      """{"appName": "$APP", "userId": "$USER", "sessionId": "$SESSION",
        "newMessage": ${adkJson.encodeToString(message)}}"""
  }
}
