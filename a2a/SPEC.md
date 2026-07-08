# A2A module

> Internal. Part of the ADK Kotlin specification: the repo-root `SPEC.md` is the
> charter and index, and `DECISIONS.md` holds the `[D-n]` design decisions. Not
> exported to GitHub.

*Module `google-adk-kotlin-a2a`.*

Agent-to-Agent consumer side. It wraps a remote A2A-compliant agent so it
presents as a local `BaseAgent`, using the external `io.a2a` SDK client and
spec.

**Non-goal.** This module does not contain an A2A server and does not expose
local agents over A2A. It is consumer-only; there is no server/exposure side.

## BaseRemoteA2AAgent

Abstract `BaseAgent` for remote A2A agents. Subclasses supply
`isStreamingEnabled` and `createA2aCallbackFlow`; the base handles outbound
event preparation and A2A metadata. It declares a nested
`AgentCardResolutionError` and a protected `EventProcessResult`.

```kotlin
/** Abstract base class for Remote A2A Agents. */
abstract class BaseRemoteA2AAgent(
  name: String,
  override val description: String = "",
  subAgents: List<BaseAgent> = emptyList(),
  beforeAgentCallbacks: List<BeforeAgentCallback> = emptyList(),
  afterAgentCallbacks: List<AfterAgentCallback> = emptyList(),
) : BaseAgent(
  name = name,
  description = description,
  subAgents = subAgents,
  beforeAgentCallbacks = beforeAgentCallbacks,
  afterAgentCallbacks = afterAgentCallbacks,
) {

  /** Returns `true` if this agent supports streaming responses. */
  abstract val isStreamingEnabled: Boolean

  /** Creates the A2A callback flow. */
  protected abstract fun createA2aCallbackFlow(
    context: InvocationContext,
    outboundEvent: Event,
  ): Flow<Event>

  // protected override (BaseAgent.runAsyncImpl is `protected abstract`)
  override fun runAsyncImpl(context: InvocationContext): Flow<Event>

  /** Returns the prepared event to be sent to the remote agent. */
  protected fun prepareOutboundEvent(context: InvocationContext): Event

  /** Adds A2A metadata to the event. */
  protected fun addA2AMetadata(
    event: Event,
    debugRequest: Result<String>? = null,
    debugResponse: Result<String>? = null,
  ): Event

  /** Exception thrown when the agent card cannot be resolved. */
  class AgentCardResolutionError(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

  protected data class EventProcessResult(
    val eventsToEmit: List<Event>,
    val taskId: String?,
    val isTerminal: Boolean?,
    val shouldClose: Boolean,
  )

  // internal companion object A2AMetadata { ... } -> excluded.
}
```

## JvmA2AAgent

Top-level factory that creates a remote A2A agent for JVM/Android from an
`io.a2a` `Client` (and optional `AgentCard`). It is the one public constructor
for a remote A2A agent on these targets and returns an internal
`LegacyA2AAgent`. This is the v0.3 consumer, backed by the legacy `io.a2a` SDK
(`a2a-legacy`, pinned to `0.3.3.Final`).

```kotlin
/** Factory function to create a [BaseRemoteA2AAgent] for JVM/Android. */
fun JvmA2AAgent(
  name: String,
  client: Client,
  agentCard: AgentCard? = null,
  streaming: Boolean = true,
  subAgents: List<BaseAgent> = emptyList(),
  beforeAgentCallbacks: List<BeforeAgentCallback> = emptyList(),
  afterAgentCallbacks: List<AfterAgentCallback> = emptyList(),
): BaseRemoteA2AAgent
```

**Logic.** `runAsyncImpl` builds the outbound event; if it has no parts it emits
a single turn-complete event, otherwise it delegates to `createA2aCallbackFlow`.
`prepareOutboundEvent` finds the last user function-call/response pairing and
threads the A2A task/context IDs into event metadata, or else assembles a USER
event from preprocessed events. `createA2aCallbackFlow` bridges the
non-suspending `io.a2a` Java client into a coroutine `callbackFlow` (backed by
an unlimited channel), converts incoming A2A messages, tasks, and artifacts into
ADK events, and closes the flow when the remote task reaches `COMPLETED`
(`ClientEvent.isCompleted()`). Separately, on teardown the task is cancelled if
its state is terminal (`isFinal`) or `INPUT_REQUIRED`; `INPUT_REQUIRED` on its
own does not close the flow.

**Internal (excluded).** `LegacyA2AAgent` (the `jvmMain` `BaseRemoteA2AAgent`
implementation driving the client), `A2AStreamingResponseAggregator`
(`commonMain`), and the converter objects/functions (`EventA2aSupport.kt` in
`commonMain` and `LegacyA2aConverters.kt` in `jvmMain`, e.g. `toA2aMessage`,
`toAdkEvent`, `MetadataKeys`, `A2AMetadataParser`) are all `internal` and are
not part of the published API.

--------------------------------------------------------------------------------
