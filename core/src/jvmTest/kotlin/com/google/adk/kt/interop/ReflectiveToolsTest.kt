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

@file:OptIn(AdkJavaInteropApi::class)

package com.google.adk.kt.interop

import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.annotations.Param
import com.google.adk.kt.annotations.Requiredness
import com.google.adk.kt.annotations.Tool
import com.google.adk.kt.testing.testToolContext
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.Type
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

enum class Style {
  FORMAL,
  CASUAL,
}

/** A data class param: the KSP path maps it to a nested object; [ReflectiveTools] does not. */
data class Point(val x: Int, val y: Int)

/**
 * Stands in for a Java caller. [ReflectiveTools] reads only the [Tool]/[Param] annotations, never
 * Kotlin parameter names, so the reflection path it exercises is the same one a `.java` source
 * hits. The methods return `String` because this source is also KSP-processed under Gradle's
 * `jvmTest`.
 */
class Fixture {
  var lastContext: ToolContext? = null

  @Tool(name = "current_time", description = "Returns the current time.")
  fun currentTime(): String = "noon"

  @Tool(description = "Adds two numbers.")
  fun addNumbers(
    @Param(name = "a", description = "first addend") a: Int,
    @Param(name = "b") b: Int,
  ): String = (a + b).toString()

  @Tool(description = "Echoes the text back.")
  fun echo(@Param(name = "text") text: String): String = text

  @Tool(description = "Reads session state.")
  fun readState(context: ToolContext): String {
    lastContext = context
    return "ok"
  }

  @Tool(description = "Uses a ToolContext alongside a value parameter.")
  fun readValue(context: ToolContext, @Param(name = "key") key: String): String {
    lastContext = context
    return "value:$key"
  }

  @Tool(description = "Scales a number, exercising Double and Float coercion.")
  fun scale(@Param(name = "factor") factor: Double, @Param(name = "ratio") ratio: Float): String =
    "$factor:$ratio"

  @Tool(description = "Greets in a given style.")
  fun greet(@Param(name = "style") style: Style): String = style.name

  @Tool(description = "Adds an optional note.")
  fun withOptional(@Param(name = "note", required = Requiredness.OPTIONAL) note: String?): String =
    note ?: "none"

  @Tool(description = "Collects items and metadata.")
  fun collect(
    @Param(name = "items") items: List<String>,
    @Param(name = "meta") meta: Map<String, String>,
  ): String = "${items.size}:${meta.size}"

  @Tool(description = "Always fails.") fun boom(): String = throw IllegalStateException("boom")

  @Tool(description = "Returns null.") fun returnsNull(): String? = null

  @Tool(description = "Has an unmapped parameter type.")
  fun unmapped(@Param(name = "p") p: Point): String = p.toString()

  @Tool(description = "Missing a @Param.") fun missingParam(x: String): String = x

  fun overloaded(a: String): String = a

  fun overloaded(a: Int): String = a.toString()

  fun notATool(): String = ""
}

class ReflectiveToolsTest {

  private val fixture = Fixture()

  @Test
  fun fromMethod_usesExplicitNameAndDescription() {
    val tool = ReflectiveTools.fromMethod(fixture, "currentTime")

    assertEquals("current_time", tool.name)
    assertEquals("Returns the current time.", tool.description)
  }

  @Test
  fun fromMethod_defaultsNameToMethodName() {
    val tool = ReflectiveTools.fromMethod(fixture, "addNumbers")

    assertEquals("addNumbers", tool.name)
  }

  @Test
  fun declaration_buildsSchemaFromParams() {
    val declaration = ReflectiveTools.fromMethod(fixture, "addNumbers").declaration()!!

    assertEquals("addNumbers", declaration.name)
    val parameters = declaration.parameters!!
    assertEquals(Type.OBJECT, parameters.type)
    val properties = parameters.properties!!
    assertEquals(Type.INTEGER, properties["a"]!!.type)
    assertEquals("first addend", properties["a"]!!.description)
    assertNull(properties["b"]!!.description)
    assertEquals(listOf("a", "b"), parameters.required)
  }

  @Test
  fun declaration_noParams_hasNoParameters() {
    val declaration = ReflectiveTools.fromMethod(fixture, "currentTime").declaration()!!

    assertNull(declaration.parameters)
  }

  @Test
  fun declaration_optionalParam_isNotRequired() {
    val parameters =
      ReflectiveTools.fromMethod(fixture, "withOptional").declaration()!!.parameters!!

    assertTrue(parameters.properties!!.containsKey("note"))
    assertNull(parameters.required)
  }

  @Test
  fun declaration_enumParam_isStringWithEnumValues() {
    val schema =
      ReflectiveTools.fromMethod(fixture, "greet").declaration()!!.parameters!!.properties!![
        "style"]!!

    assertEquals(Type.STRING, schema.type)
    assertEquals(listOf("FORMAL", "CASUAL"), schema.enum)
  }

  @Test
  fun declaration_listOrMapParam_fails() {
    // List/Map are unsupported: erased generics hide the element type needed for items/properties.
    val tool = ReflectiveTools.fromMethod(fixture, "collect")

    assertFailsWith<IllegalArgumentException> { tool.declaration() }
  }

  @Test
  fun run_invokesMethodAndReturnsResult() = runBlocking {
    val tool = ReflectiveTools.fromMethod(fixture, "currentTime")

    assertEquals("noon", tool.run(testToolContext(), emptyMap()))
  }

  @Test
  fun run_returnsValueUnwrapped() = runBlocking {
    val tool = ReflectiveTools.fromMethod(fixture, "echo")

    assertEquals("hi", tool.run(testToolContext(), mapOf("text" to "hi")))
  }

  @Test
  fun run_coercesJsonNumbersToDeclaredType() = runBlocking {
    val tool = ReflectiveTools.fromMethod(fixture, "addNumbers")

    // JSON numbers arrive as Double; the Int params must be coerced before the reflective call.
    assertEquals("5", tool.run(testToolContext(), mapOf("a" to 2.0, "b" to 3.0)))
  }

  @Test
  fun run_coercesEnumFromName() = runBlocking {
    val tool = ReflectiveTools.fromMethod(fixture, "greet")

    assertEquals("CASUAL", tool.run(testToolContext(), mapOf("style" to "CASUAL")))
  }

  @Test
  fun run_unknownEnumValue_fails() {
    val tool = ReflectiveTools.fromMethod(fixture, "greet")

    assertFailsWith<IllegalArgumentException> {
      runBlocking { tool.run(testToolContext(), mapOf("style" to "SASSY")) }
    }
  }

  @Test
  fun run_injectsToolContext() = runBlocking {
    val tool = ReflectiveTools.fromMethod(fixture, "readState")
    val context = testToolContext()

    val unused = tool.run(context, emptyMap())

    assertSame(context, fixture.lastContext)
  }

  @Test
  fun run_injectsToolContextAlongsideValueParam() = runBlocking {
    val tool = ReflectiveTools.fromMethod(fixture, "readValue")
    val context = testToolContext()

    val result = tool.run(context, mapOf("key" to "k"))

    assertEquals("value:k", result)
    assertSame(context, fixture.lastContext)
  }

  @Test
  fun run_coercesDoubleAndFloatFromJsonNumbers() = runBlocking {
    val tool = ReflectiveTools.fromMethod(fixture, "scale")

    // JSON numbers arrive as Int/Double; Double and Float params must be coerced.
    assertEquals("2.0:3.0", tool.run(testToolContext(), mapOf("factor" to 2, "ratio" to 3)))
  }

  @Test
  fun run_omittedOptionalArgument_usesMethodDefault() = runBlocking {
    val tool = ReflectiveTools.fromMethod(fixture, "withOptional")

    assertEquals("none", tool.run(testToolContext(), emptyMap()))
  }

  @Test
  fun run_nullReturn_becomesEmptyMap() = runBlocking {
    val tool = ReflectiveTools.fromMethod(fixture, "returnsNull")

    assertEquals(emptyMap<String, Any>(), tool.run(testToolContext(), emptyMap()))
  }

  @Test
  fun run_missingRequiredArgument_fails() {
    val tool = ReflectiveTools.fromMethod(fixture, "addNumbers")

    assertFailsWith<IllegalArgumentException> {
      runBlocking { tool.run(testToolContext(), mapOf("a" to 1)) }
    }
  }

  @Test
  fun run_propagatesMethodException() {
    val tool = ReflectiveTools.fromMethod(fixture, "boom")

    assertFailsWith<IllegalStateException> {
      runBlocking { tool.run(testToolContext(), emptyMap()) }
    }
  }

  @Test
  fun declaration_unmappedParamType_fails() {
    val tool = ReflectiveTools.fromMethod(fixture, "unmapped")

    assertFailsWith<IllegalArgumentException> { tool.declaration() }
  }

  @Test
  fun fromMethod_missingParamAnnotation_fails() {
    assertFailsWith<IllegalArgumentException> {
      ReflectiveTools.fromMethod(fixture, "missingParam")
    }
  }

  @Test
  fun fromMethod_overloadedMethod_fails() {
    assertFailsWith<IllegalArgumentException> { ReflectiveTools.fromMethod(fixture, "overloaded") }
  }

  @Test
  fun fromMethod_unknownMethod_fails() {
    assertFailsWith<IllegalArgumentException> { ReflectiveTools.fromMethod(fixture, "nope") }
  }

  @Test
  fun fromMethod_notAnnotated_fails() {
    assertFailsWith<IllegalArgumentException> { ReflectiveTools.fromMethod(fixture, "notATool") }
  }
}
