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

/** Converts between MCP schema types and ADK types. */
internal object McpSchemaConverter {

  private val logger = LoggerFactory.getLogger(McpSchemaConverter::class)

  /** Converts an [McpSchema.Tool] to an [FunctionDeclaration]. */
  fun McpSchema.Tool.toAdkFunctionDeclaration(): FunctionDeclaration {
    val inputSchema = inputSchema()?.let { it.toAdkSchema() }

    return FunctionDeclaration(
      name = name(),
      description = description() ?: "",
      parameters = inputSchema,
    )
  }

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
    when (typeStr) {
      null -> Type.TYPE_UNSPECIFIED
      "string" -> Type.STRING
      "integer" -> Type.INTEGER
      "number" -> Type.NUMBER
      "boolean" -> Type.BOOLEAN
      "array" -> Type.ARRAY
      "object" -> Type.OBJECT
      else -> throw IllegalArgumentException("Unknown type: $typeStr")
    }

  /** Converts a [JsonSchema] to an ADK [Schema]. */
  fun JsonSchema.toAdkSchema(): Schema {
    val properties =
      properties()
        ?.mapNotNull { (key, value) ->
          value.safeCastToMapStringAny()?.let { key to parsePropertyMap(it) }
        }
        ?.toMap()
    return Schema(
      type = parseTypeString(type()),
      properties = properties,
      required = required(),
      description = null,
    )
  }

  /** Parses a property map into an ADK [Schema]. */
  fun parsePropertyMap(map: Map<String, Any>): Schema {
    val typeValue = map["type"]
    val typeStr =
      when (typeValue) {
        is String -> typeValue
        is List<*> -> {
          val typeList = typeValue.filterIsInstance<String>()
          if (typeList.isNotEmpty()) {
            if (typeList.size > 1) {
              logger.warn {
                "MCP tool schema declares a union type $typeList; ADK schemas support a single " +
                  "type, so only \"${typeList.first()}\" is used and the remaining types are ignored."
              }
            }
            typeList.first()
          } else {
            null
          }
        }
        else -> null
      }
    val description = map["description"] as? String

    val itemsMap = map["items"].safeCastToMapStringAny()
    val items = itemsMap?.let { parsePropertyMap(it) }

    val propertiesMap = map["properties"].safeCastToMapStringAny()
    val properties =
      propertiesMap
        ?.mapNotNull { (key, value) ->
          value.safeCastToMapStringAny()?.let { key to parsePropertyMap(it) }
        }
        ?.toMap()

    val required = map["required"].safeCastToListString()

    return Schema(
      type = parseTypeString(typeStr),
      properties = properties,
      items = items,
      required = required,
      description = description,
    )
  }
}
