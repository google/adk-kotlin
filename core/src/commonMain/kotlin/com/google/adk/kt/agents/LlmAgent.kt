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
import com.google.adk.kt.callbacks.runOnEventCallbacksPipeline
import com.google.adk.kt.describesOnlyObjects
import com.google.adk.kt.events.Event
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.events.applyRewinds
import com.google.adk.kt.logging.LoggerFactory
import com.google.adk.kt.models.Model
import com.google.adk.kt.processors.AgentTransferProcessor
import com.google.adk.kt.processors.BasicRequestProcessor
import com.google.adk.kt.processors.CodeExecutionImageProcessor
import com.google.adk.kt.processors.CompactionRequestProcessor
import com.google.adk.kt.processors.ContentsProcessor
import com.google.adk.kt.processors.ContextCacheRequestProcessor
import com.google.adk.kt.processors.IdentityProcessor
import com.google.adk.kt.processors.InstructionsProcessor
import com.google.adk.kt.processors.LlmRequestProcessor
import com.google.adk.kt.processors.LlmResponseProcessor
import com.google.adk.kt.processors.OutputSchemaProcessor
import com.google.adk.kt.processors.RequestConfirmationProcessor
import com.google.adk.kt.processors.RequestCredentialProcessor
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
import com.google.adk.kt.workflow.NodeInterruptedException
import com.google.adk.kt.workflow.WorkflowConfigurationException
import com.google.adk.kt.workflow.isValidNodeName
import com.google.adk.kt.workflow.unsupportedLiveTaskDelegation
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.jvm.JvmStatic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
 * @property afterToolCallbacks List of callbacks to run after each tool call, skipped when a
 *   plugin's after-tool callback replaces the result.
 * @property inputSchema The input schema of the agent.
 * @property outputSchema The schema the agent's final response must conform to. When set, the agent
 *   asks the model to return JSON matching this schema and validates the final response against it
 *   before saving the parsed value to [outputKey]; in task mode, [outputKey] gets the parsed
 *   `finish_task` output instead. If the agent also has tools (including the framework's
 *   `transfer_to_agent` tool when sub-agents/peers are reachable), the schema is applied directly
 *   on models that take a response schema reliably together with tools; on other models (see
 *   [Model.capabilities]), the framework falls back to a `set_model_response` tool to collect the
 *   structured output.
 * @property outputKey The key in session state to store the final text response of the agent. When
 *   set, the agent's concatenated text output (excluding parts marked as thoughts) is written to
 *   `event.actions.stateDelta[outputKey]` on each final-response event, which the session service
 *   then merges into the session state.
 * @property onModelErrorCallbacks List of callbacks to run when a model call fails.
 * @property onToolErrorCallbacks List of callbacks to run when a tool call fails.
 * @param includeContents History the model sees. [IncludeContents.NONE] sends only the current
 *   turn; null means [IncludeContents.DEFAULT], except that a single-turn workflow node then runs
 *   without history.
 * @property mode How this agent converses as a workflow node or a sub-agent. In an agent tree run
 *   by a runner, it needs the tree's root to be an [LlmAgent] passed as a node, as with
 *   `App(rootNode = agent)`; otherwise building the runner throws. Null means [AgentMode.CHAT] for
 *   an agent with a parent or sub-agents or a root that runs as a workflow node, and
 *   [AgentMode.SINGLE_TURN] otherwise. A [AgentMode.TASK] agent gains a `finish_task` tool, and a
 *   task or single-turn sub-agent is offered to its parent as a tool rather than as a transfer
 *   target; it runs as a child node, so building the parent throws [IllegalArgumentException] if
 *   the sub-agent's name contains `.`.
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
    subAgents = subAgents.onEach(::requireValidChildNodeName),
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

  /** Whether this agent delegates to task sub-agents, which a live task cannot do yet. */
  internal val delegatesToTasks: Boolean = this.tools.any { it is TaskAgentTool }

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
      RequestCredentialProcessor(),
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

  internal val systemAfterTurnProcessors: List<LlmResponseProcessor> =
    listOf(CodeExecutionImageProcessor())

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
   * The mode this agent runs in as a node: any per-run override set for this agent (a runner sets
   * an invocation root's own mode, or chat when it has none), else [defaultMode].
   */
  internal fun nodeMode(context: InvocationContext): AgentMode =
    context.frameworkData.modeOverrideFor(this) ?: defaultMode()

  /**
   * Returns [context]'s invocation context for this agent's run in [mode]. The mode is keyed to
   * this agent, the node's isolation scope also reaches its sub-agents, and the node context lets
   * this agent's tools run sub-agents as child nodes. In a live run, a chat or task node also takes
   * over the run's live input, so it runs live.
   */
  private fun agentContext(context: Context, mode: AgentMode): InvocationContext {
    val ic = context.invocationContext
    val frameworkData =
      ic.frameworkData.copy(
        agentModeOverride = AgentModeOverride(agent = this, mode = mode),
        nodeContext = context,
        nodePath = context.nodePath,
        isolationScope = context.isolationScope,
      )
    if (mode != AgentMode.SINGLE_TURN) frameworkData.liveRun = ic.liveRun
    return ic.copy(frameworkData = frameworkData)
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
    var handedOff = false
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
          endRunAtHandoff(agentContext)
          handedOff = true
          false
        }
      )
    } finally {
      // Later nodes do not see the input; the handoff puts it back for the transfer targets.
      if (injected != null) {
        if (handedOff) context.handoffInput = injected
        context.invocationContext.session.events.remove(injected)
      }
    }
  }

  /**
   * Works on a delegated task until `finish_task` succeeds, live on its own connection in a live
   * run. A failed call's error reaches the model, which retries; the succeeding call's arguments
   * become the node's output and [outputKey] value. The node input, when given, is the task's
   * opening user turn if no delegating call carries it.
   */
  private fun runInTaskMode(context: Context, nodeInput: Any?): Flow<Any?> {
    // A task started by runNode escapes the check a live run makes of its graph before connecting.
    if (delegatesToTasks && context.invocationContext.liveRun != null) {
      return flow { throw unsupportedLiveTaskDelegation(name) }
    }
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

  /**
   * Holds a conversation that runs its task sub-agents. In a live run it talks live until the
   * caller closes the queue, and its connection stays closed while a delegated task runs.
   */
  private fun runInChatMode(context: Context): Flow<Any?> =
    runWithDelegations(context, agentContext(context, AgentMode.CHAT), finishTool = null)

  /**
   * Runs this agent on [agentContext] in rounds, live when it carries a live run, until the model
   * ends without delegating or, with a [finishTool], a `finish_task` call succeeds. Each round's
   * delegated tasks run as child nodes in their calls' own isolation scopes, and their answers
   * start the next round. A `transfer_to_agent` beside delegations waits for their answers, then
   * ends the run, and the dispatch loop runs its target as a node.
   */
  @OptIn(ExperimentalLiveApi::class)
  @Suppress("SuspendInFinally") // The record runs under NonCancellable.
  private fun runWithDelegations(
    context: Context,
    agentContext: InvocationContext,
    finishTool: FinishTaskTool?,
  ): Flow<Any?> = flow {
    // A child node's context starts with its parent's author.
    context.eventAuthor = name
    val toolsByName = tools.associateBy { it.name }
    val resumedTasks =
      answerPendingDelegations(context, toolsByName, ownScopeOnly = finishTool != null)
    val scope = context.isolationScope.takeIf { finishTool != null }
    unansweredDeferredTransfer(context.session.events, name, toolsByName, scope)?.let {
      emitDeferredTransfer(context, it)
      return@flow
    }
    // A run stopped after the tasks answered but before a callback's handoff hands off now.
    pendingDeferredHandoff(context.session.events, name, scope)?.let {
      emitDeferredHandoff(context, it)
      return@flow
    }
    // As after a round's tasks, a caller that hung up gets no new round.
    if (resumedTasks && agentContext.liveRequestQueue?.isClosed == true) return@flow
    val finish = finishTool?.let(::FinishState)
    val liveRun = agentContext.liveRun
    while (true) {
      val round = DelegationRound(liveRun != null)
      try {
        // Claimed before connecting, and held only while the round's connection is open.
        val claim = liveRun?.claimLiveNode(context.nodePath)
        try {
          // Only the run's first live session may resume the caller's; a later one starts anew.
          val roundContext =
            if (liveRun == null || liveRun.claimCallersSession()) {
              agentContext
            } else {
              agentContext.copy(runConfig = agentContext.runConfig?.forNewLiveSession())
            }
          val events = if (liveRun != null) runLive(roundContext) else runAsync(agentContext)
          emitAll(
            events.transformWhile { event ->
              if (round.live && event.content?.parts?.any { it.fileData != null } == true) {
                round.recordingIds += event.id
              }
              emitRoundEvent(context, event, round, finish, toolsByName)
            }
          )
        } finally {
          claim?.release()
        }
        // Only now has a live round's connection closed; see DelegationRound.heldEvents.
        // Recordings first, so the finishing response stays last and carries the output write.
        if (liveRun != null) emitRecordings(context, agentContext, liveRun, round)
        round.finishOutput?.let { context.updateState(checkNotNull(outputKey), it) }
        for (event in round.heldEvents) emitAuthored(context, event)
        if (liveRun != null) emitFinalAnswers(context, liveRun)
      } catch (e: NodeInterruptedException) {
        // A paused tool ends the round with its connection closed; the round's recordings and held
        // events go out first, so the caller gets them and the pause keeps the call that opened it.
        if (liveRun != null) emitRecordings(context, agentContext, liveRun, round)
        for (event in round.heldEvents) emitAuthored(context, event)
        throw e
      } finally {
        // A round that failed, or whose caller stopped collecting, emits nothing more.
        if (liveRun != null) {
          val appendsBefore = liveRun.appendCount
          if (!round.recordingsOut) storeAbandonedRecordings(context, agentContext, liveRun, round)
          val unused = liveRun.takeSentAnswers(context.nodePath)
          recordDeferredFinalAnswers(context, liveRun)
          liveRun.forgetLiveModelMediaMarks(context.nodePath)
          // One flush for what was stored above, since the runner skips its own on a cancel.
          if (liveRun.appendCount != appendsBefore) flushQuietly(liveRun)
        }
      }
      if (round.handedOff) endRunAtHandoff(agentContext)
      if (round.delegations.isEmpty()) return@flow
      for (call in round.delegations) emitTaskResponse(context, call)
      round.deferredTransfer?.let {
        emitDeferredTransfer(context, it)
        return@flow
      }
      round.deferredHandoff?.let {
        emitDeferredHandoff(context, it)
        return@flow
      }
      if (!canStartNextRound(round, agentContext)) return@flow
    }
  }

  /** What one round of [runWithDelegations], [live] or turn-based, has seen so far. */
  private class DelegationRound(val live: Boolean) {
    /** The task delegations the model made this round; empty until it makes some. */
    var delegations = emptyList<FunctionCall>()

    /** Long-running calls of this round that no response has answered yet. */
    val pendingLongRunningCalls = mutableSetOf<String>()

    /** The delegating step's long-running calls, until the caller or a response answers them. */
    val deferredCallsDue = mutableSetOf<String>()

    /** The delegating step's other calls that answer in that step, whose responses are due. */
    val eagerResponsesDue = mutableSetOf<String>()

    /** Whether the live delegating step waits on the caller's answers to its deferred calls. */
    var waitsOnTheCaller = false

    /** The `transfer_to_agent` call beside this round's delegations, run once they answer. */
    var deferredTransfer: FunctionCall? = null

    /** The target of a live handoff a callback set beside this round's delegations. */
    var deferredHandoff: String? = null

    /**
     * Whether a live round ended at a `transfer_to_agent` handoff, which the dispatch loop runs.
     */
    var handedOff = false

    /**
     * A live round's events held until its connection closes, so input the caller sends on seeing
     * them reaches the next live reader.
     */
    val heldEvents = mutableListOf<Event>()

    /** A live task's output for its `outputKey`, written once its round's recordings are out. */
    var finishOutput: Any? = null

    /** The ids of the recordings a live round's turn handed it, so none is emitted twice. */
    val recordingIds = mutableSetOf<String>()

    /** The recordings a live round saved once its connection closed, or null until it saves. */
    var recordings: List<Event>? = null

    /** Whether [recordings] are out; until then, a round that ends stores its audio itself. */
    var recordingsOut = false

    /** The ids of [recordings] the runner took, which the abandoned store then skips. */
    val recordingsHandedOn = mutableSetOf<String>()
  }

  /** Emits [event], or holds it while [round] is live, until the round's connection closes. */
  private suspend fun FlowCollector<Any?>.emitEnding(
    context: Context,
    round: DelegationRound,
    event: Event,
  ) {
    if (round.live) round.heldEvents += event else emitAuthored(context, event)
  }

  /**
   * Emits the stopped streams' final answers the live round's turn left on [liveRun], once their
   * first answers are out, so history shows each stream stopped.
   */
  private suspend fun FlowCollector<Any?>.emitFinalAnswers(context: Context, liveRun: LiveRun) {
    // One at a time, so the records a caller that stops never took stay for the finally to store.
    while (true) {
      val record = liveRun.takeDeferredFinalAnswer(context.nodePath) ?: break
      emitAuthored(context, record)
    }
  }

  /**
   * Records the stopped streams' final answers still left on [liveRun] for this node, straight to
   * the session and despite cancellation, when its round failed or its caller stopped collecting. A
   * failed append is logged rather than thrown, so it does not replace the round's exception.
   */
  private suspend fun recordDeferredFinalAnswers(context: Context, liveRun: LiveRun) {
    val answered = answeredCallIds(context.session.events)
    val owed =
      liveRun.takeDeferredFinalAnswers(context.nodePath).filter { record ->
        record.functionResponses().any { it.id in answered }
      }
    if (owed.isEmpty()) return
    withContext(NonCancellable) {
      try {
        for (record in owed) {
          // Scoped as the node runner would emit it, since it skips the runner.
          liveRun.append(
            record.copy(
              isolationScope = record.isolationScope ?: context.isolationScope,
              nodeInfo = (record.nodeInfo ?: NodeInfo()).copy(path = context.nodePath),
            )
          )
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        logger.warn { "Recording ${owed.size} stopped streams failed (${e::class.simpleName})." }
      }
    }
  }

  /**
   * Emits [event] under its own author, which the node runner stamps from [context], then makes
   * this agent the author again, so an event the user authored does not author what follows.
   */
  private suspend fun FlowCollector<Any?>.emitAuthored(context: Context, event: Event) {
    if (event.author.isNotEmpty()) context.eventAuthor = event.author
    emit(event)
    context.eventAuthor = name
  }

  /**
   * Saves the recordings a live [round] left once its connection closed and emits them. They are
   * kept on [round] first, so a caller that stops mid-emission leaves them to the abandoned store.
   */
  private suspend fun FlowCollector<Any?>.emitRecordings(
    context: Context,
    agentContext: InvocationContext,
    liveRun: LiveRun,
    round: DelegationRound,
  ) {
    for (event in saveUnsavedRecordings(context, agentContext, liveRun, round)) {
      emitAuthored(context, event)
      // Only once the emit returns, so one a cancel cut short is left to the abandoned store.
      round.recordingsHandedOn += event.id
    }
    round.recordingsOut = true
  }

  /**
   * Saves the audio a live round left unsaved when it ends, so the next live reader does not claim
   * it as its own, and waits for the saves its turn started but the round's end cut off. Both share
   * one time limit; a recording saved within it is kept, unless [round] already passed it on.
   */
  private suspend fun saveUnsavedRecordings(
    context: Context,
    agentContext: InvocationContext,
    liveRun: LiveRun,
    round: DelegationRound,
  ): List<Event> {
    // Not cancellable, since the audio is taken first; a cancel then leaves it on the round.
    val recordings =
      withContext(NonCancellable) {
        collectRecordings(context, agentContext, liveRun, round, "a live node's round")
      }
    round.recordings = recordings
    currentCoroutineContext().ensureActive()
    return recordings
  }

  /**
   * Saves the audio a live [round] left cached and waits for the saves its turns left stranded, all
   * within one time limit, returning the recordings saved by then in the order they were spoken.
   * [where] names the moment in the log.
   */
  // Only the async save reads cache and taken after they are made; round stays on this coroutine.
  @OptIn(ExperimentalAtomicApi::class)
  @Suppress("UnsafeCoroutineCrossing")
  private suspend fun collectRecordings(
    context: Context,
    agentContext: InvocationContext,
    liveRun: LiveRun,
    round: DelegationRound,
    where: String,
  ): List<Event> = coroutineScope {
    // The caches hold audio only when saveLiveBlob is set and the run has an artifact service.
    val cache = AudioCacheManager(agentContext.forAgent(this@LlmAgent))
    val taken = cache.take(flushUserAudio = true, flushModelAudio = true)
    val unsaved = async { cache.writeWithin(taken, where = "the end of $where") }
    val stranded = liveRun.takeStrandedSaves(context.nodePath)
    try {
      val timeout = AudioCacheManager.SAVE_TIMEOUT
      if (withTimeoutOrNull(timeout) { for (save in stranded) save.write.join() } == null) {
        logger.error { "Dropped live audio still saving at the end of $where after $timeout." }
      }
    } finally {
      for (save in stranded) save.cancel()
    }
    // Earlier turns' recordings first, in the order they were spoken.
    stranded.flatMap { it.saved.load() }.filter { it.id !in round.recordingIds } + unsaved.await()
  }

  /**
   * Stores the audio of a live [round] that failed or was cancelled, since it emits nothing more:
   * the recordings it saved, or else its earlier turns' stranded saves and its unsaved audio, which
   * the next live reader would otherwise claim. Events already stored or taken by the runner are
   * skipped, a failure is logged, and the caller flushes.
   */
  private suspend fun storeAbandonedRecordings(
    context: Context,
    agentContext: InvocationContext,
    liveRun: LiveRun,
    round: DelegationRound,
  ) {
    withContext(NonCancellable) {
      try {
        val stored = context.session.events.mapTo(mutableSetOf()) { it.id }
        val recordings =
          round.recordings
            ?: collectRecordings(
              context,
              agentContext,
              liveRun,
              round,
              "an abandoned live node round",
            )
        val runMetadata = agentContext.runConfig?.customMetadata.orEmpty()
        // The node runner's context, closest to the runner's, without this agent's mode override.
        val pluginContext = context.invocationContext
        for (event in recordings) {
          if (event.id in stored || event.id in round.recordingsHandedOn) continue
          // Scoped, tagged and passed through the plugins as the node runner and runner would.
          val scoped =
            event.copy(
              isolationScope = event.isolationScope ?: context.isolationScope,
              nodeInfo = (event.nodeInfo ?: NodeInfo()).copy(path = context.nodePath),
              customMetadata = (runMetadata + event.customMetadata.orEmpty()).ifEmpty { null },
            )
          val callbacks = pluginContext.pluginManager.onEventCallbacks
          val processed = runOnEventCallbacksPipeline(callbacks, pluginContext, scoped)
          // Merged again, as the runner does, in case a plugin dropped the run's metadata.
          liveRun.append(
            processed.copy(
              customMetadata = (runMetadata + processed.customMetadata.orEmpty()).ifEmpty { null }
            )
          )
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        logger.warn { "Storing an abandoned live round's audio failed (${e::class.simpleName})." }
      }
    }
  }

  /** Flushes what a round's end stored straight to [liveRun], logging rather than throwing. */
  private suspend fun flushQuietly(liveRun: LiveRun) {
    withContext(NonCancellable) {
      try {
        liveRun.flush()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        logger.warn { "Flushing a live round's last events failed (${e::class.simpleName})." }
      }
    }
  }

  /** The `finish_task` tool of a task run and the arguments of its latest call. */
  private class FinishState(val tool: FinishTaskTool) {
    var args: Map<String, Any?>? = null
  }

  /**
   * Emits [event] from a round and returns whether the round goes on. Once the model delegates
   * tasks, the round only keeps that step's tool responses; a task run ends with its successful
   * `finish_task` response, and a live round also ends at a `transfer_to_agent` handoff.
   */
  private suspend fun FlowCollector<Any?>.emitRoundEvent(
    context: Context,
    event: Event,
    round: DelegationRound,
    finish: FinishState?,
    toolsByName: Map<String, BaseTool>,
  ): Boolean {
    round.pendingLongRunningCalls += event.longRunningToolIds
    for (response in event.functionResponses()) {
      response.id?.let(round.pendingLongRunningCalls::remove)
      response.id?.let(round.eagerResponsesDue::remove)
      response.id?.let(round.deferredCallsDue::remove)
    }
    // The caller's answers go to the connection, not to this round's events.
    context.invocationContext.liveRun?.let {
      val sent = it.takeSentAnswers(context.nodePath)
      round.pendingLongRunningCalls -= sent
      round.deferredCallsDue -= sent
    }
    if (round.delegations.isNotEmpty()) {
      return emitDelegatingStepResponse(context, event, round)
    }
    if (finish != null) {
      when (val step = finishStepOf(event, finish, toolsByName)) {
        is FinishStep.Call -> {
          finish.args = step.args
          emitAuthored(context, event)
          return true
        }
        is FinishStep.Done -> {
          val key = outputKey?.takeIf { it.isNotEmpty() }
          if (key != null && step.output != null) {
            if (round.live) {
              round.finishOutput = step.output
            } else {
              context.updateState(key, step.output)
            }
          }
          emitEnding(context, round, event.copy(output = step.output))
          return false
        }
        null -> {}
      }
    }
    round.delegations = taskDelegationsIn(event, toolsByName)
    round.deferredTransfer = deferredTransferIn(event, toolsByName)
    // A turn-based run already stops at a handoff; a live one must stop here or it runs the target.
    val handoff = event.actions.transferToAgent?.takeIf { round.live }
    if (handoff != null) {
      // The round ends here, so the handoff is stored even when a callback set it on a partial.
      val step = event.copy(partial = false)
      if (round.delegations.isEmpty()) {
        round.handedOff = true
        emitEnding(context, round, step)
      } else {
        // Beside tasks it waits for their answers, as a deferred transfer_to_agent call does.
        round.deferredHandoff = handoff
        emitEnding(context, round, step.deferringHandoff(handoff))
      }
      return false
    }
    // A step whose other calls all defer their answers waits on the caller, as in ADK Python, so
    // the caller sees its calls now; any other delegating step is held until the round closes.
    val waitsOnTheCaller = round.live && callsOnlyDeferBesideTasks(event, toolsByName)
    if (round.delegations.isNotEmpty()) {
      round.waitsOnTheCaller = waitsOnTheCaller
      round.deferredCallsDue += event.longRunningToolIds
      round.eagerResponsesDue +=
        event
          .functionCalls()
          .filter { toolsByName[it.name] !is TaskAgentTool && it.id !in event.longRunningToolIds }
          .mapNotNull { it.id }
    }
    if (round.delegations.isNotEmpty() && !waitsOnTheCaller) {
      emitEnding(context, round, event)
    } else {
      emitAuthored(context, event)
    }
    return round.delegations.isEmpty() || hasEagerToolCalls(event, toolsByName)
  }

  /**
   * Emits [event] after the model delegated tasks, and returns whether the round goes on. It keeps
   * the tool responses of the step that delegated, and stops before the model starts another round.
   */
  private suspend fun FlowCollector<Any?>.emitDelegatingStepResponse(
    context: Context,
    event: Event,
    round: DelegationRound,
  ): Boolean {
    val branch = context.invocationContext.branch
    // A saved recording is the turn's history, not the model's next step, so it goes out now.
    if (round.live && event.content?.parts?.any { it.fileData != null } == true) {
      emitAuthored(context, event)
      return true
    }
    if (startsModelRound(event, branch, round)) return false
    // The handoff waits for the delegated tasks, as in Python, so its response comes later.
    var withoutTransfer = withoutTransferResponses(event)
    // So does a live handoff a callback set on the step's tool responses.
    val handoff = withoutTransfer.actions.transferToAgent
    val defers = round.live && handoff != null && isOwnStep(event, branch)
    if (defers) {
      round.deferredHandoff = handoff
      // Stored even when a callback set it on a partial, so a resume still hands off.
      withoutTransfer =
        withoutTransfer.copy(partial = false).deferringHandoff(checkNotNull(handoff))
    }
    // The turn hands over at a handoff, so the step ends there; the tasks run before it.
    val continues = !defers && (!isOwnStep(event, branch) || event.functionResponses().isEmpty())
    if (withoutTransfer.content == null || withoutTransfer.content.parts.isNotEmpty()) {
      val rejected = withFinishTaskRejected(withoutTransfer)
      // While the step waits on the caller with nothing held, what the model says reaches it now.
      if (round.waitsOnTheCaller && continues && round.heldEvents.isEmpty()) {
        emitAuthored(context, rejected)
      } else {
        emitEnding(context, round, rejected)
      }
    }
    return continues
  }

  /** Answers a deferred transfer [call], which hands off once this run ends. */
  private suspend fun FlowCollector<Any?>.emitDeferredTransfer(
    context: Context,
    call: FunctionCall,
  ) {
    emit(transferResponseEvent(name, call))
    endRunAtHandoff(context.invocationContext)
  }

  /** Hands off to [target], which a callback set beside tasks that have now answered. */
  private suspend fun FlowCollector<Any?>.emitDeferredHandoff(context: Context, target: String) {
    emit(Event(author = name, actions = EventActions(transferToAgent = target)))
    endRunAtHandoff(context.invocationContext)
  }

  /** Returns this step without its handoff, recording [target] for a resume to hand off to. */
  private fun Event.deferringHandoff(target: String): Event =
    copy(
      actions = actions.copy(transferToAgent = null),
      customMetadata = customMetadata.orEmpty() + (DEFERRED_HANDOFF_KEY to target),
    )

  /**
   * Returns [event] with any `finish_task` response replaced by an error, as Python does: a turn
   * that also delegated has not finished its task, so the model calls `finish_task` again later.
   */
  private fun withFinishTaskRejected(event: Event): Event {
    val content = event.content ?: return event
    if (content.parts.none { it.functionResponse?.name == FinishTaskTool.NAME }) return event
    val parts =
      content.parts.map { part ->
        val response = part.functionResponse
        if (response?.name != FinishTaskTool.NAME) part
        else
          part.copy(functionResponse = response.copy(response = FinishTaskTool.REJECTED_RESPONSE))
      }
    return event.copy(content = content.copy(parts = parts))
  }

  /** What an event of a task run means for finishing the task. */
  private sealed interface FinishStep {
    /** A `finish_task` call, whose arguments give the output once a response ends the task. */
    class Call(val args: Map<String, Any?>) : FinishStep

    /** The `finish_task` response that ends the task with [output]. */
    class Done(val output: Any?) : FinishStep
  }

  /**
   * Returns what [event] means for finishing the task of [finish], or null if it is unrelated. A
   * `finish_task` call beside a task delegation does not count, as in Python.
   */
  private fun finishStepOf(
    event: Event,
    finish: FinishState,
    toolsByName: Map<String, BaseTool>,
  ): FinishStep? {
    if (!event.partial) {
      val call = event.functionCalls().firstOrNull { it.name == FinishTaskTool.NAME }
      if (call != null && taskDelegationsIn(event, toolsByName).isEmpty()) {
        return FinishStep.Call(call.args)
      }
    }
    val args = finish.args
    if (args == null || !FinishTaskTool.isTerminalResponse(event)) return null
    return FinishStep.Done(finish.tool.taskOutput(args))
  }

  /**
   * Ends this agent's run at a handoff when [context] is resumable, so a resume does not rerun it.
   */
  private suspend fun FlowCollector<Any?>.endRunAtHandoff(context: InvocationContext) {
    if (!context.isResumable) return
    context.setAgentState(name, endOfAgent = true)
    emitEndOfAgent(context)
  }

  /**
   * Answers the delegations an earlier turn left unanswered; a task resumes only its own. Pauses
   * this run while one of those tasks waits in another invocation; a live run, which always starts
   * a new invocation, resumes such a task itself. Returns whether any task was answered.
   */
  private suspend fun FlowCollector<Any?>.answerPendingDelegations(
    context: Context,
    toolsByName: Map<String, BaseTool>,
    ownScopeOnly: Boolean,
  ): Boolean {
    val scope = if (ownScopeOnly) context.isolationScope else null
    val events = context.session.events
    val liveEvents = applyRewinds(events)
    val invocationId = context.invocationContext.invocationId
    val live = context.invocationContext.liveRun != null
    val (waiting, runnable) =
      unresolvedTaskDelegations(events, name, toolsByName, scope).partition {
        !live && taskWaitsInAnotherInvocation(liveEvents, it, invocationId)
      }
    for (call in runnable) emitTaskResponse(context, call)
    if (waiting.isNotEmpty()) throw NodeInterruptedException()
    return runnable.isNotEmpty()
  }

  /**
   * Whether another round may start once this round's tasks have answered. A turn-based round's
   * long-running call that is still unanswered pauses the turn, and so do reaching [maxSteps] and a
   * live caller closing the queue. A live round goes on with such a call left for the caller to
   * answer on its next connection, as ADK Python re-enters its live run after a delegation.
   */
  private fun canStartNextRound(round: DelegationRound, agentContext: InvocationContext): Boolean {
    if (!round.live && round.pendingLongRunningCalls.isNotEmpty()) return false
    if (agentContext.liveRequestQueue?.isClosed == true) return false
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
    emitAuthored(context, taskResponseEvent(call, output))
  }

  /**
   * Whether [event] starts a new model round on [branch] rather than finishing the current step.
   */
  private fun startsModelRound(event: Event, branch: String?, round: DelegationRound): Boolean =
    isOwnStep(event, branch) &&
      isModelContent(event, round) &&
      event.functionCalls().none { it.name == FunctionCall.REQUEST_CONFIRMATION_FUNCTION_CALL_NAME }

  /**
   * Whether [event] holds the model's content. Gemini Live sends the model's audio with no role, so
   * in a live [round] with no deferred call unanswered and no tool response still due, content
   * without a role and without tool answers counts too; before that, it does not end the step.
   */
  private fun isModelContent(event: Event, round: DelegationRound): Boolean {
    val content = event.content ?: return false
    if (content.role == Role.MODEL) return true
    return round.live &&
      round.deferredCallsDue.isEmpty() &&
      round.eagerResponsesDue.isEmpty() &&
      content.role == null &&
      content.parts.isNotEmpty() &&
      event.functionResponses().isEmpty()
  }

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
   * Returns [event], a final reply of an agent this single-turn node handed off to, marked as this
   * node's output and written to [outputKey], as ADK Python does when it runs the target inline.
   * The output is checked against [outputSchema] as this node's own reply is.
   */
  internal fun markHandoffOutput(event: Event): Event {
    val marked = markOutputIfFinalTurn(event, event.author)
    val output = validateOutput(marked.output ?: return marked) ?: return marked
    val checked = if (output == marked.output) marked else marked.copy(output = output)
    val key = outputKey?.takeIf { it.isNotEmpty() } ?: return checked
    val delta = (checked.actions.stateDelta + (key to output)).toMutableMap()
    return checked.copy(actions = checked.actions.copy(stateDelta = delta))
  }

  /**
   * Attaches the node output to [event] when it is [author]'s final model turn and has an output,
   * and sets [NodeInfo.messageAsOutput] when no [outputSchema] is configured, clearing it
   * otherwise, as a single-turn target may have set it. Otherwise returns [event] itself.
   */
  private fun markOutputIfFinalTurn(event: Event, author: String = name): Event {
    val content = event.content
    if (event.author != author || !event.isFinalResponse) return event
    if (event.functionCalls().isNotEmpty() || content == null) return event
    if (content.role != Role.MODEL) return event
    val output = outputValueOf(content) ?: return event
    if (outputSchema != null) {
      return event.copy(output = output, nodeInfo = event.nodeInfo?.copy(messageAsOutput = false))
    }
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
      if (!transferred && event.author == name && event.actions.transferToAgent != null) {
        transferred = true
        if (context.isResumable) {
          context.setAgentState(name, endOfAgent = true)
          emitEndOfAgent(context)
        }
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

  /** Whether this agent runs as a chat or task node, whose loop follows handoffs itself. */
  internal fun runsInNodeLoop(context: InvocationContext): Boolean =
    when (context.frameworkData.modeOverrideFor(this)) {
      AgentMode.CHAT,
      AgentMode.TASK -> true
      else -> false
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
        lastEvent?.actions?.endOfAgent != true &&
        (!runsInNodeLoop(context) || lastEvent?.actions?.transferToAgent == null)
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
   *   schema, and the parsed value is stored on success: a map for a schema that describes only
   *   objects, otherwise whatever the JSON holds, or the text itself for a schema that takes a
   *   string. A `null` answer the schema allows leaves [outputKey] unchanged. A blank result is
   *   skipped (it represents an empty final stream chunk). If parsing or validation fails, the raw
   *   text is stored instead and an error is logged. Mirrors `validate_schema` in the Python ADK.
   *   Note this best-effort fallback is the direct-schema path only; the `set_model_response`
   *   workaround instead returns validation errors to the model so it can call the tool again, and
   *   saves nothing until a call validates (see
   *   [com.google.adk.kt.tools.SetModelResponseTool.run]).
   * - On a single-turn workflow node run, the text is parsed as the node's output is, and a schema
   *   mismatch or a `null` answer saves nothing.
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
        else -> {
          // A schema that may describe a non-object is parsed as on a node run.
          val parsed =
            if (schema.describesOnlyObjects()) SchemaUtils.validateOutputSchema(result, schema)
            else runCatching { SchemaUtils.parseModelOutput(schema, result) }
          val value = parsed.getOrElse { cause ->
            // The cause's message can quote the model's output, so only its type is logged.
            logger.error {
              "LlmAgent output for outputKey '$outputKey' did not match the outputSchema " +
                "(${cause::class.simpleName}). Saving raw output to state."
            }
            result
          }
          // State values are never null, so a null answer saves nothing, as on a node run.
          value ?: return
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

    /**
     * Throws [IllegalArgumentException] if [agent] runs as a child node, as a task or single-turn
     * sub-agent does, and its name can't be a node name. Runs before the parent links its
     * sub-agents, so a rejected parent leaves them free to reuse.
     */
    private fun requireValidChildNodeName(agent: BaseAgent) {
      if (!(agent as? LlmAgent)?.mode.runsAsTool) return
      require(isValidNodeName(agent.name)) {
        "Task or single-turn sub-agent '${agent.name}' runs as a node, so its name must not" +
          " contain '.'."
      }
    }
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
