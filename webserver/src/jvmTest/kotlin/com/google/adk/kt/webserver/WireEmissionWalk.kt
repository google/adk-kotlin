/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.adk.kt.webserver

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/** The non-enforced spelling of `languageVersion`, emitted in its place while readers migrate. */
internal const val LEGACY_VERSION_KEY = "language_version"

/**
 * The snake_case keys the response schema still declares, each mapped to the camelCase spelling
 * that replaces it: `/version` emits the non-enforced spelling in place of `languageVersion`, never
 * both. Removing the alias empties this map rather than loosening a check.
 */
internal val KNOWN_LEGACY_ALIASES =
  mapOf("VersionInfo.$LEGACY_VERSION_KEY" to "VersionInfo.languageVersion")

/**
 * Fails when [body] carries a snake_case schema key or a null-valued schema field.
 *
 * A pass only means something if the walk reached the response: [minKeys] floors the schema keys it
 * matched, and [EmissionWalk.shapeMismatches] catches a subtree it could not enter, either of which
 * would otherwise let a wrong-shaped body satisfy the walk by being walked into nothing.
 */
internal fun assertEmissionRule(
  body: String,
  descriptor: SerialDescriptor,
  root: String,
  minKeys: Int,
) {
  val walk = EmissionWalk()

  walk.visit(Json.parseToJsonElement(body), descriptor, root)

  assertThat(walk.shapeMismatches).isEmpty()
  assertThat(walk.checkedKeys).isAtLeast(minKeys)
  // Allowed, not required: a legacy alias may or may not be emitted depending on configuration.
  assertThat(walk.snakeCaseKeys - KNOWN_LEGACY_ALIASES.keys).isEmpty()
  assertThat(walk.nullKeys).isEmpty()
}

/**
 * Walks a response against its descriptor, recording snake_case keys and null-valued fields. Map
 * keys are caller data and are not schema, so they are never reported.
 */
internal class EmissionWalk {
  val snakeCaseKeys = mutableSetOf<String>()
  val nullKeys = mutableSetOf<String>()
  /** Subtrees whose JSON did not match the descriptor, so the walk could not enter them. */
  val shapeMismatches = mutableSetOf<String>()
  /** Schema keys actually matched against the descriptor, so a vacuous pass is detectable. */
  var checkedKeys = 0
    private set

  fun visit(json: JsonElement, descriptor: SerialDescriptor, path: String) {
    // An inline value class is written as its wrapped value, which is not a shape mismatch.
    if (descriptor.isInline) return visit(json, descriptor.getElementDescriptor(0), path)
    when (descriptor.kind) {
      StructureKind.LIST ->
        if (json is JsonArray) visitItems(json, descriptor, path) else shapeMismatches += path
      StructureKind.MAP ->
        if (json is JsonObject) visitValues(json, descriptor, path) else shapeMismatches += path
      StructureKind.CLASS,
      StructureKind.OBJECT ->
        if (json is JsonObject) visitFields(json, descriptor, path) else shapeMismatches += path
      // Contextual is caller data; polymorphic is covered by the static survey instead.
      else -> {}
    }
  }

  private fun visitItems(array: JsonArray, descriptor: SerialDescriptor, path: String) {
    for (item in array) visit(item, descriptor.getElementDescriptor(0), "$path[]")
  }

  /** Visits each non-null map value, which may be a schema type; a null value is caller data. */
  private fun visitValues(map: JsonObject, descriptor: SerialDescriptor, path: String) {
    val values = descriptor.getElementDescriptor(1)
    for ((_, value) in map) if (value !is JsonNull) visit(value, values, "$path{}")
  }

  private fun visitFields(fields: JsonObject, descriptor: SerialDescriptor, path: String) {
    for ((key, value) in fields) {
      val child = "$path.$key"
      val index = descriptor.getElementIndex(key)
      if (index != CompositeDecoder.UNKNOWN_NAME) checkedKeys++
      if (key.contains('_')) snakeCaseKeys += child
      when {
        value is JsonNull -> nullKeys += child
        index != CompositeDecoder.UNKNOWN_NAME ->
          visit(value, descriptor.getElementDescriptor(index), child)
      }
    }
  }
}
