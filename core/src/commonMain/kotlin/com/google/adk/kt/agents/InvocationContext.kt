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

package com.google.adk.kt.agents

import com.google.adk.kt.artifacts.ArtifactService
import com.google.adk.kt.callbacks.CallbackChoice
import com.google.adk.kt.callbacks.runAfterToolCallbacksPipeline
import com.google.adk.kt.callbacks.runBeforeToolCallbacksPipeline
import com.google.adk.kt.callbacks.runOnToolErrorCallbacksPipeline
import com.google.adk.kt.collections.concurrentMutableMapOf
import com.google.adk.kt.events.Event
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.events.ToolConfirmation
import com.google.adk.kt.ids.Uuid
import com.google.adk.kt.memory.MemoryService
import com.google.adk.kt.plugins.PluginManager
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.sessions.SessionService
import com.google.adk.kt.telemetry.TelemetryAttributes
import com.google.adk.kt.telemetry.TelemetryConfig
import com.google.adk.kt.telemetry.TracePayloadFormatter
import com.google.adk.kt.telemetry.withSpan
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.FunctionTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import kotlin.jvm.Volatile
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * An invocation context represents the data of a single invocation of an agent.
 *
 * An invocation:
 * 1. Starts with a user message and ends with a final response.
 * 2. Can contain one or multiple agent calls.
 * 3. Is handled by runner.run_async().
 *
 * An invocation runs an agent until it does not request to transfer to another agent.
 *
 * An agent call:
 * 1. Is handled by agent.run().
 * 2. Ends when agent.run() ends.
 *
 * An LLM agent call is an agent with a BaseLLMFlow. An LLM agent call can contain one or multiple
 * steps.
 *
 * An LLM agent runs steps in a loop until:
 * 1. A final response is generated.
 * 2. The agent transfers to another agent.
 * 3. The end_invocation is set to true by any callbacks or tools.
 *
 * A step:
 * 1. Calls the LLM only once and yields its response.
 * 2. Calls the tools and yields their responses if requested.
 *
 * The summarization of the function response is considered another step, since it is another llm
 * call. A step ends when it's done calling llm and tools, or if the end_invocation is set to true
 * at any time.
 *
 * ```
 *    ┌─────────────────────── invocation ──────────────────────────┐
 *    ┌──────────── llm_agent_call_1 ────────────┐ ┌─ agent_call_2 ─┐
 *    ┌──── step_1 ────────┐ ┌───── step_2 ──────┐
 *    [call_llm] [call_tool] [call_llm] [transfer]
 * ```
 */
data class InvocationContext(
  // Required fields
  /** The current session of this invocation context. Readonly. */
  val session: Session,
  /** Configurations for live agents under this invocation. */
  val runConfig: RunConfig? = null,
  /** The current agent of this invocation context. Readonly. */
  val agent: BaseAgent,
  /**
   * The branch of the invocation context.
   *
   * The format is like agent_1.agent_2.agent_3, where agent_1 is the parent of agent_2, and agent_2
   * is the parent of agent_3.
   *
   * Branch is used when multiple sub-agents shouldn't see their peer agents' conversation history.
   */
  val branch: String? = null,
  /** The id of this invocation context. Readonly. */
  val invocationId: String = "e-" + Uuid.random(),

  // Services
  val artifactService: ArtifactService? = null,
  val memoryService: MemoryService? = null,
  val sessionService: SessionService? = null,

  // Configs
  /**
   * Optional resumability configuration for this invocation.
   *
   * The opt-in requirement is carried by `ResumabilityConfig`'s constructor (which is annotated
   * `@ExperimentalResumabilityFeature`), so any code that constructs a `ResumabilityConfig` to pass
   * here -- whether via the primary constructor or `copy(...)` -- requires
   * `@OptIn(ExperimentalResumabilityFeature::class)`.
   */
  val resumabilityConfig: ResumabilityConfig? = null,

  // State
  /** The user content that started this invocation. Readonly. */
  val userContent: Content? = null,

  // Mutable state
  /** The state of the agent for this invocation. */
  val agentStates: MutableMap<String, TypedData> = concurrentMutableMapOf(),
  /** The end of agent status for each agent in this invocation. */
  val endOfAgents: MutableMap<String, Boolean> = concurrentMutableMapOf(),
  /** Extra tools injected dynamically during invocation (e.g., by SequentialAgent). */
  val extraTools: MutableMap<String, BaseTool> = concurrentMutableMapOf(),
  /**
   * Whether to end this invocation.
   *
   * Set to True in callbacks or tools to terminate this invocation.
   */
  @Volatile var isEndOfInvocation: Boolean = false,

  /**
   * Whether this invocation is paused.
   *
   * @deprecated This flag is no longer set or read by the framework. Pause detection on a resumable
   *   invocation is now computed per-event via [shouldPauseInvocation]; long-running tool pauses
   *   are signalled via `Event.isFinalResponse` on the function-call event (which carries
   *   `Event.longRunningToolIds`). Scheduled for removal in a future release.
   */
  @Deprecated(
    message =
      "isPaused is no longer set or read by the framework. Use shouldPauseInvocation or " +
        "Event.isFinalResponse / Event.longRunningToolIds to detect long-running pauses.",
    level = DeprecationLevel.WARNING,
  )
  @Volatile
  var isPaused: Boolean = false,

  /** The manager for keeping track of plugins in this invocation. */
  val pluginManager: PluginManager = PluginManager(),
) {

  /** Returns whether the current invocation is resumable. */
  val isResumable: Boolean
    get() = resumabilityConfig?.isResumable == true

  /**
   * Creates a new InvocationContext for a child agent, derived from this context. Appends the given
   * agent's name to the branch path.
   *
   * @param childAgent The new agent for the branched context.
   * @return The new InvocationContext.
   */
  fun branch(childAgent: BaseAgent): InvocationContext {
    val newBranchPath =
      if (this.branch.isNullOrEmpty()) childAgent.name else "${this.branch}.${childAgent.name}"
    return this.copy(branch = newBranchPath, agent = childAgent)
  }

  /** Set state of an agent explicitly. Does not implicitly initialize. */
  fun setAgentState(agentName: String, agentState: TypedData? = null, endOfAgent: Boolean = false) {
    if (endOfAgent) {
      endOfAgents[agentName] = true
      agentStates.remove(agentName)
    } else if (agentState != null) {
      agentStates[agentName] = agentState
      endOfAgents[agentName] = false
    } else {
      endOfAgents.remove(agentName)
      agentStates.remove(agentName)
    }
  }

  /** Resets the state of all sub-agents of the given agent recursively. */
  fun resetSubAgentStates(agentName: String) {
    val targetAgent = agent.findAgent(agentName) ?: return
    for (subAgent in targetAgent.subAgents) {
      setAgentState(subAgent.name, null, false) // Clear state
      resetSubAgentStates(subAgent.name) // Recurse
    }
  }

  /**
   * Populates agent states for the current invocation if it is resumable.
   *
   * For history events that contain agent state information, set the agentState and endOfAgent of
   * the agent that generated the event.
   *
   * For non-workflow agents, also set an initial agentState if it has already generated some
   * contents.
   */
  suspend fun populateInvocationAgentStates() {
    if (!isResumable) return
    val events = getEvents(currentInvocation = true)
    for (event in events) {
      val author = event.author
      val agentState = event.actions.agentState
      if (event.actions.endOfAgent) {
        endOfAgents[author] = true
        agentStates.remove(author)
      } else if (agentState != null) {
        agentStates[author] = agentState
        endOfAgents[author] = false
      } else if (author != "user" && event.content != null && !agentStates.containsKey(author)) {
        agentStates[author] = TypedData.MapValue(emptyMap())
        endOfAgents[author] = false
      }
    }
  }

  /**
   * Returns the events from the current session.
   *
   * @param currentInvocation Whether to filter the events by the current invocation.
   * @param currentBranch Whether to filter the events by the current branch.
   * @return A list of events from the current session.
   */
  suspend fun getEvents(
    currentInvocation: Boolean = false,
    currentBranch: Boolean = false,
  ): List<Event> {
    val sessionService = this.sessionService
    var results: List<Event> =
      if (sessionService != null) {
        sessionService.listEvents(session.key).events
      } else {
        session.events
      }
    if (currentInvocation) {
      results = results.filter { it.invocationId == this.invocationId }
    }
    if (currentBranch) {
      results = results.filter { it.branch == this.branch || it.branch == null }
    }
    return results
  }

  /**
   * Returns whether to pause the invocation right after this event.
   *
   * "Pausing" an invocation is different from "ending" an invocation. A paused invocation can be
   * resumed later, while an ended invocation cannot.
   *
   * Pausing the current agent's run will also pause all the agents that depend on its execution,
   * i.e. the subsequent agents in a workflow, and the current agent's ancestors, etc.
   *
   * Note that parallel sibling agents won't be affected, but their common ancestors will be paused
   * after all the non-blocking sub-agents finished running.
   *
   * Both of the following conditions must hold to pause an invocation:
   * 1. The app is resumable ([isResumable]).
   * 2. The current event has a long running function call (this includes tool-confirmation / HITL
   *    requests, which are emitted as a synthetic long-running `adk_request_confirmation` call).
   *
   * Mirrors Python ADK 1.x `InvocationContext.should_pause_invocation`. (Pausing is tied to
   * resumability: only a resumable app checkpoints the paused point so a later turn can resume it.)
   *
   * @param event The current event.
   * @return Whether to pause the invocation right after this event.
   */
  fun shouldPauseInvocation(event: Event): Boolean {
    if (!isResumable) return false
    if (event.longRunningToolIds.isEmpty()) return false
    val functionCalls = event.functionCalls()
    if (functionCalls.isEmpty()) return false
    return functionCalls.any { it.id != null && it.id in event.longRunningToolIds }
  }

  /**
   * Processes a list of function calls by executing them efficiently and safely.
   *
   * This handles parallel execution, argument conversion, error processing, and merging all
   * resulting [EventActions] and standard outputs.
   *
   * @param functionCalls List of [FunctionCall] instances to process.
   * @param tools A mapping from tool name to the available [BaseTool] instance.
   * @param filters Optional set of specific function call IDs to process; others will be skipped.
   * @param toolConfirmations Map of user-approved confirmations per function call ID.
   * @return A single merged [Event] containing all responses and actions, or `null` if no tools
   *   executed.
   */
  @Suppress("UnsafeCoroutineCrossing")
  suspend fun handleFunctionCalls(
    functionCalls: List<FunctionCall>,
    tools: Map<String, BaseTool>,
    filters: Set<String> = emptySet(),
    toolConfirmations: Map<String, ToolConfirmation>? = null,
  ): Event? = coroutineScope {
    // Filter function calls
    val filteredCalls =
      if (filters.isEmpty()) {
        functionCalls
      } else {
        functionCalls.filter { it.id == null || it.id in filters }
      }
    if (filteredCalls.isEmpty()) {
      return@coroutineScope null
    }

    val functionResponseEvents =
      filteredCalls
        .map { functionCall ->
          async {
            executeSingleFunctionCall(functionCall, tools, toolConfirmations?.get(functionCall.id))
          }
        }
        .awaitAll()
        .filterNotNull()
    if (functionResponseEvents.isEmpty()) {
      return@coroutineScope null
    }

    return@coroutineScope mergeParallelFunctionResponseEvents(functionResponseEvents)
  }

  /** Executes a single function call synchronously and builds a corresponding response event. */
  suspend fun executeSingleFunctionCall(
    functionCall: FunctionCall,
    tools: Map<String, BaseTool>,
    toolConfirmation: ToolConfirmation? = null,
  ): Event? {
    val tool =
      tools[functionCall.name]
        ?: throw IllegalArgumentException("BaseTool ${functionCall.name} not found")
    val llmAgent = this.agent as? LlmAgent
    val toolContext =
      ToolContext(
        invocationContext = this,
        functionCallId = functionCall.id,
        toolConfirmation = toolConfirmation,
      )

    val safeArgs = safeCastToMapStringAny(functionCall.args)
    val responseEventId = Uuid.random()

    // 1. Run before tool callbacks
    val beforeResult = runBeforeToolCallbacks(llmAgent, tool, safeArgs, toolContext)
    val currentArgs =
      when (beforeResult) {
        is CallbackChoice.Break ->
          return buildResponseEvent(tool, beforeResult.value, toolContext, responseEventId)
        is CallbackChoice.Continue -> beforeResult.value
      }

    // 2. Execute tool.
    var toolResult: Any =
      withSpan("execute_tool [${tool.name}]") { span ->
        span[TelemetryAttributes.GEN_AI_TOOL_NAME] = tool.name
        span[TelemetryAttributes.GEN_AI_TOOL_TYPE] =
          if (tool is FunctionTool) "function" else "unknown"
        span[TelemetryAttributes.GCP_VERTEX_AGENT_INVOCATION_ID] =
          this@InvocationContext.invocationId
        this@InvocationContext.session.key.id?.let {
          span[TelemetryAttributes.GCP_VERTEX_AGENT_SESSION_ID] = it
        }
        span[TelemetryAttributes.GCP_VERTEX_AGENT_EVENT_ID] = responseEventId

        if (TelemetryConfig.captureMessageContent) {
          span[TelemetryAttributes.GCP_VERTEX_AGENT_TOOL_CALL_ARGS] =
            TracePayloadFormatter.format(currentArgs)
        }

        try {
          tool.run(toolContext, currentArgs)
        } catch (e: Exception) {
          val recoveredResult =
            runErrorBaseToolCallbacks(llmAgent, tool, currentArgs, toolContext, e)
          recoveredResult ?: throw e
        }
      }

    // Run before `runAfterToolCallbacks` to keep `Unit` from being wrapped as `{result: Unit}`.
    // A long-running tool returning `Unit` means "no response yet": suppress the FR event so the
    // FC event (which carries `longRunningToolIds`) terminates the turn. See
    // [BaseTool.isLongRunning].
    if (tool.isLongRunning && toolResult === Unit) {
      return null
    }
    // For regular tools, coerce `Unit` to `{}` so the wire form is clean.
    if (toolResult === Unit) {
      toolResult = emptyMap<String, Any>()
    }

    // 3. Run after tool callbacks
    val afterResult = runAfterToolCallbacks(llmAgent, tool, currentArgs, toolContext, toolResult)
    if (afterResult != null) {
      toolResult = afterResult
    }

    // Build response event
    return buildResponseEvent(tool, toolResult, toolContext, responseEventId)
  }

  private fun buildResponseEvent(
    tool: BaseTool,
    toolResult: Any?,
    toolContext: ToolContext,
    eventId: String,
  ): Event {
    return Event(
      invocationId = this.invocationId,
      author = this.agent.name,
      content =
        Content(
          role = Role.USER,
          parts =
            listOf(
              Part(
                functionResponse =
                  FunctionResponse(
                    name = tool.name,
                    response = toFinalResponseMap(toolResult),
                    id = toolContext.functionCallId,
                  )
              )
            ),
        ),
      actions = toolContext.actions,
      branch = this.branch,
    )
  }

  private fun mergeParallelFunctionResponseEvents(events: List<Event>): Event {
    if (events.isEmpty()) throw IllegalArgumentException("No events to merge")
    if (events.size == 1) return events.single()

    val mergedContent =
      Content(role = "user", parts = events.mapNotNull { it.content?.parts }.flatten())

    val mergedActions = events.fold(EventActions()) { acc, event -> acc.mergeWith(event.actions) }
    // Use the first event as the "base" for common attributes
    return events.first().copy(content = mergedContent, actions = mergedActions)
  }

  private suspend fun runBeforeToolCallbacks(
    llmAgent: LlmAgent?,
    tool: BaseTool,
    args: Map<String, Any>,
    toolContext: ToolContext,
  ): CallbackChoice<Map<String, Any>, Map<String, Any>> {
    if (llmAgent == null) return CallbackChoice.Continue(args)
    val allBeforeCallbacks = pluginManager.beforeToolCallbacks + llmAgent.beforeToolCallbacks
    return runBeforeToolCallbacksPipeline(allBeforeCallbacks, toolContext, tool, args)
  }

  /**
   * Finds the function call event in the current invocation that matches the function response id.
   */
  suspend fun findMatchingFunctionCall(functionResponseEvent: Event): Event? {
    val functionResponses = functionResponseEvent.functionResponses()
    if (functionResponses.isEmpty()) {
      return null
    }

    val targetId = functionResponses.first().id ?: return null
    val events = getEvents(currentInvocation = true)

    // Search backwards from the event before the current response event.
    return events.findLast { event -> event.functionCalls().any { it.id == targetId } }
  }

  private suspend fun runAfterToolCallbacks(
    llmAgent: LlmAgent?,
    tool: BaseTool,
    args: Map<String, Any>,
    toolContext: ToolContext,
    toolResult: Any?,
  ): Any? {
    if (llmAgent == null) return null

    val allAfterCallbacks = pluginManager.afterToolCallbacks + llmAgent.afterToolCallbacks
    return runAfterToolCallbacksPipeline(
      allAfterCallbacks,
      toolContext,
      tool,
      args,
      toFinalResponseMap(toolResult),
    )
  }

  private suspend fun runErrorBaseToolCallbacks(
    llmAgent: LlmAgent?,
    tool: BaseTool,
    args: Map<String, Any>,
    toolContext: ToolContext,
    error: Exception,
  ): Any? {
    if (llmAgent == null) return null
    val allOnToolErrorCallbacks = pluginManager.onToolErrorCallbacks + llmAgent.onToolErrorCallbacks
    when (
      val result =
        runOnToolErrorCallbacksPipeline(allOnToolErrorCallbacks, toolContext, tool, args, error)
    ) {
      is CallbackChoice.Break -> return result.value
      is CallbackChoice.Continue -> return null
    }
  }

  /**
   * Coerces an arbitrary tool payload into the `Map<String, Any>` shape required by both
   * [FunctionResponse.response] and the after-tool callback pipeline:
   * - A payload that is already a [Map] is fed through [safeCastToMapStringAny] (which drops
   *   non-string keys and null values).
   * - Any other value is wrapped in a single-entry `{ RESULT_KEY -> value }` map (the spec requires
   *   the response to be a dict).
   *
   * Centralizing this rule means [buildResponseEvent] and [runAfterToolCallbacks] don't need to
   * each implement the wrap-and-cast step.
   */
  private fun toFinalResponseMap(payload: Any?): Map<String, Any> =
    safeCastToMapStringAny(
      if (payload !is Map<*, *>) mapOf(BaseTool.RESULT_KEY to payload) else payload
    )

  private fun safeCastToMapStringAny(value: Any?): Map<String, Any> {
    if (value !is Map<*, *>) return emptyMap()
    return value.entries
      .mapNotNull { entry ->
        val key = entry.key as? String ?: return@mapNotNull null
        val value = entry.value ?: return@mapNotNull null
        key to value
      }
      .toMap()
  }
}
