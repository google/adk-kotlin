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
import com.google.adk.kt.serialization.adkJson
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlinx.serialization.Serializable
import org.junit.Test

@OptIn(ExperimentalAuthApi::class, FrameworkInternalApi::class)
class SecretTest {

  @Test
  fun toString_anyValue_printsPlaceholder() {
    assertEquals("<redacted>", Secret(SENTINEL).toString())
  }

  @Test
  fun toString_stringTemplate_hidesValue() {
    val secret = Secret(SENTINEL)

    assertEquals("token=<redacted>", "token=$secret")
  }

  @Test
  fun toString_insideDataClass_hidesValue() {
    assertEquals("Holder(token=<redacted>)", Holder(Secret(SENTINEL)).toString())
  }

  @Test
  fun toString_nullableInDataClass_hidesValue() {
    assertEquals("NullableHolder(token=<redacted>)", NullableHolder(Secret(SENTINEL)).toString())
    assertEquals("NullableHolder(token=null)", NullableHolder(null).toString())
  }

  @Test
  fun toString_insideList_hidesValue() {
    assertEquals("[<redacted>]", listOf(Secret(SENTINEL)).toString())
  }

  @OptIn(SecretAccess::class)
  @Test
  fun reveal_withOptIn_returnsValue() {
    assertEquals(SENTINEL, Secret(SENTINEL).reveal())
  }

  @Test
  fun equals_sameValue_isEqual() {
    assertEquals(Secret(SENTINEL), Secret(SENTINEL))
    assertEquals(Secret(SENTINEL).hashCode(), Secret(SENTINEL).hashCode())
  }

  @Test
  fun equals_differentValue_isNotEqual() {
    assertNotEquals(Secret(SENTINEL), Secret("other"))
  }

  @Test
  fun secretSerializer_encode_writesValue() {
    assertEquals("\"$SENTINEL\"", adkJson.encodeToString(SecretSerializer, Secret(SENTINEL)))
  }

  @Test
  fun secretSerializer_decodeJsonString_readsValue() {
    assertEquals(Secret(SENTINEL), adkJson.decodeFromString(SecretSerializer, "\"$SENTINEL\""))
  }

  @Test
  fun secretSerializer_onProperty_writesValueAndRoundTrips() {
    val holder = Holder(Secret(SENTINEL))

    val json = adkJson.encodeToString(Holder.serializer(), holder)

    assertEquals("{\"token\":\"$SENTINEL\"}", json)
    assertEquals(holder, adkJson.decodeFromString(Holder.serializer(), json))
  }

  @Test
  fun secretSerializer_onNullableProperty_roundTripsAndOmitsNull() {
    val holder = NullableHolder(Secret(SENTINEL))

    val json = adkJson.encodeToString(NullableHolder.serializer(), holder)

    assertEquals("{\"token\":\"$SENTINEL\"}", json)
    assertEquals(holder, adkJson.decodeFromString(NullableHolder.serializer(), json))
    assertEquals("{}", adkJson.encodeToString(NullableHolder.serializer(), NullableHolder(null)))
    assertEquals(NullableHolder(null), adkJson.decodeFromString(NullableHolder.serializer(), "{}"))
  }

  @Test
  fun redactedUrl_atSignInPassword_redactsAllUserInfo() {
    assertEquals(
      "https://<redacted>@host.example/path",
      "https://user:sentinel@password@host.example/path".redactedUrl(),
    )
  }

  @Test
  fun redactedUrl_atSignInPath_keepsPath() {
    assertEquals("https://host.example/users/@me", "https://host.example/users/@me".redactedUrl())
  }

  @Test
  fun redactedUrl_emptyQuery_keepsDelimiter() {
    assertEquals("https://host.example/path?", "https://host.example/path?".redactedUrl())
  }

  @Test
  fun redactedUrl_equalsInValue_redactsWholeValue() {
    assertEquals(
      "https://host.example/path?state=<redacted>",
      "https://host.example/path?state=sentinel==".redactedUrl(),
    )
  }

  @Test
  fun redactedUrl_fragmentParameters_redactsValues() {
    assertEquals(
      "https://app.example/callback#access_token=<redacted>&state=<redacted>",
      "https://app.example/callback#access_token=sentinel-token&state=sentinel-state".redactedUrl(),
    )
  }

  @Test
  fun redactedUrl_lineBreakInFragment_redactsValue() {
    assertEquals(
      "https://host.example/path#state=<redacted>",
      "https://host.example/path#state=sentinel\nstate".redactedUrl(),
    )
  }

  @Test
  fun redactedUrl_noQuery_returnsUrlUnchanged() {
    assertEquals("https://app.example/callback", "https://app.example/callback".redactedUrl())
  }

  @Test
  fun redactedUrl_null_returnsNull() {
    assertNull(null.redactedUrl())
  }

  @Test
  fun redactedUrl_parameterWithoutEquals_redactsWholeParameter() {
    assertEquals(
      "https://host.example/path?<redacted>&name=<redacted>",
      "https://host.example/path?sentinel-value&name=sentinel-name".redactedUrl(),
    )
  }

  @Test
  fun redactedUrl_queryAndFragment_redactsBoth() {
    assertEquals(
      "https://host.example/path?code=<redacted>#state=<redacted>",
      "https://host.example/path?code=sentinel-code#state=sentinel-state".redactedUrl(),
    )
  }

  @Test
  fun redactedUrl_queryParameters_keepsNamesAndRedactsValues() {
    assertEquals(
      "https://auth.example/authorize?client_id=<redacted>&state=<redacted>&nonce=<redacted>",
      "https://auth.example/authorize?client_id=client&state=sentinel-state&nonce=sentinel-nonce"
        .redactedUrl(),
    )
  }

  @Test
  fun redactedUrl_questionMarkInFragment_staysInFragment() {
    assertEquals(
      "https://host.example/path#state=<redacted>",
      "https://host.example/path#state=sentinel?state".redactedUrl(),
    )
  }

  @Test
  fun redactedUrl_relativeUrl_redactsQueryValues() {
    assertEquals("/callback?code=<redacted>", "/callback?code=sentinel-code".redactedUrl())
  }

  @Test
  fun redactedUrl_rfc3986AppendixBExample_redactsFragment() {
    assertEquals(
      "http://www.ics.uci.edu/pub/ietf/uri/#<redacted>",
      "http://www.ics.uci.edu/pub/ietf/uri/#Related".redactedUrl(),
    )
  }

  @Test
  fun redactedUrl_rfc3986ComponentExample_redactsQueryAndFragmentValues() {
    assertEquals(
      "foo://example.com:8042/over/there?name=<redacted>#<redacted>",
      "foo://example.com:8042/over/there?name=ferret#nose".redactedUrl(),
    )
  }

  @Test
  fun redactedUrl_rfc3986IpLiteralExample_keepsAuthorityAndPath() {
    assertEquals(
      "ldap://[2001:db8::7]/c=GB?<redacted>",
      "ldap://[2001:db8::7]/c=GB?objectClass?one".redactedUrl(),
    )
  }

  @Test
  fun redactedUrl_rfc3986MailtoExample_returnsUrlUnchanged() {
    assertEquals("mailto:John.Doe@example.com", "mailto:John.Doe@example.com".redactedUrl())
  }

  @Test
  fun redactedUrl_rfc3986UrnExample_returnsUrlUnchanged() {
    assertEquals(
      "urn:oasis:names:specification:docbook:dtd:xml:4.1.2",
      "urn:oasis:names:specification:docbook:dtd:xml:4.1.2".redactedUrl(),
    )
  }

  @Test
  fun redactedUrl_userInfo_redactsUserInfo() {
    assertEquals(
      "https://<redacted>@host.example/path",
      "https://user:sentinel-password@host.example/path".redactedUrl(),
    )
  }

  @Test
  fun redacted_null_returnsNull() {
    assertNull(null.redacted())
  }

  @Test
  fun redacted_value_returnsPlaceholder() {
    assertEquals("<redacted>", "sentinel-state".redacted())
  }

  @Serializable
  private data class Holder(@Serializable(with = SecretSerializer::class) val token: Secret)

  @Serializable
  private data class NullableHolder(
    @Serializable(with = SecretSerializer::class) val token: Secret? = null
  )

  private companion object {
    const val SENTINEL = "sentinel-secret-value"
  }
}
