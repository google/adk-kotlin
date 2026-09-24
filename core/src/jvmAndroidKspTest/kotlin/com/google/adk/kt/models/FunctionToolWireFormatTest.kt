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

package com.google.adk.kt.models

import com.google.adk.kt.agents.BaseAgent
import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.events.Event
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.tools.FunctionTool
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.toGenaiSdk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Verifies the JSON wire format of the `functionResponse` Gemini receives for a [FunctionTool]'s
 * return value, using real `@Tool` functions whose `FunctionTool` subclasses are KSP-generated.
 * Each calls the tool through the runtime's function-call path, converts the response via
 * `toGenaiSdk`, and asserts the payload.
 *
 * The checked payload is the converted `response` map (`Map<String, JsonElement>`) -- exactly what
 * the SDK puts on the wire -- so primitives keep their JSON type and `null` entries survive as JSON
 * `null`, verified without a live model call.
 */
class FunctionToolWireFormatTest {

  @Test
  fun toolReturningInt_emitsJsonNumberInResponseMap() = runTest {
    assertWireResponse(ReturnsIntTool(), expected = """{"result":42}""")
  }

  @Test
  fun toolReturningString_emitsJsonStringInResponseMap() = runTest {
    assertWireResponse(ReturnsStringTool(), expected = """{"result":"hello"}""")
  }

  @Test
  fun toolReturningBoolean_emitsJsonBooleanInResponseMap() = runTest {
    assertWireResponse(ReturnsBooleanTool(), expected = """{"result":true}""")
  }

  @Test
  fun toolReturningDouble_emitsJsonNumberInResponseMap() = runTest {
    assertWireResponse(ReturnsDoubleTool(), expected = """{"result":3.14}""")
  }

  @Test
  fun toolReturningListOfInts_emitsJsonArrayOfNumbersInResponseMap() = runTest {
    assertWireResponse(ReturnsListOfIntsTool(), expected = """{"result":[1,2,3]}""")
  }

  @Test
  fun toolReturningMapOfStringToInt_emitsNestedJsonObjectOfNumbers() = runTest {
    assertWireResponse(
      ReturnsScoreboardTool(),
      expected = """{"result":{"alice":100,"bob":87,"carol":42}}""",
    )
  }

  @Test
  fun toolReturningDataClass_emitsJsonObjectInResponseMap() = runTest {
    // The processor decomposes a data-class return field-by-field into a JSON object.
    assertWireResponse(
      ReturnsPlayerTool(),
      expected = """{"result":{"name":"alice","score":100}}""",
    )
  }

  @Test
  fun toolReturningNestedDataClass_emitsRecursivelyDecomposedJsonObject() = runTest {
    // A data class with a nested data class, a list, and a map is decomposed recursively.
    assertWireResponse(
      ReturnsProfileTool(),
      expected =
        """
        {
          "result": {
            "name": "alice",
            "address": {"city": "NYC", "zip": "10001"},
            "tags": ["a", "b"],
            "scores": {"x": 1, "y": 2}
          }
        }
        """
          .trimIndent(),
    )
  }

  @Test
  fun toolReturningUnit_emitsNullResult() = runTest {
    // The generated tool returns the `Unit` singleton; the runtime answers a regular tool's `Unit`
    // with a `null` result, as Python does for a tool returning `None`.
    assertWireResponse(ReturnsUnitTool(), expected = """{"result":null}""")
  }

  @Test
  fun toolCalledWithMissingRequiredArg_emitsErrorMap() = runTest {
    // The generated tool validates required parameters; when the LLM omits `name` it returns an
    // `error` payload directly (no `result` wrapper).
    assertWireResponse(
      RequiresNameTool(),
      args = emptyMap(),
      expected = """{"error":"Missing required parameter name"}""",
    )
  }

  @Test
  fun toolCalledWithRequiredArg_emitsResultInResponseMap() = runTest {
    // Supplying the required `name` exercises the tool body and yields a normal `result` payload.
    assertWireResponse(
      RequiresNameTool(),
      args = mapOf("name" to "world"),
      expected = """{"result":"hello, world"}""",
    )
  }

  @Test
  fun toolReturningMapStringAny_mixedValueTypes_emitsJsonObjectPreservingNativeTypes() = runTest {
    assertWireResponse(
      ReturnsStockPriceTool(),
      expected = """{"result":{"symbol":"GOOG","price":123.45,"volume":1000}}""",
    )
  }

  @Test
  fun toolReturningListAny_mixedElementTypes_emitsJsonArrayPreservingNativeTypes() = runTest {
    assertWireResponse(ReturnsStockHistoryTool(), expected = """{"result":["GOOG",123.45,1000]}""")
  }

  @Test
  fun toolReturningDeeplyNestedMapStringAny_mapListMap_emitsNestedJson() = runTest {
    assertWireResponse(
      ReturnsNestedDataTool(),
      expected =
        """
        {
          "result": {
            "outer": {
              "middle": [
                {"leaf": 1, "label": "first"},
                {"leaf": 2, "label": "second"}
              ],
              "scalar": "value"
            }
          }
        }
        """
          .trimIndent(),
    )
  }

  @Test
  fun toolReturningNestedMapWithNulls_emitsRecursivelyFlattenedJsonWithNullsPreserved() = runTest {
    // A `null` entry (here: `label`) reaches the wire as JSON `null`, so Gemini can tell it apart
    // from a field the tool omitted entirely.
    assertWireResponse(
      ReturnsKitchenSinkTool(),
      expected =
        """
        {
          "result": {
            "name": "the answer",
            "value": 42,
            "ratio": 0.5,
            "active": true,
            "label": null,
            "status": "READY",
            "tags": ["alpha", "beta"],
            "items": [
              {"id": 1, "label": "first"},
              {"id": 2, "label": "second"}
            ],
            "scores": {"alice": 100, "bob": 87}
          }
        }
        """
          .trimIndent(),
    )
  }

  // -- Helpers -----------------------------------------------------------------------------------

  /**
   * Calls [tool] with [args] through the runtime's function-call path, converts the resulting
   * function response through the real ADK -> GenAI SDK converter, and asserts the converted
   * `functionResponse` (name, id, and the `JsonElement` response map) matches [expected].
   */
  private suspend fun assertWireResponse(
    tool: FunctionTool,
    args: Map<String, Any> = emptyMap(),
    expected: String,
  ) {
    val event =
      checkNotNull(
        dummyInvocationContext()
          .executeSingleFunctionCall(
            FunctionCall(name = tool.name, args = args, id = "${tool.name}-call"),
            mapOf(tool.name to tool),
          )
      )
    val genaiContent = checkNotNull(event.content).toGenaiSdk()

    val functionResponse = genaiContent.parts!!.single().functionResponse!!
    assertEquals(tool.name, functionResponse.name)
    assertEquals("${tool.name}-call", functionResponse.id)
    assertEquals(
      Json.parseToJsonElement(expected),
      JsonObject(functionResponse.response ?: emptyMap()),
    )
  }

  private fun dummyInvocationContext(): InvocationContext =
    InvocationContext(
      session = Session(key = SessionKey("app", "user", "session")),
      runConfig = null,
      agent = NoOpAgent(),
    )

  private class NoOpAgent : BaseAgent(name = "wire-format-test-agent") {
    override fun runAsyncImpl(context: InvocationContext): Flow<Event> = flow {}
  }
}
