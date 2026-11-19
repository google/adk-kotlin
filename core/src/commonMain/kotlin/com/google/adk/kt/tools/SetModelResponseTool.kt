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
import com.google.adk.kt.acceptsNull
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.collections.concurrentMutableMapOf
import com.google.adk.kt.describesOnlyObjects
import com.google.adk.kt.extraSchema
import com.google.adk.kt.impliedType
import com.google.adk.kt.normalizedBySchema
import com.google.adk.kt.serialization.anyToJsonElement
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type

/**
 * The tool a model calls with its final answer when an output schema and tools cannot share a
 * request (see [com.google.adk.kt.models.Model.capabilities]). An [outputSchema] that only
 * describes objects is the tool's parameters; function parameters must be an object, so any other
 * schema goes into a single `response` parameter, as in the Python ADK.
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

  /** The JSON of the answers [run] validated, by function call id; only these end the turn. */
  private val validatedResponses = concurrentMutableMapOf<String, String>()

  /** Whether [outputSchema] goes into a [RESPONSE_PARAMETER], as it may describe a non-object. */
  private val wrapsOutputSchema = !outputSchema.describesOnlyObjects()

  /** The tool's parameters, which the model's arguments must match. */
  private val parameters: Schema =
    if (wrapsOutputSchema) {
      // Vertex AI rejects an `enum` without a type, and the implied type lets validation check an
      // untyped list's items; enum values are strings. A schema with `anyOf` stays untyped, as a
      // type would rule out its other branches.
      val impliedType =
        outputSchema.impliedType() ?: Type.STRING.takeIf { outputSchema.enum != null }
      val response =
        if (outputSchema.anyOf == null) outputSchema.copy(type = impliedType) else outputSchema
      Schema(
        type = Type.OBJECT,
        properties = mapOf(RESPONSE_PARAMETER to response),
        required = listOf(RESPONSE_PARAMETER),
      )
    } else {
      // An untyped schema with object keys describes an object; function parameters need the type.
      outputSchema.copy(type = Type.OBJECT)
    }

  override fun declaration(): FunctionDeclaration =
    FunctionDeclaration(name = name, description = description, parameters = parameters)

  /**
   * Records [args], or their [RESPONSE_PARAMETER], as the final answer if they match the tool's
   * parameters, trying them first without the nulls models often send for optional fields. A
   * mismatch returns an `error` entry for the model to retry, as in the Python and Java ADKs, and
   * only a schema too complex to validate throws, which reaches `onToolErrorCallbacks`.
   */
  override suspend fun run(context: ToolContext, args: Map<String, Any?>): Map<String, Any?> {
    val withoutNulls = args.withoutOptionalNulls(parameters)
    // A null rejected by the schema is treated as omitted and receives the default; a null
    // accepted by the schema is dropped only after normalizing, so no default is applied.
    val failure =
      validate(withoutNulls).exceptionOrNull()
        ?: return record(
          context,
          args
            .withoutOptionalNulls(parameters, onlyRejected = true)
            .normalizedBySchema(parameters)
            .withoutOptionalNulls(parameters),
        )
    if (validate(args).isSuccess) return record(context, args.normalizedBySchema(parameters))
    if (failure is SchemaUtils.SchemaTooComplexException) throw failure
    return mapOf(
      "error" to
        "Validation Error found:\n${validationFeedback(failure)}\nRecall the set_model_response " +
          "function correctly, fix the errors, and call it again with all required fields using " +
          "the correct types."
    )
  }

  @OptIn(FrameworkInternalApi::class)
  private fun record(context: ToolContext, validated: Map<String, Any?>): Map<String, Any?> {
    val answer = if (wrapsOutputSchema) validated[RESPONSE_PARAMETER] else validated
    validatedResponses[context.functionCallId.orEmpty()] = anyToJsonElement(answer).toString()
    // The model gets a wrapped answer back as it gets a function tool's non-map result.
    return if (wrapsOutputSchema) mapOf(BaseTool.RESULT_KEY to answer) else validated
  }

  private fun validate(args: Map<String, Any?>): Result<Unit> =
    SchemaUtils.validateMapOnSchema(args, parameters, argsName = "Output")

  /**
   * Returns the JSON of the answer [run] validated for [functionCallId], null fields included, or
   * `null` if none.
   */
  internal fun validatedResponseJson(functionCallId: String?): String? =
    validatedResponses[functionCallId.orEmpty()]

  companion object {
    const val NAME: String = "set_model_response"

    /** The parameter that carries a non-object answer. */
    private const val RESPONSE_PARAMETER = "response"
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
 * Drops each null entry whose key [schema] declares as an optional property, including in nested
 * values; when [onlyRejected] is true, drops only entries whose property has a default but does not
 * accept null. Null entries under keys without a property schema are kept, matching how pydantic
 * preserves nulls in a free-form dict.
 */
private fun Map<*, *>.withoutOptionalNulls(
  schema: Schema?,
  onlyRejected: Boolean = false,
): Map<String, Any?> {
  val properties = schema?.properties.orEmpty()
  val required = schema?.required.orEmpty()
  // Maps decoded from JSON objects have string keys.
  return entries
    .filter { (key, value) ->
      val property = properties[key] ?: return@filter true
      // Without a default, dropping the null now or later gives the same result, so skip the check.
      value != null ||
        key in required ||
        (onlyRejected && (property.default == null || property.acceptsNull()))
    }
    .associate { (key, value) ->
      val name = key as String
      name to dropOptionalNulls(value, properties[name] ?: schema?.extraSchema(), onlyRejected)
    }
}

/**
 * Returns [value] with optional nulls removed as in [withoutOptionalNulls]. The schema's own
 * `properties`, `additionalProperties` and `items` come first; `anyOf` decides only where it
 * declares none of them.
 */
private fun dropOptionalNulls(value: Any?, schema: Schema?, onlyRejected: Boolean): Any? {
  val ownShape =
    schema == null ||
      schema.properties != null ||
      schema.extraSchema() != null ||
      schema.items != null
  val branches = schema?.anyOf?.filter { it.type != Type.NULL }.orEmpty()
  return when {
    !ownShape && branches.size == 1 -> dropOptionalNulls(value, branches.single(), onlyRejected)
    // A union with several non-NULL branches does not say which fields are required.
    !ownShape && branches.size > 1 -> value
    value is Map<*, *> -> value.withoutOptionalNulls(schema, onlyRejected)
    value is List<*> -> value.map { dropOptionalNulls(it, schema?.items, onlyRejected) }
    else -> value
  }
}
