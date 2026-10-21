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
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Test

@OptIn(ExperimentalAuthApi::class, FrameworkInternalApi::class)
class AuthCredentialSerializerTest {

  @Test
  fun decode_apiKeyNotString_throws() {
    assertMalformed("""{"authType": "API_KEY", "apiKey": 123}""")
  }

  @Test
  fun decode_apiKeyNull_readsNoKey() {
    assertEquals(AuthCredential.ApiKey(), decode("""{"authType": "API_KEY", "apiKey": null}"""))
  }

  @Test
  fun decode_apiKeyWithSnakeCaseNames_readsThem() {
    assertEquals(
      AuthCredential.ApiKey(Secret("sk-123")),
      decode("""{"auth_type": "API_KEY", "api_key": "sk-123"}"""),
    )
  }

  @Test
  fun decode_httpHeaderNotString_throws() {
    assertMalformed(
      """{"authType": "HTTP", "http": {"scheme": "bearer", "credentials": {},
        "additionalHeaders": {"X-Count": 1}}}"""
    )
  }

  @Test
  fun decode_httpWithSnakeCaseNames_readsThem() {
    assertEquals(
      AuthCredential.Http.basic("user", "pass")
        .copy(additionalHeaders = mapOf("X-Key" to Secret("value"))),
      decode(
        """{"auth_type": "HTTP", "http": {"scheme": "basic",
          "credentials": {"username": "user", "password": "pass"},
          "additional_headers": {"X-Key": "value"}}}"""
      ),
    )
  }

  @Test
  fun decode_httpWithoutCredentials_throws() {
    assertMalformed("""{"authType": "HTTP", "http": {"scheme": "bearer"}}""")
  }

  @Test
  fun decode_httpWithoutPayload_throws() {
    // An Http credential needs a scheme and credentials.
    val error = assertFailsWith<SerializationException> { decode("""{"authType": "HTTP"}""") }

    assertEquals("An auth credential has no http payload.", error.message)
  }

  @Test
  fun decode_httpWithoutScheme_throws() {
    assertMalformed("""{"authType": "HTTP", "http": {"credentials": {"token": "t"}}}""")
  }

  @Test
  fun decode_malformedInput_throwsWithoutQuotingIt() {
    // The decoder's own message quotes the input, which here holds an API key.
    assertMalformed("""{"authType": "API_KEY", "apiKey": "sk-123", "http": 1}""")
  }

  @Test
  fun decode_missingAuthType_throws() {
    assertMalformed("""{"apiKey": "sk-123"}""")
  }

  @Test
  fun decode_oauth2_throwsUntilSupported() {
    val error =
      assertFailsWith<SerializationException> { decode("""{"authType": "OAUTH2", "oauth2": {}}""") }

    assertEquals("Reading this credential type from JSON is not supported yet.", error.message)
  }

  @Test
  fun decode_unknownAuthType_throws() {
    assertMalformed("""{"authType": "bogus"}""")
  }

  @Test
  fun decode_unmodeledFields_dropsThem() {
    assertEquals(
      AuthCredential.Http.bearer("t"),
      decode(
        """{"authType": "HTTP", "apiKey": "k", "resourceRef": "r", "z": 3,
          "http": {"scheme": "bearer", "credentials": {"token": "t", "x": 1}, "y": 2}}"""
      ),
    )
  }

  @Test
  fun encode_apiKeyWithoutKey_writesAuthTypeOnly() {
    assertEquals(json("""{"authType": "API_KEY"}"""), encode(AuthCredential.ApiKey()))
  }

  @Test
  fun encode_apiKey_writesAuthTypeAndKey() {
    assertEquals(
      json("""{"authType": "API_KEY", "apiKey": "sk-123"}"""),
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
        """{"authType": "HTTP", "http": {"scheme": "basic",
          "credentials": {"username": "user", "password": "pass"},
          "additionalHeaders": {"X-Key": "value"}}}"""
      ),
      encode(credential),
    )
  }

  @Test
  fun encode_http_writesPayloadWithoutNulls() {
    assertEquals(
      json("""{"authType": "HTTP", "http": {"scheme": "bearer", "credentials": {"token": "t"}}}"""),
      encode(AuthCredential.Http.bearer("t")),
    )
  }

  @Test
  fun encode_oauth2_throwsUntilSupported() {
    val error = assertFailsWith<SerializationException> { encode(AuthCredential.OAuth2()) }

    assertEquals("Writing this credential type as JSON is not supported yet.", error.message)
  }

  private companion object {
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
