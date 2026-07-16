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

package com.google.adk.firebase.utils

import com.google.adk.kt.logging.LoggerFactory
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.Citation
import com.google.adk.kt.types.CitationMetadata
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FileData
import com.google.adk.kt.types.FinishReason
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.GoogleMaps
import com.google.adk.kt.types.GoogleSearch
import com.google.adk.kt.types.GroundingMetadata
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.ThinkingConfig
import com.google.adk.kt.types.ThinkingLevel
import com.google.adk.kt.types.Tool
import com.google.adk.kt.types.Type
import com.google.adk.kt.types.UsageMetadata
import com.google.firebase.ai.type.Citation as FirebaseCitation
import com.google.firebase.ai.type.CitationMetadata as FirebaseCitationMetadata
import com.google.firebase.ai.type.Content as FirebaseContent
import com.google.firebase.ai.type.FileDataPart
import com.google.firebase.ai.type.FinishReason as FirebaseFinishReason
import com.google.firebase.ai.type.FunctionCallPart
import com.google.firebase.ai.type.FunctionDeclaration as FirebaseFunctionDeclaration
import com.google.firebase.ai.type.FunctionResponsePart
import com.google.firebase.ai.type.GenerateContentResponse
import com.google.firebase.ai.type.GenerationConfig
import com.google.firebase.ai.type.GoogleMaps as FirebaseGoogleMaps
import com.google.firebase.ai.type.GoogleSearch as FirebaseGoogleSearch
import com.google.firebase.ai.type.GroundingMetadata as FirebaseGroundingMetadata
import com.google.firebase.ai.type.InlineDataPart
import com.google.firebase.ai.type.Part as FirebasePart
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.RequestOptions
import com.google.firebase.ai.type.SafetySetting
import com.google.firebase.ai.type.Schema as FirebaseSchema
import com.google.firebase.ai.type.TextPart
import com.google.firebase.ai.type.ThinkingConfig as FirebaseThinkingConfig
import com.google.firebase.ai.type.ThinkingLevel as FirebaseThinkingLevel
import com.google.firebase.ai.type.Tool as FirebaseTool
import com.google.firebase.ai.type.ToolConfig
import com.google.firebase.ai.type.UsageMetadata as FirebaseUsageMetadata
import java.util.Base64
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

internal class Conversions {

  internal companion object {
    private val logger = LoggerFactory.getLogger(Conversions::class)

    private val allowedRoles = setOf("user", "model")

    private fun warnToolNotSupported(toolName: String) {
      logger.warn { "$toolName tool is not supported in Firebase" }
    }

    fun deserializeResponse(responseObject: JsonObject): Map<String, Any?> =
      responseObject.mapValues {
        AnySerializations.decodeJsonElementToAny(it.value)
      }

    fun deserializeArgument(argument: JsonElement): Any? =
      AnySerializations.decodeJsonElementToAny(argument)

    fun serializeResponse(responseMap: Map<String, *>): JsonObject = buildJsonObject {
      responseMap.forEach { (k, v) ->
        put(k, v as? JsonElement ?: AnySerializations.encodeAnyToJsonElement(v))
      }
    }

    fun serializeArgument(argument: Any?): JsonElement =
      argument as? JsonElement ?: AnySerializations.encodeAnyToJsonElement(argument)

    /**
     * Firebase carries `thoughtSignature` as a base64 [String] (the wire form of the proto `bytes`
     * field), whereas ADK's [Part.thoughtSignature] holds the decoded bytes. These two helpers
     * bridge the representations.
     */
    fun decodeThoughtSignature(signature: String?): ByteArray? = signature?.let {
      Base64.getDecoder().decode(it)
    }

    fun encodeThoughtSignature(signature: ByteArray?): String? = signature?.let {
      Base64.getEncoder().encodeToString(it)
    }

    /** Reads the base64 `thoughtSignature` carried on a concrete firebase [FirebasePart]. */
    fun firebaseThoughtSignature(part: FirebasePart): String? =
      when (part) {
        is TextPart -> part.thoughtSignature
        is InlineDataPart -> part.thoughtSignature
        is FileDataPart -> part.thoughtSignature
        is FunctionCallPart -> part.thoughtSignature
        is FunctionResponsePart -> part.thoughtSignature
        else -> null
      }
  }

  fun <T> convertRequest(request: LlmRequest, block: RequestConverter.() -> T): T {
    return forRequest(request).convert(block)
  }

  fun convertResponse(response: GenerateContentResponse): LlmResponse {
    val candidate = response.candidates.firstOrNull()
    if (response.candidates.size > 1) {
      logger.warn { "Multiple candidates found in the response, only the first one will be used" }
    }

    val finishReason = candidate?.finishReason?.let { toAdkFinishReason(it) }
    return LlmResponse(
      content = candidate?.content?.let { toAdkContent(it) },
      usageMetadata = response.usageMetadata?.let { toAdkUsageMetadata(it) },
      finishReason = finishReason,
      errorCode = finishReason?.takeIf { it != FinishReason.STOP }?.name,
      citationMetadata = candidate?.citationMetadata?.let { toAdkCitationMetadata(it) },
      groundingMetadata = candidate?.groundingMetadata?.let { toAdkGroundingMetadata(it) },
      errorMessage =
        if (
          (response.promptFeedback?.blockReason != null ||
            response.promptFeedback?.blockReasonMessage != null)
        ) {
          "Content was blocked with reason: ${response.promptFeedback?.blockReason} and message: ${response.promptFeedback?.blockReasonMessage}"
        } else {
          null
        },
    )
  }

  fun toAdkCitationMetadata(citationMetadata: FirebaseCitationMetadata): CitationMetadata =
    CitationMetadata(citationSources = citationMetadata.citations.map { toAdkCitation(it) })

  fun toAdkCitation(citation: FirebaseCitation): Citation =
    Citation(
      title = citation.title,
      uri = citation.uri,
      startIndex = citation.startIndex,
      endIndex = citation.endIndex,
    )

  fun toAdkGroundingMetadata(groundingMetadata: FirebaseGroundingMetadata): GroundingMetadata =
    GroundingMetadata(webSearchQueries = groundingMetadata.webSearchQueries)

  fun toAdkFinishReason(finishReason: FirebaseFinishReason): FinishReason =
    when (finishReason) {
      FirebaseFinishReason.STOP -> FinishReason.STOP
      FirebaseFinishReason.PROHIBITED_CONTENT -> FinishReason.PROHIBITED_CONTENT
      FirebaseFinishReason.MAX_TOKENS -> FinishReason.MAX_TOKENS
      FirebaseFinishReason.MALFORMED_FUNCTION_CALL -> FinishReason.MALFORMED_FUNCTION_CALL
      FirebaseFinishReason.SAFETY -> FinishReason.SAFETY
      FirebaseFinishReason.RECITATION -> FinishReason.RECITATION
      FirebaseFinishReason.OTHER -> FinishReason.OTHER
      FirebaseFinishReason.BLOCKLIST -> FinishReason.BLOCKLIST
      FirebaseFinishReason.SPII -> FinishReason.SPII
      FirebaseFinishReason.UNKNOWN -> FinishReason.FINISH_REASON_UNSPECIFIED
      FirebaseFinishReason.UNEXPECTED_TOOL_CALL -> FinishReason.UNEXPECTED_TOOL_CALL
      else -> FinishReason.FINISH_REASON_UNSPECIFIED
    }

  fun toAdkUsageMetadata(usageMetadata: FirebaseUsageMetadata): UsageMetadata =
    UsageMetadata(
      promptTokenCount = usageMetadata.promptTokenCount,
      candidatesTokenCount = usageMetadata.candidatesTokenCount,
      totalTokenCount = usageMetadata.totalTokenCount,
      thoughtsTokenCount = usageMetadata.thoughtsTokenCount,
      toolUsePromptTokenCount = usageMetadata.toolUsePromptTokenCount,
    )

  fun forRequest(request: LlmRequest): RequestConverter = RequestConverter(request)

  fun toFirebaseThinkingConfig(thinkingConfig: ThinkingConfig): FirebaseThinkingConfig =
    toFirebaseThinkingConfigBuilder(thinkingConfig).build()

  fun toFirebaseThinkingConfigBuilder(
    thinkingConfig: ThinkingConfig
  ): FirebaseThinkingConfig.Builder =
    FirebaseThinkingConfig.Builder().apply {
      includeThoughts = thinkingConfig.includeThoughts
      thinkingBudget = thinkingConfig.thinkingBudget
      thinkingLevel = thinkingConfig.thinkingLevel?.let { toFirebaseThinkingLevel(it) }
    }

  fun toFirebaseThinkingLevel(thinkingLevel: ThinkingLevel): FirebaseThinkingLevel? =
    when (thinkingLevel) {
      ThinkingLevel.MINIMAL -> FirebaseThinkingLevel.MINIMAL
      ThinkingLevel.LOW -> FirebaseThinkingLevel.LOW
      ThinkingLevel.MEDIUM -> FirebaseThinkingLevel.MEDIUM
      ThinkingLevel.HIGH -> FirebaseThinkingLevel.HIGH
      ThinkingLevel.THINKING_LEVEL_UNSPECIFIED -> null
    }

  // warn if role is not one of the role strings allowed by firebase api - don't throw just yet,
  // maybe the server can still tolerate the value
  fun inspectRole(role: String?): String? {
    if (role != null && role !in allowedRoles) {
      logger.warn { "Role should be one of $allowedRoles, but \"$role\" was encountered" }
    }
    return role
  }

  fun toFirebaseContent(content: Content): FirebaseContent =
    with(content) {
      FirebaseContent(role = inspectRole(role), parts = parts.map { toFirebasePart(it) })
    }

  fun toAdkContent(content: FirebaseContent): Content =
    with(content) { Content(role = role, parts = parts.map { toAdkPart(it) }) }

  fun toAdkPart(part: FirebasePart): Part {
    val base =
      when (part) {
        is TextPart -> Part(text = part.text)
        is InlineDataPart -> Part(inlineData = toAdkInlineData(part))
        is FileDataPart -> Part(fileData = toAdkFileData(part))
        is FunctionCallPart -> Part(functionCall = toAdkFunctionCall(part))
        is FunctionResponsePart -> Part(functionResponse = toAdkFunctionResponse(part))
        else -> throw IllegalArgumentException("Unsupported part type: $part")
      }
    return base.copy(
      thought = if (part.isThought) true else null,
      thoughtSignature = decodeThoughtSignature(firebaseThoughtSignature(part)),
    )
  }

  fun toFirebasePart(part: Part): FirebasePart {
    // saving the fields in vals to enable smart casts
    val text = part.text
    val inlineData = part.inlineData
    val fileData = part.fileData
    val functionCall = part.functionCall
    val functionResponse = part.functionResponse
    return when {
      text != null -> applyThinking(toFirebaseText(text), part)
      inlineData != null -> applyThinking(toFirebaseInlineData(inlineData), part)
      fileData != null -> applyThinking(toFirebaseFileData(fileData), part)
      functionCall != null -> applyThinking(toFirebaseFunctionCall(functionCall), part)
      functionResponse != null -> applyThinking(toFirebaseFunctionResponse(functionResponse), part)
      else -> throw IllegalArgumentException("Unsupported part type: $part")
    }
  }

  /** True when the ADK [Part] carries thinking metadata firebase's plain constructors can't set. */
  private fun Part.hasThinking(): Boolean = thought == true || thoughtSignature != null

  /**
   * Returns this firebase part unchanged, unless the source ADK [part] carries thinking metadata (a
   * thought marker or signature) — in which case it returns [block]'s result. [block] is expected
   * to rebuild this part carrying that metadata via `createWithThinking`.
   */
  private inline fun <P : FirebasePart> P.unlessHasThinking(part: Part, block: (P) -> P): P =
    if (part.hasThinking()) block(this) else this

  @OptIn(PublicPreviewAPI::class)
  private fun applyThinking(firebasePart: TextPart, part: Part): TextPart =
    firebasePart.unlessHasThinking(part) {
      TextPart.createWithThinking(
        it.text,
        part.thought ?: false,
        encodeThoughtSignature(part.thoughtSignature),
      )
    }

  @OptIn(PublicPreviewAPI::class)
  private fun applyThinking(firebasePart: InlineDataPart, part: Part): InlineDataPart =
    firebasePart.unlessHasThinking(part) {
      InlineDataPart.createWithThinking(
        it.inlineData,
        it.mimeType,
        it.displayName,
        part.thought ?: false,
        encodeThoughtSignature(part.thoughtSignature),
      )
    }

  @OptIn(PublicPreviewAPI::class)
  private fun applyThinking(firebasePart: FileDataPart, part: Part): FileDataPart =
    firebasePart.unlessHasThinking(part) {
      FileDataPart.createWithThinking(
        it.uri,
        it.mimeType,
        part.thought ?: false,
        encodeThoughtSignature(part.thoughtSignature),
      )
    }

  @OptIn(PublicPreviewAPI::class)
  private fun applyThinking(firebasePart: FunctionCallPart, part: Part): FunctionCallPart =
    firebasePart.unlessHasThinking(part) {
      FunctionCallPart.createWithThinking(
        it.name,
        it.args,
        it.id,
        part.thought ?: false,
        encodeThoughtSignature(part.thoughtSignature),
      )
    }

  @OptIn(PublicPreviewAPI::class)
  private fun applyThinking(firebasePart: FunctionResponsePart, part: Part): FunctionResponsePart =
    firebasePart.unlessHasThinking(part) {
      FunctionResponsePart.createWithThinking(
        it.name,
        it.response,
        it.id,
        it.parts,
        part.thought ?: false,
        encodeThoughtSignature(part.thoughtSignature),
      )
    }

  fun toFirebaseText(text: String): TextPart = TextPart(text = text)

  fun toFirebaseInlineData(inlineData: Blob): InlineDataPart =
    with(inlineData) {
      val localData = requireNotNull(data) { "Inline data is null" }
      val localMimeType = requireNotNull(mimeType) { "Mime type is null" }

      displayName?.let {
        InlineDataPart(inlineData = localData, mimeType = localMimeType, displayName = it)
      } ?: InlineDataPart(inlineData = localData, mimeType = localMimeType)
    }

  fun toAdkInlineData(inlineDataPart: InlineDataPart): Blob =
    with(inlineDataPart) { Blob(data = inlineData, mimeType = mimeType, displayName = displayName) }

  fun toFirebaseFileData(fileData: FileData): FileDataPart =
    with(fileData) {
      val nonNullUri = requireNotNull(fileUri) { "File URI is null" }
      val nonNullMimeType = requireNotNull(mimeType) { "Mime type is null" }

      FileDataPart(uri = nonNullUri, mimeType = nonNullMimeType)
    }

  fun toAdkFileData(fileDataPart: FileDataPart): FileData =
    with(fileDataPart) { FileData(fileUri = uri, mimeType = mimeType) }

  fun toFirebaseFunctionCall(functionCall: FunctionCall): FunctionCallPart =
    with(functionCall) {
      FunctionCallPart(name = name, args = args.mapValues { serializeArgument(it.value) }, id = id)
    }

  fun toAdkFunctionCall(functionCallPart: FunctionCallPart): FunctionCall =
    with(functionCallPart) {
      FunctionCall(name = name, args = args.mapValues { deserializeArgument(it.value) }, id = id)
    }

  fun toFirebaseFunctionResponse(functionResponse: FunctionResponse): FunctionResponsePart =
    with(functionResponse) {
      FunctionResponsePart(name = name, response = serializeResponse(response), id = id)
    }

  fun toAdkFunctionResponse(functionResponsePart: FunctionResponsePart): FunctionResponse =
    with(functionResponsePart) {
      FunctionResponse(name = name, response = deserializeResponse(response), id = id)
    }

  fun toFirebaseTool(tool: Tool): FirebaseTool? =
    with(tool) {
      // saving the fields in local vals to enable smart casts
      val localGoogleSearch = googleSearch
      val localFunctionDeclarations = functionDeclarations
      val localGoogleMaps = googleMaps
      when {
        localGoogleSearch != null ->
          FirebaseTool.googleSearch(toFirebaseGoogleSearch(localGoogleSearch))
        localGoogleMaps != null -> FirebaseTool.googleMaps(toFirebaseGoogleMaps(localGoogleMaps))
        retrieval != null -> null.also { warnToolNotSupported("Retrieval") }
        localFunctionDeclarations != null ->
          FirebaseTool.functionDeclarations(
            localFunctionDeclarations.map { toFirebaseFunctionDeclaration(it) }
          )
        else -> throw IllegalArgumentException("Unsupported tool type: $tool")
      }
    }

  fun toFirebaseGoogleSearch(googleSearch: GoogleSearch): FirebaseGoogleSearch =
    with(googleSearch) {
      if (excludeDomains.isNotEmpty()) {
        logger.warn {
          "GoogleSearch tool exclude domains are not supported in Firebase: $excludeDomains"
        }
      }
      FirebaseGoogleSearch()
    }

  fun toFirebaseGoogleMaps(googleMaps: GoogleMaps): FirebaseGoogleMaps =
    with(googleMaps) {
      if (enableWidget != null) {
        logger.warn {
          "GoogleMap tool's enable widget setting not supported in Firebase: $enableWidget"
        }
      }
      FirebaseGoogleMaps()
    }

  fun optionalParameters(schema: Schema?): List<String> {
    return if (schema == null) {
      emptyList()
    } else {
      with(schema) {
        val allProperties = properties?.keys ?: emptySet()
        val required = required?.toSet() ?: emptySet()
        return (allProperties - required).toList()
      }
    }
  }

  fun toFirebaseFunctionDeclaration(
    functionDeclaration: FunctionDeclaration
  ): FirebaseFunctionDeclaration =
    with(functionDeclaration) {
      FirebaseFunctionDeclaration(
        name = name,
        description = description,
        parameters = parameters?.properties?.mapValues { toFirebaseSchema(it.value) } ?: emptyMap(),
        optionalParameters = optionalParameters(parameters),
      )
    }

  fun toFirebaseSchema(schema: Schema): FirebaseSchema =
    with(schema) {
      val localEnum = enum
      if (localEnum != null) {
        FirebaseSchema.enumeration(values = localEnum, description = description)
      } else {
        when (this.type) {
          Type.OBJECT -> {
            val nonNullProps =
              requireNotNull(properties) {
                "Object properties schema is null"
              } // TODO: check if properties can be null when type is object
            FirebaseSchema.obj(
              properties = nonNullProps.mapValues { toFirebaseSchema(it.value) },
              description = description,
              optionalProperties = optionalParameters(this),
            )
          }

          Type.ARRAY -> {
            val nonNullItems =
              requireNotNull(items) {
                "Array items schema is null"
              } // TODO: check if items can be null when type is array
            FirebaseSchema.array(items = toFirebaseSchema(nonNullItems), description = description)
          }

          Type.STRING -> FirebaseSchema.string(description = description)
          Type.INTEGER -> FirebaseSchema.long(description = description)
          Type.NUMBER -> FirebaseSchema.double(description = description)
          Type.BOOLEAN -> FirebaseSchema.boolean(description = description)
          Type.NULL,
          Type.TYPE_UNSPECIFIED,
          null -> throw IllegalArgumentException("Unsupported schema type: ${this.type}")
        }
      }
    }

  inner class RequestConverter(val request: LlmRequest) {

    fun <T> convert(block: RequestConverter.() -> T): T = block(this)

    fun contents(): List<FirebaseContent> = with(request) { contents.map { toFirebaseContent(it) } }

    fun generationConfig(): GenerationConfig = generationConfigBuilder().build()

    fun generationConfigBuilder(): GenerationConfig.Builder =
      with(request) {
        GenerationConfig.builder().apply {
          temperature = config.temperature
          maxOutputTokens = config.maxOutputTokens
          topP = config.topP
          topK = config.topK
          stopSequences = config.stopSequences
          candidateCount = config.candidateCount
          responseMimeType = config.responseMimeType
          thinkingConfig = config.thinkingConfig?.let { toFirebaseThinkingConfig(it) }
        }
      }

    // returning null since there doesn't seem to be an equivalent configuration setting available
    // in adk
    fun safetySettings(): List<SafetySetting>? = null

    fun tools(): List<FirebaseTool>? = request.config.tools?.mapNotNull { toFirebaseTool(it) }

    // returning null since there doesn't seem to be an equivalent configuration setting available
    // in adk
    fun toolConfig(): ToolConfig? = null

    fun systemInstruction(): FirebaseContent? =
      request.config.systemInstruction?.let { toFirebaseContent(it) }

    fun requestOptions(): RequestOptions = RequestOptions()
  }
}
