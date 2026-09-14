# Workflow agents and multi-agent trees

There is no graph workflow in ADK Kotlin. Orchestration is three deterministic
containers plus LLM-driven transfer. All three containers take `name`,
`description`, `subAgents`, `beforeAgentCallbacks`, `afterAgentCallbacks`;
none takes a model, tools or instruction.

## SequentialAgent

```kotlin
SequentialAgent(
  name = "preflight",
  subAgents = listOf(gatherer, planner, writer),
)
```

Runs each child in order. A child's `outputKey` is the usual way to pass data
forward; the next child reads it with `{key}` in its instruction. Children
share the session and branch.

## ParallelAgent

```kotlin
ParallelAgent(name = "research", subAgents = listOf(webSearcher, dbSearcher, docSearcher))
```

Every child runs concurrently on its own branch (`research.webSearcher`,
...). Children do not see each other's events during the run; they do see
prior history and shared state. Give each child a distinct `outputKey`, then
add a downstream agent in a `SequentialAgent` that reads all of them. If a
direct child emits `escalate`, the remaining children are cancelled.

Tools inside parallel children run on the collector's dispatcher, so wrap
blocking I/O in `withContext(Dispatchers.IO)`.

## LoopAgent

```kotlin
LoopAgent(
  name = "refine",
  maxIterations = 5,
  subAgents = listOf(critic, reviser),
)
```

Repeats the child sequence until `maxIterations` or until any event carries
`actions.escalate = true`. `maxIterations = null` loops until escalate. To
exit from an LLM child, give it `ExitLoopTool()` and tell it when to call
`exit_loop`. To exit from code, set `escalate` in a tool, a callback, or a
custom agent.

`endInvocation()` is not an exit: it only stops the current `LlmAgent`'s step
loop, and the container moves on to the next child or iteration.

## LLM-driven transfer

```kotlin
val root =
  LlmAgent(
    name = "coordinator",
    model = model,
    instruction = Instruction("Route billing questions to billing, everything else to support."),
    subAgents = listOf(billing, support),
  )
```

With `subAgents` set, the framework adds a `transfer_to_agent` tool and the
child descriptions to the request. A transfer moves the conversation to the
child; later user turns keep going to that child until it transfers back or
the tree forbids it. `disallowTransferToParent = true` on a child stops it
handing back; `disallowTransferToPeers = true` stops sibling transfers. A
`description` on every child is what the router's model reads, so write it
for the model, not for humans.

Each `BaseAgent` instance can have exactly one parent. Reusing an instance in
two `subAgents` lists throws at construction time; build a second instance.

## Custom BaseAgent

```kotlin
class MonsterFightAgent(name: String) : BaseAgent(name = name) {
  override fun runAsyncImpl(context: InvocationContext): Flow<Event> = flow {
    val legendary = Random.nextInt(10) == 0
    emit(
      Event(
        invocationId = context.invocationId,
        author = name,
        content = Content.fromText(Role.MODEL, if (legendary) "Legendary!" else "Common."),
        actions = EventActions(escalate = legendary),
      )
    )
  }
}
```

Rules for `runAsyncImpl`:

- Return a cold `flow { }`; do not launch coroutines that outlive it.
- Set `author = name` and `invocationId = context.invocationId` on every
  event, or the runner's agent selection and the Dev UI get confused.
- Write state through `EventActions(stateDelta = ...)` on an emitted event;
  do not mutate `context.session.state` directly. The runner applies deltas
  when it persists the event.
- Use `context.branch` on events if you emit inside a `ParallelAgent`.
- To delegate, collect `child.runAsync(context)` and re-emit its events.

`examples/src/main/kotlin/com/google/adk/kt/examples/structural/` has a
runnable demo for each container.
