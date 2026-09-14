# Getting started

## Minimal agent

```kotlin
import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.models.Gemini

val rootAgent =
  LlmAgent(
    name = "hello_agent",
    model = Gemini(name = "gemini-3.1-flash-lite"),
    instruction = Instruction("You always greet the user with \"Hello\" and try to solve math problems."),
  )
```

`Gemini(name)` with no `apiKey` reads `GOOGLE_API_KEY` or `GEMINI_API_KEY`
from the environment inside the GenAI SDK. For Vertex AI use
`Gemini(name, vertexCredentials = VertexCredentials(project, location))`.
On Android neither API keys nor `GoogleCredentials` work in the GenAI SDK;
use the Firebase AI model from `google-adk-kotlin-firebase-android` or an
on-device model.

`LlmAgent` parameters you will reach for first:

| Parameter | Type | Notes |
|---|---|---|
| `name` | `String` | letters, digits, `_ - .`, must not be `"user"` |
| `model` | `Model` | `Gemini`, `LiteRtLm`, Firebase, ML Kit, or your own |
| `description` | `String` | what the parent agent's model sees when deciding to transfer |
| `instruction` | `Instruction?` | `Instruction("text")`, `Instruction(content)`, or `Instruction { ctx -> Content? }` |
| `staticInstruction` | `Content?` | verbatim, never templated; setting it moves `instruction` into user content |
| `tools` | `List<BaseTool>` | |
| `toolsets` | `List<Toolset>` | MCP, skills, AppFunctions |
| `subAgents` | `List<BaseAgent>` | enables LLM-driven transfer |
| `outputKey` | `String?` | save the final text to session state |
| `outputSchema` | `Schema?` | top-level `OBJECT` only |
| `includeContents` | `IncludeContents` | `DEFAULT` sends history, `NONE` sends only the current turn |
| `generateContentConfig` | `GenerateContentConfig?` | temperature, safety, thinking config |
| `maxSteps` | `Int?` | cap on model calls per invocation for this agent |

Every `LlmAgent` also has a `Builder` for Java.

## Instruction placeholders

`{key}` is replaced by `session.state["key"]`, and `{key?}` becomes an empty
string when the key is missing. A plain `{key}` that is missing throws
`IllegalArgumentException` at call time. `{artifact.name}` inlines an artifact.
Prefixes `app:`, `user:` and `temp:` are allowed inside the braces. Anything
that is not a valid identifier, such as JSON in the prompt, is left as is.

For values that are not in state, use a provider:

```kotlin
instruction = Instruction { ctx -> Content(parts = listOf(Part(text = "Today is ${today()}."))) }
```

## Running

```kotlin
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Role
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
  InMemoryRunner(agent = rootAgent).use { runner ->
    val events =
      runner
        .runAsync(
          userId = "u1",
          sessionId = "s1",
          newMessage = Content.fromText(Role.USER, "What is 6 times 7?"),
        )
        .toList()
    println(events.filter { it.isFinalResponse }.joinToString("") { it.contentText() })
  }
}
```

- `runAsync` returns a cold `Flow<Event>`; nothing runs until you collect.
- The session is created on first use; there is no separate create call
  needed for `InMemoryRunner`. To seed state, pass `stateDelta` to `runAsync`
  or call `runner.sessionService.createSession(SessionKey(appName, userId,
  sessionId), state)` first.
- `Runner` is `AutoCloseable`. Closing it closes every tool and toolset in
  the tree (MCP sessions in particular) and the plugins.
- `runner.run(userId, sessionId, newMessage)` is the blocking variant for
  Java and scripts. Never call it inside a coroutine.

## Reading events

```kotlin
event.isFinalResponse          // true for the answer, or when a long-running tool paused the turn
event.contentText()            // text parts joined, thoughts excluded
event.functionCalls()          // List<FunctionCall>
event.functionResponses()      // List<FunctionResponse>
event.actions.stateDelta       // what this event wrote to state
event.author                   // agent name or "user"
event.partial                  // streaming chunk, never persisted
```

## Streaming

```kotlin
runner.runAsync(userId, sessionId, newMessage = msg, runConfig = RunConfig(streamingMode = StreamingMode.SSE))
  .collect { event ->
    if (event.partial) print(event.contentText()) else if (event.isFinalResponse) println()
  }
```

In `SSE` mode both the partial chunks and the aggregated final event are
emitted, so always branch on `event.partial` or you will print the answer
twice.

## Serving over HTTP

Add `google-adk-kotlin-webserver` and run
`AdkApiServer(AdkServerConfig.inMemory(rootAgent)).start(wait = true)`, or
`AdkDevServer` for the Dev UI at `/dev-ui`. Both bind loopback because the
endpoints are unauthenticated.
