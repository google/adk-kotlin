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
  suspend fun run(nodeInput: Any?): Context = Activation(newContext()).run(nodeInput)

  private fun newContext(): Context {
    val nodePath = Context.buildNodePath(parent.nodePath, node.name, runId)
    // TODO: on resume, recovering interrupt answers from session history (ResumeScan.answersFor) is
    // added in a later change; until then only explicitly-passed resume inputs are used.
    return Context(
      invocationContext = childInvocationContext(),
      node = node,
      eventSink = parent.requireNodeState().eventSink,
      parent = parent,
      runId = runId,
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

  /**
   * State for a single execution of [node].
   *
   * Runs the node, stamps each emitted event with its author and path, and forwards it to the event
   * sink. If the stream includes events from nested nodes (such as a sub-workflow or child agent),
   * we pass them through without re-applying their routing or agent transfers. When the node
   * finishes, we send one final event for any output, routes, or state changes that weren't already
   * sent on an earlier event.
   */
  private inner class Activation(val context: Context) {
    private val nodeState = context.requireNodeState()

    suspend fun run(nodeInput: Any?): Context {
      try {
        asBaseNode(node).run(context, nodeInput).collect(::dispatch)
        flushPending()
      } catch (e: NodeInterruptedException) {
        // A dynamically dispatched child interrupted. Its ids are already on the context, and the
        // node is waiting rather than failing, so this attempt counts as finished.
        flushPending()
      } catch (e: DynamicNodeFailedException) {
        // A dynamically dispatched child failed; carry its failure up. Dynamic dispatch lands in a
        // later change, so nothing raises this yet.
        nodeState.failure = NodeExecutionFailure(e.error, e.errorNodePath)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        fail(e)
      }
      return context
    }

    /** Records the event's output and routing on [context], then stamps and forwards the event. */
    private suspend fun dispatch(event: Event) {
      track(event)
      send(event)
    }

    /** Updates [context] with any output, interrupts, or routing carried by [event]. */
    private fun track(event: Event) {
      when {
        event.output != null -> context.output = event.output
        // A final message-as-output event has no separate output; its content is the node's output.
        event.isMessageAsOutput -> context.output = event.content
      }
      if (event.longRunningToolIds.isNotEmpty()) nodeState.addInterruptIds(event.longRunningToolIds)
      // Only apply routing from this node's own events; events from nested nodes were already
      // handled at their own level.
      val isOwnEvent = event.author.isEmpty() || event.author == node.name
      if (isOwnEvent) {
        event.actions.route?.let {
          context.routes = it
          nodeState.routesEmitted = true
        }
        event.actions.transferToAgent?.let { context.actions.transferToAgent = it }
      }
    }

    /** Stamps the event, flushes any pending deltas onto it (unless partial), and sends it. */
    private suspend fun send(event: Event) {
      // When content carries message-as-output, clear output to prevent duplicate text on the wire.
      val stamped = stamp(if (event.isMessageAsOutput) event.copy(output = null) else event)
      val outgoing = if (stamped.partial) stamped else withPendingDeltas(stamped)
      nodeState.eventSink.send(outgoing)

      if (outgoing.output != null) {
        nodeState.markOutputEmitted()
      } else if (
        event.isMessageAsOutput && context.hasProducedOutput && !nodeState.hasEmittedOutput
      ) {
        // The output was already sent as this event's content, so mark it emitted to avoid sending
        // a duplicate output event in flushPending.
        nodeState.markOutputEmitted()
      }
    }

    /** Emits a final event for any output, routes, or deltas not yet sent on an event. */
    private suspend fun flushPending() {
      val hasPendingOutput = context.hasProducedOutput && !nodeState.hasEmittedOutput
      val hasPendingRoute = context.routes != null && !nodeState.routesEmitted
      val hasDeltas =
        context.actions.stateDelta.isNotEmpty() || context.actions.artifactDelta.isNotEmpty()
      if (!hasPendingOutput && !hasPendingRoute && !hasDeltas) return

      // Construct the event directly instead of calling stamp(), which only sets outputFor when
      // Event.output is non-null (a deferred output can be null).
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
      nodeState.eventSink.send(withPendingDeltas(event))

      if (hasPendingOutput) nodeState.markOutputEmitted()
      if (hasPendingRoute) nodeState.routesEmitted = true
    }

    /** Records the exception as a node failure and emits an error event carrying pending deltas. */
    private suspend fun fail(e: Exception) {
      // Only Exceptions become node failures; Errors are fatal and propagate. Attach any pending
      // state deltas to the error event so state changes made before the failure are not lost.
      val errorEvent =
        withPendingDeltas(
          stamp(
            Event(
              author = "",
              errorCode = (e as? NodeExecutionException)?.typeName ?: e::class.simpleName,
              errorMessage = e.message ?: "",
            )
          )
        )
      nodeState.eventSink.send(errorEvent)
      nodeState.failure = NodeExecutionFailure(e, context.nodePath)
    }

    /**
     * Moves the context's pending deltas onto the event, so each change is written exactly once.
     */
    private fun withPendingDeltas(event: Event): Event {
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
    private fun stamp(event: Event): Event =
      event.copy(
        author = context.eventAuthor.ifEmpty { node.name },
        invocationId = context.invocationContext.invocationId,
        branch = event.branch ?: context.invocationContext.branch,
        nodeInfo =
          (event.nodeInfo ?: NodeInfo()).copy(
            path = context.nodePath,
            // A message-as-output event carries the node's output as its content (send() has
            // cleared the output field), so it is stamped outputFor too. Python sets output_for
            // only when the output field is set (_node_runner.py).
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
}

/**
 * A final content event whose content *is* the node's output, so no separate output event follows.
 */
private val Event.isMessageAsOutput: Boolean
  get() = !partial && nodeInfo?.messageAsOutput == true && content != null

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
