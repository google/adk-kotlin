# LiteRT-LM module

> Internal. Part of the ADK Kotlin specification: the repo-root `SPEC.md` is the
> charter and index, and `DECISIONS.md` holds the `[D-n]` design decisions. Not
> exported to GitHub.

*Module `google-adk-kotlin-litertlm`.*

On-device LiteRT-LM (Google AI Edge) LLM exposed as a `Model`. Requires a JDK
21+ toolchain because the litert-lm artifacts are Java 21 class files. It
targets both JVM and Android.

## LiteRtLmModel

`Model` (and `AutoCloseable`) constructed through one of three `create`
overloads. It generates content over the native runtime and releases the engine
on `close()` when it owns it.

```kotlin
/** A [Model] implementation that uses the LiteRT-LM runtime to generate content. */
class LiteRtLmModel
private constructor(
  val engine: LiteRtLmEngine,
  private val ownsEngine: Boolean = false,
  override val name: String = "LiteRtLmModel",
) : Model, AutoCloseable {

  companion object {
    /** Creates a model with a pre-created native [Engine]; caller closes the Engine. */
    fun create(engine: Engine, name: String = "LiteRtLmModel"): LiteRtLmModel

    /** Creates a model with a custom [LiteRtLmEngine] (mainly for testing). */
    fun create(engine: LiteRtLmEngine, name: String = "LiteRtLmModel"): LiteRtLmModel

    /** Creates a model that owns the [Engine]; the Engine is closed on close(). */
    fun create(config: EngineConfig, name: String = "LiteRtLmModel"): LiteRtLmModel
  }

  override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse>

  override fun close()
}
```

**Logic.** Access to the runtime is serialized with a `Mutex`, and the active
conversation is cached keyed by conversation history. Streaming (`stream=true`)
runs through a `callbackFlow` over `conversation.sendMessageAsync`, accumulating
streamed text and emitting partial responses followed by a final non-partial
one; non-streaming uses `conversation.sendMessage`. `ConversationConfig` sets
`automaticToolCalling = false`, so ADK - not the native runtime - executes
tools. On error it emits an `LlmResponse(errorMessage=...)` and discards the
conversation. Media in responses is downgraded to text placeholders (for example
`"[Image Bytes]"`, `"[Audio File: <path>]"`, `"[Tool Response: <name>]"`).

## toLlmResponse

Top-level helper that converts a native `LiteRtLmMessage` into an `LlmResponse`,
rendering image and audio parts as text placeholders.

```kotlin
fun LiteRtLmMessage.toLlmResponse(partial: Boolean = false): LlmResponse
```

## LiteRtLmEngine

Interface wrapping the native LiteRT-LM Engine to enable mockability.

```kotlin
/** Interface wrapping the LiteRT-LM Engine to enable mockability. */
interface LiteRtLmEngine : AutoCloseable {
  fun isInitialized(): Boolean
  fun initialize()
  fun createConversation(config: ConversationConfig): LiteRtLmConversation
}
```

## LiteRtLmConversation

Interface wrapping the native LiteRT-LM Conversation to enable mockability.

```kotlin
/** Interface wrapping the LiteRT-LM Conversation to enable mockability. */
interface LiteRtLmConversation : AutoCloseable {
  fun sendMessage(message: LiteRtLmMessage): LiteRtLmMessage
  fun sendMessageAsync(message: LiteRtLmMessage, callback: MessageCallback)
}
```

## DefaultLiteRtLmEngine

Default `LiteRtLmEngine` delegating to the native `Engine`.

```kotlin
/** Default implementation of [LiteRtLmEngine] delegating to the native [Engine]. */
class DefaultLiteRtLmEngine(private val delegate: Engine) : LiteRtLmEngine {
  override fun isInitialized(): Boolean
  override fun initialize()
  override fun createConversation(config: ConversationConfig): LiteRtLmConversation
  override fun close()
}
```

## DefaultLiteRtLmConversation

Default `LiteRtLmConversation` delegating to the native `Conversation`.

```kotlin
/** Default implementation of [LiteRtLmConversation] delegating to the native [Conversation]. */
class DefaultLiteRtLmConversation(private val delegate: Conversation) : LiteRtLmConversation {
  override fun sendMessage(message: LiteRtLmMessage): LiteRtLmMessage
  override fun sendMessageAsync(message: LiteRtLmMessage, callback: MessageCallback)
  override fun close()
}
```

**Internal (excluded).** `ActiveLiteRtLmConversation` (holds the active
conversation and its history key) and `ManualOpenApiTool` (exposes a tool's JSON
schema; its `execute` throws by design since ADK performs tool calls) are
`internal` and not part of the public API.
