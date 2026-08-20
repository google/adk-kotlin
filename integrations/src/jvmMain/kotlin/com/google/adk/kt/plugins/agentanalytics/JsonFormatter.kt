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

package com.google.adk.kt.plugins.agentanalytics

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.adk.kt.logging.LoggerFactory
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale

/** Utility for parsing, formatting, and truncating content for BigQuery logging. */
internal object JsonFormatter {

  val mapper: ObjectMapper = ObjectMapper().findAndRegisterModules()
  const val TRUNCATION_SUFFIX = "...[truncated]"
  const val CYCLE_DETECTED_MESSAGE = "[cycle detected]"
  const val MAX_DEPTH_MESSAGE = "[max depth exceeded]"
  const val REDACTED_MESSAGE = "[REDACTED]"
  const val UNSERIALIZABLE_MESSAGE = "[UNSERIALIZABLE]"
  const val MAX_TRUNCATE_DEPTH = 200

  private val logger = LoggerFactory.getLogger(JsonFormatter::class)

  private val SENSITIVE_KEYS =
    setOf("client_secret", "access_token", "refresh_token", "id_token", "api_key", "password")
  private const val TEMP_KEY_PREFIX = "temp:"

  internal data class TruncationResult(val node: JsonNode, val isTruncated: Boolean)

  private fun isSensitiveKey(key: String): Boolean {
    val lower = key.lowercase(Locale.ROOT)
    return SENSITIVE_KEYS.contains(lower) || lower.startsWith(TEMP_KEY_PREFIX)
  }

  fun smartTruncate(obj: Any?, maxLength: Int): TruncationResult {
    if (obj == null) {
      return TruncationResult(mapper.nullNode(), false)
    }
    return try {
      if (obj is JsonNode) {
        recursiveSmartTruncate(obj, maxLength, Collections.newSetFromMap(IdentityHashMap()), 0)
      } else {
        recursiveSmartTruncate(
          mapper.valueToTree(obj),
          maxLength,
          Collections.newSetFromMap(IdentityHashMap()),
          0,
        )
      }
    } catch (e: IllegalArgumentException) {
      logger.debug { "smartTruncate falling back to string conversion: ${e.message}" }
      truncateWithStatus(safeToString(obj), maxLength)
    }
  }

  fun redactTree(obj: Any?): JsonNode {
    return redactTreeInternal(obj, Collections.newSetFromMap(IdentityHashMap()), 0)
  }

  private fun redactTreeInternal(obj: Any?, visited: MutableSet<Any>, depth: Int): JsonNode {
    if (obj == null) {
      return mapper.nullNode()
    }
    if (depth > MAX_TRUNCATE_DEPTH) {
      return mapper.valueToTree(MAX_DEPTH_MESSAGE)
    }
    if (obj is JsonNode) {
      return recursiveSmartTruncate(
          obj,
          Int.MAX_VALUE,
          Collections.newSetFromMap(IdentityHashMap()),
          depth,
        )
        .node
    }
    if (obj is Map<*, *>) {
      if (!visited.add(obj)) {
        return mapper.valueToTree(CYCLE_DETECTED_MESSAGE)
      }
      try {
        val node = mapper.createObjectNode()
        for ((keyObj, value) in obj) {
          val key = keyObj.toString()
          if (isSensitiveKey(key)) {
            node.set<JsonNode>(key, mapper.valueToTree(REDACTED_MESSAGE))
            continue
          }
          node.set<JsonNode>(key, redactTreeInternal(value, visited, depth + 1))
        }
        return node
      } finally {
        visited.remove(obj)
      }
    }
    if (obj is Iterable<*>) {
      if (!visited.add(obj)) {
        return mapper.valueToTree(CYCLE_DETECTED_MESSAGE)
      }
      try {
        val node = mapper.createArrayNode()
        for (element in obj) {
          node.add(redactTreeInternal(element, visited, depth + 1))
        }
        return node
      } finally {
        visited.remove(obj)
      }
    }
    return try {
      recursiveSmartTruncate(
          mapper.valueToTree(obj),
          Int.MAX_VALUE,
          Collections.newSetFromMap(IdentityHashMap()),
          depth,
        )
        .node
    } catch (e: IllegalArgumentException) {
      logger.debug { "redactTree replacing unserializable value: ${e.message}" }
      mapper.valueToTree(UNSERIALIZABLE_MESSAGE)
    }
  }

  fun convertToJsonNode(obj: Any?): JsonNode {
    if (obj == null) {
      return mapper.nullNode()
    }
    return try {
      mapper.valueToTree(obj)
    } catch (e: IllegalArgumentException) {
      mapper.valueToTree(safeToString(obj))
    }
  }

  fun safeToString(obj: Any?): String {
    return try {
      obj.toString()
    } catch (e: RuntimeException) {
      logger.warn(e) { "RuntimeException when converting object to string" }
      "[ERROR CONVERTING TO STRING]"
    }
  }

  private fun recursiveSmartTruncate(
    node: JsonNode,
    maxLength: Int,
    visited: MutableSet<JsonNode>,
    depth: Int,
  ): TruncationResult {
    if (depth > MAX_TRUNCATE_DEPTH) {
      return TruncationResult(mapper.valueToTree(MAX_DEPTH_MESSAGE), true)
    }
    if (node.isContainerNode) {
      if (visited.contains(node)) {
        return TruncationResult(mapper.valueToTree(CYCLE_DETECTED_MESSAGE), true)
      }
      visited.add(node)
    }
    try {
      var isTruncated = false
      if (node.isTextual) {
        val text = node.asText()
        if (text.toByteArray(Charsets.UTF_8).size > maxLength) {
          return TruncationResult(mapper.valueToTree(truncate(text, maxLength)), true)
        }
        return TruncationResult(node, false)
      } else if (node.isObject) {
        val newNode = mapper.createObjectNode()
        for ((key, value) in node.properties()) {
          if (isSensitiveKey(key)) {
            newNode.set<JsonNode>(key, mapper.valueToTree(REDACTED_MESSAGE))
            continue
          }
          val res = recursiveSmartTruncate(value, maxLength, visited, depth + 1)
          newNode.set<JsonNode>(key, res.node)
          isTruncated = isTruncated || res.isTruncated
        }
        return TruncationResult(newNode, isTruncated)
      } else if (node.isArray) {
        val newNode = mapper.createArrayNode()
        for (element in node) {
          val res = recursiveSmartTruncate(element, maxLength, visited, depth + 1)
          newNode.add(res.node)
          isTruncated = isTruncated || res.isTruncated
        }
        return TruncationResult(newNode, isTruncated)
      }
      return TruncationResult(node, false)
    } finally {
      if (node.isContainerNode) {
        visited.remove(node)
      }
    }
  }

  fun truncateWithStatus(s: String?, maxLength: Int): TruncationResult {
    if (s == null) {
      return TruncationResult(mapper.nullNode(), false)
    }
    if (s.toByteArray(Charsets.UTF_8).size <= maxLength) {
      return TruncationResult(mapper.valueToTree(s), false)
    }
    return TruncationResult(mapper.valueToTree(truncate(s, maxLength)), true)
  }

  fun truncate(s: String?, budget: Int): String? {
    return truncateAndAddSuffix(s, budget, TRUNCATION_SUFFIX)
  }

  fun truncateAndAddSuffix(s: String?, budget: Int, suffix: String): String? {
    if (s == null) return null
    val sBytes = s.toByteArray(Charsets.UTF_8)
    if (sBytes.size <= budget) return s

    val suffixBytes = suffix.toByteArray(Charsets.UTF_8).size
    val effectiveBudget = (budget - suffixBytes).coerceAtLeast(0)
    if (effectiveBudget == 0) {
      return suffix.substring(0, budget.coerceAtMost(suffix.length))
    }

    var byteCount = 0
    var charIndex = 0
    var i = 0
    while (i < s.length) {
      val codePoint = s.codePointAt(i)
      val codePointLen = Character.charCount(codePoint)
      val codePointBytes =
        when {
          codePoint < 0x80 -> 1
          codePoint < 0x800 -> 2
          codePoint < 0x10000 -> 3
          else -> 4
        }
      if (byteCount + codePointBytes > effectiveBudget) break
      byteCount += codePointBytes
      charIndex += codePointLen
      i += codePointLen
    }

    return s.substring(0, charIndex) + suffix
  }

  fun toKotlinObject(node: JsonNode?): Any? {
    if (node == null || node.isNull) return null
    return mapper.convertValue(node, Any::class.java)
  }

  fun writeValueAsString(obj: Any?): String {
    return mapper.writeValueAsString(obj)
  }
}
