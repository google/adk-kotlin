/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.adk.kt.compiler.ksp

import com.google.adk.kt.agents.BaseAgent
import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.annotations.Param
import com.google.adk.kt.annotations.Tool
import com.google.adk.kt.events.Event
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.Type
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class FunctionToolProcessorTest {

  private class SimpleAgent(name: String = "test") : BaseAgent(name = name) {
    override fun runAsyncImpl(context: InvocationContext): Flow<Event> = flow {}
  }

  private fun createDummyContext(functionCallId: String? = null): ToolContext {
    val session = Session(key = SessionKey("app", "user", "id"))
    val agent = SimpleAgent()
    val invocationContext = InvocationContext(session = session, runConfig = null, agent = agent)
    return ToolContext(invocationContext = invocationContext, functionCallId = functionCallId)
  }

  @Test
  fun generatesPrimitiveFunctionTool_validParams_succeeds() = runTest {
    val tool = SampleToolTool()
    val context = createDummyContext()

    val result = tool.execute(context, mapOf("name" to "Alice", "age" to 30))

    assertThat(result).isEqualTo(mapOf("result" to "Sample"))

    val declaration = tool.declaration()!!
    assertThat(declaration.name).isEqualTo("sampleTool")
    assertThat(declaration.parameters?.properties?.get("name")?.type).isEqualTo(Type.STRING)
    assertThat(declaration.parameters?.properties?.get("age")?.type).isEqualTo(Type.INTEGER)
  }

  @Test
  fun generatesSchema_withKDocAndParam_succeeds() {
    val tool = SampleSchemaToolTool()
    val declaration = tool.declaration()!!

    assertThat(declaration.description).isEqualTo("This is a sample tool.")
    assertThat(declaration.parameters?.properties?.get("name")?.description)
      .isEqualTo("The name of the user.")
    assertThat(declaration.parameters?.properties?.get("age")?.description)
      .isEqualTo("Age from Param annotation")
    assertThat(declaration.parameters?.required).containsExactly("name", "age")
  }

  @Test
  fun generatesCollectionAndEnumFunctionTool_validParams_succeeds() = runTest {
    val tool = CollectionToolTool()
    val context = createDummyContext()

    val result =
      tool.execute(
        context,
        mapOf(
          "names" to listOf("a", "b"),
          "scores" to listOf(1, 2),
          "map" to mapOf("k" to "v"),
          "enumVal" to "A",
        ),
      )

    assertThat(result).isEqualTo(mapOf("result" to "Sample"))
    val declaration = tool.declaration()!!
    assertThat(declaration.parameters?.properties?.get("names")?.type).isEqualTo(Type.ARRAY)
    assertThat(declaration.parameters?.properties?.get("map")?.type).isEqualTo(Type.OBJECT)
    assertThat(declaration.parameters?.properties?.get("enumVal")?.enum).containsExactly("A", "B")
  }

  @Test
  fun generatesDataClassFunctionTool_validParams_succeeds() = runTest {
    val tool = DataClassToolTool()
    val context = createDummyContext()

    val result = tool.execute(context, mapOf("user" to mapOf("name" to "Bob", "age" to 25)))

    assertThat(result).isEqualTo(mapOf("result" to "Hello Bob"))
  }

  @Test
  fun generatesListDataClass_validParams_succeeds() = runTest {
    val tool = ListDataClassToolTool()
    val context = createDummyContext()

    val result =
      tool.execute(
        context,
        mapOf("items" to listOf(mapOf("id" to "1", "count" to 5), mapOf("id" to "2", "count" to 3))),
      )

    assertThat(result).isEqualTo(mapOf("result" to "Got 2 items"))
  }

  @Test
  fun generatesDataClassReturn_validType_succeeds() = runTest {
    val tool = ReturnDataClassToolTool()
    val context = createDummyContext()

    val result = tool.execute(context, emptyMap())

    assertThat(result).isEqualTo(mapOf("result" to mapOf("status" to "OK", "code" to 200)))
  }

  @Test
  fun generatesNestedSchema_validDataClass_succeeds() {
    val tool = ProfileToolTool()
    val declaration = tool.declaration()!!

    assertThat(declaration.parameters?.type).isEqualTo(Type.OBJECT)
    val profileParamSchema = declaration.parameters?.properties!!["profile"]!!
    val profileProps = profileParamSchema.properties!!
    assertThat(profileProps["name"]?.type).isEqualTo(Type.STRING)
    assertThat(profileProps["address"]?.type).isEqualTo(Type.OBJECT)

    val addressProps = profileProps["address"]?.properties!!
    assertThat(addressProps["city"]?.type).isEqualTo(Type.STRING)
    assertThat(addressProps["zip"]?.type).isEqualTo(Type.INTEGER)
  }

  @Test
  fun compile_longRunningTool_generatesCorrectToolClass() {
    val tool = LongRunningToolTool()

    assertThat(tool.isLongRunning).isTrue()
    assertThat(tool.description).contains("NOTE: This tool performs a long-running operation.")
  }

  @Test
  fun compile_requireConfirmationTool_forwardsFlagToFunctionToolConstructor() = runTest {
    val tool = ConfirmToolTool()
    val context = createDummyContext(functionCallId = "call-123")

    // Without confirmation payload, it should return CONFIRMATION_REQUIRED_ERROR
    val result = tool.run(context, emptyMap())
    assertThat(result).isInstanceOf(Map::class.java)
    val resultMap = result as Map<*, *>
    assertThat(resultMap["error"])
      .isEqualTo("This tool call requires confirmation, please approve or reject.")
  }

  @Test
  fun testSuspendFunction() = runTest {
    val source = MyToolSource()
    val tool = MySuspendFunTool(source)
    val context = createDummyContext()

    val result = tool.execute(context, mapOf("name" to "World"))

    assertThat(result).isEqualTo(mapOf("result" to "Hello, World"))
  }

  @Test
  fun exceptionFromToolFunction_propagates() = runTest {
    // KSP-generated tools no longer catch exceptions from the user function. The framework's
    // outer try/catch in `InvocationContext.executeSingleFunctionCall` routes them through the
    // error-tool callback pipeline (or rethrows when no callback recovers), matching Python ADK's
    // behaviour where `FunctionTool.run_async` lets exceptions propagate.
    val source = MyToolSource()
    val tool = MyExceptionFunTool(source)
    val context = createDummyContext()

    val thrown = kotlin.runCatching { tool.execute(context, emptyMap()) }.exceptionOrNull()

    assertThat(thrown).isInstanceOf(Exception::class.java)
    assertThat(thrown!!.message).isEqualTo("Test exception")
  }

  @Test
  fun generatesListReturn_validType_succeeds() = runTest {
    val tool = ReturnListToolTool()
    val context = createDummyContext()

    val result = tool.execute(context, emptyMap())

    assertThat(result).isEqualTo(mapOf("result" to listOf("a", "b")))
  }

  @Test
  fun generatesListAnyReturn_mixedElementTypes_passesElementsThroughUnchanged() = runTest {
    val tool = ReturnListAnyToolTool()
    val context = createDummyContext()

    val result = tool.execute(context, emptyMap()) as Map<*, *>

    // Each element must preserve its Kotlin type (no `.toString()` or numeric coercion).
    val inner = result["result"] as List<*>
    assertThat(inner).containsExactly("GOOG", 123.45, 1000).inOrder()
    assertThat(inner[1]).isInstanceOf(Double::class.javaObjectType)
    assertThat(inner[2]).isInstanceOf(Int::class.javaObjectType)
    assertThat(inner[0]).isInstanceOf(String::class.java)
  }

  @Test
  fun generatesListNullableAnyReturn_nullableElements_includesNullEntries() = runTest {
    val tool = ReturnListNullableAnyToolTool()
    val context = createDummyContext()

    val result = tool.execute(context, emptyMap()) as Map<*, *>

    val inner = result["result"] as List<*>
    assertThat(inner).containsExactly(42, null, "x").inOrder()
  }

  @Test
  fun generatesMapReturn_validType_succeeds() = runTest {
    val tool = ReturnMapToolTool()
    val context = createDummyContext()

    val result = tool.execute(context, emptyMap())

    assertThat(result).isEqualTo(mapOf("result" to mapOf("a" to 1)))
  }

  @Test
  fun generatesMapStringAnyReturn_mixedValueTypes_passesValuesThroughUnchanged() = runTest {
    val tool = ReturnMapStringAnyToolTool()
    val context = createDummyContext()

    val result = tool.execute(context, emptyMap()) as Map<*, *>

    // Each value must preserve its Kotlin type (no `.toString()` or numeric coercion).
    val inner = result["result"] as Map<*, *>
    assertThat(inner).containsExactly("symbol", "GOOG", "price", 123.45, "volume", 1000)
    assertThat(inner["price"]).isInstanceOf(Double::class.javaObjectType)
    assertThat(inner["volume"]).isInstanceOf(Int::class.javaObjectType)
    assertThat(inner["symbol"]).isInstanceOf(String::class.java)
  }

  @Test
  fun generatesMapStringNullableAnyReturn_nullableValues_includesNullEntries() = runTest {
    val tool = ReturnMapStringNullableAnyToolTool()
    val context = createDummyContext()

    val result = tool.execute(context, emptyMap()) as Map<*, *>

    val inner = result["result"] as Map<*, *>
    assertThat(inner).containsExactly("present", 42, "absent", null)
  }

  @Test
  fun generatesDeeplyNestedMapStringAnyReturn_mapListMap_preservesShapeAndTypes() = runTest {
    val tool = ReturnDeeplyNestedMapStringAnyToolTool()
    val context = createDummyContext()

    val result = tool.execute(context, emptyMap()) as Map<*, *>

    // Nested Map / List / Map structure must survive pass-through unchanged: no `.toString()`,
    // no flattening, and inner primitives keep their Kotlin types.
    val outer = result["result"] as Map<*, *>
    val middleOuter = outer["outer"] as Map<*, *>
    val middleList = middleOuter["middle"] as List<*>
    assertThat(middleList).hasSize(2)
    val firstLeaf = middleList[0] as Map<*, *>
    assertThat(firstLeaf["leaf"]).isEqualTo(1)
    assertThat(firstLeaf["leaf"]).isInstanceOf(Int::class.javaObjectType)
    assertThat(firstLeaf["label"]).isEqualTo("first")
    assertThat(middleOuter["scalar"]).isEqualTo("value")
  }

  @Test
  fun generatesEnumReturn_validType_succeeds() = runTest {
    val tool = ReturnEnumToolTool()
    val context = createDummyContext()

    val result = tool.execute(context, emptyMap())

    assertThat(result).isEqualTo(mapOf("result" to "OK"))
  }

  @Test
  fun generatesMapDataClassValue_validParams_succeeds() = runTest {
    val tool = MapDataClassToolTool()
    val context = createDummyContext()

    val result = tool.execute(context, mapOf("items" to mapOf("key" to mapOf("value" to 42))))

    assertThat(result).isEqualTo(mapOf("result" to "Got 1 items"))
  }

  @Test
  fun testClassMethodExtension() {
    val service = MyAwesomeService()
    val tools = service.generatedTools()

    assertThat(tools).hasSize(2)
    assertThat(tools.map { it.name }).containsExactly("myMethod", "anotherMethod")
  }

  @Test
  fun testTopLevelExtension() {
    val tools = getFunctionToolProcessorTestGeneratedTools()

    assertThat(tools).isNotEmpty()
    assertThat(tools.map { it.name }).containsAtLeast("topLevelOne", "topLevelTwo")
  }

  @Test
  fun compile_customNameAndDescription_generatesCorrectToolClass() {
    val tool = CustomNamedToolTool()

    assertThat(tool.name).isEqualTo("custom_name")
    assertThat(tool.description).isEqualTo("Custom description")
    val declaration = tool.declaration()!!
    assertThat(declaration.name).isEqualTo("custom_name")
    assertThat(declaration.description).isEqualTo("Custom description")
  }
}

// -- Test Fixtures --

@Tool
fun sampleTool(context: ToolContext, name: String, age: Int): String {
  return "Sample"
}

/**
 * This is a sample tool.
 *
 * @param name The name of the user.
 * @param age The age of the user.
 */
@Tool
fun sampleSchemaTool(
  context: ToolContext,
  name: String,
  @Param("Age from Param annotation") age: Int,
  optionalVal: Boolean? = null,
): String {
  return "Sample"
}

enum class MyEnum {
  A,
  B,
}

@Tool
fun collectionTool(
  context: ToolContext,
  names: List<String>,
  scores: List<Int>,
  map: Map<String, String>,
  enumVal: MyEnum,
): String {
  return "Sample"
}

data class User(val name: String, val age: Int)

@Tool
fun dataClassTool(user: User): String {
  return "Hello ${user.name}"
}

data class Item(val id: String, val count: Int)

@Tool
fun listDataClassTool(items: List<Item>): String {
  return "Got ${items.size} items"
}

data class Result(val status: String, val code: Int)

@Tool
fun returnDataClassTool(): Result {
  return Result("OK", 200)
}

data class Address(val city: String, val zip: Int)

data class UserProfile(val name: String, val address: Address)

@Tool
fun profileTool(profile: UserProfile): String {
  return "Profile"
}

@Tool(isLongRunning = true)
fun longRunningTool(context: ToolContext): String {
  return "Sample"
}

@Tool(requireConfirmation = true)
fun confirmTool(context: ToolContext): String {
  return "Sample"
}

class MyToolSource {
  @Tool
  suspend fun mySuspendFun(context: ToolContext, name: String?): String {
    return "Hello, $name"
  }

  @Tool
  fun myExceptionFun(context: ToolContext) {
    throw Exception("Test exception")
  }
}

@Tool
fun returnListTool(): List<String> {
  return listOf("a", "b")
}

@Tool
fun returnListAnyTool(): List<Any> {
  return listOf("GOOG", 123.45, 1000)
}

@Tool
fun returnListNullableAnyTool(): List<Any?> {
  return listOf(42, null, "x")
}

@Tool
fun returnMapTool(): Map<String, Int> {
  return mapOf("a" to 1)
}

@Tool
fun returnMapStringAnyTool(): Map<String, Any> {
  return mapOf("symbol" to "GOOG", "price" to 123.45, "volume" to 1000)
}

@Tool
fun returnMapStringNullableAnyTool(): Map<String, Any?> {
  return mapOf("present" to 42, "absent" to null)
}

@Tool
fun returnDeeplyNestedMapStringAnyTool(): Map<String, Any> {
  return mapOf(
    "outer" to
      mapOf(
        "middle" to
          listOf(mapOf("leaf" to 1, "label" to "first"), mapOf("leaf" to 2, "label" to "second")),
        "scalar" to "value",
      )
  )
}

enum class MyResultEnum {
  OK,
  FAIL,
}

@Tool
fun returnEnumTool(): MyResultEnum {
  return MyResultEnum.OK
}

data class MapValue(val value: Int)

@Tool
fun mapDataClassTool(items: Map<String, MapValue>): String {
  return "Got ${items.size} items"
}

class MyAwesomeService {
  @Tool fun myMethod(name: String): String = "Hello $name"

  @Tool fun anotherMethod(): String = "Bye"
}

@Tool fun topLevelOne(): String = "One"

@Tool fun topLevelTwo(): String = "Two"

@Tool(name = "custom_name", description = "Custom description")
fun customNamedTool(): String {
  return "Custom"
}
