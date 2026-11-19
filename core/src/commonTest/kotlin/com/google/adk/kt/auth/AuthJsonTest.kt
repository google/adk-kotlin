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
import com.google.adk.kt.serialization.AnySerializer
import com.google.adk.kt.serialization.adkJson
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

@OptIn(ExperimentalAuthApi::class, FrameworkInternalApi::class, SecretAccess::class)
class AuthJsonTest {

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

  @Test
  fun encodeToPlainWithTypedCredentials_credentialsInAFreeFormValue_leavesThemTyped() {
    val value = valueWithEveryCredentialType()

    val plain = adkSecretRevealingJson.encodeToPlainWithTypedCredentials(AnySerializer, value)

    assertEquals(value, plain)
  }

  @Test
  fun encodeToPlainWithTypedCredentials_objectShapedLikeAPlaceholder_staysAMap() {
    val lookalike = mapOf("id" to 0L)
    val value = mapOf("credential" to AuthCredential.ApiKey(Secret(SECRET)), "other" to lookalike)

    val plain = adkSecretRevealingJson.encodeToPlainWithTypedCredentials(AnySerializer, value)

    assertEquals(value, plain)
  }

  @Test
  fun encodeToPlainWithTypedCredentials_result_outputLeavesSecretsOutAndStorageWritesThem() {
    val plain =
      adkSecretRevealingJson.encodeToPlainWithTypedCredentials(
        AnySerializer,
        valueWithEveryCredentialType(),
      )

    val output = adkJson.encodeToString(AnySerializer, checkNotNull(plain))
    val stored = adkSecretRevealingJson.encodeToString(AnySerializer, plain)

    assertFalse(SECRET in output)
    assertTrue(SECRET in stored)
  }

  /** A free-form value with an [AuthCredential], an [AuthConfig] and a [Secret], nested. */
  private fun valueWithEveryCredentialType(): Map<String, Any> =
    mapOf(
      "credential" to AuthCredential.ApiKey(Secret(SECRET)),
      "configs" to
        listOf(
          AuthConfig(
            AuthScheme.ApiKey(name = "X-Key", location = AuthScheme.ApiKey.Location.HEADER),
            rawAuthCredential = AuthCredential.ApiKey(Secret(SECRET)),
          )
        ),
      "nested" to mapOf("secret" to Secret(SECRET)),
      "text" to "plain",
    )

  private companion object {
    const val SECRET = "sentinel-secret-value"
  }
}
