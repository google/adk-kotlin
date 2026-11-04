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
import com.google.adk.kt.agents.LiveRequest
import com.google.adk.kt.agents.LiveRequestQueue
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.RunConfig
import com.google.adk.kt.agents.StreamingMode
import com.google.adk.kt.annotations.ExperimentalLiveApi
import com.google.adk.kt.annotations.FrameworkInternalApi
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
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.tools.FunctionTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.Behavior
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.Type
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.asPublisher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.reactivestreams.Publisher

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

  /** Asks to call [toolName] on every turn. */
  private class CallToolModel(private val toolName: String) : Model {
    override val name = "call-tool"

    override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> =
      flowOf(modelFunctionCallResponse(toolName))
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
  fun execute_toolReturningAFlowOfMaps_wrapsEachMapAsAResult() {
    runBlocking {
      val result = FlowMapToolTool().execute(createDummyContext(), emptyMap())

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

      runLiveUntilTurnComplete(agent, queue)

      model.connections.single().assertAllWaitsMet()
    }
  }

  @Test
  fun execute_toolReturningAFlowOfDataClasses_shapesEachLikeASingleResult() {
    runBlocking {
      val result = FlowQuotesTool().execute(createDummyContext(), emptyMap())

      assertThat((result as Flow<*>).toList())
        .containsExactly(mapOf("result" to mapOf("symbol" to "GOOG", "price" to 300)))
    }
  }

  @Test
  fun execute_toolReturningAFlowOfNullableStrings_leavesNullUnwrapped() {
    runBlocking {
      val result = FlowMaybeTool().execute(createDummyContext(), emptyMap())

      assertThat((result as Flow<*>).toList())
        .containsExactly(null, mapOf("result" to "ready"))
        .inOrder()
    }
  }

  @Test
  fun runAsync_generatedToolReturningAFlow_failsTheCall() {
    val agent =
      LlmAgent(name = "agent", model = CallToolModel("flowMaybe"), tools = listOf(FlowMaybeTool()))

    val error =
      assertThrows(IllegalStateException::class.java) {
        runBlocking {
          InMemoryRunner(agent = agent).runAsync("u", "s", newMessage = userMessage("go")).toList()
        }
      }

    assertThat(error)
      .hasMessageThat()
      .isEqualTo("Only a connected live run can stream a tool's Flow; return a value instead.")
  }

  @Test
  fun execute_toolReturningAFlowOfAny_passesItsValuesThroughUnconverted() {
    runBlocking {
      val result = FlowMixedTool().execute(createDummyContext(), emptyMap())

      assertThat((result as Flow<*>).toList()).containsExactly(mixedEvent, "done").inOrder()
    }
  }

  @Test
  fun execute_toolReturningAFlowOfEvents_passesThemThroughUnconverted() {
    runBlocking {
      val result = FlowEventsTool().execute(createDummyContext(), emptyMap())

      assertThat((result as Flow<*>).toList()).containsExactly(mixedEvent)
    }
  }

  @Test
  fun declaration_toolsReturningAFlow_describeOneValueLikeASingleResult() {
    assertThat(StreamToolTool().declaration()!!.response?.properties?.get("result")?.type)
      .isEqualTo(Type.STRING)
    assertThat(
        FlowQuotesTool().declaration()!!.response?.properties?.get("result")?.properties?.keys
      )
      .containsExactly("symbol", "price")
  }

  @Test
  fun declaration_toolsReturningAFlowOfEventsOrAny_declareNoResponse() {
    assertThat(FlowMixedTool().declaration()!!.response).isNull()
    assertThat(FlowEventsTool().declaration()!!.response).isNull()
  }

  @Test
  fun execute_toolReturningAFlowOfNullableDataClasses_leavesNullUnwrapped() {
    runBlocking {
      val result = FlowMaybeQuotesTool().execute(createDummyContext(), emptyMap())

      assertThat((result as Flow<*>).toList())
        .containsExactly(null, mapOf("result" to mapOf("symbol" to "GOOG", "price" to 300)))
        .inOrder()
    }
  }

  @Test
  fun execute_toolReturningAFlowOfFunctionResponses_passesThemThroughUnconverted() {
    runBlocking {
      val result = FlowResponsesTool().execute(createDummyContext(), emptyMap())

      assertThat((result as Flow<*>).toList()).containsExactly(chunkResponse)
    }
  }

  @Test
  fun declaration_toolReturningAFlowOfFunctionResponses_declaresNoResponse() {
    assertThat(FlowResponsesTool().declaration()!!.response).isNull()
  }

  @Test
  fun execute_toolReturningAFlowSubtypeWithAnUnrelatedTypeParameter_wrapsTheFlowsValues() {
    runBlocking {
      val result = KeyedToolTool().execute(createDummyContext(), emptyMap())

      assertThat((result as Flow<*>).toList())
        .containsExactly(mapOf("result" to "a"), mapOf("result" to "b"))
        .inOrder()
    }
  }

  @Test
  fun declaration_toolReturningAFlowSubtypeWithAnUnrelatedTypeParameter_describesTheFlowsValue() {
    assertThat(KeyedToolTool().declaration()!!.response?.properties?.get("result")?.type)
      .isEqualTo(Type.STRING)
  }

  @Test
  fun execute_toolReturningAFlowSubtypeOfLists_convertsEachList() {
    runBlocking {
      val result = PagedQuotesTool().execute(createDummyContext(), emptyMap())

      assertThat((result as Flow<*>).toList())
        .containsExactly(mapOf("result" to listOf(mapOf("symbol" to "GOOG", "price" to 300))))
    }
  }

  @Test
  fun execute_toolReturningAPublisher_shapesEachLikeASingleResult() {
    runBlocking {
      val result = PublishQuotesTool().execute(createDummyContext(), emptyMap())

      assertThat((result as Flow<*>).toList())
        .containsExactly(mapOf("result" to mapOf("symbol" to "GOOG", "price" to 300)))
    }
  }

  @Test
  fun declaration_toolReturningAPublisher_describesOneValueLikeASingleResult() {
    val response = PublishQuotesTool().declaration()?.response

    assertThat(response?.properties?.get("result")?.properties?.keys)
      .containsExactly("symbol", "price")
  }

  @Test
  fun execute_toolReturningAPublisherOfAny_passesItsValuesThroughUnconverted() {
    runBlocking {
      val result = PublishMixedTool().execute(createDummyContext(), emptyMap())

      assertThat((result as Flow<*>).toList()).containsExactly(mixedEvent, "done").inOrder()
    }
  }

  @Test
  fun declaration_toolReturningAPublisherOfAny_declaresNoResponse() {
    assertThat(PublishMixedTool().declaration()?.response).isNull()
  }

  @Test
  fun declaration_toolReturningAPublisherSubtypeWithAnUnrelatedTypeParameter_describesItsValue() {
    assertThat(KeyedPublisherTool().declaration()!!.response?.properties?.get("result")?.type)
      .isEqualTo(Type.STRING)
  }

  @OptIn(FrameworkInternalApi::class)
  @Test
  fun generate_toolReturningAFlowOrAPublisher_isStreaming() {
    assertThat(StreamToolTool().isStreaming).isTrue()
    assertThat(PublishQuotesTool().isStreaming).isTrue()
  }

  @OptIn(FrameworkInternalApi::class)
  @Test
  fun generate_toolReturningAValue_isNotStreaming() {
    assertThat(NamedParamToolTool().isStreaming).isFalse()
  }

  @OptIn(ExperimentalLiveApi::class)
  @Test
  fun generate_toolWithABehavior_overridesIt() {
    assertThat(SlowTaskTool().behavior).isEqualTo(Behavior.NON_BLOCKING)
    assertThat(QuickTaskTool().behavior).isEqualTo(Behavior.BLOCKING)
    assertThat(NamedParamToolTool().behavior).isNull()
  }

  @OptIn(ExperimentalLiveApi::class)
  @Test
  fun runLive_generatedToolWithABehavior_isDeclaredWithIt() {
    runBlocking {
      val model = DummyLiveModel(listOf(Respond(LlmResponse(turnComplete = true))))
      val tools = listOf(SlowTaskTool(), QuickTaskTool(), NamedParamToolTool())
      val queue = LiveRequestQueue()
      queue.close()

      val unused =
        InMemoryRunner(agent = LlmAgent(name = "agent", model = model, tools = tools))
          .runLive("u", "s", queue)
          .toList()

      val declarations =
        model.connectRequests.single().config.tools.orEmpty().flatMap {
          it.functionDeclarations.orEmpty()
        }
      assertThat(declarations.associate { it.name to it.behavior })
        .isEqualTo(
          mapOf(
            "slowTask" to Behavior.NON_BLOCKING,
            "quickTask" to Behavior.BLOCKING,
            "namedParamTool" to null,
          )
        )
    }
  }

  @OptIn(ExperimentalLiveApi::class)
  @Test
  fun runLive_generatedToolReturningAStream_isDeclaredNonBlocking() {
    runBlocking {
      val model = DummyLiveModel(listOf(Respond(LlmResponse(turnComplete = true))))
      val tools = listOf(StreamToolTool(), PublishQuotesTool(), NamedParamToolTool())
      val queue = LiveRequestQueue()
      queue.close()

      val unused =
        InMemoryRunner(agent = LlmAgent(name = "agent", model = model, tools = tools))
          .runLive("u", "s", queue)
          .toList()

      val declarations =
        model.connectRequests.single().config.tools.orEmpty().flatMap {
          it.functionDeclarations.orEmpty()
        }
      assertThat(declarations.associate { it.name to it.behavior })
        .isEqualTo(
          mapOf(
            "streamTool" to Behavior.NON_BLOCKING,
            "publishQuotes" to Behavior.NON_BLOCKING,
            "namedParamTool" to null,
          )
        )
    }
  }

  @Test
  fun declaration_toolTakingTheLiveInput_leavesItOutOfTheParameters() {
    val tool = EchoLiveInputTool()

    assertThat(tool.declaration()?.parameters?.properties.orEmpty()).doesNotContainKey("input")
  }

  @Test
  fun declaration_toolTakingTheLiveInputAsAnOutProjection_leavesItOutOfTheParameters() {
    assertThat(EchoOutLiveInputTool().declaration()?.parameters?.properties.orEmpty())
      .doesNotContainKey("input")
  }

  @Test
  fun execute_toolTakingTheLiveInputOutsideALiveRun_endsAtOnce() {
    runBlocking {
      val result = EchoLiveInputTool().execute(createDummyContext(), emptyMap())

      assertThat((result as Flow<*>).toList()).isEmpty()
    }
  }

  companion object {
    /** Matches a message that answers call [id] with exactly [response]. */
    @OptIn(ExperimentalLiveApi::class)
    private fun answers(id: String, response: Map<String, Any?>): (Any) -> Boolean = { message ->
      message is ContentInput &&
        message.content.parts.any {
          it.functionResponse?.id == id && it.functionResponse?.response == response
        }
    }

    /** Runs [agent] live and closes [queue] at the model's first complete turn, ending the run. */
    @OptIn(ExperimentalLiveApi::class)
    private suspend fun runLiveUntilTurnComplete(agent: LlmAgent, queue: LiveRequestQueue) {
      InMemoryRunner(agent = agent)
        .runLive("u", "s", queue, RunConfig(streamingMode = StreamingMode.BIDI))
        .onEach { if (it.turnComplete) queue.close() }
        .collect()
    }
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
internal fun streamTool(context: ToolContext, symbol: String): Flow<String> =
  flowOf("${context.functionCallId} $symbol 300", "$symbol 400")

/** Declared non-blocking in a live run. */
@Tool(behavior = Behavior.NON_BLOCKING) internal fun slowTask(): String = "done"

/** Declared blocking in a live run. */
@Tool(behavior = Behavior.BLOCKING) internal fun quickTask(): String = "done"

@Tool
internal fun flowMapTool(): Flow<Map<String, Any>> =
  flowOf(mapOf("price" to 300), mapOf("price" to 400))

internal data class Quote(val symbol: String, val price: Int)

@Tool internal fun flowQuotes(): Flow<Quote> = flowOf(Quote("GOOG", 300))

@Tool internal fun flowMaybe(): Flow<String?> = flowOf(null, "ready")

private val mixedEvent = Event(author = "flowMixed")

@Tool internal fun flowMixed(): Flow<Any> = flowOf(mixedEvent, "done")

@Tool internal fun flowEvents(): Flow<Event> = flowOf(mixedEvent)

@Tool internal fun flowMaybeQuotes(): Flow<Quote?> = flowOf(null, Quote("GOOG", 300))

private val chunkResponse =
  FunctionResponse(name = "flowResponses", response = mapOf("price" to 300))

@Tool internal fun flowResponses(): Flow<FunctionResponse> = flowOf(chunkResponse)

internal class Keyed<K>(private val values: List<String>) : Flow<String> {
  override suspend fun collect(collector: FlowCollector<String>) {
    for (value in values) collector.emit(value)
  }
}

@Tool internal fun keyedTool(): Keyed<Int> = Keyed(listOf("a", "b"))

internal class Paged<T>(private val pages: List<List<T>>) : Flow<List<T>> {
  override suspend fun collect(collector: FlowCollector<List<T>>) {
    for (page in pages) collector.emit(page)
  }
}

@Tool internal fun pagedQuotes(): Paged<Quote> = Paged(listOf(listOf(Quote("GOOG", 300))))

@Tool internal fun publishQuotes(): Publisher<Quote> = flowOf(Quote("GOOG", 300)).asPublisher()

@Tool internal fun publishMixed(): Publisher<Any> = flowOf<Any>(mixedEvent, "done").asPublisher()

internal class KeyedPublisher<K>(vararg values: String) :
  Publisher<String> by flowOf(*values).asPublisher()

@Tool internal fun keyedPublisher(): KeyedPublisher<Int> = KeyedPublisher("a", "b")

@OptIn(ExperimentalLiveApi::class)
@Tool
internal fun echoLiveInput(input: Publisher<LiveRequest>): Flow<String> =
  input.asFlow().map { "heard" }

@OptIn(ExperimentalLiveApi::class)
@Tool
internal fun echoOutLiveInput(input: Publisher<out LiveRequest>): Flow<String> =
  input.asFlow().map { "heard" }
