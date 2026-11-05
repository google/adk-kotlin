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
import com.google.adk.kt.testing.capturingLogs
import java.util.logging.Level
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalAuthApi::class)
class AuthUriLoggingTest {

  @Test
  fun withNewAuthUri_publicClient_warnsWithoutTheClientId() {
    val (credential, records) =
      capturingLogs(LOGGER, Level.WARNING) { PUBLIC_CLIENT.withNewAuthUri(AUTH_CODE) }

    assertEquals("S256", credential.codeChallengeMethod)
    val message = records.single().message.orEmpty()
    assertTrue("PKCE S256" in message)
    assertFalse(CLIENT_ID in message)
  }

  @Test
  fun withNewAuthUri_secretOrS256Client_doesNotWarn() {
    val clients =
      listOf(
        PUBLIC_CLIENT.copy(clientSecret = Secret("sec")),
        PUBLIC_CLIENT.copy(codeChallengeMethod = "S256"),
      )

    for (client in clients) {
      val (_, records) = capturingLogs(LOGGER, Level.WARNING) { client.withNewAuthUri(AUTH_CODE) }

      assertTrue(records.isEmpty())
    }
  }

  @Test
  fun withNewAuthUri_publicClientWithAnInvalidEndpoint_throwsWithoutWarning() {
    // One scheme has no endpoint to build from; the other has a relative one.
    val schemes =
      listOf(
        AuthScheme.OpenIdConnectUrl("https://o/.well-known/openid-configuration"),
        AuthScheme.OAuth2.authorizationCode(
          authorizationUrl = "/auth",
          tokenUrl = "https://a/token",
          scopes = emptyMap(),
        ),
      )

    for (scheme in schemes) {
      val (_, records) =
        capturingLogs(LOGGER, Level.WARNING) {
          assertFailsWith<IllegalArgumentException> { PUBLIC_CLIENT.withNewAuthUri(scheme) }
        }

      assertTrue(records.isEmpty())
    }
  }

  private companion object {
    // Named after the file's private `AuthUri` object.
    const val LOGGER = "com.google.adk.kt.auth.AuthUri"

    const val CLIENT_ID = "public-client-id"

    val PUBLIC_CLIENT = AuthCredential.OAuth2(clientId = CLIENT_ID, redirectUri = "https://app/cb")

    val AUTH_CODE =
      AuthScheme.OAuth2.authorizationCode(
        authorizationUrl = "https://a/auth",
        tokenUrl = "https://a/token",
        scopes = emptyMap(),
      )
  }
}
