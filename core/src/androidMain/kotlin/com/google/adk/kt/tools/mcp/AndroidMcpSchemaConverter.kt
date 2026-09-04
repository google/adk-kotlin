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

package com.google.adk.kt.tools.mcp

import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import com.google.adk.kt.types.toAny
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** Converts the Kotlin MCP SDK's JSON Schema representation to ADK schemas on Android. */
internal fun ToolSchema.toAdkSchema(): Schema {
  val scope = RefScope(defs ?: JsonObject(emptyMap()))
  val properties = properties.toAdkSchemaMap(depth = 1, scope)
  return Schema(type = Type.OBJECT, properties = properties, required = required.requiredIn(properties))
}

/** Vertex rejects a response schema containing anyOf, so omit only that optional description. */
internal fun ToolSchema.toAdkResponseSchema(): Schema? =
  toAdkSchema().takeIf { it.isTyped() && !it.containsAnyOf() }

private const val MAX_SCHEMA_DEPTH = 32
private const val MAX_REF_EXPANSIONS = 512

private class RefScope(
  val definitions: JsonObject,
  val visited: Set<String> = emptySet(),
  private val remaining: IntArray = intArrayOf(MAX_REF_EXPANSIONS),
) {
  fun spend(): Boolean = if (remaining[0] <= 0) false else { remaining[0]--; true }

  fun following(ref: String): RefScope = RefScope(definitions, visited + ref, remaining)
}

private fun JsonElement.toAdkSchema(depth: Int, scope: RefScope): Schema {
  if (depth >= MAX_SCHEMA_DEPTH || isBooleanSchema()) return Schema(type = Type.OBJECT)
  val schema = this as? JsonObject ?: return Schema()
  val ref = schema.string("\$ref")
  if (ref != null && ref in scope.visited) {
    return Schema(type = Type.OBJECT, description = "Circular ref to ${ref.substringAfterLast('/')}")
  }
  schema.resolveRef(scope.definitions)?.let { resolved ->
    if (!scope.spend()) return Schema(type = Type.OBJECT)
    return resolved.toAdkSchema(depth + 1, scope.following(checkNotNull(ref)))
  }

  val declared = schema.declaredTypes()
  val knownTypes = declared.names.filter { it.toAdkType() != null }.ifEmpty { declared.names }
  if (knownTypes.size > 1) {
    return Schema(
      anyOf = knownTypes.map { schema.branchOf(it).toAdkSchema(depth, scope) },
      nullable = schema.boolean("nullable") ?: declared.nullable,
    )
  }

  val anyOfMembers = schema["anyOf"].toAnyOfSchemas(depth + 1, scope)
  val anyOfAllowsNull = anyOfMembers?.any { it.type == Type.NULL } == true
  val anyOf = anyOfMembers?.filterNot { it.type == Type.NULL }?.takeIf { it.isNotEmpty() }
  val soleAnyOfMember = anyOf?.singleOrNull()
  if (soleAnyOfMember != null && anyOfAllowsNull && schema["type"] == null) {
    return soleAnyOfMember.copy(
      nullable = true,
      description = soleAnyOfMember.description ?: schema.string("description"),
      title = soleAnyOfMember.title ?: schema.string("title"),
      default = soleAnyOfMember.default ?: schema["default"]?.toAny(),
    )
  }

  val typeName = knownTypes.singleOrNull()
  val type = typeName?.toAdkType()
  if (typeName != null && type == null) throw IllegalArgumentException("Unknown type: $typeName")
  val properties = (schema["properties"] as? JsonObject).toAdkSchemaMap(depth + 1, scope)
  val isNumber = type == Type.INTEGER || type == Type.NUMBER
  val isString = type == Type.STRING
  val isArray = type == Type.ARRAY
  return Schema(
    type = type,
    properties = properties,
    items = schema["items"].toItemsSchema(depth + 1, scope) ?: defaultItems(type),
    required = schema.stringArray("required").requiredIn(properties),
    description = schema.string("description"),
    enum = schema["enum"].toEnumValues(),
    format = schema.geminiFormat(typeName),
    nullable = schema.boolean("nullable") ?: if (declared.nullable || anyOfAllowsNull) true else null,
    default = schema["default"]?.toAny(),
    anyOf = anyOf,
    title = schema.string("title"),
    pattern = if (isString) schema.string("pattern") else null,
    minimum = if (isNumber) schema.number("minimum") else null,
    maximum = if (isNumber) schema.number("maximum") else null,
    minLength = if (isString) schema.long("minLength") else null,
    maxLength = if (isString) schema.long("maxLength") else null,
    minItems = if (isArray) schema.long("minItems") else null,
    maxItems = if (isArray) schema.long("maxItems") else null,
  )
}

private fun JsonObject?.toAdkSchemaMap(depth: Int, scope: RefScope): Map<String, Schema>? =
  this?.mapNotNull { (name, value) ->
    if (value is JsonObject || value.isBooleanSchema()) name to value.toAdkSchema(depth, scope) else null
  }?.toMap()

private fun JsonElement?.toItemsSchema(depth: Int, scope: RefScope): Schema? =
  if (this is JsonObject || this?.isBooleanSchema() == true) toAdkSchema(depth, scope) else null

private fun JsonObject.resolveRef(definitions: JsonObject): JsonObject? {
  val ref = string("\$ref") ?: return null
  if (!ref.startsWith("#/\$defs/")) return null
  val target = definitions[ref.substringAfterLast('/')] as? JsonObject ?: return null
  return JsonObject(target + filterKeys { it != "\$ref" })
}

private fun JsonObject.declaredTypes(): DeclaredTypes =
  when (val value = this["type"]) {
    is JsonPrimitive -> DeclaredTypes(listOfNotNull(value.takeIf { it.isString }?.contentOrNull), false)
    is JsonArray -> {
      val names = value.mapNotNull { (it as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.contentOrNull }
      val realNames = names.filter { it != "null" }
      if (realNames.isEmpty()) DeclaredTypes(listOfNotNull(names.firstOrNull()), false)
      else DeclaredTypes(realNames, realNames.size != names.size)
    }
    else -> DeclaredTypes(emptyList(), false)
  }

private data class DeclaredTypes(val names: List<String>, val nullable: Boolean)

private fun JsonObject.branchOf(typeName: String): JsonObject =
  JsonObject(buildMap {
    put("type", JsonPrimitive(typeName))
    RELATED_KEYWORDS[typeName].orEmpty().forEach { key -> this@branchOf[key]?.let { put(key, it) } }
  })

private val RELATED_KEYWORDS =
  mapOf(
    "number" to listOf("description", "enum", "format", "maximum", "minimum", "title"),
    "integer" to listOf("description", "enum", "format", "maximum", "minimum", "title"),
    "string" to listOf("description", "enum", "format", "maxLength", "minLength", "pattern", "title"),
    "object" to listOf("anyOf", "description", "properties", "required", "title"),
    "array" to listOf("description", "items", "maxItems", "minItems", "title"),
    "boolean" to listOf("description", "title"),
  )

private fun JsonElement?.toAnyOfSchemas(depth: Int, scope: RefScope): List<Schema>? =
  (this as? JsonArray)?.mapNotNull { member ->
    if (member !is JsonObject && !member.isBooleanSchema()) return@mapNotNull null
    runCatching { member.toAdkSchema(depth, scope) }.getOrNull()?.takeIf { it.isTyped() }
  }?.takeIf { it.isNotEmpty() }

private fun JsonElement?.toEnumValues(): List<String>? =
  (this as? JsonArray)?.mapNotNull { value ->
    when (value) {
      JsonNull -> null
      is JsonPrimitive -> value.contentOrNull
      else -> value.toString()
    }
  }?.takeIf { it.isNotEmpty() }

private fun JsonElement?.isBooleanSchema(): Boolean =
  this is JsonPrimitive && !isString && booleanOrNull != null

private fun String.toAdkType(): Type? =
  when (this) {
    "string" -> Type.STRING
    "integer" -> Type.INTEGER
    "number" -> Type.NUMBER
    "boolean" -> Type.BOOLEAN
    "array" -> Type.ARRAY
    "object" -> Type.OBJECT
    "null" -> Type.NULL
    else -> null
  }

private fun JsonObject.string(name: String): String? =
  (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
private fun JsonObject.boolean(name: String): Boolean? = (this[name] as? JsonPrimitive)?.booleanOrNull
private fun JsonObject.number(name: String): Double? = (this[name] as? JsonPrimitive)?.doubleOrNull
private fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.longOrNull
private fun JsonObject.stringArray(name: String): List<String>? =
  (this[name] as? JsonArray)?.mapNotNull {
    (it as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.contentOrNull
  }

private fun JsonObject.geminiFormat(typeName: String?): String? {
  val format = string("format") ?: return null
  return when (typeName) {
    "integer", "number" -> format.takeIf { it == "int32" || it == "int64" }
    "string" -> format.takeIf { it == "date-time" || it == "enum" }
    else -> null
  }
}

private fun List<String>?.requiredIn(properties: Map<String, Schema>?): List<String>? =
  this?.filter { properties?.containsKey(it) == true }?.takeIf { it.isNotEmpty() }
private fun defaultItems(type: Type?): Schema? = if (type == Type.ARRAY) Schema(type = Type.STRING) else null
private fun Schema.containsAnyOf(): Boolean =
  anyOf != null || items?.containsAnyOf() == true || properties?.values?.any { it.containsAnyOf() } == true
private fun Schema.isTyped(): Boolean = type != null || properties != null || items != null || anyOf != null
