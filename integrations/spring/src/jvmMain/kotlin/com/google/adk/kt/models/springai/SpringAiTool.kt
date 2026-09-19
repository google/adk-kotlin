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
// Uses ADK's @FrameworkInternalApi serialization helper jsonElementToAny to decode a tool result
// into JSON-native values; opt in once for the whole file.
@file:OptIn(FrameworkInternalApi::class)

package com.google.adk.kt.models.springai

import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.serialization.Json
import com.google.adk.kt.serialization.jsonElementToAny
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import kotlin.jvm.JvmStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json as KxJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import org.springframework.ai.chat.model.ToolContext as SpringToolContext
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.ToolCallbackProvider

/**
 * Exposes a Spring AI [ToolCallback] as an ADK [BaseTool], so an existing Spring AI tool can be
 * used by any ADK Kotlin agent and model. Name, description, and input schema come from the
 * callback's `ToolDefinition`; ADK owns the tool loop, so [run] serializes the arguments, invokes
 * the callback on the IO dispatcher (the call is blocking), and decodes the JSON result. The ADK
 * [ToolContext] is passed to the callback under [ADK_TOOL_CONTEXT_KEY], and a `returnDirect`
 * callback maps to ADK's `skipSummarization`.
 *
 * @param toolCallback The Spring AI tool to wrap.
 */
class SpringAiTool(private val toolCallback: ToolCallback) :
  BaseTool(
    name = toolCallback.toolDefinition.name(),
    description = toolCallback.toolDefinition.description(),
  ) {

  override fun declaration(): FunctionDeclaration =
    FunctionDeclaration(
      name = name,
      description = description,
      parameters = parseJsonSchema(toolCallback.toolDefinition.inputSchema()),
    )

  override suspend fun run(context: ToolContext, args: Map<String, Any?>): Any {
    val toolInput = Json.toJsonString(args)
    // Bridge the ADK ToolContext to Spring AI under a documented key; unaware tools ignore it.
    val springContext = SpringToolContext(mapOf(ADK_TOOL_CONTEXT_KEY to context))
    val result = withContext(Dispatchers.IO) { toolCallback.call(toolInput, springContext) }
    // returnDirect (no follow-up model turn) maps to ADK skipping this response's summarization.
    if (toolCallback.toolMetadata.returnDirect()) {
      context.actions.skipSummarization = true
    }
    // Spring AI returns JSON; decode to a JSON-native value (BaseTool wraps a non-Map under
    // result).
    return decodeToolResult(result) ?: emptyMap<String, Any?>()
  }

  companion object {
    /** Key under which the ADK [ToolContext] is placed in the Spring AI `ToolContext`. */
    const val ADK_TOOL_CONTEXT_KEY: String = "adk.toolContext"

    /** Wraps every tool from a Spring AI [ToolCallbackProvider] as an ADK tool. */
    @JvmStatic
    fun from(provider: ToolCallbackProvider): List<SpringAiTool> =
      provider.toolCallbacks.map { SpringAiTool(it) }

    /** Wraps each Spring AI [ToolCallback] as an ADK tool. */
    @JvmStatic
    fun from(callbacks: List<ToolCallback>): List<SpringAiTool> = callbacks.map { SpringAiTool(it) }
  }
}

/**
 * Parses a JSON Schema string (as produced by Spring AI's `ToolDefinition.inputSchema()`) into an
 * ADK [Schema], covering type, string/numeric/size constraints, enum, properties, items, required,
 * and `anyOf` (mapping `oneOf` to `anyOf`; `allOf`/`$ref` are not represented). A null, blank, or
 * unparseable schema yields an empty object schema.
 */
private fun parseJsonSchema(schema: String?): Schema {
  val root =
    schema
      ?.takeIf { it.isNotBlank() }
      ?.let { runCatching { KxJson.parseToJsonElement(it) }.getOrNull() } as? JsonObject
  return root?.toAdkSchema() ?: Schema(type = Type.OBJECT, properties = emptyMap())
}

private fun JsonObject.toAdkSchema(): Schema =
  Schema(
    type = string("type")?.toAdkType(),
    title = string("title"),
    description = string("description"),
    format = string("format"),
    nullable = primitive("nullable")?.booleanOrNull,
    pattern = string("pattern"),
    minimum = primitive("minimum")?.doubleOrNull,
    maximum = primitive("maximum")?.doubleOrNull,
    minLength = primitive("minLength")?.longOrNull,
    maxLength = primitive("maxLength")?.longOrNull,
    minItems = primitive("minItems")?.longOrNull,
    maxItems = primitive("maxItems")?.longOrNull,
    minProperties = primitive("minProperties")?.longOrNull,
    maxProperties = primitive("maxProperties")?.longOrNull,
    enum = (this["enum"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
    anyOf = subSchemas("anyOf") ?: subSchemas("oneOf"),
    properties =
      (this["properties"] as? JsonObject)
        ?.mapNotNull { (key, value) -> (value as? JsonObject)?.let { key to it.toAdkSchema() } }
        ?.toMap(),
    items = (this["items"] as? JsonObject)?.toAdkSchema(),
    required =
      (this["required"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
  )

private fun JsonObject.primitive(key: String): JsonPrimitive? = this[key] as? JsonPrimitive

private fun JsonObject.string(key: String): String? = primitive(key)?.contentOrNull

private fun JsonObject.subSchemas(key: String): List<Schema>? =
  (this[key] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.toAdkSchema() }

private fun String.toAdkType(): Type =
  when (lowercase()) {
    "string" -> Type.STRING
    "number" -> Type.NUMBER
    "integer" -> Type.INTEGER
    "boolean" -> Type.BOOLEAN
    "array" -> Type.ARRAY
    "object" -> Type.OBJECT
    "null" -> Type.NULL
    else -> Type.TYPE_UNSPECIFIED
  }

/**
 * Decodes a Spring AI tool result (JSON) into a JSON-native value: a [Map] for an object, a [List]
 * for an array, or a scalar for anything else. Falls back to the raw string when it is not valid
 * JSON.
 */
private fun decodeToolResult(raw: String): Any? =
  runCatching { jsonElementToAny(KxJson.parseToJsonElement(raw)) }.getOrElse { raw }
