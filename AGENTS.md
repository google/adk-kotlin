## Project Overview

The Agent Development Kit (ADK) for Kotlin is an open-source, code-first
Kotlin toolkit for building, evaluating, and deploying AI agents on the JVM
and Android. It mirrors [adk-python](https://github.com/google/adk-python),
which is the source of truth for behaviour.

### Key Components

- **Agent** (`BaseAgent`, `LlmAgent`): identity, instructions, tools and
  sub-agents. `SequentialAgent`, `ParallelAgent` and `LoopAgent` compose them.
- **Runner** (`Runner`, `InMemoryRunner`): starts an invocation, selects the
  agent to run, persists every event, and returns a `Flow<Event>`.
- **Tool** (`BaseTool`, `FunctionTool`, `@Tool` via KSP, `Toolset`, MCP):
  capabilities the model can call, executed in parallel per model turn.
- **Session** (`SessionService`, `State`): conversation history and state with
  `app:`, `user:` and `temp:` scopes.
- **Memory** and **Artifacts**: long-term recall and versioned files.
- **Model** (`Gemini`, LiteRT-LM, Firebase AI, ML Kit): one interface with
  cloud and on-device backends.
- **Plugins** and **callbacks**: hooks around runs, agents, model calls and
  tool calls.

For details on how the Runner works and the invocation lifecycle, refer to
the `adk-kotlin-architecture` skill.

## ADK Knowledge, Architecture, and Style

Skills related to ADK Kotlin development are in `.agents/skills/`:

| Skill | Use it for |
|---|---|
| [`adk-kotlin-setup`](.agents/skills/adk-kotlin-setup/SKILL.md) | Gradle and Maven coordinates, KSP wiring for `@Tool`, Kotlin / JDK / Android version floors, contributor build and test commands, PR rules |
| [`adk-kotlin-architecture`](.agents/skills/adk-kotlin-architecture/SKILL.md) | Runner and invocation lifecycle, `LlmAgent` turn pipeline, coroutine model, events and state, telemetry, source-set layout |
| [`adk-kotlin-agent-builder`](.agents/skills/adk-kotlin-agent-builder/SKILL.md) | Writing agents, tools, workflows, callbacks, state handling and tests; `references/best-practices.md` lists the Kotlin-specific failure modes |

## Project Architecture

For component descriptions, the per-turn processor pipeline, and the rules
for what counts as public API, refer to the **`adk-kotlin-architecture`**
skill at `.agents/skills/adk-kotlin-architecture/SKILL.md`.

The repository is a Gradle multi-project. Module paths are
`:google-adk-kotlin-<dir>`: `core` (Kotlin Multiplatform, JVM and Android),
`processor` (KSP), `testing`, `webserver`, `integrations`, `a2a`, `litertlm`,
`firebase`, `mlkit`, and the `examples` modules. All runtime code lives under
the package `com.google.adk.kt`.

## Development Setup

The project builds with the Gradle wrapper on JDK 17 or newer (JDK 21 is also
required for the `litertlm` and examples modules) and needs an Android SDK.
Refer to the **`adk-kotlin-setup`** skill at
`.agents/skills/adk-kotlin-setup/SKILL.md` for commands, environment
variables and the single-commit, Conventional-Commit PR policy.
