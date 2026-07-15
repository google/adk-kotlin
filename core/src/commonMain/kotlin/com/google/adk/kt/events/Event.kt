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

import com.google.adk.kt.ids.Uuid
import com.google.adk.kt.models.CacheMetadata
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.types.CitationMetadata
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FinishReason
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.GroundingMetadata
import com.google.adk.kt.types.UsageMetadata
import kotlin.time.Clock
import kotlinx.serialization.Contextual
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Represents an event in a session.
 *
 * @property id The event id.
 * @property invocationId Id of the invocation that this event belongs to.
 * @property author The author of the event, it could be the name of the agent or "user" literal.
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
 * @property timestamp The timestamp of the event.
 */
@Serializable
data class Event(
  val id: String = Uuid.random(),
  val invocationId: String? = null,
  val author: String,
  val content: Content? = null,
  val actions: EventActions = EventActions(),
  val longRunningToolIds: Set<String> = emptySet(),
  val partial: Boolean = false,
  val turnComplete: Boolean = false,
  val errorCode: String? = null,
  val errorMessage: String? = null,
  val finishReason: FinishReason? = null,
  val usageMetadata: UsageMetadata? = null,
  @SerialName("avgLogprobs") val avgLogProbs: Double? = null,
  val interrupted: Boolean = false,
  val branch: String? = null,
  val groundingMetadata: GroundingMetadata? = null,
  val modelVersion: String? = null,
  val citationMetadata: CitationMetadata? = null,
  val cacheMetadata: CacheMetadata? = null,
  val customMetadata: Map<String, @Contextual Any>? = null,
  val timestamp: Long = Clock.System.now().toEpochMilliseconds(),
) {

  /** Returns all function calls from this event. */
  fun functionCalls(): List<FunctionCall> =
    content?.parts?.mapNotNull { it.functionCall } ?: emptyList()

  /** Returns all function responses from this event. */
  fun functionResponses(): List<FunctionResponse> =
    content?.parts?.mapNotNull { it.functionResponse } ?: emptyList()

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
   * `Event.hasTrailingCodeExecutionResult()`.
   *
   * The Kotlin `Part` type does not yet model `codeExecutionResult` (see
   * `com.google.adk.kt.types.Part`). Until it does, this helper is always `false`. The clause is
   * still present in [isFinalResponse] so that the call structure matches Java verbatim and so a
   * future `Part.codeExecutionResult` field is wired into the right gate by construction.
   */
  fun hasTrailingCodeExecutionResult(): Boolean = false

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
