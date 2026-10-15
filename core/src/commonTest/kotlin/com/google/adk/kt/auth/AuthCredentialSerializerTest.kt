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
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Test

/** Expected results are ADK Python's for the same input, unless a test's comment says otherwise. */
@OptIn(ExperimentalAuthApi::class, FrameworkInternalApi::class)
class AuthCredentialSerializerTest {

  @Test
  fun decode_apiKeyNotString_throws() {
    assertMalformed("""{"authType": "apiKey", "apiKey": 123}""")
  }

  @Test
  fun decode_apiKeyNull_readsNoKey() {
    assertEquals(AuthCredential.ApiKey(), decode("""{"authType": "apiKey", "apiKey": null}"""))
  }

  @Test
  fun decode_apiKeyWithSnakeCaseNames_readsThem() {
    assertEquals(
      AuthCredential.ApiKey(Secret("sk-123")),
      decode("""{"auth_type": "apiKey", "api_key": "sk-123"}"""),
    )
  }

  @Test
  fun decode_httpHeaderNotString_throws() {
    assertMalformed(
      """{"authType": "http", "http": {"scheme": "bearer", "credentials": {},
        "additionalHeaders": {"X-Count": 1}}}"""
    )
  }

  @Test
  fun decode_httpWithSnakeCaseNamesAndNulls_readsThem() {
    assertEquals(
      AuthCredential.Http.basic("user", "pass")
        .copy(additionalHeaders = mapOf("X-Key" to Secret("value"))),
      decode(
        """{"auth_type": "http", "http": {"scheme": "basic",
          "credentials": {"username": "user", "password": "pass", "token": null},
          "additional_headers": {"X-Key": "value"}}}"""
      ),
    )
  }

  @Test
  fun decode_httpWithoutCredentials_throws() {
    assertMalformed("""{"authType": "http", "http": {"scheme": "bearer"}}""")
  }

  @Test
  fun decode_httpWithoutPayload_throws() {
    // Python accepts this, but an Http credential needs a scheme and credentials.
    val error = assertFailsWith<SerializationException> { decode("""{"authType": "http"}""") }

    assertEquals("An auth credential has no http payload.", error.message)
  }

  @Test
  fun decode_httpWithoutScheme_throws() {
    assertMalformed("""{"authType": "http", "http": {"credentials": {"token": "t"}}}""")
  }

  @Test
  fun decode_malformedInput_throwsWithoutQuotingIt() {
    // The decoder's own message quotes the input, which here holds an API key.
    assertMalformed("""{"authType": "apiKey", "apiKey": "sk-123", "http": 1}""")
  }

  @Test
  fun decode_missingAuthType_throws() {
    assertMalformed("""{"apiKey": "sk-123"}""")
  }

  @Test
  fun decode_oauth2AccessTokenNotString_throws() {
    assertMalformed("""{"authType": "oauth2", "oauth2": {"accessToken": 1}}""")
  }

  @Test
  fun decode_oauth2ExpiresAtNotNumber_throws() {
    assertMalformed("""{"authType": "oauth2", "oauth2": {"expiresAt": "soon"}}""")
  }

  @Test
  fun decode_oauth2ExpiresInNotInteger_throws() {
    assertMalformed("""{"authType": "oauth2", "oauth2": {"expiresIn": 1.5}}""")
  }

  @Test
  fun decode_oauth2TokenEndpointAuthMethodNull_readsNull() {
    assertEquals(
      AuthCredential.OAuth2(tokenEndpointAuthMethod = null),
      decode("""{"authType": "oauth2", "oauth2": {"tokenEndpointAuthMethod": null}}"""),
    )
  }

  @Test
  fun decode_oauth2UnknownTokenEndpointAuthMethod_throws() {
    assertMalformed("""{"authType": "oauth2", "oauth2": {"tokenEndpointAuthMethod": "none"}}""")
  }

  @Test
  fun decode_oauth2WithSnakeCaseNames_readsEveryField() {
    assertEquals(
      OAUTH2,
      decode(
        """{"auth_type": "oauth2", "oauth2": {"client_id": "client", "client_secret": "secret",
          "auth_uri": "https://example.com/auth", "nonce": "n", "state": "s",
          "redirect_uri": "https://example.com/cb",
          "auth_response_uri": "https://example.com/cb?code=c", "auth_code": "c",
          "access_token": "at", "refresh_token": "rt", "id_token": "it",
          "expires_at": 1700000000, "expires_in": 3600, "audience": "aud",
          "prompt": "consent", "code_verifier": "cv", "code_challenge_method": "S256",
          "token_endpoint_auth_method": "client_secret_post"}}"""
      ),
    )
  }

  @Test
  fun decode_oauth2WithoutPayload_throws() {
    // Python accepts these, but treats a missing payload unlike an empty one.
    for (input in listOf("""{"authType": "oauth2"}""", """{"authType": "openIdConnect"}""")) {
      val error = assertFailsWith<SerializationException> { decode(input) }

      assertEquals("An auth credential has no oauth2 payload.", error.message)
    }
  }

  @Test
  fun decode_oauth2WithoutTokenEndpointAuthMethod_readsTheDefault() {
    assertEquals(AuthCredential.OAuth2(), decode("""{"authType": "oauth2", "oauth2": {}}"""))
  }

  @Test
  fun decode_oauth2_readsEveryField() {
    assertEquals(OAUTH2, decode(OAUTH2_JSON))
  }

  @Test
  fun decode_openIdConnect_readsItsVariant() {
    assertEquals(
      AuthCredential.OAuth2(
        variant = AuthCredential.OAuth2.Variant.OPEN_ID_CONNECT,
        clientId = "c",
      ),
      decode("""{"authType": "openIdConnect", "oauth2": {"clientId": "c"}}"""),
    )
  }

  @Test
  fun decode_serviceAccount_throwsUntilSupported() {
    val error =
      assertFailsWith<SerializationException> {
        decode("""{"authType": "serviceAccount", "serviceAccount": {}}""")
      }

    assertEquals("Reading this credential type from JSON is not supported yet.", error.message)
  }

  @Test
  fun decode_unknownAuthType_throws() {
    assertMalformed("""{"authType": "bogus"}""")
  }

  @Test
  fun decode_unmodeledFields_dropsThem() {
    // Python keeps these fields and writes them back; ADK Kotlin drops them, as elsewhere.
    assertEquals(
      AuthCredential.Http.bearer("t"),
      decode(
        """{"authType": "http", "apiKey": "k", "resourceRef": "r", "z": 3,
          "http": {"scheme": "bearer", "credentials": {"token": "t", "x": 1}, "y": 2}}"""
      ),
    )
  }

  @Test
  fun encode_apiKeyWithoutKey_writesAuthTypeOnly() {
    assertEquals(json("""{"authType": "apiKey"}"""), encode(AuthCredential.ApiKey()))
  }

  @Test
  fun encode_apiKey_writesAuthTypeAndKey() {
    assertEquals(
      json("""{"authType": "apiKey", "apiKey": "sk-123"}"""),
      encode(AuthCredential.ApiKey(Secret("sk-123"))),
    )
  }

  @Test
  fun encode_httpWithHeaders_writesHeaders() {
    val credential =
      AuthCredential.Http.basic("user", "pass")
        .copy(additionalHeaders = mapOf("X-Key" to Secret("value")))

    assertEquals(
      json(
        """{"authType": "http", "http": {"scheme": "basic",
          "credentials": {"username": "user", "password": "pass"},
          "additionalHeaders": {"X-Key": "value"}}}"""
      ),
      encode(credential),
    )
  }

  @Test
  fun encode_http_writesPayloadWithoutNulls() {
    assertEquals(
      json("""{"authType": "http", "http": {"scheme": "bearer", "credentials": {"token": "t"}}}"""),
      encode(AuthCredential.Http.bearer("t")),
    )
  }

  @Test
  fun encode_oauth2Defaults_writesTokenEndpointAuthMethod() {
    // Python writes the default method too.
    assertEquals(
      json(
        """{"authType": "oauth2", "oauth2": {"tokenEndpointAuthMethod": "client_secret_basic"}}"""
      ),
      encode(AuthCredential.OAuth2()),
    )
  }

  @Test
  fun encode_oauth2SubSecondTimes_dropsTheFraction() {
    val credential =
      AuthCredential.OAuth2(
        expiresAt = Instant.fromEpochMilliseconds(1_700_000_000_900),
        expiresIn = 3599.seconds + 900.milliseconds,
        tokenEndpointAuthMethod = null,
      )

    assertEquals(
      json("""{"authType": "oauth2", "oauth2": {"expiresAt": 1700000000, "expiresIn": 3599}}"""),
      encode(credential),
    )
  }

  @Test
  fun encode_oauth2WithoutTokenEndpointAuthMethod_omitsIt() {
    assertEquals(
      json("""{"authType": "oauth2", "oauth2": {}}"""),
      encode(AuthCredential.OAuth2(tokenEndpointAuthMethod = null)),
    )
  }

  @Test
  fun encode_oauth2_writesEveryField() {
    assertEquals(json(OAUTH2_JSON), encode(OAUTH2))
  }

  @Test
  fun encode_openIdConnect_writesItsAuthType() {
    val credential =
      AuthCredential.OAuth2(variant = AuthCredential.OAuth2.Variant.OPEN_ID_CONNECT, clientId = "c")

    assertEquals(
      json(
        """{"authType": "openIdConnect",
          "oauth2": {"clientId": "c", "tokenEndpointAuthMethod": "client_secret_basic"}}"""
      ),
      encode(credential),
    )
  }

  @Test
  fun encode_serviceAccount_throwsUntilSupported() {
    // Python writes this; ADK Kotlin throws until service account support lands.
    val error =
      assertFailsWith<SerializationException> {
        encode(AuthCredential.ServiceAccount(useDefaultCredential = true))
      }

    assertEquals("Writing this credential type as JSON is not supported yet.", error.message)
  }

  @Test
  fun tokenEndpointAuthMethod_everyValue_roundTripsUnderPythonsName() {
    for (method in AuthCredential.OAuth2.TokenEndpointAuthMethod.entries) {
      val credential = AuthCredential.OAuth2(tokenEndpointAuthMethod = method)
      val written = encode(credential)

      assertEquals(
        json(
          """{"authType": "oauth2",
            "oauth2": {"tokenEndpointAuthMethod": "${method.name.lowercase()}"}}"""
        ),
        written,
      )
      assertEquals(credential, decode(written.toString()))
    }
  }

  private companion object {
    val OAUTH2 =
      AuthCredential.OAuth2(
        clientId = "client",
        clientSecret = Secret("secret"),
        authUri = "https://example.com/auth",
        nonce = "n",
        state = "s",
        redirectUri = "https://example.com/cb",
        authResponseUri = Secret("https://example.com/cb?code=c"),
        authCode = Secret("c"),
        accessToken = Secret("at"),
        refreshToken = Secret("rt"),
        idToken = Secret("it"),
        expiresAt = Instant.fromEpochSeconds(1_700_000_000),
        expiresIn = 3600.seconds,
        audience = "aud",
        prompt = "consent",
        codeVerifier = Secret("cv"),
        codeChallengeMethod = "S256",
        tokenEndpointAuthMethod = AuthCredential.OAuth2.TokenEndpointAuthMethod.CLIENT_SECRET_POST,
      )

    const val OAUTH2_JSON =
      """{"authType": "oauth2", "oauth2": {"clientId": "client", "clientSecret": "secret",
        "authUri": "https://example.com/auth", "nonce": "n", "state": "s",
        "redirectUri": "https://example.com/cb", "authResponseUri": "https://example.com/cb?code=c",
        "authCode": "c", "accessToken": "at", "refreshToken": "rt", "idToken": "it",
        "expiresAt": 1700000000, "expiresIn": 3600, "audience": "aud", "prompt": "consent",
        "codeVerifier": "cv", "codeChallengeMethod": "S256",
        "tokenEndpointAuthMethod": "client_secret_post"}}"""

    fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    fun encode(credential: AuthCredential): JsonElement =
      adkJson.encodeToJsonElement(AuthCredentialSerializer, credential)

    fun decode(text: String): AuthCredential =
      adkJson.decodeFromString(AuthCredentialSerializer, text)

    fun assertMalformed(input: String) {
      val error = assertFailsWith<SerializationException> { decode(input) }

      assertEquals("Malformed auth credential.", error.message)
      assertNull(error.cause)
    }
  }
}
