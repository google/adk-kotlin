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
import com.google.adk.kt.auth.AuthCredential.OAuth2.TokenEndpointAuthMethod
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import org.junit.Test

/** Expected digests are what ADK Python's `_stable_model_digest` returns for ADK Kotlin's JSON. */
@OptIn(ExperimentalAuthApi::class)
class CredentialKeyTest {

  @Test
  fun constructor_blankValue_keepsIt() {
    assertEquals(" ", CredentialKey(" ").value)
  }

  @Test
  fun constructor_emptyValue_throws() {
    assertFailsWith<IllegalArgumentException> { CredentialKey("") }
  }

  @Test
  fun deriveCredentialKey_eachRawCredential_appendsItsTypeAndDigest() {
    val expected =
      mapOf(
        AuthCredential.ApiKey(Secret("sk-123")) to "apiKey_56c19575f72717f8",
        AuthCredential.Http.bearer("t") to "http_26adec9b11eb83bc",
        AuthCredential.Http(
          scheme = "basic",
          credentials = AuthCredential.Http.Credentials(username = "u", password = Secret("p")),
          additionalHeaders = mapOf("X-B" to Secret("2"), "X-A" to Secret("1")),
        ) to "http_981218342b72d6e4",
        OAUTH2 to "oauth2_0833ddacfe0bd302",
        AuthCredential.OAuth2(
          clientId = "cid",
          clientSecret = Secret("sec"),
          nonce = "n",
          idToken = Secret("it"),
          audience = "aud",
          prompt = "consent",
          codeVerifier = Secret("cv"),
          codeChallengeMethod = "S256",
          tokenEndpointAuthMethod = TokenEndpointAuthMethod.CLIENT_SECRET_POST,
        ) to "oauth2_7be94df6df8f3e1b",
        OPEN_ID_CONNECT to "openIdConnect_d62d4f4b945e72e7",
        AuthCredential.ServiceAccount(
          serviceAccountCredential =
            AuthCredential.ServiceAccount.Credential(
              type = "service_account",
              projectId = "p",
              privateKeyId = Secret("kid"),
              privateKey = Secret("pem"),
              clientEmail = "sa@p.iam.gserviceaccount.com",
              clientId = "123",
              authUri = "https://accounts.google.com/o/oauth2/auth",
              tokenUri = "https://oauth2.googleapis.com/token",
              authProviderX509CertUrl = "https://www.googleapis.com/oauth2/v1/certs",
              clientX509CertUrl = "https://www.googleapis.com/robot/v1/metadata/x509/sa",
              universeDomain = "googleapis.com",
            ),
          scopes = listOf("s"),
        ) to "serviceAccount_9d6579ef5b6f64c8",
        AuthCredential.ServiceAccount(
          scopes = listOf("https://www.googleapis.com/auth/cloud-platform"),
          useDefaultCredential = true,
        ) to "serviceAccount_a7f38d3e6b705002",
      )

    for ((credential, suffix) in expected) {
      assertEquals(
        "adk_http_3e922d755264ee81_$suffix",
        deriveCredentialKey(BEARER, credential).value,
        credential.toString(),
      )
    }
  }

  @Test
  fun deriveCredentialKey_eachSchemeWithoutRawCredential_returnsItsTypeAndDigest() {
    val expected =
      mapOf(
        AuthScheme.ApiKey(name = "X-Key", location = AuthScheme.ApiKey.Location.HEADER) to
          "adk_apiKey_39c5e4ced628d031_",
        AuthScheme.Http.basic() to "adk_http_fc8df715f5002444_",
        AuthScheme.Http.bearer() to "adk_http_178204f758fb78d5_",
        BEARER to "adk_http_3e922d755264ee81_",
        // ADK Python reads `bearerFormat` only for the `bearer` scheme.
        AuthScheme.Http(scheme = "Bearer", bearerFormat = "JWT") to "adk_http_73e1459c0625f8fc_",
        AuthScheme.OAuth2(
          flows =
            AuthScheme.OAuth2.Flows(
              authorizationCode =
                AuthScheme.OAuth2.Flow.AuthorizationCode(
                  authorizationUrl = "https://a/auth",
                  tokenUrl = "https://a/token",
                  scopes = mapOf("read" to "Read access"),
                )
            ),
          issuerUrl = "https://a",
        ) to "adk_oauth2_d7314f78425b09b3_",
        AuthScheme.OpenIdConnectUrl("https://a/.well-known/openid-configuration") to
          "adk_openIdConnect_2ed6a9c6cda775d3_",
        OPEN_ID_CONNECT_SCHEME to "adk_openIdConnect_91fdadde054c2ad3_",
      )

    for ((scheme, key) in expected) {
      assertEquals(
        key,
        deriveCredentialKey(scheme, rawAuthCredential = null).value,
        scheme.toString(),
      )
    }
  }

  @Test
  fun deriveCredentialKey_jsonEdgeCases_digestsSortedJson() {
    val expected =
      mapOf(
        // Every character JSON escapes, and some it doesn't.
        AuthScheme.ApiKey(
          name = "X-Key",
          location = AuthScheme.ApiKey.Location.HEADER,
          description =
            "say \"hi\" \\ \b\u000C\n\r\t\u0001\u001F\u007F " +
              "\u00E9 \u4E2D\u6587 \uD83D\uDE00 a/b\u2028",
        ) to "adk_apiKey_a9a139cf63bc3f1d_",
        // Keys where one is a prefix of another.
        AuthScheme.OpenIdConnect(
          authorizationEndpoint = "https://a/auth",
          tokenEndpoint = "https://a/token",
          tokenEndpointAuthMethodsSupported = listOf("client_secret_basic"),
          scopes = listOf("openid"),
        ) to "adk_openIdConnect_0eefb71e267d9423_",
        // Nested keys out of order, sharing a prefix, and needing escapes.
        AuthScheme.OAuth2.clientCredentials(
          tokenUrl = "https://a/token",
          scopes = mapOf("ab" to "1", "a" to "2", "aa" to "3", "k\"\n" to "4"),
        ) to "adk_oauth2_9652f2fcd048ab53_",
      )

    for ((scheme, key) in expected) {
      assertEquals(
        key,
        deriveCredentialKey(scheme, rawAuthCredential = null).value,
        scheme.toString(),
      )
    }
  }

  @Test
  fun deriveCredentialKey_oauth2SignInFieldsSet_leavesThemOut() {
    val signedIn =
      OAUTH2.copy(
        authUri = "https://a/auth?state=s",
        state = "s",
        redirectUri = "https://app/cb",
        authResponseUri = Secret("https://app/cb?code=c"),
        authCode = Secret("c"),
        accessToken = Secret("at"),
        refreshToken = Secret("rt"),
        expiresAt = Instant.fromEpochSeconds(1_700_000_000),
        expiresIn = 3600.seconds,
      )

    assertEquals(
      "adk_http_3e922d755264ee81_oauth2_0833ddacfe0bd302",
      deriveCredentialKey(BEARER, signedIn).value,
    )
  }

  @Test
  fun deriveCredentialKey_openIdConnectSchemeAndCredential_digestsBoth() {
    assertEquals(
      "adk_openIdConnect_91fdadde054c2ad3_openIdConnect_d62d4f4b945e72e7",
      deriveCredentialKey(OPEN_ID_CONNECT_SCHEME, OPEN_ID_CONNECT).value,
    )
  }

  @Test
  fun equals_differentValue_isNotEqual() {
    assertNotEquals(CredentialKey("k"), CredentialKey("other"))
  }

  @Test
  fun equals_sameValue_isEqual() {
    assertEquals(CredentialKey("k"), CredentialKey("k"))
    assertEquals(CredentialKey("k").hashCode(), CredentialKey("k").hashCode())
  }

  @Test
  fun stateKeys_called_listTheTempKeyThenTheValue() {
    assertEquals(listOf("temp:k", "k"), CredentialKey("k").stateKeys)
  }

  @Test
  fun toString_anyValue_printsPlaceholder() {
    assertEquals("<redacted>", CredentialKey("k").toString())
  }

  private companion object {
    val BEARER = AuthScheme.Http.bearer("JWT")

    val OPEN_ID_CONNECT_SCHEME =
      AuthScheme.OpenIdConnect(
        authorizationEndpoint = "https://a/auth",
        tokenEndpoint = "https://a/token",
        scopes = listOf("openid"),
      )

    val OAUTH2 = AuthCredential.OAuth2(clientId = "cid", clientSecret = Secret("sec"))

    val OPEN_ID_CONNECT = OAUTH2.copy(variant = AuthCredential.OAuth2.Variant.OPEN_ID_CONNECT)
  }
}
