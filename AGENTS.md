# AGENTS.md

## Project Overview

The Agent Development Kit (ADK) for Kotlin is an open-source, code-first Kotlin toolkit for building and deploying AI agents on the JVM and Android. It mirrors [adk-python](https://github.com/google/adk-python), which is the source of truth for behaviour.

### Key Components

- **Agent** (`BaseAgent`, `LlmAgent`): identity, instructions, tools and sub-agents.
- **Runner** (`Runner`, `InMemoryRunner`): runs one invocation per `runAsync` call and returns a `Flow<Event>`.
- **Tool** (`BaseTool`, `FunctionTool`, `@Tool` via KSP, `Toolset`): capabilities the model can call.
- **Context**: what tools and callbacks receive to read and write state, artifacts and memory.
- **Session** (`SessionService`, `State`): conversation history and state.
- **Model** (`Model`, `Gemini`, and on-device backends): one interface for cloud and local models.
- **Plugins** and **callbacks**: hooks around runs, agents, model calls and tool calls.

Runtime code lives in `core`, under the package `com.google.adk.kt`, one sub-package per concept (`agents`, `runners`, `tools`, `events`, `sessions`, `models`, ...). `settings.gradle.kts` lists the other modules.

## Skills

Skills for ADK Kotlin development are in `.agents/skills/`:

| Skill | Use it for |
| --- | --- |
| [`adk-kotlin-setup`](.agents/skills/adk-kotlin-setup/SKILL.md) | Adding ADK to a project, KSP wiring for `@Tool`, building and testing this repository, source-set placement, PR rules |
| [`adk-kotlin-agent-builder`](.agents/skills/adk-kotlin-agent-builder/SKILL.md) | Writing agents, tools, multi-agent trees, callbacks, state handling and tests; `references/best-practices.md` lists the common failure modes |

The skills record only what should stay true across releases and point at the code for everything else. When a skill and the code disagree, the code is right; fix the skill in the same change.

## Runtime invariants

Keep these when changing the runtime:

- `runAsync` returns a cold `Flow`: nothing runs until it is collected.
- Only non-partial events are persisted. Partial streaming chunks are emitted but never stored.
- State changes travel on events (`EventActions.stateDelta`) and are applied when the session service appends the event. `temp:` keys are applied to the live session and never stored.
- Don't choose a dispatcher on the agent, model or tool path; it inherits the collector's. Always rethrow `CancellationException`.
- Plugin callbacks run before an agent's own callbacks.
- `internal` and `@FrameworkInternalApi` symbols are not public API. `@ExperimentalWorkflowApi` marks the experimental graph workflow in the `workflow` package.
- `@AdkJavaInteropApi` marks builders and bridges for Java callers. Kotlin code uses the constructors with named arguments.
- Public API must compile at the Kotlin language level set by `kotlinCompatVersion` in the root `build.gradle.kts`.

## Development Setup

Build with the Gradle wrapper on JDK 17 or newer (some modules also need JDK 21) with an Android SDK installed. The **`adk-kotlin-setup`** skill has the build and test commands and the single-commit, Conventional-Commit PR policy.
