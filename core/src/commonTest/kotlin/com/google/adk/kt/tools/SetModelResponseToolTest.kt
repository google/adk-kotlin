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
package com.google.adk.kt.tools

import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.DummyTool
import com.google.adk.kt.testing.modelFunctionCallResponse
import com.google.adk.kt.testing.testToolContext
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

class SetModelResponseToolTest {

  private val outputSchema =
    Schema(
      type = Type.OBJECT,
      properties =
        mapOf("name" to Schema(type = Type.STRING), "city" to Schema(type = Type.STRING)),
      required = listOf("name"),
    )

  @Test
  fun declaration_exposesOutputSchemaAsParameters() {
    val declaration = SetModelResponseTool(outputSchema).declaration()

    assertEquals(SetModelResponseTool.NAME, declaration.name)
    assertEquals(outputSchema, declaration.parameters)
  }

  @Test
  fun run_validArgs_returnsArgsUnchanged() = runBlocking {
    val tool = SetModelResponseTool(outputSchema)
    val args = mapOf<String, Any>("name" to "John", "city" to "NYC")

    val result = tool.run(testToolContext(), args)

    assertEquals(args, result)
  }

  @Test
  fun run_argsMissingRequiredField_returnsFeedbackAndRecordsNothing() = runBlocking {
    val tool = SetModelResponseTool(outputSchema)

    val result = tool.run(testToolContext(functionCallId = "call_1"), mapOf("city" to "NYC"))

    assertThat(result)
      .isEqualTo(
        mapOf(
          "error" to
            "Validation Error found:\nOutput args does not contain required name\nRecall the " +
              "set_model_response function correctly, fix the errors, and call it again with " +
              "all required fields using the correct types."
        )
      )
    assertThat(tool.validatedResponseJson("call_1")).isNull()
  }

  @Test
  fun run_argsWithUnknownField_returnsFeedbackWithoutTheSchemaDump() = runBlocking {
    val tool = SetModelResponseTool(outputSchema)

    val result = tool.run(testToolContext(), mapOf("name" to "John", "unknown" to "x"))

    assertThat(result["error"] as String)
      .startsWith("Validation Error found:\nOutput arg: unknown doesn't exist in output schema\n")
  }

  @Test
  fun run_nestedMismatch_feedbackNamesTheNestedCause() = runBlocking {
    val schema =
      Schema(
        type = Type.OBJECT,
        properties =
          mapOf(
            "result" to
              Schema(
                type = Type.OBJECT,
                properties = mapOf("answer" to Schema(type = Type.STRING)),
                required = listOf("answer"),
              )
          ),
      )
    val tool = SetModelResponseTool(schema)

    val result = tool.run(testToolContext(), mapOf("result" to emptyMap<String, Any?>()))

    assertThat(result["error"] as String)
      .startsWith(
        "Validation Error found:\nOutput arg: result type does not match output schema\n" +
          "Output args does not contain required answer\n"
      )
  }

  @Test
  fun run_nonObjectSchema_throws() =
    runBlocking<Unit> {
      val tool = SetModelResponseTool(Schema(type = Type.ARRAY, items = Schema(type = Type.STRING)))

      assertFailsWith<IllegalArgumentException> { tool.run(testToolContext(), mapOf("x" to "y")) }
    }

  @Test
  fun run_schemaTooComplexToValidate_throws() =
    runBlocking<Unit> {
      // Each level reuses one child on both branches, so validation gives up.
      var nested = Schema(type = Type.STRING)
      repeat(24) { nested = Schema(anyOf = listOf(nested, nested)) }
      val tool = SetModelResponseTool(Schema(type = Type.OBJECT, properties = mapOf("x" to nested)))

      val failure =
        assertFailsWith<IllegalArgumentException> { tool.run(testToolContext(), mapOf("x" to 42)) }
      assertThat(failure).hasMessageThat().contains("too complex to validate")
    }

  @Test
  fun run_rawArgsTooComplexToValidate_returnsTheFixableError() =
    runBlocking<Unit> {
      // Arrange: without the null `x`, `y` fails as a type mismatch; with it, validation gives up.
      var nested = Schema(type = Type.STRING)
      repeat(24) { nested = Schema(anyOf = listOf(nested, nested)) }
      val schema =
        Schema(
          type = Type.OBJECT,
          properties = mapOf("x" to nested, "y" to Schema(type = Type.STRING)),
        )
      val tool = SetModelResponseTool(schema)

      // Act
      val result = tool.run(testToolContext(), mapOf("x" to null, "y" to 5))

      // Assert: the model can fix `y`, so it gets that error instead of a throw.
      assertThat(result["error"] as String).contains("y")
      assertThat(result["error"] as String).doesNotContain("too complex")
    }

  @Test
  fun run_topLevelUnionOfObjectsMatchingNoBranch_returnsAnError() = runBlocking {
    // Arrange: the model can fix arguments that match neither object.
    val withA =
      Schema(
        type = Type.OBJECT,
        properties = mapOf("a" to Schema(type = Type.STRING)),
        required = listOf("a"),
      )
    val withB =
      Schema(
        type = Type.OBJECT,
        properties = mapOf("b" to Schema(type = Type.STRING)),
        required = listOf("b"),
      )
    val tool = SetModelResponseTool(Schema(anyOf = listOf(withA, withB)))

    // Act
    val result = tool.run(testToolContext(), mapOf("c" to "x"))

    // Assert
    assertThat(result["error"] as String).startsWith("Validation Error found:")
  }

  @Test
  fun run_untypedObjectSchemaMissingARequiredField_returnsAnError() = runBlocking {
    // Arrange: a schema with properties but no type describes an object.
    val schema =
      Schema(
        properties = mapOf("answer" to Schema(type = Type.STRING)),
        required = listOf("answer"),
      )
    val tool = SetModelResponseTool(schema)

    // Act
    val result = tool.run(testToolContext(), emptyMap())

    // Assert
    assertThat(result["error"] as String).contains("required answer")
  }

  @Test
  fun run_nullOptionalFields_dropsThemAtEveryDepth() = runBlocking {
    val schema =
      Schema(
        type = Type.OBJECT,
        properties =
          mapOf(
            "result" to
              Schema(
                type = Type.OBJECT,
                properties =
                  mapOf(
                    "answer" to Schema(type = Type.STRING),
                    "note" to Schema(type = Type.STRING),
                  ),
              ),
            "error" to Schema(type = Type.OBJECT),
          ),
      )
    val tool = SetModelResponseTool(schema)

    val result =
      tool.run(
        testToolContext(),
        mapOf("result" to mapOf("answer" to "42", "note" to null), "error" to null),
      )

    assertThat(result).isEqualTo(mapOf("result" to mapOf("answer" to "42")))
  }

  @Test
  fun run_validArgsWithOptionalNull_recordsThemWithoutIt() = runBlocking {
    // The nullable `note` lets the arguments pass as sent.
    val schema =
      Schema(
        type = Type.OBJECT,
        properties =
          mapOf(
            "answer" to Schema(type = Type.STRING),
            "note" to Schema(type = Type.STRING, nullable = true),
          ),
        required = listOf("answer"),
      )
    val tool = SetModelResponseTool(schema)

    val result =
      tool.run(testToolContext(functionCallId = "call_1"), mapOf("answer" to "42", "note" to null))

    assertThat(result).isEqualTo(mapOf("answer" to "42"))
    assertThat(tool.validatedResponseJson("call_1")).isEqualTo("""{"answer":"42"}""")
  }

  @Test
  fun run_nullThatOnlyTheRawArgsSatisfy_recordsTheRawArgs() = runBlocking {
    // Dropping the optional null `a` would leave a value that matches neither union branch.
    val schema =
      Schema(
        type = Type.OBJECT,
        properties =
          mapOf(
            "kind" to Schema(type = Type.STRING),
            "a" to Schema(type = Type.STRING, nullable = true),
            "b" to Schema(type = Type.STRING),
          ),
        anyOf =
          listOf(Schema(required = listOf("kind", "a")), Schema(required = listOf("kind", "b"))),
      )
    val tool = SetModelResponseTool(schema)

    val result = tool.run(testToolContext(), mapOf("kind" to "x", "a" to null))

    assertThat(result).isEqualTo(mapOf("kind" to "x", "a" to null))
  }

  @Test
  fun run_nullRequiredNullableField_keepsIt() = runBlocking {
    val schema =
      Schema(
        type = Type.OBJECT,
        properties = mapOf("answer" to Schema(type = Type.STRING, nullable = true)),
        required = listOf("answer"),
      )
    val tool = SetModelResponseTool(schema)

    val result = tool.run(testToolContext(), mapOf("answer" to null))

    assertThat(result).isEqualTo(mapOf("answer" to null))
  }

  @Test
  fun run_nullRequiredFieldThatAcceptsNull_keepsIt() = runBlocking {
    // Neither field is marked nullable, but each accepts null: one is untyped, one a union.
    val schema =
      Schema(
        type = Type.OBJECT,
        properties =
          mapOf(
            "untyped" to Schema(),
            "union" to Schema(anyOf = listOf(Schema(type = Type.STRING), Schema(type = Type.NULL))),
          ),
        required = listOf("untyped", "union"),
      )
    val tool = SetModelResponseTool(schema)

    val result = tool.run(testToolContext(), mapOf("untyped" to null, "union" to null))

    assertThat(result).isEqualTo(mapOf("untyped" to null, "union" to null))
  }

  @Test
  fun run_nullThatANestedUnionBranchRequires_keepsIt() = runBlocking {
    // Only the union's object branch requires `a`; the null `error` rules out the raw arguments.
    val branch =
      Schema(
        type = Type.OBJECT,
        properties = mapOf("a" to Schema(type = Type.STRING, nullable = true)),
        required = listOf("a"),
      )
    val schema =
      Schema(
        type = Type.OBJECT,
        properties =
          mapOf(
            "result" to Schema(anyOf = listOf(branch, Schema(type = Type.NULL))),
            "error" to Schema(type = Type.OBJECT),
          ),
      )
    val tool = SetModelResponseTool(schema)

    val result = tool.run(testToolContext(), mapOf("result" to mapOf("a" to null), "error" to null))

    assertThat(result).isEqualTo(mapOf("result" to mapOf("a" to null)))
  }

  @Test
  fun run_nullOptionalFieldInAListOfObjects_dropsItFromEachItem() = runBlocking {
    // `note` is optional and not nullable, so only the arguments without it validate.
    val item =
      Schema(
        type = Type.OBJECT,
        properties =
          mapOf("name" to Schema(type = Type.STRING), "note" to Schema(type = Type.STRING)),
      )
    val schema =
      Schema(
        type = Type.OBJECT,
        properties = mapOf("items" to Schema(type = Type.ARRAY, items = item)),
      )
    val tool = SetModelResponseTool(schema)

    val result =
      tool.run(testToolContext(), mapOf("items" to listOf(mapOf("name" to "a", "note" to null))))

    assertThat(result).isEqualTo(mapOf("items" to listOf(mapOf("name" to "a"))))
  }

  @Test
  fun run_requiredNullUnderAdditionalProperties_keepsIt() = runBlocking {
    // Arrange: each undeclared key's value requires `a`, which may be null.
    val value =
      Schema(
        type = Type.OBJECT,
        properties = mapOf("a" to Schema(type = Type.STRING, nullable = true)),
        required = listOf("a"),
      )
    val tool = SetModelResponseTool(Schema(type = Type.OBJECT, additionalProperties = value))

    // Act: validation does not check values under `additionalProperties`, so a wrong drop passes.
    val result = tool.run(testToolContext(), mapOf("k" to mapOf("a" to null)))

    // Assert
    assertThat(result).isEqualTo(mapOf("k" to mapOf("a" to null)))
  }

  @Test
  fun run_nullOptionalFieldInANestedOneOfTheseFieldsObject_dropsIt() = runBlocking {
    // Arrange: `outcome` types its own fields; its `anyOf` only says which one to send.
    val outcome =
      Schema(
        type = Type.OBJECT,
        properties = mapOf("a" to Schema(type = Type.INTEGER), "b" to Schema(type = Type.STRING)),
        anyOf = listOf(Schema(required = listOf("a")), Schema(required = listOf("b"))),
      )
    val tool =
      SetModelResponseTool(Schema(type = Type.OBJECT, properties = mapOf("outcome" to outcome)))

    // Act
    val result = tool.run(testToolContext(), mapOf("outcome" to mapOf("a" to 1L, "b" to null)))

    // Assert
    assertThat(result).isEqualTo(mapOf("outcome" to mapOf("a" to 1L)))
  }

  @Test
  fun run_requiredNullOfAParentWithANullBranch_keepsIt() = runBlocking {
    // Arrange: `outcome` requires the nullable `note` itself; its one non-NULL branch does not.
    val outcome =
      Schema(
        type = Type.OBJECT,
        properties =
          mapOf(
            "a" to Schema(type = Type.INTEGER),
            "note" to Schema(type = Type.STRING, nullable = true),
          ),
        required = listOf("note"),
        anyOf = listOf(Schema(required = listOf("a")), Schema(type = Type.NULL)),
      )
    val schema =
      Schema(
        type = Type.OBJECT,
        properties = mapOf("outcome" to outcome, "error" to Schema(type = Type.OBJECT)),
      )
    val tool = SetModelResponseTool(schema)

    // Act: the null `error` rules out the raw arguments, so `note` must survive the drop.
    val result =
      tool.run(
        testToolContext(),
        mapOf("outcome" to mapOf("a" to 1L, "note" to null), "error" to null),
      )

    // Assert
    assertThat(result).isEqualTo(mapOf("outcome" to mapOf("a" to 1L, "note" to null)))
  }

  @Test
  fun run_nullInsideAFreeFormObject_keepsIt() = runBlocking {
    // Arrange: `error` declares no properties, so its keys are data, as in a pydantic dict field.
    val schema =
      Schema(
        type = Type.OBJECT,
        properties =
          mapOf("answer" to Schema(type = Type.STRING), "error" to Schema(type = Type.OBJECT)),
      )
    val tool = SetModelResponseTool(schema)

    // Act
    val result =
      tool.run(testToolContext(), mapOf("answer" to "42", "error" to mapOf("code" to null)))

    // Assert
    assertThat(result).isEqualTo(mapOf("answer" to "42", "error" to mapOf("code" to null)))
  }

  @Test
  fun run_nullInsideAUnionOfSeveralObjects_keepsIt() = runBlocking {
    // The union names no single branch, so `note` stays; the null `error` rules out raw args.
    val withNote =
      Schema(
        type = Type.OBJECT,
        properties =
          mapOf(
            "a" to Schema(type = Type.STRING),
            "note" to Schema(type = Type.STRING, nullable = true),
          ),
        required = listOf("a", "note"),
      )
    val withB =
      Schema(
        type = Type.OBJECT,
        properties = mapOf("b" to Schema(type = Type.STRING)),
        required = listOf("b"),
      )
    val schema =
      Schema(
        type = Type.OBJECT,
        properties =
          mapOf(
            "result" to Schema(anyOf = listOf(withNote, withB)),
            "error" to Schema(type = Type.OBJECT),
          ),
      )
    val tool = SetModelResponseTool(schema)

    val result =
      tool.run(
        testToolContext(),
        mapOf("result" to mapOf("a" to "x", "note" to null), "error" to null),
      )

    assertThat(result).isEqualTo(mapOf("result" to mapOf("a" to "x", "note" to null)))
  }

  @Test
  fun runAsync_requiredNullField_keepsItInFinalTextAndState() = runBlocking {
    // Arrange: the null `error` rules out the raw arguments, so `answer` must survive the drop.
    val schema =
      Schema(
        type = Type.OBJECT,
        properties =
          mapOf(
            "answer" to Schema(type = Type.STRING, nullable = true),
            "error" to Schema(type = Type.OBJECT),
          ),
        required = listOf("answer"),
      )
    val model =
      DummyModel.createSequential(
        "gemini-2.5-flash",
        listOf(
          modelFunctionCallResponse(
            SetModelResponseTool.NAME,
            mapOf("answer" to null, "error" to null),
            id = "call_1",
          )
        ),
      )
    val agent =
      LlmAgent(
        name = "agent",
        model = model,
        tools = listOf(DummyTool("lookup", declares = true)),
        outputSchema = schema,
        outputKey = "result",
      )
    val runner = InMemoryRunner(agent = agent)
    val unused = runner.sessionService.createSession(SessionKey(runner.appName, "u", "s"))

    // Act
    val events =
      runner.runAsync(userId = "u", sessionId = "s", newMessage = userMessage("hi")).toList()

    // Assert
    val finalEvent = events.last()
    assertThat(finalEvent.content?.parts?.single()?.text).isEqualTo("""{"answer":null}""")
    assertThat(finalEvent.actions.stateDelta["result"]).isEqualTo(mapOf("answer" to null))
  }

  @Test
  fun run_nullRequiredField_returnsFeedback() =
    runBlocking<Unit> {
      val tool = SetModelResponseTool(outputSchema)

      val result = tool.run(testToolContext(), mapOf("name" to null, "city" to "NYC"))

      assertThat(result.keys).containsExactly("error")
    }

  @Test
  fun runAsync_setModelResponseWithNullErrorBranch_endsTurnWithResult() = runBlocking {
    // Arrange: the model fills the result branch and sends null for the unused error branch.
    val schema =
      Schema(
        type = Type.OBJECT,
        properties =
          mapOf(
            "result" to
              Schema(
                type = Type.OBJECT,
                properties = mapOf("answer" to Schema(type = Type.STRING)),
              ),
            "error" to
              Schema(type = Type.OBJECT, properties = mapOf("code" to Schema(type = Type.STRING))),
          ),
      )
    val model =
      DummyModel.createSequential(
        "gemini-2.5-flash",
        listOf(
          modelFunctionCallResponse(
            SetModelResponseTool.NAME,
            mapOf("result" to mapOf("answer" to "42"), "error" to null),
            id = "call_1",
          )
        ),
      )
    val agent =
      LlmAgent(
        name = "agent",
        model = model,
        tools = listOf(DummyTool("lookup", declares = true)),
        outputSchema = schema,
        outputKey = "result",
      )
    val runner = InMemoryRunner(agent = agent)
    val unused = runner.sessionService.createSession(SessionKey(runner.appName, "u", "s"))

    // Act
    val events =
      runner.runAsync(userId = "u", sessionId = "s", newMessage = userMessage("hi")).toList()

    // Assert
    val finalEvent = events.last()
    assertThat(finalEvent.isFinalResponse).isTrue()
    assertThat(finalEvent.content?.parts?.single()?.text)
      .isEqualTo("""{"result":{"answer":"42"}}""")
    assertThat(finalEvent.actions.stateDelta["result"])
      .isEqualTo(mapOf("result" to mapOf("answer" to "42")))
  }

  @Test
  fun run_wholeNumberDoubleForInteger_recordsItAsLong() = runBlocking {
    // Arrange: Gson-based decoders read the JSON number 1 as 1.0.
    val optionalInt = Schema(anyOf = listOf(Schema(type = Type.INTEGER), Schema(type = Type.NULL)))
    val schema =
      Schema(
        type = Type.OBJECT,
        properties =
          mapOf(
            "n" to Schema(type = Type.INTEGER),
            "items" to Schema(type = Type.ARRAY, items = Schema(type = Type.INTEGER)),
            "maybe" to optionalInt,
          ),
      )
    val tool = SetModelResponseTool(schema)

    // Act
    val result =
      tool.run(
        testToolContext(functionCallId = "call_1"),
        mapOf("n" to 1.0, "items" to listOf(2.0), "maybe" to 3.0),
      )

    // Assert
    assertEquals(mapOf("n" to 1L, "items" to listOf(2L), "maybe" to 3L), result)
    assertEquals("""{"n":1,"items":[2],"maybe":3}""", tool.validatedResponseJson("call_1"))
  }

  @Test
  fun run_wholeNumberDoubleUnderIntegerAdditionalProperties_recordsItAsLong() = runBlocking {
    // Arrange
    val counts = Schema(type = Type.OBJECT, additionalProperties = Schema(type = Type.INTEGER))
    val tool =
      SetModelResponseTool(Schema(type = Type.OBJECT, properties = mapOf("counts" to counts)))

    // Act
    val result =
      tool.run(testToolContext(functionCallId = "call_1"), mapOf("counts" to mapOf("a" to 1.0)))

    // Assert
    assertEquals(mapOf("counts" to mapOf("a" to 1L)), result)
    assertEquals("""{"counts":{"a":1}}""", tool.validatedResponseJson("call_1"))
  }

  @Test
  fun run_wholeNumberDoubleInOneOfTheseFieldsSchema_recordsItAsLong() = runBlocking {
    // Arrange: the object's own properties type `a`; its `anyOf` only says which field to send.
    val outcome =
      Schema(
        type = Type.OBJECT,
        properties = mapOf("a" to Schema(type = Type.INTEGER), "b" to Schema(type = Type.STRING)),
        anyOf = listOf(Schema(required = listOf("a")), Schema(required = listOf("b"))),
      )
    val tool =
      SetModelResponseTool(Schema(type = Type.OBJECT, properties = mapOf("outcome" to outcome)))

    // Act
    val result =
      tool.run(testToolContext(functionCallId = "call_1"), mapOf("outcome" to mapOf("a" to 1.0)))

    // Assert
    assertEquals(mapOf("outcome" to mapOf("a" to 1L)), result)
  }

  @Test
  fun run_wholeNumberDoubleWithOwnPropertiesAndANullBranch_recordsItAsLong() = runBlocking {
    // Arrange: the one non-NULL branch declares no properties, so the object's own ones apply.
    val outcome =
      Schema(
        type = Type.OBJECT,
        properties = mapOf("a" to Schema(type = Type.INTEGER)),
        anyOf = listOf(Schema(required = listOf("a")), Schema(type = Type.NULL)),
      )
    val tool =
      SetModelResponseTool(Schema(type = Type.OBJECT, properties = mapOf("outcome" to outcome)))

    // Act
    val result =
      tool.run(testToolContext(functionCallId = "call_1"), mapOf("outcome" to mapOf("a" to 1.0)))

    // Assert
    assertEquals(mapOf("outcome" to mapOf("a" to 1L)), result)
  }

  @Test
  fun run_wholeNumberDoubleInIntegerOrStringUnion_recordsItAsLong() = runBlocking {
    // Arrange: no NUMBER branch, so the whole double can only have matched INTEGER.
    val id = Schema(anyOf = listOf(Schema(type = Type.INTEGER), Schema(type = Type.STRING)))
    val tool = SetModelResponseTool(Schema(type = Type.OBJECT, properties = mapOf("id" to id)))

    // Act
    val result = tool.run(testToolContext(functionCallId = "call_1"), mapOf("id" to 7.0))

    // Assert
    assertEquals(mapOf("id" to 7L), result)
    assertEquals("""{"id":7}""", tool.validatedResponseJson("call_1"))
  }

  @Test
  fun runAsync_invalidSetModelResponse_modelGetsFeedbackAndRetries() = runBlocking {
    // Arrange: the first call misses the required field, the second one is valid.
    val schema =
      Schema(
        type = Type.OBJECT,
        properties = mapOf("answer" to Schema(type = Type.STRING)),
        required = listOf("answer"),
      )
    val name = SetModelResponseTool.NAME
    val responses =
      listOf(
        modelFunctionCallResponse(name, mapOf("wrong" to "x"), id = "call_1"),
        modelFunctionCallResponse(name, mapOf("answer" to "42"), id = "call_2"),
      )
    val requests = mutableListOf<LlmRequest>()
    val model =
      DummyModel("gemini-2.5-flash") { request ->
        requests += request
        flowOf(responses[requests.size - 1])
      }
    val agent =
      LlmAgent(
        name = "agent",
        model = model,
        tools = listOf(DummyTool("lookup", declares = true)),
        outputSchema = schema,
        outputKey = "result",
      )
    val runner = InMemoryRunner(agent = agent)
    val unused = runner.sessionService.createSession(SessionKey(runner.appName, "u", "s"))

    // Act
    val events =
      runner.runAsync(userId = "u", sessionId = "s", newMessage = userMessage("hi")).toList()

    // Assert
    val feedback =
      requests[1].contents.flatMap { it.parts }.mapNotNull { it.functionResponse }.single()
    assertThat(feedback.name).isEqualTo(SetModelResponseTool.NAME)
    assertThat(feedback.response["error"] as String).startsWith("Validation Error found:")
    // The invalid first call ended neither the turn nor the output.
    assertThat(events.count { it.isFinalResponse }).isEqualTo(1)
    assertThat(events.count { it.actions.stateDelta.containsKey("result") }).isEqualTo(1)
    val finalEvent = events.last()
    assertThat(finalEvent.isFinalResponse).isTrue()
    assertThat(finalEvent.content?.parts?.single()?.text).isEqualTo("""{"answer":"42"}""")
    assertThat(finalEvent.actions.stateDelta["result"]).isEqualTo(mapOf("answer" to "42"))
  }
}
