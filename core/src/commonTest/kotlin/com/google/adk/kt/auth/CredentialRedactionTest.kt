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
import com.google.adk.kt.events.Event
import com.google.adk.kt.serialization.adkJson
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Test

/** Which credential secrets a JSON payload loses, and which values it keeps. */
@OptIn(ExperimentalAuthApi::class, FrameworkInternalApi::class)
class CredentialRedactionTest {

  @Test
  fun withoutCredentialSecrets_authTypeNotACredentialType_keepsTheObject() {
    val payload =
      """{"dict": {"authType": {"nested": "dict"}, "token": "kept"},
        "list": {"auth_type": ["nested", "list"], "apiKey": "kept"},
        "null": {"authType": null, "password": "kept"},
        "number": {"auth_type": 123, "accessToken": "kept"},
        "unknown": {"authType": "custom", "clientSecret": "kept"}}"""

    assertEquals(json(payload), redacted(payload))
  }

  @Test
  fun withoutCredentialSecrets_credentialCallNonObjectArgs_areWalked() {
    val payload =
      """{"name": "adk_request_credential",
        "args": [{"authType": "apiKey", "apiKey": "key"}, {"token": "kept"}]}"""

    assertEquals(
      json(
        """{"name": "adk_request_credential",
          "args": [{"authType": "apiKey"}, {"token": "kept"}]}"""
      ),
      redacted(payload),
    )
  }

  @Test
  fun withoutCredentialSecrets_credentialCallOtherFields_areWalked() {
    val payload =
      """{"name": "adk_request_credential", "id": "req-1",
        "extra": {"credential": {"authType": "apiKey", "apiKey": "key"}, "token": "kept"}}"""

    assertEquals(
      json(
        """{"name": "adk_request_credential", "id": "req-1",
          "extra": {"credential": {"authType": "apiKey"}, "token": "kept"}}"""
      ),
      redacted(payload),
    )
  }

  @Test
  fun withoutCredentialSecrets_credentialFieldOfTheWrongShape_isDropped() {
    val payload =
      """[{"authType": "http", "http": "basic user:pw"},
        {"authType": "oauth2", "oauth2": [{"accessToken": "at"}]},
        {"authType": "oauth2", "oauth2": {"clientId": {"apiKey": "k"}, "state": ["s"]}},
        {"rawAuthCredential": {"authType": "apiKey"},
          "credentialKey": {"authType": "apiKey", "apiKey": "k"}},
        {"authType": "serviceAccount", "serviceAccount": {"scopes": ["s", {"privateKey": "pk"}]}}]"""

    assertEquals(
      json(
        """[{"authType": "http"}, {"authType": "oauth2"}, {"authType": "oauth2", "oauth2": {}},
          {"rawAuthCredential": {"authType": "apiKey"}},
          {"authType": "serviceAccount", "serviceAccount": {"scopes": ["s"]}}]"""
      ),
      redacted(payload),
    )
  }

  @Test
  fun withoutCredentialSecrets_credentialInStateDelta_dropsItsSecrets() {
    // A workflow node's sign-in request keeps its PKCE code verifier in the state delta.
    val payload =
      """{"actions": {"stateDelta": {"adk_oauth_state:id": "s", "adk_oauth_credential:id":
        {"authType": "openIdConnect", "oauth2": {"codeChallengeMethod": "S256",
          "codeVerifier": "cv", "tokenEndpointAuthMethod": "client_secret_basic"}}}}}"""

    assertEquals(
      json(
        """{"actions": {"stateDelta": {"adk_oauth_state:id": "s", "adk_oauth_credential:id":
          {"authType": "openIdConnect", "oauth2": {"codeChallengeMethod": "S256",
            "tokenEndpointAuthMethod": "client_secret_basic"}}}}}"""
      ),
      redacted(payload),
    )
  }

  @Test
  fun withoutCredentialSecrets_credentialInsideAScheme_dropsItsSecrets() {
    val payload =
      """{"authScheme": {"type": "http", "scheme": "bearer",
          "extra": {"authType": "apiKey", "apiKey": "k"}},
        "rawAuthCredential": {"authType": "apiKey"}}"""

    assertEquals(
      json(
        """{"authScheme": {"type": "http", "scheme": "bearer", "extra": {"authType": "apiKey"}},
          "rawAuthCredential": {"authType": "apiKey"}}"""
      ),
      redacted(payload),
    )
  }

  @Test
  fun withoutCredentialSecrets_credentialRequestCallAuthConfigNotAnObject_isDropped() {
    val payload =
      """{"functionCall": {"id": "req-1", "name": "adk_request_credential", "args": {
          "functionCallId": "call-1", "authConfig": "user-key"}}}"""

    assertEquals(
      json(
        """{"functionCall": {"id": "req-1", "name": "adk_request_credential", "args": {
            "functionCallId": "call-1"}}}"""
      ),
      redacted(payload),
    )
  }

  @Test
  fun withoutCredentialSecrets_credentialRequestCallAuthConfigWithoutCredential_dropsItsSecrets() {
    val payload =
      """{"functionCall": {"id": "req-1", "name": "adk_request_credential", "args": {
          "functionCallId": "call-1", "authConfig": {"credentialKey": "key", "token": "t"}}}}"""

    assertEquals(
      json(
        """{"functionCall": {"id": "req-1", "name": "adk_request_credential", "args": {
            "functionCallId": "call-1", "authConfig": {"credentialKey": "key"}}}}"""
      ),
      redacted(payload),
    )
  }

  @Test
  fun withoutCredentialSecrets_credentialRequestCallOtherArgs_areWalked() {
    val payload =
      """{"functionCall": {"id": "req-1", "name": "adk_request_credential", "args": {
          "functionCallId": "call-1", "note": {"authType": "apiKey", "apiKey": "k"}}}}"""

    assertEquals(
      json(
        """{"functionCall": {"id": "req-1", "name": "adk_request_credential", "args": {
            "functionCallId": "call-1", "note": {"authType": "apiKey"}}}}"""
      ),
      redacted(payload),
    )
  }

  @Test
  fun withoutCredentialSecrets_credentialRequestCallScheme_isKept() {
    // The scheme declares no secret, so its password flow and a scope named "token" stay.
    val scheme =
      """{"type": "oauth2", "flows": {"password": {"tokenUrl": "https://a/token",
        "scopes": {"token": "Issue tokens"}}}}"""
    val payload =
      """{"functionCall": {"id": "req-1", "name": "adk_request_credential", "args": {
          "functionCallId": "call-1", "authConfig": {"authScheme": $scheme,
            "rawAuthCredential": {"authType": "oauth2", "oauth2": {"clientId": "cid",
              "clientSecret": "cs"}}}}}}"""

    assertEquals(
      json(
        """{"functionCall": {"id": "req-1", "name": "adk_request_credential", "args": {
            "functionCallId": "call-1", "authConfig": {"authScheme": $scheme,
              "rawAuthCredential": {"authType": "oauth2", "oauth2": {"clientId": "cid"}}}}}}"""
      ),
      redacted(payload),
    )
  }

  @Test
  fun withoutCredentialSecrets_credentialRequestCall_dropsSecretsFromItsAuthConfig() {
    val payload =
      """[{"functionCall": {"id": "req-1", "name": "adk_request_credential", "args": {
          "functionCallId": "call-1",
          "authConfig": {"token": "t", "rawAuthCredential": {"oauth2": {"clientId": "cid",
            "clientSecret": "cs"}}}}}},
        {"functionCall": {"id": "req-2", "name": "adk_request_credential", "args": {
          "functionCallId": "call-2",
          "auth_config": {"token": "t", "raw_auth_credential": {"oauth2": {"client_id": "cid2",
            "client_secret": "cs2"}}}}}}]"""

    assertEquals(
      json(
        """[{"functionCall": {"id": "req-1", "name": "adk_request_credential", "args": {
            "functionCallId": "call-1",
            "authConfig": {"rawAuthCredential": {"oauth2": {"clientId": "cid"}}}}}},
          {"functionCall": {"id": "req-2", "name": "adk_request_credential", "args": {
            "functionCallId": "call-2",
            "auth_config": {"raw_auth_credential": {"oauth2": {"client_id": "cid2"}}}}}}]"""
      ),
      redacted(payload),
    )
  }

  @Test
  fun withoutCredentialSecrets_credentialResponseNotAnObject_isDropped() {
    val responses =
      mapOf("string" to "\"user-key\"", "number" to "123", "array" to """["user-key"]""")
    for ((shape, response) in responses) {
      val payload =
        """{"functionResponse": {"id": "req-1", "name": "adk_request_credential",
          "response": $response}}"""

      assertEquals(
        json(
          """{"functionResponse": {"id": "req-1", "name": "adk_request_credential",
              "response": {}}}"""
        ),
        redacted(payload),
        shape,
      )
    }
  }

  @Test
  fun withoutCredentialSecrets_credentialResponseBareCredential_dropsItsSecrets() {
    val payload =
      """{"functionResponse": {"id": "req-1", "name": "adk_request_credential",
          "response": {"authType": "apiKey", "apiKey": "user-key"}}}"""

    assertEquals(
      json(
        """{"functionResponse": {"id": "req-1", "name": "adk_request_credential",
            "response": {"authType": "apiKey"}}}"""
      ),
      redacted(payload),
    )
  }

  @Test
  fun withoutCredentialSecrets_credentialResponseResult_isDropped() {
    // A `result` holds a bare key or token, or credential JSON as text, that no key name marks.
    val payloads =
      listOf(
        """{"functionResponse": {"id": "req-1", "name": "adk_request_credential", "response": {
            "result": "user-key"}}}""",
        """{"functionResponse": {"id": "req-1", "name": "adk_request_credential", "response": {
            "result": "{\"authType\": \"apiKey\", \"apiKey\": \"user-key\"}"}}}""",
      )

    for (payload in payloads) {
      assertEquals(
        json(
          """{"functionResponse": {"id": "req-1", "name": "adk_request_credential",
              "response": {}}}"""
        ),
        redacted(payload),
      )
    }
  }

  @Test
  fun withoutCredentialSecrets_credentialResponse_dropsSecretsFromItsResponse() {
    val payload =
      """{"functionResponse": {"id": "req-1", "name": "adk_request_credential", "response": {
          "token": "t",
          "exchangedAuthCredential": {"oauth2": {"clientId": "cid", "accessToken": "at",
            "authResponseUri": "https://app/cb?code=c"}},
          "credentialKey": "key"}}}"""

    assertEquals(
      json(
        """{"functionResponse": {"id": "req-1", "name": "adk_request_credential", "response": {
            "exchangedAuthCredential": {"oauth2": {"clientId": "cid"}}, "credentialKey": "key"}}}"""
      ),
      redacted(payload),
    )
  }

  @Test
  fun withoutCredentialSecrets_credentialUndeclaredField_isDropped() {
    val payload =
      """{"authType": "oauth2", "label": "personal",
        "oauth2": {"clientId": "cid", "sessionToken": "st"},
        "rawAuthCredential": {"oauth2": {"clientId": "cid"}}}"""

    assertEquals(
      json("""{"authType": "oauth2", "oauth2": {"clientId": "cid"}}"""),
      redacted(payload),
    )
  }

  @Test
  fun withoutCredentialSecrets_credential_dropsItsSecrets() {
    val payload =
      """{"authType": "openIdConnect", "oauth2": {"clientId": "cid", "clientSecret": "cs",
        "state": "s", "authCode": "ac", "authResponseUri": "u", "accessToken": "at",
        "refreshToken": "rt", "idToken": "it", "codeVerifier": "cv"}}"""

    assertEquals(
      json("""{"authType": "openIdConnect", "oauth2": {"clientId": "cid", "state": "s"}}"""),
      redacted(payload),
    )
  }

  @Test
  fun withoutCredentialSecrets_credentialsInAnArray_dropTheirSecrets() {
    val payload =
      """[{"authType": "apiKey", "apiKey": "key"},
        {"authType": "http", "http": {"scheme": "bearer", "credentials": {"token": "t"},
          "additionalHeaders": {"X-Key": "k"}}},
        {"authType": "http", "http": {"scheme": "basic",
          "credentials": {"username": "user", "password": "pw"}}},
        {"authType": "serviceAccount", "serviceAccount": {"serviceAccountCredential": {
          "clientEmail": "sa@example.com", "privateKeyId": "pkid", "privateKey": "pk"}}}]"""

    assertEquals(
      json(
        """[{"authType": "apiKey"},
          {"authType": "http", "http": {"scheme": "bearer", "credentials": {}}},
          {"authType": "http", "http": {"scheme": "basic", "credentials": {"username": "user"}}},
          {"authType": "serviceAccount", "serviceAccount": {"serviceAccountCredential": {
            "clientEmail": "sa@example.com"}}}]"""
      ),
      redacted(payload),
    )
  }

  @Test
  fun withoutCredentialSecrets_encodedCredentials_dropEverySecret() {
    // Encoded by ADK Kotlin's own serializer, so a secret field not typed as a Secret fails here.
    val secret = Secret(SENTINEL)
    val credentials: List<AuthCredential> =
      listOf(
        AuthCredential.ApiKey(secret),
        AuthCredential.Http(
          scheme = "basic",
          credentials = AuthCredential.Http.Credentials(username = "user", password = secret),
          additionalHeaders = mapOf("X-Key" to secret),
        ),
        AuthCredential.Http(
          scheme = "bearer",
          credentials = AuthCredential.Http.Credentials(token = secret),
        ),
        AuthCredential.OAuth2(
          clientId = "cid",
          clientSecret = secret,
          authResponseUri = secret,
          authCode = secret,
          accessToken = secret,
          refreshToken = secret,
          idToken = secret,
          codeVerifier = secret,
        ),
        AuthCredential.ServiceAccount(
          serviceAccountCredential =
            AuthCredential.ServiceAccount.Credential(
              projectId = "project",
              privateKeyId = secret,
              privateKey = secret,
              clientEmail = "sa@example.com",
              clientId = "client",
              authUri = "https://accounts.example.com/auth",
              tokenUri = "https://accounts.example.com/token",
              authProviderX509CertUrl = "https://accounts.example.com/certs",
              clientX509CertUrl = "https://accounts.example.com/sa",
              universeDomain = "example.com",
            )
        ),
      )
    val encoded =
      JsonArray(credentials.map { adkJson.encodeToJsonElement(AuthCredentialSerializer, it) })

    val redacted = encoded.withoutCredentialSecrets()

    assertTrue(SENTINEL in encoded.toString())
    assertFalse(SENTINEL in redacted.toString())
  }

  @Test
  fun withoutCredentialSecrets_encodedSignInReply_dropsTheKey() {
    val request =
      SignInRequest(
        id = "req-1",
        authConfig =
          AuthConfig(
            AuthScheme.ApiKey(name = "X-Key", location = AuthScheme.ApiKey.Location.HEADER),
            credentialKey = CredentialKey("key"),
          ),
      )
    val reply =
      Event(author = "user", content = request.reply(AuthCredential.ApiKey(Secret(SENTINEL))))
    val encoded = adkJson.encodeToJsonElement(reply)

    val redacted = encoded.withoutCredentialSecrets()

    assertTrue(SENTINEL in encoded.toString())
    assertEquals(
      adkJson.encodeToJsonElement(reply.copy(content = request.reply(AuthCredential.ApiKey()))),
      redacted,
    )
  }

  @Test
  fun withoutCredentialSecrets_rawAndExchangedCredentials_dropTheirSecrets() {
    val payload =
      """{"authScheme": {"type": "openIdConnect", "openIdConnectUrl": "https://a/.well-known"},
        "rawAuthCredential": {"oauth2": {"clientId": "cid", "clientSecret": "cs"}},
        "exchangedAuthCredential": {"oauth2": {"accessToken": "at"}}, "credentialKey": "key"}"""

    assertEquals(
      json(
        """{"authScheme": {"type": "openIdConnect", "openIdConnectUrl": "https://a/.well-known"},
          "rawAuthCredential": {"oauth2": {"clientId": "cid"}},
          "exchangedAuthCredential": {"oauth2": {}}, "credentialKey": "key"}"""
      ),
      redacted(payload),
    )
  }

  @Test
  fun withoutCredentialSecrets_requestedAuthConfigs_dropTheirSecrets() {
    val payload =
      """{"requestedAuthConfigs": {"call-1": {"credentialKey": "key", "token": "t",
          "rawAuthCredential": {"oauth2": {"clientId": "cid", "clientSecret": "cs"}}}},
        "stateDelta": {"password": "pw"}}"""

    assertEquals(
      json(
        """{"requestedAuthConfigs": {"call-1": {"credentialKey": "key",
            "rawAuthCredential": {"oauth2": {"clientId": "cid"}}}},
          "stateDelta": {"password": "pw"}}"""
      ),
      redacted(payload),
    )
  }

  @Test
  fun withoutCredentialSecrets_secretKeyOutsideAuthParts_isKept() {
    val payload =
      """{"state": {"apiKey": "my-api-key", "token": "pagination-token", "password": "pw"}}"""

    assertEquals(json(payload), redacted(payload))
  }

  @Test
  fun withoutCredentialSecrets_snakeCaseAuthTypes_dropTheirSecrets() {
    val payload =
      """{"api_key_cred": {"auth_type": "api_key", "api_key": "secret-api-key"},
        "sa_cred": {"auth_type": "service_account",
          "service_account": {"private_key": "secret-private-key"}},
        "oidc_cred": {"auth_type": "open_id_connect",
          "oauth2": {"client_secret": "secret-client-secret"}}}"""

    assertEquals(
      json(
        """{"api_key_cred": {"auth_type": "api_key"},
          "sa_cred": {"auth_type": "service_account", "service_account": {}},
          "oidc_cred": {"auth_type": "open_id_connect", "oauth2": {}}}"""
      ),
      redacted(payload),
    )
  }

  @Test
  fun withoutCredentialSecrets_snakeCaseConfigKeys_dropTheirSecrets() {
    val payload =
      """[{"requested_auth_configs": {"call-1": {"raw_auth_credential": {"oauth2": {
          "client_id": "cid", "client_secret": "cs"}}}}},
        {"raw_auth_credential": {"http": {"credentials": {"username": "user", "password": "pw"},
          "additional_headers": {"X-Key": "k"}}},
        "exchanged_auth_credential": {"oauth2": {"access_token": "at", "id_token": "it",
          "auth_code": "ac", "auth_response_uri": "u", "code_verifier": "cv"},
          "service_account": {"service_account_credential": {"private_key_id": "pkid"}}},
        "credential_key": "key"}]"""

    assertEquals(
      json(
        """[{"requested_auth_configs": {"call-1": {"raw_auth_credential": {"oauth2": {
            "client_id": "cid"}}}}},
          {"raw_auth_credential": {"http": {"credentials": {"username": "user"}}},
          "exchanged_auth_credential": {"oauth2": {},
            "service_account": {"service_account_credential": {}}},
          "credential_key": "key"}]"""
      ),
      redacted(payload),
    )
  }

  @Test
  fun withoutCredentialSecrets_snakeCaseCredential_dropsItsSecrets() {
    val payload =
      """{"auth_type": "oauth2",
        "oauth2": {"client_id": "public-client-id", "client_secret": "secret",
          "access_token": "access", "refresh_token": "refresh"}}"""

    assertEquals(
      json("""{"auth_type": "oauth2", "oauth2": {"client_id": "public-client-id"}}"""),
      redacted(payload),
    )
  }

  @Test
  fun withoutCredentialSecrets_upperCaseAuthTypes_dropTheirSecrets() {
    val payload =
      """[{"authType": "API_KEY", "apiKey": "key"},
        {"authType": "HTTP", "http": {"credentials": {"username": "user", "password": "pw"}}},
        {"authType": "OAUTH2", "oauth2": {"clientId": "cid", "accessToken": "at"}},
        {"authType": "OPEN_ID_CONNECT", "oauth2": {"clientId": "cid", "refreshToken": "rt"}},
        {"authType": "SERVICE_ACCOUNT", "serviceAccount": {"serviceAccountCredential": {
          "clientEmail": "sa@example.com", "privateKey": "pk"}}}]"""

    assertEquals(
      json(
        """[{"authType": "API_KEY"},
          {"authType": "HTTP", "http": {"credentials": {"username": "user"}}},
          {"authType": "OAUTH2", "oauth2": {"clientId": "cid"}},
          {"authType": "OPEN_ID_CONNECT", "oauth2": {"clientId": "cid"}},
          {"authType": "SERVICE_ACCOUNT", "serviceAccount": {"serviceAccountCredential": {
            "clientEmail": "sa@example.com"}}}]"""
      ),
      redacted(payload),
    )
  }

  private companion object {
    const val SENTINEL = "secret-sentinel"

    fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    fun redacted(text: String): JsonElement = json(text).withoutCredentialSecrets()
  }
}
