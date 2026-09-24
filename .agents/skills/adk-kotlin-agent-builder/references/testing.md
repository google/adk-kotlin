# Testing agents

Tests use `kotlin.test` and `runBlocking` (this repository prefers `runBlocking` over `runTest` in new tests). Core's own fakes are in `core/src/commonTest/.../testing/`, and the unpublished `testing` module has shared fixtures. In your own project, copy the two small fakes below.

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
      // Past the end of the script, emit nothing; that fails more clearly than an index error.
      return DummyModel(name) { if (i < responses.size) flowOf(responses[i++]) else emptyFlow() }
    }
  }
}
```

Each call to `generateContent` is one model turn, so a tool round-trip needs two responses: one with the function call, one with the final text.

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
fun toolRoundTrip() = runBlocking {
  val callTurn = LlmResponse(content = Content(role = Role.MODEL, parts = listOf(Part(functionCall = FunctionCall(name = "my_function", args = mapOf("q" to "x"))))))
  val finalTurn = LlmResponse(content = Content.fromText(Role.MODEL, "done"))
  val model = DummyModel.createSequential("fake", listOf(callTurn, finalTurn))
  val tool = DummyTool("my_function") { _, args -> mapOf("answer" to "42 for ${args["q"]}") }
  val agent = LlmAgent(name = "agent", model = model, tools = listOf(tool))

  InMemoryRunner(agent).use { runner ->
    val events =
      runner.runAsync(userId = "u", sessionId = "s", newMessage = Content.fromText(Role.USER, "hi")).toList()

    // The flow carries only agent events; the user message is appended to the session, not emitted.
    assertEquals(listOf("agent", "agent", "agent"), events.map { it.author })
    assertEquals("my_function", events[0].functionCalls().single().name)
    assertEquals("my_function", events[1].functionResponses().single().name)
    assertEquals("done", events.last().contentText())

    val session = runner.sessionService.getSession(SessionKey("InMemoryRunner", "u", "s"))!!
    assertEquals("user", session.events.first().author)
  }
}
```

The runner path exercises session persistence, plugins and agent selection. For a narrower unit test, build an `InvocationContext` and collect `agent.runAsync(context)` directly; that skips the runner.

## Tips

- State written by a tool appears on that tool's response event (`actions.stateDelta`) and in the stored session afterwards.
- To test streaming, have the fake model emit several `LlmResponse(partial = true)` chunks before the final one, with `RunConfig(streamingMode = StreamingMode.SSE)`.
- A generated `@Tool` class can be called directly with `run(context, args)`; reference it from a leaf test source set (`jvmTest`), not `commonTest`.
