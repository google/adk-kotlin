# Getting started

## Minimal agent

```kotlin
import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.models.Gemini
import com.google.adk.kt.models.VertexCredentials

val rootAgent =
  LlmAgent(
    name = "hello_agent",
    model =
      Gemini(
        name = "<gemini model id>",
        vertexCredentials = VertexCredentials(location = "global"),
      ),
    instruction = Instruction("You greet the user and solve math problems."),
  )
```

`vertexCredentials` runs the model on Vertex AI with Application Default Credentials (`gcloud auth application-default login`) and the project from `GOOGLE_CLOUD_PROJECT`. Without it, `Gemini(name)` calls the Gemini API with `GOOGLE_API_KEY` or `GEMINI_API_KEY`. Prefer ADC because an API key is a long-lived secret. On Android, the GenAI SDK accepts neither API keys nor Google credentials; use the Firebase AI model or an on-device model there. Any `Model` implementation works as `model`.

Beyond `name`, `model` and `instruction`, the `LlmAgent` parameters you will use most are `description` (what a parent's model reads when deciding to transfer), `tools`, `toolsets`, `subAgents`, `outputKey` and `outputSchema`. The constructor in `agents/LlmAgent.kt` has the full list. Always pass arguments by name.

## Instruction placeholders

`{key}` is replaced by `state["key"]`, and `{key?}` becomes empty when the key is missing. A missing `{key}` throws at run time. Anything that is not a valid identifier, such as JSON in the prompt, is left alone. For values that are not in state, pass a provider: `Instruction { ctx -> Content(...) }`. The provider's output still goes through placeholder substitution, so keep `{identifier}` text out of it.

## Running

```kotlin
fun main() = runBlocking {
  InMemoryRunner(agent = rootAgent).use { runner ->
    val events =
      runner
        .runAsync(userId = "u1", sessionId = "s1", newMessage = Content.fromText(Role.USER, "What is 6 times 7?"))
        .toList()
    println(events.filter { it.isFinalResponse }.joinToString("") { it.contentText() })
  }
}
```

- `runAsync` returns a cold `Flow<Event>`; nothing runs until you collect it.
- The returned events are the agents' events. The user's message is stored in the session but not emitted.
- The session is created on first use. To seed state, pass `stateDelta` to `runAsync`.
- The runner is `AutoCloseable`; closing it closes tools, toolsets (MCP sessions) and plugins.
- `runner.run(...)` is a blocking variant for Java and scripts. Never call it from a coroutine.

## Reading events

```kotlin
event.author              // agent name, or "user"
event.isFinalResponse     // the answer, or a paused long-running tool call
event.contentText()       // text, thoughts excluded
event.functionCalls()
event.functionResponses()
event.actions.stateDelta  // state this event wrote
event.partial             // streaming chunk; never stored
```

## Streaming

With `RunConfig(streamingMode = StreamingMode.SSE)` the flow yields partial chunks and then the aggregated final event, so branch on `event.partial` or the answer appears twice.

## Serving over HTTP

The `google-adk-kotlin-webserver` module serves agents over HTTP and hosts the Dev UI. It binds to loopback by default because its endpoints are unauthenticated.
