/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:OptIn(ExperimentalWorkflowApi::class)

package com.google.adk.kt.agents

import com.google.adk.kt.SchemaUtils
import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.annotations.ExperimentalLiveApi
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.callbacks.AfterAgentCallback
import com.google.adk.kt.callbacks.AfterModelCallback
import com.google.adk.kt.callbacks.AfterToolCallback
import com.google.adk.kt.callbacks.BeforeAgentCallback
import com.google.adk.kt.callbacks.BeforeModelCallback
import com.google.adk.kt.callbacks.BeforeToolCallback
import com.google.adk.kt.callbacks.OnModelErrorCallback
import com.google.adk.kt.callbacks.OnToolErrorCallback
import com.google.adk.kt.events.Event
import com.google.adk.kt.logging.LoggerFactory
import com.google.adk.kt.models.Model
import com.google.adk.kt.processors.AgentTransferProcessor
import com.google.adk.kt.processors.BasicRequestProcessor
import com.google.adk.kt.processors.CompactionRequestProcessor
import com.google.adk.kt.processors.ContentsProcessor
import com.google.adk.kt.processors.ContextCacheRequestProcessor
import com.google.adk.kt.processors.IdentityProcessor
import com.google.adk.kt.processors.InstructionsProcessor
import com.google.adk.kt.processors.LlmRequestProcessor
import com.google.adk.kt.processors.LlmResponseProcessor
import com.google.adk.kt.processors.OutputSchemaProcessor
import com.google.adk.kt.processors.RequestConfirmationProcessor
import com.google.adk.kt.processors.TaskCompletionProcessor
import com.google.adk.kt.processors.findTransferTargets
import com.google.adk.kt.serialization.anyToJsonElement
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.Toolset
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.GenerateContentConfig
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.Schema
import com.google.adk.kt.workflow.NodeInfo
import com.google.adk.kt.workflow.WorkflowConfigurationException
import kotlin.jvm.JvmStatic
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.transformWhile

/**
 * LLM-based Agent.
 *
 * When this agent is a sub-agent and the parent transfers control to it via `transfer_to_agent`,
 * the runner decides who handles the *next* user turn based on the [disallowTransferToParent] flag
 * inherited from `BaseAgent` - see that flag for the full dispatch rules.
 *
 * @property model The model to use for the agent.
 * @param tools The tools configured for this agent; the [tools] property also holds `finish_task`
 *   for a task agent and one tool per task or single-turn sub-agent.
 * @property toolsets Toolsets available to this agent.
 * @property generateContentConfig The additional content generation configurations.
 * @property instruction Instruction guiding the agent's behavior. Use one of:
 *     - `Instruction("text")` for a literal string (the most common case),
 *     - `Instruction(content)` for a pre-built, possibly multimodal [Content], or
 *     - `Instruction { ctx -> ... }` for a [Instruction.Provider] resolved per turn.
 *
 * For convenience, the resolved instruction can contain placeholders like `{variable_name}` that
 * will be resolved at runtime using session state and context.
 *
 * **Behavior depends on [staticInstruction]:**
 * - If [staticInstruction] is null: this instruction is appended to the LLM's system instruction.
 * - If [staticInstruction] is set: this instruction is added as user content in the request (after
 *   the static system instruction).
 *
 * This allows for context caching optimization where static content ([staticInstruction]) comes
 * first in the prompt, followed by dynamic content ([instruction]).
 *
 * @property staticInstruction Static instruction content sent literally as system instruction at
 *   the beginning. This field is for content that never changes. It's sent directly to the model
 *   without any processing or variable substitution.
 *
 * This field is primarily for context caching optimization. Static instructions are sent as system
 * instruction at the beginning of the request, allowing for improved performance when the static
 * portion remains unchanged.
 *
 * **Impact on [instruction] field:**
 * - When staticInstruction is null: [instruction] -> LLM's system instruction
 * - When staticInstruction is set: [instruction] -> user content (after static content)
 *
 * **Context Caching:** Setting staticInstruction alone does NOT enable caching automatically. For
 * explicit caching control, configure context_cache_config.
 *
 * @property beforeModelCallbacks List of callbacks to run before each model call.
 * @property afterModelCallbacks List of callbacks to run after each model call.
 * @property beforeToolCallbacks List of callbacks to run before each tool call.
 * @property afterToolCallbacks List of callbacks to run after each tool call.
 * @property inputSchema The input schema of the agent.
 * @property outputSchema The schema the agent's final response must conform to. Only top-level
 *   object schemas are supported, but a workflow node run also accepts list and primitive schemas
 *   when the model applies the schema directly. When set, the agent asks the model to return JSON
 *   matching this schema and validates the final response against it before saving it to
 *   [outputKey]. If the agent also has tools (including the framework's `transfer_to_agent` tool
 *   when sub-agents/peers are reachable), the schema is applied directly on models that support a
 *   response schema together with tools; on models that do not (e.g. Gemini 2.x), the framework
 *   falls back to a `set_model_response` tool to collect the structured output.
 * @property outputKey The key in session state to store the final text response of the agent. When
 *   set, the agent's concatenated text output (excluding parts marked as thoughts) is written to
 *   `event.actions.stateDelta[outputKey]` on each final-response event, which the session service
 *   then merges into the session state.
 * @property onModelErrorCallbacks List of callbacks to run when a model call fails.
 * @property onToolErrorCallbacks List of callbacks to run when a tool call fails.
 * @param includeContents History the model sees. [IncludeContents.NONE] sends only the current
 *   turn; null means [IncludeContents.DEFAULT], except that a single-turn workflow node then runs
 *   without history.
 * @property mode How this agent converses as a workflow node or a sub-agent. Null means
 *   [AgentMode.CHAT] for a sub-agent and [AgentMode.SINGLE_TURN] for any other workflow node. A
 *   [AgentMode.TASK] agent gains a `finish_task` tool, and a task or single-turn sub-agent is
 *   offered to its parent as a tool rather than as a transfer target.
 * @property maxSteps The maximum number of steps this agent runs within a single invocation, where
 *   a step is one model call plus any tool calls or transfers it triggers. Once the cap is reached
 *   the agent stops emitting further events, even if it has not yet produced a final response.
 *   Defaults to `null`, meaning no cap: the agent keeps stepping until it produces a final response
 *   or the invocation otherwise ends (which can run unbounded). Mirrors the Java ADK
 *   `LlmAgent.maxSteps`. A live run ignores it.
 *
 * Note: this cap is enforced with local, in-process state that is not persisted, so it resets if
 * the runtime restarts mid-invocation. "Max steps" is experimental and may evolve in the future
 * (e.g. to support persistence).
 */
class LlmAgent(
  name: String,
  val model: Model,
  description: String = "",
  subAgents: List<BaseAgent> = emptyList(),
  beforeAgentCallbacks: List<BeforeAgentCallback> = emptyList(),
  afterAgentCallbacks: List<AfterAgentCallback> = emptyList(),
  disallowTransferToParent: Boolean = false,
  disallowTransferToPeers: Boolean = false,
  tools: List<BaseTool> = emptyList(),
  val toolsets: List<Toolset> = emptyList(),
  val generateContentConfig: GenerateContentConfig? = null,
  val instruction: Instruction? = null,
  val staticInstruction: Content? = null,
  val beforeModelCallbacks: List<BeforeModelCallback> = emptyList(),
  val afterModelCallbacks: List<AfterModelCallback> = emptyList(),
  val beforeToolCallbacks: List<BeforeToolCallback> = emptyList(),
  val afterToolCallbacks: List<AfterToolCallback> = emptyList(),
  override val inputSchema: Schema? = null,
  override val outputSchema: Schema? = null,
  val outputKey: String? = null,
  val onModelErrorCallbacks: List<OnModelErrorCallback> = emptyList(),
  val onToolErrorCallbacks: List<OnToolErrorCallback> = emptyList(),
  includeContents: IncludeContents? = null,
  val maxSteps: Int? = null,
  @property:ExperimentalWorkflowApi val mode: AgentMode? = null,
) :
  BaseAgent(
    name = name,
    description = description,
    subAgents = subAgents,
    beforeAgentCallbacks = beforeAgentCallbacks,
    afterAgentCallbacks = afterAgentCallbacks,
    disallowTransferToParent = disallowTransferToParent,
    disallowTransferToPeers = disallowTransferToPeers,
  ) {
  /**
   * How prior history reaches the model. Reads [IncludeContents.DEFAULT] when unset, though a
   * single-turn workflow node then runs with [IncludeContents.NONE].
   */
  val includeContents: IncludeContents = includeContents ?: IncludeContents.DEFAULT

  /**
   * Whether [includeContents] was set explicitly, so a single-turn node uses it instead of NONE.
   */
  internal val includeContentsExplicit: Boolean = includeContents != null

  /**
   * The tools available to this agent: the configured ones, plus `finish_task` for a task agent and
   * a tool for each task or single-turn sub-agent.
   */
  val tools: List<BaseTool> = buildList {
    addAll(tools)
    if (mode == AgentMode.TASK) add(FinishTaskTool(this@LlmAgent))
    for (subAgent in subAgents) {
      when ((subAgent as? LlmAgent)?.mode) {
        AgentMode.SINGLE_TURN -> add(SingleTurnAgentTool(subAgent))
        AgentMode.TASK -> add(TaskAgentTool(subAgent))
        else -> {} // A chat sub-agent is a transfer target, not a tool.
      }
    }
  }

  /** Always true, so a resumed agent node shows its model the answers to its long-running calls. */
  @ExperimentalWorkflowApi
  override val rerunOnResume: Boolean
    get() = true

  // Chat and task nodes stay open across turns until they produce an output.
  @ExperimentalWorkflowApi
  override val waitForOutput: Boolean
    get() = defaultMode() != AgentMode.SINGLE_TURN

  /**
   * Controls how prior conversation history is included in this agent's model request.
   *
   * Mirrors the Python ADK `LlmAgent.include_contents` field (`Literal['default', 'none']`).
   *
   * Note: This setting only affects the `contents` portion of the model request. The system
   * instruction (managed by the instructions processor) and the available tools (managed by the
   * basic request processor) are preserved in both modes.
   */
  enum class IncludeContents {
    /** The model receives the relevant conversation history. */
    DEFAULT,

    /**
     * The model receives no prior history and operates solely on the current turn.
     *
     * The "current turn" starts at the most recent user input or, in multi-agent setups, at the
     * most recent reply from another agent. Tool calls and responses produced within the current
     * turn are still included.
     */
    NONE,
  }

  internal val systemBeforeTurnProcessors: List<LlmRequestProcessor> =
    listOf(
      BasicRequestProcessor(),
      RequestConfirmationProcessor(),
      InstructionsProcessor(),
      IdentityProcessor(),
      // Compaction should run before contents so compacted events are reflected in the model
      // request context.
      CompactionRequestProcessor(),
      ContentsProcessor(),
      ContextCacheRequestProcessor(),
      AgentTransferProcessor(),
      TaskCompletionProcessor(),
      OutputSchemaProcessor(),
    )

  internal val systemAfterTurnProcessors: List<LlmResponseProcessor> = emptyList()

  private fun getTransferToAgentOrNull(event: Event, fromAgent: String): BaseAgent? {
    if (event.author == fromAgent) {
      val transferTo = event.actions.transferToAgent
      if (transferTo != null && transferTo != fromAgent) {
        // Check declared targets first, so a same-named agent elsewhere cannot be resumed instead.
        return findTransferTargets(this).firstOrNull { it.name == transferTo }
          ?: rootAgent.findAgent(transferTo)
      }
    }
    return null
  }

  private suspend fun getSubagentToResume(context: InvocationContext): BaseAgent? {
    val events = context.getEvents(currentInvocation = true, currentBranch = true)
    if (events.isEmpty()) return null

    val lastEvent = events.last()
    if (lastEvent.author == name) {
      return getTransferToAgentOrNull(lastEvent, name)
    }

    if (lastEvent.author == Role.USER) {
      val functionCallEvent = context.findMatchingFunctionCall(lastEvent)
      if (functionCallEvent == null) {
        throw IllegalArgumentException(
          "No agent to transfer to for resuming agent from function response $name"
        )
      }
      if (functionCallEvent.author == name) {
        return null
      }
    }

    for (event in events.asReversed().asSequence().drop(1)) {
      val agent = getTransferToAgentOrNull(event, name)
      if (agent != null) {
        return agent
      }
    }

    return null
  }

  /**
   * Runs this agent over a live connection.
   *
   * A live run is one continuous conversation rather than a sequence of steps, so unlike
   * [runAsyncImpl] it has no step loop or step cap: it runs until the caller closes the queue, its
   * `task_completed` tool ends it, or a connection ends with nothing to resume, reconnecting after
   * a server go-away or a resumable drop and handing over to another agent on a transfer. Like
   * [runAsyncImpl], it saves a non-partial final text to [outputKey]; its after-model callbacks see
   * only the output transcription so far, and any change they make restarts the session.
   */
  @OptIn(ExperimentalLiveApi::class)
  override fun runLiveImpl(context: InvocationContext): Flow<Event> =
    LlmAgentTurn(this, context, systemBeforeTurnProcessors, systemAfterTurnProcessors)
      .executeLive()
      // A streaming tool's user-role message goes to the caller, never to outputKey.
      .onEach { if (it.content?.role != Role.USER) maybeSaveOutputToState(it, context) }

  /**
   * Runs this agent as a graph node in its [AgentMode]: a single turn on the node input, a chat
   * that runs its task sub-agents, or a task that ends with `finish_task`.
   */
  @ExperimentalWorkflowApi
  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> =
    when (nodeMode(context.invocationContext)) {
      AgentMode.SINGLE_TURN -> runSingleTurn(context, nodeInput)
      AgentMode.CHAT -> runInChatMode(context)
      AgentMode.TASK -> runInTaskMode(context, nodeInput)
    }

  /**
   * This agent's mode when [mode] is unset: chat with a parent or sub-agents, as in Python,
   * single-turn otherwise.
   */
  internal fun defaultMode(): AgentMode =
    mode
      ?: if (parentAgent != null || subAgents.isNotEmpty()) AgentMode.CHAT
      else AgentMode.SINGLE_TURN

  /**
   * The mode this agent runs in as a node: any per-run override set for this agent, else
   * [defaultMode].
   */
  private fun nodeMode(context: InvocationContext): AgentMode =
    context.frameworkData.modeOverrideFor(this) ?: defaultMode()

  /**
   * Returns [context]'s invocation context for this agent's run in [mode]. The mode is keyed to
   * this agent, the node's isolation scope also reaches its sub-agents, and the node context lets
   * this agent's tools run sub-agents as child nodes.
   */
  private fun agentContext(context: Context, mode: AgentMode): InvocationContext {
    val ic = context.invocationContext
    return ic.copy(
      frameworkData =
        ic.frameworkData.copy(
          agentModeOverride = AgentModeOverride(agent = this, mode = mode),
          nodeContext = context,
          nodePath = context.nodePath,
          isolationScope = context.isolationScope,
        )
    )
  }

  /**
   * Runs one turn on [nodeInput], fed to the agent as a user turn. The agent runs history-free
   * unless [includeContents] was explicitly configured, sees only session events in the node's
   * isolation scope, and its final model turn is the node's output.
   */
  private fun runSingleTurn(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    context.eventAuthor = name
    val injected = injectNodeInput(context, nodeInput)
    val agentContext = agentContext(context, AgentMode.SINGLE_TURN)
    var outputMarked = false
    try {
      emitAll(
        runAsync(agentContext).transformWhile { event ->
          if (event.author.isNotEmpty()) context.eventAuthor = event.author
          // Only the first final turn is the output; later content is not a second one.
          val marked = if (outputMarked) event else markOutputIfFinalTurn(event)
          outputMarked = outputMarked || marked !== event
          emit(marked)
          // Leave the handoff to the dispatch loop, so the target runs once, as a node.
          if (event.actions.transferToAgent == null) return@transformWhile true
          if (agentContext.isResumable) {
            agentContext.setAgentState(name, endOfAgent = true)
            emit(endOfAgentEvent(agentContext))
          }
          false
        }
      )
    } finally {
      // The input is for this run only, so later nodes on the branch do not see it.
      if (injected != null) context.invocationContext.session.events.remove(injected)
    }
  }

  /**
   * Works on a delegated task until `finish_task` succeeds. A failed call's error reaches the
   * model, which retries; the succeeding call's arguments become the node's output and [outputKey]
   * value. The node input, when given, is the task's opening user turn if no delegating call
   * carries it.
   */
  private fun runInTaskMode(context: Context, nodeInput: Any?): Flow<Any?> {
    val agentContext = agentContext(context, AgentMode.TASK)
    val taskContext =
      agentContext.copy(
        userContent = nodeInput?.let(::nodeInputToUserContent) ?: agentContext.userContent
      )
    return runWithDelegations(
      context,
      taskContext,
      tools.filterIsInstance<FinishTaskTool>().first(),
    )
  }

  /** Holds a conversation that runs its task sub-agents. */
  private fun runInChatMode(context: Context): Flow<Any?> =
    runWithDelegations(context, agentContext(context, AgentMode.CHAT), finishTool = null)

  /**
   * Runs this agent on [agentContext], running its task sub-agents along the way. Each round runs
   * the agent until the model delegates tasks or ends. The delegated tasks then run as child nodes,
   * in each call's own isolation scope, and their answers start the next round. With a
   * [finishTool], the run ends once a `finish_task` call succeeds; a `transfer_to_agent` ends it
   * too, so the dispatch loop runs the target as a node.
   */
  private fun runWithDelegations(
    context: Context,
    agentContext: InvocationContext,
    finishTool: FinishTaskTool?,
  ): Flow<Any?> = flow {
    val toolsByName = tools.associateBy { it.name }
    answerPendingDelegations(context, toolsByName, ownScopeOnly = finishTool != null)
    val finish = finishTool?.let(::FinishState)
    while (true) {
      val round = DelegationRound()
      emitAll(
        runAsync(agentContext).transformWhile { event ->
          emitRoundEvent(context, agentContext, event, round, finish, toolsByName)
        }
      )
      if (round.transferred || round.delegations.isEmpty()) return@flow
      for (call in round.delegations) emitTaskResponse(context, call)
      if (!canStartNextRound(round, agentContext)) return@flow
    }
  }

  /** What one round of [runWithDelegations] has seen so far. */
  private class DelegationRound {
    /** The task delegations the model made this round; empty until it makes some. */
    var delegations = emptyList<FunctionCall>()

    /** Long-running calls of this round that no response has answered yet. */
    val pendingLongRunningCalls = mutableSetOf<String>()

    /** Whether the round ended at a `transfer_to_agent` handoff. */
    var transferred = false
  }

  /** The `finish_task` tool of a task run and the arguments of its latest call. */
  private class FinishState(val tool: FinishTaskTool) {
    var args: Map<String, Any?>? = null
  }

  /**
   * Emits [event] from a round and returns whether the round goes on. Once the model delegates
   * tasks, the round only keeps that step's tool responses; a task run ends with its successful
   * `finish_task` response.
   */
  private suspend fun FlowCollector<Any?>.emitRoundEvent(
    context: Context,
    agentContext: InvocationContext,
    event: Event,
    round: DelegationRound,
    finish: FinishState?,
    toolsByName: Map<String, BaseTool>,
  ): Boolean {
    if (event.author.isNotEmpty()) context.eventAuthor = event.author
    round.pendingLongRunningCalls += event.longRunningToolIds
    for (response in event.functionResponses()) {
      response.id?.let(round.pendingLongRunningCalls::remove)
    }
    if (round.delegations.isNotEmpty()) {
      return emitDelegatingStepResponse(event, context.invocationContext.branch)
    }
    if (finish != null) {
      when (val step = finishStepOf(event, finish)) {
        is FinishStep.Call -> {
          finish.args = step.args
          emit(event)
          return true
        }
        is FinishStep.Done -> {
          val key = outputKey?.takeIf { it.isNotEmpty() }
          if (key != null && step.output != null) context.updateState(key, step.output)
          emit(event.copy(output = step.output))
          return false
        }
        null -> {}
      }
    }
    emit(event)
    if (event.actions.transferToAgent != null) return endRoundAtHandoff(agentContext, round)
    round.delegations = taskDelegationsIn(event, toolsByName)
    return round.delegations.isEmpty() || hasEagerToolCalls(event, toolsByName)
  }

  /**
   * Emits [event] after the model delegated tasks, and returns whether the round goes on. It keeps
   * the tool responses of the step that delegated, and stops before the model starts another round.
   */
  private suspend fun FlowCollector<Any?>.emitDelegatingStepResponse(
    event: Event,
    branch: String?,
  ): Boolean {
    if (startsModelRound(event, branch)) return false
    emit(event)
    return !isOwnStep(event, branch) || event.functionResponses().isEmpty()
  }

  /** What an event of a task run means for finishing the task. */
  private sealed interface FinishStep {
    /** A `finish_task` call, whose arguments give the output once a response ends the task. */
    class Call(val args: Map<String, Any?>) : FinishStep

    /** The `finish_task` response that ends the task with [output]. */
    class Done(val output: Any?) : FinishStep
  }

  /** Returns what [event] means for finishing the task of [finish], or null if it is unrelated. */
  private fun finishStepOf(event: Event, finish: FinishState): FinishStep? {
    if (!event.partial) {
      val call = event.functionCalls().firstOrNull { it.name == FinishTaskTool.NAME }
      if (call != null) return FinishStep.Call(call.args)
    }
    val args = finish.args
    if (args == null || !FinishTaskTool.isTerminalResponse(event)) return null
    return FinishStep.Done(finish.tool.taskOutput(args))
  }

  /**
   * Leaves a `transfer_to_agent` handoff to the dispatch loop, so the target runs once, as a node.
   * Ends this agent's run when the invocation is resumable, and returns false to end the round.
   */
  private suspend fun FlowCollector<Any?>.endRoundAtHandoff(
    agentContext: InvocationContext,
    round: DelegationRound,
  ): Boolean {
    round.transferred = true
    if (agentContext.isResumable) {
      agentContext.setAgentState(name, endOfAgent = true)
      emit(endOfAgentEvent(agentContext))
    }
    return false
  }

  /** Answers the delegations an earlier turn left unanswered; a task resumes only its own. */
  private suspend fun FlowCollector<Any?>.answerPendingDelegations(
    context: Context,
    toolsByName: Map<String, BaseTool>,
    ownScopeOnly: Boolean,
  ) {
    val scope = if (ownScopeOnly) context.isolationScope else null
    for (call in unresolvedTaskDelegations(context.session.events, name, toolsByName, scope)) {
      emitTaskResponse(context, call)
    }
  }

  /**
   * Whether another round may start once this round's tasks have answered. A long-running call of
   * the round that is still unanswered pauses the turn, and so does reaching [maxSteps].
   */
  private fun canStartNextRound(round: DelegationRound, agentContext: InvocationContext): Boolean {
    if (round.pendingLongRunningCalls.isNotEmpty()) return false
    val stepCap = maxSteps ?: return true
    return stepsTaken(agentContext) < stepCap
  }

  /** The steps this agent has taken in its node run on [agentContext]. */
  @OptIn(FrameworkInternalApi::class)
  private fun stepsTaken(agentContext: InvocationContext): Int =
    agentContext.frameworkData.agentModeOverride?.takeIf { it.agent === this }?.stepsTaken ?: 0

  /** Runs the task agent a delegation [call] names and emits the response that answers the call. */
  private suspend fun FlowCollector<Any?>.emitTaskResponse(context: Context, call: FunctionCall) {
    val callId = checkNotNull(call.id) { "A task delegation call must have an id." }
    val target =
      rootAgent.findAgent(call.name)
        ?: throw WorkflowConfigurationException("Task target agent '${call.name}' not found.")
    val output =
      context.runNode(
        target,
        nodeInput = call.args,
        runId = callId,
        useSubBranch = true,
        overrideIsolationScope = callId,
        raiseOnWait = true,
      )
    context.eventAuthor = Role.USER
    emit(taskResponseEvent(call, output))
    context.eventAuthor = name
  }

  /**
   * Whether [event] starts a new model round on [branch] rather than finishing the current step.
   */
  private fun startsModelRound(event: Event, branch: String?): Boolean =
    isOwnStep(event, branch) &&
      event.content?.role == Role.MODEL &&
      event.functionCalls().none { it.name == FunctionCall.REQUEST_CONFIRMATION_FUNCTION_CALL_NAME }

  /** Whether [event] is this agent's own step on [branch], not one from a run on a sub-branch. */
  private fun isOwnStep(event: Event, branch: String?): Boolean =
    event.author == name && event.branch == branch

  /**
   * Feeds the node's input to the agent as its user turn: the user message for a first node, or the
   * predecessor's output. Skipped on resume, for a null input, and for a user message the runner
   * already recorded. Returns the injected event, or null.
   */
  private fun injectNodeInput(context: Context, nodeInput: Any?): Event? {
    if (nodeInput == null || context.resumeInputs.isNotEmpty()) return null
    val ic = context.invocationContext
    val recorded =
      nodeInput == ic.userContent &&
        ic.session.events.any {
          it.invocationId == ic.invocationId &&
            it.author == Role.USER &&
            it.nodeInfo?.path.isNullOrEmpty() &&
            it.isolationScope == context.isolationScope &&
            it.content == nodeInput
        }
    if (recorded) return null
    val event =
      Event(
        invocationId = ic.invocationId,
        author = Role.USER,
        content = nodeInputToUserContent(nodeInput),
        branch = ic.branch,
        nodeInfo = NodeInfo(path = context.nodePath),
        isolationScope = context.isolationScope,
      )
    ic.session.events.add(event)
    return event
  }

  /**
   * Attaches the node output to [event] when it is this agent's final model turn and has an output,
   * and sets [NodeInfo.messageAsOutput] when no [outputSchema] is configured. Otherwise returns
   * [event] itself.
   */
  private fun markOutputIfFinalTurn(event: Event): Event {
    val content = event.content
    if (event.author != name || !event.isFinalResponse) return event
    if (event.functionCalls().isNotEmpty() || content == null) return event
    if (content.role != Role.MODEL) return event
    val output = outputValueOf(content) ?: return event
    if (outputSchema != null) return event.copy(output = output)
    return event.copy(
      output = output,
      nodeInfo = (event.nodeInfo ?: NodeInfo()).copy(messageAsOutput = true),
    )
  }

  /**
   * Parses the model response text in [content] against [outputSchema], or returns the trimmed text
   * when no [outputSchema] is configured. Blank text gives no output, as on a replay.
   */
  private fun outputValueOf(content: Content): Any? {
    val text = content.text()
    val schema = outputSchema ?: return text.trim().ifEmpty { null }
    return SchemaUtils.parseModelOutput(schema, text)
  }

  override fun runAsyncImpl(context: InvocationContext): Flow<Event> = flow {
    val agentState = context.agentStates[name]

    if (agentState != null) {
      val agentToTransfer = getSubagentToResume(context)
      if (agentToTransfer != null) {
        // Suppress end-of-agent if the resumed sub-agent pauses again so a later turn can resume
        // it, mirroring Python llm_agent.py.
        var resumePaused = false
        agentToTransfer.runAsync(context).collect { event ->
          emit(event)
          if (context.shouldPauseInvocation(event)) {
            resumePaused = true
          }
        }
        if (!resumePaused) {
          context.setAgentState(name, endOfAgent = true)
          emitEndOfAgent(context)
        }
        return@flow
      }
    }

    // Suppress end-of-agent on a paused long-running call so a follow-up response can resume this
    // agent, checking events both in-stream and in the last two events (Python llm_agent.py).
    var shouldPause = false
    var transferred = false
    executeTurns(context).collect { event ->
      maybeSaveOutputToState(event, context)
      emit(event)
      if (context.shouldPauseInvocation(event)) {
        shouldPause = true
      }
      // Mark this agent done before target validation so resume does not run it again.
      if (
        context.isResumable &&
          !transferred &&
          event.author == name &&
          event.actions.transferToAgent != null
      ) {
        transferred = true
        context.setAgentState(name, endOfAgent = true)
        emitEndOfAgent(context)
      }
    }
    if (shouldPause || transferred) return@flow

    if (context.isResumable && context.agent == this@LlmAgent) {
      val events = context.getEvents(currentInvocation = true, currentBranch = true)
      if (events.takeLast(2).any { context.shouldPauseInvocation(it) }) {
        return@flow
      }
      context.setAgentState(name, endOfAgent = true)
      emitEndOfAgent(context)
    }
  }

  @OptIn(FrameworkInternalApi::class)
  private fun executeTurns(context: InvocationContext): Flow<Event> = flow {
    var lastEvent: Event?
    // A chat or task node's delegation rounds share one step count.
    val override = context.frameworkData.agentModeOverride?.takeIf { it.agent === this@LlmAgent }
    var stepsCompleted = override?.stepsTaken ?: 0
    do {
      lastEvent = null
      // Counted when it starts, since a delegation round can stop the flow mid-step.
      stepsCompleted++
      override?.stepsTaken = stepsCompleted
      LlmAgentTurn(this@LlmAgent, context, systemBeforeTurnProcessors, systemAfterTurnProcessors)
        .execute()
        .collect { event ->
          lastEvent = event
          emit(event)
        }
      // Enforce the per-agent step cap. Checked after running the step so that maxSteps == N runs
      // exactly N steps, mirroring the Java ADK BaseLlmFlow.run loop.
      if (maxSteps != null && stepsCompleted >= maxSteps) {
        logger.debug { "Ending agent execution for $name: reached maxSteps=$maxSteps." }
        break
      }
      // Partial events are not saved, so another step would resend the same history.
      if (lastEvent?.partial == true) {
        logger.warn { "The last event is partial, which is not expected." }
        break
      }
    } while (
      !context.isEndOfInvocation &&
        lastEvent?.isFinalResponse == false &&
        lastEvent?.actions?.endOfAgent != true
    )
  }

  /** Materializes the [instruction] for the current turn. */
  internal suspend fun canonicalInstruction(context: ReadonlyContext): Content? =
    instruction?.resolve(context)

  /**
   * If [outputKey] is set, writes this agent's final-response text into
   * `event.actions.stateDelta[outputKey]` so that the session service merges it into session state.
   *
   * Mirrors `LlmAgent.__maybe_save_output_to_state` in the Python ADK and
   * `LlmAgent.maybeSaveOutputToState` in the Java ADK. Behavior:
   * - Skips events authored by other agents (e.g. after this agent has transferred control).
   * - Skips when [outputKey] is null or empty (mirrors Python's `if not self.output_key` falsy
   *   check; an empty string is treated as unset rather than written to `stateDelta[""]`).
   * - Only fires on [Event.isFinalResponse] events that carry text content.
   * - Concatenates the text from every non-thought part with no separator. Parts where
   *   [Part.thought][com.google.adk.kt.types.Part.thought] is `true` are ignored.
   * - If no non-thought text parts are present, leaves [Event.actions]`.stateDelta` untouched so
   *   that values already written by other code paths (e.g. tool-side state updates on
   *   function-response-only events) are not overwritten.
   * - If [outputSchema] is set, the concatenated text is parsed as JSON and validated against the
   *   schema; the parsed map is stored on success. A blank result is skipped (it represents an
   *   empty final stream chunk). If parsing or validation fails, the raw text is stored instead and
   *   an error is logged. Mirrors `validate_schema` in the Python ADK and `validateOutputSchema` in
   *   the Java ADK. Note this best-effort fallback is the direct-schema path only; the
   *   `set_model_response` workaround instead validates strictly and propagates a tool execution
   *   error (failing the invocation if unrecovered) rather than saving best-effort text (see
   *   [com.google.adk.kt.tools.SetModelResponseTool.run]).
   * - On a single-turn workflow node run, the text is parsed as the node's output is, and a schema
   *   mismatch saves nothing.
   *
   * NOTE on long-running tool events: when an event contains text alongside a long-running function
   * call (i.e. [Event.longRunningToolIds] is non-empty), Kotlin's [Event.isFinalResponse] returns
   * `true` (matching Python's `is_final_response()`), so the text portion is saved. The Java ADK's
   * `Event.finalResponse()` returns `false` for the same shape (it has no long-running-tool special
   * case), so it would not save. Kotlin intentionally follows Python here.
   */
  @OptIn(FrameworkInternalApi::class)
  private fun maybeSaveOutputToState(event: Event, context: InvocationContext) {
    if (event.author != name) {
      logger.debug { "Skipping output save for agent $name: event authored by ${event.author}" }
      return
    }
    val outputKey = outputKey?.takeIf { it.isNotEmpty() } ?: return
    // A task agent's output comes from `finish_task`, not from a text turn.
    if (mode == AgentMode.TASK) return
    if (!event.isFinalResponse) return
    val parts = event.content?.parts ?: return

    // An empty `parts` list is handled by the `textParts.isEmpty()` guard below (filtering an empty
    // list yields an empty list), so no separate `parts.isEmpty()` check is needed.
    val textParts = parts.filter { it.text != null && it.thought != true }
    if (textParts.isEmpty()) return

    val result = textParts.joinToString(separator = "") { it.text.orEmpty() }

    val nodeRun = context.frameworkData.isSingleTurnNode(this)
    val schema = outputSchema
    val output: Any =
      when {
        schema == null -> result
        // An empty/whitespace result is an empty final stream chunk; do not parse it as JSON.
        result.isBlank() -> return
        // A mismatch saves nothing; markOutputIfFinalTurn fails the node on the final model turn.
        nodeRun ->
          runCatching { SchemaUtils.parseModelOutput(schema, result) }.getOrNull() ?: return
        else ->
          SchemaUtils.validateOutputSchema(result, schema).getOrElse { cause ->
            // The cause's message can quote the model's output, so only its type is logged.
            logger.error {
              "LlmAgent output for outputKey '$outputKey' did not match the outputSchema " +
                "(${cause::class.simpleName}). Saving raw output to state."
            }
            result
          }
      }
    event.actions.stateDelta[outputKey] = output
  }

  /**
   * Fluent builder for [LlmAgent], provided primarily for Java callers. Any property left unset
   * falls back to the same default as the constructor.
   */
  @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
  class Builder {
    private var name: String? = null
    private var model: Model? = null
    private var description: String = ""
    private var subAgents: List<BaseAgent> = emptyList()
    private var beforeAgentCallbacks: List<BeforeAgentCallback> = emptyList()
    private var afterAgentCallbacks: List<AfterAgentCallback> = emptyList()
    private var disallowTransferToParent: Boolean = false
    private var disallowTransferToPeers: Boolean = false
    private var tools: List<BaseTool> = emptyList()
    private var toolsets: List<Toolset> = emptyList()
    private var generateContentConfig: GenerateContentConfig? = null
    private var instruction: Instruction? = null
    private var staticInstruction: Content? = null
    private var beforeModelCallbacks: List<BeforeModelCallback> = emptyList()
    private var afterModelCallbacks: List<AfterModelCallback> = emptyList()
    private var beforeToolCallbacks: List<BeforeToolCallback> = emptyList()
    private var afterToolCallbacks: List<AfterToolCallback> = emptyList()
    private var inputSchema: Schema? = null
    private var outputSchema: Schema? = null
    private var outputKey: String? = null
    private var onModelErrorCallbacks: List<OnModelErrorCallback> = emptyList()
    private var onToolErrorCallbacks: List<OnToolErrorCallback> = emptyList()
    private var includeContents: IncludeContents? = null
    private var maxSteps: Int? = null
    private var mode: AgentMode? = null

    fun name(name: String): Builder = apply { this.name = name }

    fun model(model: Model): Builder = apply { this.model = model }

    fun description(description: String): Builder = apply { this.description = description }

    fun subAgents(subAgents: List<BaseAgent>): Builder = apply { this.subAgents = subAgents }

    fun subAgents(vararg subAgents: BaseAgent): Builder = apply {
      this.subAgents = subAgents.toList()
    }

    fun beforeAgentCallbacks(callbacks: List<BeforeAgentCallback>): Builder = apply {
      this.beforeAgentCallbacks = callbacks
    }

    fun afterAgentCallbacks(callbacks: List<AfterAgentCallback>): Builder = apply {
      this.afterAgentCallbacks = callbacks
    }

    fun disallowTransferToParent(value: Boolean): Builder = apply {
      this.disallowTransferToParent = value
    }

    fun disallowTransferToPeers(value: Boolean): Builder = apply {
      this.disallowTransferToPeers = value
    }

    fun tools(tools: List<BaseTool>): Builder = apply { this.tools = tools }

    fun tools(vararg tools: BaseTool): Builder = apply { this.tools = tools.toList() }

    fun toolsets(toolsets: List<Toolset>): Builder = apply { this.toolsets = toolsets }

    fun toolsets(vararg toolsets: Toolset): Builder = apply { this.toolsets = toolsets.toList() }

    fun generateContentConfig(config: GenerateContentConfig?): Builder = apply {
      this.generateContentConfig = config
    }

    fun instruction(instruction: Instruction?): Builder = apply { this.instruction = instruction }

    fun instruction(instruction: String): Builder = apply {
      this.instruction = Instruction(instruction)
    }

    fun staticInstruction(staticInstruction: Content?): Builder = apply {
      this.staticInstruction = staticInstruction
    }

    fun beforeModelCallbacks(callbacks: List<BeforeModelCallback>): Builder = apply {
      this.beforeModelCallbacks = callbacks
    }

    fun afterModelCallbacks(callbacks: List<AfterModelCallback>): Builder = apply {
      this.afterModelCallbacks = callbacks
    }

    fun beforeToolCallbacks(callbacks: List<BeforeToolCallback>): Builder = apply {
      this.beforeToolCallbacks = callbacks
    }

    fun afterToolCallbacks(callbacks: List<AfterToolCallback>): Builder = apply {
      this.afterToolCallbacks = callbacks
    }

    fun inputSchema(inputSchema: Schema?): Builder = apply { this.inputSchema = inputSchema }

    fun outputSchema(outputSchema: Schema?): Builder = apply { this.outputSchema = outputSchema }

    fun outputKey(outputKey: String?): Builder = apply { this.outputKey = outputKey }

    fun onModelErrorCallbacks(callbacks: List<OnModelErrorCallback>): Builder = apply {
      this.onModelErrorCallbacks = callbacks
    }

    fun onToolErrorCallbacks(callbacks: List<OnToolErrorCallback>): Builder = apply {
      this.onToolErrorCallbacks = callbacks
    }

    fun includeContents(includeContents: IncludeContents?): Builder = apply {
      this.includeContents = includeContents
    }

    fun maxSteps(maxSteps: Int?): Builder = apply { this.maxSteps = maxSteps }

    @ExperimentalWorkflowApi fun mode(mode: AgentMode?): Builder = apply { this.mode = mode }

    fun build(): LlmAgent =
      LlmAgent(
        name = checkNotNull(name) { "LlmAgent.Builder requires name to be set." },
        model = checkNotNull(model) { "LlmAgent.Builder requires model to be set." },
        description = description,
        subAgents = subAgents,
        beforeAgentCallbacks = beforeAgentCallbacks,
        afterAgentCallbacks = afterAgentCallbacks,
        disallowTransferToParent = disallowTransferToParent,
        disallowTransferToPeers = disallowTransferToPeers,
        tools = tools,
        toolsets = toolsets,
        generateContentConfig = generateContentConfig,
        instruction = instruction,
        staticInstruction = staticInstruction,
        beforeModelCallbacks = beforeModelCallbacks,
        afterModelCallbacks = afterModelCallbacks,
        beforeToolCallbacks = beforeToolCallbacks,
        afterToolCallbacks = afterToolCallbacks,
        inputSchema = inputSchema,
        outputSchema = outputSchema,
        outputKey = outputKey,
        onModelErrorCallbacks = onModelErrorCallbacks,
        onToolErrorCallbacks = onToolErrorCallbacks,
        includeContents = includeContents,
        maxSteps = maxSteps,
        mode = mode,
      )
  }

  companion object {
    @AdkJavaInteropApi @JvmStatic fun builder(): Builder = Builder()

    private val logger = LoggerFactory.getLogger(LlmAgent::class)
  }
}

/** Ensures InvocationContext.agent is LlmAgent instance, throws exception otherwise. */
internal fun InvocationContext.requireLlmAgent(): LlmAgent {
  val agent = this.agent
  require(agent is LlmAgent) {
    "Expected agent to be an LlmAgent, but got ${agent::class.simpleName}"
  }
  return agent
}

/** Ensures CallbackContext.agent is LlmAgent instance. */
internal fun CallbackContext.requireLlmAgent(): LlmAgent {
  val agent = this.agent
  require(agent is LlmAgent) {
    "Expected agent to be an LlmAgent, but got ${agent::class.simpleName}"
  }
  return agent
}

/**
 * Converts a node's input into a user-role [Content]: re-tags a [Content] as user, formats a map or
 * list as JSON text, and converts any other value with `toString`.
 *
 * @throws IllegalArgumentException if a map or list holds a value JSON can't represent.
 */
private fun nodeInputToUserContent(value: Any): Content =
  when (value) {
    is Content -> value.copy(role = Role.USER)
    is Map<*, *>,
    is List<*> -> {
      val json =
        anyToJsonElement(value) { unsupported ->
          throw IllegalArgumentException(
            "A node input map or list must hold only JSON values, but it holds a " +
              "${unsupported::class.simpleName}."
          )
        }
      Content.fromText(Role.USER, json.toString())
    }
    else -> Content.fromText(Role.USER, value.toString())
  }
