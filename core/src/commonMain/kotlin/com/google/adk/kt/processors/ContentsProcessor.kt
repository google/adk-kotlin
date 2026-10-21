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
package com.google.adk.kt.processors

import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.LlmAgent.IncludeContents
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.events.Event
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Role

/**
 * A processor that populates the LLM request with the conversation history.
 *
 * <p>This processor retrieves the event history from the [CallbackContext] and converts it into the
 * [Content] format expected by the LLM. It may also perform history rewriting or compaction before
 * sending the contents to the model.
 *
 * If the agent is an [LlmAgent] with [LlmAgent.includeContents] set to [IncludeContents.NONE], only
 * the current turn is sent (the most recent user input or other-agent reply, plus any tool
 * calls/responses from that turn). Otherwise the full filtered history is sent. Non-[LlmAgent]
 * agents always receive the full filtered history. A single-turn workflow node counts as `NONE`
 * unless its agent sets [LlmAgent.includeContents] explicitly.
 */
internal class ContentsProcessor : LlmRequestProcessor {
  @OptIn(FrameworkInternalApi::class)
  override suspend fun process(
    context: InvocationContext,
    request: LlmRequest,
    emitEvent: suspend (Event) -> Unit,
  ): LlmRequest {
    val events = context.getEvents(currentInvocation = false, currentBranch = false)
    val agent = context.agent
    val singleTurnNode = context.frameworkData.singleTurnNodeAgent === agent
    val includeContents =
      when {
        agent !is LlmAgent -> IncludeContents.DEFAULT
        singleTurnNode && !agent.includeContentsExplicit -> IncludeContents.NONE
        else -> agent.includeContents
      }
    val history =
      HistoryRewriterProcessor()
        .rewrite(
          events = events,
          agentName = agent.name,
          currentBranch = context.branch,
          includeContents = includeContents,
        )

    val newContents =
      if (request.contents.isNotEmpty()) {
        val insertIndex = findInstructionInsertionIndex(history)
        buildList {
          addAll(history)
          addAll(insertIndex, request.contents)
        }
      } else {
        history
      }
    return request.copy(contents = newContents)
  }

  private fun findInstructionInsertionIndex(contents: List<Content>): Int {
    val lastNonUserIndex = contents.indexOfLast { content ->
      content.role != Role.USER || contentContainsFunctionResponse(content)
    }
    return lastNonUserIndex + 1
  }

  private fun contentContainsFunctionResponse(content: Content): Boolean {
    return content.parts.any { it.functionResponse != null }
  }
}
