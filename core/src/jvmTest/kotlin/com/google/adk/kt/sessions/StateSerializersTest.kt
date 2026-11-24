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

package com.google.adk.kt.sessions

import com.google.adk.kt.annotations.ExperimentalAuthApi
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.annotations.SecretAccess
import com.google.adk.kt.auth.AuthCredential
import com.google.adk.kt.auth.Secret
import com.google.adk.kt.auth.adkSecretRevealingJson
import com.google.adk.kt.serialization.adkJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@OptIn(ExperimentalAuthApi::class, FrameworkInternalApi::class, SecretAccess::class)
class StateSerializersTest {

  @Test
  fun decodeStateValueOrNull_credentialKey_typesTheValue() {
    for (key in listOf(DERIVED_KEY, "user:$DERIVED_KEY", OAUTH_CREDENTIAL_KEY)) {
      assertEquals(
        AuthCredential.ApiKey(Secret("k")),
        decodeStateValueOrNull(key, json("""{"authType": "apiKey", "apiKey": "k"}""")),
      )
    }
    assertEquals(Secret("token"), decodeStateValueOrNull(DERIVED_KEY, json("\"token\"")))
  }

  @Test
  fun decodeStateValueOrNull_malformedCredential_isDropped() {
    assertNull(decodeStateValueOrNull(DERIVED_KEY, json("""{"authType": "unknown"}""")))
  }

  @Test
  fun decodeStateValueOrNull_otherKey_keepsThePlainValue() {
    val value = json("""{"authType": "apiKey", "apiKey": "k"}""")

    for (key in listOf("my_key", "adk_oauth_state:wf_auth:x", "temp:$DERIVED_KEY")) {
      assertEquals(
        mapOf("authType" to "apiKey", "apiKey" to "k"),
        decodeStateValueOrNull(key, value),
      )
    }
  }

  @Test
  fun decodeStateValueOrNull_removalUnderACredentialKey_staysARemoval() {
    val removed =
      adkSecretRevealingJson.encodeToJsonElement(StateMapSerializer, mapOf("k" to State.REMOVED))

    assertEquals(
      State.REMOVED,
      decodeStateValueOrNull(DERIVED_KEY, (removed as JsonObject).getValue("k")),
    )
  }

  @Test
  fun stateMapSerializer_bareTokenUnderACredentialKey_outputWritesItRedacted() {
    val state = mapOf<String, Any>(DERIVED_KEY to "sk-1")

    assertEquals(
      json("""{"$DERIVED_KEY": "<redacted>"}"""),
      adkJson.encodeToJsonElement(StateMapSerializer, state),
    )
    assertEquals(
      json("""{"$DERIVED_KEY": "sk-1"}"""),
      adkSecretRevealingJson.encodeToJsonElement(StateMapSerializer, state),
    )
  }

  @Test
  fun stateMapSerializer_credentialUnderACustomKey_outputLeavesItsSecretOut() {
    // As a reload hands back a credential saved under a key of the app's own: plain values.
    val state = mapOf<String, Any>("my_key" to mapOf("authType" to "apiKey", "apiKey" to "sk-1"))

    assertEquals(
      json("""{"my_key": {"authType": "apiKey"}}"""),
      adkJson.encodeToJsonElement(StateMapSerializer, state),
    )
    assertEquals(
      json("""{"my_key": {"authType": "apiKey", "apiKey": "sk-1"}}"""),
      adkSecretRevealingJson.encodeToJsonElement(StateMapSerializer, state),
    )
  }

  @Test
  fun nullableStateMapSerializer_nullValue_readsAsNull() {
    val state =
      adkJson.decodeFromString(
        NullableStateMapSerializer,
        """{"gone": null, "$DERIVED_KEY": "t"}""",
      )

    assertEquals(mapOf("gone" to null, DERIVED_KEY to Secret("t")), state)
  }

  @Test
  fun decodeStateValueOrNull_keyWithTwoScopes_isNotACredentialKey() {
    val value = json("\"t\"")

    assertEquals("t", decodeStateValueOrNull("app:user:$DERIVED_KEY", value))
  }

  @Test
  fun mutableStateMapSerializer_jsonNull_readsAsARemoval() {
    // As a delta another ADK language wrote can hold.
    val delta =
      adkJson.decodeFromString(MutableStateMapSerializer, """{"$OAUTH_CREDENTIAL_KEY": null}""")

    assertEquals(mapOf<String, Any>(OAUTH_CREDENTIAL_KEY to State.REMOVED), delta.toMap())
  }

  @Test
  fun stateMapSerializer_credentialReadFromJson_outputLeavesItsSecretOut() {
    val state = mapOf<String, Any>(DERIVED_KEY to AuthCredential.ApiKey(Secret("sk-1")))
    val stored = adkSecretRevealingJson.encodeToString(StateMapSerializer, state)

    val read = adkJson.decodeFromString(StateMapSerializer, stored)

    assertEquals(state, read)
    assertFalse(adkJson.encodeToString(StateMapSerializer, read).contains("sk-1"))
  }

  private companion object {
    const val DERIVED_KEY = "adk_http_0123456789abcdef_"

    const val OAUTH_CREDENTIAL_KEY = "adk_oauth_credential:wf_auth:wf@1/node@1"

    fun json(text: String): JsonElement = Json.parseToJsonElement(text)
  }
}
