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

import com.google.adk.kt.logging.LoggerFactory
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import io.modelcontextprotocol.spec.McpSchema
import io.modelcontextprotocol.spec.McpSchema.JsonSchema

/**
 * Converts between MCP schema types and ADK types.
 *
 * A tool's `inputSchema` reaches us as [JsonSchema], whose `properties` are raw JSON maps, so every
 * keyword a server declares on a *property* survives transport and is available here. The record
 * itself, however, keeps only `type`, `properties`, `required`, `additionalProperties`, `$defs` and
 * `definitions`, so keywords written at the *top level* of an `inputSchema` (a `description`, for
 * instance) are dropped by the SDK before ADK sees them and cannot be recovered.
 */
internal object McpSchemaConverter {

  private val logger = LoggerFactory.getLogger(McpSchemaConverter::class)

  /**
   * Maximum nesting depth walked while converting a JSON Schema.
   *
   * A tool schema is data supplied by a remote server, so the recursive descent over `properties`
   * and `items` is bounded: at this depth a sub-schema converts to an untyped [Schema] instead of
   * recursing, so a pathologically deep schema cannot overflow the stack.
   */
  private const val MAX_SCHEMA_DEPTH = 32

  /**
   * How many `$ref`s one conversion may expand.
   *
   * The depth cap bounds how deep a schema goes, not how much work it costs. A chain of distinct
   * definitions that each reference the next from several properties expands multiplicatively, so a
   * schema small enough to sit in one request can still exhaust memory before it ever reaches the
   * depth cap -- and a server the client does not control supplies it.
   */
  private const val MAX_REF_EXPANSIONS = 512

  /**
   * What a `$ref` is resolved against, plus the guards that keep resolving it finite.
   *
   * [visited] is the chain of refs open on the path to here, so a definition that reaches itself is
   * cut where it closes; it is per path, so two siblings may still reference the same definition.
   * The expansion budget is shared across the whole conversion, which is what bounds a wide acyclic
   * graph that [visited] never fires on.
   */
  private class RefScope(
    val definitions: Map<String, Any>,
    val visited: Set<String> = emptySet(),
    private val remaining: IntArray = intArrayOf(MAX_REF_EXPANSIONS),
  ) {
    /** Takes one expansion from the shared budget, or returns false once it is spent. */
    fun spend(): Boolean {
      if (remaining[0] <= 0) return false
      remaining[0]--
      return true
    }

    fun following(ref: String) = RefScope(definitions, visited + ref, remaining)
  }

  /** Converts an [McpSchema.Tool] to an [FunctionDeclaration]. */
  fun McpSchema.Tool.toAdkFunctionDeclaration(): FunctionDeclaration =
    FunctionDeclaration(
      name = name(),
      description = description() ?: "",
      parameters = inputSchema()?.toAdkSchema(),
      // Unlike inputSchema, outputSchema reaches us as a raw JSON map, so nothing is lost before
      // conversion.
      response = outputSchema().safeCastToMapStringAny()?.let { toResponseSchema(it) },
    )

  /**
   * Converts a tool's output schema, yielding `null` when it cannot be converted.
   *
   * The output schema only describes what a tool returns, so a server that sends one this converter
   * rejects costs the model that description rather than the whole tool. The input schema is not
   * treated this way: a parameter contract nothing could convert would leave the model calling the
   * tool against a description that was never validated.
   */
  private fun toResponseSchema(map: Map<String, Any>): Schema? {
    val converted =
      try {
        parsePropertyMap(map, 0, RefScope(map.declaredDefinitions()))
      } catch (e: IllegalArgumentException) {
        logger.warn { "MCP tool output schema could not be converted, dropping it: ${e.message}" }
        return null
      }
    // Vertex takes a response schema containing `anyOf` only through `responseJsonSchema`, which
    // this declaration has no field for, and rejects the request outright otherwise -- every tool
    // call, not just this one. Describing the output is worth less than that.
    if (converted.containsAnyOf()) {
      logger.warn { "MCP tool output schema contains anyOf, which Vertex rejects; dropping it" }
      return null
    }
    // An output schema that says nothing is not worth sending.
    return converted.takeIf { it.isTyped() }
  }

  /** Whether this schema or anything nested inside it carries an `anyOf`. */
  private fun Schema.containsAnyOf(): Boolean =
    anyOf != null ||
      items?.containsAnyOf() == true ||
      properties?.values?.any { it.containsAnyOf() } == true

  /**
   * The schemas a JSON Schema declares under `$defs` or the older `definitions`.
   *
   * A raw schema map carries them as ordinary keys; [JsonSchema] has them as components. `$defs`
   * wins a name clash, being the current spelling.
   */
  private fun Map<String, Any>.declaredDefinitions(): Map<String, Any> = buildMap {
    this@declaredDefinitions["definitions"].safeCastToMapStringAny()?.let { putAll(it) }
    this@declaredDefinitions["\$defs"].safeCastToMapStringAny()?.let { putAll(it) }
  }

  /**
   * Replaces a `$ref` with the schema it names, or returns `null` when there is nothing to replace.
   *
   * Keywords written beside the `$ref` refine the referenced schema, so they win over it -- that is
   * how a server attaches a `description` or a `default` to a shared definition. This follows ADK
   * Python; JSON Schema 2020-12 would instead apply both, so a sibling `minLength` would narrow the
   * referenced one rather than replace it.
   */
  private fun Map<String, Any>.resolveRef(definitions: Map<String, Any>): Map<String, Any>? {
    val ref = this["\$ref"] as? String ?: return null
    if (!ref.startsWith("#/\$defs/") && !ref.startsWith("#/definitions/")) return null
    val target = definitions[ref.substringAfterLast('/')].safeCastToMapStringAny() ?: return null
    return target + filterKeys { it != "\$ref" }
  }

  /**
   * The schema a self-referential `$ref` collapses to.
   *
   * A recursive model -- a tree node holding more of itself -- has no finite expansion, so the
   * cycle is cut where it closes and described instead. Expanding it until a depth limit stopped it
   * would send the model dozens of nested copies of the same type.
   */
  private fun circularRef(ref: String): Schema =
    Schema(type = Type.OBJECT, description = "Circular ref to ${ref.substringAfterLast('/')}")

  private fun Any?.safeCastToMapStringAny(): Map<String, Any>? {
    val map = this as? Map<*, *> ?: return null
    val result = mutableMapOf<String, Any>()
    for ((k, v) in map) {
      if (k is String && v != null) {
        result[k] = v
      }
    }
    return result
  }

  private fun Any?.safeCastToListString(): List<String>? {
    val list = this as? List<*> ?: return null
    val result = mutableListOf<String>()
    for (item in list) {
      if (item is String) {
        result.add(item)
      }
    }
    return result
  }

  /** Parses a type string into an ADK [Type]. */
  fun parseTypeString(typeStr: String?): Type =
    typeStr.typeOrNull() ?: throw IllegalArgumentException("Unknown type: $typeStr")

  /**
   * The [Type] this JSON Schema type name denotes, or `null` when it is not a name JSON Schema
   * defines.
   *
   * Separate from [parseTypeString] so a union can ask whether a member is usable without the
   * answer being an exception.
   */
  private fun String?.typeOrNull(): Type? =
    when (this) {
      null -> Type.TYPE_UNSPECIFIED
      "string" -> Type.STRING
      "integer" -> Type.INTEGER
      "number" -> Type.NUMBER
      "boolean" -> Type.BOOLEAN
      "array" -> Type.ARRAY
      "object" -> Type.OBJECT
      "null" -> Type.NULL
      else -> null
    }

  /** Converts a [JsonSchema] to an ADK [Schema]. */
  fun JsonSchema.toAdkSchema(): Schema {
    // The record keeps both spellings of the definitions block as components, so a `$ref` in any
    // property below can be resolved against them.
    val definitions = buildMap {
      definitions()?.let { putAll(it) }
      defs()?.let { putAll(it) }
    }
    val properties = properties().toAdkSchemaMap(depth = 1, scope = RefScope(definitions))
    val type = parseTypeString(type())
    return Schema(
      type = type,
      properties = properties,
      // The record carries no `items` component, so an array here can only take the default.
      items = defaultItems(type),
      required = required().requiredIn(properties),
      description = null,
    )
  }

  /** Parses a property map into an ADK [Schema]. */
  fun parsePropertyMap(
    map: Map<String, Any>,
    depth: Int = 0,
    definitions: Map<String, Any> = emptyMap(),
  ): Schema = parsePropertyMap(map, depth, RefScope(definitions))

  private fun parsePropertyMap(map: Map<String, Any>, depth: Int, scope: RefScope): Schema {
    if (depth >= MAX_SCHEMA_DEPTH) {
      logger.warn {
        "MCP tool schema nests deeper than $MAX_SCHEMA_DEPTH levels; converting the sub-schema at " +
          "that depth to an untyped object."
      }
      return Schema(type = Type.OBJECT)
    }

    // A `$ref` stands in for a schema declared once and used in several places, which is how
    // Pydantic writes a nested model. A definition that reaches itself is cut where it closes, and
    // a wide acyclic graph is bounded by the shared expansion budget.
    val ref = map["\$ref"] as? String
    if (ref != null && ref in scope.visited) {
      return circularRef(ref)
    }
    map.resolveRef(scope.definitions)?.let {
      if (!scope.spend()) {
        logger.warn {
          "MCP tool schema expands more than $MAX_REF_EXPANSIONS \$refs; converting the rest to " +
            "an untyped object."
        }
        return Schema(type = Type.OBJECT)
      }
      return parsePropertyMap(it, depth + 1, scope.following(checkNotNull(ref)))
    }
    if (map.containsKey("\$ref")) {
      logger.warn { "MCP tool schema has an unresolvable \$ref: ${map["\$ref"]}" }
    }

    // A union of two or more real types cannot be one ADK `Schema`, but it is exactly an `anyOf` of
    // one sub-schema per type, each carrying the keywords that describe that type. Every branch is
    // kept, the way Python's `Schema.from_json_schema` does it, rather than picking a winner.
    val declared = map["type"].declaredTypes()
    // A name JSON Schema does not define cannot become a branch, and the alternatives beside it
    // still describe the value, so it is dropped rather than failing the whole declaration. ADK
    // Python arrives at the same place from the other direction: `_sanitize_schema_type` reduces
    // the union to one member and never looks at the rest. A union of nothing but unknown names
    // has no alternative left to describe anything, so it is parsed as written and throws, the way
    // a single unknown type does.
    val names = declared.names.filter { it.typeOrNull() != null }.ifEmpty { declared.names }
    if (names.size > 1) {
      return Schema(
        anyOf = names.map { parsePropertyMap(it.branchOf(map), depth, scope) },
        // A server may spell nullability as a `"null"` member, as the `nullable` keyword, or both,
        // so the union reads it the same way a single type does.
        nullable = (map["nullable"] as? Boolean) ?: if (declared.nullable) true else null,
      )
    }

    // A `"null"` in an `anyOf` says the same thing as a `"null"` in `type`, so it is reported the
    // same way rather than left in the union.
    val anyOfMembers = map["anyOf"].toAnyOfSchemas(depth + 1, scope)
    val anyOfAllowsNull = anyOfMembers?.any { it.type == Type.NULL } == true
    val anyOf = anyOfMembers?.filterNot { it.type == Type.NULL }?.takeIf { it.isNotEmpty() }

    val soleAnyOfMember = anyOf?.singleOrNull()
    if (soleAnyOfMember != null && anyOfAllowsNull && map["type"] == null) {
      // An `anyOf` of X and null is how a server spells an optional X, so fold it in rather than
      // emit a one-member union: `SchemaUtils` and the Firebase converter both branch on `type` and
      // ignore `anyOf`. A union without a null member is left alone, being a real union.
      // The keywords a server writes beside the union describe the folded type, so they are carried
      // over: `Optional[int] = 5` puts the `default` outside the `anyOf` and would otherwise lose
      // it. An outer `enum` is not carried, matching Python, because the values belong to whichever
      // arm they came from rather than to the union.
      return soleAnyOfMember.copy(
        nullable = true,
        description = soleAnyOfMember.description ?: map["description"] as? String,
        title = soleAnyOfMember.title ?: map["title"] as? String,
        default = soleAnyOfMember.default ?: map["default"],
      )
    }

    val typeName = names.singleOrNull()
    val type = parseTypeString(typeName)
    val properties = map["properties"].safeCastToMapStringAny().toAdkSchemaMap(depth + 1, scope)
    // A keyword that does not describe the resolved type would hang a numeric bound off a string,
    // so only that type's are read.
    val isNumber = type == Type.INTEGER || type == Type.NUMBER
    val isString = type == Type.STRING
    val isArray = type == Type.ARRAY
    val isObject = type == Type.OBJECT
    return Schema(
      // "No type" is spelled by leaving the field unset, never by naming `TYPE_UNSPECIFIED`. The
      // genai `Type` is a string wrapper, so that name would go out as a literal `"type"` saying
      // nothing at all -- and a union, an unresolved `$ref`, an empty `type` list and a property
      // carrying only a description all end up here, which would otherwise put the same absence of
      // information on the wire two different ways. genai's `from_json_schema` also leaves it
      // unset, defaulting a type only when there is no `anyOf` to describe the value instead.
      type = type.takeIf { it != Type.TYPE_UNSPECIFIED },
      properties = properties,
      items =
        map["items"].let { items ->
          if (items is Boolean) booleanSubSchema()
          else items.safeCastToMapStringAny()?.let { parsePropertyMap(it, depth + 1, scope) }
        } ?: defaultItems(type),
      required = map["required"].safeCastToListString().requiredIn(properties),
      description = map["description"] as? String,
      enum = map["enum"].toEnumValues(),
      format = map.geminiFormat(typeName),
      nullable =
        (map["nullable"] as? Boolean) ?: if (declared.nullable || anyOfAllowsNull) true else null,
      default = map["default"],
      anyOf = anyOf,
      title = map["title"] as? String,
      pattern = if (isString) map["pattern"] as? String else null,
      minimum = if (isNumber) map.doubleOrNull("minimum") else null,
      maximum = if (isNumber) map.doubleOrNull("maximum") else null,
      minLength = if (isString) map.longOrNull("minLength") else null,
      maxLength = if (isString) map.longOrNull("maxLength") else null,
      minItems = if (isArray) map.longOrNull("minItems") else null,
      maxItems = if (isArray) map.longOrNull("maxItems") else null,
      minProperties = if (isObject) map.longOrNull("minProperties") else null,
      maxProperties = if (isObject) map.longOrNull("maxProperties") else null,
    )
  }

  /**
   * Narrows `required` to the properties the schema actually declares.
   *
   * A property whose sub-schema is not a JSON object is dropped during conversion, and a server can
   * also mark a name required without declaring it at all. Either way the backend rejects a
   * `required` entry it cannot resolve, failing every request the agent makes rather than the one
   * tool.
   *
   * Filtering everything out leaves the field unset rather than empty: "these properties are
   * required" and "no property is required" are the same statement, and only one of them needs
   * saying.
   */
  private fun List<String>?.requiredIn(properties: Map<String, Schema>?): List<String>? =
    this?.filter { properties?.containsKey(it) == true }?.takeIf { it.isNotEmpty() }

  /**
   * What a boolean sub-schema converts to.
   *
   * JSON Schema allows `true` (accepts anything) and `false` (accepts nothing) in place of a schema
   * object. Neither has a typed equivalent, and ADK Python maps both to an object, so the shape is
   * kept rather than dropped -- dropping would take the value off the contract while the server
   * still expects it.
   */
  private fun booleanSubSchema(): Schema = Schema(type = Type.OBJECT)

  /**
   * The `items` an array falls back to when the server declared none, since the backend rejects an
   * array schema without them and fails every request the agent makes, not just the one tool.
   */
  private fun defaultItems(type: Type): Schema? =
    if (type == Type.ARRAY) Schema(type = Type.STRING) else null

  /** Converts a JSON Schema `properties` map, dropping entries that are not themselves objects. */
  private fun Map<String, Any>?.toAdkSchemaMap(depth: Int, scope: RefScope): Map<String, Schema>? =
    this?.mapNotNull { (key, value) ->
        if (value is Boolean) key to booleanSubSchema()
        else value.safeCastToMapStringAny()?.let { key to parsePropertyMap(it, depth, scope) }
      }
      ?.toMap()

  /**
   * Reads the JSON Schema `type` keyword, which is either a single type name or a union of them.
   *
   * A `"null"` member is reported as nullability rather than as a type of its own, since that is
   * how a server marks an argument optional. Whatever real types remain are all returned; the
   * caller splits a union of two or more into an `anyOf` rather than choosing between them.
   */
  private fun Any?.declaredTypes(): DeclaredTypes =
    when (this) {
      is String -> DeclaredTypes(listOf(this), nullable = false)
      is List<*> -> {
        val names = filterIsInstance<String>()
        val realNames = names.filter { it != "null" }
        // Either an empty union or `["null"]`; both are represented faithfully as-is.
        if (realNames.isEmpty()) DeclaredTypes(listOfNotNull(names.firstOrNull()), nullable = false)
        else DeclaredTypes(realNames, nullable = realNames.size != names.size)
      }
      else -> DeclaredTypes(emptyList(), nullable = false)
    }

  /** The real (non-`"null"`) members of a JSON Schema `type` keyword, plus whether null is one. */
  private data class DeclaredTypes(val names: List<String>, val nullable: Boolean)

  /**
   * The JSON Schema keywords that describe a value of each type.
   *
   * Mirrors Python's `related_field_names_by_type`, so a union splits into the same branches ADK
   * Python produces.
   */
  private val RELATED_KEYWORDS: Map<String, List<String>> =
    mapOf(
      "number" to listOf("description", "enum", "format", "maximum", "minimum", "title"),
      "integer" to listOf("description", "enum", "format", "maximum", "minimum", "title"),
      "string" to
        listOf("description", "enum", "format", "maxLength", "minLength", "pattern", "title"),
      "object" to
        listOf(
          "anyOf",
          "description",
          "maxProperties",
          "minProperties",
          "properties",
          "required",
          "title",
        ),
      "array" to listOf("description", "items", "maxItems", "minItems", "title"),
      "boolean" to listOf("description", "title"),
    )

  /**
   * One branch of a split union: this type, plus the keywords from [map] that describe it.
   *
   * `default` is deliberately left out -- a union's default cannot be attributed to one branch.
   */
  private fun String.branchOf(map: Map<String, Any>): Map<String, Any> = buildMap {
    put("type", this@branchOf)
    RELATED_KEYWORDS[this@branchOf]?.forEach { key -> map[key]?.let { put(key, it) } }
  }

  /**
   * Returns the `format` keyword when the backend accepts it for [typeName], and `null` otherwise.
   *
   * An arbitrary JSON Schema format (`uri`, `email`, `uuid`, ...) would get the whole declaration
   * rejected, so an unsupported one is dropped rather than degrading the tool.
   */
  private fun Map<String, Any>.geminiFormat(typeName: String?): String? {
    val format = this["format"] as? String ?: return null
    return when (typeName) {
      "integer",
      "number" -> format.takeIf { it == "int32" || it == "int64" }
      "string" -> format.takeIf { it == "date-time" || it == "enum" }
      else -> null
    }
  }

  /**
   * Converts a JSON Schema `anyOf`, dropping members that are not themselves objects.
   *
   * A `$ref` that resolves is replaced by the schema it names. One that does not -- an unknown
   * definition, or a pointer this converter does not understand -- converts to a bare untyped
   * schema and is dropped, rather than advertising an unconstrained alternative to the model.
   *
   * A member this converter rejects is dropped for the same reason: the alternatives that did
   * convert still describe the value, whereas failing here would take the whole declaration down
   * over one arm of a union.
   */
  private fun Any?.toAnyOfSchemas(depth: Int, scope: RefScope): List<Schema>? =
    (this as? List<*>)
      ?.mapNotNull { member ->
        member
          .safeCastToMapStringAny()
          ?.let { memberMap ->
            try {
              parsePropertyMap(memberMap, depth, scope)
            } catch (e: IllegalArgumentException) {
              logger.warn { "Dropping an anyOf member that could not be converted: ${e.message}" }
              null
            }
          }
          ?.takeIf { it.isTyped() }
      }
      ?.takeIf { it.isNotEmpty() }

  /**
   * Whether a converted sub-schema says anything a model can act on.
   *
   * A member whose `$ref` could not be resolved converts to a bare [Type.TYPE_UNSPECIFIED] schema.
   * Keeping it would advertise an unconstrained alternative to the model rather than the type the
   * server meant, so it is dropped, leaving the union no worse than before it was carried across at
   * all. A `$ref` that does resolve keeps its type and never reaches this check.
   *
   * A missing type says exactly as little as [Type.TYPE_UNSPECIFIED] does, so both are read as no
   * type. Only a union reaches here without one today, and it is kept for its `anyOf`, but relying
   * on that would make this quietly wrong the moment anything else does.
   */
  private fun Schema.isTyped(): Boolean =
    (type != null && type != Type.TYPE_UNSPECIFIED) ||
      properties != null ||
      items != null ||
      anyOf != null

  /** Reads [key] as a JSON number, widened to [Double]; `null` when absent or not numeric. */
  private fun Map<String, Any>.doubleOrNull(key: String): Double? =
    (this[key] as? Number)?.toDouble()

  /** Reads [key] as a JSON number, narrowed to [Long]; `null` when absent or not numeric. */
  private fun Map<String, Any>.longOrNull(key: String): Long? = (this[key] as? Number)?.toLong()

  /**
   * Converts a JSON Schema `enum` to the ADK representation.
   *
   * [Schema.enum] is a list of strings whereas JSON Schema permits any JSON value, so non-string
   * constants are rendered with `toString()`. JSON `null` members are dropped, since they express
   * nullability rather than an allowed value.
   */
  private fun Any?.toEnumValues(): List<String>? =
    (this as? List<*>)?.mapNotNull { it?.toString() }?.takeIf { it.isNotEmpty() }
}
