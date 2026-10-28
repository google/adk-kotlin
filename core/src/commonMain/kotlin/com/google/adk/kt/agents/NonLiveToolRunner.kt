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

import com.google.adk.kt.events.Event
import com.google.adk.kt.ids.Uuid
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.FunctionResponse
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Runs tool calls outside a live run: collects a returned [Flow], sends each emitted [Event] to the
 * caller as it arrives, and returns the flow's single value as the tool response.
 */
internal class NonLiveToolRunner {
  private val events = Channel<Event>(Channel.UNLIMITED)

  /**
   * Runs [tool] and returns a non-[Flow] result as is. A [Flow] answers with its one non-null
   * value, or [Unit] when it has none (a second value fails the call, whereas a live run streams
   * each value); each [Event] it emits goes to the caller as it arrives, and the event's output, or
   * its content when it is the message output, counts as that value. A [FunctionResponse] value
   * contributes only its payload, since the call it answers is not the tool's to choose.
   */
  suspend fun run(tool: BaseTool, context: ToolContext, args: Map<String, Any?>): Any {
    val result = tool.run(context, args)
    val stream = result as? Flow<*> ?: return result
    // A SharedFlow never completes, so waiting for its one value would hang the call.
    check(stream !is SharedFlow<*>) {
      "A tool returned a SharedFlow, which never completes; outside a live run a tool's Flow " +
        "must complete."
    }
    var value: Any? = null
    fun answerWith(answer: Any) {
      // A call has one answer, so a second value is a tool bug.
      check(value == null) {
        "A streaming tool can produce at most one result unless a live run streams it."
      }
      value = if (answer is FunctionResponse) answer.response else answer
    }
    stream.collect { chunk ->
      when {
        chunk is Event -> {
          // A transfer is an agent's to request: the tool's own event that asks fails the call, and
          // fromTool drops one that a relayed event carries.
          val ownTransfer =
            chunk.actions.transferToAgent != null &&
              (chunk.author.isEmpty() || chunk.author == tool.name)
          check(!ownTransfer) { "Only agents can request an agent transfer." }
          val messageAsOutput = chunk.nodeInfo?.messageAsOutput == true && !chunk.partial
          val output = chunk.output ?: if (messageAsOutput) chunk.content else null
          // Checked first, so an event that breaks the call never reaches the caller.
          if (output != null) answerWith(output)
          events.send(chunk.fromTool(tool, context))
        }
        // Nulls are skipped; any other chunk is the flow's value.
        chunk != null -> answerWith(chunk)
      }
    }
    return value ?: Unit
  }

  /**
   * Calls [block] with [run] in a child coroutine and sends [emit] each tool event as it arrives,
   * on this coroutine; a failure of [block] is rethrown once its events are out.
   */
  suspend fun <T> runEmittingEvents(
    emit: suspend (Event) -> Unit,
    block: suspend (ToolRunner) -> T,
  ): T = coroutineScope {
    // The block runs in this child while this coroutine only drains the channel; the session
    // stores the tools touch are thread-safe.
    @Suppress("UnsafeCoroutineCrossing")
    val work = async {
      // The block keeps its own failure, so what a tool said before it failed still goes out.
      try {
        Result.success(block(this@NonLiveToolRunner::run))
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Result.failure(e)
      } finally {
        events.close()
      }
    }
    for (event in events) emit(event)
    work.await().getOrThrow()
  }

  /**
   * A copy of this event as the tool's own, on the call's branch, with an id of its own. It carries
   * no output, route, transfer or long-running ids, so it can't set a node's output, pick a
   * workflow edge, hand the run to another agent or pause it.
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
      actions = actions.copy(route = null, transferToAgent = null),
      longRunningToolIds = emptySet(),
    )
  }
}
