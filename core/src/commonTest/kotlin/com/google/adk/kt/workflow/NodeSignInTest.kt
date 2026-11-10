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

package com.google.adk.kt.workflow

import com.google.adk.kt.annotations.ExperimentalAuthApi
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.annotations.SecretAccess
import com.google.adk.kt.auth.AuthConfig
import com.google.adk.kt.auth.AuthCredential
import com.google.adk.kt.auth.AuthCredentialSerializer
import com.google.adk.kt.auth.AuthScheme
import com.google.adk.kt.auth.CodeChallengeMethod
import com.google.adk.kt.auth.CredentialKey
import com.google.adk.kt.auth.Secret
import com.google.adk.kt.events.Event
import com.google.adk.kt.serialization.anyToJsonElement
import com.google.adk.kt.serialization.decodeFromPlainOrNull
import com.google.adk.kt.types.Role
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

/**
 * Expected requests show each request's call and state writes, with the random OAuth2 `state`, code
 * challenge and code verifier masked.
 */
@OptIn(ExperimentalAuthApi::class, FrameworkInternalApi::class)
class NodeSignInTest {

  @Test
  fun signInRequestEvent_anyConfig_isAModelEvent() {
    assertEquals(Role.MODEL, signInRequestEvent(API_KEY_CONFIG, ID).content?.role)
  }

  @Test
  fun signInRequestEvent_apiKeyCredentialForAnotherScheme_asksForAnApiKeyByTheGenericName() {
    val config =
      AuthConfig(
        authScheme = AuthScheme.Http(scheme = "bearer"),
        rawAuthCredential = AuthCredential.ApiKey(Secret("placeholder")),
      )

    assertEquals(
      "Please provide your API key for API key.",
      signInRequestEvent(config, ID).functionCalls().single().args["message"],
    )
  }

  @Test
  fun signInRequestEvent_apiKey_matchesTheExpectedRequest() {
    assertEquals(
      Json.parseToJsonElement(
        """{"args": {"authConfig": {"authScheme": {"location": "header", "name": "X-Api-Key",
          "type": "apiKey"}, "credentialKey": "node-cred", "rawAuthCredential": {"authType":
          "apiKey"}}, "functionCallId": "$ID", "message":
          "Please provide your API key for X-Api-Key."}, "id": "$ID", "longRunning": ["$ID"],
          "name": "adk_request_credential", "state": {}}"""
      ),
      signInRequestEvent(API_KEY_CONFIG, ID).masked(),
    )
  }

  @Test
  fun signInRequestEvent_emptyState_keepsNoState() {
    // A credential that already has an authorization URL is sent as it is.
    val raw =
      AuthCredential.OAuth2(
        clientId = "client-id",
        clientSecret = Secret("client-secret"),
        authUri = "https://provider.example.com/auth?client_id=client-id",
        state = "",
      )

    val event = signInRequestEvent(OAUTH2_CONFIG.copy(rawAuthCredential = raw), ID)

    assertEquals(emptyMap(), event.actions.stateDelta.toMap())
  }

  @Test
  fun signInRequestEvent_httpCredential_matchesTheExpectedRequest() {
    val config =
      AuthConfig(
        authScheme = AuthScheme.Http(scheme = "bearer"),
        rawAuthCredential =
          AuthCredential.Http(
            scheme = "bearer",
            credentials = AuthCredential.Http.Credentials(token = Secret("placeholder")),
          ),
        credentialKey = CredentialKey("node-cred"),
      )

    assertEquals(
      Json.parseToJsonElement(
        """{"args": {"authConfig": {"authScheme": {"scheme": "bearer", "type": "http"},
          "credentialKey": "node-cred", "rawAuthCredential": {"authType": "http", "http":
          {"credentials": {}, "scheme": "bearer"}}}, "functionCallId": "$ID", "message":
          "Please provide your authentication credentials."}, "id": "$ID", "longRunning":
          ["$ID"], "name": "adk_request_credential", "state": {}}"""
      ),
      signInRequestEvent(config, ID).masked(),
    )
  }

  @Test
  fun signInRequestEvent_noRawCredential_matchesTheExpectedRequest() {
    val config = AuthConfig(authScheme = API_KEY_SCHEME, credentialKey = CredentialKey("node-cred"))

    assertEquals(
      Json.parseToJsonElement(
        """{"args": {"authConfig": {"authScheme": {"location": "header", "name": "X-Api-Key",
          "type": "apiKey"}, "credentialKey": "node-cred"}, "functionCallId": "$ID", "message":
          "Please provide your authentication credentials."}, "id": "$ID", "longRunning":
          ["$ID"], "name": "adk_request_credential", "state": {}}"""
      ),
      signInRequestEvent(config, ID).masked(),
    )
  }

  @Test
  fun signInRequestEvent_oauth2_isFoundBySignInRequests() {
    val request = signInRequestEvent(OAUTH2_CONFIG, ID).signInRequests().single()

    assertEquals(ID, request.id)
  }

  @Test
  fun signInRequestEvent_oauth2_matchesTheExpectedRequest() {
    assertEquals(
      Json.parseToJsonElement(
        """{"args": {"authConfig": {"authScheme": {"flows": {"authorizationCode":
          {"authorizationUrl": "https://provider.example.com/auth", "scopes": {"read": "Read"},
          "tokenUrl": "https://provider.example.com/token"}}, "type": "oauth2"},
          "credentialKey": "node-cred", "exchangedAuthCredential": {"authType": "oauth2",
          "oauth2": {"authUri": "https://provider.example.com/auth?response_type=code&client_id=client-id&redirect_uri=https%3A%2F%2Fapp.example.com%2Fcallback&scope=read&state=STATE&access_type=offline&prompt=consent",
          "clientId": "client-id", "redirectUri": "https://app.example.com/callback",
          "state": "STATE", "tokenEndpointAuthMethod": "client_secret_basic"}},
          "rawAuthCredential": {"authType": "oauth2", "oauth2": {"clientId": "client-id",
          "redirectUri": "https://app.example.com/callback",
          "tokenEndpointAuthMethod": "client_secret_basic"}}}, "functionCallId": "$ID",
          "message": "Please complete the authentication flow."}, "id": "$ID", "longRunning":
          ["$ID"], "name": "adk_request_credential",
          "state": {"adk_oauth_state:$ID": "STATE"}}"""
      ),
      signInRequestEvent(OAUTH2_CONFIG, ID).masked(),
    )
  }

  @OptIn(SecretAccess::class)
  @Test
  fun signInRequestEvent_publicClient_keepsTheVerifierOfTheUrlsChallenge() {
    val event = signInRequestEvent(PUBLIC_OIDC_CONFIG, ID)

    val kept =
      decodeFromPlainOrNull(
        AuthCredentialSerializer,
        event.actions.stateDelta["adk_oauth_credential:$ID"],
      )
        as AuthCredential.OAuth2
    val challenge = CodeChallengeMethod.S256.challenge(assertNotNull(kept.codeVerifier).reveal())
    assertContains(
      assertNotNull(event.signInRequests().single().authUri),
      "code_challenge=$challenge&",
    )
  }

  @Test
  fun signInRequestEvent_publicClient_matchesTheExpectedRequest() {
    assertEquals(
      Json.parseToJsonElement(
        """{"args": {"authConfig": {"authScheme": {"authorizationEndpoint":
          "https://provider.example.com/auth", "scopes": ["openid", "email"], "tokenEndpoint":
          "https://provider.example.com/token", "type": "openIdConnect"}, "credentialKey":
          "node-cred", "exchangedAuthCredential": {"authType": "openIdConnect", "oauth2":
          {"authUri": "https://provider.example.com/auth?response_type=code&client_id=client-id&redirect_uri=https%3A%2F%2Fapp.example.com%2Fcallback&scope=openid+email&state=STATE&access_type=offline&prompt=consent&code_challenge=CHALLENGE&code_challenge_method=S256",
          "clientId": "client-id", "codeChallengeMethod": "S256", "redirectUri":
          "https://app.example.com/callback", "state": "STATE",
          "tokenEndpointAuthMethod": "client_secret_basic"}}, "rawAuthCredential": {"authType":
          "openIdConnect", "oauth2": {"clientId": "client-id", "redirectUri":
          "https://app.example.com/callback", "tokenEndpointAuthMethod":
          "client_secret_basic"}}}, "functionCallId": "$ID", "message":
          "Please complete the authentication flow."}, "id": "$ID", "longRunning": ["$ID"],
          "name": "adk_request_credential", "state": {"adk_oauth_credential:$ID": {"authType":
          "openIdConnect", "oauth2": {"codeChallengeMethod": "S256", "codeVerifier":
          "VERIFIER", "tokenEndpointAuthMethod": "client_secret_basic"}},
          "adk_oauth_state:$ID": "STATE"}}"""
      ),
      signInRequestEvent(PUBLIC_OIDC_CONFIG, ID).masked(),
    )
  }

  private companion object {
    const val ID = "wf_auth:wf@1/node@1"

    val API_KEY_SCHEME =
      AuthScheme.ApiKey(name = "X-Api-Key", location = AuthScheme.ApiKey.Location.HEADER)

    val API_KEY_CONFIG =
      AuthConfig(
        authScheme = API_KEY_SCHEME,
        rawAuthCredential = AuthCredential.ApiKey(Secret("placeholder")),
        credentialKey = CredentialKey("node-cred"),
      )

    val OAUTH2_CONFIG =
      AuthConfig(
        authScheme =
          AuthScheme.OAuth2(
            flows =
              AuthScheme.OAuth2.Flows(
                authorizationCode =
                  AuthScheme.OAuth2.Flow.AuthorizationCode(
                    authorizationUrl = "https://provider.example.com/auth",
                    tokenUrl = "https://provider.example.com/token",
                    scopes = mapOf("read" to "Read"),
                  )
              )
          ),
        rawAuthCredential =
          AuthCredential.OAuth2(
            clientId = "client-id",
            clientSecret = Secret("client-secret"),
            redirectUri = "https://app.example.com/callback",
          ),
        credentialKey = CredentialKey("node-cred"),
      )

    val PUBLIC_OIDC_CONFIG =
      AuthConfig(
        authScheme =
          AuthScheme.OpenIdConnect(
            authorizationEndpoint = "https://provider.example.com/auth",
            tokenEndpoint = "https://provider.example.com/token",
            scopes = listOf("openid", "email"),
          ),
        rawAuthCredential =
          AuthCredential.OAuth2(
            variant = AuthCredential.OAuth2.Variant.OPEN_ID_CONNECT,
            clientId = "client-id",
            redirectUri = "https://app.example.com/callback",
          ),
        credentialKey = CredentialKey("node-cred"),
      )

    /**
     * Returns this request's call and state delta as JSON. The kept `state` is masked wherever it
     * appears, so the URL, the request credential and the state delta must agree on it.
     */
    fun Event.masked(): JsonElement {
      val call = functionCalls().single()
      val dump = buildJsonObject {
        put("args", anyToJsonElement(call.args))
        put("id", call.id)
        put(
          "longRunning",
          buildJsonArray { longRunningToolIds.sorted().forEach { add(JsonPrimitive(it)) } },
        )
        put("name", call.name)
        put("state", anyToJsonElement(actions.stateDelta))
      }
      val state = actions.stateDelta["adk_oauth_state:$ID"] as String?
      val text =
        dump
          .toString()
          .replace(CODE_CHALLENGE, "code_challenge=CHALLENGE")
          .replace(CODE_VERIFIER, "\"codeVerifier\":\"VERIFIER\"")
      return Json.parseToJsonElement(if (state == null) text else text.replace(state, "STATE"))
    }

    /** The PKCE challenge in an authorization URL, random for each request. */
    val CODE_CHALLENGE = Regex("code_challenge=[^&\"]+")

    /** The PKCE code verifier kept in the state delta, random for each request. */
    val CODE_VERIFIER = Regex("\"codeVerifier\":\"[^\"]+\"")
  }
}
