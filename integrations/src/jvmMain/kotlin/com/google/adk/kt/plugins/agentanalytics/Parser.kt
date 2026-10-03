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
import com.google.adk.kt.plugins.agentanalytics.JsonFormatter.mapper
import com.google.adk.kt.plugins.agentanalytics.JsonFormatter.smartTruncate
import com.google.adk.kt.plugins.agentanalytics.JsonFormatter.truncate
import com.google.adk.kt.plugins.agentanalytics.JsonFormatter.truncateWithStatus
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part

/** Utility for parsing content for BigQuery logging. */
internal class Parser(
  private val maxLength: Int,
  private val logMultiModalContent: Boolean = true,
) {

  internal data class ParsedContent(
    val parts: List<JsonNode>,
    val content: JsonNode,
    val isTruncated: Boolean,
  )

  internal data class ParsedContentObject(
    val parts: ArrayNode,
    val summary: String,
    val isTruncated: Boolean,
  )

  fun parse(content: Any?, traceId: String, spanId: String): ParsedContent {
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
        message.put("role", c.role ?: "unknown")
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
      val partsList = mutableListOf<JsonNode>()
      contentParts.forEach { partsList.add(it) }
      return ParsedContent(partsList, jsonPayload, isTruncated)
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

      val partsList = mutableListOf<JsonNode>()
      parsed.parts.forEach { partsList.add(it) }
      return ParsedContent(partsList, jsonPayload, parsed.isTruncated)
    }

    if (content is Content || content is Part) {
      val parsed = parseContentObject(content)
      val summaryNode = mapper.createObjectNode()
      summaryNode.put("text_summary", parsed.summary)
      val partsList = mutableListOf<JsonNode>()
      parsed.parts.forEach { partsList.add(it) }
      return ParsedContent(partsList, summaryNode, parsed.isTruncated)
    }

    val result =
      if (content is String) {
        truncateWithStatus(content, maxLength)
      } else {
        smartTruncate(content, maxLength)
      }
    return ParsedContent(emptyList(), result.node, result.isTruncated)
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
      val textNode = res.node.get("text")
      if (textNode != null && !textNode.isNull) {
        summaries.add(textNode.asText())
      }
    }

    var summary = summaries.joinToString(" | ")
    if (summary.toByteArray(Charsets.UTF_8).size > maxLength) {
      summary = truncate(summary, maxLength) ?: ""
      isTruncated = true
    }
    return ParsedContentObject(contentParts, summary, isTruncated)
  }

  private fun processPart(part: Part, index: Int): JsonFormatter.TruncationResult {
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
      fileData.fileUri?.let { partNode.put("uri", it) }
      fileData.mimeType?.let { partNode.put("mime_type", it) }
      return JsonFormatter.TruncationResult(partNode, false)
    }

    val inlineData = part.inlineData
    if (inlineData != null) {
      val mimeType = inlineData.mimeType ?: "application/octet-stream"
      partNode.put("text", BINARY_DATA_MESSAGE)
      partNode.put("mime_type", mimeType)
      return JsonFormatter.TruncationResult(partNode, false)
    }

    val text = part.text
    if (text != null) {
      val res = truncateWithStatus(text, maxLength)
      partNode.put("text", res.node.asText())
      return JsonFormatter.TruncationResult(partNode, res.isTruncated)
    }

    val fc = part.functionCall
    if (fc != null) {
      val partAttributes = mapper.createObjectNode()
      partAttributes.put("function_name", fc.name)
      partNode.put("mime_type", "application/json")
      partNode.put("text", "Function: ${fc.name}")
      partNode.put("part_attributes", partAttributes.toString())
      return JsonFormatter.TruncationResult(partNode, false)
    }

    return JsonFormatter.TruncationResult(partNode, false)
  }

  companion object {
    private const val BINARY_DATA_MESSAGE = "[BINARY DATA]"
  }
}
