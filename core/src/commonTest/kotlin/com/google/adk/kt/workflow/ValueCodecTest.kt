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
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Role
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
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.serialization.Serializable

@Serializable private data class CodecQuery(val project: String, val maxResults: Int = 5)

@Serializable @JvmInline private value class CodecOptionalId(val id: String?)

private data class CodecPlain(val id: String)

@Serializable
private enum class CodecPriority {
  LOW,
  HIGH,
}

@Serializable private data class CodecBox<T>(val item: T)

private inline fun <reified T> codec() = ValueCodec<T>(typeOf<T>())

/** A type whose classifier is a type parameter, as in a generic node or helper. */
private fun <T> typeParameterType(): KType = checkNotNull(typeOf<List<T>>().arguments.single().type)

class ValueCodecTest {

  private val secret = "SENTINEL-SECRET-4242"

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
  fun coerce_instance_returnsItAndCoercesCollectionAndMapElements() {
    // Arrange
    val query = CodecQuery("ADK")
    val box = CodecBox(secret)

    // Act
    val sameQuery = codec<CodecQuery>().coerce(query, "input")
    val ints = codec<List<Int>>().coerce(listOf(1L, 2L), "input")
    val intSet = codec<Set<Int>>().coerce(setOf(1L, 2L), "input")
    val intMap = codec<Map<String, Int>>().coerce(mapOf("a" to 3L), "input")
    val sameBox = codec<CodecBox<Int>>().coerce(box, "input")

    // Assert
    assertSame(query, sameQuery)
    assertEquals(3, ints.sum())
    assertEquals(setOf(1, 2), intSet)
    assertIs<Int>(intMap.getValue("a"))
    assertSame<Any>(box, sameBox)
  }

  @Test
  fun coerce_numbers_convertsThroughTheSerializerWithRangeChecks() {
    // Act
    val int = codec<Int>().coerce(5L, "input")
    val long = codec<Long>().coerce(5, "input")
    val double = codec<Double>().coerce(1, "input")
    val quoted = codec<Int>().coerce("42", "input")
    val outOfRange =
      assertFailsWith<NodeInputValidationException> { codec<Int>().coerce(3_000_000_000L, "input") }
    val fraction =
      assertFailsWith<NodeInputValidationException> { codec<Int>().coerce(1.5, "input") }

    // Assert
    assertEquals(5, int)
    assertEquals(5L, long)
    assertEquals(1.0, double)
    assertEquals(42, quoted)
    assertContains(outOfRange.message.orEmpty(), "input does not match kotlin.Int")
    assertContains(fraction.message.orEmpty(), "input does not match kotlin.Int")
  }

  @Test
  fun coerce_mapToSerializableClass_decodesIgnoringExtraKeysAndRejectsMissingField() {
    // Act
    val query = codec<CodecQuery>().coerce(mapOf("project" to "ADK", "extra" to secret), "input")
    val error =
      assertFailsWith<NodeInputValidationException> {
        codec<CodecQuery>().coerce(mapOf("maxResults" to 2), "input")
      }

    // Assert
    assertEquals(CodecQuery("ADK"), query)
    assertContains(error.message.orEmpty(), "input does not match")
    assertNull(error.cause)
  }

  @Test
  fun coerce_jsonShapedValues_decodeIntoTheirTypesAndRejectAnUnknownEnum() {
    // Act
    val priority = codec<CodecPriority>().coerce("HIGH", "input")
    val queries = codec<List<CodecQuery>>().coerce(listOf(mapOf("project" to "A")), "input")
    val set = codec<Set<Int>>().coerce(listOf(1L, 1L, 2L), "input")
    val keyedByInt = codec<Map<Int, String>>().coerce(mapOf("1" to "a"), "input")
    val unknown =
      assertFailsWith<NodeInputValidationException> {
        codec<CodecPriority>().coerce("GOLD", "input")
      }

    // Assert
    assertEquals(CodecPriority.HIGH, priority)
    assertEquals(listOf(CodecQuery("A")), queries)
    assertEquals(setOf(1, 2), set)
    assertEquals(mapOf(1 to "a"), keyedByInt)
    assertContains(unknown.message.orEmpty(), "input does not match")
    assertNull(unknown.cause)
  }

  @Test
  fun coerce_content_reachesOnlyContentOrAnyPorts() {
    // Arrange
    val message = Content.fromText(Role.USER, """{"project": "ADK"}""")

    // Act
    val sameContent = codec<Content>().coerce(message, "input")
    val sameAny = codec<Any>().coerce(message, "input")
    val classError =
      assertFailsWith<NodeInputValidationException> { codec<CodecQuery>().coerce(message, "input") }

    // Assert
    assertSame(message, sameContent)
    assertSame<Any>(message, sameAny)
    assertContains(classError.message.orEmpty(), "but got Content")
  }

  @Test
  fun coerce_failures_neverQuoteTheValue() {
    // Arrange
    val attempts: List<() -> Any?> =
      listOf(
        { codec<Int>().coerce(secret, "input") },
        { codec<CodecQuery>().coerce(mapOf("project" to 1, "note" to secret), "input") },
        { codec<CodecPriority>().coerce(secret, "input") },
        { codec<List<Int>>().coerce(listOf(secret), "input") },
      )

    // Act
    val messages = attempts.flatMap { attempt ->
      generateSequence<Throwable>(assertFailsWith<NodeInputValidationException> { attempt() }) {
          it.cause
        }
        .map { it.message.orEmpty() }
        .toList()
    }

    // Assert
    for (message in messages) {
      assertFalse(secret in message)
    }
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
    assertEquals(mapOf("project" to "ADK", "maxResults" to 5L), query)
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
