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

package com.google.adk.kt.telemetry

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TracePayloadFormatterTest {

  @Test
  fun format_nullInput_returnsNullString() {
    val result = TracePayloadFormatter.format(null)

    assertThat(result).isEqualTo("null")
  }

  @Test
  fun format_stringInput_returnsQuotedString() {
    val result = TracePayloadFormatter.format("test-string")

    assertThat(result).isEqualTo("\"test-string\"")
  }

  @Test
  fun format_numberInput_returnsStringRepresentation() {
    assertThat(TracePayloadFormatter.format(42)).isEqualTo("42")
    assertThat(TracePayloadFormatter.format(3.14)).isEqualTo("3.14")
  }

  @Test
  fun format_booleanInput_returnsStringRepresentation() {
    assertThat(TracePayloadFormatter.format(true)).isEqualTo("true")
    assertThat(TracePayloadFormatter.format(false)).isEqualTo("false")
  }

  @Test
  fun format_mapInput_returnsJsonString() {
    val input = mapOf("key" to "value", "number" to 42)
    val result = TracePayloadFormatter.format(input)

    assertThat(result).contains("\"key\":\"value\"")
    assertThat(result).contains("\"number\":42")
  }

  @Test
  fun format_collectionInput_returnsJsonArrayString() {
    val input = listOf("value1", "value2")
    val result = TracePayloadFormatter.format(input)

    assertThat(result).isEqualTo("[\"value1\",\"value2\"]")
  }

  @Test
  fun format_intArrayInput_returnsJsonArrayString() {
    assertThat(TracePayloadFormatter.format(intArrayOf(1, 2))).isEqualTo("[1,2]")
  }

  @Test
  fun format_longArrayInput_returnsJsonArrayString() {
    assertThat(TracePayloadFormatter.format(longArrayOf(1L, 2L))).isEqualTo("[1,2]")
  }

  @Test
  fun format_doubleArrayInput_returnsJsonArrayString() {
    assertThat(TracePayloadFormatter.format(doubleArrayOf(1.0, 2.0))).isEqualTo("[1,2]")
  }

  @Test
  fun format_booleanArrayInput_returnsJsonArrayString() {
    assertThat(TracePayloadFormatter.format(booleanArrayOf(true, false))).isEqualTo("[true,false]")
  }

  @Test
  fun format_floatArrayInput_returnsJsonArrayString() {
    assertThat(TracePayloadFormatter.format(floatArrayOf(1.0f, 2.0f))).isEqualTo("[1,2]")
  }

  @Test
  fun format_shortArrayInput_returnsJsonArrayString() {
    assertThat(TracePayloadFormatter.format(shortArrayOf(1.toShort(), 2.toShort())))
      .isEqualTo("[1,2]")
  }

  @Test
  fun format_byteArrayInput_returnsJsonArrayString() {
    assertThat(TracePayloadFormatter.format(byteArrayOf(1, 2))).isEqualTo("[1,2]")
  }

  @Test
  fun format_charArrayInput_returnsJsonArrayString() {
    assertThat(TracePayloadFormatter.format(charArrayOf('a', 'b'))).isEqualTo("[\"a\",\"b\"]")
  }

  @Test
  fun format_objectInput_returnsJsonStringWithValueField() {
    val input = TestObject("value")

    val result = TracePayloadFormatter.format(input)

    // Android implementation wraps unknown objects in a "value" field with toString()
    assertThat(result).isEqualTo("{\"value\":\"TestObject(name=value)\"}")
  }

  @Test
  fun format_circularReference_returnsErrorJson() {
    val input = CircularObject()
    input.self = input

    val result = TracePayloadFormatter.format(input)

    assertThat(result).isEqualTo("{\"error\": \"serialization failed\"}")
  }

  private data class TestObject(val name: String)

  private data class CircularObject(var self: CircularObject? = null)
}
