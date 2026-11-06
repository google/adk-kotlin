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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/*
 * The constructors in ClassifierTypes.kt validate their arguments with these helpers, which stand
 * in for the Pydantic validation of the Python reference types. The helpers copy content so later
 * caller mutations cannot change an instance, canonicalize numbers and `JsonElement`s so an `Int`
 * and a `Long`, or a `JsonElement` and the equivalent map or list, compare equal, and re-check map
 * entries because Java callers can bypass Kotlin's types and nullability. Error messages never
 * include the content.
 */

// Pydantic's JSON nesting limit, which also keeps JSON encoding well within the default stack.
internal const val MAX_JSON_CONTENT_DEPTH = 200

/**
 * Validates that [value] is top-level JSON content (`String`, `Map`, `List`, `JsonObject`,
 * `JsonArray`, or a string `JsonPrimitive`) and returns a read-only copy in canonical form:
 * `String`, `Boolean`, `Long`, `Double`, `Map<String, Any?>`, `List<Any?>` and `null`.
 */
private fun freezeJsonContent(value: Any): Any {
  val isContent =
    value is String ||
      value is Map<*, *> ||
      value is List<*> ||
      (value is JsonPrimitive && value.isString)
  require(isContent) { "Expected string, map, or list JSON content, got ${typeName(value)}." }
  return checkNotNull(freezeJsonValue(value, depth = 0)) { "Non-null content froze to null." }
}

/** Like [freezeJsonContent], but treats `null` and [JsonNull] as an absent value. */
internal fun freezeOptionalJsonContent(value: Any?): Any? =
  if (value == null || value is JsonNull) null else freezeJsonContent(value)

internal fun freezeChoiceCriteria(criteria: Map<String, Any?>): Map<String, Any?> {
  require(criteria.size >= 2) {
    "Choice requires at least 2 criteria labels, got ${criteria.size}."
  }
  return buildMap(criteria.size) {
    for ((key, value) in (criteria as Map<*, *>).entries) {
      require(key is String) { "Choice criteria keys must be strings, got ${typeName(key)}." }
      put(key, freezeOptionalJsonContent(value))
    }
  }
}

internal fun freezeScoreCriteria(criteria: List<Any>): List<Any> {
  require(criteria.size >= 2) { "Score requires at least 2 criteria levels, got ${criteria.size}." }
  return buildList(criteria.size) {
    for (item in criteria as List<*>) {
      add(freezeJsonContent(requireNotNull(item) { "Score criteria must not contain null." }))
    }
  }
}

internal fun freezeChoiceProbabilities(probabilities: Map<String, Double>): Map<String, Double> =
  checkedMapCopy<String, Double>(probabilities, "ChoiceAnswer probabilities") {
    validateProbability("ChoiceAnswer probabilities", it)
  }

internal fun freezeScoreProbabilities(probabilities: Map<Int, Double>): Map<Int, Double> =
  checkedMapCopy<Int, Double>(probabilities, "ScoreAnswer probabilities") {
    validateProbability("ScoreAnswer probabilities", it)
  }

internal fun freezeAnswers(answers: Map<String, Answer>): Map<String, Answer> =
  checkedMapCopy<String, Answer>(answers, "ClassifierResponse answers")

internal fun validateProbability(field: String, value: Double) {
  require(value in 0.0..1.0) { "$field must be a finite number in [0, 1]." }
}

internal fun validateConfidence(confidence: Double?) {
  if (confidence != null) {
    validateProbability("confidence", confidence)
  }
}

/**
 * Copies [map], re-checking every entry against [K] and [V] for callers that bypass Kotlin types.
 */
private inline fun <reified K : Any, reified V : Any> checkedMapCopy(
  map: Map<*, *>,
  what: String,
  validate: (V) -> Unit = {},
): Map<K, V> =
  buildMap(map.size) {
    for ((key, value) in map.entries) {
      require(key is K) { "$what keys must be ${K::class.simpleName}, got ${typeName(key)}." }
      require(value is V) {
        "$what values must be non-null ${V::class.simpleName}, got ${typeName(value)}."
      }
      validate(value)
      put(key, value)
    }
  }

private fun freezeJsonValue(value: Any?, depth: Int): Any? =
  when (value) {
    null,
    is String,
    is Boolean,
    is Long -> value
    JsonNull -> null
    is Int,
    is Short,
    is Byte -> value.toLong()
    is Double -> value.also { require(it.isFinite()) { "JSON content numbers must be finite." } }
    // Widening through the decimal text keeps 0.1f as 0.1 rather than 0.10000000149011612.
    is Float -> freezeJsonValue(value.toString().toDouble(), depth)
    is JsonPrimitive -> jsonLiteralToValue(value)
    is Map<*, *> -> {
      checkContainerDepth(depth)
      buildMap(value.size) {
        for ((key, item) in value.entries) {
          require(key is String) { "JSON content map keys must be strings, got ${typeName(key)}." }
          put(key, freezeJsonValue(item, depth + 1))
        }
      }
    }
    is List<*> -> {
      checkContainerDepth(depth)
      buildList(value.size) {
        for (item in value) {
          add(freezeJsonValue(item, depth + 1))
        }
      }
    }
    else -> throw IllegalArgumentException("Unsupported value in JSON content: ${typeName(value)}.")
  }

private fun jsonLiteralToValue(literal: JsonPrimitive): Any =
  when {
    literal.isString -> literal.content
    literal.content == "true" -> true
    literal.content == "false" -> false
    SchemaUtils.JSON_INTEGER.matches(literal.content) ->
      requireNotNull(literal.content.toLongOrNull()) { "JSON content integers must fit in a Long." }
    SchemaUtils.JSON_NUMBER.matches(literal.content) ->
      requireNotNull(literal.doubleOrNull?.takeIf { it.isFinite() }) {
        "JSON content numbers must be finite."
      }
    else -> throw IllegalArgumentException("JSON content contains an invalid literal.")
  }

/** Rejects a container nested deeper than [MAX_JSON_CONTENT_DEPTH] containers, like Pydantic. */
private fun checkContainerDepth(depth: Int) {
  require(depth < MAX_JSON_CONTENT_DEPTH) {
    "JSON content exceeds maximum nesting depth of $MAX_JSON_CONTENT_DEPTH."
  }
}

private fun typeName(value: Any?): String =
  if (value == null) "null" else (value::class.simpleName ?: "an unnamed type")
