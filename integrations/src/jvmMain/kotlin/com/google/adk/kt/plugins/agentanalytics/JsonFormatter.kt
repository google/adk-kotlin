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

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.ObjectReader
import com.google.adk.kt.logging.LoggerFactory
import java.util.Base64
import java.util.Collections
import java.util.HexFormat
import java.util.IdentityHashMap
import java.util.Locale

/** Redacts credentials and truncates values to a byte budget before they are logged. */
internal object JsonFormatter {

  val mapper: ObjectMapper = ObjectMapper().findAndRegisterModules()

  /**
   * Marks a value cut to fit the content budget.
   *
   * The budget here counts UTF-8 bytes and includes this suffix.
   */
  const val TRUNCATION_SUFFIX = "...[TRUNCATED]"
  const val REDACTED_MESSAGE = "[REDACTED]"
  private const val CYCLE_DETECTED_MESSAGE = "[CIRCULAR_REFERENCE]"
  private const val MAX_DEPTH_MESSAGE = "[MAX_DEPTH_EXCEEDED]"
  private const val UNSUPPORTED_OBJECT_MESSAGE = "[UNSUPPORTED_OBJECT]"
  private const val UNDECODABLE_BYTES_MESSAGE = "[BINARY_DATA]"
  private const val UNPARSEABLE_JSON_MESSAGE = "[UNPARSEABLE_JSON_BLOB]"
  private const val REDACTED_TEXT_MESSAGE = "[REDACTED_SENSITIVE_TEXT]"
  private const val MAX_TRUNCATE_DEPTH = 200
  // Longer JSON held in a string is never parsed, whatever the content budget.
  private const val MAX_JSON_INSPECT_CHARS = 4_000_000

  // Values this formatter writes, kept as they are when a value is formatted twice.
  private val MARKERS =
    setOf(
      REDACTED_MESSAGE,
      CYCLE_DETECTED_MESSAGE,
      MAX_DEPTH_MESSAGE,
      UNSUPPORTED_OBJECT_MESSAGE,
      UNDECODABLE_BYTES_MESSAGE,
      UNPARSEABLE_JSON_MESSAGE,
      REDACTED_TEXT_MESSAGE,
    )

  private val logger = LoggerFactory.getLogger(JsonFormatter::class)

  // Reads NaN and Infinity, but no text after the document.
  private val JSON_READER =
    mapper
      .reader()
      .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
      .with(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS)
  private val STRICT_JSON_READER =
    JSON_READER.with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)

  internal data class TruncationResult(val node: JsonNode, val isTruncated: Boolean) {
    /** Converts [node] to plain Kotlin values, so a content formatter never sees a Jackson type. */
    fun toPlainValue(): Any? = JsonFormatter.toKotlinObject(node)
  }

  /** Text after redaction and truncation; [isChanged] reports whether either altered it. */
  internal data class SanitizedText(val text: String, val isChanged: Boolean)

  // SENSITIVE_TEXT_KEY_RE is derived from this one list.
  private val SENSITIVE_KEYS =
    setOf(
      "client_secret",
      "access_token",
      "refresh_token",
      "id_token",
      "api_key",
      "password",
      "private_key",
      "proxy_authorization",
      "google_access_id",
      "sig",
      "signature",
      "token",
      "secret",
      "authorization",
      "x_api_key",
      "x_amz_credential",
      "x_amz_signature",
      "x_goog_credential",
      "x_goog_security_token",
      "x_goog_signature",
    )
  private const val TEMP_KEY_PREFIX = "temp:"

  /**
   * Matches `key=value` and `"key": "value"` credential fragments embedded in free text and URLs.
   *
   * Group 1 is the key and separator, group 2 the value; `_` also matches `-` so query-string
   * spellings such as `api-key=` are covered. Quoted values repeat possessively, since a
   * backtracking group loop recurses per character and overflows the stack on long values.
   */
  private val SENSITIVE_TEXT_KEY_RE =
    Regex(
      """((?<![A-Za-z0-9_-])["']?(?:temp:[A-Za-z0-9_.-]+|""" +
        SENSITIVE_KEYS.map { it.replace("_", "[-_]") }
          .sortedByDescending { it.length }
          .joinToString("|") +
        """)["']?\s*[:=]\s*)("(?:\\.|[^"\\])*+"|'(?:\\.|[^'\\])*+'|[^\s,;&}]+)""",
      RegexOption.IGNORE_CASE,
    )

  // Where the bare value of SENSITIVE_TEXT_KEY_RE ends.
  private val VALUE_END_RE = Regex("""[\s,;&}]""")

  private val AUTH_HEADER_RE =
    Regex(
      """(\b(?:authorization|proxy-authorization|x-api-key|api-key)[ \t]*:[ \t]*)[^\r\n]*""",
      setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
    )

  // Excludes the English "bearer of ..." so prose is not mangled.
  private val BEARER_TOKEN_RE =
    Regex("""(\bbearer\s+)(?!of(?:\s|$))[^\s,;]+""", RegexOption.IGNORE_CASE)

  private val BASIC_TOKEN_RE =
    Regex("""(\bbasic\s+)([A-Za-z0-9+/]+={0,2})(?![A-Za-z0-9+/=])""", RegexOption.IGNORE_CASE)

  private const val MAX_BASIC_AUTH_TOKEN_CHARS = 16 * 1024

  fun isSensitiveKey(key: String): Boolean {
    val lower = key.lowercase(Locale.ROOT).replace('-', '_')
    return SENSITIVE_KEYS.contains(lower) || lower.startsWith(TEMP_KEY_PREFIX)
  }

  /**
   * Redacts credential constructs that key-based redaction cannot see, such as a signed URL or an
   * HTTP header quoted inside an error message.
   *
   * The input is matched as-is; escaped or percent-encoded spellings are not decoded first. A key's
   * value that opens an object or array is redacted through its closing bracket and the rest of the
   * value.
   */
  fun redactSensitivePatterns(text: String): String {
    var sanitized = AUTH_HEADER_RE.replace(text) { it.groupValues[1] + REDACTED_MESSAGE }
    sanitized = BEARER_TOKEN_RE.replace(sanitized) { it.groupValues[1] + REDACTED_MESSAGE }
    sanitized = BASIC_TOKEN_RE.replace(sanitized, ::redactBasicCredential)
    return redactKeyValues(sanitized)
  }

  /**
   * Redacts credential patterns in diagnostic [text] and truncates it to [maxLength] bytes.
   *
   * Text that parses as a JSON object or array is first redacted by key; text whose escapes decode
   * to a credential, or too long to inspect with no budget, is replaced whole. Unlike
   * [smartTruncate], the flag also counts redaction as lost content.
   */
  fun sanitizeText(text: String, maxLength: Int): SanitizedText {
    // Text too long to parse as JSON would be logged whole with only the patterns applied.
    if (text.length > MAX_JSON_INSPECT_CHARS && maxLength > MAX_JSON_INSPECT_CHARS) {
      return SanitizedText(REDACTED_TEXT_MESSAGE, isChanged = true)
    }
    // JSON that does not parse is left to the patterns, like any other text.
    val json =
      redactJsonBlob(text, Int.MAX_VALUE)?.node?.asText()?.takeIf { it != UNPARSEABLE_JSON_MESSAGE }
    val redacted = redactSensitivePatterns(json ?: text)
    // An escaped credential cannot be cut out of the text as written, so the text is replaced.
    if (hidesEscapedCredential(redacted)) {
      return SanitizedText(REDACTED_TEXT_MESSAGE, isChanged = true)
    }
    val sanitized = truncate(redacted, maxLength)
    return SanitizedText(sanitized, sanitized != text)
  }

  // Whether decoding the ASCII escapes in [text] exposes a credential pattern.
  private fun hidesEscapedCredential(text: String): Boolean {
    val decoded = decodeAsciiEscapes(text)
    return decoded != text && redactSensitivePatterns(decoded) != decoded
  }

  /**
   * Decodes `%XX`, `\xXX` and `\uXXXX` escapes of ASCII characters in [text], including ones that
   * decoding reveals, such as `%255F`.
   *
   * The result is for credential checks only and is never logged.
   */
  fun decodeAsciiEscapes(text: String): String {
    if ('%' !in text && '\\' !in text) return text
    val decoded = StringBuilder(text.length)
    for (char in text) {
      decoded.append(char)
      while (true) {
        val start = asciiEscapeStart(decoded)
        if (start < 0) break
        val digits = start + if (decoded[start] == '%') 1 else 2
        val code = decoded.substring(digits).toInt(16)
        if (code > 0x7F) break
        decoded.setLength(start)
        decoded.append(code.toChar())
      }
    }
    return decoded.toString()
  }

  // Start of the %XX, \xXX or \uXXXX escape that [text] ends with, or -1.
  private fun asciiEscapeStart(text: CharSequence): Int {
    val n = text.length
    fun hexFrom(from: Int) = (from until n).all { HexFormat.isHexDigit(text[it].code) }
    return when {
      n >= 6 && text[n - 6] == '\\' && text[n - 5] in "uU" && hexFrom(n - 4) -> n - 6
      n >= 4 && text[n - 4] == '\\' && text[n - 3] in "xX" && hexFrom(n - 2) -> n - 4
      n >= 3 && text[n - 3] == '%' && hexFrom(n - 2) -> n - 3
      else -> -1
    }
  }

  // A value that opens an object or array is redacted through its closing bracket and on to the
  // next separator, or to the end of [text], since the pattern's bare value would stop inside it.
  private fun redactKeyValues(text: String): String {
    var match = SENSITIVE_TEXT_KEY_RE.find(text) ?: return text
    val redacted = StringBuilder(text.length)
    var end = 0
    while (true) {
      redacted.append(text, end, match.range.first).append(redactKeyValue(match))
      val value = match.groupValues[2]
      end =
        if (value.startsWith('{') || value.startsWith('[')) {
          val close = containerEnd(text, match.range.last + 1 - value.length)
          VALUE_END_RE.find(text, close)?.range?.first ?: text.length
        } else {
          match.range.last + 1
        }
      match = SENSITIVE_TEXT_KEY_RE.find(text, end) ?: break
    }
    return redacted.append(text, end, text.length).toString()
  }

  // Index after the bracket that closes the one at [start], or the end of [text] if none does.
  private fun containerEnd(text: String, start: Int): Int {
    var depth = 0
    var inString = false
    var i = start
    while (i < text.length) {
      val char = text[i]
      when {
        inString -> if (char == '\\') i++ else if (char == '"') inString = false
        char == '"' -> inString = true
        char == '{' || char == '[' -> depth++
        char == '}' || char == ']' -> if (--depth == 0) return i + 1
      }
      i++
    }
    return text.length
  }

  private fun redactKeyValue(match: MatchResult): String {
    val value = match.groupValues[2]
    val quote = value.firstOrNull()?.takeIf { it == '"' || it == '\'' }?.toString().orEmpty()
    return "${match.groupValues[1]}$quote$REDACTED_MESSAGE$quote"
  }

  // Redacts oversized blobs, and decodable ones that are padded or decode to "user:password".
  private fun redactBasicCredential(match: MatchResult): String {
    val prefix = match.groupValues[1]
    val encoded = match.groupValues[2]
    if (encoded.length > MAX_BASIC_AUTH_TOKEN_CHARS) return prefix + REDACTED_MESSAGE
    val decoded =
      try {
        Base64.getDecoder().decode(encoded)
      } catch (_: IllegalArgumentException) {
        return match.value
      }
    if (!encoded.contains('=') && decoded.none { it == ':'.code.toByte() }) return match.value
    return prefix + REDACTED_MESSAGE
  }

  /**
   * Redacts sensitive keys and patterns in [obj] and truncates its strings to [maxLength] bytes.
   *
   * A string that opens like a JSON object or array is redacted by key, or replaced whole if it
   * cannot be parsed within [maxLength].
   */
  fun smartTruncate(obj: Any?, maxLength: Int): TruncationResult =
    smartTruncate(obj, maxLength, Collections.newSetFromMap(IdentityHashMap()), 0)

  /**
   * [smartTruncate] for user-facing [text], such as a model's reply.
   *
   * Bracket-led text that does not parse as JSON, such as `[INFO] done`, is kept unless it holds a
   * credential, even an escaped one. JSON is redacted by key before the budget applies.
   */
  fun smartTruncateText(text: String, maxLength: Int): TruncationResult {
    // Inspected without the budget, which applies to the redacted text below.
    val blob =
      redactJsonBlob(text, Int.MAX_VALUE)
        ?: return redactAndTruncate(mapper.valueToTree(text), maxLength)
    val redacted = blob.node.asText()
    val isProse =
      redacted == UNPARSEABLE_JSON_MESSAGE &&
        text.length <= MAX_JSON_INSPECT_CHARS &&
        redactSensitivePatterns(text) == text &&
        !hidesEscapedCredential(text)
    val value = if (isProse) text else redacted
    val truncated = truncate(value, maxLength)
    return TruncationResult(
      mapper.valueToTree(truncated),
      isTruncated = (!isProse && blob.isTruncated) || truncated != value,
    )
  }

  private fun smartTruncate(
    obj: Any?,
    maxLength: Int,
    visited: MutableSet<Any>,
    depth: Int,
  ): TruncationResult {
    if (obj == null) {
      return TruncationResult(mapper.nullNode(), isTruncated = false)
    }
    if (depth > MAX_TRUNCATE_DEPTH) {
      return TruncationResult(mapper.valueToTree(MAX_DEPTH_MESSAGE), isTruncated = true)
    }
    if (obj is JsonNode) {
      return recursiveSmartTruncate(obj, maxLength, visited, depth)
    }
    if (obj is Map<*, *>) {
      if (!visited.add(obj)) {
        return TruncationResult(mapper.valueToTree(CYCLE_DETECTED_MESSAGE), isTruncated = false)
      }
      try {
        val node = mapper.createObjectNode()
        var isTruncated = false
        for ((keyObj, value) in obj) {
          val key = keyObj.toString()
          if (isSensitiveKey(key)) {
            node.set<JsonNode>(key, mapper.valueToTree(REDACTED_MESSAGE))
            continue
          }
          val res = smartTruncate(value, maxLength, visited, depth + 1)
          node.set<JsonNode>(key, res.node)
          isTruncated = isTruncated || res.isTruncated
        }
        return TruncationResult(node, isTruncated)
      } finally {
        visited.remove(obj)
      }
    }
    if (obj is Iterable<*>) {
      if (!visited.add(obj)) {
        return TruncationResult(mapper.valueToTree(CYCLE_DETECTED_MESSAGE), isTruncated = false)
      }
      try {
        val node = mapper.createArrayNode()
        var isTruncated = false
        for (element in obj) {
          val res = smartTruncate(element, maxLength, visited, depth + 1)
          node.add(res.node)
          isTruncated = isTruncated || res.isTruncated
        }
        return TruncationResult(node, isTruncated)
      } finally {
        visited.remove(obj)
      }
    }
    return try {
      recursiveSmartTruncate(mapper.valueToTree(obj), maxLength, visited, depth)
    } catch (e: IllegalArgumentException) {
      logger.debug { "smartTruncate replacing unsupported value: ${e::class.simpleName}" }
      TruncationResult(mapper.valueToTree(UNSUPPORTED_OBJECT_MESSAGE), isTruncated = true)
    }
  }

  private fun recursiveSmartTruncate(
    node: JsonNode,
    maxLength: Int,
    visited: MutableSet<Any>,
    depth: Int,
  ): TruncationResult {
    if (depth > MAX_TRUNCATE_DEPTH) {
      return TruncationResult(mapper.valueToTree(MAX_DEPTH_MESSAGE), isTruncated = true)
    }
    if (node.isContainerNode) {
      if (visited.contains(node)) {
        return TruncationResult(mapper.valueToTree(CYCLE_DETECTED_MESSAGE), isTruncated = false)
      }
      visited.add(node)
    }
    try {
      return when {
        node.isTextual ->
          redactJsonBlob(node.asText(), maxLength, depth) ?: redactAndTruncate(node, maxLength)
        // UTF-8 bytes are logged as redacted text, not as base64.
        node.isBinary -> {
          val text =
            try {
              node.binaryValue().decodeToString(throwOnInvalidSequence = true)
            } catch (_: CharacterCodingException) {
              return TruncationResult(
                mapper.valueToTree(UNDECODABLE_BYTES_MESSAGE),
                isTruncated = true,
              )
            }
          recursiveSmartTruncate(mapper.valueToTree(text), maxLength, visited, depth + 1)
        }
        node.isObject -> {
          val newNode = mapper.createObjectNode()
          var isTruncated = false
          for ((key, value) in node.properties()) {
            if (isSensitiveKey(key)) {
              newNode.set<JsonNode>(key, mapper.valueToTree(REDACTED_MESSAGE))
              continue
            }
            val res = recursiveSmartTruncate(value, maxLength, visited, depth + 1)
            newNode.set<JsonNode>(key, res.node)
            isTruncated = isTruncated || res.isTruncated
          }
          TruncationResult(newNode, isTruncated)
        }
        node.isArray -> {
          val newNode = mapper.createArrayNode()
          var isTruncated = false
          for (element in node) {
            val res = recursiveSmartTruncate(element, maxLength, visited, depth + 1)
            newNode.add(res.node)
            isTruncated = isTruncated || res.isTruncated
          }
          TruncationResult(newNode, isTruncated)
        }
        else -> TruncationResult(node, isTruncated = false)
      }
    } finally {
      if (node.isContainerNode) {
        visited.remove(node)
      }
    }
  }

  private fun redactAndTruncate(node: JsonNode, maxLength: Int): TruncationResult {
    val original = node.asText()
    val redacted = redactSensitivePatterns(original)
    val text = truncate(redacted, maxLength)
    return TruncationResult(
      if (text == original) node else mapper.valueToTree(text),
      isTruncated = text != redacted,
    )
  }

  /**
   * Redacts by key the JSON object or array that [text] holds and returns it re-serialized and
   * truncated to [maxLength] bytes, or null if [text] holds none or needs no change.
   *
   * Text that opens like one but does not parse, or is longer than [maxLength] or the inspection
   * limit, becomes [UNPARSEABLE_JSON_MESSAGE], since the patterns cannot see nested keys.
   */
  private fun redactJsonBlob(text: String, maxLength: Int, depth: Int = 0): TruncationResult? {
    if (text in MARKERS) return null
    val json =
      text
        .trimStart { it.isWhitespace() || it == '\uFEFF' }
        .takeIf { it.startsWith('{') || it.startsWith('[') } ?: return null
    val limit = minOf(maxLength, MAX_JSON_INSPECT_CHARS)
    val strict = parseJson(STRICT_JSON_READER, json, limit)
    val parsed =
      strict
        ?: parseJson(JSON_READER, json, limit)
        ?: return TruncationResult(mapper.valueToTree(UNPARSEABLE_JSON_MESSAGE), isTruncated = true)
    val redacted =
      recursiveSmartTruncate(
        parsed,
        Int.MAX_VALUE,
        Collections.newSetFromMap(IdentityHashMap()),
        depth + 1,
      )
    // A repeated key is always rewritten, since the text keeps a value the parsed tree dropped.
    if (strict != null && redacted.node == parsed && !redacted.isTruncated) return null
    val serialized = mapper.writeValueAsString(redacted.node)
    val truncated = truncate(serialized, maxLength)
    return TruncationResult(
      mapper.valueToTree(truncated),
      isTruncated = redacted.isTruncated || truncated != serialized,
    )
  }

  private fun parseJson(reader: ObjectReader, json: String, maxChars: Int): JsonNode? {
    if (json.length > maxChars) return null
    return try {
      reader.readTree(json)
    } catch (_: JsonProcessingException) {
      null
    }
  }

  fun truncate(s: String, budget: Int): String {
    if (s.toByteArray(Charsets.UTF_8).size <= budget) return s

    val suffixBytes = TRUNCATION_SUFFIX.toByteArray(Charsets.UTF_8).size
    val effectiveBudget = (budget - suffixBytes).coerceAtLeast(0)
    if (effectiveBudget == 0) {
      return TRUNCATION_SUFFIX.substring(0, budget.coerceAtMost(TRUNCATION_SUFFIX.length))
    }

    var byteCount = 0
    var i = 0
    while (i < s.length) {
      val codePoint = s.codePointAt(i)
      val codePointBytes =
        when {
          codePoint < 0x80 -> 1
          codePoint < 0x800 -> 2
          codePoint < 0x10000 -> 3
          else -> 4
        }
      if (byteCount + codePointBytes > effectiveBudget) break
      byteCount += codePointBytes
      i += Character.charCount(codePoint)
    }

    return s.substring(0, i) + TRUNCATION_SUFFIX
  }

  fun toKotlinObject(node: JsonNode?): Any? {
    if (node == null || node.isNull) return null
    return mapper.convertValue(node, Any::class.java)
  }
}
