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
import kotlin.reflect.typeOf
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer
import org.junit.Test

@OptIn(ExperimentalAuthApi::class, FrameworkInternalApi::class)
class AuthSchemeSerializerTest {

  @Test
  fun decode_apiKeyLocationEntryName_throws() {
    assertMalformed("""{"type": "apiKey", "location": "HEADER", "name": "key"}""")
  }

  @Test
  fun decode_apiKeyUnknownLocation_throws() {
    assertMalformed("""{"type": "apiKey", "location": "BODY", "name": "key"}""")
  }

  @Test
  fun decode_apiKeyWithoutLocation_throws() {
    assertMalformed("""{"type": "apiKey", "name": "key"}""")
  }

  @Test
  fun decode_apiKey_readsEveryField() {
    assertEquals(
      AuthScheme.ApiKey("key", AuthScheme.ApiKey.Location.QUERY, "d"),
      decode("""{"type": "apiKey", "location": "query", "name": "key", "description": "d"}"""),
    )
  }

  @Test
  fun decode_httpWithoutScheme_throws() {
    assertMalformed("""{"type": "http", "bearerFormat": "JWT"}""")
  }

  @Test
  fun decode_http_readsEveryField() {
    assertEquals(
      AuthScheme.Http(scheme = "bearer", bearerFormat = "JWT", description = "d"),
      decode("""{"type": "http", "scheme": "bearer", "bearerFormat": "JWT", "description": "d"}"""),
    )
  }

  @Test
  fun decode_malformedInput_throwsWithoutQuotingIt() {
    // The decoder's own message quotes the input.
    assertMalformed("""{"type": "http", "scheme": 1, "description": "sk-123"}""")
  }

  @Test
  fun decode_missingType_throws() {
    val error =
      assertFailsWith<SerializationException> {
        decode("""{"location": "header", "name": "key"}""")
      }

    assertEquals("An auth scheme has no string type.", error.message)
  }

  @Test
  fun decode_nonObject_throws() {
    val error = assertFailsWith<SerializationException> { decode("""["apiKey"]""") }

    assertEquals("An auth scheme is not a JSON object.", error.message)
  }

  @Test
  fun decode_oauth2_throwsUntilSupported() {
    val error =
      assertFailsWith<SerializationException> { decode("""{"type": "oauth2", "flows": {}}""") }

    assertEquals("Reading this auth scheme type from JSON is not supported yet.", error.message)
  }

  @Test
  fun decode_openIdConnect_throwsUntilSupported() {
    val error =
      assertFailsWith<SerializationException> {
        decode("""{"type": "openIdConnect", "openIdConnectUrl": "https://example.com"}""")
      }

    assertEquals("Reading this auth scheme type from JSON is not supported yet.", error.message)
  }

  @Test
  fun decode_typeAdkDoesNotDefine_throws() {
    val error =
      assertFailsWith<SerializationException> { decode("""{"type": "x-auth", "tenant": "t"}""") }

    assertEquals("An auth scheme has an unsupported type.", error.message)
  }

  @Test
  fun decode_typeNotString_throws() {
    val error =
      assertFailsWith<SerializationException> {
        decode("""{"type": 1, "location": "header", "name": "key"}""")
      }

    assertEquals("An auth scheme has no string type.", error.message)
  }

  @Test
  fun decode_typeNull_throws() {
    val error =
      assertFailsWith<SerializationException> {
        decode("""{"type": null, "location": "header", "name": "key"}""")
      }

    assertEquals("An auth scheme has no string type.", error.message)
  }

  @Test
  fun decode_unmodeledFields_dropsThem() {
    assertEquals(
      AuthScheme.Http.basic(),
      decode("""{"type": "http", "scheme": "basic", "location": "header", "x-extension": "v"}"""),
    )
  }

  @Test
  fun decode_unparsableInput_throwsWithoutQuotingIt() {
    // The parser's own message quotes the input.
    assertMalformed("""{"type": "http", "description": "sk-123", """)
  }

  @Test
  fun encode_apiKey_writesTypeAndEveryField() {
    assertEquals(
      json("""{"type": "apiKey", "location": "header", "name": "X-Key", "description": "d"}"""),
      encode(AuthScheme.ApiKey("X-Key", AuthScheme.ApiKey.Location.HEADER, "d")),
    )
  }

  @Test
  fun encode_httpWithoutOptionalFields_omitsThem() {
    assertEquals(json("""{"type": "http", "scheme": "basic"}"""), encode(AuthScheme.Http.basic()))
  }

  @Test
  fun encode_http_writesTypeAndEveryField() {
    assertEquals(
      json("""{"type": "http", "scheme": "bearer", "bearerFormat": "JWT", "description": "d"}"""),
      encode(AuthScheme.Http(scheme = "bearer", bearerFormat = "JWT", description = "d")),
    )
  }

  @Test
  fun encode_oauth2_throwsUntilSupported() {
    val error =
      assertFailsWith<SerializationException> {
        encode(AuthScheme.OAuth2.clientCredentials("https://example.com/token", emptyMap()))
      }

    assertEquals("Writing this auth scheme type as JSON is not supported yet.", error.message)
  }

  @Test
  fun location_everyValue_roundTripsAsItsOpenApiValue() {
    val values =
      mapOf(
        AuthScheme.ApiKey.Location.QUERY to "query",
        AuthScheme.ApiKey.Location.HEADER to "header",
        AuthScheme.ApiKey.Location.COOKIE to "cookie",
      )

    assertEquals(AuthScheme.ApiKey.Location.entries.toSet(), values.keys)
    for ((location, value) in values) {
      val scheme = AuthScheme.ApiKey("key", location)
      val written = encode(scheme)

      assertEquals(json("""{"type": "apiKey", "location": "$value", "name": "key"}"""), written)
      assertEquals(scheme, decode(written.toString()))
    }
  }

  @Test
  fun location_serializerFoundAtRuntime_writesItsOpenApiValue() {
    val serializer = adkJson.serializersModule.serializer(typeOf<AuthScheme.ApiKey.Location>())

    assertEquals(
      "\"header\"",
      adkJson.encodeToString(serializer, AuthScheme.ApiKey.Location.HEADER),
    )
  }

  private companion object {
    fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    fun encode(scheme: AuthScheme): JsonElement =
      adkJson.encodeToJsonElement(AuthSchemeSerializer, scheme)

    fun decode(text: String): AuthScheme = adkJson.decodeFromString(AuthSchemeSerializer, text)

    fun assertMalformed(input: String) {
      val error = assertFailsWith<SerializationException> { decode(input) }

      assertEquals("Malformed auth scheme.", error.message)
      assertNull(error.cause)
    }
  }
}
