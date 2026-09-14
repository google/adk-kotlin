---
name: adk-kotlin-architecture
description: >-
  Explains how the ADK Kotlin runtime fits together: Runner and the invocation lifecycle, InvocationContext, the BaseAgent template method, the LlmAgent per-turn processor pipeline, parallel tool execution, Event and EventActions, session state and the temp: prefix, the coroutine model (Flow vs suspend, dispatchers, cancellation, Java bridges), telemetry spans, plugins, and the Kotlin Multiplatform source-set layout with its expect/actual pairs. Use when answering "how does X work" about ADK Kotlin internals, tracing where an event or state value comes from, deciding which source set or package a new capability belongs in, reviewing a change to Runner, BaseAgent, LlmAgent, InvocationContext, Event or SessionService, or judging whether a change is public API. Don't use for assembling an agent from existing pieces (use adk-kotlin-agent-builder) or for build, dependency and test-command questions (use adk-kotlin-setup).
---

# ADK Kotlin Architecture

The runtime is a tree of agents driven by one `Runner`. `Runner.runAsync` returns a cold `Flow<Event>`; collecting it starts the invocation, and every `Event` the tree emits is persisted through the `SessionService` before being handed to the caller. Agents extend `BaseAgent`, which owns callbacks and tracing and delegates to `runAsyncImpl`. `LlmAgent` runs a loop of turns; each turn builds an `LlmRequest` through a fixed list of request processors, calls `Model.generateContent`, and executes any function calls in parallel.

Read the source before relying on a signature here. Notes drift; code does not. Paths are relative to `core/src/`, and all runtime code lives under the package `com.google.adk.kt`.

## What ADK Kotlin does not have

Readers coming from adk-python or adk-java will look for these. None exist:

- No graph `Workflow`, `BaseNode` or `NodeRunner`. Orchestration is `SequentialAgent`, `ParallelAgent`, `LoopAgent` and LLM-driven transfer.
- No `BaseLlmFlow` / `SingleFlow` / `AutoFlow`. The turn pipeline is the internal class `LlmAgentTurn` plus the `processors` package.
- No live or bidirectional streaming. `StreamingMode` is `NONE` or `SSE`.
- No planner, code executor or `LlmRegistry`. The model abstraction is the `Model` interface.
- No `endInvocation()` on `InvocationContext`; it is a `@Volatile var isEndOfInvocation`, set through `CallbackContext.endInvocation()` or `ToolContext.endInvocation()`.
- No JDBC session service. Sessions are in-memory, Vertex AI, or Room on Android.

## Pick a reference

| Question | Reference |
|---|---|
| How does a caller start an invocation, how is the session found, which agent runs first, when are events persisted? | [Runner and invocation](references/runner-and-invocation.md) |
| What happens inside one LLM turn: processors, callbacks, tool execution, output key? | [LlmAgent turn](references/llm-agent-turn.md) |
| Which APIs are `suspend`, which return `Flow`, where are dispatchers used, how do parallel branches cancel, how does Java call in? | [Coroutine model](references/coroutine-model.md) |
| What is on `Event` and `EventActions`, how does `State` apply deltas, what does `temp:` do? | [Events, sessions and state](references/events-sessions-state.md) |
| Which spans exist, and how do plugins hook in? | [Telemetry and plugins](references/telemetry-and-plugins.md) |
| Which source set does a file belong in, and what are the `expect`/`actual` pairs? | [Source layout](references/source-layout.md) |

## Where the code lives

| Concept | File |
|---|---|
| `Runner` interface | `commonMain/.../runners/Runner.kt` |
| `AbstractRunner` (all of the lifecycle) | `commonMain/.../runners/AbstractRunner.kt` |
| `InMemoryRunner` | `commonMain/.../runners/InMemoryRunner.kt` |
| `InvocationContext`, `InvocationCostManager` | `commonMain/.../agents/InvocationContext.kt` |
| `BaseAgent`, `findAgent` extension | `commonMain/.../agents/BaseAgent.kt` |
| `LlmAgent` and its `Builder` | `commonMain/.../agents/LlmAgent.kt` |
| `LlmAgentTurn` (internal per-turn pipeline) | `commonMain/.../agents/LlmAgentTurn.kt` |
| Request processors | `commonMain/.../processors/` |
| `SequentialAgent`, `ParallelAgent`, `LoopAgent` | `commonMain/.../agents/` |
| `RunConfig`, `StreamingMode` | `commonMain/.../agents/RunConfig.kt` |
| `Instruction` | `commonMain/.../agents/Instruction.kt` |
| Callback interfaces, `CallbackChoice`, pipelines | `commonMain/.../callbacks/` |
| `Event`, `EventActions` | `commonMain/.../events/` |
| `Session`, `SessionKey`, `State`, `SessionService` | `commonMain/.../sessions/` |
| `Model`, `Gemini`, `LlmRequest`, `LlmResponse` | `commonMain/.../models/` |
| `BaseTool`, `Toolset`, `ToolContext`, `FunctionTool` | `commonMain/.../tools/` |
| `Plugin`, `PluginManager` | `commonMain/.../plugins/` |
| `App` | `commonMain/.../apps/App.kt` |
| `Tracer`, `Span`, coroutine span helpers | `commonMain/.../telemetry/` |
| OpenTelemetry adapter | `commonJvmAndroidMain/.../telemetry/otel/` |
| Java bridges (`AsyncJavaHelpers`, `PublisherRunner`, `BaseFuture*`) | `commonJvmAndroidMain/.../interop/` |
| MCP toolset | `jvmMain/.../tools/mcp/` |
| Room sessions, AppSearch memory, AppFunctions tools | `androidMain/.../sessions/room/`, `memory/appsearch/`, `tools/appfunctions/` |

## Public API rules of thumb

- Anything `internal` or annotated `@FrameworkInternalApi` can change without a major version. `LlmAgentTurn`, the processors, `InvocationContext.forAgent` and the callback pipelines are internal.
- `@AdkJavaInteropApi` marks builders and bridges that exist for Java callers. Keep them in step with the Kotlin constructor they mirror.
- Artifacts are compiled at Kotlin language level 2.1. A new public API must not require a 2.2+ language feature.
- Behaviour follows adk-python. When Kotlin diverges deliberately (for example, `Event.isFinalResponse` treats a long-running tool call as final, matching Python rather than Java) the KDoc says so; keep it that way.
