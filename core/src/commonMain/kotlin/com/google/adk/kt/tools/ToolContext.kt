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

package com.google.adk.kt.tools

import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.agents.ReadonlyContext
import com.google.adk.kt.agents.toReadonlyContext
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.events.ToolConfirmation
import com.google.adk.kt.types.Part

/** ToolContext provides a structured context for executing tools or functions. */
class ToolContext(
  val invocationContext: InvocationContext,
  val actions: EventActions = EventActions(),
  override val functionCallId: String? = null,
  val toolConfirmation: ToolConfirmation? = null,
  override val eventId: String? = null,
) : ReadonlyToolContext {
  override val context: ReadonlyContext
    get() = invocationContext.toReadonlyContext()

  /**
   * Requests the current LLM agent to stop after the current step completes.
   *
   * Scope is exactly this LLM agent: the per-step loop in
   * [com.google.adk.kt.agents.LlmAgent.executeTurns] exits after the current step, and this agent's
   * remaining after-agent callbacks (the checks at the end of
   * [com.google.adk.kt.agents.BaseAgent.runAsync]) are skipped.
   *
   * The flag does NOT propagate to any other agent. Enclosing workflow agents
   * ([com.google.adk.kt.agents.SequentialAgent], [com.google.adk.kt.agents.LoopAgent],
   * [com.google.adk.kt.agents.ParallelAgent]) do not read [InvocationContext.isEndOfInvocation],
   * and each child agent runs under its own context copy produced by
   * `InvocationContext.forAgent(...)` / branching, so the mutation never reaches the parent's
   * context. In `Sequential[A, B]`, a tool in `A` calling `endInvocation()` still lets `B` run.
   * This matches Python and Java ADK. To break out of a [com.google.adk.kt.agents.LoopAgent], set
   * `actions.escalate = true` instead.
   *
   * Mirrors Python ADK's `tool_context._invocation_context.end_invocation = True` and Java ADK's
   * `toolContext.actions().setEndInvocation(true)`. Tools may equivalently set `actions.endOfAgent
   * = true`; both paths cause [com.google.adk.kt.agents.LlmAgent.executeTurns] to exit after the
   * current step.
   */
  fun endInvocation() {
    invocationContext.isEndOfInvocation = true
  }

  fun requestConfirmation(hint: String? = null, payload: Any? = null) {
    if (functionCallId == null) {
      throw IllegalStateException("functionCallId is not set.")
    }
    actions.requestedToolConfirmations[functionCallId] =
      ToolConfirmation(hint = hint, confirmed = false, payload = payload)
  }

  override suspend fun listArtifacts(): List<String> {
    val service = invocationContext.artifactService ?: return emptyList()
    return service.listArtifactKeys(invocationContext.session.key)
  }

  override suspend fun loadArtifact(name: String, version: Int?): Part? {
    val service = invocationContext.artifactService ?: return null
    return service.loadArtifact(invocationContext.session.key, name, version)
  }

  suspend fun saveArtifact(name: String, artifact: Part): Int {
    val service =
      invocationContext.artifactService
        ?: throw IllegalStateException(
          "artifactService not configured on parent invocation; cannot save artifact '$name'."
        )
    val version = service.saveArtifact(invocationContext.session.key, name, artifact)
    actions.artifactDelta[name] = version
    return version
  }
}
