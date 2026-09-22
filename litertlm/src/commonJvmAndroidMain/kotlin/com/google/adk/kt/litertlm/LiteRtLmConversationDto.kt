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

package com.google.adk.kt.litertlm

import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.serialization.Json
import com.google.adk.kt.types.Content as AdkContent
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.Part as AdkPart
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type as AdkSchemaType
import com.google.ai.edge.litertlm.Content as LiteRtLmContent
import com.google.ai.edge.litertlm.Contents as LiteRtLmContents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Message as LiteRtLmMessage
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.Role as LiteRtLmRole
import com.google.ai.edge.litertlm.ToolCall as LiteRtLmToolCall
import com.google.ai.edge.litertlm.tool

/**
 * Value-typed representation of the inputs a LiteRT-LM conversation is created from: it both keys
 * the conversation cache and maps on to the native [ConversationConfig] ([toConversationConfig]),
 * so a conversation is reused only when system instruction, history, and tools all match. Values
 * that lack structural equality -- byte payloads, and any non-scalar nested in a tool call's
 * arguments or a tool response -- compare by identity, which can only cause a safe cache miss,
 * never a wrong reuse.
 */
internal data class LiteRtLmConversationDto(
  val systemInstruction: List<LiteRtLmContent>?,
  val initialMessages: List<LiteRtLmMessageDto>,
  val toolDescriptions: List<String>,
)

/** Value-typed form of a LiteRT-LM [LiteRtLmMessage]: its role, mapped contents, and tool calls. */
internal data class LiteRtLmMessageDto(
  val role: LiteRtLmRole,
  val contents: List<LiteRtLmContent>,
  val toolCalls: List<LiteRtLmToolCall>,
)

/** Translates this request's [history] and config into the cache-keyable DTO. */
internal fun LlmRequest.toConversationDto(history: List<AdkContent>): LiteRtLmConversationDto =
  LiteRtLmConversationDto(
    systemInstruction = config.systemInstruction?.parts?.mapNotNull { mapPartToContent(it) },
    initialMessages = history.map { it.toLiteRtLmMessageDto() },
    toolDescriptions =
      config.tools
        ?.flatMap { it.functionDeclarations.orEmpty() }
        ?.map { ManualOpenApiTool(it).getToolDescriptionJsonString() }
        .orEmpty(),
  )

/** Builds the native [ConversationConfig] this DTO describes. */
internal fun LiteRtLmConversationDto.toConversationConfig(): ConversationConfig =
  ConversationConfig(
    systemInstruction = systemInstruction?.let { LiteRtLmContents.of(it) },
    initialMessages = initialMessages.map { it.toLiteRtLmMessage() },
    tools = toolDescriptions.map { tool(JsonOpenApiTool(it)) },
    automaticToolCalling = false,
  )

/** Maps an ADK content directly to a LiteRT-LM message, for the current (last) turn to send. */
internal fun AdkContent.toLiteRtLmMessage(): LiteRtLmMessage =
  toLiteRtLmMessageDto().toLiteRtLmMessage()

private fun AdkContent.toLiteRtLmMessageDto(): LiteRtLmMessageDto {
  val role =
    if (parts.any { it.functionResponse != null }) {
      LiteRtLmRole.TOOL
    } else {
      when (role) {
        "user" -> LiteRtLmRole.USER
        "model" -> LiteRtLmRole.MODEL
        "system" -> LiteRtLmRole.SYSTEM
        "tool" -> LiteRtLmRole.TOOL
        else -> LiteRtLmRole.USER
      }
    }
  val contents = parts.mapNotNull { mapPartToContent(it) }
  // Only a model message carries tool calls; the runtime ignores them on other roles.
  val toolCalls =
    if (role == LiteRtLmRole.MODEL) {
      parts.mapNotNull { part ->
        part.functionCall?.let { fc -> LiteRtLmToolCall(fc.name, fc.args) }
      }
    } else {
      emptyList()
    }
  return LiteRtLmMessageDto(role, contents, toolCalls)
}

private fun LiteRtLmMessageDto.toLiteRtLmMessage(): LiteRtLmMessage {
  val contents = LiteRtLmContents.of(contents)
  return when (role) {
    LiteRtLmRole.USER -> LiteRtLmMessage.user(contents)
    LiteRtLmRole.SYSTEM -> LiteRtLmMessage.system(contents)
    LiteRtLmRole.TOOL -> LiteRtLmMessage.tool(contents)
    LiteRtLmRole.MODEL -> LiteRtLmMessage.model(contents, toolCalls)
  }
}

private fun mapPartToContent(part: AdkPart): LiteRtLmContent? {
  // Use local variables to enable smart casts on properties from other module
  val text = part.text
  val inlineData = part.inlineData
  val fileData = part.fileData
  val functionResponse = part.functionResponse

  return when {
    text != null -> LiteRtLmContent.Text(text)
    inlineData != null -> {
      val mimeType = inlineData.mimeType.orEmpty().lowercase()
      val data = inlineData.data ?: byteArrayOf()
      when {
        mimeType.startsWith("image/") -> LiteRtLmContent.ImageBytes(data)
        mimeType.startsWith("audio/") -> LiteRtLmContent.AudioBytes(data)
        else -> null
      }
    }
    fileData != null -> {
      val mimeType = fileData.mimeType.orEmpty().lowercase()
      val path = fileData.fileUri.orEmpty()
      when {
        mimeType.startsWith("image/") -> LiteRtLmContent.ImageFile(path)
        mimeType.startsWith("audio/") -> LiteRtLmContent.AudioFile(path)
        else -> null
      }
    }
    functionResponse != null -> {
      LiteRtLmContent.ToolResponse(functionResponse.name, functionResponse.response)
    }
    else -> null // functionCall is handled separately
  }
}

// --- Tool adapters ---

// The conversation is built with automaticToolCalling = false, so LiteRT-LM returns tool calls to
// ADK rather than executing them; these adapters describe tools but are never executed here.
private fun toolsDispatchedByAdk(): Nothing =
  throw UnsupportedOperationException("LiteRT-LM tools are dispatched by ADK, not executed here")

/** [OpenApiTool] that serializes an ADK [FunctionDeclaration] to the tool description JSON. */
internal class ManualOpenApiTool(private val declaration: FunctionDeclaration) : OpenApiTool {
  override fun execute(paramsJsonString: String): String = toolsDispatchedByAdk()

  override fun getToolDescriptionJsonString(): String {
    val tool = mutableMapOf<String, Any>()
    tool["name"] = declaration.name
    tool["description"] = declaration.description
    declaration.parameters?.let { params -> tool["parameters"] = params.toMap() }
    // Describing what the tool returns helps the model decide whether to call it at all, and this
    // description is plain JSON, so there is nothing stopping it carrying the response schema.
    declaration.response?.let { response -> tool["response"] = response.toMap() }
    return Json.toJsonString(tool)
  }
}

/**
 * [OpenApiTool] carrying a precomputed tool description JSON, for rebuilding tools from the DTO.
 */
private class JsonOpenApiTool(private val descriptionJson: String) : OpenApiTool {
  override fun execute(paramsJsonString: String): String = toolsDispatchedByAdk()

  override fun getToolDescriptionJsonString(): String = descriptionJson
}

internal fun Schema.toMap(): Map<String, Any> {
  val map = mutableMapOf<String, Any>()

  type?.let { t ->
    val typeName =
      when (t) {
        AdkSchemaType.OBJECT -> "object"
        AdkSchemaType.STRING -> "string"
        AdkSchemaType.INTEGER -> "integer"
        AdkSchemaType.NUMBER -> "number"
        AdkSchemaType.BOOLEAN -> "boolean"
        AdkSchemaType.ARRAY -> "array"
        AdkSchemaType.NULL -> "null"
        // A schema carrying only `anyOf` has no type of its own; naming one here would sit next
        // to the alternatives and contradict them.
        else -> if (anyOf != null) null else "string"
      }
    typeName?.let { map["type"] = it }
  }

  description?.let { map["description"] = it }
  properties?.let { props -> map["properties"] = props.mapValues { (_, schema) -> schema.toMap() } }
  items?.let { map["items"] = it.toMap() }
  required?.let { map["required"] = it }
  enum?.let { map["enum"] = it }
  // The tool description is plain JSON rather than a typed backend schema, so every constraint a
  // caller can express is emitted verbatim under its JSON Schema name.
  format?.let { map["format"] = it }
  nullable?.let { map["nullable"] = it }
  default?.let { map["default"] = it }
  anyOf?.let { schemas -> map["anyOf"] = schemas.map { it.toMap() } }
  title?.let { map["title"] = it }
  pattern?.let { map["pattern"] = it }
  minimum?.let { map["minimum"] = it }
  maximum?.let { map["maximum"] = it }
  minLength?.let { map["minLength"] = it }
  maxLength?.let { map["maxLength"] = it }
  minItems?.let { map["minItems"] = it }
  maxItems?.let { map["maxItems"] = it }
  minProperties?.let { map["minProperties"] = it }
  maxProperties?.let { map["maxProperties"] = it }

  return map
}
