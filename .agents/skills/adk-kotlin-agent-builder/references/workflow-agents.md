# Multi-agent trees

Compose agents with three containers (`SequentialAgent`, `ParallelAgent`, `LoopAgent`) and with LLM-driven transfer. The containers take a `name`, `subAgents` and agent callbacks, and no model.

```kotlin
SequentialAgent(name = "pipeline", subAgents = listOf(gatherer, planner, writer))
ParallelAgent(name = "research", subAgents = listOf(webSearcher, dbSearcher))
LoopAgent(name = "refine", maxIterations = 5, subAgents = listOf(critic, reviser))
```

- **Sequential** runs children in order on the same session. Pass data forward with `outputKey` on one child and `{key}` in the next child's instruction.
- **Parallel** runs children concurrently on separate branches. They share state but do not see each other's events during the run. Give each a distinct `outputKey` and read the results in a following agent.
- **Loop** repeats its children until `maxIterations` or until an event escalates. Give an LLM child `ExitLoopTool()` and tell it when to call it. `endInvocation()` does not exit a loop (see best-practices.md).

`examples/.../structural/` has a runnable demo of each.

## LLM-driven transfer

```kotlin
LlmAgent(
  name = "coordinator",
  model = model,
  instruction = Instruction("Route billing questions to billing, everything else to support."),
  subAgents = listOf(billing, support),
)
```

With `subAgents`, the model can call `transfer_to_agent` to hand the conversation to a child. The router's model reads each child's `description`, so write it for the model. Later user turns stay with the child the conversation was transferred to.

- `disallowTransferToParent = true` on a child stops it handing back, and also sends the next user turn back to the root. Use it for one-shot helpers.
- `disallowTransferToPeers = true` stops sibling transfers.

An agent instance can have only one parent; build a new instance for each place in the tree.

## Graph workflows (experimental)

The `workflow` package has an experimental graph engine behind `@OptIn(ExperimentalWorkflowApi::class)`. It is expected to change without notice. Use it only when asked to, and learn it from the KDoc starting at `Workflow.kt` and from the tests. Otherwise use the containers above, and for custom orchestration read `BaseAgent` and the existing containers first, since the agent base classes are changing along with the graph engine.
