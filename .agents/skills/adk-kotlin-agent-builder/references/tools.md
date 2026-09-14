# Tools

## Option 1: `@Tool` with KSP (preferred)

Annotate a function; the processor generates a `FunctionTool` subclass and a
collector function. Nothing is reflective at runtime.

```kotlin
import com.google.adk.kt.annotations.Param
import com.google.adk.kt.annotations.Tool
import com.google.adk.kt.tools.ToolContext

enum class TeaStatus { HOT, COLD, NOT_AVAILABLE }

data class Coordinates(val x: Double, val y: Double, val z: Double)

data class ImprobabilityReport(val locationName: String, val level: Double, val sideEffects: List<String>)

class HitchhikersGuideService {

  /** Calculates the improbability of a given event. */
  @Tool
  fun calculateImprobability(
    @Param("The event to calculate the improbability for") event: String,
    @Param("Desired level of improbability (optional)") level: Double? = 1.0,
  ): String = "..."

  /** Gets the drive status at given coordinates. Suspend functions and data classes are fine. */
  @Tool
  suspend fun getDriveStatus(@Param("Galactic coordinates") coordinates: Coordinates): ImprobabilityReport = ...

  /** A ToolContext parameter is injected and hidden from the model. */
  @Tool
  fun submitTeaRequest(context: ToolContext, @Param("The person requesting tea") requester: String, status: TeaStatus): String = ...

  /**
   * KDoc is enough; @Param is optional.
   *
   * @param entryName The name of the entry
   * @param edition The edition of the guide
   */
  @Tool
  fun getHistoricalGuideEntry(entryName: String, edition: String): String = ...
}

val agent = LlmAgent(name = "guide", model = model, tools = HitchhikersGuideService().generatedTools())
```

### What gets generated

| Source | Generated |
|---|---|
| `fun getWeather(...)` in class `Svc` | `class GetWeatherTool(instance: Svc) : FunctionTool`, same package |
| top-level `fun getWeather(...)` in `Weather.kt` | `class GetWeatherTool() : FunctionTool` |
| all `@Tool` members of `Svc` | extension `fun Svc.generatedTools(): List<FunctionTool>` |
| all top-level `@Tool`s in `Weather.kt` | `fun getWeatherGeneratedTools(): List<FunctionTool>` |

`@Tool(name, description, requireConfirmation, isLongRunning)`: `name`
defaults to the function name; `description` defaults to the KDoc summary
(everything before the first `@` tag), then to `"Function <name>"`.
`@Param(description, name, required)` with `Requiredness.AUTO | REQUIRED |
OPTIONAL`; the description falls back to the `@param` KDoc tag.

Annotation import is `com.google.adk.kt.annotations.Tool`, which clashes by
simple name with `com.google.adk.kt.types.Tool`. Alias one when both are in
scope.

### Supported shapes

- Top-level functions, class members, `object` members. `suspend` or not.
- Parameters: `String`, `Int`, `Double`, `Float`, `Boolean`, enums, data
  classes (nested), `List<...>` and `Map<String, ...>` of those, nullable
  variants, plus one `ToolContext`.
- Returns: `Unit`, primitives, `String`, enums, data classes, `List<T>`,
  `Map<String, T>`, `List<Any>`, `Map<String, Any>`, nullable elements.
- Data classes do **not** need `@Serializable`; the generator writes explicit
  conversion code.

Rejected with a compile-time error: a `Flow` return type, a default value on
a non-nullable parameter (`Default arguments must be nullable`),
`@Param(required = OPTIONAL)` on a non-null no-default parameter, `Long`,
`LocalDate` or other unsupported types, recursive data classes, duplicate or
blank `@Param(name)` values.

Every return value reaches the model wrapped as `{"result": ...}`. Missing or
invalid arguments become an `{"error": "..."}` response rather than an
exception.

Build wiring is in the setup skill: apply the KSP plugin, put the processor on
`ksp(...)` (or `kspJvm` / `kspAndroid` in KMP), and reference generated tools
only from leaf source sets.

## Option 2: hand-written `FunctionTool`

```kotlin
class LookupTool : FunctionTool(name = "lookup", description = "Looks a thing up") {
  override fun declaration() =
    FunctionDeclaration(
      name = name,
      description = description,
      parameters = Schema(type = Type.OBJECT, properties = mapOf("id" to Schema(type = Type.STRING)), required = listOf("id")),
    )

  override suspend fun execute(context: ToolContext, args: Map<String, Any?>): Any {
    val id = args["id"] as? String ?: return mapOf(ERROR_KEY to "Missing required parameter id")
    return mapOf("name" to repo.find(id))   // JSON-native values only
  }
}
```

`FunctionTool` adds the confirmation gate on top of `BaseTool`; if you need
neither confirmation nor the `execute` split, extend `BaseTool` directly and
override `run`. Either way the result must be JSON-native: `Map`, `List`,
`String`, number, `Boolean`, `null`. A data class returned from a hand-written
tool compiles and then fails when the event is persisted.

## `ToolContext`

```kotlin
class ToolContext(
  val invocationContext: InvocationContext,
  val actions: EventActions = EventActions(),
  override val functionCallId: String? = null,
  val toolConfirmation: ToolConfirmation? = null,
  override val eventId: String? = null,
)
```

| Need | Call |
|---|---|
| read state | `context.context.state["key"]` |
| write state | `context.actions.stateDelta["key"] = value` |
| remove state | `context.actions.removeStateByKey("key")` |
| stop the agent's step loop | `context.endInvocation()` |
| break out of a `LoopAgent` | `context.actions.escalate = true` |
| hand off | `context.actions.transferToAgent = "other_agent"` |
| artifacts | `saveArtifact(name, part)`, `loadArtifact(name, version)`, `listArtifacts()` |
| memory | `context.invocationContext.memoryService?.searchMemory(appName, userId, query)` |
| ask the user to approve | `context.requestConfirmation(hint, payload)` |

There is no `context.state` setter and no `searchMemory` on the context
itself.

## Built-in tools

| Tool | Name seen by the model | Notes |
|---|---|---|
| `GoogleSearchTool(bypassMultiToolsLimit = false)` | `google_search` | provider-side grounding; cannot be mixed with function tools unless bypass is on, which swaps it for an agent-tool |
| `VertexAiSearchTool`, `VertexAiRagRetrieval`, `UrlContextTool`, `GoogleMapsTool` | | provider-side tools |
| `ExitLoopTool()` | `exit_loop` | sets `escalate` and `skipSummarization` |
| `LoadMemoryTool()`, `PreloadMemoryTool()` | `load_memory` | need a `MemoryService` on the runner |
| `LoadArtifactsTool()` | `load_artifacts` | injects artifact content on the next turn |
| `RequestInputTool()`, `GetUserChoiceTool()` | `adk_request_input`, ... | long-running; pause for a human |
| `TransferToAgentTool` | `transfer_to_agent` | added automatically when `subAgents` exist |
| `SkillToolset(source)` | | loads agent skills from a `SkillSource` |
| `AppFunctionsToolset(context)` (Android) | | requires the app to depend on `androidx.appfunctions` itself |

## Agent as a tool

```kotlin
AgentTool(agent = specialist, skipSummarization = false, includePlugins = true)
```

The wrapped agent runs in its own `InMemoryRunner` and session, seeded from
the parent's state minus `temp:` and `_adk` keys, sharing artifacts through a
forwarding service. Its input schema is the wrapped `LlmAgent`'s
`inputSchema`, else a single required `request` string.

## MCP

JVM only. The `McpToolset` constructor is internal; build through the config:

```kotlin
val toolset =
  McpToolset.McpToolsetConfig(
      streamableHttpConnectionParams = McpConnectionParameters.StreamableHttp(url = "https://...", headers = mapOf("X-Goog-Api-Key" to key)),
      toolFilter = ToolFilter.allowList("search", "fetch"),
    )
    .toToolset()

LlmAgent(name = "mcp_agent", model = model, toolsets = listOf(toolset))
```

Exactly one of `stdioConnectionParams`, `sseConnectionParams`,
`streamableHttpConnectionParams` must be set. Prefer Streamable HTTP over SSE.
A non-null `headerProvider` disables session caching, so the tool list is
fetched on every invocation. Close the runner (or the toolset) to tear down
the MCP session.

## Long-running tools

Mark the tool `isLongRunning = true` (or `@Tool(isLongRunning = true)`).
Returning `Unit` means "no result yet": no function response is emitted, the
event is final, and the invocation pauses until the client sends a
`FunctionResponse` with the same `id` in a later `runAsync`. Returning any
value, even an empty map, is a real response and does not pause. Pausing and
resuming across processes needs `App(resumabilityConfig =
ResumabilityConfig(isResumable = true))`.

## Confirmation-gated tools

`@Tool(requireConfirmation = true)`, or `FunctionTool(requiresConfirmation =
true)`, or a predicate `requiresConfirmation = { args -> ... }`. The first
call returns `{"error": "This tool call requires confirmation, please
approve or reject."}` and records a `ToolConfirmation` in
`event.actions.requestedToolConfirmations`. The client answers with a
function response carrying the confirmation, after which the tool executes.
`examples/src/main/kotlin/com/google/adk/kt/examples/hitl/HitlDemoAgent.kt`
shows the full loop.

## Filtering tools

`ToolFilter.AllowList(setOf("a", "b"))`, `ToolFilter.allowList("a", "b")`, or
`ToolFilter.Predicate(ToolPredicate { tool, ctx -> ... })`. Toolsets accept
a filter; `Toolset.getTools(readonlyContext)` can also vary per invocation.
