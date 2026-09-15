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

package com.google.adk.kt.workflow

import com.google.adk.kt.agents.TypedData
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.events.Event
import com.google.adk.kt.events.EventActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select

/** One run of one workflow's scheduling loop. Discarded when the run ends. */
@ExperimentalWorkflowApi
internal class Scheduler(
  private val workflow: Workflow,
  private val graph: Graph,
  private val context: NodeContext,
) {
  private val nodeStates = mutableMapOf<String, NodeState>()
  private val nodeOutputs = mutableMapOf<String, Any?>()
  // The execution branch of each completed node, used by join nodes to re-merge parallel branches
  // to their common prefix.
  private val nodeBranches = mutableMapOf<String, String?>()
  private val triggerQueue = TriggerQueue()
  private val interruptIds = mutableSetOf<String>()
  private var failed = false

  // TODO: on resume, reconstructing progress from session history (ResumeScan) and fast-forwarding
  // already-completed nodes is added in a later CL; this engine runs every node fresh.

  private val isResumable: Boolean
    get() = context.invocationContext.isResumable

  suspend fun run(nodeInput: Any?) {
    enqueueStartTriggers(nodeInput)
    coroutineScope {
      val running = linkedMapOf<String, Deferred<NodeContext>>()

      while (true) {
        scheduleReady(this, running)
        if (running.isEmpty()) break

        // `select` is biased to registration order, and `running` preserves the order the nodes
        // were scheduled in, so two nodes finishing together are handled in a stable order.
        val (finishedName, childContext) =
          select<Pair<String, NodeContext>> {
            for ((name, task) in running) task.onAwait { name to it }
          }
        running.keys.remove(finishedName)

        val failure = childContext.failure
        if (failure != null) {
          nodeStates.getValue(finishedName).status = NodeStatus.FAILED
          context.failure = failure
          failed = true
          // Leaving the scope cancels every sibling still running; record that they were
          // cancelled rather than leaving their status at RUNNING.
          for (name in running.keys) nodeStates[name]?.status = NodeStatus.CANCELLED
          for (task in running.values) task.cancel()
          break
        }
        handleCompletion(finishedName, childContext)
      }
    }
    if (failed) return
    collectRemainingInterrupts()
    finalize()
    if (interruptIds.isEmpty()) emitEndOfAgent()
  }

  private fun enqueueStartTriggers(nodeInput: Any?) {
    val startEdges = graph.edges.filter { it.from.name == START_NODE_NAME }
    // START fanning out to more than one node puts each successor on its own sub-branch.
    val useSubBranch = startEdges.size > 1
    for (edge in startEdges) {
      if (edge.to.requiresAllPredecessors) {
        // A wait-for-all node fed by START must still wait for its other predecessors, so record
        // START's contribution and let the barrier decide when to fire.
        nodeOutputs[START_NODE_NAME] = nodeInput
        nodeBranches[START_NODE_NAME] = context.invocationContext.branch
        enqueueBarrierTrigger(edge.to.name)
        continue
      }
      triggerQueue.enqueue(edge.to.name, Trigger(nodeInput, useSubBranch = useSubBranch))
    }
  }

  private suspend fun scheduleReady(
    scope: CoroutineScope,
    running: MutableMap<String, Deferred<NodeContext>>,
  ) {
    for (nodeName in triggerQueue.queuedNodeNames) {
      if (nodeName in running) continue
      val state = nodeStates[nodeName]
      // One activation of a node at a time, so two triggers cannot race on its state. A node
      // waiting on a user keeps its queue; one merely waiting for output may take the next
      // trigger, which is how a join accumulates.
      if (state != null) {
        if (state.status == NodeStatus.RUNNING) continue
        if (state.status == NodeStatus.WAITING && state.interrupts.isNotEmpty()) continue
      }
      if (workflow.maxConcurrency != null && running.size >= workflow.maxConcurrency) break

      val trigger = triggerQueue.poll(nodeName) ?: continue
      // Only a node that genuinely runs marks a new step; one fast-forwarded from history must
      // not re-announce itself.
      if (start(scope, running, nodeName, trigger)) emitCheckpoint()
    }
  }

  // Confined to the scheduling coroutine: state mutations and queue access happen sequentially on
  // this single thread of execution.
  @Suppress("UnsafeCoroutineCrossing")
  private fun start(
    scope: CoroutineScope,
    running: MutableMap<String, Deferred<NodeContext>>,
    nodeName: String,
    trigger: Trigger,
  ): Boolean {
    // A fresh activation gets a fresh state so nothing carries over, but keeps the run counter so
    // its path stays unique.
    nodeStates[nodeName] =
      (nodeStates[nodeName]?.forNewRun() ?: NodeState()).apply {
        status = NodeStatus.RUNNING
        runId = runId ?: nextRunId()
      }
    val runId = nodeStates.getValue(nodeName).runId!!

    // TODO: on resume, intercepting a node recovered from history (replaying a completed node or
    // resuming a waiting one) is added in a later CL; this engine always runs the node.
    running[nodeName] = scope.async {
      NodeRunner(
          node = graph.node(nodeName),
          parent = context,
          runId = runId,
          useSubBranch = trigger.useSubBranch,
          overrideBranch = trigger.branch,
        )
        .run(trigger.input)
    }
    return true
  }

  // TODO: replayContext (builds the context a fast-forwarded node would have produced, without
  // running it) is added with resume/rehydration in a later CL.

  private suspend fun handleCompletion(nodeName: String, childContext: NodeContext) {
    val state = nodeStates.getValue(nodeName)

    if (childContext.interruptIds.isNotEmpty()) {
      state.status = NodeStatus.WAITING
      state.interrupts = childContext.interruptIds.toList()
      interruptIds.addAll(childContext.interruptIds)
      emitCheckpoint()
      return
    }

    val node = graph.node(nodeName)
    if (node.waitForOutput && !childContext.hasProducedOutput && childContext.routes == null) {
      state.status = NodeStatus.WAITING
      emitCheckpoint()
      return
    }

    state.status = NodeStatus.COMPLETED
    nodeBranches[nodeName] = childContext.invocationContext.branch
    if (childContext.hasProducedOutput) nodeOutputs[nodeName] = childContext.output

    emitCheckpoint()

    enqueueSuccessorNodeTriggers(nodeName, childContext)
  }

  /** Records where every node stands, so a later turn can see how far this one got. */
  private suspend fun emitCheckpoint() {
    if (!isResumable) return
    val snapshot = nodeStates.mapValues { (_, state) -> state.toCheckpoint() as TypedData }
    context.eventSink.send(
      Event(
        author = workflow.name,
        invocationId = context.invocationContext.invocationId,
        branch = context.invocationContext.branch,
        actions =
          EventActions(
            agentState = TypedData.MapValue(mapOf("nodes" to TypedData.MapValue(snapshot)))
          ),
      )
    )
  }

  // TODO: reemitReplayedOutput (re-surfaces a fast-forwarded node's recovered output on resume) is
  // added with resume/rehydration in a later CL.

  /** Marks a clean finish, so a resumable session can tell the workflow ran to completion. */
  private suspend fun emitEndOfAgent() {
    if (!isResumable) return
    context.eventSink.send(
      Event(
        author = workflow.name,
        invocationId = context.invocationContext.invocationId,
        branch = context.invocationContext.branch,
        actions = EventActions(endOfAgent = true),
      )
    )
  }

  private fun enqueueSuccessorNodeTriggers(nodeName: String, childContext: NodeContext) {
    val successors = graph.nodesTriggeredBy(nodeName, childContext.routes)
    // A node fanning out to more than one successor puts each on its own sub-branch; a single
    // successor inherits the completing node's branch unchanged.
    val useSubBranch = successors.size > 1
    val branch = childContext.invocationContext.branch

    for (target in successors) {
      if (graph.node(target).requiresAllPredecessors) {
        enqueueBarrierTrigger(target)
      } else {
        triggerQueue.enqueue(
          target,
          Trigger(childContext.output, useSubBranch = useSubBranch, branch = branch),
        )
      }
    }
  }

  /**
   * Buffers a trigger for a fan-in [target] once every predecessor has completed, handing it all of
   * their outputs keyed by name. A no-op while any predecessor is still outstanding. START never
   * runs, so it is satisfied as soon as the workflow begins, and its seeded input joins the
   * aggregate.
   */
  private fun enqueueBarrierTrigger(target: String) {
    val predecessors = graph.predecessorsOf(target)
    // Gate on live status so a re-triggered predecessor in a loop no longer counts as done.
    if (
      !predecessors.all { it == START_NODE_NAME || nodeStates[it]?.status == NodeStatus.COMPLETED }
    ) {
      return
    }
    val aggregated = predecessors.associateWith { nodeOutputs[it] }
    // A join re-merges onto the branch its predecessors forked from: the common prefix of theirs.
    // An empty prefix means they share no branch, which re-merges to the root (null).
    val branch = BranchPath.commonPrefix(predecessors.map { nodeBranches[it] }).ifEmpty { null }
    triggerQueue.enqueue(target, Trigger(aggregated, useSubBranch = false, branch = branch))
  }

  private fun collectRemainingInterrupts() {
    for (state in nodeStates.values) {
      if (state.status == NodeStatus.WAITING) interruptIds.addAll(state.interrupts)
    }
  }

  private fun finalize() {
    if (interruptIds.isNotEmpty()) {
      context.addInterruptIds(interruptIds)
      return
    }
    val terminalOutputs = graph.terminalNodeNames.filter { it in nodeOutputs }
    if (terminalOutputs.size > 1) {
      throw IllegalStateException(
        "Workflow ${workflow.name}: multiple terminal nodes produced output" +
          " (${terminalOutputs.size}). A workflow must have at most one terminal output."
      )
    }
    if (terminalOutputs.size == 1) {
      context.output = nodeOutputs[terminalOutputs.single()]
      context.markOutputEmitted()
    }
  }
}
