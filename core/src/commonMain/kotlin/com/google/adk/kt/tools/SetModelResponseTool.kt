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

import com.google.adk.kt.SchemaUtils
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.canDescribeAMap
import com.google.adk.kt.collections.concurrentMutableMapOf
import com.google.adk.kt.extraSchema
import com.google.adk.kt.impliedType
import com.google.adk.kt.serialization.anyToJsonElement
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import com.google.adk.kt.withIntegersAsLongs

/**
 * Internal tool used for the output schema workaround.
 *
 * This tool lets the model produce its final response when an output schema is configured alongside
 * other tools on a model that cannot use a response schema and tools at the same time (see
 * [com.google.adk.kt.models.Model.capabilities]). The model is instructed to call this tool with
 * its final answer in the required schema format instead of emitting text directly.
 *
 * Follows the Java ADK `SetModelResponseTool`: the [outputSchema] is exposed directly as the tool's
 * parameters, so only top-level object schemas are supported. The Python ADK's
 * `SetModelResponseTool` additionally handles list/primitive schemas by wrapping them in synthetic
 * `items`/`response` parameters; that is intentionally not replicated here.
 *
 * @property outputSchema The schema the model's final response must conform to.
 */
internal class SetModelResponseTool(private val outputSchema: Schema) :
  BaseTool(
    name = NAME,
    description =
      "Set your final response using the required output schema. After using any other tools " +
        "needed to complete the task, always call set_model_response with your final answer in " +
        "the specified schema format.",
  ) {

  /** The arguments [run] validated, by function call id; only these end the turn. */
  private val validatedResponses = concurrentMutableMapOf<String, Map<String, Any?>>()

  /** Whether [outputSchema], or one of its `anyOf` members, could describe the arguments map. */
  private val describesAMap =
    outputSchema.canDescribeAMap() && (outputSchema.anyOf?.any { it.canDescribeAMap() } ?: true)

  override fun declaration(): FunctionDeclaration =
    FunctionDeclaration(
      name = name,
      description = description,
      // An untyped schema with object keys describes an object; function parameters need the type.
      parameters =
        if (outputSchema.impliedType() == Type.OBJECT) outputSchema.copy(type = Type.OBJECT)
        else outputSchema,
    )

  /**
   * Validates [args] against [outputSchema] and records them as the final answer, with whole-number
   * doubles in integer fields as [Long]. Models often send null for an optional field, so [args]
   * without those nulls are tried first, then [args] as sent. If neither matches, returns an
   * `error` entry for the model to retry, as in the Python and Java ADKs. It throws, which reaches
   * `onToolErrorCallbacks`, only when [outputSchema] is too complex to validate or cannot describe
   * an object.
   */
  override suspend fun run(context: ToolContext, args: Map<String, Any?>): Map<String, Any?> {
    val withoutNulls = args.withoutOptionalNulls(outputSchema)
    val failure = validate(withoutNulls).exceptionOrNull() ?: return record(context, withoutNulls)
    if (validate(args).isSuccess) return record(context, args)
    if (failure is SchemaUtils.SchemaTooComplexException || !describesAMap) throw failure
    return mapOf(
      "error" to
        "Validation Error found:\n${validationFeedback(failure)}\nRecall the set_model_response " +
          "function correctly, fix the errors, and call it again with all required fields using " +
          "the correct types."
    )
  }

  private fun record(context: ToolContext, args: Map<String, Any?>): Map<String, Any?> {
    val response = args.withIntegersAsLongs(outputSchema)
    validatedResponses[context.functionCallId.orEmpty()] = response
    return response
  }

  private fun validate(args: Map<String, Any?>): Result<Unit> =
    SchemaUtils.validateMapOnSchema(args, outputSchema, argsName = "Output")

  /**
   * Returns the JSON of the arguments [run] validated for [functionCallId], null fields included,
   * or `null` if none.
   */
  @OptIn(FrameworkInternalApi::class)
  internal fun validatedResponseJson(functionCallId: String?): String? =
    validatedResponses[functionCallId.orEmpty()]?.let { anyToJsonElement(it).toString() }

  companion object {
    const val NAME: String = "set_model_response"
  }
}

/**
 * Joins the messages of [failure] and its causes, each cut before the schema summary that
 * `SchemaUtils` appends after `: Schema(`, since the tool declaration already gives the model the
 * schema.
 */
private fun validationFeedback(failure: Throwable): String =
  generateSequence(failure) { it.cause }
    .mapNotNull { it.message?.substringBefore(": Schema(") }
    .joinToString("\n")

/**
 * Drops each null entry whose key [schema] declares as an optional property, in nested values too.
 * A null under a key without a property schema stays, as pydantic keeps nulls in a free-form dict.
 */
private fun Map<*, *>.withoutOptionalNulls(schema: Schema?): Map<String, Any?> {
  val properties = schema?.properties.orEmpty()
  val required = schema?.required.orEmpty()
  // Maps decoded from JSON objects have string keys.
  return entries
    .filter { (key, value) -> value != null || key !in properties || key in required }
    .associate { (key, value) ->
      val name = key as String
      name to dropOptionalNulls(value, properties[name] ?: schema?.extraSchema())
    }
}

/**
 * Returns [value] without the nulls [schema] does not require. The schema's own `properties`,
 * `additionalProperties` and `items` come first; `anyOf` decides only where it declares none of
 * them.
 */
private fun dropOptionalNulls(value: Any?, schema: Schema?): Any? {
  val ownShape =
    schema == null ||
      schema.properties != null ||
      schema.extraSchema() != null ||
      schema.items != null
  val branches = schema?.anyOf?.filter { it.type != Type.NULL }.orEmpty()
  return when {
    !ownShape && branches.size == 1 -> dropOptionalNulls(value, branches.single())
    // A union with several non-NULL branches does not say which fields are required.
    !ownShape && branches.size > 1 -> value
    value is Map<*, *> -> value.withoutOptionalNulls(schema)
    value is List<*> -> value.map { dropOptionalNulls(it, schema?.items) }
    else -> value
  }
}
