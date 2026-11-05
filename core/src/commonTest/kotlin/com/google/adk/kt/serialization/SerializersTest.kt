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

package com.google.adk.kt.serialization

import com.google.adk.kt.annotations.ExperimentalAuthApi
import com.google.adk.kt.auth.AuthCredential
import com.google.adk.kt.auth.AuthCredentialSerializer
import com.google.adk.kt.auth.Secret
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant
import org.junit.Test

@OptIn(ExperimentalAuthApi::class)
class SerializersTest {

  @Test
  fun encodeToPlainMap_serializableValue_returnsItsJsonObject() {
    assertEquals(
      mapOf("authType" to "apiKey", "apiKey" to "k"),
      encodeToPlainMap(AuthCredentialSerializer, AuthCredential.ApiKey(Secret("k"))),
    )
  }

  @Test
  fun encodeToPlainMap_nestedObject_convertsItToPlainValuesThatDecodeBack() {
    val credential =
      AuthCredential.OAuth2(
        clientId = "cid",
        accessToken = Secret("at"),
        expiresAt = Instant.fromEpochSeconds(1_700_000_000),
      )

    val plain = encodeToPlainMap(AuthCredentialSerializer, credential)

    assertEquals(
      mapOf(
        "authType" to "oauth2",
        "oauth2" to
          mapOf(
            "clientId" to "cid",
            "accessToken" to "at",
            "expiresAt" to 1_700_000_000L,
            "tokenEndpointAuthMethod" to "client_secret_basic",
          ),
      ),
      plain,
    )
    assertEquals(credential, decodeFromPlainOrNull(AuthCredentialSerializer, plain))
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
}
