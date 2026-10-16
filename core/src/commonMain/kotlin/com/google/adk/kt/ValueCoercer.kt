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

package com.google.adk.kt

import com.google.adk.kt.logging.LoggerFactory
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type

/**
 * Validates and coerces runtime values against a [Schema].
 *
 * In addition to values that already match the target type, coercion accepts:
 * - [Content] messages (unwrapped to raw text when the schema accepts a string, or parsed from JSON
 *   otherwise).
 * - Stringified JSON literals for scalar types (e.g. `"42"` for `INTEGER`, `"1.5"` for `NUMBER`,
 *   `"true"` for `BOOLEAN`).
 * - Whole-number floating-point values for `INTEGER` schemas (e.g. `42.0` or `"1e2"` -> `42L` /
 *   `100L`).
 */
internal class ValueCoercer private constructor() {
  private val budget = SchemaUtils.UnionBudget()

  private fun coerce(value: Any?, schema: Schema, path: String = ""): Any? {
    // LLM agents and message-producing nodes emit Content objects; unwrap to raw text or parsed
    // JSON before checking the schema.
    val data = if (value is Content) coerceContent(value, schema) else value
    if (data == null) return coerceNull(schema, path)

    val matched = schema.anyOf?.let { coerceAnyOf(data, it, path) } ?: data
    val result = coerceByType(matched, schema, path)
    require(schema.enum == null || result.toString() in schema.enum) {
      "${at(path)}is not one of the schema's enum values"
    }
    return result
  }

  /** Null passes a nullable schema, a NULL type, or an unspecified type without an enum. */
  private fun coerceNull(schema: Schema, path: String): Any? {
    if (schema.nullable == true) return null
    // Throws unless an alternative accepts null (which coerces to null).
    val coerced = schema.anyOf?.let { coerceAnyOf(null, it, path) }
    val acceptsNullByType =
      schema.type == Type.NULL || (schema.declaredType == null && schema.enum == null)
    require(acceptsNullByType) { "${at(path)}is null" }
    return coerced
  }

  private fun coerceByType(value: Any, schema: Schema, path: String): Any {
    val coerced =
      when (schema.type) {
        null,
        Type.TYPE_UNSPECIFIED ->
          // In JSON Schema, object/array keywords (`properties`, `required`,
          // `additionalProperties`, `items`) are independent of `type` and apply whenever the
          // runtime value is a Map or Collection. This commonly comes up in `anyOf` subschemas that
          // omit `type` because the parent already declared it — e.g. `Schema(type = OBJECT,
          // properties = ..., anyOf = listOf(Schema(required = listOf("a")), ...))`.
          when {
            value is Map<*, *> && schema.declaresKeys() -> coerceObject(value, schema, path)
            value is Collection<*> && schema.items != null -> coerceArray(value, schema.items, path)
            else -> value
          }
        Type.STRING -> value as? String
        Type.INTEGER -> integerLiteral(value)
        Type.NUMBER -> numberLiteral(value)
        Type.BOOLEAN -> booleanLiteral(value)
        Type.ARRAY -> (value as? Collection<*>)?.let { coerceArray(it, schema.items, path) }
        Type.OBJECT -> (value as? Map<*, *>)?.let { coerceObject(it, schema, path) }
        Type.NULL -> null
      }
    return requireNotNull(coerced) { "${at(path)}is not ${typeName(checkNotNull(schema.type))}" }
  }

  /** Coerces [value] by the first alternative it fits as-is, or else the first it converts to. */
  private fun coerceAnyOf(value: Any?, alternatives: List<Schema>, path: String): Any? {
    var converted: Any? = null
    for (alternative in alternatives) {
      if (!budget.spend()) {
        throw SchemaUtils.SchemaTooComplexException(
          "it tried more than ${SchemaUtils.MAX_UNION_CHECKS} union alternatives, so its `anyOf` " +
            "branches multiply out beyond what can be checked"
        )
      }
      val coerced =
        try {
          coerce(value, alternative, path)
        } catch (e: SchemaUtils.SchemaTooComplexException) {
          throw e
        } catch (e: IllegalArgumentException) {
          continue
        }
      if (coerced == value) return coerced
      if (converted == null) converted = coerced
    }
    return requireNotNull(converted) { "${at(path)}matches none of the schema's alternatives" }
  }

  private fun coerceArray(value: Collection<*>, items: Schema?, path: String): List<Any?> {
    if (items == null) return value.toList()
    // Each item gets its own budget: a list's length is data, not schema complexity.
    return value.mapIndexed { i, item -> ValueCoercer().coerce(item, items, "$path[$i]") }
  }

  private fun coerceObject(value: Map<*, *>, schema: Schema, path: String): Map<String, Any?> {
    val properties = schema.properties.orEmpty()
    val extras = schema.additionalProperties
    val extraSchema = schema.extraSchema()
    val result = mutableMapOf<String, Any?>()
    for ((key, entry) in value) {
      val name = key.toString()
      val declared = properties[name]
      // An undeclared key is user data, so an error message never quotes it.
      require(declared != null || extras != false) {
        "${at(path)}has a key the schema does not declare"
      }
      result[name] =
        when {
          declared != null -> coerce(entry, declared, "$path.$name")
          extraSchema != null -> ValueCoercer().coerce(entry, extraSchema, "$path.*")
          else -> entry
        }
    }
    for (name in schema.required.orEmpty()) {
      require(name in result) { "${at(path)}lacks required '$name'" }
    }
    return result
  }

  private fun at(path: String): String =
    if (path.isEmpty()) "the value " else "'${path.removePrefix(".")}' "

  private fun typeName(type: Type): String =
    when (type) {
      Type.INTEGER -> "an integer"
      Type.ARRAY -> "a list"
      Type.OBJECT -> "an object"
      else -> "a ${type.name.lowercase()}"
    }

  companion object {
    private val logger = LoggerFactory.getLogger(ValueCoercer::class)

    /**
     * Checks [value] against [schema] and returns the coerced result, or a [Result.failure] whose
     * message names [what] and parts of the schema, never the runtime value.
     */
    internal fun coerceValue(value: Any?, schema: Schema, what: String = "value"): Result<Any?> =
      try {
        Result.success(ValueCoercer().coerce(value, schema))
      } catch (e: IllegalArgumentException) {
        Result.failure(
          IllegalArgumentException(
            "validation error: $what does not match its schema: ${e.message}"
          )
        )
      }

    /**
     * Extracts a value from a [Content] message: returns its raw text when [schema] accepts a
     * string, and otherwise parses the text as JSON (falling back to the raw text if it is not
     * valid JSON).
     */
    internal fun coerceContent(content: Content, schema: Schema): Any? {
      val dropped = content.parts.count { it.text == null }
      if (dropped > 0) {
        logger.warn { "Reading a message as a value dropped $dropped non-text parts." }
      }
      val text = content.text()
      return if (SchemaUtils.acceptsString(schema)) text
      else SchemaUtils.readJson(text).getOrDefault(text)
    }

    /**
     * Returns [value] as an integer if it is an integral number, a whole floating-point number, or
     * a JSON number string representing one; otherwise `null`.
     */
    internal fun integerLiteral(value: Any?): Number? =
      when (value) {
        is Int,
        is Long,
        is Short,
        is Byte -> value
        is Double,
        is Float -> wholeLong(value.toDouble())
        is String ->
          when {
            SchemaUtils.JSON_INTEGER.matches(value) -> value.toLongOrNull()
            SchemaUtils.JSON_NUMBER.matches(value) -> value.toDoubleOrNull()?.let(::wholeLong)
            else -> null
          }
        else -> null
      }

    /**
     * Returns [value] as a finite number if it is one or a JSON number string representing one;
     * otherwise `null`.
     */
    internal fun numberLiteral(value: Any?): Number? =
      when (value) {
        is Double,
        is Float -> value.takeIf { it.toDouble().isFinite() }
        is Number -> value
        is String ->
          value.takeIf(SchemaUtils.JSON_NUMBER::matches)?.let {
            it.toLongOrNull() ?: it.toDoubleOrNull()?.takeIf { d -> d.isFinite() }
          }
        else -> null
      }

    /**
     * Returns [value] as a boolean if it is a [Boolean] or the string `"true"` / `"false"`;
     * otherwise `null`.
     */
    internal fun booleanLiteral(value: Any?): Boolean? =
      when (value) {
        is Boolean -> value
        is String -> value.toBooleanStrictOrNull()
        else -> null
      }

    /**
     * Converts [value] to a [Long] if it is a whole number within `Long` range.
     *
     * Both bounds are strict: a double at `2^63` or `-2^63` may be a rounded out-of-range value,
     * which `Double.toLong()` would saturate to `Long.MAX_VALUE` or `Long.MIN_VALUE`.
     */
    private fun wholeLong(value: Double): Long? =
      if (
        value % 1.0 == 0.0 && value > Long.MIN_VALUE.toDouble() && value < Long.MAX_VALUE.toDouble()
      ) {
        value.toLong()
      } else {
        null
      }
  }
}
