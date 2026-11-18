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
import com.google.adk.kt.serialization.adkJson
import com.google.adk.kt.sessions.State
import com.google.adk.kt.testing.signInResponseStateKey
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Test

@OptIn(ExperimentalAuthApi::class, FrameworkInternalApi::class)
class SignInResponsesTest {

  @Test
  fun storeSignInResponse_apiKeyResponseToOAuth2Request_storesItAsIs() {
    // A credential of another type takes none of the request's OAuth2 fields.
    val state = State()

    val outcome =
      SIGN_IN_REQUEST.storeSignInResponse(
        mapOf("authType" to "apiKey", "apiKey" to "user-key"),
        state,
      )

    assertEquals(StoreSignInResponseOutcome.STORED, outcome)
    assertEquals(
      AuthCredential.ApiKey(Secret("user-key")),
      state[SIGN_IN_REQUEST.signInResponseStateKey],
    )
  }

  @Test
  fun storeSignInResponse_apiKeyResponse_storesTheKey() {
    val config = AuthConfig(API_KEY, credentialKey = CredentialKey("api-key"))
    val state = State()

    val outcome =
      config.storeSignInResponse(mapOf("authType" to "apiKey", "apiKey" to "user-key"), state)

    assertEquals(StoreSignInResponseOutcome.STORED, outcome)
    assertEquals(json("""{"authType": "apiKey", "apiKey": "user-key"}"""), state.storedJson(config))
  }

  @Test
  fun storeSignInResponse_authConfigResponse_storesItsExchangedCredential() {
    // The response's own scheme doesn't count; the request's key does.
    val state = State()

    val outcome =
      SIGN_IN_REQUEST.storeSignInResponse(
        mapOf("authScheme" to HTTP_BEARER, "exchangedAuthCredential" to TOKENS),
        state,
      )

    assertEquals(StoreSignInResponseOutcome.STORED, outcome)
    assertEquals(json(STORED_TOKENS), state.storedJson(SIGN_IN_REQUEST))
  }

  @Test
  fun storeSignInResponse_configuredClientSecret_isNotStored() {
    val config = AuthConfig(AUTH_CODE, CONFIDENTIAL)
    val state = State()

    val outcome = config.storeSignInResponse(TOKENS, state)

    assertEquals(StoreSignInResponseOutcome.STORED, outcome)
    val credential = assertIs<AuthCredential.OAuth2>(state[config.signInResponseStateKey])
    assertEquals("cid", credential.clientId)
    assertNull(credential.clientSecret)
  }

  @Test
  fun storeSignInResponse_credentialResponse_storesItWithTheRequestsFields() {
    val state = State()

    val outcome = SIGN_IN_REQUEST.storeSignInResponse(TOKENS, state)

    assertEquals(StoreSignInResponseOutcome.STORED, outcome)
    assertEquals(json(STORED_TOKENS), state.storedJson(SIGN_IN_REQUEST))
  }

  @Test
  fun storeSignInResponse_oauth2ResponseWithoutAccessToken_needsCodeExchange() {
    // ADK can't exchange an authorization code yet, so neither response is stored.
    val cases =
      listOf(
        AuthConfig(AUTH_CODE, CONFIDENTIAL) to
          mapOf("authType" to "oauth2", "oauth2" to mapOf("authCode" to "code")),
        SIGN_IN_REQUEST to mapOf("authType" to "oauth2", "oauth2" to mapOf("accessToken" to "")),
      )

    for ((config, response) in cases) {
      val state = State()

      val outcome = config.storeSignInResponse(response, state)

      assertEquals(StoreSignInResponseOutcome.NEEDS_CODE_EXCHANGE, outcome)
      assertTrue(state.isEmpty())
    }
  }

  @Test
  fun storeSignInResponse_responseNamingTokenEndpointAuthMethod_keepsIt() {
    val config = SIGN_IN_REQUEST.copy(exchangedAuthCredential = CLIENT_SECRET_POST_REQUEST)
    val response =
      mapOf(
        "authType" to "openIdConnect",
        "oauth2" to mapOf("accessToken" to "at", "tokenEndpointAuthMethod" to "client_secret_basic"),
      )
    val state = State()

    assertEquals(StoreSignInResponseOutcome.STORED, config.storeSignInResponse(response, state))
    assertEquals(
      AuthCredential.OAuth2.TokenEndpointAuthMethod.CLIENT_SECRET_BASIC,
      assertIs<AuthCredential.OAuth2>(state[config.signInResponseStateKey]).tokenEndpointAuthMethod,
    )
  }

  @Test
  fun storeSignInResponse_responseWithClientFields_pinsTheClientId() {
    // The response's redirect URI is kept, and its client secret dropped.
    val response =
      mapOf(
        "authType" to "openIdConnect",
        "oauth2" to
          mapOf(
            "accessToken" to "at",
            "clientId" to "evil",
            "clientSecret" to "evil-sec",
            "redirectUri" to "https://evil/cb",
          ),
      )
    val state = State()

    val outcome = SIGN_IN_REQUEST.storeSignInResponse(response, state)

    assertEquals(StoreSignInResponseOutcome.STORED, outcome)
    assertEquals(
      json(
        """{"authType": "openIdConnect", "oauth2": {"accessToken": "at", "clientId": "cid",
          "redirectUri": "https://evil/cb", "tokenEndpointAuthMethod": "client_secret_basic"}}"""
      ),
      state.storedJson(SIGN_IN_REQUEST),
    )
  }

  @Test
  fun storeSignInResponse_responseWithCodeVerifier_keepsIt() {
    val exchanged =
      SIGN_IN_REQUEST.exchangedAuthCredential
        .oauth2()
        .copy(codeVerifier = Secret(VERIFIER), codeChallengeMethod = "S256")
    val config = SIGN_IN_REQUEST.copy(exchangedAuthCredential = exchanged)
    val responseVerifier = "r".repeat(43)
    val state = State()

    val outcome =
      config.storeSignInResponse(
        mapOf(
          "authType" to "openIdConnect",
          "oauth2" to
            mapOf(
              "accessToken" to "at",
              "codeVerifier" to responseVerifier,
              "codeChallengeMethod" to "plain",
            ),
        ),
        state,
      )

    assertEquals(StoreSignInResponseOutcome.STORED, outcome)
    assertEquals(
      json(
        """{"authType": "openIdConnect", "oauth2": {"accessToken": "at", "clientId": "cid",
          "codeChallengeMethod": "plain", "codeVerifier": "$responseVerifier",
          "redirectUri": "https://app/cb", "tokenEndpointAuthMethod": "client_secret_basic"}}"""
      ),
      state.storedJson(config),
    )
  }

  @Test
  fun storeSignInResponse_responseWithoutCodeVerifier_takesTheRequestsVerifier() {
    val exchanged =
      SIGN_IN_REQUEST.exchangedAuthCredential
        .oauth2()
        .copy(codeVerifier = Secret(VERIFIER), codeChallengeMethod = "S256")
    val config = SIGN_IN_REQUEST.copy(exchangedAuthCredential = exchanged)
    val state = State()

    val outcome =
      config.storeSignInResponse(
        mapOf("authType" to "openIdConnect", "oauth2" to mapOf("accessToken" to "at")),
        state,
      )

    assertEquals(StoreSignInResponseOutcome.STORED, outcome)
    assertEquals(
      json(
        """{"authType": "openIdConnect", "oauth2": {"accessToken": "at", "clientId": "cid",
          "codeChallengeMethod": "S256", "codeVerifier": "$VERIFIER",
          "redirectUri": "https://app/cb", "tokenEndpointAuthMethod": "client_secret_basic"}}"""
      ),
      state.storedJson(config),
    )
  }

  @Test
  fun storeSignInResponse_responseWithoutCredential_storesNothing() {
    val responses = listOf(mapOf("authScheme" to HTTP_BEARER), mapOf("foo" to 1), "not a map", null)

    for (response in responses) {
      val state = State()

      val outcome = SIGN_IN_REQUEST.storeSignInResponse(response, state)

      assertEquals(StoreSignInResponseOutcome.NO_CREDENTIAL, outcome)
      assertTrue(state.isEmpty())
    }
  }

  @Test
  fun storeSignInResponse_responseWithoutTokenEndpointAuthMethod_takesTheRequestsMethod() {
    val config = SIGN_IN_REQUEST.copy(exchangedAuthCredential = CLIENT_SECRET_POST_REQUEST)
    val responses =
      listOf(TOKENS, mapOf("authScheme" to HTTP_BEARER, "exchangedAuthCredential" to TOKENS))

    for (response in responses) {
      val state = State()

      assertEquals(StoreSignInResponseOutcome.STORED, config.storeSignInResponse(response, state))
      assertEquals(
        AuthCredential.OAuth2.TokenEndpointAuthMethod.CLIENT_SECRET_POST,
        assertIs<AuthCredential.OAuth2>(state[config.signInResponseStateKey])
          .tokenEndpointAuthMethod,
      )
    }
  }

  @Test
  fun storeSignInResponse_tokenlessOAuth2ResponseOnHttpScheme_storesIt() {
    // Only OAuth2 and OpenID Connect schemes exchange a code.
    val config = AuthConfig(AuthScheme.Http.bearer())
    val state = State()

    val outcome =
      config.storeSignInResponse(
        mapOf("authType" to "oauth2", "oauth2" to mapOf("authCode" to "code")),
        state,
      )

    assertEquals(StoreSignInResponseOutcome.STORED, outcome)
    assertEquals(
      AuthCredential.OAuth2(authCode = Secret("code"), tokenEndpointAuthMethod = null),
      state[config.signInResponseStateKey],
    )
  }

  private companion object {
    val API_KEY = AuthScheme.ApiKey(name = "X-Key", location = AuthScheme.ApiKey.Location.HEADER)

    val SCOPES = mapOf("read" to "Read", "write" to "Write")

    val AUTH_CODE =
      AuthScheme.OAuth2.authorizationCode(
        authorizationUrl = "https://a/auth?tenant=t",
        tokenUrl = "https://a/token",
        scopes = SCOPES,
      )

    val OIDC =
      AuthScheme.OpenIdConnect(
        authorizationEndpoint = "https://o/auth",
        tokenEndpoint = "https://o/token",
        scopes = listOf("openid", "email"),
      )

    val CONFIDENTIAL =
      AuthCredential.OAuth2(
        clientId = "cid",
        clientSecret = Secret("sec"),
        redirectUri = "https://app/cb",
      )

    val HTTP_BEARER = mapOf("type" to "http", "scheme" to "bearer")

    /** A sign-in request ADK recorded for [OIDC], without the client secret. */
    val SIGN_IN_REQUEST =
      AuthConfig(
        authScheme = OIDC,
        rawAuthCredential =
          AuthCredential.OAuth2(
            variant = AuthCredential.OAuth2.Variant.OPEN_ID_CONNECT,
            clientId = "cid",
            redirectUri = "https://app/cb",
          ),
        exchangedAuthCredential =
          AuthCredential.OAuth2(
            variant = AuthCredential.OAuth2.Variant.OPEN_ID_CONNECT,
            clientId = "cid",
            authUri = "https://o/auth?state=s",
            state = "s",
            redirectUri = "https://app/cb",
          ),
      )

    /** [SIGN_IN_REQUEST]'s exchanged credential for a client that authenticates in the body. */
    val CLIENT_SECRET_POST_REQUEST =
      SIGN_IN_REQUEST.exchangedAuthCredential
        .oauth2()
        .copy(
          tokenEndpointAuthMethod = AuthCredential.OAuth2.TokenEndpointAuthMethod.CLIENT_SECRET_POST
        )

    val TOKENS =
      mapOf(
        "authType" to "openIdConnect",
        "oauth2" to mapOf("accessToken" to "at", "refreshToken" to "rt", "expiresAt" to 1767225600L),
      )

    /** What is stored for [TOKENS] in response to [SIGN_IN_REQUEST], in ADK Kotlin's JSON. */
    const val STORED_TOKENS =
      """{"authType": "openIdConnect", "oauth2": {"accessToken": "at", "clientId": "cid",
        "expiresAt": 1767225600, "redirectUri": "https://app/cb", "refreshToken": "rt",
        "tokenEndpointAuthMethod": "client_secret_basic"}}"""

    val VERIFIER = "v".repeat(43)

    fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    /** Returns the credential stored as [config]'s sign-in response, in ADK Kotlin's JSON. */
    fun State.storedJson(config: AuthConfig): JsonElement? =
      (this[config.signInResponseStateKey] as AuthCredential?)?.let {
        adkJson.encodeToJsonElement(AuthCredentialSerializer, it)
      }

    fun AuthCredential?.oauth2(): AuthCredential.OAuth2 = this as AuthCredential.OAuth2
  }
}
