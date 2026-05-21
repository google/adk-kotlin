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

import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.modelFunctionCallResponse
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.FunctionTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.toGenaiSdk
import com.google.common.truth.Truth.assertThat
import com.google.genai.types.Content as GenAiContent
import com.google.gson.JsonParser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * End-to-end serialization tests for what `Gemini` actually puts on the wire when a [FunctionTool]
 * returns a value.
 *
 * Pipeline exercised, top to bottom:
 * 1. A [FunctionTool] subclass returns `mapOf(BaseTool.RESULT_KEY to <value>)` directly. This
 *    mirrors the shape that the KSP `FunctionToolGenerator` emits for `@Tool`-annotated functions,
 *    so the test exercises the same runtime path the generated code would take -- without depending
 *    on KSP being wired into the build (Blaze or Gradle).
 * 2. [com.google.adk.kt.agents.InvocationContext.executeSingleFunctionCall] passes the payload
 *    through to a [com.google.adk.kt.types.FunctionResponse] whose `response: Map<String, Any?>`
 *    carries the serialized payload as plain Kotlin types (Int, String, List, Map of String to
 *    Any?, ...).
 * 3. On the next turn the runner re-invokes the model with that response in `LlmRequest.contents`.
 * 4. [com.google.adk.kt.models.Gemini.generateContent] converts each ADK [Content] to
 *    `com.google.genai.types.Content` via the internal `toGenaiSdk()` extension, which forwards the
 *    raw response map straight to the GenAI SDK builder.
 * 5. The GenAI SDK serializes the `Content` to JSON via the same Jackson `ObjectMapper` that its
 *    own `Models` class uses to put bytes on the wire (`JsonSerializable.objectMapper()`, exposed
 *    through `genaiContent.toJson()`).
 *
 * These tests intercept the second `LlmRequest`, replay the conversion of step 4 and the JSON
 * serialization of step 5 directly through the GenAI SDK's own `toJson()` (deliberately bypassing
 * ADK's `Json.toJsonString` Gson wrapper so the assertions are tied to what the SDK -- not ADK --
 * actually emits), and assert on the exact JSON Gemini would receive. They serve as regression
 * coverage for any future change in the [FunctionTool.execute] contract, the ADK-to-GenAI
 * converters, or the GenAI SDK's JSON encoding.
 */
@RunWith(JUnit4::class)
class FunctionToolWireFormatTest {

  @Test
  fun toolReturningInt_emitsJsonNumberInResponseMap() = runTest {
    assertFunctionResponseJsonEquals(
      tool = wireFormatTool("returnsInt") { 42 },
      expected = """{"id":"returnsInt-call","name":"returnsInt","response":{"result":42}}""",
    )
  }

  @Test
  fun toolReturningString_emitsJsonStringInResponseMap() = runTest {
    assertFunctionResponseJsonEquals(
      tool = wireFormatTool("returnsString") { "hello" },
      expected =
        """{"id":"returnsString-call","name":"returnsString","response":{"result":"hello"}}""",
    )
  }

  @Test
  fun toolReturningBoolean_emitsJsonBooleanInResponseMap() = runTest {
    assertFunctionResponseJsonEquals(
      tool = wireFormatTool("returnsBoolean") { true },
      expected =
        """{"id":"returnsBoolean-call","name":"returnsBoolean","response":{"result":true}}""",
    )
  }

  @Test
  fun toolReturningDouble_emitsJsonNumberInResponseMap() = runTest {
    assertFunctionResponseJsonEquals(
      tool = wireFormatTool("returnsDouble") { 3.14 },
      expected = """{"id":"returnsDouble-call","name":"returnsDouble","response":{"result":3.14}}""",
    )
  }

  @Test
  fun toolReturningListOfInts_emitsJsonArrayOfNumbersInResponseMap() = runTest {
    assertFunctionResponseJsonEquals(
      tool = wireFormatTool("returnsListOfInts") { listOf(1, 2, 3) },
      expected =
        """{"id":"returnsListOfInts-call","name":"returnsListOfInts","response":{"result":[1,2,3]}}""",
    )
  }

  /**
   * A tool whose direct return value is a `Map<String, Int>` -- not wrapped in a data class.
   * Verifies that map entries flatten into a JSON object whose values stay as JSON numbers (not
   * strings, not Doubles), and that the runtime nests the whole map under the conventional `result`
   * key (as required by the FunctionResponse spec, which mandates a dict).
   */
  @Test
  fun toolReturningMapOfStringToInt_emitsNestedJsonObjectOfNumbers() = runTest {
    assertFunctionResponseJsonEquals(
      tool =
        wireFormatTool("returnsScoreboard") { mapOf("alice" to 100, "bob" to 87, "carol" to 42) },
      expected =
        """
        {
          "id": "returnsScoreboard-call",
          "name": "returnsScoreboard",
          "response": {"result": {"alice": 100, "bob": 87, "carol": 42}}
        }
        """
          .trimIndent(),
    )
  }

  @Test
  fun toolReturningUnit_emitsEmptyResponseMap() = runTest {
    // Unit-returning tools propagate the `Unit` singleton; for non-long-running tools the
    // framework coerces it to an empty map so the wire form is `{}` (mirroring the Unit-branch
    // of FunctionToolGenerator's emission template).
    val unitTool =
      object : FunctionTool(name = "returnsUnit", description = "wire-format unit tool") {
        override fun declaration() = null

        override suspend fun execute(context: ToolContext, args: Map<String, Any>): Any = Unit
      }
    assertFunctionResponseJsonEquals(
      tool = unitTool,
      expected = """{"id":"returnsUnit-call","name":"returnsUnit","response":{}}""",
    )
  }

  /**
   * When the LLM calls a tool with a missing required argument, KSP-generated [FunctionTool]
   * subclasses (and conventional hand-written ones) return `mapOf(ERROR_KEY to "<message>")`
   * directly (mirroring the confirmation-rejection convention from [FunctionTool.ERROR_KEY]) so
   * Gemini sees a plain `{"error": "..."}` payload.
   */
  @Test
  fun toolCalledWithMissingRequiredArg_emitsErrorMap() = runTest {
    // Hand-written tool that performs the same required-arg validation the KSP-generated code
    // would do for `fun requiresName(name: String)`: when the LLM omits `name`, return an error
    // map directly.
    val requiresNameTool =
      object : FunctionTool(name = "requiresName", description = "wire-format required-arg tool") {
        override fun declaration() = null

        override suspend fun execute(context: ToolContext, args: Map<String, Any>): Any {
          if ("name" !in args) {
            return mapOf(FunctionTool.ERROR_KEY to "Missing required parameter name")
          }
          return mapOf(BaseTool.RESULT_KEY to "hello, ${args["name"]}")
        }
      }
    assertFunctionResponseJsonEquals(
      tool = requiresNameTool,
      expected =
        """
        {
          "id": "requiresName-call",
          "name": "requiresName",
          "response": {"error": "Missing required parameter name"}
        }
        """
          .trimIndent(),
    )
  }

  /**
   * Uber test: a tool returning a deeply-nested payload mixing primitives, an enum-like string, a
   * list of nested maps, and a map. Verifies the wire JSON Gemini receives faithfully reflects the
   * payload structure with no Kotlin-specific wrapper artifacts on the wire.
   *
   * Note that null fields (here: `label`) are *omitted* from the wire JSON: the GenAI SDK's
   * `toJson()` strips nulls. Gemini therefore never sees a `label: null` entry; the field is just
   * absent.
   *
   * This mirrors the shape the KSP `FunctionToolGenerator` would emit for a `@Tool` returning a
   * nested data class, which it flattens recursively into nested `Map<String, Any?>`.
   */
  @Test
  fun toolReturningNestedMap_emitsRecursivelyFlattenedJsonStructure() = runTest {
    val kitchenSink: Map<String, Any?> =
      mapOf(
        "name" to "the answer",
        "value" to 42,
        "ratio" to 0.5,
        "active" to true,
        "label" to null,
        "status" to "READY",
        "tags" to listOf("alpha", "beta"),
        "items" to
          listOf(mapOf("id" to 1, "label" to "first"), mapOf("id" to 2, "label" to "second")),
        "scores" to mapOf("alice" to 100, "bob" to 87),
      )
    assertFunctionResponseJsonEquals(
      tool = wireFormatTool("returnsKitchenSink") { kitchenSink },
      expected =
        """
        {
          "id": "returnsKitchenSink-call",
          "name": "returnsKitchenSink",
          "response": {
            "result": {
              "name": "the answer",
              "value": 42,
              "ratio": 0.5,
              "active": true,
              "status": "READY",
              "tags": ["alpha", "beta"],
              "items": [
                {"id": 1, "label": "first"},
                {"id": 2, "label": "second"}
              ],
              "scores": {"alice": 100, "bob": 87}
            }
          }
        }
        """
          .trimIndent(),
    )
  }

  // -- Helpers -----------------------------------------------------------------------------------

  /**
   * Builds a hand-written [FunctionTool] whose `execute` returns `mapOf(RESULT_KEY to
   * resultProvider())` -- byte-for-byte the shape `FunctionToolGenerator` emits for a `@Tool`
   * function with a non-Unit return type. [resultProvider] is invoked once per tool call and
   * supplies the raw value that gets nested under `result`.
   */
  private fun wireFormatTool(name: String, resultProvider: () -> Any?): FunctionTool =
    object : FunctionTool(name = name, description = "wire-format test tool") {
      override fun declaration() = null

      override suspend fun execute(context: ToolContext, args: Map<String, Any>): Any =
        mapOf(BaseTool.RESULT_KEY to resultProvider())
    }

  /**
   * Asserts that the JSON Gemini receives for the `functionResponse` part of the response turn for
   * [tool] is structurally equal to [expected].
   *
   * Comparison uses Gson's [JsonParser] so it is whitespace- and key-order-insensitive but
   * preserves number types (Int vs Double): `42` is not equal to `42.0`. This is essential because
   * the whole point of these tests is to verify that primitives stay as primitives all the way to
   * the wire. The choice of parser here is irrelevant to what the test verifies -- only the
   * production serialization (the GenAI SDK's Jackson `ObjectMapper`, exercised in
   * [serializedFunctionResponseJsonFor]) is under test; [JsonParser] is just a comparison utility
   * for two already-serialized JSON strings.
   */
  private suspend fun assertFunctionResponseJsonEquals(tool: FunctionTool, expected: String) {
    val actual = serializedFunctionResponseJsonFor(tool)
    assertThat(JsonParser.parseString(actual)).isEqualTo(JsonParser.parseString(expected))
  }

  /**
   * Drives an [InMemoryRunner] for a single call to [tool] and returns the raw JSON substring
   * corresponding to `parts[0].functionResponse` in the [Content] Gemini would receive on the
   * second turn.
   *
   * The model is scripted to:
   * - Turn 1: emit a [com.google.adk.kt.types.FunctionCall] for [tool].name.
   * - Turn 2: emit a final text response. The runner forwards the captured tool response in this
   *   turn's `LlmRequest.contents`.
   *
   * This helper captures every `LlmRequest` the model receives, locates the user-role [Content]
   * carrying the [com.google.adk.kt.types.FunctionResponse] for [tool].name, converts it through
   * the same `toGenaiSdk()` path `Gemini.generateContent` uses, and serializes the resulting
   * [GenAiContent] via its own `toJson()` -- which delegates to `JsonSerializable.objectMapper()`,
   * the very same Jackson `ObjectMapper` instance the GenAI SDK's `Models` class uses to put bytes
   * on the wire. ADK's `Json.toJsonString` Gson wrapper is intentionally not used here: it would
   * produce equivalent output for `JsonSerializable` inputs (because of its `JsonSerializable`
   * type-hierarchy adapter that forwards back to `toJson()`), but going straight through the SDK
   * removes that indirection so the assertions are tied to what the SDK -- not ADK -- emits. The
   * full `Content` JSON looks like:
   * ```
   * {"parts":[{"functionResponse":{"name":"...","response":{...}}}],"role":"user"}
   * ```
   *
   * We then pull out the `parts[0].functionResponse` JSON object so each test can assert on just
   * the function-response slice.
   */
  private suspend fun serializedFunctionResponseJsonFor(tool: FunctionTool): String {
    val toolName = tool.name
    val capturedRequests = mutableListOf<LlmRequest>()
    val scripted =
      DummyModel.createSequential(
        "scripted",
        listOf(
          modelFunctionCallResponse(toolName, id = "$toolName-call"),
          LlmResponse(content = modelMessage("done")),
        ),
      )
    val capturingModel =
      object : Model {
        override val name = scripted.name

        override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> =
          flow {
            capturedRequests += request
            emitAll(scripted.generateContent(request, stream))
          }
      }
    val agent =
      com.google.adk.kt.agents.LlmAgent(
        name = "test-agent",
        model = capturingModel,
        tools = listOf(tool),
      )
    val runner = InMemoryRunner(agent = agent)
    runner
      .runAsync(userId = "user", sessionId = "session", newMessage = userMessage("call $toolName"))
      .toList()

    // Turn 1 sees only the user message; turn 2 sees the user message + model's function call +
    // the user-role function response. We want the function response from turn 2.
    val secondRequest =
      checkNotNull(capturedRequests.getOrNull(1)) {
        "expected a second LlmRequest carrying the function response, got ${capturedRequests.size}"
      }
    val responseContent = functionResponseContent(secondRequest.contents, toolName)
    val genaiContent: GenAiContent = responseContent.toGenaiSdk()
    // Use the GenAI SDK's own JSON entry point (Jackson via JsonSerializable.objectMapper()) so
    // the assertion is anchored to what `Models.generateContent` actually puts on the wire.
    val fullContentJson = genaiContent.toJson()

    val tree = JsonParser.parseString(fullContentJson).asJsonObject
    val functionResponse =
      tree.getAsJsonArray("parts").get(0).asJsonObject.getAsJsonObject("functionResponse")
    return functionResponse.toString()
  }

  /**
   * Returns the single [Content] in [contents] that carries a
   * [com.google.adk.kt.types.FunctionResponse] for [toolName]. Fails the test (via [checkNotNull])
   * if there's no match.
   */
  private fun functionResponseContent(contents: List<Content>, toolName: String): Content =
    checkNotNull(
      contents.firstOrNull { content ->
        content.parts.any { part -> part.functionResponse?.name == toolName }
      }
    ) {
      "expected a Content carrying a FunctionResponse for '$toolName' in $contents"
    }
}
