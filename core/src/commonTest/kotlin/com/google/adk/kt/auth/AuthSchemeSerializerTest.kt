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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
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
    assertMalformed("""{"location": "header", "name": "key"}""")
  }

  @Test
  fun decode_nonObject_throws() {
    assertMalformed("""["apiKey"]""")
  }

  @Test
  fun decode_oauth2FlowWithoutTokenUrl_throws() {
    assertMalformed("""{"type": "oauth2", "flows": {"password": {}}}""")
  }

  @Test
  fun decode_oauth2FlowsWithoutScopes_readsThemEmpty() {
    assertEquals(
      AuthScheme.OAuth2(
        AuthScheme.OAuth2.Flows(
          implicit = AuthScheme.OAuth2.Flow.Implicit("https://a"),
          password = AuthScheme.OAuth2.Flow.Password("https://t"),
          clientCredentials = AuthScheme.OAuth2.Flow.ClientCredentials("https://t"),
          authorizationCode = AuthScheme.OAuth2.Flow.AuthorizationCode("https://a", "https://t"),
        )
      ),
      decode(
        """{"type": "oauth2", "flows": {
          "implicit": {"authorizationUrl": "https://a"},
          "password": {"tokenUrl": "https://t"},
          "clientCredentials": {"tokenUrl": "https://t"},
          "authorizationCode": {"authorizationUrl": "https://a", "tokenUrl": "https://t"}}}"""
      ),
    )
  }

  @Test
  fun decode_oauth2ScopesNull_throws() {
    assertMalformed(
      """{"type": "oauth2", "flows": {"implicit": {"authorizationUrl": "a", "scopes": null}}}"""
    )
  }

  @Test
  fun decode_oauth2WithoutAnyFlow_readsEmptyFlows() {
    assertEquals(
      AuthScheme.OAuth2(AuthScheme.OAuth2.Flows()),
      decode("""{"type": "oauth2", "flows": {}}"""),
    )
  }

  @Test
  fun decode_oauth2WithoutFlows_throws() {
    assertMalformed("""{"type": "oauth2"}""")
  }

  @Test
  fun decode_oauth2_readsEveryFlowAndField() {
    assertEquals(OAUTH2, decode(OAUTH2_JSON))
  }

  @Test
  fun decode_openIdConnectEndpointNotString_throws() {
    assertMalformed(
      """{"type": "openIdConnect", "authorizationEndpoint": "https://a", "tokenEndpoint": 1}"""
    )
  }

  @Test
  fun decode_openIdConnectUrlNotString_throws() {
    assertMalformed("""{"type": "openIdConnect", "openIdConnectUrl": 1}""")
  }

  @Test
  fun decode_openIdConnectUrlNull_readsTheEndpoints() {
    // A JSON format that writes nulls writes this for the endpoints kind.
    assertEquals(
      AuthScheme.OpenIdConnect(authorizationEndpoint = "https://a", tokenEndpoint = "https://t"),
      decode(
        """{"type": "openIdConnect", "openIdConnectUrl": null,
          "authorizationEndpoint": "https://a", "tokenEndpoint": "https://t"}"""
      ),
    )
  }

  @Test
  fun decode_openIdConnectUrl_readsTheUrl() {
    assertEquals(
      AuthScheme.OpenIdConnectUrl("https://o", "d"),
      decode("""{"type": "openIdConnect", "openIdConnectUrl": "https://o", "description": "d"}"""),
    )
  }

  @Test
  fun decode_openIdConnectWithBothForms_readsTheUrl() {
    assertEquals(
      AuthScheme.OpenIdConnectUrl("https://o"),
      decode(
        """{"type": "openIdConnect", "openIdConnectUrl": "https://o",
          "authorizationEndpoint": "https://a", "tokenEndpoint": "https://t"}"""
      ),
    )
  }

  @Test
  fun decode_openIdConnectWithOnlyAuthorizationEndpoint_throws() {
    assertMissingOpenIdConnectFields(
      """{"type": "openIdConnect", "authorizationEndpoint": "https://a"}"""
    )
  }

  @Test
  fun decode_openIdConnectWithoutUrl_throws() {
    assertMissingOpenIdConnectFields("""{"type": "openIdConnect"}""")
  }

  @Test
  fun decode_openIdConnect_readsEveryField() {
    assertEquals(OPEN_ID_CONNECT, decode(OPEN_ID_CONNECT_JSON))
  }

  @Test
  fun decode_snakeCaseNames_readsThem() {
    assertEquals(
      AuthScheme.Http(scheme = "bearer", bearerFormat = "JWT"),
      decode("""{"type": "http", "scheme": "bearer", "bearer_format": "JWT"}"""),
    )
    assertEquals(
      OAUTH2,
      decode(
        """{"type": "oauth2", "flows": {
          "implicit": {"authorization_url": "https://i/auth", "refresh_url": "https://i/refresh",
            "scopes": {"a": "A"}},
          "password": {"token_url": "https://p/token", "refresh_url": "https://p/refresh",
            "scopes": {"b": "B"}},
          "client_credentials": {"token_url": "https://c/token",
            "refresh_url": "https://c/refresh", "scopes": {"e": "E"}},
          "authorization_code": {"authorization_url": "https://ac/auth",
            "token_url": "https://ac/token", "refresh_url": "https://ac/refresh",
            "scopes": {"c": "C", "d": "D"}}},
          "issuer_url": "https://issuer", "description": "d"}"""
      ),
    )
    assertEquals(
      AuthScheme.OpenIdConnectUrl("https://o"),
      decode("""{"type": "openIdConnect", "open_id_connect_url": "https://o"}"""),
    )
    assertEquals(
      OPEN_ID_CONNECT,
      decode(
        """{"type": "openIdConnect", "authorization_endpoint": "https://a",
          "token_endpoint": "https://t", "userinfo_endpoint": "https://u",
          "revocation_endpoint": "https://r",
          "token_endpoint_auth_methods_supported": ["client_secret_basic"],
          "grant_types_supported": ["authorization_code"], "scopes": ["openid", "email"],
          "description": "d"}"""
      ),
    )
  }

  @Test
  fun decode_typeAdkDoesNotDefine_throws() {
    // The decoder's own message quotes the type.
    assertMalformed("""{"type": "x-auth", "tenant": "t"}""")
  }

  @Test
  fun decode_typeAfterTheFields_readsTheKind() {
    assertEquals(
      AuthScheme.ApiKey("key", AuthScheme.ApiKey.Location.HEADER),
      decode("""{"location": "header", "name": "key", "type": "apiKey"}"""),
    )
  }

  @Test
  fun decode_typeNotString_throws() {
    assertMalformed("""{"type": 1, "location": "header", "name": "key"}""")
  }

  @Test
  fun decode_typeNull_throws() {
    assertMalformed("""{"type": null, "location": "header", "name": "key"}""")
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
  fun encode_explicitNullsJson_roundTripsBothOpenIdConnectKinds() {
    // Such a Json writes the other kind's fields as null, which still read back as this kind.
    val nullWriting =
      Json(adkJson) {
        explicitNulls = true
        encodeDefaults = true
      }
    val otherKindFields =
      mapOf(
        AuthScheme.OpenIdConnectUrl("https://o", "d") to "authorizationEndpoint",
        OPEN_ID_CONNECT to "openIdConnectUrl",
      )

    for ((scheme, otherKindField) in otherKindFields) {
      val encoded = nullWriting.encodeToJsonElement(AuthSchemeSerializer, scheme)

      assertEquals(JsonNull, encoded.jsonObject[otherKindField], otherKindField)
      assertEquals(
        scheme,
        nullWriting.decodeFromJsonElement(AuthSchemeSerializer, encoded),
        otherKindField,
      )
    }
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
  fun encode_oauth2EmptyScopes_omitsThem() {
    val scheme =
      AuthScheme.OAuth2(
        AuthScheme.OAuth2.Flows(
          implicit = AuthScheme.OAuth2.Flow.Implicit("https://a"),
          password = AuthScheme.OAuth2.Flow.Password("https://t"),
          clientCredentials = AuthScheme.OAuth2.Flow.ClientCredentials("https://t"),
          authorizationCode = AuthScheme.OAuth2.Flow.AuthorizationCode("https://a", "https://t"),
        )
      )

    assertEquals(
      json(
        """{"type": "oauth2", "flows": {
          "implicit": {"authorizationUrl": "https://a"},
          "password": {"tokenUrl": "https://t"},
          "clientCredentials": {"tokenUrl": "https://t"},
          "authorizationCode": {"authorizationUrl": "https://a", "tokenUrl": "https://t"}}}"""
      ),
      encode(scheme),
    )
  }

  @Test
  fun encode_oauth2_writesEveryFlowAndField() {
    assertEquals(json(OAUTH2_JSON), encode(OAUTH2))
  }

  @Test
  fun encode_openIdConnectUrl_writesTheUrl() {
    assertEquals(
      json("""{"type": "openIdConnect", "openIdConnectUrl": "https://o", "description": "d"}"""),
      encode(AuthScheme.OpenIdConnectUrl("https://o", "d")),
    )
  }

  @Test
  fun encode_openIdConnect_writesEveryField() {
    assertEquals(json(OPEN_ID_CONNECT_JSON), encode(OPEN_ID_CONNECT))
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
    val OPEN_ID_CONNECT =
      AuthScheme.OpenIdConnect(
        authorizationEndpoint = "https://a",
        tokenEndpoint = "https://t",
        userinfoEndpoint = "https://u",
        revocationEndpoint = "https://r",
        tokenEndpointAuthMethodsSupported = listOf("client_secret_basic"),
        grantTypesSupported = listOf("authorization_code"),
        scopes = listOf("openid", "email"),
        description = "d",
      )

    const val OPEN_ID_CONNECT_JSON =
      """{"type": "openIdConnect", "authorizationEndpoint": "https://a",
        "tokenEndpoint": "https://t", "userinfoEndpoint": "https://u",
        "revocationEndpoint": "https://r",
        "tokenEndpointAuthMethodsSupported": ["client_secret_basic"],
        "grantTypesSupported": ["authorization_code"], "scopes": ["openid", "email"],
        "description": "d"}"""

    val OAUTH2 =
      AuthScheme.OAuth2(
        flows =
          AuthScheme.OAuth2.Flows(
            implicit =
              AuthScheme.OAuth2.Flow.Implicit(
                "https://i/auth",
                "https://i/refresh",
                mapOf("a" to "A"),
              ),
            password =
              AuthScheme.OAuth2.Flow.Password(
                "https://p/token",
                "https://p/refresh",
                mapOf("b" to "B"),
              ),
            clientCredentials =
              AuthScheme.OAuth2.Flow.ClientCredentials(
                "https://c/token",
                "https://c/refresh",
                mapOf("e" to "E"),
              ),
            authorizationCode =
              AuthScheme.OAuth2.Flow.AuthorizationCode(
                "https://ac/auth",
                "https://ac/token",
                "https://ac/refresh",
                mapOf("c" to "C", "d" to "D"),
              ),
          ),
        issuerUrl = "https://issuer",
        description = "d",
      )

    const val OAUTH2_JSON =
      """{"type": "oauth2", "flows": {
        "implicit": {"authorizationUrl": "https://i/auth", "refreshUrl": "https://i/refresh",
          "scopes": {"a": "A"}},
        "password": {"tokenUrl": "https://p/token", "refreshUrl": "https://p/refresh",
          "scopes": {"b": "B"}},
        "clientCredentials": {"tokenUrl": "https://c/token", "refreshUrl": "https://c/refresh",
          "scopes": {"e": "E"}},
        "authorizationCode": {"authorizationUrl": "https://ac/auth", "tokenUrl": "https://ac/token",
          "refreshUrl": "https://ac/refresh", "scopes": {"c": "C", "d": "D"}}},
        "issuerUrl": "https://issuer", "description": "d"}"""

    fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    fun encode(scheme: AuthScheme): JsonElement =
      adkJson.encodeToJsonElement(AuthSchemeSerializer, scheme)

    fun decode(text: String): AuthScheme = adkJson.decodeFromString(AuthSchemeSerializer, text)

    fun assertMalformed(input: String) {
      val error = assertFailsWith<SerializationException> { decode(input) }

      assertEquals("Malformed auth data.", error.message)
      assertNull(error.cause)
    }

    fun assertMissingOpenIdConnectFields(input: String) {
      val error = assertFailsWith<SerializationException> { decode(input) }

      assertEquals(
        "An openIdConnect scheme needs an openIdConnectUrl or both endpoints.",
        error.message,
      )
      assertNull(error.cause)
    }
  }
}
