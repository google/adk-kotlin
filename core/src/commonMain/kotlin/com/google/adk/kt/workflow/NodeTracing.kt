/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:OptIn(ExperimentalWorkflowApi::class)

package com.google.adk.kt.workflow

import com.google.adk.kt.agents.BaseAgent
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.telemetry.Span
import com.google.adk.kt.telemetry.Telemetry
import com.google.adk.kt.telemetry.TelemetryAttributes
import com.google.adk.kt.telemetry.failureDescription
import com.google.adk.kt.telemetry.isFailure
import com.google.adk.kt.telemetry.recordFailure
import com.google.adk.kt.telemetry.tracedFlow
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * Runs [block] in the span Python ADK opens for [node]: none for an agent, which opens its own
 * `invoke_agent` span; `invoke_workflow` for a workflow; `invoke_node` for any other node. The span
 * records an exception [block] throws, or else the failure [failure] reads from its result, since a
 * workflow hands a child's failure back as data. [eventIds] is read when [block] returns or pauses.
 *
 * @param nested whether a workflow encloses [node] in the node tree. A workflow started in another
 *   workflow's span, such as the root of a runner a node started, is flagged nested even when this
 *   is false.
 * @param statusDescription describes the failure [failure] reads for the span's ERROR status; by
 *   default the failure's message, as Python ADK describes a nested workflow's failed child.
 */
internal suspend fun <T> traceNode(
  node: Node,
  conversationId: String?,
  nested: Boolean,
  eventIds: List<String>,
  failure: (T) -> Throwable?,
  statusDescription: (Throwable) -> String = { it.message.orEmpty() },
  block: suspend () -> T,
): T {
  if (node is BaseAgent) return block()
  val isWorkflow = node is Workflow
  val operation =
    if (isWorkflow) TelemetryAttributes.OPERATION_INVOKE_WORKFLOW
    else TelemetryAttributes.OPERATION_INVOKE_NODE
  val builder = Telemetry.tracer.spanBuilder("$operation ${node.name}")
  builder[TelemetryAttributes.GEN_AI_OPERATION_NAME] = operation
  if (conversationId != null) builder[TelemetryAttributes.GEN_AI_CONVERSATION_ID] = conversationId
  if (isWorkflow) {
    builder[TelemetryAttributes.GEN_AI_WORKFLOW_NAME] = node.name
    if (nested || currentCoroutineContext()[InWorkflow] != null) {
      builder[TelemetryAttributes.GEN_AI_WORKFLOW_NESTED] = true
    }
  }
  val span = builder.startSpan()
  try {
    var outcome: Result<T>? = null
    val result =
      try {
        val workflowMarker = if (isWorkflow) InWorkflow else EmptyCoroutineContext
        withContext(Telemetry.tracer.contextWithSpan(span).asContextElement() + workflowMarker) {
          val value = block()
          outcome = Result.success(value)
          value
        }
      } catch (e: CancellationException) {
        // withContext throws on exit once cancelled; a node that swallowed it still returns.
        (outcome ?: throw e).getOrThrow()
      }
    failure(result)?.let { span.recordFailure(it, statusDescription(it)) }
    span.recordAssociatedEvents(eventIds)
    return result
  } catch (e: Throwable) {
    // A node that asks for input pauses rather than fails.
    if (isFailure(e)) span.recordFailure(e)
    if (e is NodeInterruptedException) span.recordAssociatedEvents(eventIds)
    throw e
  } finally {
    span.end()
  }
}

/**
 * Runs this flow, a runner's turn of its root [workflow], in the `invoke_workflow` span Python
 * ADK's schema v1 runner opens for a workflow root, so the run-level callbacks and the work after
 * the root run in it. The span records a failure of this flow but not of its collector.
 */
internal fun <T> Flow<T>.traceWorkflowEntrypoint(
  workflow: Workflow,
  conversationId: String,
): Flow<T> {
  val upstream = this
  return flow {
    val nested = currentCoroutineContext()[InWorkflow] != null
    var collectorFailed = false
    val operation = TelemetryAttributes.OPERATION_INVOKE_WORKFLOW
    val traced =
      tracedFlow<T>(
        "$operation ${workflow.name}",
        builder = {
          this[TelemetryAttributes.GEN_AI_OPERATION_NAME] = operation
          this[TelemetryAttributes.GEN_AI_CONVERSATION_ID] = conversationId
          this[TelemetryAttributes.GEN_AI_WORKFLOW_NAME] = workflow.name
          if (nested) this[TelemetryAttributes.GEN_AI_WORKFLOW_NESTED] = true
        },
        isOwnFailure = { !collectorFailed },
      ) { _, spanContext ->
        upstream.flowOn(spanContext + InWorkflow + WorkflowEntrypoint(workflow)).collect {
          try {
            emit(it)
          } catch (e: Throwable) {
            collectorFailed = true
            throw e
          }
        }
      }
    emitAll(traced)
  }
}

/**
 * Runs [block], the run of the root [workflow], in the span its runner opened with
 * [traceWorkflowEntrypoint]. Without one, runs it in a span of its own that lists [eventIds] and
 * records [failure], as [traceNode] does.
 */
internal suspend fun <T> traceRootWorkflow(
  workflow: Workflow,
  conversationId: String?,
  eventIds: List<String>,
  failure: (T) -> Throwable?,
  block: suspend () -> T,
): T {
  // The entrypoint span records the failure runRoot rethrows; a root workflow lists no events.
  if (currentCoroutineContext()[WorkflowEntrypoint]?.workflow === workflow) return block()
  // Python raises the root's failure through its span rather than handing it back as data.
  return traceNode(
    workflow,
    conversationId,
    nested = false,
    eventIds,
    failure,
    statusDescription = ::failureDescription,
    block,
  )
}

/**
 * Lists [eventIds] on this span once its node returns or pauses, as Python ADK does; an attempt
 * that raises lists none.
 */
private fun Span.recordAssociatedEvents(eventIds: List<String>) {
  if (eventIds.isNotEmpty()) {
    this[TelemetryAttributes.GCP_VERTEX_AGENT_ASSOCIATED_EVENT_IDS] = eventIds.toList()
  }
}

/** Marks the root [workflow] whose `invoke_workflow` span its runner opened, so it runs in it. */
private class WorkflowEntrypoint(val workflow: Workflow) : CoroutineContext.Element {
  override val key: CoroutineContext.Key<*>
    get() = Key

  companion object Key : CoroutineContext.Key<WorkflowEntrypoint>
}

/** Marks code running in a workflow's span, so a workflow started there is flagged nested. */
private object InWorkflow : CoroutineContext.Element, CoroutineContext.Key<InWorkflow> {
  override val key: CoroutineContext.Key<*>
    get() = this
}
