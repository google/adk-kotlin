# Calling ADK Kotlin from Java

`examples/java/` is a javac-only module that exists to keep this surface
honest. Patterns that work:

## Constructing agents and runners

Every public data type with defaults has a `Builder`: `LlmAgent`,
`SequentialAgent`, `ParallelAgent`, `LoopAgent`, `InMemoryRunner`, `App`,
`RunConfig`, `Event`, `EventActions`, `Schema`, `FunctionDeclaration`,
`AgentTool`, `GoogleSearchTool`, `McpToolsetConfig`, `LlmRequest`, `Session`,
`AdkServerConfig`.

```java
LlmAgent agent = LlmAgent.builder()
    .name("hello")
    .model(new Gemini("gemini-3.1-flash-lite"))
    .instruction("Greet the user.")
    .build();
InMemoryRunner runner = InMemoryRunner.builder().agent(agent).build();
```

Kotlin default arguments are invisible to Java; when you call a Kotlin
function directly, pass every argument
(`runner.runAsync(user, session, null, message, null, null)`).

## Bridging suspend and Flow

`AsyncJavaHelpers` (static methods):

| Method | Use |
|---|---|
| `await(block)` | run a suspend lambda and block for it |
| `async(scope, block)` | same, as a `CompletableFuture` |
| `collect(flow)` | block and return a `List` |
| `forEach(flow, consumer)` | block and stream |
| `asPublisher(flow)` / `asFlow(publisher)` | Reactive Streams in either direction |

`PublisherRunner.of(runner)` or `PublisherRunner.inMemory(agent)` wraps a
runner so `runAsync` returns a `Publisher<Event>`. Do not call the blocking
helpers from a thread the coroutine machinery needs (an event-loop thread,
a Ktor handler); that deadlocks.

## Implementing ADK interfaces in Java

`BaseFutureTool`, `BaseFutureToolset`, `BaseFutureSessionService`,
`BaseFutureArtifactService`, `BaseFutureMemoryService`, `BaseFuturePlugin`,
`BaseFutureSkillSource`, `BasePublisherAgent` and `BasePublisherModel` expose
`CompletableFuture` and `Publisher` variants of the suspend and Flow methods.
Callback interfaces can be implemented directly; each has a single `call`
method.

## Tools from Java

- If KSP runs on the module (a Kotlin module with Java sources), `@Tool` on a
  Java method works and generates the same classes.
- In a javac-only module use `ReflectiveTools.fromMethod(instance,
  "methodName")`. It needs a non-empty `@Tool(description = ...)` and
  `@Param(name = ...)` on every parameter, because Java bytecode keeps neither
  KDoc nor parameter names. It rejects `isLongRunning` and
  `requireConfirmation`.
- Tool results must be `Map<String, Object>` or another JSON-native value; a
  `null` return is coerced to an empty map.
