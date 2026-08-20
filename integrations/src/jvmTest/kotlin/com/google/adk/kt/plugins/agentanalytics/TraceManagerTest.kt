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

import com.google.adk.kt.testing.DummyAgent
import com.google.adk.kt.testing.testInvocationContext
import com.google.adk.kt.testing.testToolContext
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking

class TraceManagerTest {

  private val traceManager = TraceManager()
  private val context =
    testInvocationContext(agent = DummyAgent("root_agent"), invocationId = "inv-1")

  @Test
  fun ensureInvocationSpan_reusesAValidAmbientSpan() {
    val ambient =
      SpanContext.create(TRACE_ID, SPAN_ID, TraceFlags.getSampled(), TraceState.getDefault())

    Span.wrap(ambient).makeCurrent().use { traceManager.ensureInvocationSpan(context) }

    assertEquals(TRACE_ID, traceManager.getTraceId(context))
    assertEquals(SPAN_ID, traceManager.getCurrentSpanId(context))
  }

  @Test
  fun ensureInvocationSpan_withoutAnAmbientSpan_mintsATraceIdPerInvocation() {
    val other = testInvocationContext(agent = DummyAgent("other_agent"), invocationId = "inv-2")
    traceManager.ensureInvocationSpan(context)
    traceManager.ensureInvocationSpan(other)
    val traceId = traceManager.getTraceId(context)

    val agentSpan = traceManager.pushSpanRecord(context, "agent:root_agent")

    assertNotNull(agentSpan.parentSpanId)
    assertTrue(traceId.matches(Regex("[0-9a-f]{32}")))
    assertEquals(traceId, traceManager.getTraceId(context))
    assertNotEquals(traceId, traceManager.getTraceId(other))
  }

  @Test
  fun ensureInvocationSpan_keepsTheSpansAnInvocationAlreadyHas() {
    traceManager.ensureInvocationSpan(context)
    val agentSpan = traceManager.pushSpan(context, "agent:root_agent")

    traceManager.ensureInvocationSpan(context)

    assertEquals(agentSpan, traceManager.getCurrentSpanId(context))
  }

  @Test
  fun popSpan_withOperationId_removesOnlyTheMatchingSpan() {
    traceManager.ensureInvocationSpan(context)
    val first = traceManager.pushSpanRecord(context, "tool", "call-1")
    val second = traceManager.pushSpanRecord(context, "tool", "call-2")

    assertEquals(first.spanId, traceManager.popSpan(context, "tool", "call-1")?.spanId)
    assertNull(traceManager.popSpan(context, "tool", "call-1"))
    assertEquals(second.spanId, traceManager.getCurrentSpanId(context))
  }

  @Test
  fun popSpan_withOperationId_matchesTheKindToo() {
    traceManager.ensureInvocationSpan(context)
    val toolSpan = traceManager.pushSpanRecord(context, "tool", "call-1")
    val otherSpan = traceManager.pushSpanRecord(context, "other", "call-1")

    assertEquals(toolSpan.spanId, traceManager.popSpan(context, "tool", "call-1")?.spanId)
    assertEquals(otherSpan.spanId, traceManager.getCurrentSpanId(context))
  }

  @Test
  fun popSpan_withoutOperationId_popsOnlyTheTopSpan() {
    traceManager.ensureInvocationSpan(context)
    val llmSpan = traceManager.pushSpan(context, "llm_request")
    val toolSpan = traceManager.pushSpanRecord(context, "tool", "call-1")

    assertNull(traceManager.popSpan(context, "llm_request"))
    assertEquals(toolSpan.spanId, traceManager.popSpan(context, "tool", "call-1")?.spanId)
    assertEquals(llmSpan, traceManager.popSpan(context, "llm_request")?.spanId)
  }

  @Test
  fun unwindSpan_popsTheSpanAndEverySpanLeftAboveIt() {
    traceManager.ensureInvocationSpan(context)
    val invocationSpan = traceManager.getCurrentSpanId(context)
    val agentSpan = traceManager.pushSpan(context, "agent:root_agent")
    val unusedLlmSpan = traceManager.pushSpan(context, "llm_request")
    val unusedToolSpan = traceManager.pushSpanRecord(context, "tool", "call-1")

    val unwound = traceManager.unwindSpan(context, "agent:root_agent")

    assertEquals(agentSpan, unwound?.spanId)
    assertEquals(invocationSpan, unwound?.parentSpanId)
    assertEquals(invocationSpan, traceManager.getCurrentSpanId(context))
  }

  @Test
  fun unwindSpan_matchesTheKindExactly() {
    traceManager.ensureInvocationSpan(context)
    val rootSpan = traceManager.pushSpan(context, "agent:root_agent")
    val unusedChildSpan = traceManager.pushSpan(context, "agent:root_agent_child")

    assertNull(traceManager.unwindSpan(context, "agent:root"))
    assertEquals(rootSpan, traceManager.unwindSpan(context, "agent:root_agent")?.spanId)
  }

  @Test
  fun unwindSpan_findsTheSpanOnAnEnclosingBranch() {
    traceManager.ensureInvocationSpan(context)
    val invocationSpan = traceManager.getCurrentSpanId(context)
    val child =
      testInvocationContext(
        agent = DummyAgent("child"),
        invocationId = "inv-1",
        branch = "root_agent.child",
      )

    val unwound = traceManager.unwindSpan(child, TraceManager.INVOCATION_KIND)

    assertEquals(invocationSpan, unwound?.spanId)
    assertNull(traceManager.getCurrentSpanId(context))
  }

  @Test
  fun pushSpanRecord_concurrentToolCallsShareTheEnclosingParent() {
    traceManager.ensureInvocationSpan(context)
    val agentSpan = traceManager.pushSpan(context, "agent:root_agent")

    val first = traceManager.pushSpanRecord(context, "tool", "call-1")
    val second = traceManager.pushSpanRecord(context, "tool", "call-2")

    assertEquals(agentSpan, first.parentSpanId)
    assertEquals(agentSpan, second.parentSpanId)
  }

  @Test
  fun getCurrentSpanAndParent_childBranchFallsBackToItsEnclosingBranch() {
    traceManager.ensureInvocationSpan(context)
    val agentSpan = traceManager.pushSpan(context, "agent:root_agent")
    val child =
      testInvocationContext(
        agent = DummyAgent("child"),
        invocationId = "inv-1",
        branch = "root_agent.child",
      )

    val childSpan = traceManager.pushSpanRecord(child, "agent:child")

    assertEquals(agentSpan, childSpan.parentSpanId)
    assertEquals(
      TraceManager.SpanIds(childSpan.spanId, agentSpan),
      traceManager.getCurrentSpanAndParent(child),
    )
    assertEquals(agentSpan, traceManager.getCurrentSpanId(context))
  }

  @Test
  fun siblingBranches_neverCloseEachOthersSpans() {
    traceManager.ensureInvocationSpan(context)
    val parentSpan = traceManager.pushSpan(context, "agent:root_agent")
    val branchA =
      testInvocationContext(
        agent = DummyAgent("a"),
        invocationId = "inv-1",
        branch = "root_agent.a",
      )
    val branchB =
      testInvocationContext(
        agent = DummyAgent("b"),
        invocationId = "inv-1",
        branch = "root_agent.b",
      )
    val agentA = traceManager.pushSpan(branchA, "agent:a")
    val llmA = traceManager.pushSpan(branchA, "llm_request")
    val agentB = traceManager.pushSpan(branchB, "agent:b")

    assertNull(traceManager.popSpan(branchB, "llm_request"))
    assertEquals(TraceManager.SpanIds(llmA, agentA), traceManager.getCurrentSpanAndParent(branchA))
    assertEquals(agentA, traceManager.unwindSpan(branchA, "agent:a")?.spanId)
    assertEquals(
      TraceManager.SpanIds(agentB, parentSpan),
      traceManager.getCurrentSpanAndParent(branchB),
    )
  }

  @Test
  fun timeToFirstToken_keepsTheFirstTokenRecorded() {
    val spanId = traceManager.pushSpan(context, "llm_request")
    assertNull(traceManager.timeToFirstToken(context, spanId))

    traceManager.recordFirstToken(context, spanId)
    val timeToFirstToken = traceManager.timeToFirstToken(context, spanId)
    traceManager.recordFirstToken(context, spanId)

    assertNotNull(timeToFirstToken)
    assertEquals(timeToFirstToken, traceManager.timeToFirstToken(context, spanId))
  }

  @Test
  fun invocationsNeverSeeEachOthersSpans() {
    val other = testInvocationContext(agent = DummyAgent("other_agent"), invocationId = "inv-2")
    traceManager.ensureInvocationSpan(context)
    val agentSpan = traceManager.pushSpan(context, "agent:root_agent")

    traceManager.ensureInvocationSpan(other)
    val otherSpan = traceManager.pushSpan(other, "agent:other_agent")
    traceManager.endInvocation(other)

    assertNotEquals(agentSpan, otherSpan)
    assertEquals(agentSpan, traceManager.getCurrentSpanId(context))
    assertNull(traceManager.getCurrentSpanId(other))
  }

  @Test
  fun getRootAgentName_isTheAgentEachInvocationStartedWith() {
    traceManager.ensureInvocationSpan(context)
    val subAgentContext =
      testInvocationContext(agent = DummyAgent("sub_agent"), invocationId = "inv-1")
    val other = testInvocationContext(agent = DummyAgent("other_agent"), invocationId = "inv-2")
    traceManager.ensureInvocationSpan(other)

    assertEquals("root_agent", traceManager.getRootAgentName(subAgentContext))
    assertEquals("other_agent", traceManager.getRootAgentName(other))
  }

  @Test
  fun acquireToolCallId_isStableForOneCallUntilReleased() {
    val toolContext = testToolContext(invocationContext = context)
    val otherCall = testToolContext(invocationContext = context)

    val id = traceManager.acquireToolCallId(toolContext)

    assertEquals(id, traceManager.acquireToolCallId(toolContext))
    assertNotEquals(id, traceManager.acquireToolCallId(otherCall))
    assertEquals(id, traceManager.releaseToolCallId(toolContext))
    assertNull(traceManager.releaseToolCallId(toolContext))
  }

  @Test
  fun endInvocation_dropsItsToolCallIds() {
    val toolContext = testToolContext(invocationContext = context)
    val id = traceManager.acquireToolCallId(toolContext)

    traceManager.endInvocation(context)

    assertNull(traceManager.releaseToolCallId(toolContext))
    assertNotEquals(id, traceManager.acquireToolCallId(toolContext))
  }

  @Test
  fun trackingMoreInvocationsThanTheCap_evictsTheLeastRecentlyUsed() {
    val capped = TraceManager(maxTrackedInvocations = 2)
    val contexts =
      (1..3).map {
        testInvocationContext(agent = DummyAgent("agent_$it"), invocationId = "inv-$it")
      }

    for (invocation in listOf(contexts[0], contexts[1], contexts[0], contexts[2])) {
      val unusedSpanId = capped.pushSpan(invocation, "agent:x")
    }

    assertNotNull(capped.getCurrentSpanId(contexts[0]))
    assertNull(capped.getCurrentSpanId(contexts[1]))
    assertNotNull(capped.getCurrentSpanId(contexts[2]))
  }

  @Test
  fun concurrentInvocations_eachPopTheirOwnSpans(): Unit = runBlocking {
    val allPopped =
      (1..20)
        .map { i ->
          async(Dispatchers.Default) {
            val invocation =
              testInvocationContext(agent = DummyAgent("agent_$i"), invocationId = "inv-$i")
            traceManager.ensureInvocationSpan(invocation)
            val spans = (1..50).map { traceManager.pushSpanRecord(invocation, "tool", "call-$it") }
            spans.withIndex().all { (index, span) ->
              traceManager.popSpan(invocation, "tool", "call-${index + 1}")?.spanId == span.spanId
            }
          }
        }
        .awaitAll()

    assertTrue(allPopped.all { it })
  }

  @Test
  fun parallelToolCallsInOneInvocation_eachPopTheirOwnSpan(): Unit = runBlocking {
    traceManager.ensureInvocationSpan(context)
    val invocationSpan = traceManager.getCurrentSpanId(context)

    val allPopped =
      (1..100)
        .map { i ->
          async(Dispatchers.Default) {
            val span = traceManager.pushSpanRecord(context, "tool", "call-$i")
            traceManager.popSpan(context, "tool", "call-$i")?.spanId == span.spanId
          }
        }
        .awaitAll()

    assertTrue(allPopped.all { it })
    assertEquals(invocationSpan, traceManager.getCurrentSpanId(context))
  }

  private companion object {
    const val TRACE_ID = "0af7651916cd43dd8448eb211c80319c"
    const val SPAN_ID = "b7ad6b7169203331"
  }
}
