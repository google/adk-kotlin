# Testing agents

Tests are `kotlin.test` plus `kotlinx.coroutines.test.runTest`. Core keeps
its fakes in `core/src/commonTest/kotlin/com/google/adk/kt/testing/` and the
`testing` module holds shared fixtures (`DummyAgent`, `testSession()`,
`testInvocationContext()`, `testToolContext()`, `userMessage()`,
`modelMessage()`, `modelFunctionCallResponse()`). The `testing` module is not
published, so in your own project copy the two small fakes below.

## A fake model

```kotlin
class DummyModel(
  override val name: String,
  val generateContentFlow: (LlmRequest) -> Flow<LlmResponse> = { emptyFlow() },
) : Model {
  override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> = generateContentFlow(request)

  companion object {
    fun createSequential(name: String, responses: List<LlmResponse>): DummyModel {
      var i = 0
      return DummyModel(name) { flowOf(responses[i++]) }
    }
  }
}
```

Each call to `generateContent` is one model turn, so a tool round-trip needs
two responses: one with the function call, one with the final text.

## A fake tool

```kotlin
class DummyTool(
  name: String = "dummy_tool",
  description: String = "A dummy tool for testing.",
  isLongRunning: Boolean = false,
  val onRun: suspend (ToolContext, Map<String, Any?>) -> Any = { _, _ -> mapOf("status" to "done") },
) : BaseTool(name, description, isLongRunning) {
  override fun declaration(): FunctionDeclaration? = null
  override suspend fun run(context: ToolContext, args: Map<String, Any?>): Any = onRun(context, args)
}
```

## Driving an agent through the runner

```kotlin
@Test
fun toolRoundTrip() = runTest {
  val callTurn = LlmResponse(content = Content(role = Role.MODEL, parts = listOf(Part(functionCall = FunctionCall(name = "my_function", args = mapOf("q" to "x"))))))
  val finalTurn = LlmResponse(content = Content.fromText(Role.MODEL, "done"))
  val model = DummyModel.createSequential("fake", listOf(callTurn, finalTurn))
  val tool = DummyTool("my_function") { _, args -> mapOf("answer" to "42 for ${args["q"]}") }
  val agent = LlmAgent(name = "agent", model = model, tools = listOf(tool))

  val events =
    InMemoryRunner(agent).use { runner ->
      runner.runAsync("u", "s", newMessage = Content.fromText(Role.USER, "hi")).toList()
    }

  assertEquals(listOf("user", "agent", "agent", "agent"), events.map { it.author })
  assertEquals("done", events.last().contentText())
  assertEquals("my_function", events[1].functionCalls().single().name)
  assertEquals("my_function", events[2].functionResponses().single().name)
}
```

The runner path exercises session persistence, plugins and agent selection.
For a narrower unit test, build an `InvocationContext(agent = agent, session
= session)` and collect `agent.runAsync(context)` directly; that skips the
runner and the `user` event.

## Asserting on state and streaming

- State written by a tool shows up as `event.actions.stateDelta` on the
  function-response event and in `runner.sessionService.getSession(key)!!.state`
  afterwards.
- With `RunConfig(streamingMode = StreamingMode.SSE)` a `DummyModel` can emit
  several `LlmResponse(partial = true)` chunks followed by the final one to
  test partial handling.
- To assert an `outputKey`, read `session.state[key]` after collection, not
  the final event's content.

## Testing a `@Tool` function

Generated tools are plain classes, so call `GetWeatherTool(service).run(
testToolContext(), mapOf("city" to "Paris"))` and assert on the returned
map (`{"result": ...}`). Remember that generated sources must be referenced
from a leaf test source set (`jvmTest`, not `commonTest`) in a KMP project.

## Telemetry in tests

`Telemetry.setTracerForTest(tracer)` swaps in a recording tracer per thread;
core's `DummyTracer` shows the shape. Without it, spans go to OpenTelemetry's
no-op and cost nothing.
