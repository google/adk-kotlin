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
package com.google.adk.kt.processors

import com.google.adk.kt.agents.LlmAgent.IncludeContents
import com.google.adk.kt.events.Event
import com.google.adk.kt.events.applyRewinds
import com.google.adk.kt.serialization.Json
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role

/**
 * Rewrites history events to be suitable for LLM context.
 *
 * Handles:
 * - Filtering "invisible" parts (pure thoughts).
 * - Rearranging/merging function response events (parallel calls).
 */
internal class HistoryRewriterProcessor {
  fun rewrite(
    events: List<Event>,
    agentName: String,
    currentBranch: String?,
    includeContents: IncludeContents = IncludeContents.DEFAULT,
  ): List<Content> {
    val workingEvents =
      when (includeContents) {
        IncludeContents.DEFAULT -> events
        IncludeContents.NONE -> getCurrentTurnEvents(events, agentName, currentBranch)
      }

    val rewindFilteredEvents = applyRewinds(workingEvents)

    // Filter raw events
    val rawFilteredEvents = rewindFilteredEvents.filter {
      shouldIncludeEventInContext(currentBranch, it)
    }

    // Process events. Compaction events are kept here (they carry their summary in
    // actions.compaction rather than content) so processCompactionEvents can expand them below.
    val filteredEvents = rawFilteredEvents.mapNotNull { event ->
      when {
        event.actions.compaction != null -> event
        event.content == null -> null
        isOtherAgentReply(agentName, event) -> presentOtherAgentMessage(event)
        else -> event
      }
    }

    // Replace each compaction event with its summary and drop the raw events it covers.
    val eventsWithCompactionApplied =
      if (filteredEvents.any { it.actions.compaction != null }) {
        processCompactionEvents(filteredEvents)
      } else {
        filteredEvents
      }

    // Rearrange for latest function response (merge scenarios) and async function responses
    return eventsWithCompactionApplied
      .let { rearrangeEventsForLatestFunctionResponse(it) }
      .let { rearrangeEventsForAsyncFunctionResponsesInHistory(it) }
      .mapNotNull { event ->
        val content = event.content ?: return@mapNotNull null
        stripClientFunctionCallIds(content)
      }
  }

  /**
   * Processes events by applying compaction. Identifies compacted ranges and filters out events
   * that are covered by compaction summaries.
   *
   * @param events The list of events to process.
   * @return The list of events with compaction applied.
   */
  private fun processCompactionEvents(events: List<Event>): List<Event> {
    // Extract all compaction ranges from the events.
    val compactionRanges = events.mapIndexedNotNull { index, event ->
      event.actions.compaction?.let { CompactionRange(index, it.startTimestamp, it.endTimestamp) }
    }
    val coveredIndices = coveredCompactionRangeIndices(compactionRanges)
    val keptCompactionRanges = compactionRanges.filter { it.index !in coveredIndices }

    data class Item(val timestamp: Long, val index: Int, val event: Event)

    val finalItems = mutableListOf<Item>()

    // Pass 1: append all kept compaction events.
    for (range in keptCompactionRanges) {
      val compaction = events[range.index].actions.compaction!!
      finalItems.add(
        Item(
          compaction.endTimestamp,
          range.index,
          events[range.index].copy(
            author = Role.MODEL,
            content = compaction.compactedContent,
            timestamp = compaction.endTimestamp,
          ),
        )
      )
    }

    // Pass 2: append raw (non-compaction) events that don't fall into a kept compaction range.
    finalItems +=
      events
        .withIndex()
        .filter { (_, event) -> event.actions.compaction == null }
        .filter { (_, event) -> keptCompactionRanges.none { event.timestamp in it.start..it.end } }
        .map { (index, event) -> Item(event.timestamp, index, event) }

    return finalItems.sortedWith(compareBy({ it.timestamp }, { it.index })).map { it.event }
  }

  /**
   * Returns the indices of [ranges] that are fully contained by another range. When two ranges are
   * identical only the later one is kept; partially overlapping ranges (neither containing the
   * other) are both kept.
   */
  private fun coveredCompactionRangeIndices(ranges: List<CompactionRange>): Set<Int> =
    ranges.filter { range -> ranges.any { it.covers(range) } }.map { it.index }.toSet()

  private data class CompactionRange(val index: Int, val start: Long, val end: Long) {
    /**
     * True if this range fully contains [other] -- strictly larger on at least one side, or
     * identical but appearing later (so equal ranges keep only the most recent).
     */
    fun covers(other: CompactionRange): Boolean =
      index != other.index &&
        start <= other.start &&
        end >= other.end &&
        (start < other.start || end > other.end || index > other.index)
  }

  /**
   * Returns the suffix of [events] that belongs to the current turn.
   *
   * Used when [IncludeContents.NONE] is set on the agent. Logic is based on Python ADK's
   * `_get_current_turn_contents`. The current turn starts at the most recent event whose author is
   * the user or, in multi-agent setups, an "other agent" whose reply forms the input for this
   * agent. Tool calls/responses produced within the current turn are still included.
   *
   * Returns an empty list if no current-turn boundary event can be found.
   */
  private fun getCurrentTurnEvents(
    events: List<Event>,
    agentName: String,
    currentBranch: String?,
  ): List<Event> {
    for (i in events.indices.reversed()) {
      if (isCurrentTurnBoundary(events[i], agentName, currentBranch)) {
        return events.subList(i, events.size)
      }
    }
    return emptyList()
  }

  /**
   * Returns whether [event] qualifies as the start of the current turn for [agentName] on
   * [currentBranch]: it must be visible in this agent's context, and it must be a user input or
   * another agent's reply (not this agent's own output, and not an internal/auth/etc. event).
   */
  private fun isCurrentTurnBoundary(
    event: Event,
    agentName: String,
    currentBranch: String?,
  ): Boolean =
    shouldIncludeEventInContext(currentBranch, event) &&
      (event.author == Role.USER || isOtherAgentReply(agentName, event))

  private fun isOtherAgentReply(currentAgentName: String, event: Event): Boolean {
    return currentAgentName.isNotEmpty() &&
      event.author != currentAgentName &&
      event.author != Role.USER
  }

  /**
   * Presents another agent's message as user context for the current agent.
   *
   * Reformats the event with role='user' and adds '[agent_name] said:' prefix to provide context
   * without confusion about authorship.
   *
   * @param event The event from another agent to present as context.
   * @return Event reformatted as user-role context with agent attribution, or None if no meaningful
   *   content remains after filtering.
   */
  private fun presentOtherAgentMessage(event: Event): Event? {
    if (event.content == null || event.content.parts.isEmpty()) {
      return event
    }

    val newParts = mutableListOf<Part>()
    newParts.add(Part(text = "For context:"))

    for (part in event.content.parts) {
      if (part.thought == true) {
        // Exclude thoughts from the context.
        continue
      }
      part.text?.let { text -> newParts.add(Part(text = "[${event.author}] said: $text")) }
      part.functionCall?.let { functionCall ->
        newParts.add(
          Part(
            text =
              "[${event.author}] called tool `${functionCall.name}`" +
                " with parameters: ${Json.toJsonString(functionCall.args)}"
          )
        )
      }
      part.functionResponse?.let { functionResponse ->
        newParts.add(
          Part(
            text =
              "[${event.author}] `${functionResponse.name}` tool" +
                " returned result: ${Json.toJsonString(functionResponse.response)}"
          )
        )
      }
      if (part.inlineData != null || part.fileData != null) {
        newParts.add(part)
      } else {
        continue
      }
    }

    if (newParts.size == 1) { // Only "For context:" added
      return null
    }

    return event.copy(content = Content(role = Role.USER, parts = newParts))
  }

  /**
   * Determines if an event should be included in the LLM context.
   *
   * This filters out events that are considered empty (e.g., no text, function calls, or
   * transcriptions), do not belong to the current agent's branch, or are internal events like
   * authentication or confirmation requests.
   */
  private fun shouldIncludeEventInContext(currentBranch: String?, event: Event): Boolean {
    return !(containsEmptyContent(event) ||
      !isEventBelongsToBranch(currentBranch, event) ||
      isAdkFrameworkEvent(event) ||
      isAuthEvent(event) ||
      isRequestConfirmationEvent(event) ||
      isRequestInputEvent(event))
  }

  /**
   * Check if an event should be skipped due to missing or empty content.
   *
   * This can happen to the events that only changed session state. When both content and
   * transcriptions are empty, the event will be considered as empty. The content is considered
   * empty if none of its parts contain text, inline data, function call, or function response.
   * Parts with only thoughts are also considered empty.
   */
  private fun containsEmptyContent(event: Event): Boolean {
    // Compaction events carry their summary in actions.compaction rather than content; keep them so
    // processCompactionEvents can expand them into summary content.
    if (event.actions.compaction != null) return false

    val hasContent =
      event.content != null &&
        event.content.role != null &&
        event.content.parts.isNotEmpty() &&
        !event.content.parts.all { isPartInvisible(it) }
    return !hasContent
  }

  /**
   * Returns whether a part is invisible for LLM context.
   *
   * A part is invisible if:
   * - It has no meaningful content (text, inline_data, function_call, or function_response), OR
   * - It is marked as a thought AND does not contain function_call or function_response.
   *
   * Function calls and responses are never invisible, even if marked as thought, because they
   * represent actions that need to be executed or results that need to be processed.
   */
  private fun isPartInvisible(part: Part): Boolean {
    // Function calls and responses are never invisible, even if marked as thought
    if (part.functionCall != null || part.functionResponse != null) {
      return false
    }

    val isThought = part.thought == true
    val hasContent = !part.text.isNullOrBlank() || part.inlineData != null || part.fileData != null

    return isThought || !hasContent
  }

  /** Checks if the event is an ADK framework event. */
  private fun isAdkFrameworkEvent(event: Event): Boolean =
    isFunctionCallEvent(event, "adk_framework")

  /** Checks if the event is an authentication event. */
  private fun isAuthEvent(event: Event): Boolean =
    isFunctionCallEvent(event, REQUEST_EUC_FUNCTION_CALL_NAME)

  /** Checks if the event is a request confirmation event. */
  private fun isRequestConfirmationEvent(event: Event): Boolean =
    isFunctionCallEvent(event, FunctionCall.REQUEST_CONFIRMATION_FUNCTION_CALL_NAME)

  /** Checks if the event is a request input event. */
  private fun isRequestInputEvent(event: Event): Boolean =
    isFunctionCallEvent(event, REQUEST_INPUT_FUNCTION_CALL_NAME)

  /** Checks if an event is a function call/response for a given function name. */
  private fun isFunctionCallEvent(event: Event, functionName: String): Boolean {
    return event.content?.parts?.any { part ->
      part.functionCall?.name == functionName || part.functionResponse?.name == functionName
    } ?: false
  }

  /**
   * Check if an event belongs to the current branch.
   *
   * This is for event context segregation between agents. E.g. agent A shouldn't see output of
   * agent B.
   */
  private fun isEventBelongsToBranch(invocationBranch: String?, event: Event): Boolean {
    if (invocationBranch.isNullOrEmpty() || event.branch.isNullOrEmpty()) {
      return true
    }
    // We use dot to delimit branch nodes. To avoid simple prefix match
    // (e.g. agent_0 unexpectedly matching agent_00), require either perfect branch
    // match, or match prefix with an additional explicit '.'
    return invocationBranch == event.branch || invocationBranch.startsWith("${event.branch}.")
  }

  private fun findMatchingFunctionCallEvent(
    events: List<Event>,
    currentFunctionResponseIds: Set<String>,
    searchFromIndex: Int = events.size - 2,
  ): Int {
    // Find the historical Event that contains the FunctionCalls corresponding to the
    // FunctionResponses in our current (last) Event.
    //
    // An Event is considered a match if any of the FunctionCalls it contains share an ID with
    // the FunctionResponses we are processing.
    //
    // If it finds an imperfect match (where not all FunctionResponse IDs were contained in that
    // Event's calls), it throws an exception.
    return (searchFromIndex downTo 0).firstOrNull { index ->
      val requestedCallIds = events[index].functionCalls().mapNotNull { it.id }.toSet()
      if (requestedCallIds.isEmpty()) return@firstOrNull false

      // Does this historical event contain requests for the tools we are currently responding to?
      val isMatchingEvent = currentFunctionResponseIds.any { it in requestedCallIds }

      if (isMatchingEvent) {
        // Sanity check: verify that this event requested all the tools we are currently
        // responding
        // to.
        val missingRequests = currentFunctionResponseIds - requestedCallIds
        if (missingRequests.isNotEmpty()) {
          throw IllegalStateException(
            "Unexpected mismatch in IDs: Function Responses: $missingRequests were not requested by any of the Function Calls: $requestedCallIds"
          )
        }
      }
      isMatchingEvent
    }
      ?: throw IllegalStateException(
        "No function call event found for function responses ids: $currentFunctionResponseIds in event list: $events"
      )
  }

  /**
   * Rearranges events to ensure that function responses are grouped with their corresponding
   * function calls.
   *
   * @param events The list of events to rearrange.
   * @return The rearranged list of events.
   */
  fun rearrangeEventsForLatestFunctionResponse(events: List<Event>): List<Event> {
    if (events.size < 2) return events

    val lastEvent = events.last()
    val functionResponses = lastEvent.functionResponses()
    if (functionResponses.isEmpty()) return events

    val currentFunctionResponseIds = functionResponses.mapNotNull { it.id }.toSet()

    // Fast-path (parity with Java/Python): if the immediately-preceding event already contains a
    // matching function_call for the latest function_response, the history is already in the
    // canonical [..., model(function_call), function_response] shape, so return as-is. This
    // preserves the [user, model_fc, tool_fr] shape required when replaying out-of-band
    // long-running tool results, and prevents the merge logic from collapsing unrelated
    // intermediate events into the response.
    val penultimateCallIds =
      events[events.size - 2].functionCalls().mapNotNull { it.id }.toSet()
    if (currentFunctionResponseIds.any { it in penultimateCallIds }) {
      return events
    }

    // Otherwise scan backwards starting from the third-to-last event (skip the penultimate event
    // which we already know does not match).
    val functionCallEventIndex =
      findMatchingFunctionCallEvent(
        events,
        currentFunctionResponseIds,
        searchFromIndex = events.size - 3,
      )

    // Collect all events containing matching function responses between the function call event
    // and
    // now.
    val functionCallIds =
      events[functionCallEventIndex].functionCalls().mapNotNull { it.id }.toSet()
    val functionResponseEvents =
      events.subList(functionCallEventIndex + 1, events.size).filter { event ->
        val responses = event.functionResponses()
        responses.isNotEmpty() && responses.any { it.id in functionCallIds }
      }

    // Result: [0..functionCallEventIndex] + [mergedEvent]
    val result = events.subList(0, functionCallEventIndex + 1).toMutableList()
    result.add(mergeFunctionResponseEvents(functionResponseEvents))
    return result
  }

  /**
   * Merges a list of function_response events into one event.
   *
   * The key goal is to ensure:
   * 1. function_call and function_response are always of the same number.
   * 2. The function_call and function_response are consecutively in the content.
   *
   * @param responseEvents A list of function_response events. NOTE: responseEvents must fulfill
   *   these requirements:
   * 1. The list is in increasing order of timestamp;
   * 2. the first event is the initial function_response event;
   * 3. all later events should contain at least one function_response part that related to the
   *    function_call event. Caveat: This implementation doesn't support when a parallel
   *    function_call event contains async function_call of the same name.
   *
   * @return A merged event, that is
   * 1. All later function_response will replace function_response part in the initial
   *    function_response event.
   * 2. All non-function_response parts will be appended to the part list of the initial
   *    function_response event.
   */
  private fun mergeFunctionResponseEvents(responseEvents: List<Event>): Event {
    if (responseEvents.isEmpty()) {
      throw IllegalArgumentException("responseEvents cannot be empty")
    }

    // Base event is the first one (Python: function_response_events[0])
    // We start with a deep copy of it (conceptually, in Kotlin we copy and modify)
    val baseEvent = responseEvents[0]
    val mergedParts =
      baseEvent.content?.parts?.toMutableList()
        ?: throw IllegalArgumentException(
          "There should be at least one function_response part in the first event."
        )

    if (mergedParts.isEmpty()) {
      throw IllegalArgumentException(
        "There should be at least one function_response part in the first event."
      )
    }

    // Index existing function responses in the base event by ID
    val partIndicesInMergedEvent = mutableMapOf<String, Int>()
    mergedParts.forEachIndexed { index, part ->
      val id = part.functionResponse?.id
      if (id != null) {
        partIndicesInMergedEvent[id] = index
      }
    }

    // Iterate through subsequent events
    for (event in responseEvents.drop(1)) {
      val parts =
        event.content?.parts
          ?: throw IllegalArgumentException("There should be at least one function_response part.")

      if (parts.isEmpty()) {
        throw IllegalArgumentException("There should be at least one function_response part.")
      }

      for (part in parts) {
        val functionResponse = part.functionResponse
        if (functionResponse != null) {
          val id =
            functionResponse.id
              ?: throw IllegalArgumentException("Function response ID cannot be null.")
          if (id in partIndicesInMergedEvent) {
            // Replace existing part (last-write-wins for same ID)
            mergedParts[partIndicesInMergedEvent[id]!!] = part
          } else {
            // Append new part and update index for future replacements if this ID appears again
            mergedParts.add(part)
            partIndicesInMergedEvent[id] = mergedParts.size - 1
          }
        } else {
          // Non-function response parts are just appended
          mergedParts.add(part)
        }
      }
    }

    // Return copy of baseEvent with updated content.
    return baseEvent.copy(content = Content(role = baseEvent.content.role, parts = mergedParts))
  }

  /** Rearrange the async function_response events in the history. */
  private fun rearrangeEventsForAsyncFunctionResponsesInHistory(events: List<Event>): List<Event> {
    // 1. Map function call IDs to their response event index
    val functionCallIdToResponseEventIndex = buildMap {
      events.forEachIndexed { index, event ->
        for (response in event.functionResponses()) {
          response.id?.let { id -> put(id, index) }
        }
      }
    }

    // 2. Process events
    return buildList {
      for (event in events) {
        if (event.functionResponses().isNotEmpty()) {
          // Function responses are handled when processing their corresponding function calls
          continue
        }

        add(event)

        val functionCalls = event.functionCalls()
        if (functionCalls.isNotEmpty()) {
          val functionResponseEventIndices =
            functionCalls
              .mapNotNull { call -> call.id?.let { functionCallIdToResponseEventIndex[it] } }
              .toSet()

          if (functionResponseEventIndices.isNotEmpty()) {
            if (functionResponseEventIndices.size == 1) {
              add(events[functionResponseEventIndices.first()])
            } else {
              // Merge all async function responses as one response event
              val responsesToMerge = functionResponseEventIndices.sorted().map { events[it] }
              add(mergeFunctionResponseEvents(responsesToMerge))
            }
          }
        }
      }
    }
  }

  // Helper to actually remove IDs, returning new Content if changed
  private fun stripClientFunctionCallIds(content: Content): Content {
    val parts = content.parts
    if (parts.isEmpty()) return content

    val newParts = parts.map { part ->
      val functionCall = part.functionCall
      val functionResponse = part.functionResponse

      when {
        functionCall?.id?.startsWith(AF_FUNCTION_CALL_ID_PREFIX) == true -> {
          part.copy(functionCall = functionCall.copy(id = null))
        }
        functionResponse?.id?.startsWith(AF_FUNCTION_CALL_ID_PREFIX) == true -> {
          part.copy(functionResponse = functionResponse.copy(id = null))
        }
        else -> part
      }
    }
    return Content(role = content.role, parts = newParts)
  }

  companion object {
    private const val AF_FUNCTION_CALL_ID_PREFIX = "adk-"
    private const val REQUEST_EUC_FUNCTION_CALL_NAME = "adk_request_credential"
    private const val REQUEST_INPUT_FUNCTION_CALL_NAME = "adk_request_input"
  }
}
