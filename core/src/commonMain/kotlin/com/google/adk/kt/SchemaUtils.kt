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

import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.serialization.Json
import com.google.adk.kt.serialization.adkJson
import com.google.adk.kt.serialization.jsonElementToAny
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import kotlin.reflect.KType
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.serializerOrNull

/**
 * Utility class for validating schemas.
 *
 * A schema that declares no type constrains nothing, so any value satisfies it. A schema that
 * carries `anyOf` is satisfied only when the value matches one of the alternatives, and that holds
 * alongside whatever else the schema declares.
 */
object SchemaUtils {

  /**
   * How many union alternatives a single validation may try before giving up.
   *
   * Nested unions are all checked against the same value, so the work grows with the product of the
   * branch counts rather than their sum, and a schema whose alternatives share objects can describe
   * millions of combinations in a handful of them. A real schema tries a few dozen alternatives, so
   * this leaves generous room while keeping the worst case in milliseconds.
   */
  private const val MAX_UNION_CHECKS = 1000

  /** The union alternatives still available to one validation, shared across its recursion. */
  private class UnionBudget {
    private var remaining = MAX_UNION_CHECKS

    /** Takes one alternative from the budget, or reports that none is left. */
    fun spend(): Boolean {
      if (remaining <= 0) return false
      remaining--
      return true
    }
  }

  /**
   * Signals that validation gave up rather than explore a schema's unions exhaustively.
   *
   * A distinct type so the reason survives being passed back up: the caller reports a type mismatch
   * for a property, which this is not, and relabelling it would hide the real cause.
   */
  private class SchemaTooComplexException(message: String) : IllegalArgumentException(message)

  private fun tooComplex(argsName: String): Result<Unit> =
    Result.failure(
      SchemaTooComplexException(
        "$argsName schema is too complex to validate: it tried more than $MAX_UNION_CHECKS union " +
          "alternatives, so its `anyOf` branches multiply out beyond what can be checked"
      )
    )

  /**
   * Matches a value against a schema type.
   *
   * @param value The value to match.
   * @param schema The schema to match against.
   * @param argsName The name of the arguments being validated (e.g., "Input" or "Output").
   * @return [Result.success] if the value matches the schema type, [Result.failure] wrapping an
   *   [IllegalArgumentException] otherwise.
   */
  private fun matchType(
    value: Any?,
    schema: Schema,
    argsName: String,
    budget: UnionBudget,
  ): Result<Unit> {
    // A schema that declares itself nullable has to accept null, or the validator contradicts the
    // schema it is validating against.
    if (value == null && schema.nullable == true) {
      return Result.success(Unit)
    }

    // A union is satisfied when any one member is, and it constrains the value on its own: a schema
    // carrying only `anyOf` has no type, which would otherwise be read as "no constraint".
    //
    // An object is the exception. It hands this whole schema to [validateMapOnSchema] below, which
    // reads the same union again under the rule that also enforces a member's `required`. Checking
    // it here too would spend the budget twice over per level and judge one union by two
    // definitions of a match -- and the looser one here never gets the last word anyway.
    val anyOf = schema.anyOf
    if (anyOf != null && schema.type != Type.OBJECT) {
      var matched = false
      for (member in anyOf) {
        if (!budget.spend()) return tooComplex(argsName)
        val memberResult = matchType(value, member, argsName, budget)
        if (memberResult.isSuccess) {
          matched = true
          break
        }
        // Giving up is not the same as not matching, and the reason only survives if it is carried
        // out of the loop: the budget can run out inside the last member, leaving no later `spend`
        // to report it.
        val failure = memberResult.exceptionOrNull()
        if (failure is SchemaTooComplexException) return Result.failure(failure)
      }
      if (!matched) {
        return Result.failure(
          IllegalArgumentException("$argsName value does not match any allowed schema: $value")
        )
      }
    }

    val type = schema.type ?: return Result.success(Unit) // If type is not specified, assume match.

    val matches =
      when (type) {
        Type.STRING -> value is String
        Type.INTEGER -> value is Int || value is Long
        Type.BOOLEAN -> value is Boolean
        Type.NUMBER -> value is Number
        Type.ARRAY -> {
          if (value !is Collection<*>) {
            return Result.failure(IllegalArgumentException("$argsName value is not a list: $value"))
          }
          val itemSchema = schema.items ?: return Result.success(Unit)
          for (item in value) {
            // Each element gets its own budget. The budget bounds how far a *schema* multiplies
            // out; the length of a list is data, not schema, and one shared budget made a long
            // list of ordinary values report the schema as too complex to validate. Every element
            // is checked against the same sub-schema, so the work per element is what needs
            // bounding, and the total stays linear in what the model actually sent.
            matchType(item, itemSchema, argsName, UnionBudget()).onFailure {
              return Result.failure(it)
            }
          }
          true
        }
        Type.OBJECT -> {
          if (value !is Map<*, *>) {
            return Result.failure(IllegalArgumentException("$argsName value is not a map: $value"))
          }
          @Suppress("UNCHECKED_CAST")
          return validateMapOnSchema(value as Map<String, Any?>, schema, argsName, budget)
        }
        Type.NULL -> value == null
        // No declared type is no constraint, the same as an absent one. Rejecting here would fail
        // a value the schema never said anything about.
        Type.TYPE_UNSPECIFIED -> return Result.success(Unit)
      }

    return if (matches) {
      Result.success(Unit)
    } else {
      Result.failure(IllegalArgumentException("$argsName value $value does not match type $type"))
    }
  }

  /**
   * Whether this schema could describe a map at all.
   *
   * A schema that declares no type constrains nothing, so it can; one that declares `OBJECT`
   * obviously can; one that declares any other type cannot, whatever else it says.
   */
  private fun Schema.canDescribeAMap(): Boolean =
    type == null || type == Type.OBJECT || type == Type.TYPE_UNSPECIFIED

  /**
   * How to name a schema in a validation error.
   *
   * [Schema] has far more fields than a typical one sets, so printing the data class buries the
   * mismatch under `=null` pairs. Only the shape a reader needs in order to see why the value was
   * rejected is reported.
   */
  private fun Schema.summary(): String =
    buildList {
        type?.let { add("type=$it") }
        properties?.keys?.let { add("properties=$it") }
        required?.let { add("required=$it") }
        anyOf?.let { add("anyOf=${it.size} alternatives") }
        enum?.let { add("enum=$it") }
      }
      .joinToString(prefix = "Schema(", postfix = ")")

  /**
   * Validates a map against a schema.
   *
   * @param args The map to validate.
   * @param schema The schema to validate against.
   * @param argsName The name of the arguments being validated (e.g., "Input" or "Output").
   * @return [Result.success] if the map matches the schema, [Result.failure] wrapping an
   *   [IllegalArgumentException] describing the first validation error otherwise.
   */
  fun validateMapOnSchema(args: Map<String, Any?>, schema: Schema, argsName: String): Result<Unit> =
    validateMapOnSchema(args, schema, argsName, UnionBudget())

  private fun validateMapOnSchema(
    args: Map<String, Any?>,
    schema: Schema,
    argsName: String,
    budget: UnionBudget,
  ): Result<Unit> {
    // What is being validated is a map, so a schema declaring some other type describes it in no
    // way at all. Nothing further down reads `type`: the key check is what used to turn a
    // non-object schema away, and it only runs for a schema that names properties. Rejecting here
    // keeps that from depending on whether properties happen to be declared, and it is the same
    // question the union walk below asks of each member.
    if (!schema.canDescribeAMap()) {
      return Result.failure(
        IllegalArgumentException(
          "$argsName schema does not describe an object: ${schema.summary()}"
        )
      )
    }
    // Every alternative is a schema in its own right and the map has to satisfy one of them. That
    // is ANDed with whatever else this schema declares, the way `matchType` reads a union for a
    // value -- a schema does not stop meaning what it says because it also carries an `anyOf`.
    val anyOf = schema.anyOf
    if (anyOf != null) {
      var matched = false
      for (member in anyOf) {
        if (!budget.spend()) return tooComplex(argsName)
        // The check at the top of this function would turn such a member away anyway, but doing it
        // here skips building a failure only to discard it -- this loop runs up to the budget's
        // thousand alternatives, and each rejection allocates a message describing the schema.
        if (!member.canDescribeAMap()) continue
        val memberResult = validateMapOnSchema(args, member, argsName, budget)
        if (memberResult.isSuccess) {
          matched = true
          break
        }
        // As in `matchType`: a member that exhausted the budget did not fail to match, and saying
        // so is only possible if the reason is carried out of the loop rather than dropped.
        val failure = memberResult.exceptionOrNull()
        if (failure is SchemaTooComplexException) return Result.failure(failure)
      }
      if (!matched) {
        return Result.failure(
          IllegalArgumentException("$argsName args match no allowed schema: ${schema.summary()}")
        )
      }
    }
    // Only a schema that names properties can say a key does not belong. A schema that names none
    // -- a union, a member of one, or an object left open on purpose -- is describing something
    // other than the key set, and reading its absent property map as "no key is allowed" would
    // reject every argument. An empty map is not the same statement: that one does say no key is
    // allowed, and is still enforced.
    val properties = schema.properties
    if (properties != null) {
      for ((key, value) in args) {
        // Check if the argument is in the schema.
        if (!properties.containsKey(key)) {
          return Result.failure(
            IllegalArgumentException(
              "$argsName arg: $key doesn't exist in ${argsName.lowercase()} schema: ${schema.summary()}"
            )
          )
        }
        // Check if the argument type matches the schema type.
        matchType(value, properties[key]!!, argsName, budget).onFailure {
          if (it is SchemaTooComplexException) return Result.failure(it)
          return Result.failure(
            IllegalArgumentException(
              "$argsName arg: $key type does not match ${argsName.lowercase()} schema: ${schema.summary()}",
              it,
            )
          )
        }
      }
    }
    // Check if all required arguments are present.
    schema.required?.forEach { required ->
      if (!args.containsKey(required)) {
        return Result.failure(
          IllegalArgumentException("$argsName args does not contain required $required")
        )
      }
    }
    return Result.success(Unit)
  }

  /**
   * Parses a model output string as JSON and validates it against a schema.
   *
   * Mirrors `SchemaUtils.validateOutputSchema` in the Java ADK and `validate_schema` in the Python
   * ADK: the [output] is expected to be a JSON object that matches [schema].
   *
   * Only top-level object schemas are supported: [output] must parse to a JSON object (it is
   * decoded via [Json.fromJsonToMap]). Top-level array or primitive schemas are not supported and
   * will yield a [Result.failure]. This matches the Java ADK; the Python ADK additionally supports
   * list/primitive output schemas.
   *
   * @param output The model output string to parse and validate.
   * @param schema The schema to validate against.
   * @return [Result.success] wrapping the parsed map if it is valid JSON matching [schema];
   *   [Result.failure] if the string is not valid JSON object or does not match the schema.
   */
  fun validateOutputSchema(output: String, schema: Schema): Result<Map<String, Any?>> =
    runCatching {
      val parsed = Json.fromJsonToMap(output)
      validateMapOnSchema(parsed, schema, "Output").getOrThrow()
      parsed
    }

  /**
   * Checks any [value] against [schema] with the rules [validateMapOnSchema] applies to an
   * argument, and returns it; a null [value] passes. A [Content] becomes its text, read as JSON
   * unless [schema] takes a string or the text is not JSON. A failure names [argsName] and, for a
   * map, a reason that names only parts of the schema, but never the value.
   */
  internal fun validateValue(
    value: Any?,
    schema: Schema,
    argsName: String = "value",
  ): Result<Any?> {
    if (value == null) return Result.success(null)
    val data =
      if (value !is Content) value
      else value.text().let { if (acceptsString(schema)) it else readJson(it).getOrDefault(it) }
    val detail: String?
    if (data is Map<*, *>) {
      val args = data.entries.associate { (k, v) -> k.toString() to v }
      val failure = validateMapOnSchema(args, schema, "Value").exceptionOrNull()
      if (failure == null) return Result.success(data)
      val properties = schema.properties
      // An undeclared key is data, so it goes unnamed; other messages name only the schema's parts.
      detail =
        if (properties != null && args.keys.any { it !in properties }) {
          "it has a key the schema does not declare"
        } else {
          failure.message
        }
    } else {
      val failure = matchType(data, schema, "Value", UnionBudget()).exceptionOrNull()
      if (failure == null) return Result.success(data)
      // A type mismatch message quotes the value; only running out of budget does not.
      detail = (failure as? SchemaTooComplexException)?.message
    }
    val suffix = detail?.let { ": $it" } ?: "."
    return Result.failure(
      IllegalArgumentException("validation error: $argsName does not match its schema$suffix")
    )
  }

  /** Whether [schema] takes a string, directly or through an `anyOf` alternative. */
  internal fun acceptsString(schema: Schema): Boolean =
    schema.type == Type.STRING || schema.anyOf.orEmpty().any { acceptsString(it) }

  /**
   * Reads [text] as JSON into plain Kotlin values. Text that is not JSON fails, including an
   * unquoted word, which the JSON parser alone takes as a literal, and the failure never quotes it.
   */
  @OptIn(FrameworkInternalApi::class)
  internal fun readJson(text: String): Result<Any?> {
    val element =
      try {
        adkJson.parseToJsonElement(text)
      } catch (e: SerializationException) {
        null
      }
    if (element == null || !isStrictJson(element)) {
      return Result.failure(IllegalArgumentException("validation error: value is not valid JSON."))
    }
    return Result.success(jsonElementToAny(element))
  }

  private fun isStrictJson(element: JsonElement): Boolean =
    when (element) {
      is JsonObject -> element.values.all { isStrictJson(it) }
      is JsonArray -> element.all { isStrictJson(it) }
      is JsonPrimitive ->
        element is JsonNull ||
          element.isString ||
          element.booleanOrNull != null ||
          element.doubleOrNull?.isFinite() == true
    }

  /**
   * Infers a [Schema] for the JSON form of [kType] from its serializer's descriptor, so it covers
   * every `@Serializable` type. Returns `null` when the type has no serializer or its shape cannot
   * be described, such as `Any`, `Unit`, a star projection, or a polymorphic class.
   */
  @ExperimentalWorkflowApi
  @OptIn(FrameworkInternalApi::class)
  fun inferSchema(kType: KType): Schema? {
    val nullable = if (kType.isMarkedNullable) true else null
    return when (kType.classifier) {
      // Neither has a serializer, but a value of either has a JSON form.
      Number::class -> Schema(type = Type.NUMBER, nullable = nullable)
      CharSequence::class -> Schema(type = Type.STRING, nullable = nullable)
      else -> serializerFor(kType)?.descriptor?.let { schemaOf(it, kType.isMarkedNullable) }
    }
  }

  /** Returns the [adkJson] serializer for [kType], or `null` when there is none. */
  @OptIn(FrameworkInternalApi::class)
  internal fun serializerFor(kType: KType): KSerializer<Any?>? =
    try {
      adkJson.serializersModule.serializerOrNull(kType)
    } catch (e: IllegalArgumentException) {
      // Thrown for a star projection or a type parameter, which have no serializer either.
      null
    }

  /**
   * Maps [descriptor] to the [Schema] of its JSON form, or returns `null` when that form is
   * unconstrained or cannot be described. A list whose elements cannot be described still maps to
   * an array, and a map to an object, each without constraints on its contents.
   */
  internal fun schemaOf(
    descriptor: SerialDescriptor,
    nullable: Boolean,
    visited: MutableSet<String> = mutableSetOf(),
  ): Schema? = describe(descriptor, nullable, visited).takeUnless { it === CYCLE }

  /** Marks a type that contains itself; it propagates up so the whole type has no schema. */
  private val CYCLE = Schema()

  private fun describe(
    descriptor: SerialDescriptor,
    nullable: Boolean,
    visited: MutableSet<String>,
  ): Schema? {
    val isNullable = nullable || descriptor.isNullable
    if (descriptor.isInline) {
      return describe(descriptor.getElementDescriptor(0), isNullable, visited)
    }
    val nullableFlag = if (isNullable) true else null
    return when (descriptor.kind) {
      PrimitiveKind.STRING,
      PrimitiveKind.CHAR -> Schema(type = Type.STRING, nullable = nullableFlag)
      PrimitiveKind.INT,
      PrimitiveKind.LONG,
      PrimitiveKind.SHORT,
      PrimitiveKind.BYTE -> Schema(type = Type.INTEGER, nullable = nullableFlag)
      PrimitiveKind.FLOAT,
      PrimitiveKind.DOUBLE -> Schema(type = Type.NUMBER, nullable = nullableFlag)
      PrimitiveKind.BOOLEAN -> Schema(type = Type.BOOLEAN, nullable = nullableFlag)
      SerialKind.ENUM ->
        Schema(
          type = Type.STRING,
          enum = List(descriptor.elementsCount) { descriptor.getElementName(it) },
          nullable = nullableFlag,
        )
      StructureKind.LIST -> {
        val items = describe(descriptor.getElementDescriptor(0), nullable = false, visited)
        if (items === CYCLE) CYCLE
        else Schema(type = Type.ARRAY, items = items, nullable = nullableFlag)
      }
      StructureKind.MAP -> Schema(type = Type.OBJECT, nullable = nullableFlag)
      StructureKind.CLASS -> classSchemaOf(descriptor, nullableFlag, visited)
      // No shape to describe; returning here also keeps the recursive JsonElement descriptor
      // finite.
      else -> null
    }
  }

  private fun classSchemaOf(
    descriptor: SerialDescriptor,
    nullable: Boolean?,
    visited: MutableSet<String>,
  ): Schema? {
    val serialName = descriptor.serialName.removeSuffix("?")
    if (!visited.add(serialName)) return CYCLE
    try {
      val properties = mutableMapOf<String, Schema>()
      val required = mutableListOf<String>()
      for (i in 0 until descriptor.elementsCount) {
        val elementDescriptor = descriptor.getElementDescriptor(i)
        val property = describe(elementDescriptor, nullable = false, visited)
        if (property == null || property === CYCLE) return property
        properties[descriptor.getElementName(i)] = property
        if (!descriptor.isElementOptional(i)) required += descriptor.getElementName(i)
      }
      return Schema(
        type = Type.OBJECT,
        properties = properties,
        required = required.ifEmpty { null },
        nullable = nullable,
      )
    } finally {
      visited.remove(serialName)
    }
  }

  /**
   * Whether every value [output] describes also satisfies [input]. An [output] object without
   * properties or an array without items is of unknown shape, so its contents are not checked.
   */
  internal fun isSchemaCompatible(output: Schema, input: Schema): Boolean {
    if (input.isUnconstrained()) return true
    if (output.nullable == true && input.nullable != true) return false
    if (!output.anyOf.isNullOrEmpty()) {
      return output.anyOf.all { isSchemaCompatible(it, input) }
    }
    if (!input.anyOf.isNullOrEmpty()) {
      return input.anyOf.any { isSchemaCompatible(output, it) }
    }
    if (input.type != null && input.type != Type.TYPE_UNSPECIFIED) {
      val typesMatch =
        output.type == input.type || (output.type == Type.INTEGER && input.type == Type.NUMBER)
      if (!typesMatch) return false
    }
    if (input.enum != null && (output.enum == null || !input.enum.containsAll(output.enum))) {
      return false
    }
    val outProps = output.properties
    if (outProps != null) {
      if (!output.required.orEmpty().toSet().containsAll(input.required.orEmpty())) return false
      val inProps = input.properties
      if (inProps != null) {
        if (!inProps.keys.containsAll(outProps.keys)) return false
        if (outProps.any { (k, outProp) -> !isSchemaCompatible(outProp, inProps.getValue(k)) }) {
          return false
        }
      }
    }
    val outItems = output.items
    val inItems = input.items
    if (outItems != null && inItems != null && !isSchemaCompatible(outItems, inItems)) {
      return false
    }
    return true
  }

  private fun Schema.isUnconstrained(): Boolean =
    type == null &&
      nullable == null &&
      anyOf == null &&
      enum == null &&
      required == null &&
      properties == null &&
      items == null
}
