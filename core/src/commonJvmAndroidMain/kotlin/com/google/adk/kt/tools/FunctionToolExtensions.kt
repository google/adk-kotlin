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

import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.serialization.anyToJsonElement
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import java.io.StringWriter
import kotlin.jvm.JvmName
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.kxml2.io.KXmlSerializer

/**
 * Generates a text description of the function tools for use in an LLM prompt.
 *
 * @param format The format in which to render the tool descriptions (e.g., XML or JSON).
 * @return A formatted string describing the provided tools and their parameters.
 */
@JvmName("toPromptDescriptionFromDeclarations")
internal fun Iterable<FunctionDeclaration>.toPromptDescription(
  format: PromptFormat = PromptFormat.XML
): String {
  return when (format) {
    PromptFormat.XML -> toXmlPromptDescription()
    PromptFormat.JSON -> toJsonPromptDescription()
  }
}

/**
 * Generates a text description of the function tools for use in an LLM prompt.
 *
 * @param format The format in which to render the tool descriptions (e.g., XML or JSON).
 * @return A formatted string describing the provided tools and their parameters.
 */
@JvmName("toPromptDescriptionFromTools")
internal fun Iterable<FunctionTool>.toPromptDescription(
  format: PromptFormat = PromptFormat.XML
): String {
  return this.mapNotNull { it.declaration() }.toPromptDescription(format)
}

private fun Iterable<FunctionDeclaration>.toXmlPromptDescription(): String {
  val writer = StringWriter()
  val serializer = KXmlSerializer()
  serializer.setOutput(writer)
  serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true)

  serializer.startTag(null, "tools")
  for (declaration in this@toXmlPromptDescription) {
    serializer.startTag(null, "tool")

    serializer.startTag(null, "name")
    serializer.text(declaration.name)
    serializer.endTag(null, "name")

    serializer.startTag(null, "description")
    serializer.text(declaration.description)
    serializer.endTag(null, "description")

    val parameters = declaration.parameters
    if (parameters != null && parameters.describesArguments()) {
      serializer.startTag(null, "parameters")
      if (parameters.properties != null) {
        schemaToXml(parameters, serializer)
      } else {
        // The arguments are not a named list -- an object left open, or a union. `schemaToXml`
        // walks named properties, so it would write an empty element here; the schema is written
        // out as itself instead. Only at the top level: a nested one already has its type and
        // constraints written by the property loop before it recurses.
        parameters.typeNameOrNull()?.let { typeName ->
          serializer.startTag(null, "type")
          serializer.text(typeName)
          serializer.endTag(null, "type")
        }
        parameters.constraintsXml(serializer)
      }
      serializer.endTag(null, "parameters")
    }

    serializer.endTag(null, "tool")
  }
  serializer.endTag(null, "tools")
  serializer.flush()

  return writer.toString()
}

private fun Iterable<FunctionDeclaration>.toJsonPromptDescription(): String =
  JsonArray(
      map { declaration ->
        jsonObject { tool ->
          tool["name"] = JsonPrimitive(declaration.name)
          tool["description"] = JsonPrimitive(declaration.description)

          val parameters = declaration.parameters
          if (parameters != null && parameters.describesArguments()) {
            tool["parameters"] = schemaToJsonObject(parameters)
          }
        }
      }
    )
    .toString()

/**
 * Builds a [JsonObject] by filling an ordered map.
 *
 * kotlinx's own `buildJsonObject` builder is the usual way to do this, but its `put` returns the
 * displaced value, and Android Lint's `CheckReturnValue` rejects every discarded return. This
 * writes the same thing through `MutableMap.set`, which returns nothing to discard.
 */
private fun jsonObject(fill: (MutableMap<String, JsonElement>) -> Unit): JsonObject =
  JsonObject(LinkedHashMap<String, JsonElement>().also(fill))

/**
 * Renders a `default` as JSON so it keeps the property's own type: an integer must not come out as
 * "5", nor a list as "[a, b]", which is all `toString` would give.
 *
 * A `default` is declared `Any?`, so a caller can put a value in it that has no JSON shape. That is
 * worth a default that reads oddly, not a tool description that fails to render, so such a value
 * falls back to its own text.
 */
@OptIn(FrameworkInternalApi::class)
private fun defaultAsJson(value: Any): JsonElement =
  try {
    anyToJsonElement(value)
  } catch (unrenderable: IllegalArgumentException) {
    JsonPrimitive(value.toString())
  }

/**
 * Whether this parameters schema says anything about what the tool accepts.
 *
 * An empty `properties` map is the one shape that means "no arguments", and a tool declaring that
 * is described without a parameters block at all. An absent map is a different statement: the
 * arguments are simply not a named list, which is how an open object or a union is written, and the
 * model still has to be told about them.
 */
private fun Schema.describesArguments(): Boolean =
  if (properties != null) properties.isNotEmpty() else anyOf != null || type != null

private fun schemaToJsonObject(schema: Schema): JsonObject = jsonObject { fields ->
  schema.typeNameOrNull()?.let { fields["type"] = JsonPrimitive(it) }
  schema.description?.let { fields["description"] = JsonPrimitive(it) }

  // This description is the whole contract the model gets on a prompt-driven tool path, so every
  // constraint a schema can express is written out.
  schema.title?.let { fields["title"] = JsonPrimitive(it) }
  schema.format?.let { fields["format"] = JsonPrimitive(it) }
  schema.nullable?.let { fields["nullable"] = JsonPrimitive(it) }
  schema.pattern?.let { fields["pattern"] = JsonPrimitive(it) }
  schema.minimum?.let { fields["minimum"] = JsonPrimitive(it) }
  schema.maximum?.let { fields["maximum"] = JsonPrimitive(it) }
  schema.minLength?.let { fields["minLength"] = JsonPrimitive(it) }
  schema.maxLength?.let { fields["maxLength"] = JsonPrimitive(it) }
  schema.minItems?.let { fields["minItems"] = JsonPrimitive(it) }
  schema.maxItems?.let { fields["maxItems"] = JsonPrimitive(it) }
  schema.minProperties?.let { fields["minProperties"] = JsonPrimitive(it) }
  schema.maxProperties?.let { fields["maxProperties"] = JsonPrimitive(it) }
  schema.enum?.let { values -> fields["enum"] = JsonArray(values.map { JsonPrimitive(it) }) }
  schema.default?.let { fields["default"] = defaultAsJson(it) }
  schema.anyOf?.let { members ->
    fields["anyOf"] = JsonArray(members.map { schemaToJsonObject(it) })
  }

  when (schema.type) {
    Type.OBJECT ->
      if (schema.properties != null) {
        fields["properties"] = jsonObject { props ->
          for ((name, propSchema) in schema.properties) props[name] = schemaToJsonObject(propSchema)
        }
        if (!schema.required.isNullOrEmpty()) {
          fields["required"] = JsonArray(schema.required.map { JsonPrimitive(it) })
        }
      }
    Type.ARRAY -> schema.items?.let { fields["items"] = schemaToJsonObject(it) }
    else -> {}
  }
}

/**
 * Writes the constraints a property declares, so a prompt-driven model is told the same rules a
 * native function-calling backend would receive.
 *
 * A union is written out as nested `<anyOf>` entries, because naming the type `anyOf` would
 * describe a type that does not exist and leave the model with no idea what it may send.
 */
private fun Schema.constraintsXml(serializer: KXmlSerializer) {
  fun tag(name: String, value: Any?) {
    if (value == null) return
    serializer.startTag(null, name)
    serializer.text(value.toString())
    serializer.endTag(null, name)
  }
  tag("title", title)
  tag("format", format)
  tag("nullable", nullable)
  // Rendered as JSON rather than with `toString`, for the reason given on `defaultAsJson`.
  default?.let { tag("default", defaultAsJson(it).toString()) }
  tag("pattern", pattern)
  tag("minimum", minimum)
  tag("maximum", maximum)
  tag("minLength", minLength)
  tag("maxLength", maxLength)
  tag("minItems", minItems)
  tag("maxItems", maxItems)
  tag("minProperties", minProperties)
  tag("maxProperties", maxProperties)
  enum?.let { values -> for (value in values) tag("enum", value) }
  anyOf?.let { members ->
    for (member in members) {
      serializer.startTag(null, "anyOf")
      tag("type", member.typeNameOrNull())
      member.constraintsXml(serializer)
      serializer.endTag(null, "anyOf")
    }
  }
}

/**
 * The name a tool description gives this schema's type, in either format.
 *
 * A schema carrying only `anyOf` has no type of its own; its alternatives are written out instead,
 * so it reports none rather than claiming to be a string.
 */
private fun Schema.typeNameOrNull(): String? =
  type?.takeIf { it != Type.TYPE_UNSPECIFIED }?.name?.lowercase()
    ?: if (anyOf != null) null else "string"

private fun schemaToXml(schema: Schema, serializer: KXmlSerializer) {
  when (schema.type) {
    Type.OBJECT -> {
      if (schema.properties != null) {
        for ((name, propSchema) in schema.properties) {
          serializer.startTag(null, "parameter")

          serializer.startTag(null, "name")
          serializer.text(name)
          serializer.endTag(null, "name")

          propSchema.typeNameOrNull()?.let { propType ->
            serializer.startTag(null, "type")
            serializer.text(propType)
            serializer.endTag(null, "type")
          }

          if (propSchema.description != null) {
            serializer.startTag(null, "description")
            serializer.text(propSchema.description)
            serializer.endTag(null, "description")
          }

          propSchema.constraintsXml(serializer)

          val required = schema.required?.contains(name) == true
          serializer.startTag(null, "required")
          serializer.text(required.toString())
          serializer.endTag(null, "required")

          if (propSchema.type == Type.OBJECT || propSchema.type == Type.ARRAY) {
            serializer.startTag(null, "schema")
            schemaToXml(propSchema, serializer)
            serializer.endTag(null, "schema")
          }

          serializer.endTag(null, "parameter")
        }
      }
    }
    Type.ARRAY -> {
      if (schema.items != null) {
        serializer.startTag(null, "items")

        schema.items.typeNameOrNull()?.let { itemType ->
          serializer.startTag(null, "type")
          serializer.text(itemType)
          serializer.endTag(null, "type")
        }
        schema.items.constraintsXml(serializer)

        if (schema.items.type == Type.OBJECT || schema.items.type == Type.ARRAY) {
          serializer.startTag(null, "schema")
          schemaToXml(schema.items, serializer)
          serializer.endTag(null, "schema")
        }

        serializer.endTag(null, "items")
      }
    }
    else -> {}
  }
}
