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

import com.google.adk.kt.SchemaUtils
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
import com.google.adk.kt.processors.ContentsProcessor
import com.google.adk.kt.processors.InstructionsProcessor
import com.google.adk.kt.processors.LlmRequestProcessor
import com.google.adk.kt.processors.LlmResponseProcessor
import com.google.adk.kt.processors.OutputSchemaProcessor
import com.google.adk.kt.processors.RequestConfirmationProcessor
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.tools.Toolset
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.GenerateContentConfig
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * LLM-based Agent.
 *
 * When this agent is a sub-agent and the parent transfers control to it via `transfer_to_agent`,
 * the runner decides who handles the *next* user turn based on the [disallowTransferToParent] /
 * [disallowTransferToPeers] flags inherited from `BaseAgent` - see those flags for the full
 * dispatch rules.
 *
 * @property model The model to use for the agent.
 * @property tools Tools available to this agent.
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
 *   object schemas are supported (matching the Java ADK; the Python ADK additionally supports
 *   list/primitive output schemas). When set, the agent asks the model to return JSON matching this
 *   schema and validates the final response against it before saving it to [outputKey]. If the
 *   agent also has tools (including the framework's `transfer_to_agent` tool when sub-agents/peers
 *   are reachable), the schema is applied directly on models that support a response schema
 *   together with tools; on models that do not (e.g. Gemini 2.x), the framework falls back to a
 *   `set_model_response` tool to collect the structured output.
 * @property outputKey The key in session state to store the final text response of the agent. When
 *   set, the agent's concatenated text output (excluding parts marked as thoughts) is written to
 *   `event.actions.stateDelta[outputKey]` on each final-response event, which the session service
 *   then merges into the session state.
 * @property onModelErrorCallbacks List of callbacks to run when a model call fails.
 * @property onToolErrorCallbacks List of callbacks to run when a tool call fails.
 * @property includeContents Controls how prior conversation history is included in the model
 *   request. Defaults to [IncludeContents.DEFAULT], which includes the relevant conversation
 *   history. Set to [IncludeContents.NONE] to exclude prior history; the model then receives only
 *   the current turn (the most recent user input or other-agent reply, plus any tool
 *   calls/responses produced within that turn). The system instruction and tools are preserved in
 *   both modes.
 * @property maxSteps The maximum number of steps this agent runs within a single invocation, where
 *   a step is one model call plus any tool calls or transfers it triggers. Once the cap is reached
 *   the agent stops emitting further events, even if it has not yet produced a final response.
 *   Defaults to `null`, meaning no cap: the agent keeps stepping until it produces a final response
 *   or the invocation otherwise ends (which can run unbounded). Mirrors the Java ADK
 *   `LlmAgent.maxSteps`.
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
  val tools: List<BaseTool> = emptyList(),
  val toolsets: List<Toolset> = emptyList(),
  val generateContentConfig: GenerateContentConfig? = null,
  val instruction: Instruction? = null,
  val staticInstruction: Content? = null,
  val beforeModelCallbacks: List<BeforeModelCallback> = emptyList(),
  val afterModelCallbacks: List<AfterModelCallback> = emptyList(),
  val beforeToolCallbacks: List<BeforeToolCallback> = emptyList(),
  val afterToolCallbacks: List<AfterToolCallback> = emptyList(),
  val inputSchema: Schema? = null,
  val outputSchema: Schema? = null,
  val outputKey: String? = null,
  val onModelErrorCallbacks: List<OnModelErrorCallback> = emptyList(),
  val onToolErrorCallbacks: List<OnToolErrorCallback> = emptyList(),
  val includeContents: IncludeContents = IncludeContents.DEFAULT,
  val maxSteps: Int? = null,
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
      ContentsProcessor(),
      AgentTransferProcessor(),
      OutputSchemaProcessor(),
    )

  internal val systemAfterTurnProcessors: List<LlmResponseProcessor> = emptyList()

  private fun getTransferToAgentOrNull(event: Event, fromAgent: String): BaseAgent? {
    if (event.author == fromAgent) {
      val transferTo = event.actions.transferToAgent
      if (transferTo != null && transferTo != fromAgent) {
        return findAgent(transferTo)
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

  override fun runAsyncImpl(context: InvocationContext): Flow<Event> = flow {
    val agentState = context.agentStates[name]

    if (agentState != null) {
      val agentToTransfer = getSubagentToResume(context)
      if (agentToTransfer != null) {
        agentToTransfer.runAsync(context).collect { emit(it) }
        context.setAgentState(name, endOfAgent = true)
        emitEndOfAgent(context)
        return@flow
      }
    }

    // On a resumable invocation that paused on a long-running tool call, do not emit the
    // end-of-agent marker so a follow-up `runAsync(newMessage = userFunctionResponse(...))` can
    // resume the same agent. We detect a pause both per-event (during `executeTurns`) and, as a
    // backstop, from the session's last two events afterwards. Mirrors Python ADK
    // `agents/llm_agent.py:522-541`.
    var shouldPause = false
    executeTurns(context).collect { event ->
      maybeSaveOutputToState(event)
      emit(event)
      if (context.shouldPauseInvocation(event)) {
        shouldPause = true
      }
    }
    if (shouldPause) return@flow

    if (context.isResumable && context.agent == this@LlmAgent) {
      val events = context.getEvents(currentInvocation = true, currentBranch = true)
      if (events.takeLast(2).any { context.shouldPauseInvocation(it) }) {
        return@flow
      }
      context.setAgentState(name, endOfAgent = true)
      emitEndOfAgent(context)
    }
  }

  private fun executeTurns(context: InvocationContext): Flow<Event> = flow {
    var lastEvent: Event?
    var stepsCompleted = 0
    do {
      lastEvent = null
      LlmAgentTurn(this@LlmAgent, context, systemBeforeTurnProcessors, systemAfterTurnProcessors)
        .execute()
        .collect { event ->
          lastEvent = event
          emit(event)
        }
      stepsCompleted++
      // Enforce the per-agent step cap. Checked after running the step so that maxSteps == N runs
      // exactly N steps, mirroring the Java ADK BaseLlmFlow.run loop.
      if (maxSteps != null && stepsCompleted >= maxSteps) {
        logger.debug { "Ending agent execution for $name: reached maxSteps=$maxSteps." }
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
   *   [Part.thought] is `true` are ignored.
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
   *
   * NOTE on long-running tool events: when an event contains text alongside a long-running function
   * call (i.e. [Event.longRunningToolIds] is non-empty), Kotlin's [Event.isFinalResponse] returns
   * `true` (matching Python's `is_final_response()`), so the text portion is saved. The Java ADK's
   * `Event.finalResponse()` returns `false` for the same shape (it has no long-running-tool special
   * case), so it would not save. Kotlin intentionally follows Python here.
   */
  private fun maybeSaveOutputToState(event: Event) {
    if (event.author != name) {
      logger.debug { "Skipping output save for agent $name: event authored by ${event.author}" }
      return
    }
    val outputKey = outputKey?.takeIf { it.isNotEmpty() } ?: return
    if (!event.isFinalResponse) return
    val parts = event.content?.parts ?: return

    // An empty `parts` list is handled by the `textParts.isEmpty()` guard below (filtering an empty
    // list yields an empty list), so no separate `parts.isEmpty()` check is needed.
    val textParts = parts.filter { it.text != null && it.thought != true }
    if (textParts.isEmpty()) return

    val result = textParts.joinToString(separator = "") { it.text.orEmpty() }

    val output: Any =
      if (outputSchema != null) {
        // An empty/whitespace result is an empty final stream chunk; do not parse it as JSON.
        if (result.isBlank()) return
        SchemaUtils.validateOutputSchema(result, outputSchema).getOrElse { cause ->
          logger.error(cause) {
            "LlmAgent output for outputKey '$outputKey' did not match the outputSchema. " +
              "Saving raw output to state."
          }
          result
        }
      } else {
        result
      }
    event.actions.stateDelta[outputKey] = output
  }

  private companion object {
    private val logger = LoggerFactory.getLogger(LlmAgent::class)
  }
}

/**
 * A tool that takes a photo by delegating to a custom, injected [capture] lambda.
 *
 * This allows integrating with real camera hardware or providing simulated image frames.
 *
 * @property capture A suspending lambda that performs the photo capture and returns a map
 *   containing image data.
 */
class TakePhotoTool(private val capture: suspend () -> Map<String, Any>) :
  BaseTool(
    name = "take_photo",
    description =
      "Takes a photo from the camera by delegating to a custom, injected capture lambda.",
  ) {

  override fun declaration(): FunctionDeclaration {
    return FunctionDeclaration(
      name = name,
      description = description,
      parameters = Schema(type = Type.OBJECT, properties = emptyMap()),
    )
  }

  override suspend fun run(context: ToolContext, args: Map<String, Any>): Any {
    return capture()
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
