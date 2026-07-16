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

package com.google.adk.kt.plugins

import com.google.adk.kt.agents.CallbackContext
import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.callbacks.CallbackChoice
import com.google.adk.kt.events.Event
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.logging.LoggerFactory
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.Content
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.representer.Representer

/**
 * A plugin that captures complete debug information to a file.
 *
 * This plugin records detailed interaction data including:
 * - LLM requests (model, system instruction, contents, tools)
 * - LLM responses (content, usage metadata, errors)
 * - Function calls with arguments
 * - Function responses with results
 * - Events yielded from the runner
 * - Session state at the end of each invocation
 *
 * The output is written as YAML for human readability. Each invocation is appended to the file as a
 * separate YAML document (separated by `---`). Unlike [LoggingPlugin], which prints truncated
 * summaries to the console, this plugin captures the data in full.
 *
 * **CAUTION**: The plugin writes raw requests / responses, including user prompts, tool arguments,
 * and session state, to disk. Be mindful of sensitive data disclosure and protect the output file
 * accordingly.
 *
 * @property outputPath Path to the output file. Defaults to `adk_debug.yaml`.
 * @property includeSessionState Whether to include a session-state snapshot for each invocation.
 * @property includeSystemInstruction Whether to include the full system instruction of requests.
 * @property name The unique name of the plugin instance.
 */
class DebugLoggingPlugin(
  private val outputPath: String = "adk_debug.yaml",
  private val includeSessionState: Boolean = true,
  private val includeSystemInstruction: Boolean = true,
  override val name: String = "debug_logging_plugin",
) : Plugin {

  private val invocationStates = ConcurrentHashMap<String, InvocationDebugState>()
  private val writeLock = Any()
  private val yaml = run {
    val dumperOptions =
      DumperOptions().apply {
        defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
        isAllowUnicode = true
        width = 120
      }
    // This plugin only serializes (dumps) data; a SafeConstructor is used so the instance never
    // deserializes arbitrary types.
    Yaml(SafeConstructor(LoaderOptions()), Representer(dumperOptions), dumperOptions)
  }

  override suspend fun onUserMessage(
    invocationContext: InvocationContext,
    userMessage: Content,
  ): Content {
    // The runner invokes onUserMessage before beforeRun, so the invocation state is created here if
    // it does not exist yet.
    ensureState(invocationContext)
    addEntry(
      invocationContext.invocationId,
      "user_message",
      data = dataOf("content" to serializeContent(userMessage)),
    )
    return userMessage
  }

  override suspend fun beforeRun(
    invocationContext: InvocationContext
  ): CallbackChoice<Unit, Content> {
    ensureState(invocationContext)
    addEntry(
      invocationContext.invocationId,
      "invocation_start",
      agentName = invocationContext.agent.name,
      data = dataOf("branch" to invocationContext.branch),
    )
    return CallbackChoice.Continue(Unit)
  }

  override suspend fun onEvent(invocationContext: InvocationContext, event: Event): Event {
    val eventData =
      dataOf(
        "event_id" to event.id,
        "author" to event.author,
        "content" to serializeContent(event.content),
        "is_final_response" to event.isFinalResponse,
        "partial" to event.partial,
        "turn_complete" to event.turnComplete,
        "branch" to event.branch,
        "actions" to serializeActions(event.actions),
        "usage_metadata" to
          event.usageMetadata?.let {
            dataOf(
              "prompt_token_count" to it.promptTokenCount,
              "candidates_token_count" to it.candidatesTokenCount,
              "total_token_count" to it.totalTokenCount,
            )
          },
        "has_grounding_metadata" to (if (event.groundingMetadata != null) true else null),
        "error_code" to event.errorCode,
        "error_message" to event.errorMessage,
        "long_running_tool_ids" to event.longRunningToolIds.ifEmpty { null }?.toList(),
      )
    addEntry(invocationContext.invocationId, "event", agentName = event.author, data = eventData)
    return event
  }

  override suspend fun afterRun(invocationContext: InvocationContext) {
    val invocationId = invocationContext.invocationId
    val state = invocationStates[invocationId]
    if (state == null) {
      logger.warn { "[$name] No debug state for invocation $invocationId, skipping write" }
      return
    }

    if (includeSessionState) {
      val session = invocationContext.session
      addEntry(
        invocationId,
        "session_state_snapshot",
        data =
          dataOf(
            "state" to safeSerialize(session.state.toMap()),
            "event_count" to session.events.size,
          ),
      )
    }
    addEntry(invocationId, "invocation_end")

    val outputData = serializeState(state)
    synchronized(writeLock) {
      try {
        val file = File(outputPath)
        file.parentFile?.mkdirs()
        // Restrict the file to the owner before writing any sensitive data, so the captured
        // requests/responses are not exposed via a permissive default umask.
        if (!file.exists()) {
          file.createNewFile()
          restrictToOwner(file)
        }
        file.appendText("---\n" + yaml.dump(outputData))
      } catch (e: IOException) {
        logger.error(e) { "[$name] Failed to write debug data" }
      }
    }
    invocationStates.remove(invocationId)
  }

  override suspend fun beforeAgent(
    context: CallbackContext
  ): CallbackChoice<EventActions, Content> {
    addEntry(
      context.invocationId,
      "agent_start",
      agentName = context.agentName,
      data = dataOf("branch" to context.branch),
    )
    return CallbackChoice.Continue(EventActions())
  }

  override suspend fun afterAgent(context: CallbackContext): CallbackChoice<Unit, Content> {
    addEntry(context.invocationId, "agent_end", agentName = context.agentName)
    return CallbackChoice.Continue(Unit)
  }

  override suspend fun beforeModel(
    context: CallbackContext,
    request: LlmRequest,
  ): CallbackChoice<LlmRequest, LlmResponse> {
    val configData = linkedMapOf<String, Any?>()
    val config = request.config
    val systemInstruction = config.systemInstruction
    if (systemInstruction != null) {
      if (includeSystemInstruction) {
        configData["system_instruction"] = serializeContent(systemInstruction)
      } else {
        configData["has_system_instruction"] = true
      }
    }
    config.temperature?.let { configData["temperature"] = it }
    config.topP?.let { configData["top_p"] = it }
    config.topK?.let { configData["top_k"] = it }
    config.maxOutputTokens?.let { configData["max_output_tokens"] = it }
    config.responseMimeType?.let { configData["response_mime_type"] = it }
    if (config.responseSchema != null) {
      configData["has_response_schema"] = true
    }

    val toolNames =
      config.tools?.flatMap { it.functionDeclarations ?: emptyList() }?.map { it.name }

    addEntry(
      context.invocationId,
      "llm_request",
      agentName = context.agentName,
      data =
        dataOf(
          "model" to request.model?.name,
          "content_count" to request.contents.size,
          "contents" to request.contents.map { serializeContent(it) },
          "tools" to toolNames?.ifEmpty { null },
          "config" to configData.ifEmpty { null },
        ),
    )
    return CallbackChoice.Continue(request)
  }

  override suspend fun afterModel(context: CallbackContext, response: LlmResponse): LlmResponse {
    addEntry(
      context.invocationId,
      "llm_response",
      agentName = context.agentName,
      data =
        dataOf(
          "content" to serializeContent(response.content),
          "partial" to response.partial,
          "error_code" to response.errorCode,
          "error_message" to response.errorMessage,
          "usage_metadata" to
            response.usageMetadata?.let {
              dataOf(
                "prompt_token_count" to it.promptTokenCount,
                "candidates_token_count" to it.candidatesTokenCount,
                "total_token_count" to it.totalTokenCount,
                "cached_content_token_count" to it.cachedContentTokenCount,
              )
            },
          "has_grounding_metadata" to (if (response.groundingMetadata != null) true else null),
          "finish_reason" to response.finishReason?.toString(),
          "model_version" to response.modelVersion,
        ),
    )
    return response
  }

  override suspend fun onModelError(
    context: CallbackContext,
    request: LlmRequest,
    error: Throwable,
  ): CallbackChoice<Unit, LlmResponse> {
    addEntry(
      context.invocationId,
      "llm_error",
      agentName = context.agentName,
      data =
        dataOf(
          "error_type" to (error::class.simpleName ?: "Unknown"),
          "error_message" to error.message,
          "model" to request.model?.name,
        ),
    )
    return CallbackChoice.Continue(Unit)
  }

  override suspend fun beforeTool(
    context: ToolContext,
    tool: BaseTool,
    args: Map<String, Any>,
  ): CallbackChoice<Map<String, Any>, Map<String, Any>> {
    addEntry(
      context.invocationContext.invocationId,
      "tool_call",
      agentName = context.invocationContext.agent.name,
      data =
        dataOf(
          "tool_name" to tool.name,
          "function_call_id" to context.functionCallId,
          "args" to safeSerialize(args),
        ),
    )
    return CallbackChoice.Continue(args)
  }

  override suspend fun afterTool(
    context: ToolContext,
    tool: BaseTool,
    args: Map<String, Any>,
    result: Map<String, Any>,
  ): Map<String, Any> {
    addEntry(
      context.invocationContext.invocationId,
      "tool_response",
      agentName = context.invocationContext.agent.name,
      data =
        dataOf(
          "tool_name" to tool.name,
          "function_call_id" to context.functionCallId,
          "result" to safeSerialize(result),
        ),
    )
    return result
  }

  override suspend fun onToolError(
    context: ToolContext,
    tool: BaseTool,
    args: Map<String, Any>,
    error: Throwable,
  ): CallbackChoice<Unit, Map<String, Any>> {
    addEntry(
      context.invocationContext.invocationId,
      "tool_error",
      agentName = context.invocationContext.agent.name,
      data =
        dataOf(
          "tool_name" to tool.name,
          "function_call_id" to context.functionCallId,
          "args" to safeSerialize(args),
          "error_type" to (error::class.simpleName ?: "Unknown"),
          "error_message" to error.message,
        ),
    )
    return CallbackChoice.Continue(Unit)
  }

  /**
   * Restricts [file] to owner-only read/write access (best effort). Some platforms/filesystems (for
   * example certain Android storage) do not honor these calls, in which case the permissions are
   * left unchanged.
   */
  private fun restrictToOwner(file: File) {
    // Revoke access for everyone, then grant read/write back to the owner only.
    file.setReadable(false, false)
    file.setWritable(false, false)
    file.setExecutable(false, false)
    file.setReadable(true, true)
    file.setWritable(true, true)
  }

  private fun ensureState(invocationContext: InvocationContext) {
    val invocationId = invocationContext.invocationId
    // computeIfAbsent initializes the state atomically, so a state created by a concurrent call for
    // the same invocation is never clobbered.
    invocationStates.computeIfAbsent(invocationId) {
      InvocationDebugState(
        invocationId = invocationId,
        sessionId = invocationContext.session.key.id,
        appName = invocationContext.session.key.appName,
        userId = invocationContext.session.key.userId,
        startTime = timestamp(),
      )
    }
  }

  private fun addEntry(
    invocationId: String,
    entryType: String,
    agentName: String? = null,
    data: Map<String, Any?> = emptyMap(),
  ) {
    val state = invocationStates[invocationId]
    if (state == null) {
      logger.warn { "[$name] No debug state for invocation $invocationId, skipping entry" }
      return
    }
    val entry =
      DebugEntry(
        timestamp = timestamp(),
        entryType = entryType,
        invocationId = invocationId,
        agentName = agentName,
        data = data,
      )
    synchronized(state) { state.entries.add(entry) }
  }

  private fun serializeState(state: InvocationDebugState): Map<String, Any?> {
    val entries = synchronized(state) { state.entries.toList() }
    return dataOf(
      "invocation_id" to state.invocationId,
      "session_id" to state.sessionId,
      "app_name" to state.appName,
      "user_id" to state.userId,
      "start_time" to state.startTime,
      "entries" to
        entries.map { entry ->
          dataOf(
            "timestamp" to entry.timestamp,
            "entry_type" to entry.entryType,
            "invocation_id" to entry.invocationId,
            "agent_name" to entry.agentName,
            "data" to entry.data.ifEmpty { null },
          )
        },
    )
  }

  private fun serializeActions(actions: EventActions): Map<String, Any?>? {
    val actionsData =
      dataOf(
        "state_delta" to actions.stateDelta.ifEmpty { null }?.let { safeSerialize(it) },
        "artifact_delta" to actions.artifactDelta.ifEmpty { null }?.let { it.toMap() },
        "transfer_to_agent" to actions.transferToAgent,
        "escalate" to (if (actions.escalate) true else null),
        "requested_tool_confirmations" to actions.requestedToolConfirmations.ifEmpty { null }?.size,
      )
    return actionsData.ifEmpty { null }
  }

  private fun serializeContent(content: Content?): Map<String, Any?>? {
    if (content == null) return null
    val parts =
      content.parts.mapNotNull { part ->
        val partData = linkedMapOf<String, Any?>()
        part.text?.let { partData["text"] = it }
        part.functionCall?.let { fc ->
          partData["function_call"] =
            dataOf("id" to fc.id, "name" to fc.name, "args" to safeSerialize(fc.args))
        }
        part.functionResponse?.let { fr ->
          partData["function_response"] =
            dataOf("id" to fr.id, "name" to fr.name, "response" to safeSerialize(fr.response))
        }
        part.inlineData?.let { blob ->
          partData["inline_data"] =
            dataOf(
              "mime_type" to blob.mimeType,
              "display_name" to blob.displayName,
              // The actual bytes are omitted to keep the file size manageable.
              "_data_omitted" to true,
            )
        }
        part.fileData?.let { fd ->
          partData["file_data"] = dataOf("file_uri" to fd.fileUri, "mime_type" to fd.mimeType)
        }
        partData.ifEmpty { null }
      }
    return dataOf("role" to content.role, "parts" to parts)
  }

  private fun safeSerialize(obj: Any?): Any? =
    when (obj) {
      null -> null
      is String,
      is Boolean,
      is Number -> obj
      is ByteArray -> "<bytes: ${obj.size} bytes>"
      is Map<*, *> -> obj.entries.associate { (k, v) -> k.toString() to safeSerialize(v) }
      is Collection<*> -> obj.map { safeSerialize(it) }
      is Array<*> -> obj.map { safeSerialize(it) }
      else ->
        try {
          obj.toString()
        } catch (e: Exception) {
          "<unserializable>"
        }
    }

  /** Builds a map that omits keys with `null` values. */
  private fun dataOf(vararg pairs: Pair<String, Any?>): Map<String, Any?> =
    linkedMapOf(*pairs).filterValues { it != null }

  private fun timestamp(): String = Clock.System.now().toString()

  private data class DebugEntry(
    val timestamp: String,
    val entryType: String,
    val invocationId: String?,
    val agentName: String?,
    val data: Map<String, Any?>,
  )

  private class InvocationDebugState(
    val invocationId: String,
    val sessionId: String?,
    val appName: String,
    val userId: String,
    val startTime: String,
    val entries: MutableList<DebugEntry> = mutableListOf(),
  )

  private companion object {
    private val logger = LoggerFactory.getLogger(DebugLoggingPlugin::class)
  }
}
