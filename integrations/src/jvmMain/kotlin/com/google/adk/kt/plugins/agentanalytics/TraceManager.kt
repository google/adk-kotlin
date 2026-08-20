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
import io.opentelemetry.api.trace.Span
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.collections.ArrayDeque

/**
 * Manages the BQAA-internal execution tree of span IDs for one invocation.
 *
 * Span records are kept in per-branch stacks keyed by [InvocationContext.branch].
 */
internal class TraceManager {

  internal data class SpanRecord(
    val spanId: String,
    val kind: String,
    val operationId: String?,
    val parentSpanId: String?,
    val startTime: Instant = Instant.now(),
    val firstTokenTime: AtomicReference<Instant?> = AtomicReference(null),
  )

  internal data class RecordData(
    val spanId: String,
    val parentSpanId: String?,
    val duration: Duration,
  )

  internal data class SpanIds(val spanId: String?, val parentSpanId: String?)

  private val stacksByBranch = ConcurrentHashMap<String, ArrayDeque<SpanRecord>>()
  @Volatile
  var rootAgentName: String = DEFAULT_ROOT_AGENT_NAME
    private set

  @Volatile private var activeInvocationId: String = "_bq_analytics_active_invocation_id"
  @Volatile private var traceId: String? = null

  fun initTrace(context: InvocationContext) {
    val name = context.agent.name
    if (name.isNotEmpty()) {
      this.rootAgentName = name
    }
  }

  fun initTraceIfNeeded(context: InvocationContext) {
    if (rootAgentName != DEFAULT_ROOT_AGENT_NAME) return
    try {
      initTrace(context)
    } catch (_: RuntimeException) {
      // Leave sentinel
    }
  }

  fun getTraceId(context: InvocationContext): String {
    val tid = this.traceId
    if (tid != null) return tid

    val ambient = Span.current().spanContext
    if (ambient.isValid) {
      return ambient.traceId
    }
    return context.invocationId
  }

  fun pushSpan(context: InvocationContext, spanName: String): String {
    return pushSpanRecord(context, spanName, null).spanId
  }

  fun pushSpanRecord(
    context: InvocationContext,
    spanName: String,
    operationId: String? = null,
  ): SpanRecord {
    val branch = branchKey(context)
    val parentSpanId = findParentSpanId(branch, if (operationId != null) spanName else null)
    val record = SpanRecord(newSpanId(), spanName, operationId, parentSpanId, Instant.now())
    stackFor(branch).addLast(record)
    return record
  }

  private fun findParentSpanId(branch: String, skipConcurrentKind: String?): String? {
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

  fun attachCurrentSpan(context: InvocationContext): String {
    val ambient = Span.current().spanContext
    val spanId: String
    if (ambient.isValid) {
      spanId = ambient.spanId
      this.traceId = ambient.traceId
    } else {
      spanId = newSpanId()
    }
    stackFor(branchKey(context))
      .addLast(SpanRecord(spanId, "invocation", null, null, Instant.now()))
    return spanId
  }

  fun ensureInvocationSpan(context: InvocationContext) {
    val currentInv = context.invocationId

    if (hasAnyRecords()) {
      if (currentInv == activeInvocationId) return
      logger.debug { "Clearing stale span records from previous invocation." }
      clearStack()
    }

    activeInvocationId = currentInv
    this.traceId = null

    if (Span.current().spanContext.isValid) {
      val unusedAttached = attachCurrentSpan(context)
    } else {
      val unusedSpanId = pushSpan(context, "invocation")
    }
  }

  private fun hasAnyRecords(): Boolean {
    return stacksByBranch.values.any { it.isNotEmpty() }
  }

  fun popSpan(
    context: InvocationContext,
    expectedKindPrefix: String,
    operationId: String? = null,
  ): RecordData? {
    val stack = stacksByBranch[branchKey(context)]
    if (stack == null || stack.isEmpty()) return null

    if (operationId != null) {
      for (i in stack.indices.reversed()) {
        val record = stack[i]
        if (record.kind.startsWith(expectedKindPrefix) && operationId == record.operationId) {
          stack.removeAt(i)
          return RecordData(
            record.spanId,
            record.parentSpanId,
            Duration.between(record.startTime, Instant.now()),
          )
        }
      }
      logger.debug {
        "No span with kind prefix '$expectedKindPrefix' and operation ID '$operationId' to pop."
      }
      return null
    }

    val top = stack.lastOrNull() ?: return null
    if (!top.kind.startsWith(expectedKindPrefix)) {
      logger.debug {
        "Not popping span of kind '${top.kind}': expected kind prefix '$expectedKindPrefix'."
      }
      return null
    }
    val record = stack.removeLastOrNull() ?: return null
    return RecordData(
      record.spanId,
      record.parentSpanId,
      Duration.between(record.startTime, Instant.now()),
    )
  }

  fun clearStack() {
    stacksByBranch.clear()
  }

  fun getCurrentSpanAndParent(context: InvocationContext): SpanIds {
    val chain = branchChain(branchKey(context))

    var current: SpanRecord? = null
    var currentChainIndex = -1
    for (i in chain.indices) {
      val top = chain[i].lastOrNull()
      if (top != null) {
        current = top
        currentChainIndex = i
        break
      }
    }
    if (current == null) {
      return SpanIds(null, null)
    }

    var parent: SpanRecord? = null
    val currentStack = chain[currentChainIndex]
    if (currentStack.size >= 2) {
      parent = currentStack[currentStack.size - 2]
    }
    if (parent == null) {
      for (i in (currentChainIndex + 1) until chain.size) {
        val top = chain[i].lastOrNull()
        if (top != null) {
          parent = top
          break
        }
      }
    }
    return SpanIds(current.spanId, parent?.spanId)
  }

  fun getCurrentSpanId(context: InvocationContext): String? {
    for (stack in branchChain(branchKey(context))) {
      val top = stack.lastOrNull()
      if (top != null) return top.spanId
    }
    return null
  }

  private fun findSpanRecord(spanId: String): SpanRecord? {
    for (stack in stacksByBranch.values) {
      for (record in stack.asReversed()) {
        if (record.spanId == spanId) {
          return record
        }
      }
    }
    return null
  }

  fun recordFirstToken(spanId: String) {
    findSpanRecord(spanId)?.firstTokenTime?.compareAndSet(null, Instant.now())
  }

  fun getStartTime(spanId: String): Instant? {
    return findSpanRecord(spanId)?.startTime
  }

  fun getFirstTokenTime(spanId: String): Instant? {
    return findSpanRecord(spanId)?.firstTokenTime?.get()
  }

  private fun stackFor(branch: String): ArrayDeque<SpanRecord> {
    return stacksByBranch.computeIfAbsent(branch) { ArrayDeque() }
  }

  private fun branchChain(branch: String): List<ArrayDeque<SpanRecord>> {
    val chain = mutableListOf<ArrayDeque<SpanRecord>>()
    var key = branch
    while (true) {
      val stack = stacksByBranch[key]
      if (stack != null) {
        chain.add(stack)
      }
      if (key.isEmpty()) break
      val lastDot = key.lastIndexOf('.')
      key = if (lastDot >= 0) key.substring(0, lastDot) else ROOT_BRANCH
    }
    return chain
  }

  companion object {
    const val DEFAULT_ROOT_AGENT_NAME = "_bq_analytics_root_agent_name"
    private const val ROOT_BRANCH = ""
    private val logger = LoggerFactory.getLogger(TraceManager::class)

    private fun newSpanId(): String {
      return UUID.randomUUID().toString().replace("-", "").substring(0, 16)
    }

    private fun branchKey(context: InvocationContext): String {
      return try {
        context.branch ?: ROOT_BRANCH
      } catch (_: RuntimeException) {
        ROOT_BRANCH
      }
    }
  }
}
