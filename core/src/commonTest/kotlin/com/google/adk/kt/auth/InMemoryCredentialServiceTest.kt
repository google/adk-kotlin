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
import com.google.adk.kt.testing.testInvocationContext
import com.google.adk.kt.testing.testSession
import com.google.adk.kt.testing.testToolContext
import com.google.adk.kt.tools.ToolContext
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Test

@OptIn(ExperimentalAuthApi::class)
class InMemoryCredentialServiceTest {

  private val service = InMemoryCredentialService()

  @Test
  fun loadCredential_nothingSaved_returnsNull() = runBlocking {
    assertNull(service.loadCredential(SIGNED_IN_CONFIG, context()))
  }

  @Test
  fun saveCredential_thenLoad_returnsItWithTheClientSecret() = runBlocking {
    service.saveCredential(SIGNED_IN_CONFIG, context())

    assertEquals(SIGNED_IN, service.loadCredential(SIGNED_IN_CONFIG, context()))
  }

  @Test
  fun saveCredential_savedTwice_keepsTheLatest() = runBlocking {
    val refreshed = SIGNED_IN.copy(accessToken = Secret("tok2"))
    service.saveCredential(SIGNED_IN_CONFIG, context())

    service.saveCredential(SIGNED_IN_CONFIG.copy(exchangedAuthCredential = refreshed), context())

    assertEquals(refreshed, service.loadCredential(SIGNED_IN_CONFIG, context()))
  }

  @Test
  fun saveCredential_noExchangedCredential_removesTheStoredOne() = runBlocking {
    service.saveCredential(SIGNED_IN_CONFIG, context())

    service.saveCredential(SIGNED_IN_CONFIG.copy(exchangedAuthCredential = null), context())

    assertNull(service.loadCredential(SIGNED_IN_CONFIG, context()))
  }

  @Test
  fun loadCredential_anotherSessionOfTheSameUser_returnsIt() = runBlocking {
    service.saveCredential(SIGNED_IN_CONFIG, context(sessionId = "s1"))

    assertEquals(SIGNED_IN, service.loadCredential(SIGNED_IN_CONFIG, context(sessionId = "s2")))
  }

  @Test
  fun loadCredential_anotherUserOrApp_returnsNull() = runBlocking {
    service.saveCredential(SIGNED_IN_CONFIG, context())

    assertNull(service.loadCredential(SIGNED_IN_CONFIG, context(userId = "other")))
    assertNull(service.loadCredential(SIGNED_IN_CONFIG, context(appName = "other")))
  }

  @Test
  fun saveCredential_twoCredentialKeys_storesEachSeparately() = runBlocking {
    service.saveCredential(SIGNED_IN_CONFIG, context())
    service.saveCredential(API_KEY_CONFIG, context())

    assertEquals(SIGNED_IN, service.loadCredential(SIGNED_IN_CONFIG, context()))
    assertEquals(API_KEY, service.loadCredential(API_KEY_CONFIG, context()))
  }

  @Test
  fun saveCredential_concurrentSaves_keepsEveryCredential() = runBlocking {
    val configs = (1..200).map { SIGNED_IN_CONFIG.copy(credentialKey = CredentialKey("key$it")) }

    configs
      .map { config -> async(Dispatchers.Default) { service.saveCredential(config, context()) } }
      .awaitAll()

    for (config in configs) assertEquals(SIGNED_IN, service.loadCredential(config, context()))
  }

  private companion object {
    val CLIENT = AuthCredential.OAuth2(clientId = "cid", clientSecret = Secret("sec"))

    val SIGNED_IN = CLIENT.copy(accessToken = Secret("tok"), refreshToken = Secret("ref"))

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

    val API_KEY = AuthCredential.ApiKey(Secret("sk-123"))

    val API_KEY_CONFIG =
      AuthConfig(
        authScheme =
          AuthScheme.ApiKey(name = "X-Key", location = AuthScheme.ApiKey.Location.HEADER),
        exchangedAuthCredential = API_KEY,
      )

    fun context(
      appName: String = "app",
      userId: String = "user",
      sessionId: String = "s1",
    ): ToolContext =
      testToolContext(testInvocationContext(session = testSession(appName, userId, sessionId)))
  }
}
