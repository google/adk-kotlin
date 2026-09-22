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
package com.google.adk.kt.models.springai

import com.google.adk.kt.events.EventActions
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.Type
import com.google.common.truth.Truth.assertThat
import java.util.function.Function
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.springframework.ai.chat.model.ToolContext as SpringToolContext
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.ToolCallbackProvider
import org.springframework.ai.tool.definition.ToolDefinition
import org.springframework.ai.tool.function.FunctionToolCallback
import org.springframework.ai.tool.metadata.ToolMetadata
import org.springframework.ai.util.json.schema.JsonSchemaGenerator

class SpringAiToolTest {

  private fun callback(fn: Function<Map<String, Any?>, String>): ToolCallback =
    FunctionToolCallback.builder("echo", fn)
      .description("Echoes a message.")
      .inputType(Map::class.java)
      .inputSchema(
        "{\"type\":\"object\",\"properties\":{\"msg\":{\"type\":\"string\"}}," +
          "\"required\":[\"msg\"]}"
      )
      .build()

  @Test
  fun declaration_parsesNameDescriptionAndSchema() {
    val tool = SpringAiTool(callback { "{}" })

    assertThat(tool.name).isEqualTo("echo")
    assertThat(tool.description).isEqualTo("Echoes a message.")
    val params = tool.declaration().parameters!!
    assertThat(params.type).isEqualTo(Type.OBJECT)
    assertThat(params.properties).containsKey("msg")
    assertThat(params.properties?.get("msg")?.type).isEqualTo(Type.STRING)
    assertThat(params.required).containsExactly("msg")
  }

  @Test
  fun run_passesArgsAndParsesJsonObjectResult() =
    runBlocking<Unit> {
      var captured: Map<String, Any?>? = null
      val tool =
        SpringAiTool(
          callback { args ->
            captured = args
            "{\"ok\":true}"
          }
        )

      val result = tool.run(mock<ToolContext>(), mapOf("msg" to "hi"))

      assertThat(captured).containsEntry("msg", "hi")
      assertThat(result).isEqualTo(mapOf("ok" to true))
    }

  @Test
  fun run_returnsRawStringForNonJsonResult() =
    runBlocking<Unit> {
      // A raw ToolCallback returning non-JSON. FunctionToolCallback would quote it into valid JSON
      // (hiding the fallback), so use a mock to exercise decodeToolResult's getOrElse { raw } path.
      val definition =
        mock<ToolDefinition> {
          on { name() } doReturn "t"
          on { description() } doReturn "d"
          on { inputSchema() } doReturn "{\"type\":\"object\"}"
        }
      val metadata = mock<ToolMetadata> { on { returnDirect() } doReturn false }
      val callback =
        mock<ToolCallback> {
          on { toolDefinition } doReturn definition
          on { toolMetadata } doReturn metadata
          on { call(any<String>(), any<SpringToolContext>()) } doReturn "not json{"
        }

      val result = SpringAiTool(callback).run(mock<ToolContext>(), mapOf("msg" to "hi"))

      assertThat(result).isEqualTo("not json{")
    }

  @Test
  fun declaration_resolvesDefsRef() {
    // A `$ref` into root `$defs` must resolve, or the property becomes an empty object while
    // staying
    // required - the tool then fails at call time.
    val schema =
      "{\"type\":\"object\",\"properties\":{\"origin\":{\"\$ref\":\"#/\$defs/Location\"}}," +
        "\"required\":[\"origin\"]," +
        "\"\$defs\":{\"Location\":{\"type\":\"object\",\"properties\":" +
        "{\"city\":{\"type\":\"string\"}},\"required\":[\"city\"]}}}"
    val cb =
      FunctionToolCallback.builder("t", Function<Map<String, Any?>, String> { "{}" })
        .description("d")
        .inputType(Map::class.java)
        .inputSchema(schema)
        .build()

    val origin = SpringAiTool(cb).declaration().parameters?.properties?.get("origin")!!

    assertThat(origin.type).isEqualTo(Type.OBJECT)
    assertThat(origin.properties).containsKey("city")
    assertThat(origin.required).containsExactly("city")
  }

  @Test
  fun declaration_convertsRealSpringGeneratedSchema() {
    // Exercises what Spring AI's JsonSchemaGenerator actually emits for a reused nested type, which
    // is `$defs` + `$ref`; a hand-written schema would not have caught the missing resolution.
    val schema = JsonSchemaGenerator.generateForType(RouteRequest::class.java)
    val cb =
      FunctionToolCallback.builder("route", Function<Map<String, Any?>, String> { "{}" })
        .description("d")
        .inputType(Map::class.java)
        .inputSchema(schema)
        .build()

    val origin = SpringAiTool(cb).declaration().parameters?.properties?.get("origin")!!

    assertThat(origin.type).isEqualTo(Type.OBJECT)
    assertThat(origin.properties).containsKey("city")
  }

  @Test
  fun declaration_parsesConstraintsAndAnyOf() {
    val schema =
      "{\"type\":\"object\",\"properties\":{" +
        "\"age\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":120}," +
        "\"id\":{\"anyOf\":[{\"type\":\"string\"},{\"type\":\"integer\"}]}}}"
    val cb =
      FunctionToolCallback.builder("t", Function<Map<String, Any?>, String> { "{}" })
        .description("d")
        .inputType(Map::class.java)
        .inputSchema(schema)
        .build()

    val params = SpringAiTool(cb).declaration().parameters!!

    val age = params.properties?.get("age")!!
    assertThat(age.minimum).isEqualTo(0.0)
    assertThat(age.maximum).isEqualTo(120.0)
    val id = params.properties?.get("id")!!
    assertThat(id.anyOf).hasSize(2)
    assertThat(id.anyOf?.get(0)?.type).isEqualTo(Type.STRING)
  }

  @Test
  fun declaration_integerEnum_keepsIntegerValues() {
    // Parsed via kotlinx, so an integer enum stays 1/2/3 rather than being widened to 1.0/2.0/3.0.
    val schema =
      "{\"type\":\"object\",\"properties\":{\"n\":{\"type\":\"integer\",\"enum\":[1,2,3]}}}"
    val cb =
      FunctionToolCallback.builder("t", Function<Map<String, Any?>, String> { "{}" })
        .description("d")
        .inputType(Map::class.java)
        .inputSchema(schema)
        .build()

    val n = SpringAiTool(cb).declaration().parameters?.properties?.get("n")!!

    assertThat(n.enum).containsExactly("1", "2", "3").inOrder()
  }

  @Test
  fun from_wrapsEachCallback() {
    val tools = SpringAiTool.from(listOf(callback { "{}" }, callback { "{}" }))

    assertThat(tools).hasSize(2)
    assertThat(tools[0].name).isEqualTo("echo")
  }

  @Test
  fun run_returnDirect_setsSkipSummarization() =
    runBlocking<Unit> {
      val definition =
        mock<ToolDefinition> {
          on { name() } doReturn "t"
          on { description() } doReturn "d"
          on { inputSchema() } doReturn "{\"type\":\"object\"}"
        }
      val metadata = mock<ToolMetadata> { on { returnDirect() } doReturn true }
      val callback =
        mock<ToolCallback> {
          on { toolDefinition } doReturn definition
          on { toolMetadata } doReturn metadata
          on { call(any<String>(), any<SpringToolContext>()) } doReturn "{}"
        }
      val actions = EventActions()
      val context = mock<ToolContext> { on { this.actions } doReturn actions }

      SpringAiTool(callback).run(context, emptyMap())

      assertThat(actions.skipSummarization).isTrue()
    }

  @Test
  fun run_decodesJsonArrayResult() =
    runBlocking<Unit> {
      val result =
        SpringAiTool(callback { "[1,2,3]" }).run(mock<ToolContext>(), mapOf("msg" to "x"))

      assertThat(result).isEqualTo(listOf(1L, 2L, 3L))
    }

  @Test
  fun run_decodesScalarResult() =
    runBlocking<Unit> {
      val result = SpringAiTool(callback { "42" }).run(mock<ToolContext>(), mapOf("msg" to "x"))

      assertThat(result).isEqualTo(42L)
    }

  @Test
  fun run_nullResult_becomesEmptyMap() =
    runBlocking<Unit> {
      val result = SpringAiTool(callback { "null" }).run(mock<ToolContext>(), mapOf("msg" to "x"))

      assertThat(result).isEqualTo(emptyMap<String, Any?>())
    }

  @Test
  fun declaration_blankOrUnparseableSchema_yieldsEmptyObject() {
    for (schema in listOf("", "   ", "not json{")) {
      val definition =
        mock<ToolDefinition> {
          on { name() } doReturn "t"
          on { description() } doReturn "d"
          on { inputSchema() } doReturn schema
        }
      val callback = mock<ToolCallback> { on { toolDefinition } doReturn definition }

      val params = SpringAiTool(callback).declaration().parameters!!

      assertThat(params.type).isEqualTo(Type.OBJECT)
      assertThat(params.properties).isEmpty()
    }
  }

  @Test
  fun springAiToolset_wrapsEachProviderCallback() =
    runBlocking<Unit> {
      val provider =
        mock<ToolCallbackProvider> {
          on { toolCallbacks } doReturn arrayOf(callback { "{}" }, callback { "{}" })
        }

      val tools = SpringAiToolset(provider).getTools()

      assertThat(tools).hasSize(2)
      assertThat(tools[0]).isInstanceOf(SpringAiTool::class.java)
      assertThat(tools[0].name).isEqualTo("echo")
    }

  @Test
  fun run_passesAdkToolContextToSpringUnderKey() =
    runBlocking<Unit> {
      val definition =
        mock<ToolDefinition> {
          on { name() } doReturn "t"
          on { description() } doReturn "d"
          on { inputSchema() } doReturn "{\"type\":\"object\"}"
        }
      val metadata = mock<ToolMetadata> { on { returnDirect() } doReturn false }
      val callback =
        mock<ToolCallback> {
          on { toolDefinition } doReturn definition
          on { toolMetadata } doReturn metadata
          on { call(any<String>(), any<SpringToolContext>()) } doReturn "{}"
        }
      val adkContext = mock<ToolContext>()

      SpringAiTool(callback).run(adkContext, emptyMap())

      val captor = argumentCaptor<SpringToolContext>()
      verify(callback).call(any(), captor.capture())
      assertThat(captor.firstValue.context)
        .containsEntry(SpringAiTool.ADK_TOOL_CONTEXT_KEY, adkContext)
    }
}

/** Reused nested type, so Spring AI's schema generator emits `$defs` + `$ref` for it. */
private data class Location(val city: String, val country: String)

private data class RouteRequest(val origin: Location, val destination: Location)
