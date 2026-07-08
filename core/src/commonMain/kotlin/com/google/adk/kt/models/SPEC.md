# Models

> Internal. Part of the ADK Kotlin specification: the repo-root `SPEC.md` is the
> charter and index, and `DECISIONS.md` holds the `[D-n]` design decisions. Not
> exported to GitHub.

*Package `com.google.adk.kt.models`.*

The `models` package defines the abstraction over LLM backends (`Model`) and the
request/response value types that flow through it. The reference implementation
is `Gemini`, backed by the GenAI SDK, which serves both Google AI Studio (API
key) and Vertex AI. Common source dir:
`core/src/commonMain/kotlin/com/google/adk/kt/models/`.

## Model

Common interface for interacting with different LLMs. A `Model` exposes a name
and a single content generation entry point.

```kotlin
package com.google.adk.kt.models

import kotlinx.coroutines.flow.Flow

/** Interface that provides a common interface for interacting with different LLMs. */
interface Model {
  /** The name of the model. */
  val name: String

  /**
   * Generates content for the given [LlmRequest]. Returns a [Flow] of [LlmResponse]s.
   * @param request The request containing prompt and config.
   * @param stream Whether to enable streaming mode. If true, partial responses will be emitted.
   */
  fun generateContent(request: LlmRequest, stream: Boolean = false): Flow<LlmResponse>
}
```

**Logic.**

1.  `generateContent` returns a cold `Flow<LlmResponse>`: nothing runs until the
    flow is collected [D-2].
2.  Two internal extensions (not public API) refine model-name handling and
    capability detection [D-13]:
    -   `Model.shortName` extracts the bare model name from a path-based name
        (matches `projects/*/locations/*/publishers/*/models/(.+)` and
        `apigee/...(.+)`; falls back to `name.removePrefix("models/")`).
    -   `Model.canUseOutputSchemaWithTools` is
        `!GEMINI_2_PATTERN.matches(shortName)` where `GEMINI_2_PATTERN =
        ^gemini-2\..*`. Gemini 2.x returns `false` (cannot combine a response
        schema with tools, so ADK falls back to a `set_model_response` tool
        workaround); every other model returns `true`. This deliberately follows
        Java's conservative rule.

## Gemini

Implementation of `Model` backed by the GenAI SDK. The primary constructor's
`client` param is `internal` and `models` is `private`; the two public
constructors (API key / Vertex) are the supported entry points.

```kotlin
package com.google.adk.kt.models

import com.google.genai.kotlin.Client
import com.google.genai.kotlin.types.Content as GenAiContent
import com.google.genai.kotlin.types.GenerateContentResponse as GenAiGenerateContentResponse
import kotlin.jvm.JvmOverloads
import kotlinx.coroutines.flow.Flow

class Gemini(
  internal val client: Client,          // internal property; constructor is public
  override val name: String,
  private val models: GeminiModels = RealGeminiModels(client.models),  // private property
) : Model {

  /** Creates a [Gemini] using a Google AI API key (falls back to env vars when null). */
  @JvmOverloads
  constructor(name: String, apiKey: String? = null)

  /** Creates a [Gemini] using Vertex AI credentials. */
  constructor(name: String, vertexCredentials: VertexCredentials)

  override val name: String

  override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse>

  /** Wrapper around GenAI SDK Models to allow mocking in tests. */
  interface GeminiModels {
    fun generateContentStream(
      model: String,
      contents: List<GenAiContent>,
      config: com.google.genai.kotlin.types.GenerateContentConfig,
    ): Flow<GenAiGenerateContentResponse>

    suspend fun generateContent(
      model: String,
      contents: List<GenAiContent>,
      config: com.google.genai.kotlin.types.GenerateContentConfig,
    ): GenAiGenerateContentResponse
  }

  class RealGeminiModels(private val delegate: com.google.genai.kotlin.Models) : GeminiModels {
    override fun generateContentStream(
      model: String,
      contents: List<GenAiContent>,
      config: com.google.genai.kotlin.types.GenerateContentConfig,
    ): Flow<GenAiGenerateContentResponse>

    override suspend fun generateContent(
      model: String,
      contents: List<GenAiContent>,
      config: com.google.genai.kotlin.types.GenerateContentConfig,
    ): GenAiGenerateContentResponse
  }

  companion object   // all members private (TRACKING_HEADERS, logger, labelsDropWarningLogged) - no public API
}
```

**Logic.**

1.  **Backend selection funnels through one boolean, `client.enterprise`**
    (true = Vertex, false = AI Studio). It is fixed at construction and controls
    only request sanitization and the labels warning; the SDK client itself is
    configured once by the chosen constructor.
    -   **API-key (AI Studio)**: builds `Client(apiKey=...,
        httpOptions=HttpOptions(headers=TRACKING_HEADERS))` with `enterprise =
        false`. A null `apiKey` lets the GenAI SDK fall back to `GOOGLE_API_KEY`
        / `GEMINI_API_KEY` env vars.
    -   **Vertex**: builds `Client(project=..., location=..., credentials=...,
        enterprise = true, httpOptions=HttpOptions(headers=TRACKING_HEADERS))`.
        The `enterprise = true` flag selects the Vertex path.
2.  **Tracking headers** are computed once in the companion and set on
    `HttpOptions.headers` in every constructor: `versionHeaderValue =
    "google-adk/$VERSION gl-kotlin/${KotlinVersion.CURRENT}"`, applied as both
    `x-goog-api-client` and `user-agent`.
3.  **`generateContent(request, stream)`** returns a cold `flow { ... }`. On
    collection:
    1.  **Prepare**: `preparedRequest =
        request.prepareGenerateContentRequest(!client.enterprise)`. The
        `sanitize` arg is `!client.enterprise`, so the AI-Studio path sanitizes
        (true) and the Vertex path does not (false). This is the only place the
        two backends diverge in this method.
    2.  **`prepareGenerateContentRequest(sanitize)`** does: `req = if (sanitize)
        sanitizeForGeminiApi() else this`, then `req.copy(contents =
        req.contents.ensureModelResponse())`. So sanitization is conditional but
        `ensureModelResponse` always runs.
        -   **Sanitization** (`sanitizeForGeminiApi`, AI-Studio only): clears
            `config.labels` to null, and strips `displayName` from each part's
            inline/file data (unsupported by the Gemini API). Empty contents
            means only labels are cleared.
        -   **`ensureModelResponse`** guarantees the last turn is a user turn so
            the model will respond: empty list -> one synthetic user content
            ("Handle the requests as specified in the System Instruction.");
            last role already USER (case-insensitive) -> unchanged; otherwise
            append a synthetic user content ("Continue processing previous
            requests as instructed. ...").
    3.  **Labels warning (Vertex only)**: if `preparedRequest.config.labels` is
        non-empty and the one-shot atomic
        `labelsDropWarningLogged.compareAndSet(false, true)` wins, log a
        one-time warning that labels are unsupported by the Kotlin GenAI SDK and
        will be dropped. This is reachable only on Vertex, because the API-key
        path already cleared labels during sanitization; `toGenaiSdk()` drops
        them regardless (the SDK type lacks the field).
    4.  **Convert to SDK types** (`config.toGenaiSdk()`, `contents.map {
        it.toGenaiSdk() }`), then debug-log a redacted request map
        (text/blob/file payloads reduced to sizes and names; logging only, does
        not affect the wire request).
    5.  **Branch on `stream`:**
        -   **Streaming**: create one `StreamingResponseAggregator`; collect the
            SDK `generateContentStream` flow; for each chunk
            `emit(aggregator.processResponse(...))` marked `partial = true`;
            after the loop `aggregate()?.let { emit(it) }` emits the final
            non-partial merged response (or nothing if `aggregate()` returns
            null).
        -   **Non-streaming**: `models.generateContent(...)` (suspend), then a
            single `emit(LlmResponse.from(response))`.
4.  **Error mapping** is delegated to `LlmResponse.from` (non-streaming) and to
    the aggregator (streaming). Both use the same convention: a non-STOP finish
    reason becomes `errorCode = reason.name`.
5.  The flow has no explicit try/catch: SDK exceptions propagate out of the flow
    to the caller.

**Status.** On Vertex, `config.labels` are unsupported and silently dropped by
the Kotlin GenAI SDK; a one-time warning is logged per process the first time
non-empty labels are seen.

## LlmRequest

Immutable request to an LLM: the model, the conversation contents, and
generation config. The `with*` style is provided by copy-returning `append*`
methods.

```kotlin
package com.google.adk.kt.models

import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.GenerateContentConfig

/**
 * LlmRequest represents a request to an LLM.
 * @property model The model to use for the request.
 * @property contents The contents of the request.
 * @property config The configuration for generating content.
 */
data class LlmRequest(
  val model: Model? = null,
  val contents: List<Content> = emptyList(),
  val config: GenerateContentConfig = GenerateContentConfig(),
  internal val toolsDict: List<BaseTool> = emptyList(),   // internal - excluded from public surface
) {
  /** Appends tools to the request and merges any new function declarations. */
  fun appendTools(tools: List<BaseTool>): LlmRequest

  /** Appends instructions to the system instruction (extracts text; non-text parts become user contents). */
  fun appendInstructions(instructions: Content): LlmRequest

  /** Appends a content block to the request. */
  fun appendContent(content: Content): LlmRequest

  // data class auto-generated: copy(...), componentN(), equals/hashCode/toString
}
```

**Logic.**

1.  **`appendTools(tools)`** consolidates every tool's declaration into a single
    function-declaration `Tool`:
    1.  Empty input -> return `this` unchanged.
    2.  `newFunctionDeclarations = tools.mapNotNull { it.declaration() }` (tools
        whose `declaration()` is null contribute nothing).
    3.  If no declarations were produced, only record the tools in `toolsDict`
        (`copy(toolsDict = toolsDict + tools)`) and return. This is how a
        null-declaration tool still gets registered without adding a wire tool.
    4.  Otherwise find the existing `config.tools` entry that already has
        `functionDeclarations != null`: not found -> add a new
        `Tool(functionDeclarations = newFunctionDeclarations)`; found -> replace
        it with `existingTool.copy(functionDeclarations = existing + new)`. All
        function declarations end up in one `Tool`.
    5.  Return a copy with the updated `config.tools` and `toolsDict =
        toolsDict + tools`.
2.  **`appendInstructions(instructions)`** builds the system instruction as a
    string and turns non-text parts into placeholders plus separate user
    contents (the API requires `system_instruction` to be a string):
    1.  For each part: text is added raw; inline/file data become a `PartInfo`
        with a generated id (`<dataType>_<n>`), whose reference text
        `"[Reference to <dataType>: <id>(<displayInfo>)]"` is added to the text
        section, and whose expansion (a user Content pairing a "Referenced ...:
        id" text part with the binary/file part) is appended to `userContents`.
    2.  Merge the joined text (`textParts.joinToString("\n\n")`) into
        `config.systemInstruction`: no existing instruction -> a new single-text
        Content; existing instruction -> append a text part, prefixing a
        blank-line separator only when prior parts exist. The text merge is
        skipped when there is no text.
    3.  Return a copy with the new `systemInstruction` and `contents =
        contents + userContents`.
3.  **`appendContent(content)`** is a simple append: `copy(contents = contents +
    content)`.

## LlmResponse

Immutable response value produced from a GenAI SDK `GenerateContentResponse`.
Carries content plus metadata, finish reason, and error fields.

```kotlin
package com.google.adk.kt.models

import com.google.adk.kt.types.CitationMetadata
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FinishReason
import com.google.adk.kt.types.GenerateContentResponse
import com.google.adk.kt.types.GroundingMetadata
import com.google.adk.kt.types.LogprobsResult
import com.google.adk.kt.types.UsageMetadata

data class LlmResponse(
  val content: Content? = null,
  val usageMetadata: UsageMetadata? = null,
  val finishReason: FinishReason? = null,
  val errorMessage: String? = null,
  val partial: Boolean = false,
  val interrupted: Boolean = false,
  val modelVersion: String? = null,
  val citationMetadata: CitationMetadata? = null,
  val groundingMetadata: GroundingMetadata? = null,
  val errorCode: String? = null,
  val customMetadata: Map<String, Any?>? = null,
  val avgLogprobs: Double? = null,
  val logprobsResult: LogprobsResult? = null,
) {
  companion object {
    /** Creates an [LlmResponse] from a [GenerateContentResponse]. */
    fun from(response: GenerateContentResponse): LlmResponse
  }
  // data class auto-generated: copy(...), componentN(), equals/hashCode/toString
}
```

**Logic.** `LlmResponse.from(response)`:

1.  Use only the first candidate: `candidate =
    response.candidates.firstOrNull()`; extra candidates are dropped.
2.  Resolve the finish reason with a fallback: `finishReason =
    candidate?.finishReason ?:
    response.promptFeedback?.blockReason?.toFinishReason()` - a prompt-level
    block reason is mapped to a `FinishReason` when the candidate has none.
3.  Map to error fields with the STOP convention:
    -   `errorCode = finishReason?.takeIf { it != STOP }?.name` - null when STOP
        or when `finishReason` is null; any other finish reason becomes the
        error code.
    -   `errorMessage`: null if `finishReason == STOP`; otherwise
        `candidate?.finishMessage ?: response.promptFeedback?.blockReasonMessage
        ?: "Unknown error."`.
4.  Copy `content` (from the candidate), `usageMetadata`, `modelVersion`,
    `citationMetadata`, `groundingMetadata`, `avgLogprobs`, and `logprobsResult`
    from the candidate/response.

Edge cases: no candidate and no block reason yields `finishReason=null,
errorCode=null` but `errorMessage="Unknown error."` (the else branch runs
because null != STOP); a normal STOP candidate yields `errorCode=null,
errorMessage=null`.

## VertexCredentials

Config for authenticating to Vertex AI when constructing a `Gemini` model.

```kotlin
package com.google.adk.kt.models

/** Config for authenticating to Vertex AI when constructing a [Gemini] model. */
data class VertexCredentials(
  val project: String? = null,       // Google Cloud project.
  val location: String? = null,      // Google Cloud project location.
  val credentials: GoogleCredentials? = null,  // defaults to application-default when null.
)
```

## GoogleCredentials

ADK-owned handle to Google Cloud credentials for Vertex AI (see
`VertexCredentials`). Declared as an `expect` type in common code; on JVM and
Android it is a `typealias` for the Java Auth Library's `GoogleCredentials`. The
`toGenaiSdk()` bridge is `internal` and excluded from the public surface.

```kotlin
// commonMain
package com.google.adk.kt.models

/**
 * ADK-owned handle to Google Cloud credentials for Vertex AI (see [VertexCredentials]).
 * On JVM and Android it is a `typealias` for `com.google.auth.oauth2.GoogleCredentials`.
 */
expect class GoogleCredentials
```

```kotlin
// commonJvmAndroidMain
package com.google.adk.kt.models

import com.google.auth.oauth2.GoogleCredentials as JavaGoogleCredentials

/** On JVM and Android, ADK credentials are the Java Auth Library's `GoogleCredentials`. */
actual typealias GoogleCredentials = JavaGoogleCredentials
```

## GenaiPrompt

A `Model` implementation for `androidMain` that uses the ML Kit GenAI API to
generate content. The primary constructor is `private`; instances are created
via the `create` factory.

```kotlin
package com.google.adk.kt.models.mlkit

import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.Model
import com.google.mlkit.genai.prompt.GenerativeModel
import kotlinx.coroutines.flow.Flow

/** A [Model] implementation that uses the ML Kit GenAI API to generate content. */
class GenaiPrompt private constructor(
  val generativeModel: GenerativeModel,
  override val name: String,
) : Model {

  override val name: String

  override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse>

  companion object {
    val logger

    /** Creates a [GenaiPrompt] with the given [generativeModel] and [name]. */
    fun create(generativeModel: GenerativeModel, name: String = "GenaiPrompt"): GenaiPrompt
  }
}
```

## Internal architecture (not public API)

**StreamingResponseAggregator** is an `internal class` (no public members; the
only public entry is `Gemini.generateContent`). It merges a stream of partial
`LlmResponse`s into one final response. The following describes current behavior
and may change without notice.

Merge algorithm:

1.  **Mutex-guarded.** `processResponse` and `aggregate` both run under a
    `Mutex`, so concurrent stream collection is safe. State accumulates
    latest-wins metadata (usage, grounding, citation, finishReason), an ordered
    `partsSequence`, a text buffer, and a function-call buffer.
2.  **Per chunk (`processResponse`):** store the raw chunk, accumulate any
    non-null metadata, then for each part route it: text -> the text buffer;
    function call -> the function-call handler; anything else -> flush the text
    buffer and append the part directly. Each per-chunk emission is returned
    with `partial = true`.
3.  **Text buffering flushes on thought flips.** When a text part's `thought`
    flag differs from the buffered text's flag, the buffer is flushed to its own
    `Part` before appending, so thought text and normal text never merge into
    one part. Flushing emits `Part(text=buffer, thought=currentTextIsThought)`
    and resets the buffer.
4.  **Streamed function-call args concatenate by JSON path.** A function call is
    treated as streaming when it has `partialArgs` or `willContinue == true`.
    Name and id may arrive in any chunk. Each partial arg is placed at its
    `jsonPath` (leading `$.` stripped, split on `.`), creating nested maps on
    demand; string fragments are **concatenated** onto any existing string at
    that path, while number/bool/null values are set as-is. The first non-null
    `thoughtSignature` seen is kept.
5.  **Finish handling.** When a function call's stream ends (`willContinue !=
    true`), the text buffer and the function-call buffer are flushed into
    `partsSequence`. A non-streaming function call with a non-empty name flushes
    the text buffer and appends the part as-is (empty-name non-streaming calls
    are ignored).
6.  **`aggregate()` returns null if no chunk.** It returns null when no chunk
    was ever processed, when the last chunk has no first candidate, or when
    `partsSequence` is empty after flushing. Otherwise it builds the final
    `LlmResponse(role=MODEL, parts=partsSequence, partial=false)` with
    accumulated metadata, `finishReason = accumulated ?:
    candidate.finishReason`, and the same STOP-vs-error mapping as
    `LlmResponse.from` (`errorCode = finishReason.takeIf { it != STOP }?.name`,
    `errorMessage = if (STOP) null else candidate.finishMessage`).
