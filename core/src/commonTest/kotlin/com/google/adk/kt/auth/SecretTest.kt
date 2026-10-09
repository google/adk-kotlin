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
