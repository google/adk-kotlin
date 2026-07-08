# Core data types

> Internal. Part of the ADK Kotlin specification: the repo-root `SPEC.md` is the
> charter and index, and `DECISIONS.md` holds the `[D-n]` design decisions. Not
> exported to GitHub.

*Package `com.google.adk.kt.types`.*

This package defines ADK's own GenAI data model. The types are plain data
classes, enums, and constant objects with trivial logic. They are not
typealiases or re-exports of the Kotlin GenAI SDK; ADK owns its own copies and
bridges them to `com.google.genai.kotlin.types.*` through internal converters
[D-5]. Data-carrying classes that persist through the Event graph are
`@Serializable` via kotlinx serialization [D-6]; free-form `Any` fields carry
`@Contextual` and are resolved by `serialization.AnySerializer`.

## Content model

### Content

A message: a role plus an ordered list of `Part`s.

```kotlin
@Serializable
data class Content(
  val role: String? = null,
  val parts: List<Part> = emptyList(),
) {
  companion object {
    @JvmStatic
    fun fromText(role: String, text: String): Content // ...
  }
}
```

### Part

A single piece of a `Content` holding one of text, inline data, file data,
function call, or function response. The primary constructor is
`@FrameworkInternalApi`; the `opaqueData` parameter is `@Transient` and reserved
for ADK internal use, so it is never persisted and resets to null on
deserialize. Normal callers use the public secondary constructor and the public
`copy` overload.

```kotlin
@Serializable
class Part
@FrameworkInternalApi
constructor(
  val text: String? = null,
  val inlineData: Blob? = null,
  val fileData: FileData? = null,
  val functionCall: FunctionCall? = null,
  val functionResponse: FunctionResponse? = null,
  val thought: Boolean? = null,
  val thoughtSignature: ByteArray? = null,
  val videoMetadata: VideoMetadata? = null,
  val partMetadata: Map<String, @Contextual Any?>? = null,
  @Transient @FrameworkInternalApi val opaqueData: Any? = null,
) {

  // Public secondary constructor (no opaqueData; opaqueData defaults to null):
  constructor(
    text: String? = null,
    inlineData: Blob? = null,
    fileData: FileData? = null,
    functionCall: FunctionCall? = null,
    functionResponse: FunctionResponse? = null,
    thought: Boolean? = null,
    thoughtSignature: ByteArray? = null,
    videoMetadata: VideoMetadata? = null,
    partMetadata: Map<String, Any?>? = null,
  )

  // custom (non-generated) public overrides:
  override fun equals(other: Any?): Boolean // ...
  override fun hashCode(): Int // ...
  override fun toString(): String // ...

  // copy overload that requires opaqueData (framework-internal):
  @FrameworkInternalApi
  fun copy(
    text: String? = this.text,
    inlineData: Blob? = this.inlineData,
    fileData: FileData? = this.fileData,
    functionCall: FunctionCall? = this.functionCall,
    functionResponse: FunctionResponse? = this.functionResponse,
    thought: Boolean? = this.thought,
    thoughtSignature: ByteArray? = this.thoughtSignature,
    videoMetadata: VideoMetadata? = this.videoMetadata,
    partMetadata: Map<String, Any?>? = this.partMetadata,
    opaqueData: Any?,
  ): Part // ...

  // public copy overload (preserves existing opaqueData):
  fun copy(
    text: String? = this.text,
    inlineData: Blob? = this.inlineData,
    fileData: FileData? = this.fileData,
    functionCall: FunctionCall? = this.functionCall,
    functionResponse: FunctionResponse? = this.functionResponse,
    thought: Boolean? = this.thought,
    thoughtSignature: ByteArray? = this.thoughtSignature,
    videoMetadata: VideoMetadata? = this.videoMetadata,
    partMetadata: Map<String, Any?>? = this.partMetadata,
  ): Part // ...

  @FrameworkInternalApi
  fun toStringInternal(): String // ...
}
```

### Blob

Inline binary data with a MIME type.

```kotlin
@Serializable
data class Blob(
  val mimeType: String? = null,
  val displayName: String? = null,
  val data: ByteArray? = null,
) {
  // custom (non-generated) public overrides:
  override fun equals(other: Any?): Boolean // ...
  override fun hashCode(): Int // ...
}
```

### FileData

A reference to file content by URI.

```kotlin
@Serializable
data class FileData(
  val mimeType: String? = null,
  val displayName: String? = null,
  val fileUri: String? = null,
)
```

### VideoMetadata

Video framing metadata (offsets are `kotlin.time.Duration`).

```kotlin
@Serializable
data class VideoMetadata(
  val startOffset: Duration? = null,   // kotlin.time.Duration
  val endOffset: Duration? = null,     // kotlin.time.Duration
  val fps: Double? = null,
)
```

### Role

String constants for message roles.

```kotlin
object Role {
  const val USER = "user"
  const val MODEL = "model"
  const val SYSTEM = "system"
}
```

## Function calling

### FunctionCall

A model request to invoke a named function with arguments.

```kotlin
@Serializable
data class FunctionCall(
  val name: String = "",
  val args: Map<String, @Contextual Any?> = emptyMap(),
  val id: String? = null,
  val partialArgs: List<PartialArg>? = null,
  val willContinue: Boolean? = null,
) {
  companion object {
    const val ADK_FUNCTION_CALL_ID_PREFIX = "adk-"
    const val REQUEST_EUC_FUNCTION_CALL_NAME = "adk_request_credential"
    const val REQUEST_CONFIRMATION_FUNCTION_CALL_NAME = "adk_request_confirmation"
    const val ORIGINAL_FUNCTION_CALL_KEY = "originalFunctionCall"
    const val TOOL_CONFIRMATION_KEY = "toolConfirmation"
    const val NAME_KEY = "name"
    const val ARGS_KEY = "args"
    const val ID_KEY = "id"

    fun generateId(): String // ...  (not @JvmStatic)
  }
}
```

### FunctionResponse

The result returned for a `FunctionCall`.

```kotlin
@Serializable
data class FunctionResponse(
  val name: String,
  val response: Map<String, @Contextual Any?> = emptyMap(),
  val id: String? = null,
)
```

### FunctionDeclaration

A function's name, description, and parameter schema, advertised to the model.

```kotlin
data class FunctionDeclaration(
  val name: String,
  val description: String,
  val parameters: Schema? = null,
)
```

### PartialArgValue

Sealed value type for a streamed partial argument.

```kotlin
@Serializable
sealed interface PartialArgValue {
  @Serializable data class BoolValue(val value: Boolean) : PartialArgValue
  @Serializable data class NumberValue(val value: Double) : PartialArgValue
  @Serializable data class StringValue(val value: String) : PartialArgValue
  @Serializable object NullValue : PartialArgValue
}
```

### PartialArg

A single streamed partial argument keyed by JSON path, with typed convenience
getters.

```kotlin
@Serializable
data class PartialArg(
  val value: PartialArgValue? = null,
  val jsonPath: String? = null,
  val willContinue: Boolean? = null,
) {
  val boolValue: Boolean?      // get() = (value as? PartialArgValue.BoolValue)?.value
  val numberValue: Double?     // get() = (value as? PartialArgValue.NumberValue)?.value
  val stringValue: String?     // get() = (value as? PartialArgValue.StringValue)?.value
  val nullValue: Boolean?      // get() = if (value is PartialArgValue.NullValue) true else null
}
```

## Tool wire model

These are the GenAI tool wire types, distinct from the `annotations.Tool`
marker.

### Tool

A tool offered to the model: function declarations and/or a built-in capability.

```kotlin
data class Tool(
  val functionDeclarations: List<FunctionDeclaration>? = null,
  val googleSearch: GoogleSearch? = null,
  val googleMaps: GoogleMaps? = null,
  val retrieval: Retrieval? = null,
  val urlContext: UrlContext? = null,
)
```

### GoogleSearch

Google Search grounding config.

```kotlin
data class GoogleSearch(val excludeDomains: List<String> = emptyList())
```

### GoogleMaps

Google Maps grounding config.

```kotlin
data class GoogleMaps(val enableWidget: Boolean? = null)
```

### UrlContext

Empty marker enabling URL-context grounding.

```kotlin
class UrlContext
```

### Retrieval

Retrieval-grounding config wrapping a Vertex AI Search source.

```kotlin
data class Retrieval(val vertexAiSearch: VertexAISearch? = null)
```

### VertexAISearch

Vertex AI Search retrieval configuration.

```kotlin
data class VertexAISearch(
  val dataStoreSpecs: List<VertexAISearchDataStoreSpec>? = null,
  val datastore: String? = null,
  val engine: String? = null,
  val filter: String? = null,
  val maxResults: Int? = null,
)
```

### VertexAISearchDataStoreSpec

A single Vertex AI Search data store and filter.

```kotlin
data class VertexAISearchDataStoreSpec(val dataStore: String? = null, val filter: String? = null)
```

### ToolConfig

Wrapper carrying function-calling configuration.

```kotlin
data class ToolConfig(
  val functionCallingConfig: FunctionCallingConfig? = null,
)
```

### FunctionCallingConfig

Restricts which function names the model may call.

```kotlin
data class FunctionCallingConfig(
  val allowedFunctionNames: List<String>? = null
)
```

## Schema

### Type

JSON schema value types.

```kotlin
enum class Type {
  TYPE_UNSPECIFIED,
  STRING,
  NUMBER,
  INTEGER,
  BOOLEAN,
  ARRAY,
  OBJECT,
  NULL,
}
```

### Schema

A subset of the OpenAPI 3.0 schema object, used for parameters and response
schemas.

```kotlin
data class Schema(
  val type: Type? = null,
  val properties: Map<String, Schema>? = null,
  val items: Schema? = null,
  val required: List<String>? = null,
  val description: String? = null,
  val enum: List<String>? = null,
)
```

## Generation config/response

### GenerateContentConfig

Per-request generation configuration.

```kotlin
data class GenerateContentConfig(
  val tools: List<Tool>? = null,
  val labels: Map<String, String>? = null,
  val systemInstruction: Content? = null,
  val temperature: Float? = null,
  val topP: Float? = null,
  val topK: Int? = null,
  val candidateCount: Int? = null,
  val maxOutputTokens: Int? = null,
  val stopSequences: List<String>? = null,
  val responseMimeType: String? = null,
  val responseSchema: Schema? = null,
  val thinkingConfig: ThinkingConfig? = null,
  val toolConfig: ToolConfig? = null,
  val safetySettings: List<SafetySetting>? = null,
  val mediaResolution: MediaResolution? = null,
  val serviceTier: ServiceTier? = null,
  val presencePenalty: Float? = null,
  val frequencyPenalty: Float? = null,
  val responseLogprobs: Boolean? = null,
)
```

### GenerateContentResponse

A model response: candidates plus prompt feedback and usage.

```kotlin
data class GenerateContentResponse(
  val candidates: List<Candidate> = emptyList(),
  val promptFeedback: PromptFeedback? = null,
  val usageMetadata: UsageMetadata? = null,
  val modelVersion: String? = null,
)
```

### Candidate

A single generated candidate with its finish and grounding metadata.

```kotlin
data class Candidate(
  val content: Content,
  val finishReason: FinishReason? = null,
  val finishMessage: String? = null,
  val citationMetadata: CitationMetadata? = null,
  val groundingMetadata: GroundingMetadata? = null,
  val avgLogprobs: Double? = null,
  val logprobsResult: LogprobsResult? = null,
)
```

### PromptFeedback

Why a prompt was blocked, if it was.

```kotlin
data class PromptFeedback(
  val blockReason: BlockedReason? = null,
  val blockReasonMessage: String? = null,
)
```

### UsageMetadata

Token accounting for a response.

```kotlin
@Serializable
data class UsageMetadata(
  val promptTokenCount: Int? = null,
  val candidatesTokenCount: Int? = null,
  val totalTokenCount: Int? = null,
  val thoughtsTokenCount: Int? = null,
  val toolUsePromptTokenCount: Int? = null,
  val cachedContentTokenCount: Int? = null,
  val promptTokensDetails: List<ModalityTokenCount>? = null,
  val candidatesTokensDetails: List<ModalityTokenCount>? = null,
)
```

### ModalityTokenCount

Token count for one media modality.

```kotlin
@Serializable
data class ModalityTokenCount(
  val modality: MediaModality? = null,
  val tokenCount: Int? = null,
)
```

### LlmConstants

String constants for LLM request/response mapping and logging.

```kotlin
object LlmConstants {
  const val INLINE_DATA = "inline_data"
  const val FILE_DATA = "file_data"
  const val KEY_MODEL = "model"
  const val KEY_CONTENTS = "contents"
  const val KEY_CONFIG = "config"
  const val KEY_SYSTEM_INSTRUCTION = "systemInstruction"
}
```

## Enums

### BlockedReason

Why a prompt was blocked.

```kotlin
enum class BlockedReason {
  BLOCKED_REASON_UNSPECIFIED,
  SAFETY,
  OTHER,
  BLOCKLIST,
  PROHIBITED_CONTENT,
  IMAGE_SAFETY,
  MODEL_ARMOR,
  JAILBREAK;
  // internal fun toFinishReason(): FinishReason  -- excluded (internal)
}
```

### FinishReason

Why generation stopped for a candidate. Serialized by an internal serializer
that decodes unknown values to `OTHER`.

```kotlin
@Serializable(with = FinishReasonSerializer::class)
enum class FinishReason {
  FINISH_REASON_UNSPECIFIED,
  STOP,
  MAX_TOKENS,
  SAFETY,
  RECITATION,
  OTHER,
  BLOCKLIST,
  PROHIBITED_CONTENT,
  SPII,
  MALFORMED_FUNCTION_CALL,
  UNEXPECTED_TOOL_CALL,
  LANGUAGE,
  IMAGE_SAFETY,
  IMAGE_PROHIBITED_CONTENT,
  NO_IMAGE,
  IMAGE_RECITATION,
  IMAGE_OTHER,
}
```

### MediaModality

Media modality of a content part.

```kotlin
@Serializable
enum class MediaModality {
  MODALITY_UNSPECIFIED,
  TEXT,
  IMAGE,
  VIDEO,
  AUDIO,
  DOCUMENT,
}
```

### MediaResolution

Requested media resolution.

```kotlin
enum class MediaResolution {
  MEDIA_RESOLUTION_UNSPECIFIED,
  MEDIA_RESOLUTION_LOW,
  MEDIA_RESOLUTION_MEDIUM,
  MEDIA_RESOLUTION_HIGH,
}
```

### ServiceTier

Requested serving tier.

```kotlin
enum class ServiceTier {
  UNSPECIFIED,
  FLEX,
  STANDARD,
  PRIORITY,
}
```

### ThinkingLevel

Requested thinking level (Gemini 3+).

```kotlin
enum class ThinkingLevel {
  THINKING_LEVEL_UNSPECIFIED,
  MINIMAL,
  LOW,
  MEDIUM,
  HIGH,
}
```

### HarmCategory

Safety harm categories.

```kotlin
enum class HarmCategory {
  HARM_CATEGORY_UNSPECIFIED,
  HARM_CATEGORY_HARASSMENT,
  HARM_CATEGORY_HATE_SPEECH,
  HARM_CATEGORY_SEXUALLY_EXPLICIT,
  HARM_CATEGORY_DANGEROUS_CONTENT,
  HARM_CATEGORY_CIVIC_INTEGRITY,
  HARM_CATEGORY_IMAGE_HATE,
  HARM_CATEGORY_IMAGE_DANGEROUS_CONTENT,
  HARM_CATEGORY_IMAGE_HARASSMENT,
  HARM_CATEGORY_IMAGE_SEXUALLY_EXPLICIT,
  HARM_CATEGORY_JAILBREAK,
}
```

### HarmBlockThreshold

Blocking threshold for a harm category.

```kotlin
enum class HarmBlockThreshold {
  HARM_BLOCK_THRESHOLD_UNSPECIFIED,
  BLOCK_LOW_AND_ABOVE,
  BLOCK_MEDIUM_AND_ABOVE,
  BLOCK_ONLY_HIGH,
  BLOCK_NONE,
  OFF,
}
```

### ThinkingConfig

Thinking controls (budget: 0 disables, -1 automatic).

```kotlin
data class ThinkingConfig(
  val includeThoughts: Boolean? = null,
  val thinkingBudget: Int? = null,
  val thinkingLevel: ThinkingLevel? = null,
)
```

### SafetySetting

Threshold for one harm category.

```kotlin
data class SafetySetting(
  val category: HarmCategory? = null,
  val threshold: HarmBlockThreshold? = null,
)
```

## Grounding/citation/logprobs

### Citation

A single citation span.

```kotlin
@Serializable
data class Citation(
  val title: String? = null,
  val uri: String? = null,
  val startIndex: Int? = null,
  val endIndex: Int? = null,
)
```

### CitationMetadata

The set of citations for a candidate.

```kotlin
@Serializable
data class CitationMetadata(
  val citationSources: List<Citation> = emptyList()
)
```

### GroundingMetadata

Grounding chunks, supports, and search metadata for a candidate.

```kotlin
@Serializable
data class GroundingMetadata(
  val imageSearchQueries: List<String> = emptyList(),
  val groundingChunks: List<GroundingChunk>? = null,
  val groundingSupports: List<GroundingSupport>? = null,
  val webSearchQueries: List<String>? = null,
  val searchEntryPoint: SearchEntryPoint? = null,
  val retrievalMetadata: RetrievalMetadata? = null,
)
```

### GroundingChunk

One grounding source: web or retrieved context.

```kotlin
@Serializable
data class GroundingChunk(
  val web: GroundingChunkWeb? = null,
  val retrievedContext: GroundingChunkRetrievedContext? = null,
)
```

### GroundingChunkWeb

A web grounding source.

```kotlin
@Serializable
data class GroundingChunkWeb(
  val uri: String? = null,
  val title: String? = null,
  val domain: String? = null,
)
```

### GroundingChunkRetrievedContext

A retrieved-context grounding source.

```kotlin
@Serializable
data class GroundingChunkRetrievedContext(
  val uri: String? = null,
  val title: String? = null,
  val text: String? = null,
)
```

### GroundingSupport

Links a response segment to its grounding chunks with confidence scores.

```kotlin
@Serializable
data class GroundingSupport(
  val segment: Segment? = null,
  val groundingChunkIndices: List<Int>? = null,
  val confidenceScores: List<Float>? = null,
)
```

### Segment

A span of the response content.

```kotlin
@Serializable
data class Segment(
  val startIndex: Int? = null,
  val endIndex: Int? = null,
  val partIndex: Int? = null,
  val text: String? = null,
)
```

### SearchEntryPoint

Rendered search-suggestion content.

```kotlin
@Serializable
data class SearchEntryPoint(
  val renderedContent: String? = null,
)
```

### RetrievalMetadata

Dynamic-retrieval score for grounding.

```kotlin
@Serializable
data class RetrievalMetadata(
  val googleSearchDynamicRetrievalScore: Float? = null,
)
```

### LogprobsResult

Log-probability result for a candidate.

```kotlin
@Serializable
data class LogprobsResult(
  val chosenCandidates: List<LogprobsResultCandidate>? = null,
  val topCandidates: List<LogprobsResultTopCandidates>? = null,
  val logProbabilitySum: Double? = null,
)
```

### LogprobsResultCandidate

A token with its log probability.

```kotlin
@Serializable
data class LogprobsResultCandidate(
  val token: String? = null,
  val tokenId: Int? = null,
  val logProbability: Double? = null,
)
```

### LogprobsResultTopCandidates

Top candidate tokens for a position.

```kotlin
@Serializable
data class LogprobsResultTopCandidates(
  val candidates: List<LogprobsResultCandidate>? = null
)
```

Internal (not public API): the `EnumConverters.kt`/`GenaiConverters.kt`
extension functions and `FinishReasonSerializer` bridge these types to the GenAI
SDK; they may change without notice.
