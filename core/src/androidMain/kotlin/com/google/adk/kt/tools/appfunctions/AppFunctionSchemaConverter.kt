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

package com.google.adk.kt.tools.appfunctions

import androidx.appfunctions.metadata.AppFunctionAllOfTypeMetadata
import androidx.appfunctions.metadata.AppFunctionArrayTypeMetadata
import androidx.appfunctions.metadata.AppFunctionBooleanTypeMetadata
import androidx.appfunctions.metadata.AppFunctionComponentsMetadata
import androidx.appfunctions.metadata.AppFunctionDataTypeMetadata
import androidx.appfunctions.metadata.AppFunctionDoubleTypeMetadata
import androidx.appfunctions.metadata.AppFunctionFloatTypeMetadata
import androidx.appfunctions.metadata.AppFunctionIntTypeMetadata
import androidx.appfunctions.metadata.AppFunctionLongTypeMetadata
import androidx.appfunctions.metadata.AppFunctionMetadata
import androidx.appfunctions.metadata.AppFunctionObjectTypeMetadata
import androidx.appfunctions.metadata.AppFunctionParameterMetadata
import androidx.appfunctions.metadata.AppFunctionParcelableTypeMetadata
import androidx.appfunctions.metadata.AppFunctionReferenceTypeMetadata
import androidx.appfunctions.metadata.AppFunctionStringTypeMetadata
import com.google.adk.kt.logging.LoggerFactory
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type

/**
 * Converts AppFunction metadata into the ADK declaration a model is shown.
 *
 * A type the model cannot supply -- bytes, a `Parcelable`, a `oneOf` -- converts to `null`, and the
 * caller decides whether that costs an optional parameter or the whole function.
 */
internal object AppFunctionSchemaConverter {

  private val logger = LoggerFactory.getLogger(AppFunctionSchemaConverter::class)

  /**
   * Maximum nesting depth walked while converting a type.
   *
   * The metadata is supplied by another app, so the descent over object properties and array items
   * is bounded rather than trusted to terminate.
   */
  private const val MAX_SCHEMA_DEPTH = 32

  /**
   * How many references one conversion may expand.
   *
   * The depth cap bounds how deep a type goes, not how much work it costs: a chain of definitions
   * that each reference the next from several properties expands multiplicatively.
   */
  private const val MAX_REF_EXPANSIONS = 512

  /**
   * How many all-of members one conversion may merge in total.
   *
   * Shared across every merge rather than granted per node: a per-node budget would multiply with
   * the reference budget, so the real ceiling would be their product rather than either one.
   */
  private const val MAX_ALL_OF_MEMBERS = 1024

  /**
   * What a reference is resolved against, plus the guards that keep resolving it finite.
   *
   * [visited] is the chain of references open on the path to here, so a definition that reaches
   * itself is cut where it closes; the expansion budget is shared across the whole conversion,
   * which is what bounds a wide acyclic graph that [visited] never fires on.
   */
  private class RefScope(
    val functionId: String,
    val components: AppFunctionComponentsMetadata,
    val visited: Set<String> = emptySet(),
    private val remaining: IntArray = intArrayOf(MAX_REF_EXPANSIONS),
    /** Shared with every all-of merge, so the whole conversion is bounded once, not per node. */
    val members: IntArray = intArrayOf(MAX_ALL_OF_MEMBERS),
  ) {
    /** Takes one expansion from the shared budget, or returns false once it is spent. */
    fun spend(): Boolean {
      if (remaining[0] <= 0) return false
      remaining[0]--
      return true
    }

    fun following(ref: String) = RefScope(functionId, components, visited + ref, remaining, members)
  }

  /**
   * Converts [metadata] into a declaration named [name], or `null` when the function cannot be
   * offered to the model at all.
   *
   * A function is dropped when a required parameter has no representable schema, since the model
   * would otherwise be shown a contract it cannot satisfy.
   */
  fun toFunctionDeclaration(metadata: AppFunctionMetadata, name: String): FunctionDeclaration? {
    val scope = RefScope(metadata.id, metadata.components)
    val properties = mutableMapOf<String, Schema>()
    // The library resolves a repeated name to its first declaration, so every later one is dropped
    // before anything is decided from it.
    val declared = metadata.parameters.distinctBy { it.name }
    for (parameter in declared) {
      val converted = convert(parameter.dataType, depth = 0, scope = scope)
      if (converted == null) {
        // A nullable required parameter is optional to the library, so losing it costs the model
        // one parameter rather than the whole function.
        if (parameter.isRequired && !parameter.dataType.isNullable) {
          logger.warn {
            "Skipping app function ${metadata.id}: required parameter '${parameter.name}' has no " +
              "schema."
          }
          return null
        }
        continue
      }
      properties[parameter.name] = converted.describedBy(parameter)
    }

    return FunctionDeclaration(
      name = name,
      description = describe(metadata),
      parameters =
        Schema(
          type = Type.OBJECT,
          properties = properties.toMap(),
          // Nothing stops another app declaring one name twice, and a repeated entry here is a
          // malformed declaration the backend may reject whole.
          required =
            declared
              .filter { it.isRequired && properties.containsKey(it.name) }
              .map { it.name }
              .takeIf { it.isNotEmpty() },
        ),
      // The response only describes what comes back, so one that cannot be converted costs the
      // model that description rather than the whole tool.
      response = toResponseSchema(metadata, scope),
    )
  }

  /**
   * What the model is told the function does.
   *
   * An app that documents nothing still gets an identifier, which says more than an empty string. A
   * deprecated function says so, rather than being offered as though the app still recommended it.
   */
  private fun describe(metadata: AppFunctionMetadata): String {
    val described = metadata.description.ifBlank { metadata.id }
    val deprecation = metadata.deprecation?.message?.ifBlank { null } ?: return described
    return "$described\n\nDeprecated: $deprecation"
  }

  /**
   * The shape of the payload the tool returns, which wraps the app's return value.
   *
   * The value is nested under [BaseTool.RESULT_KEY] so the declared response matches the payload
   * whatever the app returns, rather than only when it returns an object. Nothing is marked
   * required, because a failed call reports an error in place of the value. Nothing converts to
   * `anyOf`, which Vertex rejects here for the whole request; anything that later does must drop
   * it.
   */
  private fun toResponseSchema(metadata: AppFunctionMetadata, scope: RefScope): Schema? {
    val value =
      convert(metadata.response.valueType, depth = 0, scope = scope)
        ?: return warnDroppedResponse(metadata, scope)
    val described =
      metadata.response.description.ifBlank { null }?.let { value.copy(description = it) } ?: value
    return Schema(type = Type.OBJECT, properties = mapOf(BaseTool.RESULT_KEY to described))
  }

  /**
   * Names an unrepresentable parcelable response, so a screen the toolset misreads is diagnosable.
   */
  private fun warnDroppedResponse(metadata: AppFunctionMetadata, scope: RefScope): Schema? {
    val resolved = AppFunctionTypes.resolve(metadata.response.valueType, scope.components)
    if (resolved is AppFunctionParcelableTypeMetadata) {
      logger.warn {
        "App function ${metadata.id} returns an unrepresentable parcelable " +
          "'${resolved.qualifiedName}'; dropping the response schema."
      }
    }
    return null
  }

  /** Overlays the parameter's own description, which is the one written for the model. */
  private fun Schema.describedBy(parameter: AppFunctionParameterMetadata): Schema =
    if (parameter.description.isBlank()) this else copy(description = parameter.description)

  /**
   * Overlays what a reference says about itself onto the definition it names.
   *
   * A reference carries its own nullability and description per use site, and the app validates the
   * property against those rather than against the definition's. Nullability always wins, since a
   * reference cannot decline to state it; a blank description really does say nothing.
   */
  private fun Schema.refinedBy(description: String?, nullable: Boolean?): Schema =
    copy(description = description ?: this.description, nullable = nullable)

  /**
   * Converts one AppFunction type, or returns `null` when nothing the model can produce describes
   * it.
   */
  private fun convert(type: AppFunctionDataTypeMetadata, depth: Int, scope: RefScope): Schema? {
    if (depth >= MAX_SCHEMA_DEPTH) {
      logger.warn {
        "App function ${scope.functionId} schema nests deeper than $MAX_SCHEMA_DEPTH levels; " +
          "dropping it."
      }
      return null
    }
    val nullable = type.isNullable.takeIf { it }
    val description = type.description.ifBlank { null }
    return when (type) {
      is AppFunctionStringTypeMetadata ->
        Schema(
          type = Type.STRING,
          description = description,
          enum = type.enumValues?.toList(),
          // `enum` is required alongside enumerated values; anything else is the app's own hint.
          format = type.enumValues?.let { "enum" } ?: type.format,
          // The app validates its own string values against this, so a model never shown it sends
          // arguments the app refuses for a reason the refusal cannot give.
          pattern = type.pattern,
          nullable = nullable,
        )
      is AppFunctionIntTypeMetadata ->
        Schema(
          type = Type.INTEGER,
          description = description,
          enum = type.enumValues?.map(Int::toString),
          format = "int32",
          nullable = nullable,
        )
      is AppFunctionLongTypeMetadata ->
        Schema(
          type = Type.INTEGER,
          description = description,
          format = "int64",
          nullable = nullable,
        )
      // Gemini rejects a `float`/`double` format, so a real number carries none.
      is AppFunctionFloatTypeMetadata ->
        Schema(type = Type.NUMBER, description = description, nullable = nullable)
      is AppFunctionDoubleTypeMetadata ->
        Schema(type = Type.NUMBER, description = description, nullable = nullable)
      is AppFunctionBooleanTypeMetadata ->
        Schema(type = Type.BOOLEAN, description = description, nullable = nullable)
      // An array of arrays is dropped: the data side has no setter for one, so offering it would
      // produce a parameter every call fails on.
      is AppFunctionArrayTypeMetadata ->
        if (type.itemType is AppFunctionArrayTypeMetadata) null
        else
          convert(type.itemType, depth + 1, scope)?.let {
            Schema(type = Type.ARRAY, items = it, description = description, nullable = nullable)
          }
      is AppFunctionObjectTypeMetadata ->
        convertObject(type.properties, type.required, description, nullable, depth, scope)
      is AppFunctionAllOfTypeMetadata ->
        AppFunctionTypes.flattenAllOf(type, scope.components, remaining = scope.members)?.let {
          convertObject(it.properties, it.required, description, nullable, depth, scope)
        }
      is AppFunctionReferenceTypeMetadata ->
        convertReference(type, depth, scope)?.refinedBy(description, nullable)
      // Everything else has no value the model could supply or read back: bytes, a Parcelable, a
      // unit return, a one-of the data side cannot build back, or a type added after this.
      else -> null
    }
  }

  /**
   * Converts an object, dropping a property that cannot be represented.
   *
   * A dropped property that was required takes the whole object with it: the app would reject a
   * call that omitted it, so offering the object at all would only produce failures.
   */
  private fun convertObject(
    properties: Map<String, AppFunctionDataTypeMetadata>,
    required: List<String>,
    description: String?,
    nullable: Boolean?,
    depth: Int,
    scope: RefScope,
  ): Schema? {
    val converted = mutableMapOf<String, Schema>()
    val requiredNames = required.toSet()
    for ((name, property) in properties) {
      val schema = convert(property, depth + 1, scope)
      if (schema == null) {
        if (name in requiredNames && !property.isNullable) return null
        continue
      }
      converted[name] = schema
    }
    return Schema(
      type = Type.OBJECT,
      properties = converted.toMap(),
      required = requiredNames.filter { converted.containsKey(it) }.takeIf { it.isNotEmpty() },
      description = description,
      nullable = nullable,
    )
  }

  /**
   * Converts a reference by expanding what it names.
   *
   * A definition that reaches itself is cut rather than expanded: a recursive type has no finite
   * expansion, and describing it as an unconstrained object would invite the model to guess.
   */
  private fun convertReference(
    type: AppFunctionReferenceTypeMetadata,
    depth: Int,
    scope: RefScope,
  ): Schema? {
    val ref = type.referenceDataType
    if (ref in scope.visited) {
      logger.warn {
        "App function ${scope.functionId} schema references '$ref' from itself; dropping the " +
          "recursive property."
      }
      return null
    }
    val target = scope.components.dataTypes[ref]
    if (target == null) {
      logger.warn {
        "App function ${scope.functionId} schema has a reference '$ref' that resolves to nothing; " +
          "dropping it."
      }
      return null
    }
    val resolved = AppFunctionTypes.resolve(target, scope.components)
    if (resolved == null) {
      logger.warn {
        "App function ${scope.functionId} schema has a reference chain from '$ref' that does not " +
          "resolve; dropping it."
      }
      return null
    }
    // The app supplies an object for a reference whatever it names, so a reference to a scalar or
    // an array would offer the model a value the app can never be given.
    if (resolved !is AppFunctionObjectTypeMetadata && resolved !is AppFunctionAllOfTypeMetadata) {
      logger.warn {
        "App function ${scope.functionId} schema references non-object type '$ref'; dropping the " +
          "property."
      }
      return null
    }
    if (!scope.spend()) {
      logger.warn {
        "App function ${scope.functionId} schema expands more than $MAX_REF_EXPANSIONS references; " +
          "dropping the rest."
      }
      return null
    }
    return convert(target, depth + 1, scope.following(ref))
  }
}
