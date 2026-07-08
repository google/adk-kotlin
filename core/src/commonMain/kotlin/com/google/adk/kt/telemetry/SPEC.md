# Telemetry

> Internal. Part of the ADK Kotlin specification: the repo-root `SPEC.md` is the
> charter and index, and `DECISIONS.md` holds the `[D-n]` design decisions. Not
> exported to GitHub.

*Package `com.google.adk.kt.telemetry`.*

This package defines a platform-independent tracing abstraction mapped onto the
OpenTelemetry GenAI semantic conventions. The public surface is a small set of
interfaces plus three objects; the concrete tracer implementations are internal.

## Tracer

Creates `SpanBuilder`s and exposes the current propagation context. It is the
entry point to the tracing abstraction.

```kotlin
package com.google.adk.kt.telemetry

/** A Tracer is used to create [SpanBuilder]s for telemetry spans. */
interface Tracer {
  fun spanBuilder(spanName: String): SpanBuilder
  suspend fun currentContext(): TelemetryContext
  fun contextWithSpan(span: Span): TelemetryContext
}
```

## Span

Represents a single operation within a trace. Attribute setters and the event
and exception recorders are `@CanIgnoreReturnValue` so they chain.

```kotlin
package com.google.adk.kt.telemetry

import com.google.errorprone.annotations.CanIgnoreReturnValue

/** A Span represents a single operation within a trace. */
interface Span {
  @CanIgnoreReturnValue operator fun set(key: String, value: String): Span
  @CanIgnoreReturnValue operator fun set(key: String, value: Long): Span
  @CanIgnoreReturnValue operator fun set(key: String, value: Double): Span
  @CanIgnoreReturnValue operator fun set(key: String, value: Boolean): Span
  @CanIgnoreReturnValue operator fun set(key: String, value: List<String>): Span
  @CanIgnoreReturnValue fun addEvent(name: String): Span
  @CanIgnoreReturnValue fun recordException(exception: Throwable): Span
  fun end()
}
```

## SpanBuilder

Configures attributes and a parent context, then starts a `Span`.

```kotlin
package com.google.adk.kt.telemetry

import com.google.errorprone.annotations.CanIgnoreReturnValue

/** A builder for creating and starting a [Span]. */
interface SpanBuilder {
  @CanIgnoreReturnValue operator fun set(key: String, value: String): SpanBuilder
  @CanIgnoreReturnValue operator fun set(key: String, value: Long): SpanBuilder
  @CanIgnoreReturnValue operator fun set(key: String, value: Double): SpanBuilder
  @CanIgnoreReturnValue operator fun set(key: String, value: Boolean): SpanBuilder
  @CanIgnoreReturnValue fun setParent(context: TelemetryContext): SpanBuilder
  fun startSpan(): Span
}
```

## TelemetryContext

A propagation context. It can be turned into a coroutine element or attached and
detached imperatively via a `Scope`.

```kotlin
package com.google.adk.kt.telemetry

interface TelemetryContext {
  fun asContextElement(): TelemetryContextElement
  fun attach(): Scope
  fun detach(scopeToken: Scope)
}
```

## TelemetryContextElement

A `CoroutineContext.Element` that carries a `TelemetryContext` across suspension
points; the companion serves as its context key.

```kotlin
package com.google.adk.kt.telemetry

import kotlin.coroutines.CoroutineContext

interface TelemetryContextElement : CoroutineContext.Element {
  val context: TelemetryContext

  companion object Key : CoroutineContext.Key<TelemetryContextElement>
}
```

## Scope

A context-propagation scope; closing it restores the previously active context.

```kotlin
package com.google.adk.kt.telemetry

/** Represents a scope for context propagation. */
interface Scope {
  fun close()
}
```

## Telemetry

Global entry point for the abstraction. It exposes the active `Tracer`
(defaulting to the platform implementation), per-test overrides, and the current
context.

```kotlin
package com.google.adk.kt.telemetry

/** Global entry point for the Telemetry abstraction. */
object Telemetry {
  /** The active Tracer. Defaults to the platform implementation; overridable per-thread in tests. */
  val tracer: Tracer

  fun setTracerForTest(tracer: Tracer)
  fun resetTracer()
  suspend fun currentContext(): TelemetryContext
}
```

## TelemetryConfig

Global configuration for ADK telemetry behavior. `captureMessageContent` gates
whether prompt and response content is recorded into spans; it defaults to
`false` so message content (which may contain PII) is not captured unless opted
in [D-14].

```kotlin
package com.google.adk.kt.telemetry

/** Global configuration for ADK telemetry behavior. */
object TelemetryConfig {
  @Volatile var captureMessageContent: Boolean
}
```

## TelemetryAttributes

OpenTelemetry semantic attribute keys and common values mapped to the ADK
execution flow. The full public constant listing:

```kotlin
package com.google.adk.kt.telemetry

/** OpenTelemetry semantic attributes mapped to the Google ADK execution flow. */
object TelemetryAttributes {
  // GenAI attributes
  const val GEN_AI_OPERATION_NAME = "gen_ai.operation.name"
  const val GEN_AI_SYSTEM = "gen_ai.system"
  const val GEN_AI_AGENT_NAME = "gen_ai.agent.name"
  const val GEN_AI_AGENT_DESCRIPTION = "gen_ai.agent.description"
  const val GEN_AI_CONVERSATION_ID = "gen_ai.conversation.id"
  const val GEN_AI_TOOL_NAME = "gen_ai.tool.name"
  const val GEN_AI_TOOL_DESCRIPTION = "gen_ai.tool.description"
  const val GEN_AI_TOOL_TYPE = "gen_ai.tool.type"
  const val GEN_AI_TOOL_CALL_ID = "gen_ai.tool.call.id"
  const val GEN_AI_REQUEST_MODEL = "gen_ai.request.model"
  const val GEN_AI_REQUEST_MAX_TOKENS = "gen_ai.request.max_tokens"
  const val GEN_AI_REQUEST_TOP_P = "gen_ai.request.top_p"
  const val GEN_AI_RESPONSE_FINISH_REASONS = "gen_ai.response.finish_reasons"
  const val GEN_AI_USAGE_INPUT_TOKENS = "gen_ai.usage.input_tokens"
  const val GEN_AI_USAGE_OUTPUT_TOKENS = "gen_ai.usage.output_tokens"
  const val GEN_AI_USAGE_CACHE_READ_INPUT_TOKENS = "gen_ai.usage.cache_read.input_tokens"
  const val GEN_AI_USAGE_REASONING_OUTPUT_TOKENS = "gen_ai.usage.reasoning.output_tokens"
  const val GEN_AI_USAGE_REASONING_TOKENS_LIMIT = "gen_ai.usage.experimental.reasoning_tokens_limit"

  // General OpenTelemetry attributes
  const val ERROR_TYPE = "error.type"

  // Common attribute values
  const val SYSTEM_GCP_VERTEX_AGENT = "gcp.vertex.agent"
  const val OPERATION_INVOKE_AGENT = "invoke_agent"
  const val OPERATION_EXECUTE_TOOL = "execute_tool"

  // Custom ADK attributes
  const val GCP_VERTEX_AGENT_INVOCATION_ID = "gcp.vertex.agent.invocation_id"
  const val GCP_VERTEX_AGENT_SESSION_ID = "gcp.vertex.agent.session_id"
  const val GCP_VERTEX_AGENT_EVENT_ID = "gcp.vertex.agent.event_id"
  const val GCP_VERTEX_AGENT_TOOL_CALL_ARGS = "gcp.vertex.agent.tool_call_args"
  const val GCP_VERTEX_AGENT_TOOL_RESPONSE = "gcp.vertex.agent.tool_response"
  const val GCP_VERTEX_AGENT_LLM_REQUEST = "gcp.vertex.agent.llm_request"
  const val GCP_VERTEX_AGENT_LLM_RESPONSE = "gcp.vertex.agent.llm_response"

  // MCP / remote-tool attributes
  const val GCP_MCP_SERVER_DESTINATION_ID = "gcp.mcp.server.destination.id"
}
```

## Notes

-   **Implementations are internal.** The OpenTelemetry-backed tracer (the
    `Otel*` classes) and the `NoOp*` fallbacks are `internal` and not part of
    the public API. Callers use only the interfaces and objects above.
-   **System scope.** ADK spans are emitted under the system scope
    `gcp.vertex.agent` (`SYSTEM_GCP_VERTEX_AGENT`).
-   **Status.** Parent span linking is not yet implemented (b/490424682).

--------------------------------------------------------------------------------
