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

@file:OptIn(ExperimentalWorkflowApi::class)
// typeOf<T>() is a compiler intrinsic and does not require kotlin-reflect.
@file:Suppress("KotlinReflectNeeded")

package com.google.adk.kt.workflow

import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import kotlin.jvm.JvmInline
import kotlin.reflect.KType
import kotlin.reflect.typeOf
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.serialization.Serializable

@Serializable private data class CodecQuery(val project: String, val maxResults: Int = 5)

@Serializable @JvmInline private value class CodecOptionalId(val id: String?)

private data class CodecPlain(val id: String)

private inline fun <reified T> codec() = ValueCodec<T>(typeOf<T>())

/** A type whose classifier is a type parameter, as in a generic node or helper. */
private fun <T> typeParameterType(): KType = checkNotNull(typeOf<List<T>>().arguments.single().type)

class ValueCodecTest {

  @Test
  fun coerce_null_acceptsNullableAndUnitAndRejectsOtherTypes() {
    // Act
    val nullable = codec<String?>().coerce(null, "input")
    val unit = codec<Unit>().coerce(null, "input")
    val error =
      assertFailsWith<NodeInputValidationException> { codec<String>().coerce(null, "input") }

    // Assert
    assertNull(nullable)
    assertEquals(Unit, unit)
    assertContains(error.message.orEmpty(), "input does not accept null (expected kotlin.String)")
  }

  @Test
  fun coerce_starProjectionAndTypeParameter_passValuesThrough() {
    // Arrange
    val mixed = listOf(1, "a")

    // Act
    val starList = codec<List<*>>().coerce(mixed, "input")
    val typeParameterValue = ValueCodec<Any?>(typeParameterType<Int>()).coerce("x", "input")

    // Assert
    assertSame(mixed, starList)
    assertEquals("x", typeParameterValue)
  }

  @Test
  fun schema_isInferredFromTheType() {
    // Act
    val schema = codec<List<Int>>().schema

    // Assert
    assertEquals(Schema(type = Type.ARRAY, items = Schema(type = Type.INTEGER)), schema)
  }

  @Test
  fun encode_typedValues_giveTheirJsonForm() {
    // Act
    val int = codec<Int>().encode(5, "output")
    val query = codec<CodecQuery>().encode(CodecQuery("ADK"), "output")
    val bytes = codec<ByteArray>().encode(byteArrayOf(1, 2, 3), "output")
    val anyMap = codec<Any?>().encode(mapOf("a" to 1), "output")
    val nullValue = codec<String?>().encode(null, "output")

    // Assert
    assertEquals(5L, int)
    assertEquals(mapOf("project" to "ADK"), query)
    assertEquals("AQID", bytes)
    assertEquals(mapOf("a" to 1L), anyMap)
    assertNull(nullValue)
  }

  @Test
  fun encode_valuesWithoutAJsonForm_throwNamingTheValueNotQuotingIt() {
    // Act
    val noSerializer =
      assertFailsWith<IllegalArgumentException> {
        codec<CodecPlain>().encode(CodecPlain("secret-id"), "output of node 'n'")
      }
    val classOnAnyPort =
      assertFailsWith<IllegalArgumentException> {
        codec<Any?>().encode(CodecQuery("secret-project"), "output of node 'n'")
      }
    val encodesToNull =
      assertFailsWith<IllegalArgumentException> {
        codec<CodecOptionalId>().encode(CodecOptionalId(null), "output of node 'n'")
      }

    // Assert
    assertContains(noSerializer.message.orEmpty(), "output of node 'n' has type")
    assertContains(noSerializer.message.orEmpty(), "which has no serializer")
    assertContains(classOnAnyPort.message.orEmpty(), "output of node 'n' has no JSON form")
    assertContains(encodesToNull.message.orEmpty(), "output of node 'n' encodes to null")
    for (message in
      listOf(noSerializer, classOnAnyPort, encodesToNull).map { it.message.orEmpty() }) {
      assertFalse("secret" in message)
    }
  }
}
