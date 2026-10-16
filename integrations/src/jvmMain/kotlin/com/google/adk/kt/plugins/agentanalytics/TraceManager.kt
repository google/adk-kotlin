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

package com.google.adk.kt.plugins.agentanalytics

import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.logging.LoggerFactory
import com.google.adk.kt.tools.ToolContext
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanContext
import java.time.Duration
import java.time.Instant
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.collections.ArrayDeque

/**
 * Tracks the span tree this plugin logs, separately for each invocation.
 *
 * Concurrent and nested runs that share a plugin never see each other's spans. Within an
 * invocation, span records are kept in per-branch stacks keyed by [InvocationContext.branch].
 */
internal class TraceManager(private val maxTrackedInvocations: Int = MAX_TRACKED_INVOCATIONS) {

  internal class SpanRecord(
    val spanId: String,
    val kind: String,
    val operationId: String?,
    val parentSpanId: String?,
  ) {
    val startTime: Instant = Instant.now()
    val firstTokenTime = AtomicReference<Instant?>(null)
  }

  internal data class RecordData(
    val spanId: String,
    val parentSpanId: String?,
    val duration: Duration,
  )

  internal data class SpanIds(val spanId: String?, val parentSpanId: String?)

  // Accessed only while holding this state's monitor.
  private class InvocationState(val rootAgentName: String) {
    val stacksByBranch = mutableMapOf<String, ArrayDeque<SpanRecord>>()
    // Identity-keyed: a ToolContext without a function call id is one call, however it compares.
    val toolCallIds = IdentityHashMap<ToolContext, String>()
    var traceId: String? = null
  }

  // LRU eviction, since a cancelled run never reaches afterRun or onRunError cleanup.
  private val invocations =
    object : LinkedHashMap<String, InvocationState>(16, 0.75f, /* accessOrder= */ true) {
      override fun removeEldestEntry(
        eldest: MutableMap.MutableEntry<String, InvocationState>
      ): Boolean = size > maxTrackedInvocations
    }

  /** Returns the name of the agent the invocation started with, for `root_agent_name`. */
  fun getRootAgentName(context: InvocationContext): String =
    findState(context)?.rootAgentName ?: context.agent.name

  fun getTraceId(context: InvocationContext): String {
    val state = findState(context)
    val traceId = if (state == null) null else synchronized(state) { state.traceId }
    return traceId ?: ambientSpanContext()?.traceId ?: context.invocationId
  }

  /** Pushes the invocation's root span unless this invocation already has spans. */
  fun ensureInvocationSpan(context: InvocationContext) {
    val state = getOrCreateState(context)
    synchronized(state) {
      if (state.stacksByBranch.values.any { it.isNotEmpty() }) return
      // Reuse a valid ambient span (e.g. the runner's invocation span) without owning it.
      val ambient = ambientSpanContext()
      state.traceId = ambient?.traceId ?: newTraceId()
      val spanId = ambient?.spanId ?: newSpanId()
      state
        .stackFor(branchKey(context))
        .addLast(SpanRecord(spanId, INVOCATION_KIND, operationId = null, parentSpanId = null))
    }
  }

  /** Returns the id that pairs the tool call of [toolContext] with its span until released. */
  fun acquireToolCallId(toolContext: ToolContext): String {
    val state = getOrCreateState(toolContext.invocationContext)
    synchronized(state) {
      return state.toolCallIds.getOrPut(toolContext) { "tool-ctx-${UUID.randomUUID()}" }
    }
  }

  /** Forgets the id [acquireToolCallId] returned, or returns null if there is none. */
  fun releaseToolCallId(toolContext: ToolContext): String? {
    val state = findState(toolContext.invocationContext) ?: return null
    synchronized(state) {
      return state.toolCallIds.remove(toolContext)
    }
  }

  fun pushSpan(context: InvocationContext, kind: String): String {
    return pushSpanRecord(context, kind, null).spanId
  }

  fun pushSpanRecord(
    context: InvocationContext,
    kind: String,
    operationId: String? = null,
  ): SpanRecord {
    val state = getOrCreateState(context)
    synchronized(state) {
      val branch = branchKey(context)
      val parentSpanId = state.findParentSpanId(branch, if (operationId != null) kind else null)
      val record = SpanRecord(newSpanId(), kind, operationId, parentSpanId)
      state.stackFor(branch).addLast(record)
      return record
    }
  }

  /**
   * Pops the newest span of [kind] on the context's branch.
   *
   * Without [operationId] only the top of the stack is eligible, so a caller never pops a span it
   * does not own.
   */
  fun popSpan(context: InvocationContext, kind: String, operationId: String? = null): RecordData? {
    val state = findState(context) ?: return null
    synchronized(state) {
      val stack = state.stacksByBranch[branchKey(context)] ?: return null
      val index =
        when {
          operationId != null ->
            stack.indexOfLast { it.kind == kind && it.operationId == operationId }
          stack.lastOrNull()?.kind == kind -> stack.lastIndex
          else -> -1
        }
      if (index < 0) {
        logger.debug { "No span of kind '$kind' to pop." }
        return null
      }
      return stack.removeAt(index).toRecordData()
    }
  }

  /**
   * Pops the newest span of [kind] on the context's branch, together with every span above it.
   *
   * Those are spans whose closing callback never ran, such as a model call that a before-model
   * callback short-circuited. Enclosing branches are searched too: a resumed run can open its
   * invocation on the root branch and close it on a sub-agent branch.
   */
  fun unwindSpan(context: InvocationContext, kind: String): RecordData? {
    val state = findState(context) ?: return null
    synchronized(state) {
      for (stack in state.branchChain(branchKey(context))) {
        val index = stack.indexOfLast { it.kind == kind }
        if (index < 0) continue
        val record = stack[index]
        while (stack.size > index) stack.removeLast()
        return record.toRecordData()
      }
      logger.debug { "No span of kind '$kind' to unwind." }
      return null
    }
  }

  fun getCurrentSpanAndParent(context: InvocationContext): SpanIds {
    val state = findState(context) ?: return SpanIds(spanId = null, parentSpanId = null)
    synchronized(state) {
      val chain = state.branchChain(branchKey(context)).filter { it.isNotEmpty() }
      val currentStack = chain.firstOrNull() ?: return SpanIds(spanId = null, parentSpanId = null)
      val parent = currentStack.getOrNull(currentStack.lastIndex - 1) ?: chain.getOrNull(1)?.last()
      return SpanIds(currentStack.last().spanId, parent?.spanId)
    }
  }

  fun getCurrentSpanId(context: InvocationContext): String? =
    getCurrentSpanAndParent(context).spanId

  fun recordFirstToken(context: InvocationContext, spanId: String) {
    findSpanRecord(context, spanId)?.firstTokenTime?.compareAndSet(null, Instant.now())
  }

  /** Returns the time from the start of span [spanId] to its first token, if one was recorded. */
  fun timeToFirstToken(context: InvocationContext, spanId: String): Duration? {
    val record = findSpanRecord(context, spanId) ?: return null
    val firstTokenTime = record.firstTokenTime.get() ?: return null
    return Duration.between(record.startTime, firstTokenTime)
  }

  /** Drops all span and tool-call state of the context's invocation. */
  fun endInvocation(context: InvocationContext) {
    synchronized(invocations) { invocations.remove(context.invocationId) }
  }

  /** Drops the state of every invocation. */
  fun clear() {
    synchronized(invocations) { invocations.clear() }
  }

  private fun findState(context: InvocationContext): InvocationState? =
    synchronized(invocations) { invocations[context.invocationId] }

  private fun getOrCreateState(context: InvocationContext): InvocationState =
    synchronized(invocations) {
      invocations.getOrPut(context.invocationId) { InvocationState(context.agent.name) }
    }

  private fun findSpanRecord(context: InvocationContext, spanId: String): SpanRecord? {
    val state = findState(context) ?: return null
    synchronized(state) {
      return state.stacksByBranch.values.firstNotNullOfOrNull { stack ->
        stack.lastOrNull { it.spanId == spanId }
      }
    }
  }

  private fun SpanRecord.toRecordData(): RecordData =
    RecordData(spanId, parentSpanId, Duration.between(startTime, Instant.now()))

  private fun InvocationState.stackFor(branch: String): ArrayDeque<SpanRecord> {
    return stacksByBranch.getOrPut(branch) { ArrayDeque() }
  }

  private fun InvocationState.findParentSpanId(
    branch: String,
    skipConcurrentKind: String?,
  ): String? {
    for (stack in branchChain(branch)) {
      for (record in stack.asReversed()) {
        if (
          skipConcurrentKind != null &&
            record.kind == skipConcurrentKind &&
            record.operationId != null
        ) {
          continue
        }
        return record.spanId
      }
    }
    return null
  }

  private fun InvocationState.branchChain(branch: String): List<ArrayDeque<SpanRecord>> =
    buildList {
      var key = branch
      while (true) {
        stacksByBranch[key]?.let { add(it) }
        if (key.isEmpty()) break
        val lastDot = key.lastIndexOf('.')
        key = if (lastDot >= 0) key.substring(0, lastDot) else ROOT_BRANCH
      }
    }

  companion object {
    const val INVOCATION_KIND = "invocation"
    private const val MAX_TRACKED_INVOCATIONS = 10_000
    private const val ROOT_BRANCH = ""
    private val logger = LoggerFactory.getLogger(TraceManager::class)

    private fun newSpanId(): String {
      return UUID.randomUUID().toString().replace("-", "").substring(0, 16)
    }

    // 32 hex characters.
    private fun newTraceId(): String = UUID.randomUUID().toString().replace("-", "")

    private fun ambientSpanContext(): SpanContext? =
      Span.current().spanContext.takeIf { it.isValid }

    private fun branchKey(context: InvocationContext): String = context.branch ?: ROOT_BRANCH
  }
}
