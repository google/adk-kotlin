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
import com.fasterxml.jackson.databind.node.ArrayNode
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.plugins.agentanalytics.JsonFormatter.SanitizedText
import com.google.adk.kt.plugins.agentanalytics.JsonFormatter.decodeAsciiEscapes
import com.google.adk.kt.plugins.agentanalytics.JsonFormatter.isSensitiveKey
import com.google.adk.kt.plugins.agentanalytics.JsonFormatter.mapper
import com.google.adk.kt.plugins.agentanalytics.JsonFormatter.sanitizeText
import com.google.adk.kt.plugins.agentanalytics.JsonFormatter.smartTruncate
import com.google.adk.kt.plugins.agentanalytics.JsonFormatter.smartTruncateText
import com.google.adk.kt.plugins.agentanalytics.JsonFormatter.truncate
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8

/**
 * Builds a row's `content` and `content_parts` values, with credentials redacted and each string
 * truncated to [maxLength] bytes.
 */
internal class Parser(private val maxLength: Int) {

  internal data class ParsedContent(
    val parts: List<JsonNode>,
    val content: JsonNode,
    val isTruncated: Boolean,
  )

  private data class ParsedContentObject(
    val parts: ArrayNode,
    val summary: String,
    val isTruncated: Boolean,
  )

  fun parse(content: Any?): ParsedContent {
    if (content is LlmRequest) {
      val jsonPayload = mapper.createObjectNode()
      val messages = mapper.createArrayNode()
      val contentParts = mapper.createArrayNode()
      var isTruncated = false

      for (c in content.contents) {
        val res = parseContentObject(c)
        isTruncated = isTruncated || res.isTruncated
        contentParts.addAll(res.parts)
        val message = mapper.createObjectNode()
        message.put("role", c.role ?: UNKNOWN)
        message.put("content", res.summary)
        messages.add(message)
      }
      if (messages.size() > 0) {
        jsonPayload.set<JsonNode>("prompt", messages)
      }
      val sysInstruction = content.config.systemInstruction
      if (sysInstruction != null) {
        val res = parseContentObject(sysInstruction)
        isTruncated = isTruncated || res.isTruncated
        contentParts.addAll(res.parts)
        jsonPayload.put("system_prompt", res.summary)
      }
      return ParsedContent(contentParts.toList(), jsonPayload, isTruncated)
    }

    if (content is LlmResponse) {
      val jsonPayload = mapper.createObjectNode()
      val parsed = parseContentObject(content.content)
      val summaryNode = mapper.createObjectNode()
      summaryNode.put("text_summary", parsed.summary)
      jsonPayload.set<JsonNode>("response", summaryNode)

      content.usageMetadata?.let { usage ->
        val usageNode = jsonPayload.putObject("usage")
        usage.promptTokenCount?.let { usageNode.put("prompt", it) }
        usage.candidatesTokenCount?.let { usageNode.put("completion", it) }
        usage.totalTokenCount?.let { usageNode.put("total", it) }
      }

      return ParsedContent(parsed.parts.toList(), jsonPayload, parsed.isTruncated)
    }

    if (content is Content || content is Part) {
      val parsed = parseContentObject(content)
      val summaryNode = mapper.createObjectNode()
      summaryNode.put("text_summary", parsed.summary)
      return ParsedContent(parsed.parts.toList(), summaryNode, parsed.isTruncated)
    }

    val result =
      if (content is String) smartTruncateText(content, maxLength)
      else smartTruncate(content, maxLength)
    return ParsedContent(emptyList(), result.node, result.isTruncated)
  }

  /**
   * Formats [texts] as `text: '...'` entries joined by ` | `.
   *
   * [SanitizedText.isChanged] reports redaction as well as truncation.
   */
  fun formatText(texts: List<String>): SanitizedText {
    val sanitized = texts.map { sanitizeText(it, maxLength) }
    return SanitizedText(
      sanitized.joinToString(" | ") { "text: '${it.text}'" },
      sanitized.any { it.isChanged },
    )
  }

  private fun parseContentObject(content: Any?): ParsedContentObject {
    val parts: List<Part> =
      when (content) {
        is Content -> content.parts
        is Part -> listOf(content)
        else -> emptyList()
      }

    val contentParts = mapper.createArrayNode()
    val summaries = mutableListOf<String>()
    var isTruncated = false

    for ((index, part) in parts.withIndex()) {
      val res = processPart(part, index)
      contentParts.add(res.node)
      isTruncated = isTruncated || res.isTruncated
      res.summary?.let { summaries.add(it) }
    }

    val summary = summaries.joinToString(" | ")
    val truncatedSummary = truncate(summary, maxLength)
    if (truncatedSummary != summary) isTruncated = true
    return ParsedContentObject(contentParts, truncatedSummary, isTruncated)
  }

  /** A `content_parts` entry and what it adds to the content summary. */
  private data class ProcessedPart(
    val node: JsonNode,
    val summary: String?,
    val isTruncated: Boolean,
  )

  private fun processPart(part: Part, index: Int): ProcessedPart {
    val partNode = mapper.createObjectNode()
    partNode.put("part_index", index)
    partNode.put("mime_type", "text/plain")
    partNode.putNull("uri")
    partNode.putNull("text")
    partNode.put("part_attributes", "{}")
    partNode.put("storage_mode", "INLINE")
    partNode.putNull("object_ref")

    val fileData = part.fileData
    if (fileData != null) {
      partNode.put("storage_mode", "EXTERNAL_URI")
      val uri = fileData.fileUri?.let { sanitizeExternalUri(it) }
      if (uri != null) partNode.put("uri", uri.text)
      fileData.mimeType?.let { partNode.put("mime_type", it) }
      return ProcessedPart(partNode, summary = null, isTruncated = uri?.isChanged == true)
    }

    val inlineData = part.inlineData
    if (inlineData != null) {
      val mimeType = inlineData.mimeType ?: "application/octet-stream"
      partNode.put("text", BINARY_DATA_MESSAGE)
      partNode.put("mime_type", mimeType)
      return ProcessedPart(partNode, summary = null, isTruncated = false)
    }

    val text = part.text
    if (!text.isNullOrEmpty()) {
      val res = smartTruncateText(text, maxLength)
      val cleanText = res.node.asText()
      partNode.put("text", cleanText)
      return ProcessedPart(partNode, cleanText, res.isTruncated)
    }

    val fc = part.functionCall
    if (fc != null) {
      val partAttributes = mapper.createObjectNode()
      partAttributes.put("function_name", fc.name)
      partNode.put("mime_type", "application/json")
      partNode.put("text", "Function: ${fc.name}")
      partNode.put("part_attributes", partAttributes.toString())
      return ProcessedPart(partNode, summary = null, isTruncated = false)
    }

    val functionResponse = part.functionResponse
    if (functionResponse != null) {
      val res =
        serializePartModel(
          "name" to functionResponse.name,
          "response" to functionResponse.response,
          "id" to functionResponse.id,
        )
      val summary = "Function response: ${res.node.path("name").asText().ifEmpty { UNKNOWN }}"
      partNode.put("mime_type", "application/json")
      partNode.put("text", summary)
      partNode.put("part_attributes", partAttributes("function_response", res.node))
      return ProcessedPart(partNode, summary, res.isTruncated)
    }

    val executableCode = part.executableCode
    if (executableCode != null) {
      val res =
        serializePartModel(
          "code" to executableCode.code,
          "language" to executableCode.language?.name,
          "id" to executableCode.id,
        )
      val language = res.node.path("language").asText().ifEmpty { UNKNOWN }
      val code = res.node.path("code").asText()
      partNode.put("text", code)
      partNode.put("part_attributes", partAttributes("executable_code", res.node))
      return ProcessedPart(partNode, "Executable code ($language): $code", res.isTruncated)
    }

    val codeExecutionResult = part.codeExecutionResult
    if (codeExecutionResult != null) {
      val res =
        serializePartModel(
          "outcome" to codeExecutionResult.outcome?.name,
          "output" to codeExecutionResult.output,
          "id" to codeExecutionResult.id,
        )
      val outcome = res.node.path("outcome").asText().ifEmpty { UNKNOWN }
      val output = res.node.path("output").asText()
      partNode.put("text", output)
      partNode.put("part_attributes", partAttributes("code_execution_result", res.node))
      return ProcessedPart(partNode, "Code execution result ($outcome): $output", res.isTruncated)
    }

    return ProcessedPart(partNode, summary = null, isTruncated = false)
  }

  // Drops null fields, redacts and bounds the rest.
  private fun serializePartModel(
    vararg fields: Pair<String, Any?>
  ): JsonFormatter.TruncationResult =
    smartTruncate(fields.filter { it.second != null }.toMap(), maxLength)

  private fun partAttributes(key: String, value: JsonNode): String =
    mapper.createObjectNode().set<JsonNode>(key, value).toString()

  /**
   * Redacts credentials in a file URI but keeps its location.
   *
   * A URI with userinfo is replaced whole. Otherwise key-named path segments and the segment after
   * them, sensitive query values, and credentials that [sanitizeText] finds in any component are
   * redacted, with percent-escapes decoded once before the key checks.
   */
  private fun sanitizeExternalUri(uri: String): SanitizedText {
    val groups =
      URI_RE.matchEntire(uri)?.groups ?: return SanitizedText(REDACTED_URI, isChanged = true)
    if (groups[AUTHORITY_GROUP]?.value?.contains('@') == true) {
      return SanitizedText(REDACTED_URI, isChanged = true)
    }
    val sanitized = buildString {
      groups[SCHEME_GROUP]?.let { append(it.value) }
      groups[AUTHORITY_WITH_SLASHES_GROUP]?.let { append(it.value) }
      append(sanitizeUriPath(groups[PATH_GROUP]?.value.orEmpty()))
      groups[QUERY_GROUP]?.let { append('?').append(sanitizeUriQuery(it.value)) }
      groups[FRAGMENT_GROUP]?.let { append('#').append(redactUriComponent(it.value)) }
    }
    val result = truncate(sanitized, maxLength)
    return SanitizedText(result, result != uri)
  }

  // The whole URI is truncated afterwards, so a component gets no budget of its own.
  private fun redactUriComponent(text: String): String = sanitizeText(text, Int.MAX_VALUE).text

  private fun sanitizeUriPath(path: String): String {
    var redactNext = false
    return path.split('/').joinToString("/") { segment ->
      when {
        segment.isEmpty() -> segment
        redactNext -> {
          redactNext = false
          ENCODED_REDACTED
        }
        isSensitiveKey(decodeAsciiEscapes(segment)) -> {
          redactNext = true
          ENCODED_REDACTED
        }
        else -> {
          val redacted = redactUriComponent(segment)
          if (redacted == segment) segment else percentEncode(redacted)
        }
      }
    }
  }

  private fun sanitizeUriQuery(query: String): String =
    query.split('&').joinToString("&") { pair ->
      val separator = pair.indexOf('=')
      val rawKey = if (separator < 0) pair else pair.substring(0, separator)
      val key = percentDecode(rawKey)
      if (isSensitiveKey(key)) return@joinToString "$rawKey=$ENCODED_REDACTED"
      val value = if (separator < 0) "" else percentDecode(pair.substring(separator + 1))
      val safeKey = redactUriComponent(key)
      val safeValue = redactUriComponent(value)
      if (safeKey == key && safeValue == value) {
        pair
      } else {
        "${URLEncoder.encode(safeKey, UTF_8)}=${URLEncoder.encode(safeValue, UTF_8)}"
      }
    }

  companion object {
    private const val BINARY_DATA_MESSAGE = "[BINARY DATA]"
    private const val UNKNOWN = "unknown"
    private const val REDACTED_URI = "[REDACTED_SENSITIVE_URI]"
    private const val ENCODED_REDACTED = "%5BREDACTED%5D"

    // RFC 3986 appendix B: every component is optional, so any string splits.
    private val URI_RE = Regex("""(?s)(([^:/?#]+):)?(//([^/?#]*))?([^?#]*)(\?([^#]*))?(#(.*))?""")
    private const val SCHEME_GROUP = 1
    private const val AUTHORITY_WITH_SLASHES_GROUP = 3
    private const val AUTHORITY_GROUP = 4
    private const val PATH_GROUP = 5
    private const val QUERY_GROUP = 7
    private const val FRAGMENT_GROUP = 9

    // Form decoding, so `+` is a space; a malformed escape leaves the text as it was.
    private fun percentDecode(text: String): String =
      try {
        URLDecoder.decode(text, UTF_8)
      } catch (_: IllegalArgumentException) {
        text
      }

    // Leaves only RFC 3986 unreserved characters literal.
    private fun percentEncode(text: String): String =
      URLEncoder.encode(text, UTF_8).replace("+", "%20").replace("*", "%2A").replace("%7E", "~")
  }
}
