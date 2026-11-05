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

import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.annotations.ExperimentalAuthApi
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import org.junit.Test

@OptIn(ExperimentalAuthApi::class, AdkJavaInteropApi::class)
class AuthConfigTest {

  @Test
  fun toBuilder_everyPropertySet_matchesCopy() {
    assertEquals(AUTH_CONFIG.copy(), AUTH_CONFIG.toBuilder().build())
  }

  @Test
  fun builder_requiredFieldsOnly_matchesConstructorDefaults() {
    assertEquals(AuthConfig(authScheme = SCHEME), AuthConfig.builder().authScheme(SCHEME).build())
  }

  @Test
  fun builder_withoutAuthScheme_throws() {
    assertFailsWith<IllegalStateException> {
      AuthConfig.builder().credentialKey(CREDENTIAL_KEY).build()
    }
  }

  @Test
  fun builder_withoutCredentialKey_derivesKey() {
    assertEquals(
      deriveCredentialKey(SCHEME, AUTH_CONFIG.rawAuthCredential).value,
      AUTH_CONFIG.toBuilder().credentialKey(null).build().credentialKey.value,
    )
  }

  @Test
  fun constructor_withoutCredentialKey_derivesKey() {
    val raw = AUTH_CONFIG.rawAuthCredential

    assertEquals(
      deriveCredentialKey(SCHEME, raw).value,
      AuthConfig(authScheme = SCHEME, rawAuthCredential = raw).credentialKey.value,
    )
  }

  @Test
  fun copy_otherRawCredential_keepsKey() {
    val config = AuthConfig(authScheme = SCHEME)

    assertEquals(
      config.credentialKey.value,
      config.copy(rawAuthCredential = AUTH_CONFIG.rawAuthCredential).credentialKey.value,
    )
  }

  @Test
  fun toString_authConfig_omitsSecrets() {
    val printed = AUTH_CONFIG.toString()

    assertEquals(
      "AuthConfig(authScheme=$SCHEME, rawAuthCredential=${AUTH_CONFIG.rawAuthCredential}, " +
        "exchangedAuthCredential=${AUTH_CONFIG.exchangedAuthCredential}, " +
        "credentialKey=<redacted>)",
      printed,
    )
    assertFalse(printed.contains(CLIENT_SECRET))
    assertFalse(printed.contains(ACCESS_TOKEN))
  }

  private companion object {
    val CREDENTIAL_KEY = CredentialKey("adk_openIdConnect_key")
    const val CLIENT_SECRET = "sentinel-client-secret"
    const val ACCESS_TOKEN = "sentinel-access-token"

    val SCHEME =
      AuthScheme.OpenIdConnect(
        authorizationEndpoint = "https://auth.example.com/authorize",
        tokenEndpoint = "https://auth.example.com/token",
        scopes = listOf("openid"),
      )

    val AUTH_CONFIG =
      AuthConfig(
        authScheme = SCHEME,
        rawAuthCredential =
          AuthCredential.OAuth2(
            variant = AuthCredential.OAuth2.Variant.OPEN_ID_CONNECT,
            clientId = "client-id",
            clientSecret = Secret(CLIENT_SECRET),
          ),
        exchangedAuthCredential =
          AuthCredential.OAuth2(
            variant = AuthCredential.OAuth2.Variant.OPEN_ID_CONNECT,
            accessToken = Secret(ACCESS_TOKEN),
          ),
        credentialKey = CREDENTIAL_KEY,
      )
  }
}
