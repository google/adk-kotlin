---
name: adk-kotlin-agent-builder
description: >-
  Builds ADK Kotlin agents: LlmAgent with instructions and tools, KSP-generated @Tool function tools, hand-written BaseTool and FunctionTool subclasses, MCP and agent-as-tool, Sequential / Parallel / Loop workflow agents, LLM-driven transfer between sub-agents, callbacks and plugins, session state and artifacts, long-running and confirmation-gated tools, running with InMemoryRunner, and unit tests with a fake Model. Use when asked to create an agent, add or fix a tool, wire @Tool with KSP, branch or loop between agents, run steps in parallel, pause for user approval, read or write session state, or test an agent. Don't use for explaining runtime internals (use adk-kotlin-architecture) or for build, dependency and CI questions (use adk-kotlin-setup).
---

# ADK Kotlin Agent Builder

Read only the reference that matches the task. Every API in these references was checked against the source under `core/src/commonMain/kotlin/com/google/adk/kt/` in this repository. If a symbol is missing at compile time, read the source rather than guessing a neighbouring name; the Kotlin API is close to adk-python but not identical.

## Start here

| Task | Reference |
|---|---|
| First agent, running it, reading the reply, streaming | [getting-started.md](references/getting-started.md) |
| The rules that cause most runtime failures | [best-practices.md](references/best-practices.md) |

## Building blocks

- [tools.md](references/tools.md) — `@Tool` with KSP (supported shapes, `@Param`, generated names), hand-written `FunctionTool` and `BaseTool`, `ToolContext`, built-in tools, `AgentTool`, MCP, long-running and confirmation-gated tools.
- [workflow-agents.md](references/workflow-agents.md) — `SequentialAgent`, `ParallelAgent`, `LoopAgent`, LLM transfer between sub-agents, custom `BaseAgent` subclasses, `escalate` versus `endInvocation`.
- [callbacks-and-plugins.md](references/callbacks-and-plugins.md) — the eight agent-level callbacks, `CallbackChoice`, and runner-level `Plugin` hooks.
- [state-sessions-artifacts.md](references/state-sessions-artifacts.md) — reading and writing state, the `app:` / `user:` / `temp:` prefixes, `outputKey`, artifacts, memory, session services.

## Verification and interop

- [testing.md](references/testing.md) — a fake `Model`, `DummyTool`, driving an agent through `InMemoryRunner` or a raw `InvocationContext` in `runTest`.
- [java-interop.md](references/java-interop.md) — builders, `AsyncJavaHelpers`, `PublisherRunner`, `ReflectiveTools` for javac-only modules.

## Names that differ from adk-python

| Python | Kotlin |
|---|---|
| `Agent` / `LlmAgent(instruction="...")` | `LlmAgent(instruction = Instruction("..."))` |
| `global_instruction` | none; `staticInstruction: Content?` is the closest and is not templated |
| `FunctionTool(func)` | KSP `@Tool` on the function, then `Service().generatedTools()`; or subclass `FunctionTool` |
| `tool_context.state[k] = v` | `context.actions.stateDelta[k] = v`; read with `context.context.state[k]` |
| `BaseToolset` | `Toolset` |
| `LongRunningFunctionTool` | `isLongRunning = true` on the tool, or `@Tool(isLongRunning = true)` |
| `output_schema=MyModel` | `outputSchema = Schema(type = Type.OBJECT, properties = ...)`, built by hand |
| `SequentialAgent` / `ParallelAgent` / `LoopAgent` | same names, same semantics |
| `Runner.run_async(...)` async generator | `runner.runAsync(...)` returns `Flow<Event>` |
| `event.is_final_response()` | `event.isFinalResponse` property |
| `Workflow`, `BaseNode`, `RequestInput` | not present |
