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
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.annotations.SecretAccess
import com.google.adk.kt.serialization.anyToJsonElement
import com.google.adk.kt.sessions.State
import com.google.adk.kt.testing.signInResponseStateKey
import com.google.adk.kt.testing.testApiKeyConfig
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.Role
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import org.junit.Test

@OptIn(ExperimentalAuthApi::class, FrameworkInternalApi::class, SecretAccess::class)
class SignInRequestTest {

  @Test
  fun authUri_apiKeyRequest_isNull() {
    assertNull(signInRequest(API_KEY_REQUEST).authUri)
  }

  @Test
  fun authUri_oauth2Request_isTheAuthorizationUrl() {
    assertEquals("https://a/auth?state=s", signInRequest(OIDC_REQUEST).authUri)
  }

  @Test
  fun equals_differentId_isNotEqual() {
    assertNotEquals(
      SignInRequest(id = "req-1", authConfig = API_KEY_REQUEST),
      SignInRequest(id = "req-2", authConfig = API_KEY_REQUEST),
    )
  }

  @Test
  fun equals_differentAuthConfig_isNotEqual() {
    assertNotEquals(signInRequest(API_KEY_REQUEST), signInRequest(OIDC_REQUEST))
  }

  @Test
  fun equals_sameProperties_isEqual() {
    assertEquals(signInRequest(OIDC_REQUEST), signInRequest(OIDC_REQUEST))
    assertEquals(signInRequest(OIDC_REQUEST).hashCode(), signInRequest(OIDC_REQUEST).hashCode())
  }

  @Test
  fun createResponse_apiKeyCredential_answersTheRequestWithItsConfig() {
    val message =
      signInRequest(API_KEY_REQUEST).createResponse(AuthCredential.ApiKey(Secret("user-key")))

    val response = message.functionResponse()
    assertEquals(Role.USER, message.role)
    assertEquals(FunctionCall.REQUEST_EUC_FUNCTION_CALL_NAME, response.name)
    assertEquals("req-1", response.id)
    assertEquals(
      Json.parseToJsonElement(
        """{"authScheme": {"type": "apiKey", "in": "header", "name": "X-Key"},
          "exchangedAuthCredential": {"authType": "apiKey", "apiKey": "user-key"},
          "credentialKey": "key"}"""
      ),
      anyToJsonElement(response.response, adkSecretRevealingJson),
    )
  }

  @Test
  fun createResponse_apiKeyCredential_outputLeavesTheKeyOut() {
    val message =
      signInRequest(API_KEY_REQUEST).createResponse(AuthCredential.ApiKey(Secret("user-key")))

    assertEquals(
      Json.parseToJsonElement(
        """{"authScheme": {"type": "apiKey", "in": "header", "name": "X-Key"},
          "exchangedAuthCredential": {"authType": "apiKey"}, "credentialKey": "key"}"""
      ),
      anyToJsonElement(message.functionResponse().response),
    )
  }

  @Test
  fun createResponse_httpCredentialToOAuth2Request_replacesTheRequestCredential() {
    val answer = AuthCredential.Http.bearer("t")

    val message = signInRequest(OIDC_REQUEST).createResponse(answer)

    val expected = OIDC_REQUEST.copy(exchangedAuthCredential = answer)
    assertEquals(
      adkSecretRevealingJson.encodeToJsonElement(AuthConfigSerializer, expected),
      anyToJsonElement(message.functionResponse().response, adkSecretRevealingJson),
    )
  }

  @Test
  fun createResponse_oauth2CredentialWithAccessToken_fillsInTheRequestCredential() {
    val answer =
      AuthCredential.OAuth2(
        redirectUri = "https://app/other-cb",
        authResponseUri = Secret("https://app/other-cb?code=c&state=s"),
        authCode = Secret("c"),
        accessToken = Secret("at"),
        refreshToken = Secret("rt"),
        idToken = Secret("it"),
        expiresAt = Instant.fromEpochSeconds(1_767_225_600L),
        expiresIn = 3600.seconds,
      )

    val message = signInRequest(OIDC_REQUEST).createResponse(answer)

    val filledIn =
      OIDC_SIGN_IN.copy(
        redirectUri = answer.redirectUri,
        authResponseUri = answer.authResponseUri,
        authCode = answer.authCode,
        accessToken = answer.accessToken,
        refreshToken = answer.refreshToken,
        idToken = answer.idToken,
        expiresAt = answer.expiresAt,
        expiresIn = answer.expiresIn,
      )
    val expected = OIDC_REQUEST.copy(exchangedAuthCredential = filledIn)
    assertEquals(
      adkSecretRevealingJson.encodeToJsonElement(AuthConfigSerializer, expected),
      anyToJsonElement(message.functionResponse().response, adkSecretRevealingJson),
    )
  }

  @Test
  fun createResponse_oauth2CredentialWithAccessToken_isStoredAsTheCredential() {
    val state = State()

    val message =
      signInRequest(OIDC_REQUEST).createResponse(AuthCredential.OAuth2(accessToken = Secret("at")))

    assertEquals(
      StoreSignInResponseOutcome.STORED,
      OIDC_REQUEST.storeSignInResponse(message.functionResponse().response, state),
    )
    assertEquals(
      OIDC_SIGN_IN.copy(accessToken = Secret("at")),
      state[OIDC_REQUEST.signInResponseStateKey],
    )
  }

  @Test
  fun createResponse_oauth2CredentialWithOnlyAccessToken_keepsTheRequestCredentialsFields() {
    val message =
      signInRequest(OIDC_REQUEST).createResponse(AuthCredential.OAuth2(accessToken = Secret("at")))

    val expected =
      OIDC_REQUEST.copy(exchangedAuthCredential = OIDC_SIGN_IN.copy(accessToken = Secret("at")))
    assertEquals(
      adkSecretRevealingJson.encodeToJsonElement(AuthConfigSerializer, expected),
      anyToJsonElement(message.functionResponse().response, adkSecretRevealingJson),
    )
  }

  @Test
  fun createResponse_oauth2CredentialWithoutAccessToken_throws() {
    val request = signInRequest(OIDC_REQUEST)

    val error =
      assertFailsWith<IllegalArgumentException> {
        request.createResponse(AuthCredential.OAuth2(authCode = Secret("code")))
      }

    assertEquals(
      "A sign-in response needs an access token; ADK can't exchange an authorization code yet.",
      error.message,
    )
  }

  @Test
  fun createResponse_requestWithEveryField_writesEveryConfigField() {
    // Every field, so that one added later can't be left out of the response unnoticed.
    val message =
      signInRequest(OIDC_REQUEST).createResponse(AuthCredential.OAuth2(accessToken = Secret("at")))

    assertEquals(
      AuthConfigSerializer.descriptor.elementNames.toSet(),
      message.functionResponse().response.keys,
    )
  }

  private companion object {
    val API_KEY_REQUEST = testApiKeyConfig(credentialKey = "key")

    /** The credential ADK sends to sign in to an OpenID Connect provider. */
    val OIDC_SIGN_IN =
      AuthCredential.OAuth2(
        variant = AuthCredential.OAuth2.Variant.OPEN_ID_CONNECT,
        clientId = "cid",
        authUri = "https://a/auth?state=s",
        state = "s",
        redirectUri = "https://app/cb",
      )

    /** The sign-in request ADK records for an OpenID Connect tool, without the client secret. */
    val OIDC_REQUEST =
      AuthConfig(
        authScheme =
          AuthScheme.OpenIdConnect(
            authorizationEndpoint = "https://a/auth",
            tokenEndpoint = "https://a/token",
            scopes = listOf("openid"),
          ),
        rawAuthCredential =
          AuthCredential.OAuth2(
            variant = AuthCredential.OAuth2.Variant.OPEN_ID_CONNECT,
            clientId = "cid",
            redirectUri = "https://app/cb",
          ),
        exchangedAuthCredential = OIDC_SIGN_IN,
      )

    fun signInRequest(authConfig: AuthConfig): SignInRequest =
      SignInRequest(id = "req-1", authConfig = authConfig)

    fun Content.functionResponse(): FunctionResponse = checkNotNull(parts.single().functionResponse)
  }
}
