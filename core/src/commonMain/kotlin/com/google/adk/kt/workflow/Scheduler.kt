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

import com.google.adk.kt.SchemaUtils
import com.google.adk.kt.agents.AgentMode
import com.google.adk.kt.agents.BaseAgent
import com.google.adk.kt.agents.Context
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.NodeExecutionState
import com.google.adk.kt.agents.TypedData
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.events.Event
import com.google.adk.kt.events.EventActions
import kotlin.time.Duration
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
  private val context: Context,
  replayTimeout: Duration = ReplaySequence.DEFAULT_TIMEOUT,
) {
  private val nodeStates = mutableMapOf<String, NodeState>()
  private val nodeOutputs = mutableMapOf<String, Any?>()
  // The execution branch of each completed node, used by join nodes to re-merge parallel branches
  // to their common prefix.
  private val nodeBranches = mutableMapOf<String, String?>()
  private val triggerQueue = TriggerQueue()
  private val interruptIds = mutableSetOf<String>()

  /**
   * Reconstructed states of this workflow's nodes from earlier turns or earlier attempts of this
   * workflow, keyed by `name@runId`.
   */
  private val recovered: Map<String, RecoveredNode> =
    ResumeScan.scan(
      ResumeScan.invocationEvents(context.invocationContext),
      context.nodePath,
      context.invocationContext.invocationId,
    )

  /**
   * Releases replayed nodes in the order they first finished, paused, or were answered, so their
   * successors start in that order. Children run on this workflow's own context, such as an agent a
   * node hands off to, share it.
   */
  private val replaySequence =
    ReplaySequence(replayOrder(recovered), timeout = replayTimeout).also {
      context.requireNodeState().childRuns.setReplaySequence(it)
    }

  /** Nodes replayed from history in the current activation. */
  private val replayed = mutableSetOf<String>()

  /** Nodes whose latest output was replayed, so no event in this turn carried it in `outputFor`. */
  private val replayedOutputs = mutableSetOf<String>()

  private val isResumable: Boolean
    get() = context.invocationContext.isResumable

  suspend fun run(nodeInput: Any?) {
    enqueueStartTriggers(nodeInput)
    coroutineScope { Loop(this).drive() }
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

  /**
   * The scheduling loop, bound to one coroutine [scope]. Owns [running], the nodes executing right
   * now, so the ready-scan and launch read it directly rather than threading it through every call.
   * Runs until nothing is queued and nothing is running, or a node fails.
   */
  private inner class Loop(private val scope: CoroutineScope) {
    private val running = linkedMapOf<String, Deferred<Context>>()

    /** Running nodes started as replays, so they take no [Workflow.maxConcurrency] slot. */
    private val replays = mutableSetOf<String>()

    /** Blockers holding back queued replays under [Workflow.maxConcurrency]. */
    private var heldBlockers: List<Deferred<Unit>> = emptyList()

    suspend fun drive() {
      while (true) {
        scheduleReady()
        if (running.isEmpty()) break

        // Take the next completion plus any siblings that finished in the same window, and act on
        // a failure in that batch before scheduling again -- otherwise a node that succeeded beside
        // a failing sibling would have its successors started only to be cancelled. Python likewise
        // drains the FIRST_COMPLETED batch and stops on an error before scheduling.
        val batch = awaitCompletedBatch()
        if (batch.isEmpty()) continue
        if (handleFailures(batch)) return
        handleCompletion(batch)
      }
      finalize()
    }

    /**
     * Fails the run on the first failed node in [batch], cancelling the siblings still running, and
     * returns whether it did.
     */
    private fun handleFailures(batch: List<Pair<String, Context>>): Boolean {
      for ((name, ctx) in batch) {
        val failure = ctx.requireNodeState().failure ?: continue
        nodeStates.getValue(name).status = NodeStatus.FAILED
        context.requireNodeState().failure = failure
        // Fail fast: cancel every sibling still running, then leave the loop.
        for (task in running.values) task.cancel()
        return true
      }
      return false
    }

    /**
     * Suspends for the next node to finish (or for a held replay's blocker to clear), then takes
     * any siblings already finished, dropping all of them from [running]. Returning the batch lets
     * [drive] see a failure before it schedules successors of a node that succeeded in the same
     * window.
     */
    private suspend fun awaitCompletedBatch(): List<Pair<String, Context>> {
      val batch = mutableListOf<Pair<String, Context>>()
      awaitNextFinishedOrUnblocked()?.let(batch::add)
      for (name in running.filterValues { it.isCompleted }.keys.toList()) {
        val task = running.remove(name) ?: continue
        batch.add(name to task.await())
      }
      // Nodes that finished together are handled in their recorded order, then the rest.
      return batch.sortedBy { (name, finished) -> recordedPlace(name, finished) ?: Int.MAX_VALUE }
    }

    /**
     * The place in the replay order of [name], which ended as [finished], or null for a new run or
     * a run after a pause, whose live output is not what its successors' recorded runs took.
     */
    private fun recordedPlace(name: String, finished: Context): Int? {
      // A run on this workflow's context is the node itself or an agent it handed off to.
      val key =
        if (finished.parent === context) {
          finished.nodePath.substringAfterLast('/')
        } else {
          BranchPath.segment(name, nodeStates.getValue(name).runId ?: return null)
        }
      val recoveredNode = recovered[key] ?: return null
      if (!finished.requireNodeState().isReplay && recoveredNode.placeIsPause) return null
      return recoveredNode.sequenceIndex
    }

    /**
     * Awaits the next node to finish (dropping it from [running]) or a held replay's blocker to
     * complete (returning null so [drive] re-checks the queue).
     */
    private suspend fun awaitNextFinishedOrUnblocked(): Pair<String, Context>? {
      // `select` is biased to registration order, and `running` preserves the order the nodes were
      // scheduled in, so two nodes finishing together are handled in a stable order.
      val finished =
        select<Pair<String, Context>?> {
          for ((name, task) in running) task.onAwait { name to it }
          for (blocker in heldBlockers) blocker.onAwait { null }
        }
      if (finished != null) running.keys.remove(finished.first)
      return finished
    }

    private suspend fun scheduleReady() {
      heldBlockers = emptyList()
      // As in Python ADK, no other node starts while a task node waits on the user.
      if (hasWaitingTaskNode()) {
        // No successor starts either, so a replay must not wait for a node that cannot start.
        replaySequence.releaseAll()
        return
      }
      val limit = workflow.maxConcurrency
      val held = mutableMapOf<String, Deferred<Unit>>()
      while (true) {
        var startedAny = false
        held.clear()
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
          if (limit != null && deferIfBlocked(nodeName, limit, held)) continue

          val trigger = triggerQueue.poll(nodeName) ?: continue
          // A run or completed transfer marks a new step; a plain replay does not.
          if (start(nodeName, trigger)) emitCheckpoint()
          startedAny = true
        }
        // A rerun after a pause advances synchronously, which may unblock a held replay right away.
        if (!startedAny || held.isEmpty()) break
      }
      if (held.isNotEmpty() && running.isEmpty()) {
        // Nothing running can release a held replay, so start one to surface the divergence.
        val nodeName = held.keys.first()
        val trigger = triggerQueue.poll(nodeName)
        if (trigger != null && start(nodeName, trigger)) emitCheckpoint()
        heldBlockers = held.values.drop(1).distinct()
      } else {
        heldBlockers = held.values.distinct()
      }
    }

    /**
     * Returns whether [nodeName] must wait under [limit], recording any sequence blocker in [held].
     */
    private fun deferIfBlocked(
      nodeName: String,
      limit: Int,
      held: MutableMap<String, Deferred<Unit>>,
    ): Boolean {
      val childKey =
        BranchPath.segment(nodeName, context.requireNodeState().peekChildRunId(nodeName))
      val interception =
        ResumeScan.intercept(
          graph.node(nodeName),
          recovered[childKey],
          cancelledMidRun = context.isCancelledMidRun(childKey),
        )
      if (interception.shouldRun) {
        return running.keys.count { it !in replays } >= limit
      }
      // Hold unreleased replays so a queued live node cannot time them out.
      val blocker = firstBlockerFor(nodeName, childKey, interception) ?: return false
      held[nodeName] = blocker
      return true
    }

    /**
     * First blocker holding back [nodeName]'s replay [firstKey], walking recorded sibling handoffs
     * on this workflow's context while each hop would replay.
     */
    private fun firstBlockerFor(
      nodeName: String,
      firstKey: String,
      firstInterception: Interception,
    ): Deferred<Unit>? {
      val siblingCounts = mutableMapOf(nodeName to 1)
      var current: Node = graph.node(nodeName)
      var key = firstKey
      var interception = firstInterception
      while (true) {
        replaySequence.firstBlocker(key)?.let {
          return it
        }
        if (replaySequence.isPending(key) || interception.interrupts.isNotEmpty()) return null
        val transfer = interception.transferToAgent ?: return null
        val agent = current as? BaseAgent ?: return null
        val sibling = resolveSiblingTransferTarget(transfer, agent) ?: return null
        val offset = siblingCounts.getOrElse(sibling.name) { 0 }
        val runId =
          (context.requireNodeState().peekChildRunId(sibling.name).toInt() + offset).toString()
        siblingCounts[sibling.name] = offset + 1
        key = BranchPath.segment(sibling.name, runId)
        val hopRecovered = recovered[key] ?: return null
        interception =
          ResumeScan.intercept(
            sibling,
            hopRecovered,
            dynamic = true,
            cancelledMidRun = context.isCancelledMidRun(key),
          )
        if (interception.shouldRun) return null
        current = sibling
      }
    }

    // Confined to the scheduling coroutine: state mutations and queue access happen sequentially on
    // this single thread of execution.
    @Suppress("UnsafeCoroutineCrossing")
    private fun start(nodeName: String, trigger: Trigger): Boolean {
      val runId = context.requireNodeState().nextChildRunId(nodeName)
      nodeStates[nodeName] = NodeState(status = NodeStatus.RUNNING, runId = runId)
      replays.remove(nodeName)

      val node = graph.node(nodeName)
      val useAsOutput = nodeName in graph.terminalNodeNames

      val childKey = BranchPath.segment(nodeName, runId)
      val recoveredNode = recovered[childKey]
      val interception =
        ResumeScan.intercept(
          node,
          recoveredNode,
          cancelledMidRun = context.isCancelledMidRun(childKey),
        )
      // Follows an agent node's transfer_to_agent too.
      suspend fun dispatch(firstHop: Context?): Context =
        context.runGraphNode(
          node = node,
          nodeInput = trigger.input,
          runId = runId,
          useAsOutput = useAsOutput,
          useSubBranch = trigger.useSubBranch,
          overrideBranch = trigger.branch,
          overrideIsolationScope = taskScopeFor(nodeName, runId),
          resumeInputs = interception.resumeInputs,
          openInterrupts = interception.interrupts,
          firstHop = firstHop,
        )
      if (recoveredNode != null && !interception.shouldRun) {
        val completesTransfer =
          interception.transferToAgent != null &&
            recoveredNode.interruptIds.isNotEmpty() &&
            recoveredNode.unresolved.isEmpty()
        // A recorded transfer hands off again: a paused target resumes, a finished one gives its
        // output back, and a plain node fails.
        val followsTransfer = interception.transferToAgent != null
        if (!completesTransfer) replayed.add(nodeName)
        replays.add(nodeName)
        val scheduled =
          scheduledInvocationContext(
            context.invocationContext,
            trigger.branch,
            trigger.useSubBranch,
            nodeName,
            runId,
          )
        val replayContext =
          ResumeScan.replayContext(context, scheduled, node, runId, useAsOutput, interception)
        running[nodeName] = scope.async {
          replaySequence.awaitTurn(childKey)
          if (!followsTransfer) return@async replayContext
          // The replay itself is done, so it lets later replays go while the transfer runs on.
          replaySequence.advance(childKey)
          dispatch(firstHop = replayContext)
        }
        return completesTransfer
      }
      // A node that runs again after it paused gives up its place, so later replays go at once.
      if (recoveredNode?.placeIsPause == true) replaySequence.advance(childKey)
      running[nodeName] = scope.async { dispatch(firstHop = null) }
      return true
    }
  }

  private suspend fun handleCompletion(batch: List<Pair<String, Context>>) {
    for ((name, ctx) in batch) handleCompletion(name, ctx)
  }

  private suspend fun handleCompletion(nodeName: String, childContext: Context) {
    val state = nodeStates.getValue(nodeName)
    state.runId?.let { replaySequence.advance(BranchPath.segment(nodeName, it)) }
    // A handoff to a sibling runs on this workflow's context and defers advancing its final hop.
    if (childContext.parent === context) {
      replaySequence.advance(childContext.nodePath.substringAfterLast('/'))
    }
    // A followed transfer whose last hop really ran is not a replay, even if the node was.
    val wasReplayed = replayed.remove(nodeName) && childContext.requireNodeState().isReplay

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
    if (childContext.hasProducedOutput) {
      nodeOutputs[nodeName] = childContext.output
      if (wasReplayed) replayedOutputs.add(nodeName) else replayedOutputs.remove(nodeName)
    }

    if (wasReplayed) reemitReplayedOutput(childContext) else emitCheckpoint()

    enqueueSuccessorNodeTriggers(nodeName, childContext)
  }

  /** Records where every node stands, so a later turn can see how far this one got. */
  private suspend fun emitCheckpoint() {
    if (!isResumable) return
    val snapshot = nodeStates.mapValues { (_, state) -> state.toCheckpoint() as TypedData }
    emit(
      EventActions(
        agentState = TypedData.MapValue(mapOf(NodeState.NODES_KEY to TypedData.MapValue(snapshot)))
      )
    )
  }

  /** Re-emits a replayed node's output on resumable runs without setting `outputFor`. */
  private suspend fun reemitReplayedOutput(childContext: Context) {
    if (!isResumable || !childContext.hasProducedOutput) return
    context
      .requireNodeState()
      .eventSink
      .send(
        Event(
          author = workflow.name,
          invocationId = context.invocationContext.invocationId,
          branch = childContext.invocationContext.branch,
          output = childContext.output,
          nodeInfo = NodeInfo(path = childContext.nodePath),
        )
      )
  }

  /** Sends this workflow's checked output as its own event, for every node it counts for. */
  private suspend fun emitOutput(state: NodeExecutionState) {
    state.eventSink.send(
      Event(
        author = workflow.name,
        invocationId = context.invocationContext.invocationId,
        branch = context.invocationContext.branch,
        output = context.output,
        nodeInfo = NodeInfo(path = context.nodePath, outputFor = state.outputFor),
      )
    )
  }

  /** Whether a node that is not a workflow takes this workflow's output through `useAsOutput`. */
  private fun NodeExecutionState.isDelegatedToANode(): Boolean =
    generateSequence(outputParent) { it.outputParent }.any { it.node !is Workflow }

  /** Marks a clean finish, so a resumable session can tell the workflow ran to completion. */
  private suspend fun emitEndOfAgent() {
    if (!isResumable) return
    emit(EventActions(endOfAgent = true))
  }

  /** Sends a workflow-level event carrying [actions], attributed to the workflow. */
  private suspend fun emit(actions: EventActions) {
    context
      .requireNodeState()
      .eventSink
      .send(
        Event(
          author = workflow.name,
          invocationId = context.invocationContext.invocationId,
          branch = context.invocationContext.branch,
          actions = actions,
        )
      )
  }

  /** Whether a task agent node is waiting, which for a task means waiting on the user. */
  private fun hasWaitingTaskNode(): Boolean = nodeStates.any { (name, state) ->
    state.status == NodeStatus.WAITING && (graph.node(name) as? LlmAgent)?.mode == AgentMode.TASK
  }

  /** A task agent's isolation scope: its own node path, which keeps its conversation apart. */
  private fun taskScopeFor(nodeName: String, runId: String): String? =
    if ((graph.node(nodeName) as? LlmAgent)?.mode == AgentMode.TASK) {
      Context.buildNodePath(context.nodePath, nodeName, runId)
    } else {
      null
    }

  private fun enqueueSuccessorNodeTriggers(nodeName: String, childContext: Context) {
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
    // A predecessor is done once it has COMPLETED with nothing queued for it. Status alone is not
    // enough: it flips to RUNNING only in start(), so a predecessor a loop re-triggered still reads
    // COMPLETED while its trigger waits. Firing then would hand the join that predecessor's stale
    // output, and fire again once it really re-runs.
    val queued = triggerQueue.queuedNodeNames.toSet()
    if (
      !predecessors.all {
        it == START_NODE_NAME || (nodeStates[it]?.status == NodeStatus.COMPLETED && it !in queued)
      }
    ) {
      return
    }
    val aggregated = predecessors.associateWith { nodeOutputs[it] }
    // A join re-merges onto the branch its predecessors forked from: the common prefix of theirs.
    // An empty prefix means they share no branch, which re-merges to the root (null).
    val branch = BranchPath.commonPrefix(predecessors.map { nodeBranches[it] }).ifEmpty { null }
    triggerQueue.enqueue(target, Trigger(aggregated, useSubBranch = false, branch = branch))
  }

  /**
   * Finalizes the run: surfaces any interrupts from still-waiting nodes to the parent, or, when
   * there are none, publishes the sole terminal output and marks the workflow done.
   */
  private suspend fun finalize() {
    for (state in nodeStates.values) {
      if (state.status == NodeStatus.WAITING) interruptIds.addAll(state.interrupts)
    }
    // A child dispatched from this workflow's own context records its interrupts on the workflow.
    if (interruptIds.isNotEmpty() || context.interruptIds.isNotEmpty()) {
      context.requireNodeState().addInterruptIds(interruptIds)
      return
    }
    val terminalOutputs = graph.terminalNodeNames.filter { it in nodeOutputs }
    if (terminalOutputs.size > 1) {
      throw WorkflowConfigurationException(
        "Workflow ${workflow.name}: multiple terminal nodes produced output" +
          " (${terminalOutputs.size}). A workflow must have at most one terminal output."
      )
    }
    if (terminalOutputs.size == 1) {
      val terminalNode = terminalOutputs.single()
      val output = nodeOutputs[terminalNode]
      val schema = workflow.outputSchema
      val side = "output of workflow '${workflow.name}'"
      context.output =
        if (schema == null) output else SchemaUtils.checkedValue(output, schema, side)
      // Validation can read a Content as JSON null, which is no output; the terminal node's raw
      // output then no longer counts for delegating ancestors either.
      val state = context.requireNodeState()
      if (context.hasProducedOutput) {
        // Sent only when the check changed the value. A node that delegated to this workflow
        // replays the checked value from this event on resume or retry.
        val sentChecked = context.output != output && state.isDelegatedToANode()
        if (sentChecked) emitOutput(state)
        // Unmarked so NodeRunner sends a replayed terminal node's output for the delegating node.
        if (sentChecked || terminalNode !in replayedOutputs || !state.isDelegatedToANode()) {
          state.markOutputEmitted()
        }
      } else if (output != null) {
        state.clearDelegatedOutput(output)
      }
    }
    emitEndOfAgent()
  }
}
