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

package com.google.adk.kt.agents

import com.google.adk.kt.collections.concurrentMutableListOf
import com.google.adk.kt.events.Event
import com.google.adk.kt.ids.Uuid
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.FunctionResponse
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

/**
 * Runs tool calls outside a live run: collects a returned [Flow], keeps each emitted [Event] for
 * the caller, and returns the flow's single value as the tool response.
 */
internal class TurnBasedToolRunner {
  private val events = concurrentMutableListOf<Event>()

  /**
   * Runs [tool] and returns a non-[Flow] result as is. A [Flow] answers with its one non-null
   * value, or [Unit] when it has none, and each [Event] it emits is kept for the caller. A
   * [FunctionResponse] value contributes only its payload, since the call it answers is not the
   * tool's to choose.
   */
  suspend fun run(tool: BaseTool, context: ToolContext, args: Map<String, Any?>): Any {
    val result = tool.run(context, args)
    val stream = result as? Flow<*> ?: return result
    var value: Any? = null
    stream.collect { chunk ->
      if (chunk is Event) {
        val forCaller = chunk.fromTool(tool, context)
        events += forCaller
      } else if (chunk != null) {
        // A call has one answer, so a second value is a tool bug; nulls are skipped.
        check(value == null) {
          "A streaming tool can produce at most one result unless a live run streams it."
        }
        value = if (chunk is FunctionResponse) chunk.response else chunk
      }
    }
    return value ?: Unit
  }

  /** Calls [block] with [run], then sends [emit] the tools' events, even if [block] fails. */
  suspend fun <T> runThenEmitEvents(
    emit: suspend (Event) -> Unit,
    block: suspend (ToolRunner) -> T,
  ): T {
    val result =
      try {
        block(this::run)
      } catch (e: Exception) {
        // What a tool said before it failed still reaches the caller, then the failure does.
        if (e !is CancellationException) for (event in takeEvents()) emit(event)
        throw e
      }
    for (event in takeEvents()) emit(event)
    return result
  }

  /**
   * Takes the events tools sent the caller so far, oldest first. [runThenEmitEvents] calls this
   * only after its block joins every call, so no tool adds an event during it and no lock is
   * needed.
   */
  private fun takeEvents(): List<Event> = events.toList().also { events.clear() }

  /**
   * A copy of this event as the tool's own, on the call's branch, with an id of its own. It carries
   * no output and no long-running ids, so it can't set a node's output or pause the run.
   */
  private fun Event.fromTool(tool: BaseTool, context: ToolContext): Event {
    val callId = context.functionCallId
    val segment = if (callId.isNullOrEmpty()) tool.name else "${tool.name}@$callId"
    val parent = context.branch
    return copy(
      id = Uuid.random(),
      timestamp = Clock.System.now().toEpochMilliseconds(),
      invocationId = context.invocationId,
      author = tool.name,
      branch = if (parent.isNullOrEmpty()) segment else "$parent.$segment",
      output = null,
      nodeInfo = null,
      longRunningToolIds = emptySet(),
    )
  }
}
