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
import com.google.adk.kt.serialization.jsonElementToAny
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import org.junit.Test

@OptIn(ExperimentalAuthApi::class, FrameworkInternalApi::class, SecretAccess::class)
class AuthJsonTest {

  @Test
  fun withoutCredentialSecretsOrNull_apiKeyMap_dropsTheKey() {
    val map = mapOf("authType" to "apiKey", "apiKey" to "sk-1")

    assertEquals(mapOf("authType" to "apiKey"), map.withoutCredentialSecretsOrNull())
  }

  @Test
  fun withoutCredentialSecretsOrNull_snakeCaseMap_dropsTheKey() {
    val map = mapOf("auth_type" to "service_account", "private_key" to "pem")

    assertEquals(mapOf("auth_type" to "service_account"), map.withoutCredentialSecretsOrNull())
  }

  @Test
  fun withoutCredentialSecretsOrNull_kindAsPythonEnumName_dropsTheKey() {
    val map = mapOf("authType" to "API_KEY", "apiKey" to "sk-1")

    assertEquals(mapOf("authType" to "API_KEY"), map.withoutCredentialSecretsOrNull())
  }

  @Test
  fun withoutCredentialSecretsOrNull_fieldThatFailsToDecode_stillDropsTheSecrets() {
    // A fractional expiresIn and a numeric clientId fail the credential's decode.
    val map =
      mapOf(
        "authType" to "oauth2",
        "oauth2" to mapOf("accessToken" to "at", "clientId" to 123, "expiresIn" to 3600.5),
      )

    assertEquals(
      mapOf("authType" to "oauth2", "oauth2" to mapOf("clientId" to 123, "expiresIn" to 3600.5)),
      map.withoutCredentialSecretsOrNull(),
    )
  }

  @Test
  fun withoutCredentialSecretsOrNull_secretsAtAnyDepth_areDropped() {
    val map =
      mapOf(
        "authType" to "http",
        "http" to
          mapOf(
            "credentials" to mapOf("token" to "t"),
            "additionalHeaders" to mapOf("X-Key" to "h"),
          ),
        "history" to listOf(mapOf("refreshToken" to "rt", "at" to 1)),
      )

    assertEquals(
      mapOf(
        "authType" to "http",
        "http" to mapOf("credentials" to emptyMap<String, Any?>()),
        "history" to listOf(mapOf("at" to 1)),
      ),
      map.withoutCredentialSecretsOrNull(),
    )
  }

  @Test
  fun withoutCredentialSecretsOrNull_secretsInASetOrArray_areDropped() {
    val map =
      mapOf(
        "authType" to "oauth2",
        "set" to setOf(mapOf("refreshToken" to "rt")),
        "array" to arrayOf(mapOf("idToken" to "it")),
      )

    assertEquals(
      mapOf(
        "authType" to "oauth2",
        "set" to listOf(emptyMap<String, Any?>()),
        "array" to listOf(emptyMap<String, Any?>()),
      ),
      map.withoutCredentialSecretsOrNull(),
    )
  }

  @Test
  fun withoutCredentialSecretsOrNull_extraKeys_areKept() {
    val map = mapOf("authType" to "apiKey", "apiKey" to "sk-1", "label" to "Weather")

    assertEquals(
      mapOf("authType" to "apiKey", "label" to "Weather"),
      map.withoutCredentialSecretsOrNull(),
    )
  }

  @Test
  fun withoutCredentialSecretsOrNull_credentialKindWithoutSecrets_returnsAnEqualMap() {
    // Shaped like an API key scheme rather than a credential, so it has nothing to drop.
    val map = mapOf("authType" to "apiKey", "name" to "X-Key", "in" to "header")

    assertEquals(map, map.withoutCredentialSecretsOrNull())
  }

  @Test
  fun withoutCredentialSecretsOrNull_mapWithoutAuthType_returnsNull() {
    assertNull(mapOf("apiKey" to "sk-1").withoutCredentialSecretsOrNull())
  }

  @Test
  fun withoutCredentialSecretsOrNull_unknownKind_returnsNull() {
    assertNull(mapOf("authType" to "unknown", "apiKey" to "sk-1").withoutCredentialSecretsOrNull())
  }

  @Test
  fun withoutCredentialSecretsOrNull_kindThatIsNotAString_returnsNull() {
    assertNull(mapOf("authType" to 1, "apiKey" to "sk-1").withoutCredentialSecretsOrNull())
  }

  @Test
  fun withoutCredentialSecretsOrNull_everySecretFieldOfEveryKind_isDropped() {
    // Fails when a credential gains a secret field whose name is missing from the secret keys.
    val credentials =
      listOf(
        AuthCredential.ApiKey.of("sk-1"),
        AuthCredential.Http.basic("user", "pass")
          .copy(additionalHeaders = mapOf("X-Key" to Secret("h"))),
        AuthCredential.Http.bearer("t"),
        OAUTH2_WITH_EVERY_FIELD,
        SERVICE_ACCOUNT_WITH_KEY,
      )

    for (credential in credentials) {
      val stored = adkSecretRevealingJson.encodeToJsonElement(AuthCredentialSerializer, credential)
      val output = adkJson.encodeToJsonElement(AuthCredentialSerializer, credential)

      assertEquals(
        jsonElementToAny(output),
        (jsonElementToAny(stored) as Map<*, *>).withoutCredentialSecretsOrNull(),
      )
    }
  }

  @Test
  fun decodeFromPlainOrNull_plainMap_decodesIt() {
    assertEquals(
      AuthCredential.ApiKey(Secret("k")),
      decodeFromPlainOrNull(
        AuthCredentialSerializer,
        mapOf("authType" to "apiKey", "apiKey" to "k"),
      ),
    )
  }

  @Test
  fun decodeFromPlainOrNull_invalidValue_returnsNull() {
    val values =
      listOf(
        mapOf("authType" to "oauth2", "oauth2" to "not an object"),
        "not a map",
        null,
        // No JSON form, so the conversion itself fails.
        mapOf("authType" to "apiKey", "apiKey" to Any()),
      )

    for (value in values) assertNull(decodeFromPlainOrNull(AuthCredentialSerializer, value))
  }

  @Test
  fun decodeFromPlainOrNull_typedCredential_keepsItsSecrets() {
    val credential = AuthCredential.ApiKey(Secret("k"))

    assertEquals(credential, decodeFromPlainOrNull(AuthCredentialSerializer, credential))
  }

  private companion object {
    val OAUTH2_WITH_EVERY_FIELD =
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
      )

    val SERVICE_ACCOUNT_WITH_KEY =
      AuthCredential.ServiceAccount(
        serviceAccountCredential =
          AuthCredential.ServiceAccount.Credential(
            type = "service_account",
            projectId = "p",
            privateKeyId = Secret("kid"),
            privateKey = Secret("pem"),
            clientEmail = "sa@p.iam.gserviceaccount.com",
            clientId = "123",
            authUri = "https://accounts.google.com/o/oauth2/auth",
            tokenUri = "https://oauth2.googleapis.com/token",
            authProviderX509CertUrl = "https://www.googleapis.com/oauth2/v1/certs",
            clientX509CertUrl = "https://www.googleapis.com/robot/v1/metadata/x509/sa",
            universeDomain = "googleapis.com",
          ),
        scopes = listOf("s"),
      )
  }
}
