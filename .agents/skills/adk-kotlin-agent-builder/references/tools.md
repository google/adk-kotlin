# Tools

## `@Tool` with KSP (preferred)

Annotate a function; the KSP processor generates a `FunctionTool` subclass and a collector. Build wiring is in the setup skill.

```kotlin
import com.google.adk.kt.agents.Context
import com.google.adk.kt.annotations.Param
import com.google.adk.kt.annotations.Tool

class GuideService {
  /** Calculates the improbability of a given event. */
  @Tool
  fun calculateImprobability(
    @Param("The event to calculate the improbability for") event: String,
    @Param("Desired level of improbability (optional)") level: Double? = null,
  ): String = "level ${level ?: 1.0}"

  /** A Context parameter is injected by the framework and hidden from the model. */
  @Tool
  fun submitTeaRequest(context: Context, @Param("The person requesting tea") requester: String): String {
    context.updateState("last_tea_requester", requester)
    return "queued"
  }
}

val agent = LlmAgent(name = "guide", model = model, tools = GuideService().generatedTools())
```

- The description comes from `@Tool(description = ...)` or the KDoc summary, and parameter descriptions from `@Param` or `@param` KDoc tags.
- Use simple types: primitives, `String`, enums, data classes, nullable versions of these, and lists or string-keyed maps of primitives, strings or data classes. Anything else, such as a list of enums, is rejected at compile time with an error naming the type; wrap it in a data class. `FunctionToolGenerator.kt` in the processor is the authority.
- Data classes do not need `@Serializable`.
- A return value reaches the model as `{"result": ...}`; `Unit` becomes `{}`.
- `com.google.adk.kt.annotations.Tool` clashes by name with `com.google.adk.kt.types.Tool`; alias one when both are imported.

## Hand-written tools

Subclass `FunctionTool` (adds the optional confirmation gate) and implement `declaration()` and `execute(...)`, or subclass `BaseTool` and implement `run(...)`. Return JSON-native values (see best-practices.md).

```kotlin
class LookupTool : FunctionTool(name = "lookup", description = "Looks a thing up") {
  override fun declaration() =
    FunctionDeclaration(
      name = name,
      description = description,
      parameters = Schema(type = Type.OBJECT, properties = mapOf("id" to Schema(type = Type.STRING)), required = listOf("id")),
    )

  override suspend fun execute(context: ToolContext, args: Map<String, Any?>): Any {
    val id = args["id"] as? String ?: return mapOf("error" to "Missing required parameter id")
    return mapOf("name" to repo.find(id))
  }
}
```

## What a tool can do with its `Context`

- Read and write state: `context.state[key]`, `context.updateState(key, value)`.
- Save and load artifacts, search or add memory.
- Control flow through `context.actions`: `transferToAgent`, `escalate` (with `skipSummarization`) to leave a loop, and `context.endInvocation()` to stop the current agent.
- Ask the user to approve: `context.requestConfirmation(...)`.

The signatures of `BaseTool.run` and `FunctionTool.execute` still say `ToolContext`, a thin subclass of `Context`; treat it as a `Context`.

## Built-in tools

`core/src/*/kotlin/com/google/adk/kt/tools/` holds the built-ins: search and grounding tools, `ExitLoopTool`, memory and artifact tools, human-input tools, `AgentTool`, and `SkillToolset`. Two to know:

- Provider-side tools such as `GoogleSearchTool` run inside the model API and generally cannot be combined with function tools in one request. Put them in a dedicated sub-agent or `AgentTool`, or use `GoogleSearchTool(bypassMultiToolsLimit = true)`, which does that wrapping for you.
- `transfer_to_agent` is added automatically for agents with transfer targets; you never add it.

## Agent as a tool

`AgentTool(agent = specialist)` lets a model call another agent like a function. The wrapped agent runs in its own session, seeded from the caller's state without `temp:` keys, and shares artifacts.

## MCP (JVM)

Build an `McpToolset` through `McpToolset.McpToolsetConfig(...).toToolset()` and pass it in `toolsets`. Prefer Streamable HTTP connections. Closing the runner closes the MCP session. `examples/.../mcp/` has working setups.

## Long-running tools

Mark the tool long-running (`isLongRunning = true` or `@Tool(isLongRunning = true)`). Returning `Unit` means "no result yet": no response is emitted and the run ends. The client continues later by sending a `FunctionResponse` with the same call `id` in a new `runAsync` (with a resumable `App`, that resumes the original invocation). Returning any value completes the call.

## Confirmation-gated tools

Mark the tool with `@Tool(requireConfirmation = true)` or pass `requiresConfirmation` to the `FunctionTool` constructor. The first call does not run the tool. Instead the runtime emits a synthetic long-running call named `adk_request_confirmation` (`FunctionCall.REQUEST_CONFIRMATION_FUNCTION_CALL_NAME`) with its own `id`. The client replies with a function response of that name, **using the synthetic call's `id`**, and the tool then runs (or is rejected):

```kotlin
val call = events.flatMap { it.functionCalls() }
  .single { it.name == FunctionCall.REQUEST_CONFIRMATION_FUNCTION_CALL_NAME }

val reply = Content(
  role = Role.USER,
  parts = listOf(Part(functionResponse = FunctionResponse(
    name = FunctionCall.REQUEST_CONFIRMATION_FUNCTION_CALL_NAME,
    id = call.id,
    response = mapOf(ToolConfirmation.CONFIRMED_KEY to true),   // false rejects
  ))),
)
runner.runAsync(userId = userId, sessionId = sessionId, newMessage = reply).collect { }
```

`examples/.../hitl/HitlDemoAgent.kt` has the agent side.
