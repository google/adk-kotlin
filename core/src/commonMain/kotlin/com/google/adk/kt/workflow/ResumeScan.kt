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

@file:OptIn(ExperimentalWorkflowApi::class, FrameworkInternalApi::class)

package com.google.adk.kt.workflow

import com.google.adk.kt.SchemaUtils
import com.google.adk.kt.ValueCoercer
import com.google.adk.kt.agents.Context
import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.events.Event
import com.google.adk.kt.serialization.adkJson
import com.google.adk.kt.serialization.anyToJsonElement
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.Schema
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * Reconstructed execution state of a node from earlier in this invocation (in an earlier turn or an
 * earlier attempt).
 */
internal class RecoveredNode {
  var output: Any? = null
  var errorCode: String? = null
  var route: List<Route>? = null
  var branch: String? = null
  var transferToAgent: String? = null
  val interruptIds: MutableSet<String> = mutableSetOf()

  /** The [interruptIds] the node raised itself, not a node under it. */
  val directInterruptIds: MutableSet<String> = mutableSetOf()
  val resolvedResponses: MutableMap<String, Any?> = mutableMapOf()

  /** Whether the node's last direct event, since its last answer, shows it finished. */
  var finishedAfterResume: Boolean = false

  /**
   * Whether a user message in the node's scope, not just answers, followed its last direct event.
   */
  var userMessageSinceLastEvent: Boolean = false

  /** The isolation scope of the node's last direct event. */
  var isolationScope: String? = null

  /** The isolation scopes of the node's descendants, such as the tasks it delegated to. */
  val nestedScopes: MutableSet<String> = mutableSetOf()

  /** Whether the user replied to one of those tasks after the node's last direct event. */
  var replyToNestedTaskSinceLastEvent: Boolean = false

  /** Interrupt IDs raised by this node that have not yet been answered. */
  val unresolved: Set<String>
    get() = interruptIds - resolvedResponses.keys

  /** Records [answer] to [interruptId]; what the node emitted before asking is not its result. */
  fun recordAnswer(interruptId: String, answer: Any?) {
    output = null
    finishedAfterResume = false
    resolvedResponses[interruptId] = answer
  }
}

/**
 * Replay decision and recovered data for a previously visited node. [interrupts] are the node's
 * open interrupts: a replay waits on them, and a rerun that ends without a result keeps them.
 */
internal data class Interception(
  val shouldRun: Boolean,
  val output: Any? = null,
  val route: List<Route>? = null,
  val branch: String? = null,
  val interrupts: Set<String> = emptySet(),
  val resumeInputs: Map<String, Any?> = emptyMap(),
  val transferToAgent: String? = null,
)

/**
 * Reconstructs workflow execution state from session events and decides how each node resumes.
 *
 * On a resumed turn or retry attempt, [scan] and [answersFor] fold the invocation's event log into
 * [RecoveredNode] snapshots (outputs, routes, transfers, errors, and interrupt answers). When the
 * scheduler or [Context.runNode] reaches a node, [intercept] decides whether to run it again or
 * replay its prior result via [replayContext].
 */
internal object ResumeScan {

  /** Returns the session events of [context]'s invocation, the only ones a resume reads. */
  fun invocationEvents(context: InvocationContext): List<Event> =
    context.session.events.filter { it.invocationId == context.invocationId }

  /** Reconstructs direct child nodes under [basePath], keyed by `name@runId`. */
  fun scan(
    events: List<Event>,
    basePath: String,
    invocationId: String,
  ): Map<String, RecoveredNode> =
    reconstruct(events, basePath, invocationId, groupByDirectChild = true)

  /**
   * Returns resolved interrupt answers for [nodePath] and its descendants, keyed by interrupt ID.
   */
  fun answersFor(events: List<Event>, nodePath: String, invocationId: String): Map<String, Any?> =
    reconstruct(events, nodePath, invocationId, groupByDirectChild = false)[nodePath]
      ?.resolvedResponses
      .orEmpty()

  /**
   * Folds non-partial [events] from [invocationId] into per-node state in one forward pass. A user
   * event can only answer an interrupt; any other event reports progress of the node that wrote it.
   */
  private fun reconstruct(
    events: List<Event>,
    basePath: String,
    invocationId: String,
    groupByDirectChild: Boolean,
  ): Map<String, RecoveredNode> {
    val reconstruction = Reconstruction(basePath, groupByDirectChild)
    for (event in events) {
      if (event.invocationId != invocationId || event.partial) continue
      if (event.author == Role.USER) {
        reconstruction.recordUserEvent(event)
      } else {
        reconstruction.recordNodeEvent(event)
      }
    }
    return reconstruction.recovered
  }

  /**
   * State of one pass over the event log. Events are attributed to a key: the `name@runId` child
   * segment under [basePath] when [groupByDirectChild] is set, otherwise [basePath] itself.
   */
  private class Reconstruction(
    private val basePath: String,
    private val groupByDirectChild: Boolean,
  ) {
    /** Reconstructed nodes keyed by owner, in the order they first appear. */
    val recovered = linkedMapOf<String, RecoveredNode>()

    /** Node that raised each pending interrupt, so a later user answer can find its owner. */
    private val interruptOwner = mutableMapOf<String, RecoveredNode>()

    /** Response schema each `adk_request_input` carried, to validate its answer when it arrives. */
    private val schemas = mutableMapOf<String, Schema>()

    /** Ids of every call in the invocation, so a response to one is not a new user message. */
    private val callIds = mutableSetOf<String>()

    private val childPrefix = BranchPath.childNodePath(basePath, "")

    /** Attributes [event] to its owner and records what it says about that node. */
    fun recordNodeEvent(event: Event) {
      event.functionCalls().mapNotNullTo(callIds) { it.id }
      val eventPath = event.nodeInfo?.path.orEmpty()
      val key = ownerKey(eventPath) ?: return
      val ownerPath = if (groupByDirectChild) BranchPath.childNodePath(basePath, key) else key
      // False when `ownerPath` (a sub-workflow or a node calling `Context.runNode`) ran a nested
      // child node whose event carries a deeper path such as `$ownerPath/nested@1`.
      val isDirect = eventPath == ownerPath
      val node = recovered.getOrPut(key) { RecoveredNode() }

      // Order matters: the finish check reads the error state and the interrupts raised here.
      recordOutcome(event, isDirect, ownerPath, node)
      val raisedAny = recordRaisedInterrupts(event, node, isDirect)
      if (!isDirect) {
        event.isolationScope?.let(node.nestedScopes::add)
      } else {
        node.userMessageSinceLastEvent = false
        node.replyToNestedTaskSinceLastEvent = false
        node.isolationScope = event.isolationScope
        node.finishedAfterResume =
          !raisedAny &&
            node.errorCode == null &&
            event.functionCalls().isEmpty() &&
            event.functionResponses().isEmpty()
      }
    }

    /**
     * Records the answers in a user [event]. Anything else in it, such as text or a response to no
     * recorded call, is a new user message for the nodes seen so far in the event's scope.
     */
    fun recordUserEvent(event: Event) {
      recordAnswers(event)
      val parts = event.content?.parts.orEmpty()
      val onlyAnswers = parts.isNotEmpty() && parts.all { it.functionResponse?.id in callIds }
      // A node's injected input or task answer carries its path; it is not the user writing.
      if (onlyAnswers || !event.nodeInfo?.path.isNullOrEmpty()) return
      val scope = event.isolationScope
      for (node in recovered.values) {
        // A single-turn helper shares its caller's scope, so a message there is not for a task.
        if (node.isolationScope == scope) node.userMessageSinceLastEvent = true
        else if (scope in node.nestedScopes) node.replyToNestedTaskSinceLastEvent = true
      }
    }

    /**
     * Resolves the interrupts a user's function responses answer.
     *
     * User events the runner stores carry no `nodeInfo`, so every user `FunctionResponse` reaches
     * here. When `interruptOwner[id]` is null, the response either belongs to a node outside
     * [basePath] or answers an inner prompt on a tool sub-branch named after an outer call ID (e.g.
     * `wf@1.task@ask_1`), in which case [interruptsMatchingBranch] attributes the answer to
     * `ask_1`.
     */
    fun recordAnswers(event: Event) {
      if (interruptOwner.isEmpty()) return
      for (response in event.functionResponses()) {
        val id = response.id ?: continue
        val targets =
          interruptOwner[id]?.let { listOf(it to id) } ?: interruptsMatchingBranch(event.branch)
        if (targets.isEmpty()) continue
        val answer = validateAnswer(schemas[id], response.response, id)
        for ((node, interruptId) in targets) node.recordAnswer(interruptId, answer)
      }
    }

    /**
     * Pending `(node, interruptId)` pairs whose interrupt ID is one of [branch]'s segment run IDs,
     * used to resolve an outer tool-call interrupt when a reply arrives on its sub-branch.
     */
    private fun interruptsMatchingBranch(branch: String?): List<Pair<RecoveredNode, String>> {
      val branchRunIds = BranchPath.runIds(branch)
      if (branchRunIds.isEmpty()) return emptyList()
      return recovered.values.flatMap { node ->
        node.interruptIds.filter { it in branchRunIds }.map { node to it }
      }
    }

    /** Returns the key owning [eventPath], or null if it lies outside [basePath]. */
    private fun ownerKey(eventPath: String): String? =
      when {
        eventPath == basePath -> if (groupByDirectChild) null else basePath
        eventPath.startsWith(childPrefix) ->
          if (groupByDirectChild) eventPath.removePrefix(childPrefix).substringBefore('/')
          else basePath
        else -> null
      }

    /**
     * Records output, route, transfer, and error state for [ownerPath] from [event].
     *
     * Descendant events are grouped under [ownerPath] so their interrupts bubble up to [node], but
     * their outcomes belong to the descendant unless it delegated its output to [ownerPath] via
     * `useAsOutput = true` (`isDelegated`).
     */
    private fun recordOutcome(
      event: Event,
      isDirect: Boolean,
      ownerPath: String,
      node: RecoveredNode,
    ) {
      val eventOutput =
        event.output ?: event.content?.takeIf { event.nodeInfo?.messageAsOutput == true }
      val isDelegated =
        eventOutput != null && event.nodeInfo?.outputFor?.contains(ownerPath) == true
      if (!isDirect && !isDelegated) return

      if (eventOutput != null) {
        node.output = eventOutput
        // A delegate's output event carries the delegate's branch, not this node's.
        if (isDirect && event.output != null) node.branch = event.branch
      }
      event.actions.route?.let { node.route = it }
      event.actions.transferToAgent?.let {
        node.transferToAgent = it
        // A transfer has no output, so keep its branch for a child target that resumes under it.
        node.branch = event.branch
      }
      // A result or later non-error event clears an error; an error drops the attempt's result.
      val hasResult =
        eventOutput != null || event.actions.route != null || event.actions.transferToAgent != null
      if (hasResult || event.errorCode == null) {
        node.errorCode = null
      } else {
        node.output = null
        node.route = null
        node.transferToAgent = null
        node.errorCode = event.errorCode
      }
    }

    /**
     * Registers the interrupts [event] raises on [node] (both long-running tool calls and
     * `adk_request_input` calls along with their `response_schema`) and returns whether any were
     * raised. An interrupt raised again counts as unanswered until its new answer arrives.
     */
    private fun recordRaisedInterrupts(
      event: Event,
      node: RecoveredNode,
      isDirect: Boolean,
    ): Boolean {
      val requests = event.functionCalls().filter { it.name == RequestInput.FUNCTION_CALL_NAME }
      val raised = event.longRunningToolIds + requests.mapNotNull { it.id }
      for (id in raised) {
        node.interruptIds.add(id)
        node.resolvedResponses.remove(id)
        if (isDirect) node.directInterruptIds.add(id)
        interruptOwner[id] = node
      }
      for (request in requests) {
        val id = request.id ?: continue
        readSchema(request.args[RequestInput.RESPONSE_SCHEMA_KEY])?.let { schemas[id] = it }
      }
      return raised.isNotEmpty()
    }
  }

  /**
   * Determines whether [node] should run or replay from [recovered]. With no recorded output or
   * route, a node [cancelledMidRun] in this turn reruns, and a [Node.rerunOnResume] or
   * [Node.waitForOutput] node reruns until it finishes its turn, the latter again after a new user
   * message or a reply to a task it delegated. Otherwise a [dynamic] node reruns while a static one
   * does not.
   */
  fun intercept(
    node: Node,
    recovered: RecoveredNode?,
    dynamic: Boolean = false,
    cancelledMidRun: Boolean = false,
  ): Interception {
    if (recovered == null) return Interception(shouldRun = true)
    if (node is Workflow) {
      return Interception(shouldRun = true, resumeInputs = recovered.resolvedResponses)
    }
    val resolved = recovered.resolvedResponses
    val transfer = recovered.transferToAgent
    val branch = recovered.branch
    val unresolved = recovered.unresolved

    if (unresolved.isNotEmpty()) {
      // A reply to a task the node delegated reruns the node, so the task gets the reply.
      val progressed = resolved.isNotEmpty() || recovered.replyToNestedTaskSinceLastEvent
      if (node.rerunOnResume && progressed) {
        // Follow a recorded handoff so its target gets the new input, unless the node still waits.
        if (transfer == null) {
          // An agent can't go past an open call; another node may go on without it, as in Python.
          val open = if (node is LlmAgent) unresolved else emptySet()
          return Interception(shouldRun = true, interrupts = open, resumeInputs = resolved)
        }
        if (unresolved.none { it in recovered.directInterruptIds }) {
          return Interception(shouldRun = false, branch = branch, transferToAgent = transfer)
        }
      }
      return Interception(
        shouldRun = false,
        branch = branch,
        interrupts = unresolved,
        transferToAgent = transfer,
      )
    }

    if (recovered.errorCode != null) {
      return Interception(shouldRun = true, resumeInputs = resolved, transferToAgent = transfer)
    }

    val output = recovered.output
    if (output != null || recovered.route != null || transfer != null) {
      return Interception(
        shouldRun = false,
        output = if (output is Content) processRehydratedOutput(node, output) else output,
        route = recovered.route,
        branch = branch,
        transferToAgent = transfer,
      )
    }

    // A node cancelled mid-run in this turn left progress events but no result.
    if (cancelledMidRun) return Interception(shouldRun = true, resumeInputs = resolved)

    if (recovered.interruptIds.isNotEmpty() && !node.rerunOnResume) {
      val answerOutput = if (resolved.size == 1) resolved.values.single() else resolved.toMap()
      return Interception(
        shouldRun = false,
        output = answerOutput,
        branch = branch,
        transferToAgent = transfer,
      )
    }

    // A finished node stays done, unless the user writes to it or to a task it delegated.
    val repliedTo = recovered.userMessageSinceLastEvent || recovered.replyToNestedTaskSinceLastEvent
    val shouldRun =
      when {
        node.waitForOutput -> !recovered.finishedAfterResume || repliedTo
        node.rerunOnResume -> !recovered.finishedAfterResume
        else -> dynamic
      }
    return Interception(
      shouldRun = shouldRun,
      branch = branch,
      resumeInputs = resolved,
      transferToAgent = transfer,
    )
  }

  /**
   * Builds a [Context] carrying the recovered [interception] state without running [node]. The node
   * replays on the branch it recorded, or else on the branch of [scheduled], the context that it
   * would run against.
   */
  fun replayContext(
    parent: Context,
    scheduled: InvocationContext,
    node: Node,
    runId: String,
    useAsOutput: Boolean,
    interception: Interception,
  ): Context {
    val invocationContext =
      if (interception.branch != null) scheduled.copy(branch = interception.branch) else scheduled
    val context =
      Context(
        invocationContext = invocationContext,
        node = node,
        eventSink = parent.requireNodeState().eventSink,
        parent = parent,
        runId = runId,
        useAsOutput = useAsOutput,
      )
    val state = context.requireNodeState()
    state.isReplay = true
    interception.output?.let { context.output = it }
    interception.route?.let {
      context.routes = it
      state.routesEmitted = true
    }
    interception.transferToAgent?.let { context.actions.transferToAgent = it }
    state.addInterruptIds(interception.interrupts)
    return context
  }

  /**
   * Reconstructs a message-as-output node's value from [content], decoding it from JSON when
   * [Node.outputSchema] is a non-string schema.
   */
  internal fun processRehydratedOutput(node: Node, content: Content): Any? {
    // A model Content with only thought parts has blank text(), which produces no output.
    val text = content.text().trim()
    if (text.isEmpty()) return null
    val schema = node.outputSchema ?: return text
    // Model text is raw rather than JSON-quoted, so keep it as-is when the schema accepts strings.
    if (SchemaUtils.acceptsString(schema)) return text
    return SchemaUtils.readJson(text).getOrElse {
      throw IllegalArgumentException(
        "Validation failed for rehydrated output against schema: ${it.message}",
        it,
      )
    }
  }
}

/** Decodes a response [Schema] from an interrupt event's `response_schema` argument. */
private fun readSchema(value: Any?): Schema? =
  when (value) {
    null -> null
    is Schema -> value
    else ->
      runCatching { adkJson.decodeFromJsonElement<Schema>(anyToJsonElement(value)) }.getOrNull()
  }

/**
 * Unwraps an interrupt [response] and returns it as [schema] reads it. A `{"result": value}`
 * envelope is unwrapped, and string values inside it are parsed as JSON unless [schema] accepts a
 * string; a null answer fails a schema that does not admit null.
 */
internal fun validateAnswer(schema: Schema?, response: Any?, interruptId: String): Any? {
  val answer = unwrapAnswer(schema, response)
  if (schema == null) return answer
  return ValueCoercer.coerceValue(answer, schema, "answer").getOrElse {
    throw IllegalArgumentException(
      "Validation failed for interrupt $interruptId: ${it.message}",
      it,
    )
  }
}

private fun unwrapAnswer(schema: Schema?, response: Any?): Any? {
  if (response !is Map<*, *> || response.keys.singleOrNull() != BaseTool.RESULT_KEY) return response
  val value = response[BaseTool.RESULT_KEY]
  if (value !is String || (schema != null && SchemaUtils.acceptsString(schema))) return value
  return SchemaUtils.readJson(value).getOrDefault(value)
}

/** Returns the first text message the user sent in [invocationId], or null if there is none. */
internal fun findUserMessageForInvocation(events: List<Event>, invocationId: String): Content? {
  for (event in events) {
    if (
      event.invocationId == invocationId &&
        event.author == "user" &&
        event.content != null &&
        event.content.parts.isNotEmpty() &&
        event.content.parts[0].text?.isNotEmpty() == true
    ) {
      return event.content
    }
  }
  return null
}
