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

@file:OptIn(ExperimentalClassifierApi::class)

package com.google.adk.kt.classifiers

import com.google.adk.kt.SchemaUtils
import com.google.adk.kt.annotations.ExperimentalClassifierApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Pydantic's JSON nesting limit, which also keeps encoding well within the default stack. */
internal const val MAX_JSON_CONTENT_DEPTH = 200

/** Checks the labels and descriptions of [criteria], including from callers that bypass types. */
internal fun validateChoiceCriteria(criteria: Map<String, ClassifierContent?>) {
  require(criteria.size >= 2) {
    "Choice requires at least 2 criteria labels, got ${criteria.size}."
  }
  for ((key, value) in (criteria as Map<*, *>).entries) {
    require(key is String) { "Choice criteria keys must be strings, got ${typeName(key)}." }
    require(value == null || value is ClassifierContent) {
      "Choice criteria descriptions must be ClassifierContent, got ${typeName(value)}."
    }
  }
}

/** Checks the rubric levels of [criteria], including from callers that bypass types. */
internal fun validateScoreCriteria(criteria: List<ClassifierContent>) {
  require(criteria.size >= 2) { "Score requires at least 2 criteria levels, got ${criteria.size}." }
  for (item in criteria as List<*>) {
    requireNotNull(item) { "Score criteria must not contain null." }
    require(item is ClassifierContent) {
      "Score criteria levels must be ClassifierContent, got ${typeName(item)}."
    }
  }
}

/**
 * Wraps plain-text Choice [criteria] in [ClassifierContent.Text], checking the description types.
 */
internal fun textCriteria(criteria: Map<String, String?>): Map<String, ClassifierContent?> {
  for (description in (criteria as Map<*, *>).values) {
    require(description == null || description is String) {
      "Choice criteria descriptions must be strings, got ${typeName(description)}."
    }
  }
  return criteria.mapValues { (_, description) -> description?.let(ClassifierContent::Text) }
}

/** Wraps plain-text Score [criteria] in [ClassifierContent.Text], checking the level types. */
internal fun textLevels(criteria: List<String>): List<ClassifierContent> {
  for (item in criteria as List<*>) {
    requireNotNull(item) { "Score criteria must not contain null." }
    require(item is String) { "Score criteria levels must be strings, got ${typeName(item)}." }
  }
  return criteria.map(ClassifierContent::Text)
}

/**
 * Returns [json] with each number written as the `Long` or `Double` it parses to. Throws
 * [IllegalArgumentException] if it nests more than [MAX_JSON_CONTENT_DEPTH] deep or holds a
 * non-finite number, an integer beyond a `Long`, or a literal that is not JSON.
 */
internal fun normalizedJson(json: JsonObject): JsonObject =
  normalized(json, depth = 0) as JsonObject

/** Returns [json] normalized and checked as the [JsonObject] overload does. */
internal fun normalizedJson(json: JsonArray): JsonArray = normalized(json, depth = 0) as JsonArray

/** Parses the text of a JSON object or array for [ClassifierContent.parse]. */
@Suppress("UnusedException") // Chaining the cause would quote the JSON.
internal fun parseClassifierContent(json: String): ClassifierContent {
  checkJsonTextDepth(json)
  val element =
    try {
      Json.parseToJsonElement(json)
    } catch (e: SerializationException) {
      throw IllegalArgumentException("ClassifierContent must be valid JSON.")
    }
  return when (element) {
    is JsonObject -> ClassifierContent.JsonObject(element)
    is JsonArray -> ClassifierContent.JsonArray(element)
    is JsonPrimitive ->
      throw IllegalArgumentException("ClassifierContent must be a JSON object or array.")
  }
}

internal inline fun <reified K : Any> validateProbabilities(
  probabilities: Map<K, Double>,
  what: String,
) {
  validateMapEntries(probabilities, what) { validateProbability(what, it) }
}

internal fun validateProbability(field: String, value: Double) {
  require(value in 0.0..1.0) { "$field must be a finite number in [0, 1]." }
}

internal fun validateConfidence(confidence: Double?) {
  if (confidence != null) {
    validateProbability("confidence", confidence)
  }
}

/** Checks every entry of [map] against [K] and [V] for callers that bypass Kotlin types. */
internal inline fun <reified K : Any, reified V : Any> validateMapEntries(
  map: Map<K, V>,
  what: String,
  validate: (V) -> Unit = {},
) {
  val raw: Map<*, *> = map
  for ((key, value) in raw.entries) {
    require(key is K) { "$what keys must be ${K::class.simpleName}, got ${typeName(key)}." }
    require(value is V) {
      "$what values must be non-null ${V::class.simpleName}, got ${typeName(value)}."
    }
    validate(value)
  }
}

internal fun typeName(value: Any?): String =
  if (value == null) "null" else (value::class.simpleName ?: "an unnamed type")

private fun normalized(element: JsonElement, depth: Int): JsonElement =
  when (element) {
    is JsonObject -> {
      checkContainerDepth(depth)
      JsonObject(element.mapValues { (_, value) -> normalized(value, depth + 1) })
    }
    is JsonArray -> {
      checkContainerDepth(depth)
      JsonArray(element.map { normalized(it, depth + 1) })
    }
    is JsonPrimitive -> if (element.isString) element else normalizedLiteral(element)
  }

private fun normalizedLiteral(literal: JsonPrimitive): JsonPrimitive {
  val content = literal.content
  return when {
    literal is JsonNull || content == "true" || content == "false" -> literal
    SchemaUtils.JSON_INTEGER.matches(content) ->
      JsonPrimitive(
        requireNotNull(content.toLongOrNull()) { "JSON content integers must fit in a Long." }
      )
    SchemaUtils.JSON_NUMBER.matches(content) -> {
      val number = content.toDouble()
      require(number.isFinite()) { "JSON content numbers must be finite." }
      JsonPrimitive(number)
    }
    else -> throw IllegalArgumentException("JSON content contains an invalid literal.")
  }
}

/**
 * Rejects JSON text nested deeper than [MAX_JSON_CONTENT_DEPTH] before parsing, since kotlinx
 * parses arrays recursively on the call stack.
 */
private fun checkJsonTextDepth(json: String) {
  var depth = 0
  var inString = false
  var escaped = false
  for (char in json) {
    when {
      escaped -> escaped = false
      inString ->
        when (char) {
          '\\' -> escaped = true
          '"' -> inString = false
        }
      char == '"' -> inString = true
      char == '[' || char == '{' -> {
        checkContainerDepth(depth)
        depth++
      }
      char == ']' || char == '}' -> if (depth > 0) depth--
    }
  }
}

internal fun checkContainerDepth(depth: Int) {
  require(depth < MAX_JSON_CONTENT_DEPTH) {
    "JSON content exceeds maximum nesting depth of $MAX_JSON_CONTENT_DEPTH."
  }
}
