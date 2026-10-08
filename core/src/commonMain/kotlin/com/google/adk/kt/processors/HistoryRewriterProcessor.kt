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
import com.google.adk.kt.artifacts.isAdkLiveArtifact
import com.google.adk.kt.events.Event
import com.google.adk.kt.events.applyRewinds
import com.google.adk.kt.events.functionCallIds
import com.google.adk.kt.events.isToolBranch
import com.google.adk.kt.serialization.Json
import com.google.adk.kt.tools.TransferToAgentTool
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
 * - Merging stored live transcriptions into `user` and `model` text turns.
 */
internal class HistoryRewriterProcessor {
  fun rewrite(
    events: List<Event>,
    agentName: String,
    currentBranch: String?,
    includeContents: IncludeContents = IncludeContents.DEFAULT,
  ): List<Content> {
    // A tool's own messages sit on a `<tool>@<call id>` branch named by a recorded call id.
    val toolCallIds = events.functionCallIds()
    val workingEvents =
      when (includeContents) {
        IncludeContents.DEFAULT -> events
        IncludeContents.NONE -> getCurrentTurnEvents(events, agentName, currentBranch, toolCallIds)
      }

    val rewindFilteredEvents = applyRewinds(workingEvents)

    // Filter raw events
    val rawFilteredEvents = rewindFilteredEvents.filter {
      shouldIncludeEventInContext(currentBranch, it, agentName, toolCallIds)
    }

    // Replace each compaction event with its summary and drop the raw events it covers.
    val eventsWithCompactionApplied =
      if (rawFilteredEvents.any { it.actions.compaction != null }) {
        processCompactionEvents(rawFilteredEvents, agentName)
      } else {
        rawFilteredEvents
      }

    // Compaction may have summarized away a function_call whose response survives (e.g. a
    // long-running call resumed after it was compacted). Recover the missing call from the
    // pre-compaction events so call/response pairing stays intact for the steps below.
    val recoveredEvents =
      recoverCompactedFunctionCalls(eventsWithCompactionApplied, rawFilteredEvents)

    // A result goes with its call when the call is presented as context, or it pairs with nothing.
    val narratedCallIds = mutableSetOf<String>()
    val filteredEvents = buildList {
      for (event in mergeTranscriptions(recoveredEvents)) {
        if (event.content == null) continue
        val narrate =
          isOtherAgentReply(agentName, event) ||
            event.functionResponses().any { it.id in narratedCallIds }
        if (!narrate) {
          add(event)
          continue
        }
        event.functionCalls().mapNotNullTo(narratedCallIds) { it.id }
        presentOtherAgentMessage(event)?.let { add(it) }
      }
    }

    // Rearrange for latest function response (merge scenarios) and async function responses
    return filteredEvents
      .let { rearrangeEventsForLatestFunctionResponse(it) }
      .let { rearrangeEventsForAsyncFunctionResponsesInHistory(it) }
      .mapNotNull { event ->
        val content = event.content ?: return@mapNotNull null
        stripClientFunctionCallIds(content)
      }
  }

  /**
   * Merges each run of transcription events without visible content into one text event, input
   * transcriptions as a `user` turn and output transcriptions as a `model` turn, so later
   * invocations on the session replay what was said. A run is one author's consecutive chunks of
   * one kind, and a blank run is dropped. It runs before the other-agent rewrite, so another
   * agent's speech is narrated as context just like text.
   */
  private fun mergeTranscriptions(events: List<Event>): List<Event> {
    val merged = mutableListOf<Event>()
    val text = StringBuilder()
    events.forEachIndexed { i, event ->
      val kind = transcriptionKind(event)
      if (kind == null) {
        merged += event
        return@forEachIndexed
      }
      text.append(kind.textOf(event))
      if (!continuesTranscriptionRun(event, events.getOrNull(i + 1))) {
        // Blankness is judged per run, so a lone space chunk still separates words.
        if (text.isNotBlank()) merged += kind.toTextEvent(event, text.toString())
        text.clear()
      }
    }
    return merged
  }

  /** Whether [next] continues [event]'s run: same kind, same author, no visible content. */
  private fun continuesTranscriptionRun(event: Event, next: Event?): Boolean {
    val kind = transcriptionKind(event) ?: return false
    return next != null && next.author == event.author && transcriptionKind(next) == kind
  }

  /** The kind of transcription [event] is merged as, or null if it has visible content or none. */
  private fun transcriptionKind(event: Event): TranscriptionKind? =
    when {
      hasContent(event) -> null
      !event.inputTranscription?.text.isNullOrEmpty() -> TranscriptionKind.INPUT
      !event.outputTranscription?.text.isNullOrEmpty() -> TranscriptionKind.OUTPUT
      else -> null
    }

  private enum class TranscriptionKind {
    INPUT,
    OUTPUT;

    fun textOf(event: Event): String =
      when (this) {
        INPUT -> event.inputTranscription?.text
        OUTPUT -> event.outputTranscription?.text
      }.orEmpty()

    /**
     * The run's last [event], carrying the run's [text] as content instead of a transcription. It
     * drops the live session id, so the agent's own speech stays a model turn.
     */
    fun toTextEvent(event: Event, text: String): Event =
      when (this) {
        INPUT ->
          event.copy(
            content = Content(Role.USER, listOf(Part(text = text))),
            inputTranscription = null,
            liveSessionId = null,
          )
        OUTPUT ->
          event.copy(
            content = Content(Role.MODEL, listOf(Part(text = text))),
            outputTranscription = null,
            liveSessionId = null,
          )
      }
  }

  /**
   * Processes events by applying compaction. Identifies compacted ranges and filters out events
   * that are covered by compaction summaries.
   *
   * @param events The list of events to process.
   * @param agentName The current agent, which authors each summary.
   * @return The list of events with compaction applied.
   */
  private fun processCompactionEvents(events: List<Event>, agentName: String): List<Event> {
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
            // The agent's own, so the other-agent rewrite does not narrate it.
            author = agentName.ifEmpty { Role.MODEL },
            content = compaction.compactedContent,
            timestamp = compaction.endTimestamp,
          ),
        )
      )
    }

    // Pass 2: append raw (non-compaction) events that don't fall into a kept compaction range. A
    // raw event is only covered by a compaction that appears after it in the stream (`index <
    // it.index`).
    finalItems +=
      events
        .withIndex()
        .filter { (_, event) -> event.actions.compaction == null }
        .filter { (index, event) ->
          keptCompactionRanges.none { index < it.index && event.timestamp in it.start..it.end }
        }
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
   * Re-injects function-call events that compaction removed but whose responses came later.
   *
   * The canonical case is a paused long-running tool call: the call and its intermediate
   * placeholder response are compacted, then the real result arrives on resume -- a later event not
   * covered by the summary. That surviving response would be orphaned, which breaks call/response
   * pairing in [rearrangeEventsForLatestFunctionResponse] (it throws).
   *
   * For each response whose call is no longer present, the original call event is restored from
   * [sourceEvents] (the pre-compaction list) and inserted immediately before the first surviving
   * response that references it. The whole call event is recovered so parallel calls stay intact;
   * for every call in that event whose response was *also* compacted away, the freshest (latest)
   * response for that call is re-injected too, so a parallel sibling is not surfaced as a phantom
   * pending call.
   *
   * Only long-running calls are recovered: a response whose call was compacted is only expected for
   * a long-running call (its placeholder response balanced the call, so the compactor did not treat
   * it as an open obligation). A response whose call is not long-running -- or whose call is absent
   * from [sourceEvents] entirely -- is left unrecovered, so a genuinely unmatched response still
   * surfaces via the throw in [rearrangeEventsForLatestFunctionResponse].
   *
   * @param events The post-compaction events being assembled into request contents.
   * @param sourceEvents The pre-compaction events to recover missing calls (and responses) from.
   * @return [events] with recovered long-running call events re-injected; the same list is returned
   *   when nothing needs recovery.
   */
  private fun recoverCompactedFunctionCalls(
    events: List<Event>,
    sourceEvents: List<Event>,
  ): List<Event> {
    val callIdsPresent = mutableSetOf<String>()
    val responseIdsPresent = mutableSetOf<String>()
    for (event in events) {
      for (call in event.functionCalls()) {
        call.id?.let(callIdsPresent::add)
      }
      for (response in event.functionResponses()) {
        response.id?.let(responseIdsPresent::add)
      }
    }

    // Responses whose originating call is no longer present in the assembled events.
    val orphanedIds = responseIdsPresent - callIdsPresent
    if (orphanedIds.isEmpty()) return events

    // The call events that match the orphaned responses, limited to long-running calls.
    val callEventByOrphanId = mutableMapOf<String, Event>()
    for (event in sourceEvents) {
      for (call in event.functionCalls()) {
        val id = call.id ?: continue
        if (id in orphanedIds && id in event.longRunningToolIds && id !in callEventByOrphanId) {
          callEventByOrphanId[id] = event
        }
      }
    }
    // No long-running calls to recover: leave the list as-is so the downstream rearrange still
    // throws for any unmatched response.
    if (callEventByOrphanId.isEmpty()) return events

    // Final response event per call id, taken from the pre-compaction source. Selected by max
    // timestamp so the newest result is kept.
    val finalResponseEventByID = mutableMapOf<String, Event>()
    for (event in sourceEvents) {
      for (response in event.functionResponses()) {
        val id = response.id ?: continue
        val existing = finalResponseEventByID[id]
        if (existing == null || event.timestamp >= existing.timestamp) {
          finalResponseEventByID[id] = event
        }
      }
    }

    val result = mutableListOf<Event>()
    val reinjectedCallIds = mutableSetOf<String>()
    for (event in events) {
      for (response in event.functionResponses()) {
        val id = response.id ?: continue
        val callEvent = callEventByOrphanId[id] ?: continue
        if (id in reinjectedCallIds) continue

        // Re-inject the whole call event (the call with orphaned response and all its siblings).
        result.add(callEvent)

        // Mark all calls in the event as reinjected so we don't re-inject the sibling calls later
        // again.
        val callIdsInEvent = callEvent.functionCalls().mapNotNull { it.id }
        reinjectedCallIds.addAll(callIdsInEvent)

        // Restore the final response for any sibling call whose response was also compacted
        // away, so the sibling is not left looking like a pending call.
        val siblingResponsesToReinject = LinkedHashSet<Event>()
        for (callId in callIdsInEvent) {
          if (callId !in responseIdsPresent) {
            finalResponseEventByID[callId]?.let(siblingResponsesToReinject::add)
          }
        }
        result.addAll(siblingResponsesToReinject)
      }
      result.add(event)
    }
    return result
  }

  /**
   * Returns the suffix of [events] that belongs to the current turn under [IncludeContents.NONE],
   * as ADK Python's `_get_current_turn_contents` does. The turn starts at the latest user input or
   * event presented as another agent's reply, skipping transfers and posted-back results and
   * reaching back to the call such a result answers. Returns an empty list if nothing can start it.
   */
  private fun getCurrentTurnEvents(
    events: List<Event>,
    agentName: String,
    currentBranch: String?,
    toolCallIds: Set<String>,
  ): List<Event> {
    // A posted-back result continues its call's turn, so the turn reaches back to that call.
    val unmatchedResultIds = mutableSetOf<String>()
    for (i in events.indices.reversed()) {
      unmatchedResultIds.removeAll(events[i].functionCalls().mapNotNull { it.id })
      val isSubmittedResult = isSubmittedToolResult(events[i])
      if (isSubmittedResult) {
        events[i].functionResponses().mapNotNullTo(unmatchedResultIds) {
          it.id?.takeIf { id -> id in toolCallIds }
        }
      }
      if (
        unmatchedResultIds.isNotEmpty() ||
          isSubmittedResult ||
          isDirectTransfer(events[i]) ||
          isStreamFinalAnswer(events[i])
      ) {
        continue
      }
      if (!isCurrentTurnBoundary(events[i], agentName, currentBranch, toolCallIds)) continue
      val start = runStart(events, i, agentName, currentBranch, toolCallIds)
      // A blank spoken run is dropped by the merge, so it cannot start the turn.
      val kind = transcriptionKind(events[i])
      if (
        kind != null &&
          runText(events, start, i, kind, agentName, currentBranch, toolCallIds).isBlank()
      ) {
        continue
      }
      val turn = events.subList(start, events.size)
      // A stream's final answer to a call made before the turn has nothing in it to answer.
      val turnCallIds = turn.functionCallIds()
      return turn.filter { event ->
        !isStreamFinalAnswer(event) || event.functionResponses().any { it.id in turnCallIds }
      }
    }
    return emptyList()
  }

  /**
   * Whether [event] is a result the caller posted back for a long-running call. It continues the
   * call's turn, so a turn started on it would cut the history above the call.
   */
  private fun isSubmittedToolResult(event: Event): Boolean =
    event.author == Role.USER && event.functionResponses().isNotEmpty()

  /**
   * Whether [event] is a live stream's final answer, recorded after the call's first answer. It
   * replaces that answer where it stands rather than continuing a turn.
   */
  private fun isStreamFinalAnswer(event: Event): Boolean =
    event.customMetadata?.get(STREAM_FINAL_ANSWER_KEY) == true &&
      event.functionResponses().isNotEmpty()

  /**
   * Whether [event] is a `transfer_to_agent` call or response. A turn started on one would drop the
   * user input that led to the transfer, so the turn starts before it, as in ADK Python.
   */
  private fun isDirectTransfer(event: Event): Boolean =
    event.actions.transferToAgent != null ||
      event.functionCalls().any { it.name == TransferToAgentTool.TRANSFER_TO_AGENT_TOOL_NAME }

  /**
   * Returns where the transcription run that ends at [end] starts, so the current turn keeps all of
   * a spoken turn rather than its last chunk. Events left out of the context do not break the run.
   */
  private fun runStart(
    events: List<Event>,
    end: Int,
    agentName: String,
    currentBranch: String?,
    toolCallIds: Set<String>,
  ): Int {
    var start = end
    for (i in end - 1 downTo 0) {
      if (!shouldIncludeEventInContext(currentBranch, events[i], agentName, toolCallIds)) continue
      if (!continuesTranscriptionRun(events[i], events[start])) break
      start = i
    }
    return start
  }

  /** The text the merge would give the [kind] run from [start] to [end]. */
  private fun runText(
    events: List<Event>,
    start: Int,
    end: Int,
    kind: TranscriptionKind,
    agentName: String,
    currentBranch: String?,
    toolCallIds: Set<String>,
  ): String =
    (start..end)
      .map { events[it] }
      .filter { shouldIncludeEventInContext(currentBranch, it, agentName, toolCallIds) }
      .joinToString("") { kind.textOf(it) }

  /**
   * Returns whether [event] qualifies as the start of the current turn for [agentName] on
   * [currentBranch]: it must be visible in this agent's context, and it must be a user input or an
   * event presented as another agent's reply (see [isOtherAgentReply]), not an internal/auth/etc.
   * event.
   *
   * An other-agent reply also has to survive [presentOtherAgentMessage], unless it is a spoken
   * chunk, which is narrated once the merge gives it content. Signature and server-side tool parts
   * are visible but have nothing to narrate, so a turn started on one would truncate the history at
   * an event that then contributes nothing, leaving the request with no contents at all.
   */
  private fun isCurrentTurnBoundary(
    event: Event,
    agentName: String,
    currentBranch: String?,
    toolCallIds: Set<String>,
  ): Boolean {
    if (!shouldIncludeEventInContext(currentBranch, event, agentName, toolCallIds)) return false
    if (event.author == Role.USER) return true
    return isOtherAgentReply(agentName, event) &&
      (transcriptionKind(event) != null || presentOtherAgentMessage(event) != null)
  }

  /**
   * Whether [event] is presented to the model as another agent's reply. Every event from a live
   * session counts unless the user wrote it, this agent's own included, since the Live API takes
   * anything after a function response as the model's turn and can stay silent. A spoken chunk is
   * still judged by author, as ADK Python stores speech without a session id.
   */
  private fun isOtherAgentReply(currentAgentName: String, event: Event): Boolean {
    if (event.liveSessionId != null && transcriptionKind(event) == null) {
      return event.author != Role.USER
    }
    return currentAgentName.isNotEmpty() &&
      event.author != currentAgentName &&
      event.author != Role.USER
  }

  /**
   * Presents another agent's message as user context for the current agent.
   *
   * Reformats the event with role='user' and adds an `[agent_name] said:` prefix to provide context
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
      // Blank text is not narrated: such a part is a signature carrier, and a bare "said:" would
      // both pollute the prompt and keep the event alive on nothing. Matches the emptiness rule.
      part.text
        ?.takeIf { it.isNotBlank() }
        ?.let { text -> newParts.add(Part(text = "[${event.author}] said: $text")) }
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
      if ((part.inlineData != null || part.fileData != null) && !part.isAdkLiveArtifact()) {
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
   *
   * Note: `adk_request_input` events are kept. Unlike credential/confirmation requests (whose
   * outcome the framework applies itself), request_input carries a free-form answer the model must
   * read to continue. Matches Python, Java, and Go ADK.
   */
  private fun shouldIncludeEventInContext(
    currentBranch: String?,
    event: Event,
    agentName: String,
    toolCallIds: Set<String>,
  ): Boolean {
    return !(containsEmptyContent(event) ||
      !isEventBelongsToBranch(currentBranch, event) ||
      isToolSubBranchEvent(currentBranch, event, agentName, toolCallIds) ||
      isAdkFrameworkEvent(event) ||
      isAuthEvent(event) ||
      isRequestConfirmationEvent(event))
  }

  /**
   * Check if an event should be skipped due to missing or empty content, such as an event that only
   * changed session state. It is empty when it has no visible content (see [hasContent]) and no
   * transcription has text.
   */
  private fun containsEmptyContent(event: Event): Boolean {
    // Compaction events carry their summary in actions.compaction rather than content; keep them so
    // processCompactionEvents can expand them into summary content.
    if (event.actions.compaction != null) return false

    return !hasContent(event) &&
      event.inputTranscription?.text.isNullOrEmpty() &&
      event.outputTranscription?.text.isNullOrEmpty()
  }

  /** Whether [event] has a role and at least one part that is visible in the LLM context. */
  private fun hasContent(event: Event): Boolean =
    event.content != null &&
      event.content.role != null &&
      event.content.parts.isNotEmpty() &&
      !event.content.parts.all { isPartInvisible(it) }

  /**
   * Returns whether a part is invisible for LLM context.
   *
   * A part is invisible if:
   * - It has no meaningful content (text, inline_data, function_call, function_response, tool_call
   *   or tool_response) and no thought_signature, OR
   * - It is marked as a thought AND contains none of those.
   *
   * Function calls and responses are never invisible, even if marked as thought, because they
   * represent actions that need to be executed or results that need to be processed. Parts carrying
   * a thought signature, and server-side tool calls and responses, are never invisible either,
   * because the caller is required to echo them back on the next request.
   */
  private fun isPartInvisible(part: Part): Boolean {
    // Function calls and responses are never invisible, even if marked as thought
    if (part.functionCall != null || part.functionResponse != null) {
      return false
    }

    // A saveLiveBlob recording is an artifact:// reference that no model can resolve.
    if (part.isAdkLiveArtifact()) {
      return true
    }

    // A thought signature is opaque state to hand back verbatim, and it routinely arrives on a part
    // with nothing else in it, so it has to be checked before the emptiness test below.
    if (part.thoughtSignature?.isNotEmpty() == true) {
      return false
    }

    // Server-side tool calls/responses must be echoed back to the model.
    if (part.toolCall != null || part.toolResponse != null) {
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
    isFunctionCallEvent(event, FunctionCall.REQUEST_EUC_FUNCTION_CALL_NAME)

  /** Checks if the event is a request confirmation event. */
  private fun isRequestConfirmationEvent(event: Event): Boolean =
    isFunctionCallEvent(event, FunctionCall.REQUEST_CONFIRMATION_FUNCTION_CALL_NAME)

  /** Checks if an event is a function call/response for a given function name. */
  private fun isFunctionCallEvent(event: Event, functionName: String): Boolean {
    return event.content?.parts?.any { part ->
      part.functionCall?.name == functionName || part.functionResponse?.name == functionName
    } ?: false
  }

  /**
   * Whether [event] sits on a `<tool>@<call id>` branch below [currentBranch] for a call in
   * [toolCallIds], excluding [agentName]'s own branch when run as a tool. The caller sees these
   * events, while the model receives only the tool's response.
   */
  private fun isToolSubBranchEvent(
    currentBranch: String?,
    event: Event,
    agentName: String,
    toolCallIds: Set<String>,
  ): Boolean {
    val branch =
      event.branch.orEmpty().ifEmpty {
        return false
      }
    val segments = branch.split('.')
    val base = currentBranch?.ifEmpty { null }?.split('.').orEmpty()
    if (segments.size <= base.size || segments.subList(0, base.size) != base) return false
    return segments.drop(base.size).any { isToolBranch(it, agentName, toolCallIds) }
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
      // Identified by shape only: the events themselves hold model text and tool payloads.
      ?: throw IllegalStateException(
        "No function call event found for function response ids: $currentFunctionResponseIds " +
          "among ${events.size} events searched from index $searchFromIndex"
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

    // A live stream's final answer replaces the call's first answer where it stands.
    if (isStreamFinalAnswer(lastEvent)) return events

    // Fast-path (parity with Java/Python): if the immediately-preceding event already contains a
    // matching function_call for the latest function_response, the history is already in the
    // canonical [..., model(function_call), function_response] shape, so return as-is. This
    // preserves the [user, model_fc, tool_fr] shape required when replaying out-of-band
    // long-running tool results, and prevents the merge logic from collapsing unrelated
    // intermediate events into the response.
    val penultimateCallIds = events[events.size - 2].functionCalls().mapNotNull { it.id }.toSet()
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
  }
}

/**
 * The [Event.customMetadata] key that marks a live stream's final answer, which a live run records
 * once it stops the stream or the stream ends on its own.
 */
internal const val STREAM_FINAL_ANSWER_KEY = "adk_stream_final_answer"
