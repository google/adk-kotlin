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

import com.google.adk.kt.agents.Context
import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.events.Event
import com.google.adk.kt.events.EventActions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

/**
 * Runs one node to completion and returns the context holding its results.
 *
 * This is where a node's declared behavior is applied: the stamping that gives every event its
 * author and its place in the graph. The scheduler reads `output`, `routes` and `interruptIds` off
 * the returned context.
 */
internal class NodeRunner(
  private val node: Node,
  private val parent: Context,
  private val runId: String = "1",
  private val resumeInputs: Map<String, Any?> = emptyMap(),
  private val useSubBranch: Boolean = false,
  private val overrideBranch: String? = null,
) {

  /** Runs the node and returns the context of the run. */
  suspend fun run(nodeInput: Any?): Context {
    val context = newContext(attempt = 1)
    try {
      runAndDispatchEvents(context, nodeInput)
      emitPendingOutputAndRoutes(context)
      return context
    } catch (e: NodeInterruptedException) {
      // A dynamically dispatched child interrupted. Its ids are already on the context, and the
      // node is waiting rather than failing, so this attempt counts as finished.
      emitPendingOutputAndRoutes(context)
      return context
    } catch (e: DynamicNodeFailedException) {
      context.requireNodeState().failure = NodeExecutionFailure(e.error, e.errorNodePath)
      return context
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // Only an Exception becomes a node failure; an Error stays fatal and propagates.
      // A failed attempt is reported. The author is left empty here and assigned by stamp,
      // the single place events are attributed. Pending deltas ride the error event so a state
      // change made before the failure is not lost.
      val errorEvent =
        withPendingDeltas(
          stamp(
            Event(
              author = "",
              errorCode = (e as? NodeExecutionException)?.typeName ?: e::class.simpleName,
              errorMessage = e.message ?: "",
            ),
            context,
          ),
          context,
        )
      context.requireNodeState().eventSink.send(errorEvent)
      context.requireNodeState().failure = NodeExecutionFailure(e, context.nodePath)
      return context
    }
  }

  private fun newContext(attempt: Int): Context {
    val nodePath = Context.buildNodePath(parent.nodePath, node.name, runId)
    val invocationContext = childInvocationContext()
    // TODO: on resume, recovering interrupt answers from session history (ResumeScan.answersFor) is
    // added in a later change; until then only explicitly-passed resume inputs are used.
    return Context(
      invocationContext = invocationContext,
      node = node,
      eventSink = parent.requireNodeState().eventSink,
      parent = parent,
      runId = runId,
      attemptCount = attempt,
      resumeInputs = resumeInputs,
      nodePath = nodePath,
    )
  }

  /**
   * The invocation context the node runs against. A fanned-out node runs on a sub-branch derived
   * from its base branch; an override branch (a single successor inheriting its predecessor's, or a
   * join re-merging to the common prefix) replaces the inherited one; otherwise the parent's
   * context is shared unchanged. The branch keeps one parallel branch's events out of a sibling's
   * LLM history.
   */
  private fun childInvocationContext(): InvocationContext {
    val ic = parent.invocationContext
    val base = overrideBranch ?: ic.branch
    return when {
      useSubBranch -> ic.copy(branch = BranchPath.subBranch(base, node.name, runId))
      overrideBranch != null -> ic.copy(branch = overrideBranch)
      else -> ic
    }
  }

  /** Runs the node and, for each event it emits, records it on the context and sends it on. */
  private suspend fun runAndDispatchEvents(context: Context, nodeInput: Any?) {
    asBaseNode(node).run(context, nodeInput).collect { event ->
      track(event, context)
      send(event, context)
    }
  }

  /**
   * Adapts any [Node] to a [BaseNode] so the engine's execution and normalization loop can run it.
   */
  private fun asBaseNode(node: Node): BaseNode =
    node as? BaseNode
      ?: object :
        BaseNode(
          name = node.name,
          description = node.description,
          rerunOnResume = node.rerunOnResume,
          waitForOutput = node.waitForOutput,
        ) {
        override val requiresAllPredecessors: Boolean
          get() = node.requiresAllPredecessors

        override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> =
          node.runNode(context, nodeInput)
      }

  /** Records on the context what an event reports, so the scheduler can read it back. */
  private fun track(event: Event, context: Context) {
    if (event.output != null) {
      context.output = event.output
    } else if (!event.partial && event.nodeInfo?.messageAsOutput == true && event.content != null) {
      // A final message-as-output event has no separate output; its content is the node's output.
      context.output = event.content
    }
    if (event.longRunningToolIds.isNotEmpty()) {
      context.requireNodeState().addInterruptIds(event.longRunningToolIds)
    }
    // Only a node's own event speaks for it; one forwarded from a descendant has already been
    // acted on further down, and re-reading it here would bubble a decision twice.
    val isOwnEvent = event.author.isEmpty() || event.author == node.name
    if (isOwnEvent) {
      event.actions.route?.let {
        context.routes = it
        context.requireNodeState().routesEmitted = true
      }
      event.actions.transferToAgent?.let { context.actions.transferToAgent = it }
    }
  }

  private suspend fun send(event: Event, context: Context) {
    val ns = context.requireNodeState()
    var outgoing = event

    // When content carries message-as-output, clear output to prevent duplicate text on the wire.
    val messageAsOutput =
      !outgoing.partial && outgoing.nodeInfo?.messageAsOutput == true && outgoing.content != null
    if (messageAsOutput) {
      outgoing = outgoing.copy(output = null)
    }

    outgoing = stamp(outgoing, context)
    if (!outgoing.partial) outgoing = withPendingDeltas(outgoing, context)
    ns.eventSink.send(outgoing)

    if (outgoing.output != null) {
      ns.markOutputEmitted()
    } else if (messageAsOutput && context.hasProducedOutput && !ns.hasEmittedOutput) {
      // The content event delivered the output, so suppress the deferred output event.
      ns.markOutputEmitted()
    }
  }

  /** Emits a final event for any output, routes, or deltas not yet sent on an event. */
  private suspend fun emitPendingOutputAndRoutes(context: Context) {
    val ns = context.requireNodeState()
    val hasPendingOutput = context.hasProducedOutput && !ns.hasEmittedOutput
    val hasPendingRoute = context.routes != null && !ns.routesEmitted
    val hasDeltas =
      context.actions.stateDelta.isNotEmpty() || context.actions.artifactDelta.isNotEmpty()
    if (!hasPendingOutput && !hasPendingRoute && !hasDeltas) return

    val event =
      Event(
        author = context.eventAuthor.ifEmpty { node.name },
        invocationId = context.invocationContext.invocationId,
        branch = context.invocationContext.branch,
        output = if (hasPendingOutput) context.output else null,
        actions = EventActions(route = if (hasPendingRoute) context.routes else null),
        nodeInfo =
          NodeInfo(
            path = context.nodePath,
            outputFor = if (hasPendingOutput) listOf(context.nodePath) else null,
          ),
      )
    val outgoing = withPendingDeltas(event, context)
    ns.eventSink.send(outgoing)

    if (hasPendingOutput) ns.markOutputEmitted()
    if (hasPendingRoute) ns.routesEmitted = true
  }

  /** Moves the context's pending deltas onto the event, so each change is written exactly once. */
  private fun withPendingDeltas(event: Event, context: Context): Event {
    val stateDelta = context.actions.stateDelta
    val artifactDelta = context.actions.artifactDelta
    if (stateDelta.isEmpty() && artifactDelta.isEmpty()) return event

    event.actions.stateDelta.putAll(stateDelta)
    event.actions.artifactDelta.putAll(artifactDelta)
    stateDelta.clear()
    artifactDelta.clear()
    return event
  }

  /** Attributes an event to its author and to the node activation that produced it. */
  private fun stamp(event: Event, context: Context): Event =
    event.copy(
      author = context.eventAuthor.ifEmpty { node.name },
      invocationId = context.invocationContext.invocationId,
      branch = event.branch ?: context.invocationContext.branch,
      nodeInfo =
        (event.nodeInfo ?: NodeInfo()).copy(
          path = context.nodePath,
          // A message-as-output event carries the node's output as its content (send() has cleared
          // the output field), so it is stamped outputFor too. This diverges from Python, which
          // sets output_for only when the output field is set (_node_runner.py:462).
          outputFor =
            if (
              event.output != null || (!event.partial && event.nodeInfo?.messageAsOutput == true)
            ) {
              listOf(context.nodePath)
            } else {
              event.nodeInfo?.outputFor
            },
        ),
    )
}
