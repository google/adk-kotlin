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
import com.google.firebase.ai.type.BlockReason as FirebaseBlockReason
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
import com.google.firebase.ai.type.StringFormat
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
        val unused = put(k, v as? JsonElement ?: AnySerializations.encodeAnyToJsonElement(v))
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

    // Mirror Gemini's LlmResponse.from(): fall back to the block reason when there is no candidate,
    // so a blocked response still carries a finish reason (and therefore an error code).
    val finishReason =
      candidate?.finishReason?.let { toAdkFinishReason(it) }
        ?: response.promptFeedback?.blockReason?.let { blockReasonToAdkFinishReason(it) }
    return LlmResponse(
      content = candidate?.content?.let { toAdkContent(it) },
      usageMetadata = response.usageMetadata?.let { toAdkUsageMetadata(it) },
      finishReason = finishReason,
      errorCode = finishReason?.takeIf { it != FinishReason.STOP }?.name,
      citationMetadata = candidate?.citationMetadata?.let { toAdkCitationMetadata(it) },
      groundingMetadata = candidate?.groundingMetadata?.let { toAdkGroundingMetadata(it) },
      errorMessage =
        toErrorMessage(
          finishReason,
          candidate?.finishMessage ?: response.promptFeedback?.blockReasonMessage,
        ),
    )
  }

  /**
   * Returns the error message for a response, or `null` if it is not an error. Only a finish reason
   * other than [FinishReason.STOP] is treated as an error; in that case [message] is used, falling
   * back to a generic "Unknown error.". This matches the behavior of Gemini backend's
   * `LlmResponse.from()`.
   */
  fun toErrorMessage(finishReason: FinishReason?, message: String?): String? =
    finishReason?.takeIf { it != FinishReason.STOP }?.let { message ?: "Unknown error." }

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

  fun blockReasonToAdkFinishReason(blockReason: FirebaseBlockReason): FinishReason =
    when (blockReason) {
      FirebaseBlockReason.SAFETY -> FinishReason.SAFETY
      FirebaseBlockReason.BLOCKLIST -> FinishReason.BLOCKLIST
      FirebaseBlockReason.PROHIBITED_CONTENT -> FinishReason.PROHIBITED_CONTENT
      FirebaseBlockReason.OTHER -> FinishReason.OTHER
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
      // History now keeps parts a server-side tool minted, which Firebase cannot express. Drop a
      // part only when nothing else on it is expressible, so a thought signature riding on a tool
      // call still reaches the model instead of leaving with the part that carried it.
      FirebaseContent(
        role = inspectRole(role),
        parts = parts.filter { it.isExpressibleInFirebase() }.map { toFirebasePart(it) },
      )
    }

  /** True when [toFirebasePart] has a branch for this part; mirrors its `when`. */
  private fun Part.isExpressibleInFirebase(): Boolean =
    text != null ||
      inlineData != null ||
      fileData != null ||
      functionCall != null ||
      functionResponse != null ||
      thoughtSignature != null

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
        else -> throw IllegalArgumentException("Unsupported part type: ${part::class.simpleName}")
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
      // A thought signature arrives on a part holding nothing else; Firebase carries it as an
      // empty text part so the model still gets its own state back.
      part.thoughtSignature != null -> applyThinking(toFirebaseText(""), part)
      else -> throw IllegalArgumentException("Unsupported part type")
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
      // Firebase describes a declaration's parameters as a map of named properties, so only the
      // properties are converted and the schema around them is not. A top-level union, or an
      // object left open with no properties, therefore reaches the model as a tool taking no
      // arguments at all. There is no field to put either one in, so this is reported rather than
      // fixed. An empty property map is not the same thing: that tool really does take none.
      parameters?.let { params ->
        if (params.anyOf != null || params.properties == null) {
          logger.warn {
            "Function declaration parameters are a schema Firebase cannot express as named " +
              "properties, so the tool is advertised without parameters: $name"
          }
        }
      }
      if (response != null) {
        logger.warn {
          "Function declaration response schema is not supported in Firebase and is dropped: $name"
        }
      }
      FirebaseFunctionDeclaration(
        name = name,
        description = description,
        parameters = parameters?.properties?.mapValues { toFirebaseSchema(it.value) } ?: emptyMap(),
        optionalParameters = optionalParameters(parameters),
      )
    }

  fun toFirebaseSchema(schema: Schema): FirebaseSchema =
    with(schema) {
      // Firebase models a union as a schema that carries only `anyOf`, so a typed schema cannot
      // hold one as well. An untyped union is the shape a server sends, and reaching the `when`
      // below with no type would throw, so it is handled first.
      warnUnsupportedConstraints(this)
      if (dropsUnionForItsType(this)) {
        logger.warn {
          "Schema declares both a type ($type) and anyOf; Firebase holds one or the other, so the " +
            "union is dropped in favour of the type"
        }
      }
      if (substitutesStringForEnumType(this)) {
        logger.warn {
          "Schema declares an enum on type $type; Firebase describes every enum as a string, so " +
            "the declared type is not what reaches the model"
        }
      }

      val isNullable = nullable == true
      val localAnyOf = anyOf
      val localEnum = enum
      // The same three branches [firebaseBranch] names, in the same order.
      when {
        localAnyOf != null && firebaseBranch() == FirebaseBranch.UNION ->
          FirebaseSchema.anyOf(schemas = localAnyOf.map { toFirebaseSchema(it) })
        localEnum != null ->
          FirebaseSchema.enumeration(
            values = localEnum,
            description = description,
            nullable = isNullable,
            title = title,
          )
        else ->
          when (this.type) {
            // A schema with no declared type constrains nothing, which is the same open shape as
            // an object that declares no properties, so the two convert identically. Either way
            // the properties are the only description there is: with them it is an object, and
            // without them there is nothing for Firebase to hold but the free-form string.
            Type.OBJECT,
            Type.TYPE_UNSPECIFIED,
            null -> {
              val declaredProps = properties
              if (declaredProps.isNullOrEmpty()) {
                freeFormObject(description, isNullable, title)
              } else {
                FirebaseSchema.obj(
                  properties = declaredProps.mapValues { toFirebaseSchema(it.value) },
                  description = description,
                  optionalProperties = optionalParameters(this),
                  nullable = isNullable,
                  title = title,
                )
              }
            }

            Type.ARRAY -> {
              // Firebase has no untyped array, and an array whose items nothing describes is
              // still a list of something, so a string is the least wrong guess available here.
              val nonNullItems = items ?: Schema(type = Type.STRING)
              FirebaseSchema.array(
                items = toFirebaseSchema(nonNullItems),
                description = description,
                nullable = isNullable,
                title = title,
                // Firebase counts items in `Int` where the JSON Schema bound is a `Long`, so a
                // bound past `Int.MAX_VALUE` is clamped rather than silently wrapped negative.
                minItems = minItems?.coerceIn(0, Int.MAX_VALUE.toLong())?.toInt(),
                maxItems = maxItems?.coerceIn(0, Int.MAX_VALUE.toLong())?.toInt(),
              )
            }

            Type.STRING ->
              FirebaseSchema.string(
                description = description,
                nullable = isNullable,
                format = format?.let { StringFormat.Custom(it) },
                title = title,
              )
            Type.INTEGER ->
              FirebaseSchema.long(
                description = description,
                nullable = isNullable,
                title = title,
                minimum = minimum,
                maximum = maximum,
              )
            Type.NUMBER ->
              FirebaseSchema.double(
                description = description,
                nullable = isNullable,
                title = title,
                minimum = minimum,
                maximum = maximum,
              )
            Type.BOOLEAN ->
              FirebaseSchema.boolean(
                description = description,
                nullable = isNullable,
                title = title,
              )
            // Firebase has no null-typed schema and its `nullable` is only a modifier on a real
            // type, so a nullable string is the closest this backend expresses. Handled here so
            // every `Type` a caller can construct converts, rather than only the ones in use today.
            Type.NULL ->
              FirebaseSchema.string(description = description, nullable = true, title = title)
          }
      }
    }

  /**
   * How an object nothing describes is handed to the model: a string carrying JSON.
   *
   * It cannot go out as an object. The backend rejects `{"type":"OBJECT","properties":{}}` with
   * `should be non-empty for OBJECT type` and fails the whole request -- every other tool
   * declaration in it included -- so a local shape that used to throw would become a remote 400. A
   * string is the shape that survives, and it is what [Type.NULL] and an array with no `items`
   * already fall back to. The description tells the model to fill it with JSON, since nothing else
   * now says the value is structured.
   */
  private fun freeFormObject(
    description: String?,
    nullable: Boolean,
    title: String?,
  ): FirebaseSchema =
    FirebaseSchema.string(
      description =
        listOfNotNull(description, "A JSON object, serialized as a string.").joinToString(" "),
      nullable = nullable,
      title = title,
    )

  /**
   * The shape a [Schema] converts to, in the order [toFirebaseSchema] tries them.
   *
   * Deciding it in one place is what keeps the conversion and [unsupportedConstraints] from
   * disagreeing about which fields a schema will actually carry.
   */
  private enum class FirebaseBranch {
    /** An `anyOf` with no type beside it: built from its alternatives alone. */
    UNION,
    /** An `enum`: always a string with `format = "enum"`, whatever type was declared. */
    ENUMERATION,
    /** Everything else: a branch chosen by [Schema.type]. */
    TYPED,
  }

  private fun Schema.firebaseBranch(): FirebaseBranch =
    when {
      anyOf != null && (type == null || type == Type.TYPE_UNSPECIFIED) -> FirebaseBranch.UNION
      enum != null -> FirebaseBranch.ENUMERATION
      else -> FirebaseBranch.TYPED
    }

  /**
   * Whether Firebase drops this schema's `anyOf` because a type is declared beside it.
   *
   * Firebase holds a type or a union, never both, so one of them has to give way and the type wins.
   * Pure so it can be asserted directly -- the logging sink is an Android stub in unit tests, which
   * puts the warning itself out of reach.
   */
  internal fun dropsUnionForItsType(schema: Schema): Boolean =
    schema.anyOf != null && schema.firebaseBranch() != FirebaseBranch.UNION

  /**
   * Whether Firebase describes this schema as a string because it carries an `enum`.
   *
   * `FirebaseSchema.enumeration` always builds a string with `format = "enum"`, so an enum declared
   * on any other type does not reach the model as that type. Pure so it can be asserted directly.
   */
  internal fun substitutesStringForEnumType(schema: Schema): Boolean =
    schema.firebaseBranch() == FirebaseBranch.ENUMERATION &&
      schema.type != null &&
      schema.type != Type.STRING &&
      schema.type != Type.TYPE_UNSPECIFIED

  /**
   * The constraints this schema declares that Firebase will not carry.
   *
   * Which fields survive depends on the branch a schema takes, not just on the field: `format`
   * reaches a string but has no parameter on a number, an enumeration carries only its values, and
   * the numeric and array bounds only exist on their own types. Pure so it can be asserted directly
   * -- the logging sink is an Android stub in unit tests.
   */
  internal fun unsupportedConstraints(schema: Schema): List<String> =
    with(schema) {
      val branch = firebaseBranch()
      val isTyped = branch == FirebaseBranch.TYPED
      buildList {
        // A union is built from its alternatives alone, so nothing it declares about itself lands.
        if (branch == FirebaseBranch.UNION) {
          description?.let { add("description") }
          title?.let { add("title") }
          nullable?.let { add("nullable") }
          enum?.let { add("enum") }
        }
        // Neither a union nor an enumeration is built from the fields that describe a structure:
        // the first is built from its alternatives, the second from its values. A typed schema is
        // the only one that can carry them.
        if (!isTyped) {
          properties?.let { add("properties") }
          items?.let { add("items") }
          required?.let { add("required") }
        }
        default?.let { add("default") }
        // Firebase's object schema has no property-count bounds, so these never survive.
        minProperties?.let { add("minProperties") }
        maxProperties?.let { add("maxProperties") }
        pattern?.let { add("pattern") }
        minLength?.let { add("minLength") }
        maxLength?.let { add("maxLength") }
        // A string branch has a `format` parameter, and an enumeration fixes its own to "enum"; any
        // other spelling is lost.
        val formatSurvives =
          when (branch) {
            FirebaseBranch.UNION -> false
            FirebaseBranch.ENUMERATION -> format == "enum"
            FirebaseBranch.TYPED -> type == Type.STRING
          }
        if (format != null && !formatSurvives) add("format")
        // The numeric bounds only exist on the integer and number branches...
        if (!isTyped || (type != Type.INTEGER && type != Type.NUMBER)) {
          minimum?.let { add("minimum") }
          maximum?.let { add("maximum") }
        }
        // ...and the item bounds only on the array branch.
        if (!isTyped || type != Type.ARRAY) {
          minItems?.let { add("minItems") }
          maxItems?.let { add("maxItems") }
        }
      }
    }

  /** Warns about the constraints Firebase has no field for, so they are not dropped silently. */
  private fun warnUnsupportedConstraints(schema: Schema) {
    val unsupported = unsupportedConstraints(schema).takeIf { it.isNotEmpty() } ?: return
    logger.warn { "Schema constraints are not supported in Firebase: $unsupported" }
  }

  inner class RequestConverter(val request: LlmRequest) {

    fun <T> convert(block: RequestConverter.() -> T): T = block(this)

    fun contents(): List<FirebaseContent> =
      with(request) { contents.map { toFirebaseContent(it) }.filter { it.parts.isNotEmpty() } }

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
