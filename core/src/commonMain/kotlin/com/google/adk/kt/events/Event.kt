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

package com.google.adk.kt.events

import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.ids.Uuid
import com.google.adk.kt.models.CacheMetadata
import com.google.adk.kt.serialization.LenientEpochMillisSerializer
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.types.CitationMetadata
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FinishReason
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.GroundingMetadata
import com.google.adk.kt.types.InteractionStatus
import com.google.adk.kt.types.LiveServerSessionResumptionUpdate
import com.google.adk.kt.types.Transcription
import com.google.adk.kt.types.TurnCompleteReason
import com.google.adk.kt.types.UsageMetadata
import com.google.adk.kt.types.VoiceActivity
import com.google.adk.kt.workflow.NodeInfo
import com.google.adk.kt.workflow.NodeInfoNullIfEmptySerializer
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic
import kotlin.time.Clock
import kotlinx.serialization.Contextual
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

/**
 * Represents an event in a session.
 *
 * @property id The event id.
 * @property invocationId Id of the invocation that this event belongs to.
 * @property author The author of the event, such as the name of the agent or node or "user";
 *   defaults to `""` and is stamped with the workflow or node name when emitted from a workflow
 *   node.
 * @property content The content of the event.
 * @property actions Optional actions associated with this event.
 * @property longRunningToolIds Set of ids of the long running function calls. Agent client will
 *   know from this field about which function call is long running.
 * @property partial True for incomplete chunks from the LLM streaming response. The last chunk's
 *   partial is false.
 * @property turnComplete True if this event marks the completion of a turn.
 * @property errorCode An error code if an error occurred during the event processing.
 * @property errorMessage A human-readable error message if an error occurred.
 * @property finishReason The reason the LLM generation finished.
 * @property usageMetadata Metadata about the token usage for the LLM call.
 * @property avgLogProbs The average log probabilities of the generated tokens.
 * @property interrupted True if the generation of this event was interrupted.
 * @property branch The branch of the event. The format is like agent_1.agent_2.agent_3, where
 *   agent_1 is the parent of agent_2, and agent_2 is the parent of agent_3. Branch is used when
 *   multiple sub-agents shouldn't see their peer agents' conversation history.
 * @property groundingMetadata The grounding metadata of the event.
 * @property modelVersion The model version used to generate the response.
 * @property cacheMetadata Context cache metadata associated with this event's LLM response, used to
 *   carry cache state across turns. `null` when context caching is disabled.
 * @property output For a workflow node, the value handed to successors, distinct from [content].
 *   Holds a JSON-native value (map with string keys, list, string, number, boolean, null,
 *   `JsonElement`) or a [Content].
 * @property nodeInfo Identifies the workflow-node activation that emitted this event; `null`
 *   outside a workflow.
 * @property timestamp The timestamp of the event.
 * @property turnCompleteReason Why the model ended its turn, when the live server reports a reason
 *   other than ordinary completion.
 * @property interactionStatus Whether the model is still working on the prompt, reported only with
 *   [turnComplete]; see [InteractionStatus]. On a completed turn, `null` means the model is done.
 * @property inputTranscription Transcription of the audio the user sent on a live connection.
 * @property outputTranscription Transcription of the audio the model returned on a live connection.
 * @property liveSessionId Identifier of the live session this event came from.
 * @property liveSessionResumptionUpdate A resumption handle issued by the live server.
 * @property voiceActivity A voice activity signal from the live server, marking where it detected
 *   speech starting or stopping.
 */
@Serializable
data class Event
@JvmOverloads
constructor(
  // Always emit: an omitted default is regenerated at decode time (a fresh random), losing the id.
  @OptIn(ExperimentalSerializationApi::class)
  @EncodeDefault(EncodeDefault.Mode.ALWAYS)
  val id: String = Uuid.random(),
  @JsonNames("invocation_id") val invocationId: String? = null,
  @EncodeDefault(EncodeDefault.Mode.ALWAYS) val author: String = "",
  val content: Content? = null,
  val actions: EventActions = EventActions(),
  @JsonNames("long_running_tool_ids") val longRunningToolIds: Set<String> = emptySet(),
  val partial: Boolean = false,
  @JsonNames("turn_complete") val turnComplete: Boolean = false,
  @JsonNames("error_code") val errorCode: String? = null,
  @JsonNames("error_message") val errorMessage: String? = null,
  @JsonNames("finish_reason") val finishReason: FinishReason? = null,
  @JsonNames("usage_metadata") val usageMetadata: UsageMetadata? = null,
  @SerialName("avgLogprobs") @JsonNames("avg_logprobs") val avgLogProbs: Double? = null,
  val interrupted: Boolean = false,
  val branch: String? = null,
  @JsonNames("grounding_metadata") val groundingMetadata: GroundingMetadata? = null,
  @JsonNames("model_version") val modelVersion: String? = null,
  @JsonNames("citation_metadata") val citationMetadata: CitationMetadata? = null,
  @JsonNames("cache_metadata") val cacheMetadata: CacheMetadata? = null,
  @JsonNames("custom_metadata") val customMetadata: Map<String, @Contextual Any?>? = null,
  val output: @Contextual Any? = null,
  @Serializable(with = NodeInfoNullIfEmptySerializer::class)
  @JsonNames("node_info")
  val nodeInfo: NodeInfo? = null,
  // Always emit: an omitted default is regenerated at decode time, changing timestamp on reload.
  @OptIn(ExperimentalSerializationApi::class)
  @EncodeDefault(EncodeDefault.Mode.ALWAYS)
  @Serializable(with = LenientEpochMillisSerializer::class)
  val timestamp: Long = Clock.System.now().toEpochMilliseconds(),
  @JsonNames("turn_complete_reason") val turnCompleteReason: TurnCompleteReason? = null,
  @JsonNames("interaction_status") val interactionStatus: InteractionStatus? = null,
  @JsonNames("input_transcription") val inputTranscription: Transcription? = null,
  @JsonNames("output_transcription") val outputTranscription: Transcription? = null,
  @JsonNames("live_session_id") val liveSessionId: String? = null,
  @JsonNames("live_session_resumption_update")
  val liveSessionResumptionUpdate: LiveServerSessionResumptionUpdate? = null,
  @JsonNames("voice_activity") val voiceActivity: VoiceActivity? = null,
) {

  /** Returns all function calls from this event. */
  fun functionCalls(): List<FunctionCall> =
    content?.parts?.mapNotNull { it.functionCall } ?: emptyList()

  /** Returns all function responses from this event. */
  fun functionResponses(): List<FunctionResponse> =
    content?.parts?.mapNotNull { it.functionResponse } ?: emptyList()

  /**
   * Returns this event's [content] text joined with [separator] (none by default), or "" when the
   * event has no content; forwards [includeThoughts] to [Content.text].
   */
  @JvmOverloads
  fun contentText(separator: CharSequence = "", includeThoughts: Boolean = false): String =
    content?.text(separator, includeThoughts) ?: ""

  /**
   * Returns true if this event is the final response of an agent turn — i.e. it terminates the
   * per-turn model loop in [com.google.adk.kt.agents.LlmAgent.executeTurns]. Matches ADK Python's
   * `Event.is_final_response()`: `skipSummarization` or a non-empty `longRunningToolIds` set both
   * mark the event as final, otherwise the event is final iff it carries no function calls, no
   * function responses, is not a partial streaming chunk, and has no trailing code-execution-result
   * part.
   */
  val isFinalResponse: Boolean
    get() {
      if (actions.skipSummarization || longRunningToolIds.isNotEmpty()) return true
      return functionCalls().isEmpty() &&
        functionResponses().isEmpty() &&
        !partial &&
        !hasTrailingCodeExecutionResult()
    }

  /**
   * Returns true if the last part of this event is a code-execution result. Mirrors ADK Java's
   * `Event.hasTrailingCodeExecutionResult()`, keeping such an event out of [isFinalResponse] so the
   * turn loop runs again to let the model act on the result.
   */
  fun hasTrailingCodeExecutionResult(): Boolean =
    content?.parts?.lastOrNull()?.codeExecutionResult != null

  /**
   * Scans a model response event's parts for function calls missing an ID and generates one for
   * them.
   *
   * @return A new [Event] if any function call was updated, otherwise returns the original event.
   */
  fun populateClientFunctionCallId(): Event {
    val content = this.content ?: return this
    if (content.parts.isEmpty()) {
      return this
    }

    var hasUpdates = false
    val newParts =
      content.parts.map { part ->
        val functionCall = part.functionCall
        if (functionCall != null && functionCall.id.isNullOrEmpty()) {
          hasUpdates = true
          val newFunctionCall = functionCall.copy(id = FunctionCall.generateId())
          part.copy(functionCall = newFunctionCall)
        } else {
          part
        }
      }

    if (!hasUpdates) {
      return this
    }

    return this.copy(content = Content(role = content.role, parts = newParts))
  }

  /**
   * Returns this event with [original]'s id, invocation id and timestamp, and with [original]'s
   * author if this one has none. ADK Python's `Runner._get_output_event` protects the same fields.
   */
  internal fun withIdentityOf(original: Event): Event =
    copy(
      id = original.id,
      invocationId = original.invocationId,
      timestamp = original.timestamp,
      author = author.ifEmpty { original.author },
    )

  /**
   * Returns a [Builder] initialized with this instance's properties, primarily for Java callers.
   * Prefer it over `copy` from Java: `copy` takes every property positionally, so its signature
   * changes whenever a property is added.
   */
  @AdkJavaInteropApi
  fun toBuilder(): Builder =
    Builder()
      .id(id)
      .invocationId(invocationId)
      .author(author)
      .content(content)
      .actions(actions)
      .longRunningToolIds(longRunningToolIds)
      .partial(partial)
      .turnComplete(turnComplete)
      .errorCode(errorCode)
      .errorMessage(errorMessage)
      .finishReason(finishReason)
      .usageMetadata(usageMetadata)
      .avgLogProbs(avgLogProbs)
      .interrupted(interrupted)
      .branch(branch)
      .groundingMetadata(groundingMetadata)
      .modelVersion(modelVersion)
      .citationMetadata(citationMetadata)
      .cacheMetadata(cacheMetadata)
      .customMetadata(customMetadata)
      .output(output)
      .nodeInfo(nodeInfo)
      .timestamp(timestamp)
      .turnCompleteReason(turnCompleteReason)
      .interactionStatus(interactionStatus)
      .inputTranscription(inputTranscription)
      .outputTranscription(outputTranscription)
      .liveSessionId(liveSessionId)
      .liveSessionResumptionUpdate(liveSessionResumptionUpdate)
      .voiceActivity(voiceActivity)

  /**
   * Fluent builder for [Event], provided primarily for Java callers. Any property left unset falls
   * back to the same default as the constructor.
   */
  @AdkJavaInteropApi
  @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
  class Builder {
    private var id: String = Uuid.random()
    private var invocationId: String? = null
    private var author: String = ""
    private var content: Content? = null
    private var actions: EventActions = EventActions()
    private var longRunningToolIds: Set<String> = emptySet()
    private var partial: Boolean = false
    private var turnComplete: Boolean = false
    private var errorCode: String? = null
    private var errorMessage: String? = null
    private var finishReason: FinishReason? = null
    private var usageMetadata: UsageMetadata? = null
    private var avgLogProbs: Double? = null
    private var interrupted: Boolean = false
    private var branch: String? = null
    private var groundingMetadata: GroundingMetadata? = null
    private var modelVersion: String? = null
    private var citationMetadata: CitationMetadata? = null
    private var cacheMetadata: CacheMetadata? = null
    private var customMetadata: Map<String, @Contextual Any?>? = null
    private var output: @Contextual Any? = null
    private var nodeInfo: NodeInfo? = null
    private var timestamp: Long = Clock.System.now().toEpochMilliseconds()
    private var turnCompleteReason: TurnCompleteReason? = null
    private var interactionStatus: InteractionStatus? = null
    private var inputTranscription: Transcription? = null
    private var outputTranscription: Transcription? = null
    private var liveSessionId: String? = null
    private var liveSessionResumptionUpdate: LiveServerSessionResumptionUpdate? = null
    private var voiceActivity: VoiceActivity? = null

    fun id(id: String): Builder = apply { this.id = id }

    fun invocationId(invocationId: String?): Builder = apply { this.invocationId = invocationId }

    fun author(author: String): Builder = apply { this.author = author }

    fun content(content: Content?): Builder = apply { this.content = content }

    fun actions(actions: EventActions): Builder = apply { this.actions = actions }

    fun longRunningToolIds(longRunningToolIds: Set<String>): Builder = apply {
      this.longRunningToolIds = longRunningToolIds
    }

    fun partial(partial: Boolean): Builder = apply { this.partial = partial }

    fun turnComplete(turnComplete: Boolean): Builder = apply { this.turnComplete = turnComplete }

    fun errorCode(errorCode: String?): Builder = apply { this.errorCode = errorCode }

    fun errorMessage(errorMessage: String?): Builder = apply { this.errorMessage = errorMessage }

    fun finishReason(finishReason: FinishReason?): Builder = apply {
      this.finishReason = finishReason
    }

    fun usageMetadata(usageMetadata: UsageMetadata?): Builder = apply {
      this.usageMetadata = usageMetadata
    }

    fun avgLogProbs(avgLogProbs: Double?): Builder = apply { this.avgLogProbs = avgLogProbs }

    fun interrupted(interrupted: Boolean): Builder = apply { this.interrupted = interrupted }

    fun branch(branch: String?): Builder = apply { this.branch = branch }

    fun groundingMetadata(groundingMetadata: GroundingMetadata?): Builder = apply {
      this.groundingMetadata = groundingMetadata
    }

    fun modelVersion(modelVersion: String?): Builder = apply { this.modelVersion = modelVersion }

    fun citationMetadata(citationMetadata: CitationMetadata?): Builder = apply {
      this.citationMetadata = citationMetadata
    }

    fun cacheMetadata(cacheMetadata: CacheMetadata?): Builder = apply {
      this.cacheMetadata = cacheMetadata
    }

    fun customMetadata(customMetadata: Map<String, @Contextual Any?>?): Builder = apply {
      this.customMetadata = customMetadata
    }

    fun output(output: Any?): Builder = apply { this.output = output }

    fun nodeInfo(nodeInfo: NodeInfo?): Builder = apply { this.nodeInfo = nodeInfo }

    fun timestamp(timestamp: Long): Builder = apply { this.timestamp = timestamp }

    fun turnCompleteReason(turnCompleteReason: TurnCompleteReason?): Builder = apply {
      this.turnCompleteReason = turnCompleteReason
    }

    fun interactionStatus(interactionStatus: InteractionStatus?): Builder = apply {
      this.interactionStatus = interactionStatus
    }

    fun inputTranscription(inputTranscription: Transcription?): Builder = apply {
      this.inputTranscription = inputTranscription
    }

    fun outputTranscription(outputTranscription: Transcription?): Builder = apply {
      this.outputTranscription = outputTranscription
    }

    fun liveSessionId(liveSessionId: String?): Builder = apply {
      this.liveSessionId = liveSessionId
    }

    fun liveSessionResumptionUpdate(
      liveSessionResumptionUpdate: LiveServerSessionResumptionUpdate?
    ): Builder = apply { this.liveSessionResumptionUpdate = liveSessionResumptionUpdate }

    fun voiceActivity(voiceActivity: VoiceActivity?): Builder = apply {
      this.voiceActivity = voiceActivity
    }

    fun build(): Event =
      Event(
        id = id,
        invocationId = invocationId,
        author = author,
        content = content,
        actions = actions,
        longRunningToolIds = longRunningToolIds,
        partial = partial,
        turnComplete = turnComplete,
        errorCode = errorCode,
        errorMessage = errorMessage,
        finishReason = finishReason,
        usageMetadata = usageMetadata,
        avgLogProbs = avgLogProbs,
        interrupted = interrupted,
        branch = branch,
        groundingMetadata = groundingMetadata,
        modelVersion = modelVersion,
        citationMetadata = citationMetadata,
        cacheMetadata = cacheMetadata,
        customMetadata = customMetadata,
        output = output,
        nodeInfo = nodeInfo,
        timestamp = timestamp,
        turnCompleteReason = turnCompleteReason,
        interactionStatus = interactionStatus,
        inputTranscription = inputTranscription,
        outputTranscription = outputTranscription,
        liveSessionId = liveSessionId,
        liveSessionResumptionUpdate = liveSessionResumptionUpdate,
        voiceActivity = voiceActivity,
      )
  }

  companion object {
    @AdkJavaInteropApi @JvmStatic fun builder(): Builder = Builder()
  }
}

/**
 * Retrieves a set of function call IDs that correspond to long-running tools.
 *
 * @param tools The available tools mapped by name.
 * @return A set of string IDs representing long-running tool executions.
 */
internal fun List<FunctionCall>.getLongRunningFunctionIds(
  tools: Map<String, BaseTool>
): Set<String> {
  val longRunningToolIds = mutableSetOf<String>()
  for (functionCall in this) {
    val tool = tools[functionCall.name]
    if (tool != null && tool.isLongRunning) {
      functionCall.id?.let { longRunningToolIds.add(it) }
    }
  }
  return longRunningToolIds
}
