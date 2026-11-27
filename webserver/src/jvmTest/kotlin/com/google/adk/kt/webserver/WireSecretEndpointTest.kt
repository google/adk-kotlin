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

import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.annotations.ExperimentalAuthApi
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.annotations.SecretAccess
import com.google.adk.kt.auth.AuthConfig
import com.google.adk.kt.auth.AuthCredential
import com.google.adk.kt.auth.AuthScheme
import com.google.adk.kt.auth.CredentialKey
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
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.adk.kt.webserver.dev.adkDevModule
import com.google.common.truth.Truth.assertThat
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import org.junit.Test

/**
 * A sign-in response sent as plain values reaches the agent, but no wire response carries its
 * secret.
 */
@OptIn(ExperimentalAuthApi::class, FrameworkInternalApi::class, SecretAccess::class)
class WireSecretEndpointTest {

  /** The API key each run of [SignInTool] saw. */
  private val seenKeys: MutableList<String?> = mutableListOf()

  @Test
  fun createSession_bareTokenUnderACredentialKey_respondsWithItRedacted() = testApplication {
    application { adkApiModule(config()) }

    val response = client.post("/apps/$APP/users/$USER/sessions") { jsonBody(TOKEN_STATE) }

    assertThat(response.status).isEqualTo(HttpStatusCode.OK)
    assertTokensRedacted(response.bodyAsText())
  }

  @Test
  fun getSession_bareTokenUnderACredentialKey_respondsWithItRedacted() = testApplication {
    application { adkApiModule(config()) }
    client.post("/apps/$APP/users/$USER/sessions/$TOKEN_SESSION") { jsonBody(TOKEN_STATE) }

    val response = client.get("/apps/$APP/users/$USER/sessions/$TOKEN_SESSION")

    assertThat(response.status).isEqualTo(HttpStatusCode.OK)
    assertTokensRedacted(response.bodyAsText())
  }

  @Test
  fun listSessions_bareTokenUnderACredentialKey_respondsWithItRedacted() = testApplication {
    application { adkApiModule(config()) }
    client.post("/apps/$APP/users/$USER/sessions") { jsonBody(TOKEN_STATE) }

    val response = client.get("/apps/$APP/users/$USER/sessions")

    assertThat(response.status).isEqualTo(HttpStatusCode.OK)
    assertTokensRedacted(response.bodyAsText())
  }

  @Test
  fun runSse_bareKeyAnsweringTheRequestUnderAnotherName_leavesTheKeyOutOfEveryResponse() =
    testApplication {
      val bodies = answerSignIn { id ->
        """{"name": "answer", "id": "$id", "response": {"result": "$SENTINEL"}}"""
      }

      assertThat(seenKeys).containsExactly(null, SENTINEL).inOrder()
      assertThat(bodies.session).contains("\"exchangedAuthCredential\":{\"authType\":\"apiKey\"}")
      assertThat(bodies.runSse).doesNotContain(SENTINEL)
      assertThat(bodies.sessions).doesNotContain(SENTINEL)
      assertThat(bodies.session).doesNotContain(SENTINEL)
    }

  @Test
  fun runSse_bareKeyInSignInResponse_leavesTheKeyOutOfEveryResponse() = testApplication {
    val bodies = answerSignIn { id ->
      """{"name": "adk_request_credential", "id": "$id", "response": {"result": "$SENTINEL"}}"""
    }

    assertThat(seenKeys).containsExactly(null, SENTINEL).inOrder()
    assertThat(bodies.session).contains("\"exchangedAuthCredential\":{\"authType\":\"apiKey\"}")
    assertThat(bodies.runSse).doesNotContain(SENTINEL)
    assertThat(bodies.sessions).doesNotContain(SENTINEL)
    assertThat(bodies.session).doesNotContain(SENTINEL)
  }

  @Test
  fun runSse_malformedSignInResponse_isStoredEmpty() = testApplication {
    val bodies = answerSignIn { id ->
      """{"name": "adk_request_credential", "id": "$id", "response": {"token": "$SENTINEL"}}"""
    }

    assertThat(seenKeys).containsExactly(null, null).inOrder()
    assertThat(bodies.session).contains("\"response\":{}")
    assertThat(bodies.runSse).doesNotContain(SENTINEL)
    assertThat(bodies.sessions).doesNotContain(SENTINEL)
    assertThat(bodies.session).doesNotContain(SENTINEL)
  }

  @Test
  fun runSse_signInResponseAsPlainValues_leavesTheKeyOutOfEveryResponse() = testApplication {
    // As an HTTP client sends it: a bare credential as plain values.
    val bodies = answerSignIn { id ->
      """{"name": "adk_request_credential", "id": "$id",
        "response": {"authType": "apiKey", "apiKey": "$SENTINEL"}}"""
    }

    assertThat(seenKeys).containsExactly(null, SENTINEL).inOrder()
    assertThat(bodies.session).contains("\"exchangedAuthCredential\":{\"authType\":\"apiKey\"}")
    assertThat(bodies.runSse).doesNotContain(SENTINEL)
    assertThat(bodies.sessions).doesNotContain(SENTINEL)
    assertThat(bodies.session).doesNotContain(SENTINEL)
  }

  /** The bodies of the `/run_sse` answer, the session list and the session. */
  private class Bodies(val runSse: String, val sessions: String, val session: String)

  /**
   * Runs a turn that asks for sign-in, then answers it as an HTTP client would, with the function
   * response JSON [answer] builds from the request's id.
   */
  private suspend fun ApplicationTestBuilder.answerSignIn(answer: (String) -> String): Bodies {
    application { adkDevModule(config()) }
    val firstTurn = client.post("/run") { jsonBody(runBody(HI)) }
    assertThat(firstTurn.status).isEqualTo(HttpStatusCode.OK)
    val request =
      adkJson
        .decodeFromString<List<Event>>(firstTurn.bodyAsText())
        .flatMap { it.signInRequests() }
        .single()
    val runSse =
      client.post("/run_sse") {
        jsonBody(
          runBody("""{"role": "user", "parts": [{"functionResponse": ${answer(request.id)}}]}""")
        )
      }
    assertThat(runSse.status).isEqualTo(HttpStatusCode.OK)
    val sessionPath = "/apps/$APP/users/$USER/sessions"
    val sessions = client.get(sessionPath)
    assertThat(sessions.status).isEqualTo(HttpStatusCode.OK)
    val session = client.get("$sessionPath/$SESSION")
    assertThat(session.status).isEqualTo(HttpStatusCode.OK)
    return Bodies(
      runSse = runSse.bodyAsText(),
      sessions = sessions.bodyAsText(),
      session = session.bodyAsText(),
    )
  }

  /** Checks that [body] holds both of [TOKEN_STATE]'s tokens as placeholders only. */
  private fun assertTokensRedacted(body: String) {
    assertThat(body).contains("\"$DERIVED_KEY\":\"<redacted>\"")
    assertThat(body).contains("\"user:$DERIVED_KEY\":\"<redacted>\"")
    assertThat(body).doesNotContain(SESSION_TOKEN)
    assertThat(body).doesNotContain(USER_TOKEN)
  }

  private fun config(): AdkServerConfig =
    AdkServerConfig(
      appLoader = FakeAppLoader(APP) { signInAgent() },
      sessionService = sessionService(),
      artifactService = FakeArtifactService(),
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

  private companion object {
    const val APP = "app"
    const val USER = "user"
    const val SESSION = "session"
    const val TOOL = "read_data"
    const val SENTINEL = "secret-sentinel"
    const val TOKEN_SESSION = "token-session"
    const val SESSION_TOKEN = "session-scoped-token"
    const val USER_TOKEN = "user-scoped-token"

    /** A credential key in the shape ADK derives one, under which state holds a credential. */
    const val DERIVED_KEY = "adk_apiKey_0123456789abcdef_"

    /** A session-create body with a bare token under [DERIVED_KEY], unscoped and user-scoped. */
    const val TOKEN_STATE =
      """{"state": {"$DERIVED_KEY": "$SESSION_TOKEN", "user:$DERIVED_KEY": "$USER_TOKEN"}}"""

    val CONFIG: AuthConfig =
      AuthConfig(
        AuthScheme.ApiKey(name = "X-Key", location = AuthScheme.ApiKey.Location.HEADER),
        credentialKey = CredentialKey("key"),
      )

    const val HI = """{"role": "user", "parts": [{"text": "hi"}]}"""

    /** Returns a `/run` body with [message], the new message's JSON. */
    fun runBody(message: String): String =
      """{"appName": "$APP", "userId": "$USER", "sessionId": "$SESSION", "newMessage": $message}"""
  }
}
