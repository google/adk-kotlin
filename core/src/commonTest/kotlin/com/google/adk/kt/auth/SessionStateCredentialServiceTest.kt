/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.adk.kt.auth

import com.google.adk.kt.annotations.ExperimentalAuthApi
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.annotations.SecretAccess
import com.google.adk.kt.apps.App
import com.google.adk.kt.events.Event
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.serialization.jsonElementToAny
import com.google.adk.kt.sessions.InMemorySessionService
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.testing.testInvocationContext
import com.google.adk.kt.testing.testSession
import com.google.adk.kt.testing.testToolContext
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import com.google.adk.kt.workflow.Edge
import com.google.adk.kt.workflow.Start
import com.google.adk.kt.workflow.Workflow
import com.google.adk.kt.workflow.asNode
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import org.junit.Test

@OptIn(ExperimentalAuthApi::class)
class SessionStateCredentialServiceTest {

  private val service = SessionStateCredentialService()

  @Test
  fun loadCredential_nothingSaved_returnsNull() = runBlocking {
    assertNull(service.loadCredential(SIGNED_IN_CONFIG, context()))
  }

  @Test
  fun saveCredential_thenLoad_returnsItWithTheConfiguredClientSecret() = runBlocking {
    val context = context()

    service.saveCredential(SIGNED_IN_CONFIG, context)

    assertEquals(SIGNED_IN, service.loadCredential(LOAD_CONFIG, context))
    assertEquals(
      SIGNED_IN.copy(clientSecret = null),
      context.state[SIGNED_IN_CONFIG.credentialKey.value],
    )
  }

  @Test
  fun saveCredential_oauth2Credential_storesItTypedWithoutTheClientSecret() = runBlocking {
    val context = context()

    service.saveCredential(SIGNED_IN_CONFIG, context)

    assertEquals(
      SIGNED_IN.copy(clientSecret = null),
      context.state[SIGNED_IN_CONFIG.credentialKey.value],
    )
  }

  @Test
  fun saveCredential_savedTwice_keepsTheLatest() = runBlocking {
    val context = context()
    val refreshed =
      SIGNED_IN.copy(accessToken = Secret("tok2"), clientSecret = Secret("updated-client-secret"))
    val updatedConfig = SIGNED_IN_CONFIG.copy(exchangedAuthCredential = refreshed)
    service.saveCredential(SIGNED_IN_CONFIG, context)

    service.saveCredential(updatedConfig, context)

    assertEquals(
      refreshed.copy(clientSecret = CLIENT.clientSecret),
      service.loadCredential(updatedConfig, context),
    )
  }

  @Test
  fun saveCredential_noExchangedCredential_removesTheStoredOne() = runBlocking {
    val context = context()
    service.saveCredential(SIGNED_IN_CONFIG, context)

    service.saveCredential(SIGNED_IN_CONFIG.copy(exchangedAuthCredential = null), context)

    assertNull(service.loadCredential(SIGNED_IN_CONFIG, context))
  }

  @Test
  @OptIn(ExperimentalWorkflowApi::class)
  fun saveCredential_underToolNodeStateSchema_savesAndRemovesCredentials() = runBlocking {
    val customKeyConfig = API_KEY_CONFIG.copy(credentialKey = CredentialKey("custom_key"))
    var action: suspend (ToolContext) -> Unit = { context ->
      service.saveCredential(SIGNED_IN_CONFIG, context)
      service.saveCredential(customKeyConfig, context)
    }
    val tool =
      object : BaseTool(name = "cred_tool", description = "") {
        override fun declaration(): FunctionDeclaration? = null

        override suspend fun run(context: ToolContext, args: Map<String, Any?>): Any {
          action(context)
          return "ok"
        }
      }
    val workflow =
      Workflow(
        name = "wf",
        edges = listOf(Edge(Start, tool.asNode())),
        stateSchema =
          Schema(type = Type.OBJECT, properties = mapOf("count" to Schema(type = Type.INTEGER))),
      )
    val runner = InMemoryRunner(app = App(appName = "app", rootNode = workflow))

    runner.runAsync(userId = "user", sessionId = "s1", newMessage = userMessage("{}")).toList()

    val saved = checkNotNull(runner.sessionService.getSession(SessionKey("app", "user", "s1")))
    assertEquals(SIGNED_IN, service.loadCredential(LOAD_CONFIG, context(saved)))
    assertEquals(API_KEY, service.loadCredential(customKeyConfig, context(saved)))

    action = { context -> service.saveCredential(LOAD_CONFIG, context) }
    runner.runAsync(userId = "user", sessionId = "s1", newMessage = userMessage("{}")).toList()

    val removed = checkNotNull(runner.sessionService.getSession(SessionKey("app", "user", "s1")))
    assertNull(service.loadCredential(LOAD_CONFIG, context(removed)))
    assertEquals(API_KEY, service.loadCredential(customKeyConfig, context(removed)))
  }

  @Test
  fun saveCredential_twoCredentialKeys_storesEachSeparately() = runBlocking {
    val context = context()

    service.saveCredential(SIGNED_IN_CONFIG, context)
    service.saveCredential(API_KEY_CONFIG, context)

    assertEquals(SIGNED_IN, service.loadCredential(SIGNED_IN_CONFIG, context))
    assertEquals(API_KEY, service.loadCredential(API_KEY_CONFIG, context))
  }

  @Test
  fun loadCredential_reloadedSession_returnsIt() = runBlocking {
    val sessionService = InMemorySessionService()
    val session = sessionService.createSession(SessionKey("app", "user", "s1"))
    val saving = context(session)
    service.saveCredential(SIGNED_IN_CONFIG, saving)
    val unused =
      sessionService.appendEvent(session, Event(author = "tool", actions = saving.actions))

    val reloaded = checkNotNull(sessionService.getSession(session.key))

    assertEquals(SIGNED_IN, service.loadCredential(SIGNED_IN_CONFIG, context(reloaded)))
  }

  @Test
  fun loadCredential_anotherSessionOfTheSameUser_returnsNull() = runBlocking {
    val sessionService = InMemorySessionService()
    val session = sessionService.createSession(SessionKey("app", "user", "s1"))
    val saving = context(session)
    service.saveCredential(SIGNED_IN_CONFIG, saving)
    val unused =
      sessionService.appendEvent(session, Event(author = "tool", actions = saving.actions))

    val otherSession = sessionService.createSession(SessionKey("app", "user", "s2"))

    assertNull(service.loadCredential(SIGNED_IN_CONFIG, context(otherSession)))
  }

  @Test
  @OptIn(FrameworkInternalApi::class, SecretAccess::class)
  fun loadCredential_storedCredentialJsonMap_returnsItWithTheConfiguredClientSecret() =
    runBlocking {
      // As a session service that stores JSON reads the saved credential back: plain values.
      val context = context()
      val saved = SIGNED_IN.copy(clientSecret = null)
      val plain =
        jsonElementToAny(
          adkSecretRevealingJson.encodeToJsonElement(AuthCredentialSerializer, saved)
        )
      context.updateState(SIGNED_IN_CONFIG.credentialKey.value, checkNotNull(plain))

      assertEquals(SIGNED_IN, service.loadCredential(LOAD_CONFIG, context))
    }

  @Test
  fun loadCredential_storedCredentialObject_restoresOnlyTheConfiguredClientSecret() = runBlocking {
    val context = context()
    val stored = SIGNED_IN.copy(clientId = "stored-cid", clientSecret = Secret("stored-secret"))
    context.updateState(SIGNED_IN_CONFIG.credentialKey.value, stored)

    assertEquals(
      SIGNED_IN.copy(clientId = "stored-cid"),
      service.loadCredential(LOAD_CONFIG, context),
    )
  }

  @Test
  fun loadCredential_noOAuth2RawCredential_keepsTheStoredClientSecret() = runBlocking {
    val config = SIGNED_IN_CONFIG.copy(rawAuthCredential = null)
    val context = context()
    context.updateState(config.credentialKey.value, SIGNED_IN)

    assertEquals(SIGNED_IN, service.loadCredential(config, context))
  }

  @Test
  fun loadCredential_storedString_returnsNull() = runBlocking {
    val context = context()
    context.updateState(SIGNED_IN_CONFIG.credentialKey.value, "ya29.token")

    assertNull(service.loadCredential(SIGNED_IN_CONFIG, context))
  }

  @Test
  fun loadCredential_storedNumber_returnsNull() = runBlocking {
    val context = context()
    context.updateState(SIGNED_IN_CONFIG.credentialKey.value, 42)

    assertNull(service.loadCredential(SIGNED_IN_CONFIG, context))
  }

  @Test
  fun loadCredential_malformedMap_throwsWithAFixedMessage() = runBlocking {
    val context = context()
    context.updateState(
      SIGNED_IN_CONFIG.credentialKey.value,
      mapOf("authType" to "oauth2", "oauth2" to "ya29.token"),
    )

    val e =
      assertFailsWith<SerializationException> { service.loadCredential(SIGNED_IN_CONFIG, context) }

    assertEquals("Malformed auth credential.", e.message)
    assertNull(e.cause)
  }

  @Test
  fun loadCredential_mapWithANonJsonValue_throwsWithAFixedMessage() = runBlocking {
    val context = context()
    context.updateState(
      SIGNED_IN_CONFIG.credentialKey.value,
      mapOf("authType" to "apiKey", "apiKey" to Any()),
    )

    val e =
      assertFailsWith<SerializationException> { service.loadCredential(SIGNED_IN_CONFIG, context) }

    assertEquals("Malformed auth credential.", e.message)
    assertNull(e.cause)
  }

  private companion object {
    val CLIENT = AuthCredential.OAuth2(clientId = "cid", clientSecret = Secret("agent-secret"))

    val SIGNED_IN =
      CLIENT.copy(
        accessToken = Secret("tok"),
        refreshToken = Secret("ref"),
        expiresAt = Instant.fromEpochSeconds(1_800_000_000),
      )

    val SIGNED_IN_CONFIG =
      AuthConfig(
        authScheme =
          AuthScheme.OAuth2.authorizationCode(
            authorizationUrl = "https://a/auth",
            tokenUrl = "https://a/token",
            scopes = mapOf("read" to "Read"),
          ),
        rawAuthCredential = CLIENT,
        exchangedAuthCredential = SIGNED_IN,
      )

    val LOAD_CONFIG = SIGNED_IN_CONFIG.copy(exchangedAuthCredential = null)

    val API_KEY = AuthCredential.ApiKey(Secret("sk-123"))

    val API_KEY_CONFIG =
      AuthConfig(
        authScheme =
          AuthScheme.ApiKey(name = "X-Key", location = AuthScheme.ApiKey.Location.HEADER),
        exchangedAuthCredential = API_KEY,
      )

    fun context(session: Session = testSession("app", "user", "s1")): ToolContext =
      testToolContext(testInvocationContext(session = session))
  }
}
