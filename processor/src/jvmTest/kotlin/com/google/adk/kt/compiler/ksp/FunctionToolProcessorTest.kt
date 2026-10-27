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
import com.google.adk.kt.agents.Context
import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.agents.LiveRequestQueue
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.RunConfig
import com.google.adk.kt.agents.StreamingMode
import com.google.adk.kt.annotations.ExperimentalLiveApi
import com.google.adk.kt.annotations.Param
import com.google.adk.kt.annotations.Requiredness
import com.google.adk.kt.annotations.Tool
import com.google.adk.kt.events.Event
import com.google.adk.kt.models.ContentInput
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.Model
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.testing.DummyLiveModel
import com.google.adk.kt.testing.DummyLiveStep.AwaitSent
import com.google.adk.kt.testing.DummyLiveStep.Respond
import com.google.adk.kt.testing.modelFunctionCallResponse
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.tools.FunctionTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.Type
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
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

  /** Calls [toolName], then answers with text once the request carries the tool's answer. */
  private class CallThenAnswerModel(private val toolName: String) : Model {
    override val name = "call-then-answer"

    override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> = flow {
      val answered = request.contents.lastOrNull()?.parts?.any { it.functionResponse != null }
      emit(
        if (answered == true) LlmResponse(content = modelMessage("done"))
        else modelFunctionCallResponse(toolName)
      )
    }
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
  fun paramNameOverride_usesWireNameInSchemaAndArgs() = runTest {
    val tool = NamedParamToolTool()
    val declaration = tool.declaration()!!

    assertThat(declaration.parameters?.properties).containsKey("wire_name")
    assertThat(declaration.parameters?.properties).doesNotContainKey("kotlinName")
    assertThat(declaration.parameters?.required).containsExactly("wire_name")
    assertThat(tool.execute(createDummyContext(), mapOf("wire_name" to "v")))
      .isEqualTo(mapOf("result" to "got v"))
  }

  @Test
  fun requiredness_required_marksNullableParamRequired() {
    val declaration = RequiredOverrideToolTool().declaration()!!

    assertThat(declaration.parameters?.required).containsExactly("maybe")
  }

  @Test
  fun requiredness_optional_leavesParamOutOfRequired() {
    val declaration = OptionalOverrideToolTool().declaration()!!

    assertThat(declaration.parameters?.properties).containsKey("note")
    assertThat(declaration.parameters?.required).isNull()
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
  fun declaration_toolReturningDataClass_describesTheResultWrapper() {
    val declaration = ReturnDataClassToolTool().declaration()!!

    val response = declaration.response!!
    assertThat(response.type).isEqualTo(Type.OBJECT)
    assertThat(response.required).isNull()

    val result = response.properties!!["result"]!!
    assertThat(result.type).isEqualTo(Type.OBJECT)
    assertThat(result.properties!!["status"]?.type).isEqualTo(Type.STRING)
    assertThat(result.properties!!["code"]?.type).isEqualTo(Type.INTEGER)
    assertThat(result.required).containsExactly("status", "code")
  }

  @Test
  fun declaration_toolReturningString_describesAStringResult() {
    val declaration = SampleToolTool().declaration()!!

    assertThat(declaration.response?.properties!!["result"]?.type).isEqualTo(Type.STRING)
  }

  @Test
  fun declaration_toolReturningList_describesAnArrayResultWithItems() {
    val declaration = ReturnListToolTool().declaration()!!

    val result = declaration.response?.properties!!["result"]!!
    assertThat(result.type).isEqualTo(Type.ARRAY)
    assertThat(result.items?.type).isEqualTo(Type.STRING)
  }

  @Test
  fun declaration_toolReturningEnum_describesItsValues() {
    val declaration = ReturnEnumToolTool().declaration()!!

    val result = declaration.response?.properties!!["result"]!!
    assertThat(result.type).isEqualTo(Type.STRING)
    assertThat(result.enum).containsExactly("OK", "FAIL")
  }

  @Test
  fun declaration_toolReturningUnit_declaresNoResponse() {
    assertThat(ReturnUnitToolTool().declaration()!!.response).isNull()
  }

  @Test
  fun declaration_longRunningTool_declaresNoResponse() {
    assertThat(LongRunningToolTool().declaration()!!.response).isNull()
  }

  @Test
  fun declaration_nullableReturn_marksTheResultNullable() {
    val result = ReturnNullableStringToolTool().declaration()!!.response?.properties!!["result"]!!
    assertThat(result.type).isEqualTo(Type.STRING)
    assertThat(result.nullable).isTrue()
  }

  @Test
  fun declaration_dataClassWithNullableProperty_marksThatPropertyNullable() {
    val result = ReturnNullablePropertyToolTool().declaration()!!.response?.properties!!["result"]!!
    assertThat(result.properties!!["name"]?.nullable).isNull()
    assertThat(result.properties!!["note"]?.nullable).isTrue()
    // A nullable property is not required either.
    assertThat(result.required).containsExactly("name")
  }

  @Test
  fun declaration_nullableParameter_marksThatParameterNullable() {
    val params = NullableParamToolTool().declaration()!!.parameters!!
    assertThat(params.properties!!["required"]?.nullable).isNull()
    assertThat(params.properties!!["optional"]?.nullable).isTrue()
  }

  @Test
  fun declaration_toolReturningListOfNullableAny_declaresNoResponse() {
    assertThat(ReturnListNullableAnyToolTool().declaration()!!.response).isNull()
  }

  @Test
  fun declaration_dataClassHoldingAListOfAny_declaresNoResponse() {
    // Only passes if `describesFaithfully` recurses through the data class's properties.
    assertThat(ReturnBoxOfAnyToolTool().declaration()!!.response).isNull()
  }

  @Test
  fun declaration_toolReturningListOfAny_declaresNoResponse() {
    // The elements go out untouched, so the list really can hold mixed types.
    assertThat(ReturnListAnyToolTool().declaration()!!.response).isNull()
  }

  @Test
  fun declaration_toolReturningMapOfAny_stillDescribesAnObjectResult() {
    val result = ReturnMapStringAnyToolTool().declaration()!!.response?.properties!!["result"]!!
    assertThat(result.type).isEqualTo(Type.OBJECT)
    assertThat(result.properties).isNull()
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
  fun classMethodTool_recordsSourceClassAndMethodInCustomMetadata() {
    val tool = MyAwesomeService().generatedTools().first { it.name == "myMethod" }

    assertThat(tool.customMetadata[FunctionTool.SOURCE_CLASS_METADATA_KEY])
      .isEqualTo(MyAwesomeService::class.java.name)
    assertThat(tool.customMetadata[FunctionTool.SOURCE_METHOD_METADATA_KEY]).isEqualTo("myMethod")
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

  @Test
  fun execute_toolWithBaseContextParam_injectsContext() = runTest {
    val tool = BaseContextToolTool()
    val context = createDummyContext()

    assertThat(tool.declaration()?.parameters?.properties).doesNotContainKey("context")
    val result = tool.execute(context, mapOf("name" to "Alice"))

    assertThat(result).isEqualTo(mapOf("result" to "test:Alice"))
  }

  @Test
  fun execute_toolReturningAFlowOfStrings_wrapsEachValueAsAResult() {
    runBlocking {
      val result =
        StreamToolTool()
          .execute(createDummyContext(functionCallId = "c1"), mapOf("symbol" to "GOOG"))

      assertThat((result as Flow<*>).toList())
        .containsExactly(mapOf("result" to "c1 GOOG 300"), mapOf("result" to "GOOG 400"))
        .inOrder()
    }
  }

  @Test
  fun execute_toolReturningAFlowOfMaps_returnsTheirValues() {
    runBlocking {
      val result = StreamMapToolTool().execute(createDummyContext(), emptyMap())

      assertThat((result as Flow<*>).toList())
        .containsExactly(
          mapOf("result" to mapOf("price" to 300)),
          mapOf("result" to mapOf("price" to 400)),
        )
        .inOrder()
    }
  }

  @OptIn(ExperimentalLiveApi::class)
  @Test
  fun runLive_generatedToolReturningAFlow_streamsItsValues() {
    runBlocking {
      val pending =
        mapOf("status" to "The function is running asynchronously and the results are pending.")
      fun answers(id: String, response: Map<String, Any?>): (Any) -> Boolean = { message ->
        message is ContentInput &&
          message.content.parts.any {
            it.functionResponse?.id == id && it.functionResponse?.response == response
          }
      }
      val script =
        listOf(
          Respond(modelFunctionCallResponse("streamTool", mapOf("symbol" to "GOOG"), id = "c1")),
          AwaitSent("the pending answer", answers("c1", pending)),
          AwaitSent("the first value", answers("c1", mapOf("result" to "c1 GOOG 300"))),
          AwaitSent("the second value", answers("c1", mapOf("result" to "GOOG 400"))),
          Respond(LlmResponse(turnComplete = true)),
        )
      val model = DummyLiveModel(script)
      val agent = LlmAgent(name = "agent", model = model, tools = listOf(StreamToolTool()))
      val queue = LiveRequestQueue()

      val unused =
        InMemoryRunner(agent = agent)
          .runLive("u", "s", queue, RunConfig(streamingMode = StreamingMode.BIDI))
          .onEach { if (it.turnComplete) queue.close() }
          .toList()

      model.connections.single().assertAllWaitsMet()
    }
  }

  @Test
  fun execute_toolReturningAFlowOfDataClasses_shapesEachLikeASingleResult() {
    runBlocking {
      val result = StreamQuotesTool().execute(createDummyContext(), emptyMap())

      assertThat((result as Flow<*>).toList())
        .containsExactly(mapOf("result" to mapOf("symbol" to "GOOG", "price" to 300)))
    }
  }

  @Test
  fun execute_toolReturningAFlowOfNullableStrings_leavesNullUnwrapped() {
    runBlocking {
      val result = StreamMaybeTool().execute(createDummyContext(), emptyMap())

      assertThat((result as Flow<*>).toList())
        .containsExactly(null, mapOf("result" to "ready"))
        .inOrder()
    }
  }

  @Test
  fun runAsync_generatedToolReturningNullThenAValue_answersWithTheValue() {
    runBlocking {
      val agent =
        LlmAgent(
          name = "agent",
          model = CallThenAnswerModel("streamMaybe"),
          tools = listOf(StreamMaybeTool()),
        )

      val events =
        InMemoryRunner(agent = agent).runAsync("u", "s", newMessage = userMessage("go")).toList()

      assertThat(events.flatMap { it.functionResponses() }.single().response)
        .isEqualTo(mapOf("result" to "ready"))
    }
  }

  @Test
  fun execute_toolReturningAFlowOfAny_passesItsValuesThroughUnconverted() {
    runBlocking {
      val result = StreamMixedTool().execute(createDummyContext(), emptyMap())

      assertThat((result as Flow<*>).toList()).containsExactly(mixedEvent, "done").inOrder()
    }
  }

  @Test
  fun execute_toolReturningAFlowOfEvents_passesThemThroughUnconverted() {
    runBlocking {
      val result = StreamEventsTool().execute(createDummyContext(), emptyMap())

      assertThat((result as Flow<*>).toList()).containsExactly(mixedEvent)
    }
  }

  @Test
  fun execute_toolReturningAStateFlow_streamsItLikeAFlow() {
    runBlocking {
      val result = StreamStateTool().execute(createDummyContext(), emptyMap())

      assertThat((result as Flow<*>).first()).isEqualTo(mapOf("result" to "ready"))
    }
  }

  @Test
  fun declaration_toolsReturningAFlow_describeOneValueLikeASingleResult() {
    assertThat(StreamToolTool().declaration()!!.response?.properties?.get("result")?.type)
      .isEqualTo(Type.STRING)
    assertThat(
        StreamQuotesTool().declaration()!!.response?.properties?.get("result")?.properties?.keys
      )
      .containsExactly("symbol", "price")
  }

  @Test
  fun declaration_toolsReturningAFlowOfEventsOrAny_declareNoResponse() {
    assertThat(StreamMixedTool().declaration()!!.response).isNull()
    assertThat(StreamEventsTool().declaration()!!.response).isNull()
  }
}

// -- Test Fixtures --

@Tool
fun baseContextTool(context: Context, name: String): String {
  return "${context.agentName}:$name"
}

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

@Tool
fun returnUnitTool() {
  // Returns nothing on purpose: exercises the declaration's handling of a `Unit` function.
}

@Tool fun returnNullableStringTool(): String? = null

data class Noted(val name: String, val note: String?)

@Tool fun returnNullablePropertyTool(): Noted = Noted("a", null)

@Tool fun nullableParamTool(required: String, optional: String?): String = "$required$optional"

data class BoxOfAny(val label: String, val items: List<Any>)

@Tool fun returnBoxOfAnyTool(): BoxOfAny = BoxOfAny("x", listOf(1, "two"))

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

@Tool fun namedParamTool(@Param(name = "wire_name") kotlinName: String): String = "got $kotlinName"

@Tool
fun requiredOverrideTool(@Param(required = Requiredness.REQUIRED) maybe: String?): String =
  "maybe=$maybe"

@Tool
fun optionalOverrideTool(@Param(required = Requiredness.OPTIONAL) note: String?): String =
  "note=$note"

@Tool
fun streamTool(context: ToolContext, symbol: String): Flow<String> =
  flowOf("${context.functionCallId} $symbol 300", "$symbol 400")

@Tool
fun streamMapTool(): Flow<Map<String, Any>> = flowOf(mapOf("price" to 300), mapOf("price" to 400))

data class Quote(val symbol: String, val price: Int)

@Tool fun streamQuotes(): Flow<Quote> = flowOf(Quote("GOOG", 300))

@Tool fun streamMaybe(): Flow<String?> = flowOf(null, "ready")

private val mixedEvent = Event(author = "progress")

@Tool fun streamMixed(): Flow<Any> = flowOf(mixedEvent, "done")

@Tool fun streamEvents(): Flow<Event> = flowOf(mixedEvent)

@Tool fun streamState(): StateFlow<String> = MutableStateFlow("ready")
