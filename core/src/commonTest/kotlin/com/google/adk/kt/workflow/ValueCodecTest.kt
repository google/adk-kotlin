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
import kotlin.reflect.KType
import kotlin.reflect.typeOf
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

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
}
