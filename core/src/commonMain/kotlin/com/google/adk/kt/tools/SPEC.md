# Tools and toolsets

> Internal. Part of the ADK Kotlin specification: the repo-root `SPEC.md` is the
> charter and index, and `DECISIONS.md` holds the `[D-n]` design decisions. Not
> exported to GitHub.

## Tools

*Package `com.google.adk.kt.tools`.*

Tools are the units the model can call. Every tool derives from `BaseTool`,
which defines the model-facing declaration, the `run` entry point, and a default
request hook that registers the tool. Function-backed tools derive from
`FunctionTool` (with a KSP-generated `execute`), and an agent can be wrapped as
a tool via `AgentTool`. The rest of this section covers the shared abstractions,
the tool context types, the `Schema` annotation, and the built-in tools.

### BaseTool

Abstract base for all tools. Defines the model-facing declaration, the `run`
entry point, and the default request hook that registers the tool.

```kotlin
abstract class BaseTool(
  val name: String,
  val description: String,
  val isLongRunning: Boolean = false,
  val customMetadata: Map<String, Any> = emptyMap(),
) : AutoCloseable {

  abstract fun declaration(): FunctionDeclaration?

  abstract suspend fun run(context: ToolContext, args: Map<String, Any>): Any

  open suspend fun processLlmRequest(toolContext: ToolContext, llmRequest: LlmRequest): LlmRequest

  override fun close()

  companion object {
    const val RESULT_KEY = "result"
  }
}
```

**Logic.**

1.  `declaration()` returns the `FunctionDeclaration` exposed to the model, or
    `null` to expose no callable. A null declaration is still registered (see
    `appendTools`), it just contributes no wire tool.
2.  `run(context, args)` executes the tool. The result must be JSON-native
    (Map/List/String/number/Boolean/null). A non-Map result is not wrapped
    inside `run`.
3.  The default `processLlmRequest` = `llmRequest.appendTools(listOf(this))`,
    i.e. a tool just registers itself; overriders (grounding and memory tools)
    add more.
4.  `RESULT_KEY` wrapping happens in `InvocationContext.toFinalResponseMap`, not
    in `run`: a non-Map payload becomes `mapOf(RESULT_KEY to payload)`; a Map is
    passed through, dropping non-String keys and null values.
5.  `isLongRunning` semantics: when true, returning `Unit` means "no response
    yet" (the function-response event is suppressed so the function-call event
    ends the turn); any non-Unit value (even an empty Map) is a real response.

### FunctionTool

Abstract base for function-backed tools. Adds a per-call confirmation gate and
delegates to `execute`; the KSP processor generates `execute` from an annotated
function. `run` is final.

```kotlin
abstract class FunctionTool(
  name: String,
  description: String,
  isLongRunning: Boolean = false,
  customMetadata: Map<String, Any> = emptyMap(),
  // protected: per-call confirmation-gate predicate; default "never".
  protected val requiresConfirmation: (Map<String, Any>) -> Boolean = { false },
) : BaseTool(name, description, isLongRunning, customMetadata) {

  // Boolean convenience secondary constructor (requiresConfirmation has no default here).
  constructor(
    name: String,
    description: String,
    isLongRunning: Boolean = false,
    customMetadata: Map<String, Any> = emptyMap(),
    requiresConfirmation: Boolean,
  )

  abstract suspend fun execute(context: ToolContext, args: Map<String, Any>): Any

  final override suspend fun run(context: ToolContext, args: Map<String, Any>): Any

  companion object {
    const val LONG_RUNNING_OPERATION_NOTE: String =
      "NOTE: This tool performs a long-running operation. ..."
    const val ERROR_KEY: String = "error"
    const val CONFIRMATION_REQUIRED_ERROR: String =
      "This tool call requires confirmation, please approve or reject."
    const val REJECTED_ERROR: String = "This tool call is rejected."
  }
}
```

**Logic.** `run` applies the confirmation gate, then delegates to `execute`:

1.  If `requiresConfirmation(args)` is false (the default), `execute` runs
    directly.
2.  First call, no confirmation yet (`context.toolConfirmation == null`): call
    `context.requestConfirmation(hint = "Please approve or reject the tool call
    <name>() ...")`, set `context.actions.skipSummarization = true`, and return
    `mapOf(ERROR_KEY to CONFIRMATION_REQUIRED_ERROR)` without running `execute`.
3.  Rejected (`!confirmation.confirmed`): return `mapOf(ERROR_KEY to
    REJECTED_ERROR)`; `execute` is not run.
4.  Confirmed: fall through and `return execute(context, args)` - the only path
    that runs the wrapped function.
5.  Long-running: `execute` may return `Unit` to defer; combined with
    `isLongRunning = true`, the framework suppresses the function-response event
    (the defer is handled in `InvocationContext`).

### AgentTool

Wraps a `BaseAgent` as a callable tool, running it as an isolated sub-invocation
and returning its final text.

```kotlin
open class AgentTool(
  val agent: BaseAgent,
  val skipSummarization: Boolean = false,
  val includePlugins: Boolean = true,
  val propagateGroundingMetadata: Boolean = false,
) : BaseTool(name = agent.name, description = agent.description) {

  override fun declaration(): FunctionDeclaration

  override suspend fun run(context: ToolContext, args: Map<String, Any>): String
}
```

**Logic.**

1.  `run` runs `agent` in an isolated `InMemoryRunner` (its own session and
    services), so the sub-agent executes as a separate invocation.
2.  Input: the child session is seeded from the parent's merged session state
    (`context.context.state`), excluding keys under the internal `_adk`
    namespace and `temp:` (invocation-scoped) keys, which must not cross into a
    new session. **Parity:** matches Python ADK, which seeds the child from the
    parent session state.
3.  Artifacts: a `ForwardingArtifactService` makes the child's artifact
    reads/writes resolve against the parent's artifact service.
4.  `includePlugins` (default true) controls whether the parent's plugins run in
    the child invocation.
5.  On completion, the child's last-event `stateDelta` is merged back out into
    the calling `ToolContext.actions.stateDelta`, so the parent invocation
    reflects child state changes.
6.  `propagateGroundingMetadata` (default false): when true, the child's
    grounding metadata is written to the parent under a temp state key.
7.  Result = the last event's non-thought parts joined by newlines (the
    sub-agent's final answer); an empty string when there is no last event.
8.  `declaration()` = the sub-agent's `inputSchema` if defined, else a single
    `request` string parameter.

### ToolContext

Mutable per-call context passed to `run`. Exposes the invocation, event actions,
confirmation state, and artifact read/write.

```kotlin
class ToolContext(
  val invocationContext: InvocationContext,
  val actions: EventActions = EventActions(),
  override val functionCallId: String? = null,
  val toolConfirmation: ToolConfirmation? = null,
  override val eventId: String? = null,
) : ReadonlyToolContext {

  override val context: ReadonlyContext

  fun endInvocation()

  fun requestConfirmation(hint: String? = null, payload: Any? = null)

  override suspend fun listArtifacts(): List<String>

  override suspend fun loadArtifact(name: String, version: Int?): Part?

  suspend fun saveArtifact(name: String, artifact: Part): Int
}
```

### ReadonlyToolContext

Read-only view of the tool context: no mutation, no artifact save.

```kotlin
interface ReadonlyToolContext {

  val context: ReadonlyContext

  val functionCallId: String?

  val eventId: String?

  suspend fun listArtifacts(): List<String>

  suspend fun loadArtifact(name: String, version: Int? = null): Part?
}
```

### Schema

Annotation describing a function or parameter for the KSP-generated tool
declaration (name, description, optional).

```kotlin
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class Schema(
  val name: String = "",
  val description: String = "",
  val optional: Boolean = false,
)
```

### KSP-generated `execute` contract

The KSP processor generates the `FunctionTool.execute` body from an annotated
function. It coerces JSON args to Kotlin types and serializes the result.

**Logic.**

1.  Arg coercion, per parameter: a `ToolContext`-typed param receives the live
    `context` (not read from args); primitives/String coerce from `args[k]`
    (numbers via `Number` to tolerate JSON int/double, strings/enums via `as?`);
    data class, `List`, and `Map` params coerce recursively.
2.  Enum: read `args[k] as? String` then `EnumType.valueOf(it)`; an unknown
    value is caught and returns `mapOf(ERROR_KEY to "Invalid value for enum
    ...")`.
3.  Required = non-nullable with no default. A missing required param returns
    `mapOf(ERROR_KEY to "Missing required parameter <name>")`. Params with
    defaults must be nullable or generation fails.
4.  Result serialization: a `Unit`/null return emits the call then returns
    `Unit` (the framework treats `Unit` as "no response yet" for long-running,
    else coerces to `{}`). Other returns are serialized (data
    classes/enums/lists/nested types to Maps) and returned as
    `mapOf(BaseTool.RESULT_KEY to <serialized>)`, so generated tools pre-wrap
    under `RESULT_KEY`. Unsupported or unserializable types fail at compile
    time.

### GoogleSearchTool

Backend-native Google Search grounding tool. `declaration()` returns null;
grounding is attached in `processLlmRequest`. `model` is deprecated and unused.

```kotlin
class GoogleSearchTool(
  val bypassMultiToolsLimit: Boolean = false,
  @Deprecated(
    "Model-based tool gating has been removed; tool support is verified by the backend. " +
      "This parameter is unused and will be removed in a future release."
  )
  val model: String? = null,
) : BaseTool(name = "google_search", description = "google_search") {

  override fun declaration(): FunctionDeclaration? // returns null

  override suspend fun run(context: ToolContext, args: Map<String, Any>): Any

  override suspend fun processLlmRequest(toolContext: ToolContext, llmRequest: LlmRequest): LlmRequest
}
```

### GoogleMapsTool

Backend-native Google Maps grounding tool. `declaration()` returns null;
grounding is attached in `processLlmRequest`. `model` is deprecated and unused.

```kotlin
class GoogleMapsTool(
  @Deprecated(
    "Model-based tool gating has been removed; tool support is verified by the backend. " +
      "This parameter is unused and will be removed in a future release."
  )
  val model: String? = null,
) : BaseTool(name = "google_maps", description = "google_maps") {

  override fun declaration(): FunctionDeclaration? // returns null

  override suspend fun run(context: ToolContext, args: Map<String, Any>): Any

  override suspend fun processLlmRequest(toolContext: ToolContext, llmRequest: LlmRequest): LlmRequest
}
```

### VertexAiSearchTool

Backend-native Vertex AI Search grounding tool. `init` requires exactly one of
`dataStoreId`/`searchEngineId` (and `dataStoreSpecs` needs `searchEngineId`).
`declaration()` returns null; `model` is deprecated and unused.

```kotlin
class VertexAiSearchTool(
  val dataStoreId: String? = null,
  val dataStoreSpecs: List<VertexAISearchDataStoreSpec>? = null,
  val searchEngineId: String? = null,
  val filter: String? = null,
  val maxResults: Int? = null,
  val bypassMultiToolsLimit: Boolean = false,
  @Deprecated(
    "Model-based tool gating has been removed; tool support is verified by the backend. " +
      "This parameter is unused and will be removed in a future release."
  )
  val model: String? = null,
) : BaseTool(name = "vertex_ai_search", description = "vertex_ai_search") {
  // init: requires exactly one of dataStoreId / searchEngineId; dataStoreSpecs needs searchEngineId.

  override fun declaration(): FunctionDeclaration? // returns null

  override suspend fun run(context: ToolContext, args: Map<String, Any>): Any

  override suspend fun processLlmRequest(toolContext: ToolContext, llmRequest: LlmRequest): LlmRequest
}
```

### UrlContextTool

Backend-native URL-context grounding tool. `declaration()` returns null; the
tool is attached in `processLlmRequest`.

```kotlin
class UrlContextTool : BaseTool(name = "url_context", description = "url_context") {

  override fun declaration(): FunctionDeclaration? // returns null

  override suspend fun run(context: ToolContext, args: Map<String, Any>): Any

  override suspend fun processLlmRequest(toolContext: ToolContext, llmRequest: LlmRequest): LlmRequest
}
```

### LoadArtifactsTool

Lets the model load session artifacts on demand; also injects available-artifact
instructions and attaches requested artifact bytes.

```kotlin
class LoadArtifactsTool :
  BaseTool(
    name = "load_artifacts",
    description = "Loads artifacts into the session for this request.",
  ) {

  override fun declaration(): FunctionDeclaration // non-null

  override suspend fun run(context: ToolContext, args: Map<String, Any>): Any

  override suspend fun processLlmRequest(toolContext: ToolContext, llmRequest: LlmRequest): LlmRequest
}
```

**Logic.**

1.  `declaration()` is an OBJECT schema with `artifact_names: ARRAY<STRING>`
    (not marked required).
2.  `run` reads `artifact_names` and returns `{artifact_names, status}` (a
    status string noting the contents were temporarily inserted); it does not
    load bytes itself.
3.  `processLlmRequest` calls `super` (register the tool), then injects:
    -   Availability: `toolContext.listArtifacts()`; if any exist, append a
        system instruction listing them and telling the model to call
        `load_artifacts` before answering artifact questions.
    -   Byte attach: if the last content is a `load_artifacts` functionResponse,
        for each requested name `loadArtifact` it and append a USER Content
        `[Part("Artifact <name> is:"), artifactPart]`.
    -   `loadArtifact` falls back to `user:<name>` (cross-session) when the
        session-scoped load returns null.

### LoadMemoryTool

Callable memory-search tool (`load_memory`); the model pulls memory by query.

```kotlin
class LoadMemoryTool :
  BaseTool(name = "load_memory", description = "Loads the memory for the current user.") {

  override fun declaration(): FunctionDeclaration // non-null

  override suspend fun run(context: ToolContext, args: Map<String, Any>): Any

  override suspend fun processLlmRequest(toolContext: ToolContext, llmRequest: LlmRequest): LlmRequest
}
```

**Logic.**

1.  `declaration()` is an OBJECT with a required `query: STRING`.
2.  `run` validates `query` and the configured `MemoryService` (error maps
    otherwise), then returns `memoryService.searchMemory(appName, userId,
    query)`; the raw search response is the tool result.
3.  `processLlmRequest` calls `super` (register the tool), then appends a system
    instruction telling the model it has memory and can call `load_memory` with
    a query. Pull model: the model decides when to call.

### PreloadMemoryTool

Auto-injects relevant past-conversation memory into the system instruction each
request. Not model-callable.

```kotlin
class PreloadMemoryTool : BaseTool(name = "preload_memory", description = "preload_memory") {

  override fun declaration(): FunctionDeclaration? // returns null

  override suspend fun run(context: ToolContext, args: Map<String, Any>): Any

  override suspend fun processLlmRequest(toolContext: ToolContext, llmRequest: LlmRequest): LlmRequest
}
```

**Logic.**

1.  `declaration()` returns null (never exposed as a callable), and `run` throws
    `UnsupportedOperationException` - it is not meant to be model-invoked.
2.  `processLlmRequest` runs automatically each request and does NOT call
    `super` (so it never registers itself as a tool):
    -   Extract `query` text from `invocationContext.userContent`; if there is
        no user content, no query text, or no `MemoryService`, return unchanged
        (no-op).
    -   `searchMemory`, then build the memory text (prefixing `Time: <ts>` and
        `<author>: ` when present); if empty, return unchanged.
    -   Append a system instruction wrapping the text in
        `<PAST_CONVERSATIONS>...</PAST_CONVERSATIONS>`.
3.  Push model (auto-inject each request) versus `LoadMemoryTool`'s pull model
    (callable on demand).

### ExitLoopTool

Callable tool that exits the enclosing loop agent.

```kotlin
class ExitLoopTool :
  BaseTool(
    name = "exit_loop",
    description = "Exits the loop.\n\nCall this function only when you are instructed to do so.\n",
  ) {

  override fun declaration(): FunctionDeclaration // non-null

  override suspend fun run(context: ToolContext, args: Map<String, Any>): Any
}
```

### GetUserChoiceTool

Long-running tool (`isLongRunning = true`) that presents options and waits for
the user's choice.

```kotlin
// Long-running built-in tool (isLongRunning = true). Name "get_user_choice".
class GetUserChoiceTool :
  BaseTool(
    name = "get_user_choice",
    description = "Provides the options to the user and asks them to choose one.",
    isLongRunning = true,
  ) {

  override fun declaration(): FunctionDeclaration // non-null

  override suspend fun run(context: ToolContext, args: Map<String, Any>): Any
}
```

### RequestInputTool

Long-running tool (`isLongRunning = true`, call name `adk_request_input`) that
asks the user a question and waits for a response.

```kotlin
// Long-running built-in tool (isLongRunning = true). Call name "adk_request_input".
class RequestInputTool :
  BaseTool(
    name = "adk_request_input",
    description =
      "Ask the user a question and wait for their response. Use this when you need " +
        "clarification or additional information before proceeding.",
    isLongRunning = true,
  ) {

  override fun declaration(): FunctionDeclaration // non-null

  override suspend fun run(context: ToolContext, args: Map<String, Any>): Any
}
```

### Internal (not public API)

These `tools`-package types are `internal`, so they are not part of the public
API and get no frozen listing:

-   `TransferToAgentTool` - internal tool that transfers control to another
    agent.
-   `SetModelResponseTool` - internal tool for the `set_model_response`
    output-schema workaround (used when a model cannot combine a response schema
    with tools).
-   `GoogleSearchAgentTool` (with `createGoogleSearchAgent`,
    `GOOGLE_SEARCH_AGENT_NAME`)
    -   internal wrapper that runs `GoogleSearchTool` as a sub-agent.
-   `VertexAiSearchAgentTool` (with `createVertexAiSearchAgent`,
    `VERTEX_AI_SEARCH_AGENT_NAME`) - internal wrapper that runs
    `VertexAiSearchTool` as a sub-agent.
-   `InternalToolHelpers` - internal object of shared tool helpers.

**Parity note.** When an agent has multiple tools, `GoogleSearchTool` and
`VertexAiSearchTool` are wrapped as `GoogleSearchAgentTool` /
`VertexAiSearchAgentTool` sub-agents rather than attached directly, because the
backend-native search tools cannot be combined with other tools on a single
model call (matching the Java behavior; the `bypassMultiToolsLimit` flag opts
out).

## Toolsets

*Package `com.google.adk.kt.tools`.*

A toolset supplies a dynamic set of tools and may also mutate the request. The
public toolsets are `SkillToolset` (skills) and the JVM-only `McpToolset` (Model
Context Protocol servers). Both implement the `Toolset` interface.

### Toolset

Interface for a dynamic collection of tools that can also mutate the request.

```kotlin
interface Toolset : AutoCloseable {

  suspend fun getTools(readonlyContext: ReadonlyContext? = null): List<BaseTool>

  override fun close()

  suspend fun processLlmRequest(toolContext: ToolContext, llmRequest: LlmRequest): LlmRequest
}
```

**Logic.**

1.  `getTools(readonlyContext)` returns the current tool list; it may depend on
    the context, so it is resolved per invocation.
2.  `processLlmRequest` lets the toolset mutate the request (for example, inject
    an instruction), mirroring `BaseTool.processLlmRequest`.
3.  `close()` releases any resources the toolset holds.

### SkillToolset

Toolset exposing skill discovery/loading tools and injecting an
`<available_skills>` catalog into the system instruction.

```kotlin
// Constructor param `source: SkillSource` is `internal val` (not public API).
class SkillToolset(internal val source: SkillSource) : Toolset {

  override suspend fun getTools(readonlyContext: ReadonlyContext?): List<BaseTool>

  override suspend fun processLlmRequest(toolContext: ToolContext, llmRequest: LlmRequest): LlmRequest

  suspend fun getSkillCatalogInstruction(): String?

  companion object {
    const val TOOL_NAME_LIST_SKILLS = "list_skills"
    const val TOOL_NAME_LOAD_SKILL = "load_skill"
    const val TOOL_NAME_LOAD_SKILL_RESOURCE = "load_skill_resource"
    const val PARAM_SKILL_NAME = "skill_name"
    const val PARAM_PATH = "path"
    const val KEY_ERROR = "error"
    const val KEY_INSTRUCTIONS = "instructions"
    const val KEY_FRONTMATTER = "frontmatter"
    const val KEY_CONTENT = "content"
    const val KEY_STATUS = "status"
    const val MSG_BINARY_FILE = "Binary file detected. Content not shown."
  }
}
```

**Logic.**

1.  `getTools` returns three tools: `list_skills`, `load_skill`, and
    `load_skill_resource`, backed by the internal `ListSkillsTool`,
    `LoadSkillTool`, and `LoadSkillResourceTool` (these three tool types are
    internal, not public API).
2.  `processLlmRequest` injects an `<available_skills>` catalog instruction,
    sourced from `getSkillCatalogInstruction()`, so the model can see which
    skills exist.
3.  The constructor param `source: SkillSource` is `internal` (not public API).

### McpToolset [D-10]

JVM-only toolset that adapts an MCP (Model Context Protocol) server's tools, and
optionally its resources, into `BaseTool`s. Constructed via
`McpToolsetConfig.toToolset(...)`; the primary constructor is internal.

```kotlin
class McpToolset
internal constructor(/* internal - not public API */) : Toolset {

  override suspend fun getTools(readonlyContext: ReadonlyContext?): List<BaseTool>

  suspend fun listResources(readonlyContext: ReadonlyContext? = null): List<String>

  suspend fun readResource(uri: String, readonlyContext: ReadonlyContext? = null): Any

  override fun close()

  // Nested config / factory. File:270
  data class McpToolsetConfig(
    val stdioConnectionParams: McpConnectionParameters.Stdio? = null,
    val sseConnectionParams: McpConnectionParameters.Sse? = null,
    val streamableHttpConnectionParams: McpConnectionParameters.StreamableHttp? = null,
    val toolFilter: List<String>? = null,
    val useMcpResources: Boolean = false,
    val maxMcpResourceLength: Int = 10000,
  ) {
    // Throws IllegalArgumentException unless exactly one connection param is set.
    fun toToolset(
      headerProvider: ((ReadonlyContext) -> Map<String, String>)? = null,
      progressConsumers: List<(McpSchema.ProgressNotification) -> Unit> = emptyList(),
    ): McpToolset
    // NOTE: a second internal overload toToolset(sessionManager, headerProvider) is internal.
  }
}
```

```kotlin
sealed class McpConnectionParameters {

  data class Stdio(
    val serverParameters: ServerParameters,
    val timeoutDuration: Duration = Duration.ofSeconds(5),
  ) : McpConnectionParameters()

  data class Sse(
    val url: String,
    val sseEndpoint: String = "sse",
    val headers: Map<String, String> = emptyMap(),
    val timeout: Duration = Duration.ofSeconds(5),
    val sseReadTimeout: Duration = Duration.ofMinutes(5),
  ) : McpConnectionParameters()

  data class StreamableHttp(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val timeout: Duration = Duration.ofSeconds(5),
    val readTimeout: Duration = Duration.ofMinutes(5),
  ) : McpConnectionParameters()
}
```

```kotlin
class McpTool
internal constructor(/* internal - not public API */) : BaseTool(name, description) {

  override fun declaration(): FunctionDeclaration?

  override suspend fun run(context: ToolContext, args: Map<String, Any>): Any

  val annotations: McpSchema.ToolAnnotations?

  val meta: Map<String, Any>?

  val mcpSessionClient: McpAsyncClient
}
```

```kotlin
sealed class McpToolException(message: String, cause: Throwable? = null) :
  RuntimeException(message, cause) {

  class McpToolDeclarationException(message: String, cause: Throwable? = null) :
    McpToolException(message, cause)

  class McpToolLoadingException(message: String, cause: Throwable? = null) :
    McpToolException(message, cause)

  class McpToolExecutionException(message: String, cause: Throwable? = null) :
    McpToolException(message, cause)
}
```

**Logic.**

1.  JVM-only: `McpToolset`, `McpTool`, `McpConnectionParameters`, and
    `McpToolException` live in `com.google.adk.kt.tools.mcp` (jvmMain).
2.  Construction: build a `McpToolsetConfig`, then call `toToolset(...)`. The
    primary constructor of both `McpToolset` and `McpTool` is `internal`;
    instances are made via the config factory / by the toolset.
3.  `McpToolsetConfig.toToolset` throws `IllegalArgumentException` unless
    exactly one connection param is set (`Stdio`, `Sse`, or `StreamableHttp`).
4.  `getTools` returns the server's tools (filtered by `toolFilter` when set).
    When `useMcpResources = true`, optional resource tools are added, and
    `listResources` / `readResource` expose server resources
    (`maxMcpResourceLength` bounds the content length).
5.  `McpTool.run` invokes the corresponding tool on the MCP server;
    `declaration()` comes from the server's tool schema and may be null.
    Declaration, loading, and execution failures surface as the sealed
    `McpToolException` subtypes.

### Internal (not public API)

These `tools` / `tools.mcp` types are `internal` and are not part of the public
API:

-   `ListSkillsTool`, `LoadSkillTool`, `LoadSkillResourceTool` - back the three
    `SkillToolset` tools.
-   `ListMcpResourcesTool`, `ListMcpResourceTemplatesTool`,
    `LoadMcpResourceTool` - the optional MCP resource tools.
-   `McpTransportBuilder` / `DefaultMcpTransportBuilder` - internal transport
    construction for MCP connections.
-   `SessionManager` / `McpSessionManager` - internal MCP session lifecycle.
-   `McpSchemaConverter` - internal MCP schema helper.
