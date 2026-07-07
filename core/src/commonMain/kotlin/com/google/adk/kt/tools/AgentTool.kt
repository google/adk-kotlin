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

import com.google.adk.kt.SchemaUtils
import com.google.adk.kt.agents.BaseAgent
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.apps.App
import com.google.adk.kt.artifacts.ForwardingArtifactService
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.serialization.Json
import com.google.adk.kt.sessions.InMemorySessionService
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.sessions.State
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import kotlinx.coroutines.flow.lastOrNull

/**
 * A tool that wraps a [BaseAgent].
 *
 * This tool allows an agent to be called as a tool within a larger application. The agent's input
 * schema is used to define the tool's input parameters, and the agent's output is returned as the
 * tool's result.
 *
 * @property agent The agent to wrap.
 * @property skipSummarization Whether to skip summarization of the agent output in the parent
 *   agent.
 * @property includePlugins Whether the parent runner's plugins should be propagated to the wrapped
 *   agent's runner. When `true` (the default), the wrapped agent observes the same plugins as the
 *   parent (their plugin callbacks fire for the child invocation). When `false`, the wrapped agent
 *   runs with no plugins.
 * @property propagateGroundingMetadata Whether grounding metadata produced by the wrapped agent
 *   should be propagated back to the parent invocation. Used by the built-in-tool workaround (e.g.
 *   [GoogleSearchAgentTool]) so the parent still surfaces the sub-agent's grounding metadata.
 */
open class AgentTool(
  val agent: BaseAgent,
  val skipSummarization: Boolean = false,
  val includePlugins: Boolean = true,
  val propagateGroundingMetadata: Boolean = false,
) : BaseTool(name = agent.name, description = agent.description) {

  private val inputSchema: Schema? by lazy { getInputSchema(agent) }

  /**
   * Recursively finds the input schema from the first [LlmAgent] encountered in a depth-first
   * traversal of the agent's sub-agents. Only the first sub-agent is considered at each level.
   */
  private fun getInputSchema(agent: BaseAgent): Schema? =
    when {
      agent is LlmAgent -> agent.inputSchema
      agent.subAgents.isNotEmpty() -> getInputSchema(agent.subAgents.first())
      else -> null
    }

  override fun declaration(): FunctionDeclaration {
    return FunctionDeclaration(
      name = name,
      description = description,
      parameters =
        inputSchema
          ?: Schema(
            type = Type.OBJECT,
            properties = mapOf(REQUEST_KEY to Schema(type = Type.STRING)),
            required = listOf(REQUEST_KEY),
          ),
    )
  }

  override suspend fun run(context: ToolContext, args: Map<String, Any>): String {
    if (skipSummarization) {
      context.actions.skipSummarization = true
    }

    val content =
      if (inputSchema != null) {
        SchemaUtils.validateMapOnSchema(args, inputSchema!!, argsName = "Input").getOrThrow()
        Content(parts = listOf(Part(text = Json.toJsonString(args))))
      } else {
        val request =
          args[REQUEST_KEY] ?: throw IllegalArgumentException("Missing '$REQUEST_KEY' argument")
        Content(parts = listOf(Part(text = request.toString())))
      }

    // Run the wrapped agent in an isolated session so that it does not see the
    // parent's history and does not append its events to the parent session.
    val childAppName = context.invocationContext.session.key.appName
    val childSessionService = InMemorySessionService()
    // When [includePlugins] is true (the default), reuse the parent's plugins so the wrapped
    // agent's runner fires the same plugin callbacks as the parent.
    val childPlugins =
      if (includePlugins) context.invocationContext.pluginManager.plugins else emptyList()
    val runner =
      InMemoryRunner(
        app = App(appName = childAppName, rootAgent = agent, plugins = childPlugins),
        sessionService = childSessionService,
        // Share artifacts bidirectionally with the parent invocation: the wrapped agent reads
        // and writes through the parent's [ArtifactService], and each save is recorded into the
        // parent [ToolContext]'s `actions.artifactDelta`. Mirrors Python ADK 1.x
        // `ForwardingArtifactService(tool_context)`.
        artifactService = ForwardingArtifactService(context),
        skipClosingPlugins = includePlugins && childPlugins.isNotEmpty(),
      )

    // Seed the child session from the parent's merged session state, excluding ADK-internal
    // ("_adk") and temporary ("temp:") keys. Temp state is invocation-scoped and must not cross
    // into the child session.
    val parentState =
      context.context.state
        .filterKeys { key -> !key.startsWith("_adk") && !key.startsWith(State.TEMP_PREFIX) }
        .takeIf { it.isNotEmpty() }

    val childSession =
      childSessionService.createSession(
        key =
          SessionKey(
            appName = childAppName,
            userId = context.invocationContext.session.key.userId,
            id = null,
          ),
        state = parentState,
      )

    val lastEvent =
      runner
        .runAsync(
          userId = childSession.key.userId,
          sessionId = childSession.key.id!!,
          newMessage = content,
        )
        .lastOrNull()

    if (lastEvent != null) {
      // Propagate non-internal state back to the parent context. Artifact deltas are recorded
      // per-save by [ForwardingArtifactService] into `context.actions.artifactDelta`, so no
      // post-hoc merge is needed (and the child's last-event delta would refer to its throwaway
      // session anyway).
      context.actions.stateDelta.putAll(lastEvent.actions.stateDelta)

      if (propagateGroundingMetadata) {
        lastEvent.groundingMetadata?.let {
          context.actions.stateDelta[GROUNDING_METADATA_TEMP_KEY] = it
        }
      }

      val text =
        lastEvent.content
          ?.parts
          ?.filter { it.thought != true }
          ?.joinToString("\n") { it.text ?: "" } ?: ""

      return text
    }

    return ""
  }

  companion object {
    private const val REQUEST_KEY = "request"

    /** Temp state key under which propagated grounding metadata is stored. */
    private const val GROUNDING_METADATA_TEMP_KEY = "temp:_adk_grounding_metadata"
  }
}
